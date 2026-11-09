"use strict";

const express = require("express");
const { query, withClient } = require("../db");
const hub = require("../hub");
const { broadcastFamilyFeed } = require("../notify");
const {
  asyncHandler,
  requireAuth,
  requireHousehold,
} = require("../middleware");

const router = express.Router();

router.use(requireAuth, requireHousehold);

function mapFeeding(row) {
  return {
    id: row.id,
    householdId: row.household_id,
    dayDate: formatDay(row.day_date),
    slotIndex: row.slot_index,
    time: row.time_value || "",
    clientId: row.client_id,
    fedByUserId: row.fed_by_user_id,
    fedByName: row.fed_by_name || "",
    updatedAt: new Date(row.updated_at).toISOString(),
    createdAt: new Date(row.created_at).toISOString(),
  };
}

function formatDay(value) {
  if (!value) return "";
  if (typeof value === "string") return value.slice(0, 10);
  const d = new Date(value);
  const y = d.getUTCFullYear();
  const m = String(d.getUTCMonth() + 1).padStart(2, "0");
  const day = String(d.getUTCDate()).padStart(2, "0");
  return `${y}-${m}-${day}`;
}

function parseIncoming(item) {
  const dayDate = String(item.dayDate || item.day || "").slice(0, 10);
  const slotIndex = Number(item.slotIndex);
  const time = item.time == null ? "" : String(item.time).slice(0, 8);
  const clientId = String(item.clientId || "").trim();
  const updatedAt = item.updatedAt
    ? new Date(item.updatedAt)
    : new Date();
  if (!/^\d{4}-\d{2}-\d{2}$/.test(dayDate)) {
    return { error: "Некорректная дата dayDate" };
  }
  if (!Number.isInteger(slotIndex) || slotIndex < 0 || slotIndex > 31) {
    return { error: "Некорректный slotIndex" };
  }
  if (!clientId) {
    return { error: "clientId обязателен" };
  }
  if (Number.isNaN(updatedAt.getTime())) {
    return { error: "Некорректный updatedAt" };
  }
  return { dayDate, slotIndex, time, clientId, updatedAt };
}

/**
 * Last-write-wins by updatedAt; clientId is idempotent within household.
 */
async function upsertFeeding(client, householdId, user, parsed) {
  const byClient = await client.query(
    `SELECT * FROM feedings
     WHERE household_id = $1 AND client_id = $2
     FOR UPDATE`,
    [householdId, parsed.clientId]
  );

  if (byClient.rowCount > 0) {
    const row = byClient.rows[0];
    if (new Date(row.updated_at) >= parsed.updatedAt) {
      return { feeding: mapFeeding(row), applied: false, reason: "stale-client" };
    }
    /* Same clientId may move to another slot — free target slot if needed */
    if (
      formatDay(row.day_date) !== parsed.dayDate ||
      row.slot_index !== parsed.slotIndex
    ) {
      await client.query(
        `DELETE FROM feedings
         WHERE household_id = $1 AND day_date = $2 AND slot_index = $3
           AND client_id <> $4`,
        [householdId, parsed.dayDate, parsed.slotIndex, parsed.clientId]
      );
    }
    const updated = await client.query(
      `UPDATE feedings SET
         day_date = $1,
         slot_index = $2,
         time_value = $3,
         fed_by_user_id = $4,
         fed_by_name = $5,
         updated_at = $6
       WHERE id = $7
       RETURNING *`,
      [
        parsed.dayDate,
        parsed.slotIndex,
        parsed.time,
        user.id,
        user.displayName || "",
        parsed.updatedAt.toISOString(),
        row.id,
      ]
    );
    return { feeding: mapFeeding(updated.rows[0]), applied: true };
  }

  const bySlot = await client.query(
    `SELECT * FROM feedings
     WHERE household_id = $1 AND day_date = $2 AND slot_index = $3
     FOR UPDATE`,
    [householdId, parsed.dayDate, parsed.slotIndex]
  );

  if (bySlot.rowCount > 0) {
    const row = bySlot.rows[0];
    if (new Date(row.updated_at) >= parsed.updatedAt) {
      return { feeding: mapFeeding(row), applied: false, reason: "stale-slot" };
    }
    const updated = await client.query(
      `UPDATE feedings SET
         time_value = $1,
         client_id = $2,
         fed_by_user_id = $3,
         fed_by_name = $4,
         updated_at = $5
       WHERE id = $6
       RETURNING *`,
      [
        parsed.time,
        parsed.clientId,
        user.id,
        user.displayName || "",
        parsed.updatedAt.toISOString(),
        row.id,
      ]
    );
    return { feeding: mapFeeding(updated.rows[0]), applied: true };
  }

  const inserted = await client.query(
    `INSERT INTO feedings (
       household_id, day_date, slot_index, time_value, client_id,
       fed_by_user_id, fed_by_name, updated_at
     ) VALUES ($1,$2,$3,$4,$5,$6,$7,$8)
     RETURNING *`,
    [
      householdId,
      parsed.dayDate,
      parsed.slotIndex,
      parsed.time,
      parsed.clientId,
      user.id,
      user.displayName || "",
      parsed.updatedAt.toISOString(),
    ]
  );
  return { feeding: mapFeeding(inserted.rows[0]), applied: true };
}

router.get(
  "/",
  asyncHandler(async (req, res) => {
    const from = String(req.query.from || "").slice(0, 10);
    const to = String(req.query.to || "").slice(0, 10);
    const params = [req.membership.household_id];
    let sql = `SELECT * FROM feedings WHERE household_id = $1`;
    if (/^\d{4}-\d{2}-\d{2}$/.test(from)) {
      params.push(from);
      sql += ` AND day_date >= $${params.length}`;
    }
    if (/^\d{4}-\d{2}-\d{2}$/.test(to)) {
      params.push(to);
      sql += ` AND day_date <= $${params.length}`;
    }
    sql += ` ORDER BY day_date ASC, slot_index ASC`;
    const result = await query(sql, params);
    res.json({ feedings: result.rows.map(mapFeeding) });
  })
);

router.post(
  "/sync",
  asyncHandler(async (req, res) => {
    const list = (req.body && req.body.feedings) || [];
    if (!Array.isArray(list)) {
      return res.status(400).json({ error: "feedings должен быть массивом" });
    }
    if (list.length > 500) {
      return res.status(400).json({ error: "Слишком много записей за раз" });
    }

    const householdId = req.membership.household_id;
    const results = [];

    await withClient(async (client) => {
      for (const item of list) {
        const parsed = parseIncoming(item || {});
        if (parsed.error) {
          results.push({ ok: false, error: parsed.error, clientId: item && item.clientId });
          continue;
        }
        await client.query("BEGIN");
        try {
          const outcome = await upsertFeeding(client, householdId, req.user, parsed);
          await client.query("COMMIT");
          results.push({
            ok: true,
            applied: outcome.applied,
            reason: outcome.reason || null,
            feeding: outcome.feeding,
          });
          if (outcome.applied) {
            hub.broadcast(householdId, {
              type: "feeding",
              feeding: outcome.feeding,
            });
            broadcastFamilyFeed(householdId, outcome.feeding, req.user);
          }
        } catch (err) {
          await client.query("ROLLBACK");
          results.push({
            ok: false,
            error: err.message || "Ошибка записи",
            clientId: parsed.clientId,
          });
        }
      }
    });

    res.json({ results });
  })
);

module.exports = router;
