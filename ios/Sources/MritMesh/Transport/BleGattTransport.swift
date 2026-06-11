import Foundation
import CoreBluetooth

// MARK: - Constants (PROTOCOL.md §11)

/// GATT bridge service UUID — distinct from any discovery-only UUID Android may advertise.
let bleGattServiceUUID = CBUUID(string: "4d524954-0010-1000-8000-00805f9b34fb")

/// Single read/write/notify characteristic carrying fragmented MMP packets.
let bleGattDataCharacteristicUUID = CBUUID(string: "4d524954-0011-1000-8000-00805f9b34fb")

/// Fragment header flags (PROTOCOL.md §11.3)
private let bleFlagFirst: UInt8 = 0x01
private let bleFlagLast:  UInt8 = 0x02

/// Fallback fragment payload size if the platform reports something smaller
/// than the default ATT MTU (23) minus the 3-byte ATT header allows.
private let bleDefaultMaxFragment = 20

/// Largest possible encoded MeshPacket: 69-byte header + 65535-byte payload.
private let bleMaxTotalLength = MeshPacket.headerSize + MeshPacket.maxPayloadSize

private let bleRetryDelay: TimeInterval = 0.05

// MARK: - BleGattTransport

/// BleGattTransport — phone-to-phone data bridge over Bluetooth LE GATT (Phase 6).
///
/// Role: a real, OS-agnostic transport that lets an iOS device exchange MMP
/// packets with an Android device (and with other iOS devices), bridging the
/// gap left by `MultipeerTransport` (iOS-only) and Android's WiFi Direct
/// transport. Full wire-format spec: PROTOCOL.md §11 "BLE GATT transport bridge".
///
/// Every node runs BOTH GATT roles simultaneously:
///   - **Peripheral** (`CBPeripheralManager`) — advertises [bleGattServiceUUID],
///     accepts writes to [bleGattDataCharacteristicUUID], and sends notifications back.
///   - **Central** (`CBCentralManager`) — scans for [bleGattServiceUUID], connects,
///     subscribes to notifications, and writes outgoing data.
///
/// One GATT connection between central X and peripheral Y is a single
/// bidirectional pipe: X→Y via characteristic writes, Y→X via notifications,
/// both on the same characteristic.
///
/// Large MeshPackets are split into small fragments sized to the negotiated
/// ATT MTU (PROTOCOL.md §11.3) and reassembled on the other side.
///
/// Handshake (PROTOCOL.md §11.4):
///   Central enables notifications → central sends DISCOVER (its public key) →
///   peripheral replies with its own DISCOVER. Both sides report a
///   [PeerHandshake] with `bleAddress` set so `MeshNode` can register the peer
///   and route to it as a fallback when no Multipeer/WiFi Direct link exists.
public class BleGattTransport: NSObject {

    private let ourId:      MeshID
    private let keyManager: KeyManager

    private var centralManager:    CBCentralManager!
    private var peripheralManager: CBPeripheralManager!
    private var dataCharacteristic: CBMutableCharacteristic?

    private var isRunning = false

    /// GATT connections where we are the CENTRAL (we initiated via connect). Keyed by `CBPeripheral.identifier.uuidString`.
    private var centralLinks = [String: BleLink]()

    /// GATT connections where we are the PERIPHERAL (a remote central subscribed to us). Keyed by `CBCentral.identifier.uuidString`.
    private var serverLinks = [String: BleLink]()

    /// Peripheral identifiers for which a `connect()` call is in flight (de-dupes scan results).
    private var connecting = Set<String>()

    private let lock = NSLock()

    // MARK: - Callbacks

    /// Called when a non-DISCOVER data packet arrives.
    var onPacketReceived: ((MeshPacket) -> Void)?

    /// Called when a handshake completes and a new peer is identified.
    var onPeerHandshake: ((PeerHandshake) -> Void)?

    // MARK: - Init

    public init(ourId: MeshID, keyManager: KeyManager) {
        self.ourId      = ourId
        self.keyManager = keyManager
        super.init()
        centralManager    = CBCentralManager(delegate: self, queue: nil)
        peripheralManager = CBPeripheralManager(delegate: self, queue: nil)
    }

    // MARK: - Lifecycle

