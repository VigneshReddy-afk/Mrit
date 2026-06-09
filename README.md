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
│  encryption, store-and-forward           │
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

- **MeshID** — 256-bit SHA-256 identity, generated on first install
- **TTL** — starts at 64, decrements each hop, packet dies at 0
- **Types** — MSG, ACK, DISCOVER, ROUTE, SOS
- **Max payload** — 65,535 bytes per packet

---

## Routing — AODV

MRIT uses **Ad-hoc On-Demand Distance Vector (AODV)** routing — the same algorithm used in military and emergency mesh networks. Routes are discovered on demand and expire after 30 seconds to handle device movement.

---

## Transport

| Layer | Technology | Range | Purpose |
|---|---|---|---|
| Discovery | Bluetooth LE | ~100 m | Always-on peer detection |
| Data | WiFi Direct | ~200 m | Bulk packet transfer |

---

## Store-and-Forward

Packets that can't be delivered immediately (unknown route) are stored in a local SQLite database for up to 24 hours. When the destination node comes into range, queued packets are automatically forwarded.

---

## Project Structure

```
app/src/main/java/com/mrit/mesh/
├── core/
│   ├── MeshID.kt               — 256-bit node identity
│   └── MeshPacket.kt           — MMP packet model + PacketType enum
├── protocol/
│   ├── MMPEncoder.kt           — serialize MeshPacket to bytes
│   └── MMPDecoder.kt           — deserialize bytes to MeshPacket
├── transport/
│   ├── WifiDirectTransport.kt  — data transfer over WiFi Direct
│   └── BLETransport.kt         — peer discovery over BLE
├── routing/
│   └── AODVRouter.kt           — AODV mesh routing engine
├── storage/
│   └── PacketStore.kt          — SQLite store-and-forward queue
├── service/
│   └── MeshService.kt          — foreground service, ties everything together
└── MainActivity.kt             — entry point, permissions, UI
```

---

## Roadmap

- [x] **Phase 1** — Core protocol + transport layer (current)
- [ ] **Phase 2** — Peer address table, full multi-hop routing
- [ ] **Phase 3** — Encrypted messaging app on top of the mesh
- [ ] **Phase 4** — iOS port
- [ ] **Phase 5** — Custom DSL for mesh-aware application development

---

Built by Vikki Reddy — [github.com/VigneshReddy-afk](https://github.com/VigneshReddy-afk)
