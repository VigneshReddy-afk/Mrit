@file:Suppress("DEPRECATION")

package com.mrit.mesh.transport

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import com.mrit.mesh.core.MeshID
import com.mrit.mesh.core.MeshPacket
import com.mrit.mesh.core.PacketType
import com.mrit.mesh.crypto.KeyManager
import com.mrit.mesh.protocol.MMPDecoder
import com.mrit.mesh.protocol.MMPEncoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * BleGattTransport — phone-to-phone data bridge over Bluetooth LE GATT (Phase 6).
 *
 * Role: a real, OS-agnostic transport that lets an Android device exchange
 * MMP packets with an iOS device (and with other Android devices), bridging
 * the gap left by [WifiDirectTransport] (Android-only) and MultipeerConnectivity
 * (iOS-only). Full wire-format spec: PROTOCOL.md §11 "BLE GATT transport bridge".
 *
 * Every node runs BOTH GATT roles simultaneously:
 *   - **Peripheral** (GATT server) — advertises [SERVICE_UUID], accepts writes
 *     to [DATA_CHARACTERISTIC_UUID], and sends notifications back.
 *   - **Central** (GATT client) — scans for [SERVICE_UUID], connects, enables
 *     notifications via the CCCD, and writes outgoing data.
 *
 * One GATT connection between central X and peripheral Y is a single
 * bidirectional pipe: X→Y via characteristic writes, Y→X via notifications,
 * both on the same characteristic.
 *
 * Large MeshPackets are split into small fragments sized to the negotiated
 * ATT MTU (PROTOCOL.md §11.3) and reassembled on the other side.
 *
 * Handshake (PROTOCOL.md §11.4):
 *   Central enables notifications → central sends DISCOVER (its public key) →
 *   peripheral replies with its own DISCOVER. Both sides emit [peerHandshakes]
 *   with `bleAddress` set so [com.mrit.mesh.mesh.MeshNode] can register the peer
 *   and route to it as a fallback when no WiFi Direct / Multipeer link exists.
 */