    public func start() {
        isRunning = true
        if centralManager.state == .poweredOn {
            startScanning()
        }
        if peripheralManager.state == .poweredOn {
            setupGattServerAndAdvertise()
        }
    }

    public func stop() {
        isRunning = false

        if centralManager.state == .poweredOn {
            centralManager.stopScan()
        }
        if peripheralManager.state == .poweredOn {
            peripheralManager.stopAdvertising()
            peripheralManager.removeAllServices()
        }
        dataCharacteristic = nil

        lock.lock()
        let toDisconnect = Array(centralLinks.values)
        centralLinks.removeAll()
        serverLinks.removeAll()
        connecting.removeAll()
        lock.unlock()

        toDisconnect.forEach { link in
            if let peripheral = link.peripheral {
                centralManager.cancelPeripheralConnection(peripheral)
            }
        }
    }

    // MARK: - GATT server (peripheral role)

    private func setupGattServerAndAdvertise() {
        let characteristic = CBMutableCharacteristic(
            type:        bleGattDataCharacteristicUUID,
            properties:  [.write, .notify],
            value:       nil,
            permissions: [.readable, .writeable]
        )
        dataCharacteristic = characteristic

        let service = CBMutableService(type: bleGattServiceUUID, primary: true)
        service.characteristics = [characteristic]
        peripheralManager.add(service)
    }

    // MARK: - Scanning (central role)

    private func startScanning() {
        centralManager.scanForPeripherals(
            withServices: [bleGattServiceUUID],
            options: [CBCentralManagerScanOptionAllowDuplicatesKey: false]
        )
    }

    // MARK: - Sending / fragmentation

    /// Send a MeshPacket to the BLE peer at `address` (a `PeerInfo.bleAddress` value).
    public func send(_ packet: MeshPacket, to address: String) {
        lock.lock()
        let link = centralLinks[address] ?? serverLinks[address]
        lock.unlock()
        guard let link = link else {
            print("[BleGattTransport] send: no BLE GATT link to \(address)")
            return
        }
        enqueue(link, packet)
    }

    private func enqueue(_ link: BleLink, _ packet: MeshPacket) {
        let encoded     = MMPCodec.encode(packet)
        let maxFragment = maxFragmentSize(for: link)
        let fragments   = fragmentMessage(encoded, maxFragment: maxFragment)

        lock.lock()
        link.outQueue.append(contentsOf: fragments)
        lock.unlock()

        sendNextFragment(link)
    }

    private func maxFragmentSize(for link: BleLink) -> Int {
        switch link.role {
        case .central:
            guard let peripheral = link.peripheral else { return bleDefaultMaxFragment }
            return max(peripheral.maximumWriteValueLength(for: .withResponse), bleDefaultMaxFragment)
        case .peripheral:
            guard let central = link.central else { return bleDefaultMaxFragment }
            return max(central.maximumUpdateValueLength, bleDefaultMaxFragment)
        }
    }

    /// Pop and dispatch the next queued fragment for `link`, if no write is currently in flight.
    private func sendNextFragment(_ link: BleLink) {
        var fragment: Data?
        lock.lock()
        if !link.writeInFlight, !link.outQueue.isEmpty {
            fragment = link.outQueue.removeFirst()
            link.writeInFlight = true
        }
        lock.unlock()

        guard let fragment = fragment else { return }

        let dispatched: Bool
        switch link.role {
        case .central:    dispatched = dispatchCentralWrite(link, fragment)
        case .peripheral: dispatched = dispatchPeripheralNotify(link, fragment)
        }

        if !dispatched {
            lock.lock()
            link.outQueue.insert(fragment, at: 0)
            link.writeInFlight = false
            lock.unlock()

            DispatchQueue.main.asyncAfter(deadline: .now() + bleRetryDelay) { [weak self] in
                self?.sendNextFragment(link)
            }
        }
    }

    /// Called once a fragment write/notify has completed — clears the in-flight
    /// flag and (asynchronously, to avoid deep recursion on long messages)
    /// dispatches the next queued fragment.
    private func onFragmentSent(_ link: BleLink) {
        lock.lock()
        link.writeInFlight = false
        lock.unlock()

        DispatchQueue.main.async { [weak self] in
            self?.sendNextFragment(link)
        }
    }

