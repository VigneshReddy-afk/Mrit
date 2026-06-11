# MRIT — Mobile Mesh Protocol

> Phone-to-phone mesh network. No towers. No satellites. No infrastructure.

MRIT is a from-scratch mesh networking stack for Android and iOS that lets phones communicate directly with each other — hop by hop — with zero internet infrastructure.

The complete wire protocol is formally specified in **[PROTOCOL.md](PROTOCOL.md)** —
the canonical reference both implementations are kept in sync against.

---

## Architecture

```
┌──────────────────────────────────────────┐
│  LAYER 4 : APPS                          │
│  Chat / SOS / Maps / File Share          │
├──────────────────────────────────────────┤
│  LAYER 3 : MESH SDK                      │
│  send() / receive() / discover()         │
├──────────────────────────────────────────┤
│  LAYER 2 : MMP (Mobile Mesh Protocol)    │
│  Custom protocol — routing, addressing,  │
│  E2E encryption, store-and-forward, ACK  │
├──────────────────────────────────────────┤
│  LAYER 1 : TRANSPORT                     │
│  WiFi Direct (data) + BLE (discovery)    │
└──────────────────────────────────────────┘
```

---

## MMP Packet Format

```
┌──────────┬──────────┬────────────┬────────────┬──────────┬──────────┬──────────────┐
│ VERSION  │   TYPE   │   SRC_ID   │   DST_ID   │   TTL    │  LENGTH  │   PAYLOAD    │
│  1 byte  │  1 byte  │  32 bytes  │  32 bytes  │  1 byte  │  2 bytes │   N bytes    │
└──────────┴──────────┴────────────┴────────────┴──────────┴──────────┴──────────────┘
```

- **MeshID** — 256-bit SHA-256(EC_PublicKey + timestamp + salt), permanent identity
- **TTL** — starts at 64, decrements each hop, packet dies at 0
- **Types** — MSG(0x01), ACK(0x02), DISCOVER(0x03), ROUTE(0x04), SOS(0x05), FILE_CHUNK(0x06)
- **Max payload** — 65,535 bytes per packet

---

## File Transfer (Phase 4)

Files of any size are transferred peer-to-peer over the mesh.

```
sender.sendFile(destId, "photo.jpg", bytes)
```

| Step | Detail |
|---|---|
| Split | File divided into 32 KB chunks |
| Header | 30-byte binary header: transferId(16) + chunkIndex(4) + totalChunks(4) + size(4) + nameLen(2) + name |
| Encrypt | Each chunk AES-256-GCM encrypted with the per-peer shared key |
| Reassemble | Chunks may arrive in any order; FileTransferManager reconstructs in index order |
| Deliver | `incomingFiles: SharedFlow<ReceivedFile>` emits the complete file |

Chunk format is binary-compatible between Android and iOS.

---

## End-to-End Encryption (Phase 3)

Every unicast message is encrypted before it leaves the device.

| Step | Algorithm | Detail |
|---|---|---|
| Key generation | EC P-256 | One key pair per device, generated on first install |
| Key exchange | ECDH | Public keys exchanged in DISCOVER handshake |
| Key derivation | SHA-256 | `SHA-256(sharedSecret)` → 32-byte AES key |
| Encryption | AES-256-GCM | 12-byte random IV per message, 16-byte auth tag |

**Encrypted payload wire format:**
```
[ 12 bytes : random IV ] [ N+16 bytes : ciphertext + auth tag ]
```
Overhead: 28 bytes per message. Any tampering → auth tag fails → packet dropped.

---

## Routing — AODV

MRIT uses **Ad-hoc On-Demand Distance Vector (AODV)** routing (RFC 3561).

**Multi-hop flow (A → B → C):**
```
A ──RREQ──▶ B ──RREQ──▶ C
A ◀──RREP── B ◀──RREP── C
A ──MSG───▶ B ──MSG───▶ C   (route now known)
```
Routes expire after 30 seconds to handle device movement.

---

## Reliability — ACK + Retry (Phase 3)

Every delivered MSG triggers an ACK packet back to the sender.
If ACK is not received within 5 seconds, the message is re-sent (up to 3 attempts).

---

## Transport

| Layer | Technology | Range | Purpose |
|---|---|---|---|
| Discovery | Bluetooth LE | ~100 m | Always-on peer detection |
| Data | WiFi Direct | ~200 m | Bulk packet transfer (Android↔Android) |
| Data | Multipeer Connectivity | ~30 m | Bulk packet transfer (iOS↔iOS) |
| Cross-platform bridge | Bluetooth LE GATT | ~10-30 m | iOS↔Android (and iOS↔iOS) data fallback (Phase 6) |

