"use strict";

const express = require("express");
const { query, withClient } = require("../db");
const { generateInviteCode } = require("../invite");
const hub = require("../hub");
const {
  normalizePetsPayload,
  parseUpdatedAt,
  mapProfile,
  shouldApplyClient,
} = require("../profile");
const {
  asyncHandler,
  requireAuth,
  requireHousehold,
  loadMembership,
} = require("../middleware");

const router = express.Router();

router.use(requireAuth);

async function loadHouseholdProfile(householdId) {
  const res = await query(
    `SELECT pets_json, pets_updated_at FROM households WHERE id = $1`,
    [householdId]
  );
  return mapProfile(res.rows[0]);
}

async function uniqueInviteCode(client) {
  for (let i = 0; i < 12; i++) {
    const code = generateInviteCode(8);
    const q = client ? client.query.bind(client) : query;
    const exists = await q(
      "SELECT 1 FROM households WHERE invite_code = $1",
      [code]
    );
    if (exists.rowCount === 0) return code;
  }
  throw Object.assign(new Error("Не удалось создать код приглашения"), {
    status: 500,
  });
}

router.get(
  "/mine",
  asyncHandler(async (req, res) => {
    const membership = await loadMembership(req.user.id);
    if (!membership) {
      return res.json({ household: null });
    }
    const members = await query(
      `SELECT u.id, u.email, u.display_name, hm.role, hm.joined_at
       FROM household_members hm
       JOIN users u ON u.id = hm.user_id
       WHERE hm.household_id = $1
       ORDER BY hm.joined_at ASC`,
      [membership.household_id]
    );
    const profile = await loadHouseholdProfile(membership.household_id);
    res.json({
      household: {
        id: membership.household_id,
        name: membership.name,
        role: membership.role,
        inviteCode: membership.invite_code,
        ownerId: membership.owner_id,
        profile,
        members: members.rows.map((m) => ({
          id: m.id,
          email: m.email,
          displayName: m.display_name,
          role: m.role,
          joinedAt: m.joined_at,
        })),
      },
    });
  })
);

router.post(
  "/",
  asyncHandler(async (req, res) => {
    const name = String((req.body && req.body.name) || "Семья").trim() || "Семья";
    const existing = await loadMembership(req.user.id);
    if (existing) {
      return res
        .status(409)
        .json({ error: "Вы уже состоите в семье — сначала выйдите" });
    }

    const initialPets = normalizePetsPayload(
      (req.body && (req.body.pets || req.body.profile)) || null
    );
    const initialUpdatedAt =
      parseUpdatedAt(req.body && req.body.updatedAt) || new Date();

    const household = await withClient(async (client) => {
      await client.query("BEGIN");
      try {
        const code = await uniqueInviteCode(client);
        const h = await client.query(
          `INSERT INTO households (name, invite_code, owner_id, pets_json, pets_updated_at)
           VALUES ($1, $2, $3, $4::jsonb, $5)
           RETURNING id, name, invite_code, owner_id, created_at, pets_json, pets_updated_at`,
          [
            name,
            code,
            req.user.id,
            JSON.stringify(initialPets),
            initialUpdatedAt.toISOString(),
          ]
        );
        await client.query(
          `INSERT INTO household_members (household_id, user_id, role)
           VALUES ($1, $2, 'owner')`,
          [h.rows[0].id, req.user.id]
        );
        await client.query("COMMIT");
        return h.rows[0];
      } catch (err) {
        await client.query("ROLLBACK");
        throw err;
      }
    });

    res.status(201).json({
      household: {
        id: household.id,
        name: household.name,
        role: "owner",
        inviteCode: household.invite_code,
        ownerId: household.owner_id,
        profile: mapProfile(household),
      },
    });
  })
);

