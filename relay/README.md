# MRIT Relay Server

A small WebSocket server that extends MRIT's reach from "phones in radio
range" to "anywhere both ends have an internet connection." It's a **dumb
forwarder** — it never decrypts anything; it only reads the 32-byte `DST_ID`
field of an MMP packet to decide which connection to forward it to.

See [PROTOCOL.md §13](../PROTOCOL.md#13-relay-network--gateway-nodes-phase-9)
for the full wire-format spec.

## Run locally

```bash
cd relay
npm install
npm start
# [relay] MRIT relay server listening on ws://0.0.0.0:8765
```

Point `RelayTransport.DEFAULT_RELAY_URL` (or a future settings field) at
`ws://<this-machine's-LAN-IP>:8765` so phones on the same network can use it.

## Deploy for real long-distance use

Any host that can run Node.js and exposes a port works: a small VPS, a Raspberry
Pi on a home connection with port forwarding, or a free-tier host (Render,
Fly.io, Railway, etc.). For production, put it behind TLS (`wss://`) using a
reverse proxy (Caddy/nginx) — OkHttp's `WebSocketListener` on the Android side
works the same with `ws://` or `wss://`.

```bash
PORT=8765 node server.js
```

## Notes / limitations (v1)

- **No persistence** — if a node disconnects, packets addressed to it are
  dropped (not queued). Local store-and-forward (`PacketStore`, 24h TTL)
  still applies for the *local mesh* hop before reaching the relay.
- **No gateway-on-behalf-of** — each node registers its *own* MeshID. A
  future phase could let one internet-connected phone register routes for
  mesh peers it can reach locally but that have no internet themselves.
- **Anyone can register any MeshID** — there's no authentication. Since all
  `MSG`/`ACK` payloads are end-to-end encrypted and MeshIDs are
  self-certifying (derived from a public key, PROTOCOL.md §4), a malicious
  registration can at most cause a denial-of-service for that one MeshID, not
  read or forge messages.
