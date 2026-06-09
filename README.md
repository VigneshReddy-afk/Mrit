# MRIT — Mobile Mesh Protocol

> Phone-to-phone mesh network. No towers. No satellites. No infrastructure.

MRIT is a from-scratch mesh networking stack for Android that lets phones communicate directly with each other — hop by hop — with zero internet infrastructure.

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
| Data | WiFi Direct | ~200 m | Bulk packet transfer |

---

## Store-and-Forward

Packets for unreachable peers are stored in SQLite for up to 24 hours.
Delivered automatically when the destination comes into range.

---

## Project Structure

```
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
│   └── BLETransport.kt         — peer discovery over Bluetooth LE
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
    │   └── MultipeerTransport.swift — MultipeerConnectivity, service "mrit-mesh"
    └── Mesh/
        ├── MeshNode.swift      — iOS public API (sendMessage/sendFile/sendSOS)
        └── FileTransferManager.swift — chunked file transfer, binary-compatible payloads
```

---

## Roadmap

- [x] **Phase 1** — MMP protocol, WiFi Direct + BLE transport, AODV router, store-and-forward
- [x] **Phase 2** — Peer registry, WiFi Direct handshake, full routing, messaging UI
- [x] **Phase 3** — E2E encryption (ECDH + AES-256-GCM), ACK + retry, multi-hop RREQ/RREP
- [x] **Phase 4** — iOS Swift Package (9 files, binary-compatible), Android Keystore key storage, encrypted 32KB chunked file transfer, 11-test crypto suite
- [ ] **Phase 5** — Cross-platform DSL for mesh-aware application development

---

Built by Vikki Reddy — [github.com/VigneshReddy-afk](https://github.com/VigneshReddy-afk)