---

## BLE GATT Bridge (Phase 6)

MultipeerConnectivity (iOS) and WiFi Direct (Android) are different radio
protocols and can't talk to each other directly. Bluetooth LE GATT is the
one transport every modern phone supports, so MRIT uses it as a universal
bridge — **every node runs both GATT roles at once**:

```
┌─────────────┐                              ┌─────────────┐
│   Node A    │  scans, connects, writes     │   Node B    │
│  (CENTRAL)  │ ────────────────────────────▶│ (PERIPHERAL)│
│ (PERIPHERAL)│◀──────────────────────────── │  (CENTRAL)  │
└─────────────┘        notifications         └─────────────┘
```

- **Service UUID** `4d524954-0010-1000-8000-00805f9b34fb`, **data characteristic**
  `4d524954-0011-1000-8000-00805f9b34fb` (WRITE + NOTIFY) — one connection is a
  single bidirectional pipe: writes one way, notifications the other.
- **Fragmentation** — packets are split to fit the negotiated ATT MTU. Each
  fragment starts with a FLAGS byte (FIRST/LAST bits); the first fragment also
  carries a 4-byte big-endian total-length prefix. Reassembled before MMP decode.
- **Handshake** — the central enables notifications, sends a DISCOVER (its
  public key), and the peripheral replies with its own DISCOVER. Both sides
  derive the ECDH shared key and register the peer by `bleAddress`.
- **Routing fallback** — `MeshNode` tries WiFi Direct/Multipeer first, falling
  back to the BLE GATT link for any peer only reachable over BLE.
- **Throughput** — roughly 1-4 KB/s, fine for chat/SOS text. See
  [PROTOCOL.md §11](PROTOCOL.md) for the full spec and known limitations.

---

## Store-and-Forward

Packets for unreachable peers are stored in SQLite for up to 24 hours.
Delivered automatically when the destination comes into range.

---

## Developer SDK / DSL (Phase 5)

Most apps don't need to touch `MeshNode`, `MeshPacket`, or routing directly.
The **Mrit** DSL wraps the whole stack behind `send()` / `sendFile()` / `sos()`
and three declarative callbacks — `onMessage`, `onFile`, `onPeers` — and is
implemented symmetrically on Android and iOS.

**Android (Kotlin):**
```kotlin
val mesh = Mrit.start(applicationContext) {
    onMessage { msg  -> log("From ${msg.from.shortId()}: ${msg.text}") }
    onFile    { file -> saveToDisk(file.fileName, file.data) }
    onPeers   { peers -> updatePeerChips(peers) }
}

mesh.send(peerId, "Hello mesh!")
mesh.sendFile(peerId, "map.png", bytes)
mesh.sos("Need help — twisted ankle, 2km north of trailhead")

// ... later, e.g. in onDestroy()
mesh.stop()
```

**iOS (Swift):**
```swift
let mesh = Mrit { config in
    config.onMessage { msg in print("From \(msg.from.shortId): \(msg.text)") }
    config.onFile    { file in save(file.fileName, file.data) }
    config.onPeers   { peers in updatePeerList(peers) }
}

mesh.send(to: peerId, text: "Hello mesh!")
mesh.sendFile(to: peerId, name: "map.png", data: bytes)
mesh.sos("Need help — twisted ankle, 2km north of trailhead")

// ... later
mesh.stop()
```

The DSL is a thin layer — `mesh.node` (Android) / `mesh.node` (iOS) still
exposes the full `MeshNode` API for advanced use cases.

---

## Project Structure