class BleGattTransport(
    private val context: Context,
    private val ourId: MeshID,
    private val keyManager: KeyManager
) {

    companion object {
        private const val TAG = "BleGattTransport"

        /** GATT bridge service UUID — distinct from BLETransport's discovery-only UUID. */
        val SERVICE_UUID: UUID = UUID.fromString("4d524954-0010-1000-8000-00805f9b34fb")

        /** Single read/write/notify characteristic carrying fragmented MMP packets. */
        val DATA_CHARACTERISTIC_UUID: UUID = UUID.fromString("4d524954-0011-1000-8000-00805f9b34fb")

        /** Standard Client Characteristic Configuration Descriptor UUID. */
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        // Fragment header flags (PROTOCOL.md §11.3)
        private const val FLAG_FIRST: Int = 0x01
        private const val FLAG_LAST: Int = 0x02

        private const val DEFAULT_MTU = 23
        private const val REQUESTED_MTU = 247
        private const val ATT_OVERHEAD = 3

        /** Largest possible encoded MeshPacket: 69-byte header + 65535-byte payload. */
        private const val MAX_TOTAL_LENGTH = MeshPacket.HEADER_SIZE + MeshPacket.MAX_PAYLOAD_SIZE

        private const val RETRY_DELAY_MS = 50L

        /**
         * Cap on simultaneous CENTRAL-role GATT connections. Both Android and iOS
         * BLE stacks become unreliable (connection failures, dropped notifications)
         * with too many concurrent links — PROTOCOL.md §11.7.
         */
        private const val MAX_CENTRAL_LINKS = 4

        /** Exponential backoff bounds for reconnecting after a disconnect or
         *  failed connection attempt — PROTOCOL.md §11.7. */
        private const val RECONNECT_BASE_DELAY_MS = 1_000L
        private const val RECONNECT_MAX_DELAY_MS = 30_000L
        private const val RECONNECT_MAX_SHIFT = 5 // base * 2^5 = base * 32
    }

    private val bluetoothManager: BluetoothManager by lazy {
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    }
    private val bluetoothAdapter: BluetoothAdapter? by lazy { bluetoothManager.adapter }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanner: BluetoothLeScanner? = null
    private var gattServer: BluetoothGattServer? = null
    private var dataCharacteristic: BluetoothGattCharacteristic? = null

    /** GATT connections where we are the CENTRAL (we initiated via connectGatt). Keyed by device address. */
    private val centralLinks = ConcurrentHashMap<String, Link>()

    /** GATT connections where we are the PERIPHERAL (a remote central connected to our GATT server). Keyed by device address. */
    private val serverLinks = ConcurrentHashMap<String, Link>()

    /** Device addresses for which a connectGatt() call is in flight (de-dupes scan results). */
    private val connecting = ConcurrentHashMap.newKeySet<String>()

    /** Reconnect attempt counters per device address — drives exponential backoff. */
    private val reconnectAttempts = ConcurrentHashMap<String, Int>()

    /** Earliest [System.currentTimeMillis] at which we should (re)connect to this address. */
    private val nextConnectAttempt = ConcurrentHashMap<String, Long>()

    @Volatile private var isRunning = false

    /** Emits decoded data packets (non-DISCOVER) for the router. */
    private val _incomingPackets = MutableSharedFlow<MeshPacket>(extraBufferCapacity = 128)
    val incomingPackets: SharedFlow<MeshPacket> = _incomingPackets

    /** Emits a [PeerHandshake] each time we learn a new peer's MeshID ↔ BLE address mapping. */
    private val _peerHandshakes = MutableSharedFlow<PeerHandshake>(extraBufferCapacity = 32)
    val peerHandshakes: SharedFlow<PeerHandshake> = _peerHandshakes

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    fun start() {
        if (bluetoothAdapter?.isEnabled != true) {
            Log.e(TAG, "Bluetooth is not enabled — BLE GATT bridge unavailable")
            return
        }
        isRunning = true
        startGattServer()
        startAdvertising()
        startScanning()
        Log.d(TAG, "BleGattTransport started — our MeshID: ${ourId.shortId()}")
    }

    fun stop() {
        isRunning = false

        try { advertiser?.stopAdvertising(advertiseCallback) } catch (_: Exception) {}
        try { scanner?.stopScan(scanCallback) } catch (_: Exception) {}

        centralLinks.values.forEach { link ->
            try { link.gatt?.disconnect() } catch (_: Exception) {}
            try { link.gatt?.close() } catch (_: Exception) {}
        }
        centralLinks.clear()

        serverLinks.values.forEach { link ->
            val device = link.device
            if (device != null) {
                try { gattServer?.cancelConnection(device) } catch (_: Exception) {}
            }
        }
        serverLinks.clear()

        try { gattServer?.close() } catch (_: Exception) {}
        gattServer = null
        dataCharacteristic = null

        connecting.clear()
        reconnectAttempts.clear()
        nextConnectAttempt.clear()
        scope.cancel()
        Log.d(TAG, "BleGattTransport stopped")
    }

    // ── GATT server (peripheral role) ───────────────────────────────────────────

    private fun startGattServer() {
        gattServer = bluetoothManager.openGattServer(context, gattServerCallback)

        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)

        val characteristic = BluetoothGattCharacteristic(
            DATA_CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE
        )

        val cccd = BluetoothGattDescriptor(
            CCCD_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
        characteristic.addDescriptor(cccd)
        service.addCharacteristic(characteristic)

        dataCharacteristic = characteristic
        gattServer?.addService(service)
    }

    // ── Advertising ───────────────────────────────────────────────────────────

    private fun startAdvertising() {
        advertiser = bluetoothAdapter?.bluetoothLeAdvertiser
        if (advertiser == null) {
            Log.w(TAG, "This device does not support BLE peripheral mode")
            return
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .build()

        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .setIncludeDeviceName(false)
            .build()

        advertiser?.startAdvertising(settings, data, advertiseCallback)
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            Log.d(TAG, "BLE GATT bridge advertising active")
        }
        override fun onStartFailure(errorCode: Int) {
            Log.e(TAG, "BLE GATT bridge advertising failed, errorCode=$errorCode")
        }
    }

    // ── Scanning (central role) ─────────────────────────────────────────────────

    private fun startScanning() {
        scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner == null) {
            Log.w(TAG, "BLE scanner not available")
            return
        }

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner?.startScan(listOf(filter), settings, scanCallback)
        Log.d(TAG, "BLE GATT bridge scan started")
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val address = result.device.address
            if (centralLinks.containsKey(address)) return
            if (centralLinks.size >= MAX_CENTRAL_LINKS) return

            val now = System.currentTimeMillis()
            val earliest = nextConnectAttempt[address] ?: 0L
            if (now < earliest) return // still backing off from a recent failure

            if (!connecting.add(address)) return

            Log.d(TAG, "BLE GATT peer discovered: $address — connecting")
            result.device.connectGatt(context, false, centralCallback)
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "BLE GATT bridge scan failed, errorCode=$errorCode")
        }
    }

    /**
     * Schedule a reconnect attempt to [device] after an exponential backoff
     * (PROTOCOL.md §11.7): 1s, 2s, 4s, 8s, 16s, capped at 30s. Resets when a
     * connection to this address succeeds (see [centralCallback]).
     *
     * Continuous scanning would otherwise re-trigger `connectGatt()` on every
     * scan result for a peer that just dropped, which can overwhelm the BLE
     * stack (Android GATT status 133) when a peer is repeatedly unreachable.
     */
    private fun scheduleReconnect(device: BluetoothDevice) {
        val address = device.address
        val attempt = (reconnectAttempts[address] ?: 0) + 1
        reconnectAttempts[address] = attempt
        val shift = (attempt - 1).coerceAtMost(RECONNECT_MAX_SHIFT)
        val delayMs = (RECONNECT_BASE_DELAY_MS shl shift).coerceAtMost(RECONNECT_MAX_DELAY_MS)
        nextConnectAttempt[address] = System.currentTimeMillis() + delayMs

        Log.d(TAG, "Central: reconnect to $address in ${delayMs}ms (attempt $attempt)")
        scope.launch {
            delay(delayMs)
            if (!isRunning) return@launch
            if (centralLinks.containsKey(address)) return@launch
            if (centralLinks.size >= MAX_CENTRAL_LINKS) return@launch
            if (!connecting.add(address)) return@launch
            device.connectGatt(context, false, centralCallback)
        }
    }

    // ── Central GATT callback ────────────────────────────────────────────────────

    private val centralCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val address = gatt.device.address
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connecting.remove(address)
                    reconnectAttempts.remove(address)
                    nextConnectAttempt.remove(address)
                    centralLinks[address] = Link(address = address, role = Role.CENTRAL, gatt = gatt, device = gatt.device)
                    Log.d(TAG, "Central: connected to $address")
                    gatt.requestMtu(REQUESTED_MTU)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    connecting.remove(address)
                    centralLinks.remove(address)
                    try { gatt.close() } catch (_: Exception) {}
                    Log.d(TAG, "Central: disconnected from $address (status=$status)")
                    scheduleReconnect(gatt.device)
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            val link = centralLinks[gatt.device.address] ?: return
            link.mtu = if (status == BluetoothGatt.GATT_SUCCESS) mtu else DEFAULT_MTU
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            val service = gatt.getService(SERVICE_UUID) ?: return
            val characteristic = service.getCharacteristic(DATA_CHARACTERISTIC_UUID) ?: return

            gatt.setCharacteristicNotification(characteristic, true)

            val cccd = characteristic.getDescriptor(CCCD_UUID) ?: return
            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            gatt.writeDescriptor(cccd)
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (descriptor.uuid != CCCD_UUID) return
            val link = centralLinks[gatt.device.address] ?: return
            link.notificationsEnabled = (status == BluetoothGatt.GATT_SUCCESS)
            if (link.notificationsEnabled) {
                // We're CENTRAL — initiate the handshake (PROTOCOL.md §11.4)
                sendHandshake(link)
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid != DATA_CHARACTERISTIC_UUID) return
            val link = centralLinks[gatt.device.address] ?: return
            val fragment = characteristic.value ?: return
            handleIncomingFragment(link, fragment)
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (characteristic.uuid != DATA_CHARACTERISTIC_UUID) return
            val link = centralLinks[gatt.device.address] ?: return
            onFragmentSent(link)
        }
    }

    // ── GATT server callback (peripheral role) ──────────────────────────────────

    private val gattServerCallback = object : BluetoothGattServerCallback() {

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    serverLinks[device.address] = Link(address = device.address, role = Role.PERIPHERAL, device = device)
                    Log.d(TAG, "Peripheral: central connected ${device.address}")
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    serverLinks.remove(device.address)
                    Log.d(TAG, "Peripheral: central disconnected ${device.address}")
                }
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            serverLinks[device.address]?.mtu = mtu
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            if (characteristic.uuid == DATA_CHARACTERISTIC_UUID) {
                serverLinks[device.address]?.let { link -> handleIncomingFragment(link, value) }
            }
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            if (descriptor.uuid == CCCD_UUID) {
                serverLinks[device.address]?.notificationsEnabled =
                    value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            }
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            val link = serverLinks[device.address] ?: return
            onFragmentSent(link)
        }
    }

    // ── Sending / fragmentation ──────────────────────────────────────────────────

    /** Send a MeshPacket to the BLE peer at [address] (a [PeerInfo.bleAddress] value). */
    fun send(packet: MeshPacket, address: String) {
        val link = linkFor(address)
        if (link == null) {
            Log.w(TAG, "send: no BLE GATT link to $address")
            return
        }
        enqueue(link, packet)
    }

    private fun linkFor(address: String): Link? = centralLinks[address] ?: serverLinks[address]

    private fun enqueue(link: Link, packet: MeshPacket) {
        val encoded = MMPEncoder.encode(packet)
        val fragments = fragmentMessage(encoded, link.mtu)
        synchronized(link.outQueue) {
            link.outQueue.addAll(fragments)
        }
        sendNextFragment(link)
    }

    /**
     * Split [encoded] into ATT-sized fragments per PROTOCOL.md §11.3.
     *
     * Fragment layout:
     *   byte 0      : FLAGS (bit0=FIRST, bit1=LAST)
     *   [bytes 1-4] : TOTAL_LENGTH uint32 BE — present only if FIRST set
     *   remainder   : raw slice of [encoded]
     */
    private fun fragmentMessage(encoded: ByteArray, mtu: Int): List<ByteArray> {
        val maxFragment = (mtu.coerceAtLeast(DEFAULT_MTU) - ATT_OVERHEAD).coerceAtLeast(1)
        val total = encoded.size
        val fragments = mutableListOf<ByteArray>()
        var offset = 0

        do {
            val isFirst = offset == 0
            val headerSize = if (isFirst) 5 else 1
            val payloadCapacity = (maxFragment - headerSize).coerceAtLeast(1)
            val remaining = total - offset
            val chunkSize = remaining.coerceAtMost(payloadCapacity)
            val isLast = (offset + chunkSize) >= total

            var flags = 0
            if (isFirst) flags = flags or FLAG_FIRST
            if (isLast) flags = flags or FLAG_LAST

            val fragment = ByteArray(headerSize + chunkSize)
            fragment[0] = flags.toByte()
            if (isFirst) {
                fragment[1] = ((total shr 24) and 0xFF).toByte()
                fragment[2] = ((total shr 16) and 0xFF).toByte()
                fragment[3] = ((total shr 8) and 0xFF).toByte()
                fragment[4] = (total and 0xFF).toByte()
            }
            if (chunkSize > 0) {
                System.arraycopy(encoded, offset, fragment, headerSize, chunkSize)
            }
            fragments.add(fragment)

            offset += chunkSize
        } while (offset < total)

        return fragments
    }

    /** Pop and dispatch the next queued fragment for [link], if no write is currently in flight. */
    private fun sendNextFragment(link: Link) {
        val fragment: ByteArray
        synchronized(link.outQueue) {
            if (link.writeInFlight) return
            fragment = link.outQueue.removeFirstOrNull() ?: return
            link.writeInFlight = true
        }

        val dispatched = when (link.role) {
            Role.CENTRAL -> dispatchCentralWrite(link, fragment)
            Role.PERIPHERAL -> dispatchPeripheralNotify(link, fragment)
        }

        if (!dispatched) {
            synchronized(link.outQueue) {
                link.outQueue.addFirst(fragment)
                link.writeInFlight = false
            }
            scope.launch {
                delay(RETRY_DELAY_MS)
                sendNextFragment(link)
            }
        }
    }

    /** Called once a fragment write/notify has completed — clears the in-flight flag and dispatches the next one. */
    private fun onFragmentSent(link: Link) {
        synchronized(link.outQueue) {
            link.writeInFlight = false
        }
        sendNextFragment(link)
    }

    private fun dispatchCentralWrite(link: Link, fragment: ByteArray): Boolean {
        val gatt = link.gatt ?: return false
        val service = gatt.getService(SERVICE_UUID) ?: return false
        val characteristic = service.getCharacteristic(DATA_CHARACTERISTIC_UUID) ?: return false

        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        characteristic.value = fragment

        return try {
            gatt.writeCharacteristic(characteristic)
        } catch (e: Exception) {
            Log.e(TAG, "writeCharacteristic failed: ${e.message}")
            false
        }
    }

    private fun dispatchPeripheralNotify(link: Link, fragment: ByteArray): Boolean {
        val server = gattServer ?: return false
        val device = link.device ?: return false
        val characteristic = dataCharacteristic ?: return false

        characteristic.value = fragment

        return try {
            server.notifyCharacteristicChanged(device, characteristic, false)
        } catch (e: Exception) {
            Log.e(TAG, "notifyCharacteristicChanged failed: ${e.message}")
            false
        }
    }

    // ── Receiving / reassembly ───────────────────────────────────────────────────

    private fun handleIncomingFragment(link: Link, fragment: ByteArray) {
        val complete = link.reassembly.accept(fragment) ?: return
        val packet = MMPDecoder.decode(complete)
        if (packet == null) {
            Log.w(TAG, "Dropping malformed reassembled packet from ${link.address}")
            return
        }

        if (packet.type == PacketType.DISCOVER) {
            link.meshId = packet.srcId
            if (link.role == Role.PERIPHERAL) {
                // We're PERIPHERAL — reply with our own DISCOVER (PROTOCOL.md §11.4)
                sendHandshake(link)
            }
            _peerHandshakes.tryEmit(
                PeerHandshake(
                    meshId = packet.srcId,
                    bleAddress = link.address,
                    publicKeyBytes = packet.payload
                )
            )
        } else {
            _incomingPackets.tryEmit(packet)
        }
    }

    // ── Handshake ─────────────────────────────────────────────────────────────────

    private fun buildDiscoverPacket(): MeshPacket = MeshPacket(
        type = PacketType.DISCOVER,
        srcId = ourId,
        dstId = MeshID.BROADCAST,
        ttl = 1,
        payload = keyManager.publicKeyBytes
    )

    private fun sendHandshake(link: Link) {
        if (link.handshakeSent) return
        link.handshakeSent = true
        enqueue(link, buildDiscoverPacket())
    }

    // ── Internal types ────────────────────────────────────────────────────────────

    private enum class Role { CENTRAL, PERIPHERAL }

    /** State for one BLE GATT connection (either CENTRAL or PERIPHERAL role). */
    private class Link(
        val address: String,
        val role: Role,
        val gatt: BluetoothGatt? = null,
        val device: BluetoothDevice? = null
    ) {
        @Volatile var mtu: Int = DEFAULT_MTU
        @Volatile var notificationsEnabled: Boolean = false
        @Volatile var handshakeSent: Boolean = false
        @Volatile var meshId: MeshID? = null

        val reassembly = FragmentReassembler()
        val outQueue = ArrayDeque<ByteArray>()
        var writeInFlight: Boolean = false
    }

    /**
     * Reassembles a single stream of length-prefixed fragments (PROTOCOL.md §11.3)
     * back into one encoded MeshPacket. One instance per [Link] — connections are
     * 1:1, so there's never more than one in-flight message per direction at a time.
     */
    private class FragmentReassembler {
        private var buffer: ByteArrayOutputStream? = null
        private var totalLength: Int = -1

        /**
         * Feed one fragment. Returns the fully reassembled packet bytes once the
         * LAST fragment is received, or null while more fragments are still needed
         * (or the input was invalid and the buffer was reset).
         */
        fun accept(fragment: ByteArray): ByteArray? {
            if (fragment.isEmpty()) {
                reset()
                return null
            }

            val flags = fragment[0].toInt() and 0xFF
            val isFirst = (flags and FLAG_FIRST) != 0
            val isLast = (flags and FLAG_LAST) != 0

            if (isFirst) {
                if (fragment.size < 5) {
                    reset()
                    return null
                }
                val length = ((fragment[1].toInt() and 0xFF) shl 24) or
                    ((fragment[2].toInt() and 0xFF) shl 16) or
                    ((fragment[3].toInt() and 0xFF) shl 8) or
                    (fragment[4].toInt() and 0xFF)

                if (length < 1 || length > MAX_TOTAL_LENGTH) {
                    reset()
                    return null
                }

                buffer = ByteArrayOutputStream(length.coerceAtMost(MAX_TOTAL_LENGTH))
                totalLength = length
                buffer!!.write(fragment, 5, fragment.size - 5)
            } else {
                val active = buffer
                if (active == null || totalLength < 0) {
                    // Continuation fragment without a preceding FIRST — drop & reset
                    reset()
                    return null
                }
                active.write(fragment, 1, fragment.size - 1)
            }

            if (isLast) {
                val active = buffer
                val length = totalLength
                reset()
                if (active == null || length < 0) return null
                val data = active.toByteArray()
                return if (data.size >= length) data.copyOf(length) else null
            }

            return null
        }

        fun reset() {
            buffer = null
            totalLength = -1
        }
    }

    /**
     * Result of a completed BLE GATT handshake.
     * Mirrors [WifiDirectTransport.PeerHandshake] / MultipeerTransport's PeerHandshake,
     * but carries [bleAddress] instead of an IP address.
     */
    data class PeerHandshake(
        val meshId: MeshID,
        val bleAddress: String,
        val publicKeyBytes: ByteArray = ByteArray(0)
    )
}