router.post(
  "/join",
  asyncHandler(async (req, res) => {
    const inviteCode = String((req.body && req.body.inviteCode) || "")
      .trim()
      .toUpperCase();
    if (!inviteCode) {
      return res.status(400).json({ error: "Укажите код приглашения" });
    }
    const existing = await loadMembership(req.user.id);
    if (existing) {
      return res
        .status(409)
        .json({ error: "Вы уже состоите в семье — сначала выйдите" });
    }

    const found = await query(
      `SELECT id, name, invite_code, owner_id FROM households WHERE invite_code = $1`,
      [inviteCode]
    );
    if (found.rowCount === 0) {
      return res.status(404).json({ error: "Код приглашения не найден" });
    }
    const h = found.rows[0];
    await query(
      `INSERT INTO household_members (household_id, user_id, role)
       VALUES ($1, $2, 'member')
       ON CONFLICT DO NOTHING`,
      [h.id, req.user.id]
    );

    /* Optional migrate: LWW-merge local pets from joiner into household */
    const clientPets = req.body && (req.body.pets || req.body.profile);
    const clientUpdatedAt = parseUpdatedAt(req.body && req.body.updatedAt);
    if (clientPets && clientUpdatedAt) {
      const merged = await withClient(async (client) => {
        await client.query("BEGIN");
        try {
          const current = await client.query(
            `SELECT pets_json, pets_updated_at FROM households WHERE id = $1 FOR UPDATE`,
            [h.id]
          );
          if (
            current.rowCount > 0 &&
            shouldApplyClient(current.rows[0].pets_updated_at, clientUpdatedAt)
          ) {
            const pets = normalizePetsPayload(clientPets);
            await client.query(
              `UPDATE households SET pets_json = $1::jsonb, pets_updated_at = $2 WHERE id = $3`,
              [JSON.stringify(pets), clientUpdatedAt.toISOString(), h.id]
            );
            await client.query("COMMIT");
            return { pets, updatedAt: clientUpdatedAt.toISOString() };
          }
          await client.query("COMMIT");
          return null;
        } catch (err) {
          await client.query("ROLLBACK");
          throw err;
        }
      });
      if (merged) {
        hub.broadcast(h.id, { type: "profile", profile: merged });
      }
    }

    const profile = await loadHouseholdProfile(h.id);
    res.json({
      household: {
        id: h.id,
        name: h.name,
        role: "member",
        inviteCode: h.invite_code,
        ownerId: h.owner_id,
        profile,
      },
    });
  })
);

router.post(
  "/leave",
  requireHousehold,
  asyncHandler(async (req, res) => {
    const { household_id: householdId, role, owner_id: ownerId } =
      req.membership;

    if (role === "owner") {
      const others = await query(
        `SELECT COUNT(*)::int AS n FROM household_members
         WHERE household_id = $1 AND user_id <> $2`,
        [householdId, req.user.id]
      );
      if (others.rows[0].n > 0) {
        return res.status(400).json({
          error:
            "Владелец не может выйти, пока в семье есть участники. Передайте владение или удалите семью.",
        });
      }
      await query("DELETE FROM households WHERE id = $1", [householdId]);
      return res.json({ ok: true, deleted: true });
    }

    await query(
      `DELETE FROM household_members WHERE household_id = $1 AND user_id = $2`,
      [householdId, req.user.id]
    );
    res.json({ ok: true, deleted: false, ownerId });
  })
);

router.post(
  "/invite/rotate",
  requireHousehold,
  asyncHandler(async (req, res) => {
    if (req.membership.role !== "owner") {
      return res.status(403).json({ error: "Только владелец может сменить код" });
    }
    const code = await uniqueInviteCode();
    await query(`UPDATE households SET invite_code = $1 WHERE id = $2`, [
      code,
      req.membership.household_id,
    ]);
    res.json({ inviteCode: code });
  })
);

router.get(
  "/profile",
  requireHousehold,
  asyncHandler(async (req, res) => {
    const profile = await loadHouseholdProfile(req.membership.household_id);
    res.json({ profile });
  })
);

/**
 * PUT body: { pets: {version, pets:[{species,name}]}, updatedAt: ISO }
 * Last-write-wins by updatedAt. Stale writes return current profile with applied:false.
 */
router.put(
  "/profile",
  requireHousehold,
  asyncHandler(async (req, res) => {
    const householdId = req.membership.household_id;
    const pets = normalizePetsPayload(
      (req.body && (req.body.pets || req.body.profile)) || null
    );
    const clientUpdatedAt = parseUpdatedAt(req.body && req.body.updatedAt);
    if (!clientUpdatedAt) {
      return res.status(400).json({ error: "updatedAt обязателен (ISO)" });
    }

    const outcome = await withClient(async (client) => {
      await client.query("BEGIN");
      try {
        const current = await client.query(
          `SELECT pets_json, pets_updated_at FROM households WHERE id = $1 FOR UPDATE`,
          [householdId]
        );
        if (current.rowCount === 0) {
          const err = new Error("Семья не найдена");
          err.status = 404;
          throw err;
        }
        const row = current.rows[0];
        if (!shouldApplyClient(row.pets_updated_at, clientUpdatedAt)) {
          await client.query("COMMIT");
          return {
            applied: false,
            reason: "stale",
            profile: mapProfile(row),
          };
        }
        const updated = await client.query(
          `UPDATE households
           SET pets_json = $1::jsonb, pets_updated_at = $2
           WHERE id = $3
           RETURNING pets_json, pets_updated_at`,
          [JSON.stringify(pets), clientUpdatedAt.toISOString(), householdId]
        );
        await client.query("COMMIT");
        return {
          applied: true,
          profile: mapProfile(updated.rows[0]),
        };
      } catch (err) {
        await client.query("ROLLBACK");
        throw err;
      }
    });

    if (outcome.applied) {
      hub.broadcast(householdId, {
        type: "profile",
        profile: outcome.profile,
      });
    }

    res.json(outcome);
  })
);

module.exports = router;
