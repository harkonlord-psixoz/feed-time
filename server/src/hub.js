"use strict";

/** In-memory WebSocket hub: householdId → Set of sockets */
const rooms = new Map();

function join(householdId, ws) {
  if (!householdId || !ws) return;
  let set = rooms.get(householdId);
  if (!set) {
    set = new Set();
    rooms.set(householdId, set);
  }
  set.add(ws);
  ws.__householdId = householdId;
}

function leave(ws) {
  const householdId = ws && ws.__householdId;
  if (!householdId) return;
  const set = rooms.get(householdId);
  if (!set) return;
  set.delete(ws);
  if (set.size === 0) rooms.delete(householdId);
}

function broadcast(householdId, payload, exceptWs) {
  const set = rooms.get(householdId);
  if (!set) return;
  const data = typeof payload === "string" ? payload : JSON.stringify(payload);
  for (const ws of set) {
    if (ws === exceptWs) continue;
    if (ws.readyState === 1) {
      try {
        ws.send(data);
      } catch (_) {
        /* ignore broken socket */
      }
    }
  }
}

module.exports = { join, leave, broadcast };
