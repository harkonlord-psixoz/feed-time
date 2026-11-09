"use strict";

const express = require("express");
const { query } = require("../db");
const {
  hashPassword,
  verifyPassword,
  issueTokens,
  rotateRefreshToken,
  revokeRefreshToken,
  publicUser,
  normalizeEmail,
  validatePassword,
} = require("../auth");
const { asyncHandler, requireAuth, loadMembership } = require("../middleware");

const router = express.Router();

router.post(
  "/register",
  asyncHandler(async (req, res) => {
    const email = normalizeEmail(req.body && req.body.email);
    const password = req.body && req.body.password;
    const displayName = String(
      (req.body && req.body.displayName) || ""
    ).trim();

    if (!email || !email.includes("@")) {
      return res.status(400).json({ error: "Укажите корректный email" });
    }
    validatePassword(password);
    if (!displayName || displayName.length < 1) {
      return res.status(400).json({ error: "Укажите имя" });
    }

    const existing = await query("SELECT id FROM users WHERE email = $1", [
      email,
    ]);
    if (existing.rowCount > 0) {
      return res.status(409).json({ error: "Email уже зарегистрирован" });
    }

    const passwordHash = await hashPassword(password);
    const inserted = await query(
      `INSERT INTO users (email, password_hash, display_name)
       VALUES ($1, $2, $3)
       RETURNING id, email, display_name`,
      [email, passwordHash, displayName]
    );
    const tokens = await issueTokens(inserted.rows[0]);
    res.status(201).json(tokens);
  })
);

router.post(
  "/login",
  asyncHandler(async (req, res) => {
    const email = normalizeEmail(req.body && req.body.email);
    const password = req.body && req.body.password;
    if (!email || !password) {
      return res.status(400).json({ error: "Email и пароль обязательны" });
    }
    const found = await query(
      `SELECT id, email, display_name, password_hash FROM users WHERE email = $1`,
      [email]
    );
    if (found.rowCount === 0) {
      return res.status(401).json({ error: "Неверный email или пароль" });
    }
    const user = found.rows[0];
    const ok = await verifyPassword(password, user.password_hash);
    if (!ok) {
      return res.status(401).json({ error: "Неверный email или пароль" });
    }
    const tokens = await issueTokens(user);
    res.json(tokens);
  })
);

router.post(
  "/refresh",
  asyncHandler(async (req, res) => {
    const refreshToken = req.body && req.body.refreshToken;
    if (!refreshToken) {
      return res.status(400).json({ error: "refreshToken обязателен" });
    }
    const tokens = await rotateRefreshToken(refreshToken);
    res.json(tokens);
  })
);

router.post(
  "/logout",
  asyncHandler(async (req, res) => {
    await revokeRefreshToken(req.body && req.body.refreshToken);
    res.json({ ok: true });
  })
);

router.get(
  "/me",
  requireAuth,
  asyncHandler(async (req, res) => {
    const userRes = await query(
      `SELECT id, email, display_name FROM users WHERE id = $1`,
      [req.user.id]
    );
    if (userRes.rowCount === 0) {
      return res.status(404).json({ error: "Пользователь не найден" });
    }
    const membership = await loadMembership(req.user.id);
    let household = null;
    if (membership) {
      const profileRes = await query(
        `SELECT pets_json, pets_updated_at FROM households WHERE id = $1`,
        [membership.household_id]
      );
      const { mapProfile } = require("../profile");
      household = {
        id: membership.household_id,
        name: membership.name,
        role: membership.role,
        inviteCode: membership.invite_code,
        ownerId: membership.owner_id,
        profile: mapProfile(profileRes.rows[0]),
      };
    }
    res.json({
      user: publicUser(userRes.rows[0]),
      household,
    });
  })
);

module.exports = router;
