"use strict";

const crypto = require("crypto");
const bcrypt = require("bcryptjs");
const jwt = require("jsonwebtoken");
const { v4: uuidv4 } = require("uuid");
const { query } = require("./db");

const BCRYPT_ROUNDS = Number(process.env.BCRYPT_ROUNDS || 12);
const ACCESS_TTL = process.env.JWT_ACCESS_TTL || "15m";
const REFRESH_TTL_DAYS = Number(process.env.JWT_REFRESH_TTL_DAYS || 30);

function jwtSecret() {
  const secret = process.env.JWT_SECRET;
  if (!secret || secret.length < 16) {
    throw new Error("JWT_SECRET must be set (min 16 chars)");
  }
  return secret;
}

function hashPassword(password) {
  return bcrypt.hash(password, BCRYPT_ROUNDS);
}

function verifyPassword(password, hash) {
  return bcrypt.compare(password, hash);
}

function signAccessToken(user) {
  return jwt.sign(
    {
      sub: user.id,
      email: user.email,
      name: user.display_name,
    },
    jwtSecret(),
    { expiresIn: ACCESS_TTL }
  );
}

function verifyAccessToken(token) {
  return jwt.verify(token, jwtSecret());
}

function hashRefreshToken(token) {
  return crypto.createHash("sha256").update(token).digest("hex");
}

function refreshExpiryDate() {
  const d = new Date();
  d.setDate(d.getDate() + REFRESH_TTL_DAYS);
  return d;
}

async function issueTokens(user) {
  const accessToken = signAccessToken(user);
  const refreshToken = uuidv4() + "." + crypto.randomBytes(24).toString("hex");
  const tokenHash = hashRefreshToken(refreshToken);
  const expiresAt = refreshExpiryDate();
  await query(
    `INSERT INTO refresh_tokens (user_id, token_hash, expires_at)
     VALUES ($1, $2, $3)`,
    [user.id, tokenHash, expiresAt.toISOString()]
  );
  return {
    accessToken,
    refreshToken,
    expiresIn: ACCESS_TTL,
    user: publicUser(user),
  };
}

async function rotateRefreshToken(oldRefreshToken) {
  const tokenHash = hashRefreshToken(oldRefreshToken);
  const res = await query(
    `SELECT rt.id, rt.user_id, rt.expires_at, u.email, u.display_name
     FROM refresh_tokens rt
     JOIN users u ON u.id = rt.user_id
     WHERE rt.token_hash = $1`,
    [tokenHash]
  );
  if (res.rowCount === 0) {
    const err = new Error("Недействительный refresh-токен");
    err.status = 401;
    throw err;
  }
  const row = res.rows[0];
  if (new Date(row.expires_at) < new Date()) {
    await query("DELETE FROM refresh_tokens WHERE id = $1", [row.id]);
    const err = new Error("Срок refresh-токена истёк");
    err.status = 401;
    throw err;
  }
  await query("DELETE FROM refresh_tokens WHERE id = $1", [row.id]);
  return issueTokens({
    id: row.user_id,
    email: row.email,
    display_name: row.display_name,
  });
}

async function revokeRefreshToken(refreshToken) {
  if (!refreshToken) return;
  const tokenHash = hashRefreshToken(refreshToken);
  await query("DELETE FROM refresh_tokens WHERE token_hash = $1", [tokenHash]);
}

function publicUser(user) {
  return {
    id: user.id,
    email: user.email,
    displayName: user.display_name,
  };
}

function normalizeEmail(email) {
  return String(email || "")
    .trim()
    .toLowerCase();
}

function validatePassword(password) {
  if (!password || String(password).length < 8) {
    const err = new Error("Пароль не короче 8 символов");
    err.status = 400;
    throw err;
  }
}

module.exports = {
  hashPassword,
  verifyPassword,
  issueTokens,
  rotateRefreshToken,
  revokeRefreshToken,
  verifyAccessToken,
  publicUser,
  normalizeEmail,
  validatePassword,
};
