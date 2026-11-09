"use strict";

const express = require("express");
const { query } = require("../db");
const {
  asyncHandler,
  requireAuth,
  loadMembership,
} = require("../middleware");

const router = express.Router();

router.use(requireAuth);

router.post(
  "/register",
  asyncHandler(async (req, res) => {
    const deviceId = String((req.body && req.body.deviceId) || "")
      .trim()
      .slice(0, 128);
    const platformRaw = String((req.body && req.body.platform) || "web")
      .trim()
      .toLowerCase();
    const platform = platformRaw === "android" ? "android" : "web";
    const fcmTokenRaw = req.body && req.body.fcmToken;
    const fcmToken =
      fcmTokenRaw == null || fcmTokenRaw === ""
        ? null
        : String(fcmTokenRaw).trim().slice(0, 512);

    if (!deviceId) {
      return res.status(400).json({ error: "deviceId обязателен" });
    }

    const membership = await loadMembership(req.user.id);
    const householdId = membership ? membership.household_id : null;

    const result = await query(
      `INSERT INTO device_tokens (user_id, household_id, device_id, platform, fcm_token, last_seen_at)
       VALUES ($1, $2, $3, $4, $5, now())
       ON CONFLICT (user_id, device_id) DO UPDATE SET
         household_id = EXCLUDED.household_id,
         platform = EXCLUDED.platform,
         fcm_token = COALESCE(EXCLUDED.fcm_token, device_tokens.fcm_token),
         last_seen_at = now()
       RETURNING id, device_id, platform, household_id, fcm_token IS NOT NULL AS has_fcm, last_seen_at`,
      [req.user.id, householdId, deviceId, platform, fcmToken]
    );

    const row = result.rows[0];
    res.json({
      device: {
        id: row.id,
        deviceId: row.device_id,
        platform: row.platform,
        householdId: row.household_id,
        hasFcm: row.has_fcm,
        lastSeenAt: row.last_seen_at,
      },
    });
  })
);

router.post(
  "/unregister",
  asyncHandler(async (req, res) => {
    const deviceId = String((req.body && req.body.deviceId) || "")
      .trim()
      .slice(0, 128);
    if (!deviceId) {
      return res.status(400).json({ error: "deviceId обязателен" });
    }
    await query(
      `DELETE FROM device_tokens WHERE user_id = $1 AND device_id = $2`,
      [req.user.id, deviceId]
    );
    res.json({ ok: true });
  })
);

module.exports = router;
