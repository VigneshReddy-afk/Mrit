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
- **Types** — MSG, ACK, DISCOVER, ROUTE, SOS
- **Max payload** — 65,535 bytes per packet

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
│   ├── MeshPacket.kt           — MMP packet + PacketType enum
│   ├── PeerInfo.kt             — peer data (MeshID, IP, public key)
│   └── PeerRegistry.kt        — thread-safe peer + shared key table
├── crypto/
│   ├── KeyManager.kt           — EC P-256 key pair generation & storage
│   └── MeshCrypto.kt           — ECDH key agreement + AES-256-GCM encrypt/decrypt
├── protocol/
│   ├── MMPEncoder.kt           — MeshPacket → bytes
│   └── MMPDecoder.kt           — bytes → MeshPacket
├── transport/
│   ├── WifiDirectTransport.kt  — data transfer + handshake over WiFi Direct
│   └── BLETransport.kt         — peer discovery over Bluetooth LE
├── routing/
│   └── AODVRouter.kt           — AODV routing engine (RREQ/RREP/route table)
├── reliability/
│   └── AckManager.kt           — ACK tracking + retry logic
├── storage/
│   └── PacketStore.kt          — SQLite store-and-forward queue
├── mesh/
│   └── MeshNode.kt             — complete mesh API (send/receive/route/encrypt)
├── service/
│   └── MeshService.kt          — foreground service lifecycle
├── ui/
│   ├── PeerAdapter.kt          — live peer chip list
│   └── MessageAdapter.kt       — message log
└── MainActivity.kt             — entry point, permissions, messaging UI
```

---

## Roadmap

- [x] **Phase 1** — MMP protocol, WiFi Direct + BLE transport, AODV router, store-and-forward
- [x] **Phase 2** — Peer registry, WiFi Direct handshake, full routing, messaging UI
- [x] **Phase 3** — E2E encryption (ECDH + AES-256-GCM), ACK + retry, multi-hop RREQ/RREP
- [ ] **Phase 4** — iOS port, Android Keystore migration, file transfer
- [ ] **Phase 5** — Custom DSL for mesh-aware application development

---

Built by Vikki Reddy — [github.com/VigneshReddy-afk](https://github.com/VigneshReddy-afk)
