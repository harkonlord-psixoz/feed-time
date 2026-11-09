"use strict";

const { verifyAccessToken } = require("./auth");
const { query } = require("./db");

function asyncHandler(fn) {
  return function wrapped(req, res, next) {
    Promise.resolve(fn(req, res, next)).catch(next);
  };
}

function requireAuth(req, res, next) {
  const header = req.headers.authorization || "";
  const match = header.match(/^Bearer\s+(.+)$/i);
  if (!match) {
    return res.status(401).json({ error: "Требуется авторизация" });
  }
  try {
    const payload = verifyAccessToken(match[1]);
    req.user = {
      id: payload.sub,
      email: payload.email,
      displayName: payload.name,
    };
    return next();
  } catch (err) {
    return res.status(401).json({ error: "Токен недействителен или истёк" });
  }
}

async function loadMembership(userId) {
  const res = await query(
    `SELECT hm.household_id, hm.role, h.name, h.invite_code, h.owner_id
     FROM household_members hm
     JOIN households h ON h.id = hm.household_id
     WHERE hm.user_id = $1
     LIMIT 1`,
    [userId]
  );
  return res.rows[0] || null;
}

async function requireHousehold(req, res, next) {
  try {
    const membership = await loadMembership(req.user.id);
    if (!membership) {
      return res.status(404).json({ error: "Вы не состоите в семье" });
    }
    req.membership = membership;
    return next();
  } catch (err) {
    return next(err);
  }
}

function errorHandler(err, req, res, next) {
  const status = err.status || 500;
  if (status >= 500) {
    console.error(err);
  }
  res.status(status).json({
    error: err.message || "Внутренняя ошибка сервера",
  });
}

module.exports = {
  asyncHandler,
  requireAuth,
  requireHousehold,
  loadMembership,
  errorHandler,
};