    private func dispatchCentralWrite(_ link: BleLink, _ fragment: Data) -> Bool {
        guard let peripheral = link.peripheral, let characteristic = link.characteristic,
              peripheral.state == .connected else { return false }
        peripheral.writeValue(fragment, for: characteristic, type: .withResponse)
        return true   // completion (success or failure) reported via didWriteValueFor
    }

    private func dispatchPeripheralNotify(_ link: BleLink, _ fragment: Data) -> Bool {
        guard let central = link.central, let characteristic = dataCharacteristic else { return false }
        let sent = peripheralManager.updateValue(fragment, for: characteristic, onSubscribedCentrals: [central])
        if sent {
            onFragmentSent(link)
        }
        return sent
    }

    // MARK: - Receiving / reassembly

    private func handleIncomingFragment(_ link: BleLink, _ fragment: Data) {
        guard let complete = link.reassembly.accept(fragment) else { return }
        guard let packet = MMPCodec.decode(complete) else {
            print("[BleGattTransport] Dropping malformed reassembled packet from \(link.address)")
            return
        }

        if packet.type == .discover {
            link.meshId = packet.srcId
            if link.role == .peripheral {
                // We're PERIPHERAL — reply with our own DISCOVER (PROTOCOL.md §11.4)
                sendHandshake(link)
            }
            onPeerHandshake?(PeerHandshake(
                meshId:         packet.srcId,
                bleAddress:     link.address,
                publicKeyBytes: packet.payload
            ))
        } else {
            onPacketReceived?(packet)
        }
    }

    // MARK: - Handshake

    private func buildDiscoverPacket() -> MeshPacket {
        MeshPacket(type: .discover, srcId: ourId, dstId: .broadcast, ttl: 1, payload: keyManager.publicKeyBytes)
    }

    private func sendHandshake(_ link: BleLink) {
        guard !link.handshakeSent else { return }
        link.handshakeSent = true
        enqueue(link, buildDiscoverPacket())
    }

    // MARK: - Data model

    /// Result of a completed BLE GATT handshake.
    /// Mirrors `MultipeerTransport.PeerHandshake`, but carries `bleAddress`
    /// instead of relying on an `MCPeerID`.
    public struct PeerHandshake {
        public let meshId:         MeshID
        public let bleAddress:     String
        public let publicKeyBytes: Data
    }
}

// MARK: - CBCentralManagerDelegate

extension BleGattTransport: CBCentralManagerDelegate {

    public func centralManagerDidUpdateState(_ central: CBCentralManager) {
        if central.state == .poweredOn && isRunning {
            startScanning()
        }
    }

    public func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral,
                                advertisementData: [String: Any], rssi RSSI: NSNumber) {
        let address = peripheral.identifier.uuidString

        lock.lock()
        let alreadyKnown = centralLinks[address] != nil || connecting.contains(address)
        if !alreadyKnown { connecting.insert(address) }
        lock.unlock()

        guard !alreadyKnown else { return }

        peripheral.delegate = self
        centralManager.connect(peripheral, options: nil)
    }

    public func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        let address = peripheral.identifier.uuidString

        lock.lock()
        connecting.remove(address)
        centralLinks[address] = BleLink(address: address, role: .central, peripheral: peripheral)
        lock.unlock()

        peripheral.discoverServices([bleGattServiceUUID])
    }

    public func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        let address = peripheral.identifier.uuidString
        lock.lock()
        connecting.remove(address)
        centralLinks.removeValue(forKey: address)
        lock.unlock()
    }

    public func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        let address = peripheral.identifier.uuidString
        lock.lock()
        connecting.remove(address)
        centralLinks.removeValue(forKey: address)
        lock.unlock()
    }
}

// MARK: - CBPeripheralDelegate (we are CENTRAL — `peripheral` is the remote GATT server)

extension BleGattTransport: CBPeripheralDelegate {

