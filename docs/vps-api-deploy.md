# Feed Time API — деплой на свой VPS

Подробная инструкция для Ubuntu-подобного VPS: Docker Compose (API + Postgres + Caddy/TLS), переменные окружения, файрвол, первый аккаунт, бэкапы, обновление. Секреты **не** кладём в git.

Репозиторий: каталог `server/`. Пример env: `server/.env.example`.

---

## 1. Что получите

| Сервис | Роль |
|--------|------|
| **api** | Node.js (Express) — auth, семья, профиль питомцев, синк кормлений, device tokens, WebSocket `/ws` |
| **db** | Postgres 16 |
| **caddy** (опционально) | HTTPS + Let’s Encrypt при наличии домена |

Клиент (веб / Android WebView) в настройках указывает URL сервера, регистрируется и создаёт/входит в семью по коду.

---

## 2. Требования к VPS

- Ubuntu 22.04 / 24.04 (или Debian 12+)
- 1 vCPU, ≥1 ГБ RAM (удобнее 2 ГБ)
- Открытые порты: **22** (SSH), **80/443** (если Caddy + домен), либо один порт API при работе по IP
- Docker Engine + Docker Compose plugin

### Установка Docker (кратко)

```bash
sudo apt update
sudo apt install -y ca-certificates curl
sudo install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg | sudo gpg --dearmor -o /etc/apt/keyrings/docker.gpg
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] \
  https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo $VERSION_CODENAME) stable" \
  | sudo tee /etc/apt/sources.list.d/docker.list > /dev/null
sudo apt update
sudo apt install -y docker-ce docker-ce-cli containerd.io docker-compose-plugin
sudo usermod -aG docker "$USER"
# перелогиньтесь, затем:
docker version
docker compose version
```

---

## 3. Клон и секреты

```bash
sudo mkdir -p /opt/feed-time && sudo chown "$USER":"$USER" /opt/feed-time
cd /opt/feed-time
git clone https://github.com/harkonlord-psixoz/feed-time.git .
cd server
cp .env.example .env
nano .env   # или vim
```

Обязательно смените:

| Переменная | Смысл |
|------------|--------|
| `POSTGRES_PASSWORD` | Пароль БД (длинный, случайный) |
| `JWT_SECRET` | Секрет подписи JWT (≥32 символа, случайный) |
| `CORS_ORIGIN` | Обычно `*` для WebView; или список origins через запятую |
| `DOMAIN` | Домен для Let’s Encrypt (если есть) |
| `API_BIND` | По умолчанию `127.0.0.1:3080` (только localhost) |

Генерация секретов:

```bash
openssl rand -base64 32   # → JWT_SECRET / POSTGRES_PASSWORD
```

Файл `.env` в `.gitignore` — **не коммитьте**.

---

## 4. Два сценария доступа

### A) Есть домен → Let’s Encrypt (рекомендуется)

1. A-запись DNS: `feed.example.com` → IP VPS.
2. В `.env`: `DOMAIN=feed.example.com`, `API_BIND=127.0.0.1:3080`.
3. Запуск:

```bash
cd /opt/feed-time/server
docker compose --profile tls up -d --build
```

Caddy слушает 80/443, проксирует на API, сам получает сертификат.

Проверка:

```bash
curl -sS https://feed.example.com/api/health
# {"ok":true,"service":"feed-time-api",...}
```

В приложении URL сервера: `https://feed.example.com` (без хвоста `/api`).

### B) Только IP → HTTP или self-signed

**Вариант B1 — HTTP по IP (быстрый старт / домашняя сеть):**

```bash
# В .env:
# API_BIND=0.0.0.0:3080
# DOMAIN=   (пусто, профиль tls не включаем)

docker compose up -d --build
curl -sS http://ВАШ_IP:3080/api/health
```

В клиенте: `http://ВАШ_IP:3080`.

> Android WebView в сборке Feed Time разрешает cleartext (HTTP) для своего VPS. В браузере на телефоне HTTP тоже ок, но в публичном интернете лучше HTTPS.

**Вариант B2 — self-signed HTTPS (без домена):**

1. Поднимите API на localhost (`API_BIND=127.0.0.1:3080`).
2. Поставьте nginx или Caddy с самоподписанным сертификатом:

```bash
sudo openssl req -x509 -nodes -days 825 -newkey rsa:2048 \
  -keyout /etc/ssl/private/feedtime-self.key \
  -out /etc/ssl/certs/feedtime-self.crt \
  -subj "/CN=ВАШ_IP"
```

Пример nginx (фрагмент):

```nginx
server {
  listen 443 ssl;
  server_name _;
  ssl_certificate     /etc/ssl/certs/feedtime-self.crt;
  ssl_certificate_key /etc/ssl/private/feedtime-self.key;
  location / {
    proxy_pass http://127.0.0.1:3080;
    proxy_http_version 1.1;
    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection "upgrade";
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
  }
}
```

Клиент: `https://ВАШ_IP`. На телефоне может понадобиться принять предупреждение о сертификате (системный WebView иногда капризничает — тогда проще домен + Let’s Encrypt или HTTP в LAN).