```
PROTOCOL.md                       — canonical MMP v1 wire-format spec (Phase 5)

app/src/main/java/com/mrit/mesh/
├── core/
│   ├── MeshID.kt               — 256-bit node identity
│   ├── MeshPacket.kt           — MMP packet + PacketType enum (MSG/ACK/DISCOVER/ROUTE/SOS/FILE_CHUNK)
│   ├── PeerInfo.kt             — peer data (MeshID, IP, public key)
│   └── PeerRegistry.kt        — thread-safe peer + shared key table
├── crypto/
│   ├── KeyManager.kt           — EC P-256 key pair — EncryptedSharedPreferences (Phase 4)
│   └── MeshCrypto.kt           — ECDH key agreement + AES-256-GCM encrypt/decrypt
├── protocol/
│   ├── MMPEncoder.kt           — MeshPacket → bytes (big-endian)
│   └── MMPDecoder.kt           — bytes → MeshPacket (null-safe)
├── transport/
│   ├── WifiDirectTransport.kt  — data transfer + DISCOVER handshake over WiFi Direct
│   ├── BLETransport.kt         — peer discovery over Bluetooth LE
│   └── BleGattTransport.kt     — BLE GATT data bridge: fragmentation + handshake (Phase 6)
├── routing/
│   └── AODVRouter.kt           — AODV routing engine (RREQ/RREP/route table)
├── reliability/
│   └── AckManager.kt           — ACK tracking + 3-retry logic
├── storage/
│   └── PacketStore.kt          — SQLite store-and-forward queue (24h TTL)
├── transfer/
│   └── FileTransferManager.kt  — 32KB chunked file send/reassemble (Phase 4)
├── mesh/
│   └── MeshNode.kt             — complete mesh API: sendMessage/sendFile/sendSOS
├── dsl/
│   └── Mrit.kt                 — high-level Mrit DSL: send/sendFile/sos + onMessage/onFile/onPeers (Phase 5)
├── service/
│   └── MeshService.kt          — foreground service lifecycle
├── ui/
│   ├── PeerAdapter.kt          — live peer chip list
│   └── MessageAdapter.kt       — message log
└── MainActivity.kt             — entry point, permissions, messaging UI

ios/                            — Swift Package (Phase 4)
├── Package.swift               — SPM manifest, iOS 14+
└── Sources/MritMesh/
    ├── Core/
    │   ├── MeshID.swift        — 256-bit identity, CryptoKit P256
    │   └── MeshPacket.swift    — MMP struct, binary-compatible with Android
    ├── Protocol/
    │   └── MMPCodec.swift      — encode/decode, big-endian, identical wire format
    ├── Crypto/
    │   ├── KeyManager.swift    — iOS Keychain (SecItem) key persistence
    │   └── MeshCrypto.swift    — CryptoKit ECDH + AES.GCM, same derivation as Android
    ├── Transport/
    │   ├── MultipeerTransport.swift — MultipeerConnectivity, service "mrit-mesh"
    │   └── BleGattTransport.swift   — BLE GATT data bridge: CoreBluetooth, fragmentation + handshake (Phase 6)
    ├── Mesh/
    │   ├── MeshNode.swift      — iOS public API (sendMessage/sendFile/sendSOS)
    │   └── FileTransferManager.swift — chunked file transfer, binary-compatible payloads
    └── DSL/
        └── Mrit.swift          — high-level Mrit DSL: send/sendFile/sos + onMessage/onFile/onPeers (Phase 5)
```

---

## Roadmap

- [x] **Phase 1** — MMP protocol, WiFi Direct + BLE transport, AODV router, store-and-forward
- [x] **Phase 2** — Peer registry, WiFi Direct handshake, full routing, messaging UI
- [x] **Phase 3** — E2E encryption (ECDH + AES-256-GCM), ACK + retry, multi-hop RREQ/RREP
- [x] **Phase 4** — iOS Swift Package (9 files, binary-compatible), Android Keystore key storage, encrypted 32KB chunked file transfer, 11-test crypto suite
- [x] **Phase 5** — Cross-platform protocol reconciliation: 65-byte x963 EC public keys on Android (matching iOS CryptoKit), AODV RREQ payload bugfix (multi-hop routing on Android), iOS AES-GCM empty-plaintext decrypt fix, formal [PROTOCOL.md](PROTOCOL.md) spec, and the `Mrit` developer DSL (Android + iOS)
- [x] **Phase 6** — BLE GATT transport bridge for real iOS↔Android interop: `BleGattTransport` (Android, CoreBluetooth-equivalent GATT central+peripheral) and `BleGattTransport.swift` (iOS, CoreBluetooth), fragmentation/reassembly over the negotiated ATT MTU, DISCOVER-based ECDH handshake, and transport-fallback routing (`MeshNode.transmit`) on both platforms
- [ ] **Phase 7** — Cross-platform field testing & BLE reliability hardening (connection retry/backoff, CoreBluetooth state restoration, multi-peer GATT scaling), plus mesh topology visualization in the UI

---

Built by Vikki Reddy — [github.com/VigneshReddy-afk](https://github.com/VigneshReddy-afk)
