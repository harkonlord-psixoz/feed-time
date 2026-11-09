# Feed Time API (свой сервер)

Node.js + Express + Postgres + WebSocket. Деплой на VPS:

→ **[docs/vps-api-deploy.md](../docs/vps-api-deploy.md)** (русский гайд: Docker Compose, TLS, бэкапы)

```bash
cp .env.example .env   # заполните секреты
docker compose up -d --build
# с доменом и Let’s Encrypt:
# docker compose --profile tls up -d --build
```

Локально: `npm install && npm run migrate && npm start`.