---

## 5. Файрвол (ufw)

```bash
sudo ufw default deny incoming
sudo ufw default allow outgoing
sudo ufw allow OpenSSH
# домен + Caddy:
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp
# ИЛИ только API по IP:
# sudo ufw allow 3080/tcp
sudo ufw enable
sudo ufw status
```

Postgres **не** публикуйте наружу (в `docker-compose.yml` порт БД не проброшен — так и оставьте).

---

## 6. Первый админ / семья

Отдельной роли «системный админ» нет: первый пользователь регистрируется из приложения (Настройки → Семья / сервер) или через API:

```bash
curl -sS -X POST https://feed.example.com/api/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"email":"you@example.com","password":"минимум8символов","displayName":"Мама"}'
```

Затем создайте семью (нужен Bearer accessToken из ответа register/login):

```bash
curl -sS -X POST https://feed.example.com/api/households \
  -H "Authorization: Bearer ACCESS_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"name":"Дом"}'
```

В ответе будет `inviteCode` — им делятся с остальными. Второй человек: регистрация → «Вступить по коду».

Роли: **owner** (создатель) и **member**. Владелец может сменить invite-код: `POST /api/households/invite/rotate`.

---

## 7. API (кратко)

| Метод | Путь | Описание |
|-------|------|----------|
| GET | `/api/health` | Жив ли сервис и БД |
| POST | `/api/auth/register` | Регистрация |
| POST | `/api/auth/login` | Вход |
| POST | `/api/auth/refresh` | Обновление JWT |
| GET | `/api/auth/me` | Текущий пользователь + семья |
| POST | `/api/households` | Создать семью (можно сразу `pets` + `updatedAt`) |
| POST | `/api/households/join` | Вступить по коду (+ опционально migrate профиля LWW) |
| POST | `/api/households/leave` | Выйти |
| GET | `/api/households/profile` | Профиль питомцев семьи |
| PUT | `/api/households/profile` | Upsert профиля (LWW по `updatedAt`) |
| GET | `/api/feedings?from=&to=` | Выгрузка слотов |
| POST | `/api/feedings/sync` | Пакетный upsert (LWW + clientId); шлёт `family_feed` |
| POST | `/api/devices/register` | Реестр устройства (`deviceId`, `platform`, опц. `fcmToken`) |
| POST | `/api/devices/unregister` | Снять устройство |
| WS | `/ws?token=ACCESS` | `feeding` / `profile` / `family_feed` |

CORS настроен под веб и Android WebView (`CORS_ORIGIN`).

После `git pull` на VPS: `docker compose up -d --build` и миграции (`npm run migrate` / entrypoint) — нужна **002_household_profile_devices.sql**.

Опционально в `.env`: `FCM_SERVER_KEY=…` (legacy FCM) — фоновые пуши на токены из `device_tokens`. Без ключа семейные оповещения идут по WebSocket, пока приложение/вкладка живы.

---

## 8. Бэкапы Postgres

Том Docker: `feedtime_pg`.

Ежедневный дамп (cron от root или пользователя с доступом к docker):

```bash
sudo mkdir -p /var/backups/feed-time
cat <<'EOF' | sudo tee /usr/local/bin/feedtime-pg-dump.sh
#!/bin/bash
set -euo pipefail
STAMP=$(date -u +%Y%m%dT%H%M%SZ)
FILE=/var/backups/feed-time/feedtime-$STAMP.sql.gz
cd /opt/feed-time/server
docker compose exec -T db pg_dump -U "$POSTGRES_USER" "$POSTGRES_DB" | gzip > "$FILE"
# хранить 14 дней
find /var/backups/feed-time -name 'feedtime-*.sql.gz' -mtime +14 -delete
EOF
```

Подставьте пользователя/БД из `.env` или экспортируйте их в скрипте:

```bash
sudo sed -i '1a set -a; source /opt/feed-time/server/.env; set +a' /usr/local/bin/feedtime-pg-dump.sh
sudo chmod +x /usr/local/bin/feedtime-pg-dump.sh
sudo crontab -e
# 0 3 * * * /usr/local/bin/feedtime-pg-dump.sh
```

Восстановление:

```bash
gunzip -c /var/backups/feed-time/feedtime-….sql.gz \
  | docker compose exec -T db psql -U feedtime -d feedtime
```

---

## 9. Обновление API

```bash
cd /opt/feed-time
git pull origin main
cd server
docker compose up -d --build
# с TLS-профилем:
# docker compose --profile tls up -d --build
docker compose logs -f --tail=100 api
```

Миграции SQL применяются автоматически при старте контейнера (`node src/migrate.js && node src/index.js`).

Откат: предыдущий git tag/commit + тот же `docker compose up -d --build`; при необходимости восстановите дамп БД.

---

## 10. Локальная разработка без Docker Compose (опционально)

Нужен локальный Postgres и `.env` с `DATABASE_URL`.

```bash
cd server
cp .env.example .env
# DATABASE_URL=postgres://feedtime:...@127.0.0.1:5432/feedtime
npm install
npm run migrate
npm run dev
# → http://127.0.0.1:3080/api/health
```

