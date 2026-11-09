"use strict";

const hub = require("./hub");
const { query } = require("./db");

/**
 * Family feed notification after a synced feeding.
 * Always wakes connected WebSocket clients.
 * If FCM_SERVER_KEY is set and devices have fcm_token, also send FCM (legacy HTTP).
 */

function familyFeedPayload(feeding, actor) {
  const time = (feeding && feeding.time) || "";
  const name = (actor && actor.displayName) || feeding.fedByName || "Участник";
  const body = time
    ? `${name}: питомцев покормили в ${time}`
    : `${name}: запись кормления обновлена`;
  return {
    type: "family_feed",
    title: "Feed Time",
    body,
    feeding,
    actorUserId: actor && actor.id ? actor.id : feeding.fedByUserId || null,
    actorName: name,
  };
}

function broadcastFamilyFeed(householdId, feeding, actor, exceptWs) {
  if (!householdId || !feeding) return null;
  /* Skip empty clears — only notify when someone recorded a time */
  if (!String(feeding.time || "").trim()) return null;
  const payload = familyFeedPayload(feeding, actor);
  hub.broadcast(householdId, payload, exceptWs);
  sendFcmToHousehold(householdId, payload, actor && actor.id).catch((err) => {
    console.warn("FCM notify failed:", err.message || err);
  });
  return payload;
}

async function sendFcmToHousehold(householdId, payload, exceptUserId) {
  const serverKey = process.env.FCM_SERVER_KEY || process.env.FIREBASE_SERVER_KEY;
  if (!serverKey) return { sent: 0, skipped: "no-fcm-key" };

  const result = await query(
    `SELECT fcm_token, user_id, device_id FROM device_tokens
     WHERE household_id = $1
       AND fcm_token IS NOT NULL
       AND fcm_token <> ''
       AND ($2::uuid IS NULL OR user_id <> $2)`,
    [householdId, exceptUserId || null]
  );
  if (result.rowCount === 0) return { sent: 0, skipped: "no-tokens" };

  let sent = 0;
  for (const row of result.rows) {
    try {
      const ok = await postLegacyFcm(serverKey, row.fcm_token, payload);
      if (ok) sent += 1;
    } catch (err) {
      console.warn("FCM token send error:", row.device_id, err.message || err);
    }
  }
  return { sent };
}

async function postLegacyFcm(serverKey, token, payload) {
  const res = await fetch("https://fcm.googleapis.com/fcm/send", {
    method: "POST",
    headers: {
      Authorization: `key=${serverKey}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({
      to: token,
      priority: "high",
      notification: {
        title: payload.title || "Feed Time",
        body: payload.body || "",
        sound: "default",
        tag: "family_feed",
      },
      data: {
        type: "family_feed",
        feedingId: payload.feeding && payload.feeding.id ? String(payload.feeding.id) : "",
        clientId:
          payload.feeding && payload.feeding.clientId
            ? String(payload.feeding.clientId)
            : "",
        actorName: payload.actorName || "",
      },
    }),
  });
  if (!res.ok) {
    const text = await res.text();
    throw new Error(`FCM HTTP ${res.status}: ${text.slice(0, 200)}`);
  }
  return true;
}

module.exports = {
  familyFeedPayload,
  broadcastFamilyFeed,
  sendFcmToHousehold,
};