    public func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard error == nil, let services = peripheral.services else { return }
        for service in services where service.uuid == bleGattServiceUUID {
            peripheral.discoverCharacteristics([bleGattDataCharacteristicUUID], for: service)
        }
    }

    public func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        guard error == nil, let characteristics = service.characteristics else { return }
        let address = peripheral.identifier.uuidString

        for characteristic in characteristics where characteristic.uuid == bleGattDataCharacteristicUUID {
            lock.lock()
            centralLinks[address]?.characteristic = characteristic
            lock.unlock()

            peripheral.setNotifyValue(true, for: characteristic)
        }
    }

    public func peripheral(_ peripheral: CBPeripheral, didUpdateNotificationStateFor characteristic: CBCharacteristic, error: Error?) {
        guard error == nil, characteristic.isNotifying else { return }
        let address = peripheral.identifier.uuidString

        lock.lock()
        let link = centralLinks[address]
        link?.notificationsEnabled = true
        lock.unlock()

        if let link = link {
            // We're CENTRAL — initiate the handshake (PROTOCOL.md §11.4)
            sendHandshake(link)
        }
    }

    public func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        guard error == nil, let data = characteristic.value else { return }
        let address = peripheral.identifier.uuidString

        lock.lock()
        let link = centralLinks[address]
        lock.unlock()

        guard let link = link else { return }
        handleIncomingFragment(link, data)
    }

    public func peripheral(_ peripheral: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic, error: Error?) {
        let address = peripheral.identifier.uuidString
        lock.lock()
        let link = centralLinks[address]
        lock.unlock()

        guard let link = link else { return }
        if let error = error {
            print("[BleGattTransport] write error to \(address): \(error.localizedDescription)")
        }
        onFragmentSent(link)
    }
}

// MARK: - CBPeripheralManagerDelegate (we are PERIPHERAL — `central` is the remote GATT client)

extension BleGattTransport: CBPeripheralManagerDelegate {

    public func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        if peripheral.state == .poweredOn && isRunning {
            setupGattServerAndAdvertise()
        }
    }

    public func peripheralManager(_ peripheral: CBPeripheralManager, didAdd service: CBService, error: Error?) {
        if let error = error {
            print("[BleGattTransport] Failed to add GATT service: \(error.localizedDescription)")
            return
        }
        peripheralManager.startAdvertising([
            CBAdvertisementDataServiceUUIDsKey: [bleGattServiceUUID]
        ])
    }

    public func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral, didSubscribeTo characteristic: CBCharacteristic) {
        guard characteristic.uuid == bleGattDataCharacteristicUUID else { return }
        let address = central.identifier.uuidString

        lock.lock()
        let link = serverLinks[address] ?? BleLink(address: address, role: .peripheral, central: central)
        link.notificationsEnabled = true
        serverLinks[address] = link
        lock.unlock()

        // Per PROTOCOL.md §11.4, the CENTRAL initiates the handshake — we wait for its DISCOVER.
    }

    public func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral, didUnsubscribeFrom characteristic: CBCharacteristic) {
        let address = central.identifier.uuidString
        lock.lock()
        serverLinks.removeValue(forKey: address)
        lock.unlock()
    }

    public func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveWrite requests: [CBATTRequest]) {
        for request in requests {
            guard request.characteristic.uuid == bleGattDataCharacteristicUUID, let value = request.value else {
                peripheralManager.respond(to: request, withResult: .requestNotSupported)
                continue
            }

            let address = request.central.identifier.uuidString
            lock.lock()
            let link = serverLinks[address] ?? BleLink(address: address, role: .peripheral, central: request.central)
            serverLinks[address] = link
            lock.unlock()

            handleIncomingFragment(link, value)
            peripheralManager.respond(to: request, withResult: .success)
        }
    }

    public func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveRead request: CBATTRequest) {
        guard request.characteristic.uuid == bleGattDataCharacteristicUUID, request.offset == 0 else {
            peripheralManager.respond(to: request, withResult: .invalidOffset)
            return
        }
        request.value = Data()
        peripheralManager.respond(to: request, withResult: .success)
    }

    public func peripheralManagerIsReady(toUpdateSubscribers peripheral: CBPeripheralManager) {
        lock.lock()
        let links = Array(serverLinks.values)
        lock.unlock()
        links.forEach { sendNextFragment($0) }
    }
}

// MARK: - Internal types

private enum BleRole {
    case central
    case peripheral
}

/// State for one BLE GATT connection (either CENTRAL or PERIPHERAL role).
private final class BleLink {
    let address: String
    let role: BleRole