---

## 11. Безопасность — чеклист

- [ ] `.env` только на сервере, права `chmod 600 .env`
- [ ] SSH по ключу, `PasswordAuthentication no`
- [ ] ufw включён; Postgres не торчит наружу
- [ ] Сильные `POSTGRES_PASSWORD` и `JWT_SECRET`
- [ ] По возможности домен + Let’s Encrypt вместо голого HTTP в интернете
- [ ] Регулярные бэкапы и проверка восстановления раз в квартал
- [ ] `docker compose logs` при странностях; не публикуйте access/refresh токены

---

## 12. Клиент

### Браузер vs API (важно)

| Что | URL (пример на VPS HarkonLord) | Кто открывает |
|-----|--------------------------------|---------------|
| **Веб-UI** (статический `web/`) | `https://app.harkonlord.su` (или путь на сайте) | люди **без APK** в браузере |
| **API** (Node на :3080 за nginx) | `http://api.harkonlord.su` | приложение / веб-UI в настройках синка |

Сайт и API — **разные** origin’ы. В UI поле «URL сервера» — это **только API**, не адрес страницы календаря.

На текущем VPS:

- **443** занят **Xray** → API отдаём по **HTTP** (`http://api.harkonlord.su` → nginx → `127.0.0.1:3080`), не через HTTPS на том же порту.
- Клиент Feed Time (веб и Android WebView) умеет cleartext HTTP к своему VPS.
- Дефолт в `web/index.html`: meta `feed-time-default-api` = `http://api.harkonlord.su` (если в `localStorage` ещё нет своего URL).

В приложении / на сайте → **Настройки → Сервер и семья**:

1. URL API (`http://api.harkonlord.su` по умолчанию; или свой `http://IP:3080` / HTTPS-домен)
2. Регистрация / вход
3. Создать семью или ввести invite-код
4. Календарь синкается (офлайн-очередь + `localStorage`); тема и прочие `mur-calendar-*` ключи остаются локальными

Подробнее о модели данных: `docs/family-sync-and-notifications.md` (бэкенд в v1 — свой API на VPS, не Supabase).

---

## 13. Статический веб-клиент (nginx), не ломая placeholder на apex

Исходники UI: каталог **`web/`** в репозитории (один `index.html`, без сборки). Тот же файл кладётся в Android `assets/`.

**Рекомендация:** отдельный поддомен `app.harkonlord.su` (или `feed.harkonlord.su`), чтобы корневой `harkonlord.su` с placeholder-шаблоном не трогать.

### 13.1. Файлы на сервере

```bash
# если репо уже в /opt/feed-time (как для API):
sudo mkdir -p /var/www/feed-time
sudo rsync -a --delete /opt/feed-time/web/ /var/www/feed-time/
# после git pull + обновления UI:
# sudo rsync -a --delete /opt/feed-time/web/ /var/www/feed-time/
```

DNS: A-запись `app` → IP VPS (как у apex / api).

### 13.2. nginx — поддомен приложения (предпочтительно)

Новый сайт, **не** меняя root у `harkonlord.su`:

```nginx
# /etc/nginx/sites-available/feed-time-web
server {
  listen 80;
  listen [::]:80;
  server_name app.harkonlord.su;

  root /var/www/feed-time;
  index index.html;

  # SPA-ish single page: всё на index.html
  location / {
    try_files $uri $uri/ /index.html;
  }

  # долгий кэш только если добавите хешированные ассеты; пока HTML лучше без агрессивного кэша
  location = /index.html {
    add_header Cache-Control "no-cache";
  }
}
```

```bash
sudo ln -sf /etc/nginx/sites-available/feed-time-web /etc/nginx/sites-enabled/
sudo nginx -t && sudo systemctl reload nginx
```

Если для apex уже есть HTTPS/сертификат и **443 свободен для nginx** — можно добавить `listen 443 ssl` с тем же `root`. Пока 443 держит Xray, пользователям достаточно **HTTP** на `app.` или TLS на другом порту/CDN — как решите на VPS.

`CORS_ORIGIN` у API: `*` (как в `.env.example`) или явный список с origin веб-UI, например:

```bash
CORS_ORIGIN=http://app.harkonlord.su,https://app.harkonlord.su,http://harkonlord.su,https://harkonlord.su
```

### 13.3. Альтернатива — путь на существующем сайте

Если не хотите поддомен, можно отдать UI с пути, **не затирая** текущий document root placeholder:

```nginx
# внутри server { ... } для harkonlord.su — ДОБАВИТЬ, не меняя location /
location /feed/ {
  alias /var/www/feed-time/;
  try_files $uri $uri/ /feed/index.html;
}
```

Открывать: `https://harkonlord.su/feed/` (или http). Placeholder на `/` остаётся.

### 13.4. Проверка

```bash
curl -sS -o /dev/null -w "%{http_code}\n" http://app.harkonlord.su/
curl -sS http://api.harkonlord.su/api/health
# в браузере: открыть сайт → Настройки → поле API должно быть http://api.harkonlord.su
```
