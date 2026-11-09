"use strict";

require("dotenv").config();

const http = require("http");
const express = require("express");
const cors = require("cors");
const { WebSocketServer } = require("ws");
const { URL } = require("url");

const { getPool } = require("./db");
const { verifyAccessToken } = require("./auth");
const { loadMembership, errorHandler } = require("./middleware");
const hub = require("./hub");
const authRoutes = require("./routes/auth");
const householdRoutes = require("./routes/household");
const feedingsRoutes = require("./routes/feedings");
const devicesRoutes = require("./routes/devices");

const PORT = Number(process.env.PORT || 3080);
const HOST = process.env.HOST || "0.0.0.0";

function corsOrigin() {
  const raw = process.env.CORS_ORIGIN || "*";
  if (raw === "*") return true;
  return raw.split(",").map((s) => s.trim()).filter(Boolean);
}

async function main() {
  /* Fail fast if DB unreachable */
  await getPool().query("SELECT 1");

  const app = express();
  app.set("trust proxy", 1);
  app.use(
    cors({
      origin: corsOrigin(),
      credentials: true,
      allowedHeaders: ["Content-Type", "Authorization"],
      methods: ["GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"],
    })
  );
  app.use(express.json({ limit: "1mb" }));

  app.get("/api/health", async (req, res) => {
    try {
      await getPool().query("SELECT 1");
      res.json({
        ok: true,
        service: "feed-time-api",
        time: new Date().toISOString(),
      });
    } catch (err) {
      res.status(503).json({ ok: false, error: "database unavailable" });
    }
  });

  app.use("/api/auth", authRoutes);
  app.use("/api/households", householdRoutes);
  app.use("/api/feedings", feedingsRoutes);
  app.use("/api/devices", devicesRoutes);

  app.use(errorHandler);

  const server = http.createServer(app);
  const wss = new WebSocketServer({ server, path: "/ws" });

  wss.on("connection", async (ws, req) => {
    try {
      const url = new URL(req.url || "", `http://${req.headers.host || "localhost"}`);
      const token =
        url.searchParams.get("token") ||
        (req.headers.authorization || "").replace(/^Bearer\s+/i, "");
      if (!token) {
        ws.close(4401, "unauthorized");
        return;
      }
      const payload = verifyAccessToken(token);
      const membership = await loadMembership(payload.sub);
      if (!membership) {
        ws.close(4404, "no household");
        return;
      }
      hub.join(membership.household_id, ws);
      ws.send(
        JSON.stringify({
          type: "hello",
          householdId: membership.household_id,
        })
      );
      ws.on("message", (raw) => {
        /* Client pings / ignore */
        try {
          const msg = JSON.parse(String(raw));
          if (msg && msg.type === "ping") {
            ws.send(JSON.stringify({ type: "pong" }));
          }
        } catch (_) {
          /* ignore */
        }
      });
      ws.on("close", () => hub.leave(ws));
      ws.on("error", () => hub.leave(ws));
    } catch (err) {
      try {
        ws.close(4401, "unauthorized");
      } catch (_) {
        /* ignore */
      }
    }
  });

  server.listen(PORT, HOST, () => {
    console.log(`feed-time-api listening on http://${HOST}:${PORT}`);
  });
}

main().catch((err) => {
  console.error("Fatal:", err);
  process.exit(1);
});
