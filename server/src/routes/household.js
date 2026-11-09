"use strict";

const express = require("express");
const { query, withClient } = require("../db");
const { generateInviteCode } = require("../invite");
const {
  asyncHandler,
  requireAuth,
  requireHousehold,
  loadMembership,
} = require("../middleware");

const router = express.Router();

router.use(requireAuth);

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
    res.json({
      household: {
        id: membership.household_id,
        name: membership.name,
        role: membership.role,
        inviteCode: membership.invite_code,
        ownerId: membership.owner_id,
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

    const household = await withClient(async (client) => {
      await client.query("BEGIN");
      try {
        const code = await uniqueInviteCode(client);
        const h = await client.query(
          `INSERT INTO households (name, invite_code, owner_id)
           VALUES ($1, $2, $3)
           RETURNING id, name, invite_code, owner_id, created_at`,
          [name, code, req.user.id]
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
    res.json({
      household: {
        id: h.id,
        name: h.name,
        role: "member",
        inviteCode: h.invite_code,
        ownerId: h.owner_id,
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

module.exports = router;
