'use strict';

/**
 * MRIT relay server (Phase 9) — PROTOCOL.md §13.
 *
 * A dumb forwarder: nodes connect over WebSocket, register their 32-byte
 * MeshID, and exchange opaque MMP packets (still end-to-end encrypted —
 * this server never reads payloads, only the DST_ID header field).
 *
 * Frame format (every binary WebSocket message):
 *   REGISTER : [0x00][MeshID — 32 bytes]
 *   PACKET   : [0x01][MMP-encoded MeshPacket]
 *
 * The MMP header places DST_ID at packet offset 34..65 (PROTOCOL.md §2),
 * so within a PACKET frame (which prefixes one extra type byte) DST_ID is
 * at frame offset 35..66.
 */

const { WebSocketServer } = require('ws');

const PORT = process.env.PORT ? parseInt(process.env.PORT, 10) : 8765;

const FRAME_REGISTER = 0x00;
const FRAME_PACKET = 0x01;

const MESH_ID_LEN = 32;
const DST_ID_OFFSET = 1 + 34; // frame type byte + (VERSION + TYPE + SRC_ID)

// meshId hex (64 chars, lowercase) -> WebSocket
const registry = new Map();

const wss = new WebSocketServer({ port: PORT });

wss.on('connection', (ws) => {
  let meshIdHex = null;

  ws.on('message', (data, isBinary) => {
    if (!isBinary || !Buffer.isBuffer(data) || data.length < 1) return;

    const frameType = data[0];

    if (frameType === FRAME_REGISTER) {
      if (data.length !== 1 + MESH_ID_LEN) return;
      meshIdHex = data.subarray(1, 1 + MESH_ID_LEN).toString('hex');
      registry.set(meshIdHex, ws);
      console.log(`[relay] registered ${meshIdHex.slice(0, 8)}... (${registry.size} online)`);
      return;
    }

    if (frameType === FRAME_PACKET) {
      if (data.length < DST_ID_OFFSET + MESH_ID_LEN) return;
      const dstHex = data.subarray(DST_ID_OFFSET, DST_ID_OFFSET + MESH_ID_LEN).toString('hex');
      const dest = registry.get(dstHex);
      if (dest && dest.readyState === dest.OPEN) {
        dest.send(data);
      } else {
        console.log(`[relay] no route to ${dstHex.slice(0, 8)}... — dropped`);
      }
      return;
    }
  });

  ws.on('close', () => {
    if (meshIdHex && registry.get(meshIdHex) === ws) {
      registry.delete(meshIdHex);
      console.log(`[relay] unregistered ${meshIdHex.slice(0, 8)}... (${registry.size} online)`);
    }
  });
});

console.log(`[relay] MRIT relay server listening on ws://0.0.0.0:${PORT}`);