    /// Set when `role == .central` — the remote GATT server we connected to.
    var peripheral: CBPeripheral?
    /// Set when `role == .central` once discovered — the remote data characteristic.
    var characteristic: CBCharacteristic?

    /// Set when `role == .peripheral` — the remote GATT client subscribed to us.
    var central: CBCentral?

    var notificationsEnabled = false
    var handshakeSent = false
    var meshId: MeshID?

    let reassembly = BleFragmentReassembler()
    var outQueue: [Data] = []
    var writeInFlight = false

    init(address: String, role: BleRole, peripheral: CBPeripheral? = nil, central: CBCentral? = nil) {
        self.address    = address
        self.role       = role
        self.peripheral = peripheral
        self.central    = central
    }
}

/// Split `encoded` into ATT-sized fragments per PROTOCOL.md §11.3.
///
/// Fragment layout:
///   byte 0      : FLAGS (bit0=FIRST, bit1=LAST)
///   [bytes 1-4] : TOTAL_LENGTH uint32 BE — present only if FIRST set
///   remainder   : raw slice of `encoded`
private func fragmentMessage(_ encoded: Data, maxFragment: Int) -> [Data] {
    let maxFrag = max(maxFragment, bleDefaultMaxFragment)
    let bytes   = [UInt8](encoded)
    let total   = bytes.count

    var fragments: [Data] = []
    var offset = 0

    repeat {
        let isFirst = offset == 0
        let headerSize = isFirst ? 5 : 1
        let payloadCapacity = max(maxFrag - headerSize, 1)
        let remaining = total - offset
        let chunkSize = min(remaining, payloadCapacity)
        let isLast = (offset + chunkSize) >= total

        var flags: UInt8 = 0
        if isFirst { flags |= bleFlagFirst }
        if isLast  { flags |= bleFlagLast }

        var fragment = [UInt8]()
        fragment.reserveCapacity(headerSize + chunkSize)
        fragment.append(flags)
        if isFirst {
            let totalU32 = UInt32(total)
            fragment.append(UInt8((totalU32 >> 24) & 0xFF))
            fragment.append(UInt8((totalU32 >> 16) & 0xFF))
            fragment.append(UInt8((totalU32 >> 8)  & 0xFF))
            fragment.append(UInt8(totalU32 & 0xFF))
        }
        if chunkSize > 0 {
            fragment.append(contentsOf: bytes[offset..<(offset + chunkSize)])
        }
        fragments.append(Data(fragment))

        offset += chunkSize
    } while offset < total

    return fragments
}

/// Reassembles a single stream of length-prefixed fragments (PROTOCOL.md §11.3)
/// back into one encoded MeshPacket. One instance per `BleLink` — connections
/// are 1:1, so there's never more than one in-flight message per direction at a time.
private final class BleFragmentReassembler {
    private var buffer: [UInt8]?
    private var totalLength: Int = -1

    /// Feed one fragment. Returns the fully reassembled packet bytes once the
    /// LAST fragment is received, or nil while more fragments are still needed
    /// (or the input was invalid and the buffer was reset).
    func accept(_ fragment: Data) -> Data? {
        let bytes = [UInt8](fragment)
        guard !bytes.isEmpty else {
            reset()
            return nil
        }

        let flags   = bytes[0]
        let isFirst = (flags & bleFlagFirst) != 0
        let isLast  = (flags & bleFlagLast) != 0

        if isFirst {
            guard bytes.count >= 5 else {
                reset()
                return nil
            }
            let length = (Int(bytes[1]) << 24) | (Int(bytes[2]) << 16) | (Int(bytes[3]) << 8) | Int(bytes[4])
            guard length >= 1 && length <= bleMaxTotalLength else {
                reset()
                return nil
            }
            totalLength = length
            buffer = Array(bytes[5...])
        } else {
            guard buffer != nil, totalLength >= 0 else {
                reset()
                return nil
            }
            buffer!.append(contentsOf: bytes[1...])
        }

        if isLast {
            guard let data = buffer, totalLength >= 0 else {
                reset()
                return nil
            }
            let length = totalLength
            reset()
            guard data.count >= length else { return nil }
            return Data(data[0..<length])
        }

        return nil
    }

    func reset() {
        buffer = nil
        totalLength = -1
    }
}
