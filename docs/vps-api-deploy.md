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
| **Веб-UI** (статический `web/`) | `http://app.harkonlord.su` (**сейчас**); после §14 — `https://app…` | люди **без APK** в браузере |
| **API** (Node на :3080 за nginx) | `http://api.harkonlord.su` (**сейчас**); после §14 — `https://api…` | приложение / веб-UI в настройках синка |

Сайт и API — **разные** origin’ы. В UI поле «URL сервера» — это **только API**, не адрес страницы календаря.

**Сейчас (до §14):** 443 занят Xray / чужим сертификатом — клиенты должны ходить на **HTTP**:

- Дефолт в `web/index.html`: meta `feed-time-default-api` = `http://api.harkonlord.su`.
- Клиент **принудительно** лечит сохранённый `https://api.harkonlord.su` → `http://…` (временный костыль после преждевременного дефолта PR #10; убрать, когда `/api/health` по HTTPS станет OK).
- UI открывать как `http://app.harkonlord.su`. На `https://app.harkonlord.su` сейчас отдаётся **другой** сайт (placeholder Frames), не Feed Time.

**После §14 (HTTPS жив):** сменить дефолт meta/JS на `https://api…`, убрать coerce `https→http`, в настройках указать HTTPS.

В приложении / на сайте → **Настройки → Сервер и семья**:

1. URL API (`http://api.harkonlord.su` по умолчанию; или свой `http://IP:3080`)
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

Новый сайт, **не** меняя root у `harkonlord.su`. Ниже — только HTTP (`:80`); TLS добавляется в §14 **поверх** существующих блоков, без переписывания ваших `location` / `proxy_pass`.

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

API на `api.harkonlord.su` уже должен слушать **только :80** и проксировать на `127.0.0.1:3080` (ваш существующий snippet **не удаляйте**). Типичный каркас (если вдруг нет — создайте отдельно, не трогая apex):

```nginx
# /etc/nginx/sites-available/feed-time-api  — пример; у вас может называться иначе
server {
  listen 80;
  listen [::]:80;
  server_name api.harkonlord.su;

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

### 13.4. Проверка (до TLS)

```bash
curl -sS -o /dev/null -w "%{http_code}\n" http://app.harkonlord.su/
curl -sS http://api.harkonlord.su/api/health
```

После §14 проверяйте уже `https://…` (см. §14.6).

---

## 14. HTTPS для `app.` и `api.` (certbot + nginx, порт 443)

**Цель:** `https://app.harkonlord.su` и `https://api.harkonlord.su` с Let’s Encrypt.

**Проблема:** на VPS порт **443** часто занят **Xray**, а nginx уже отдаёт оба хоста по **HTTP :80**. Certbot HTTP-01 работает через **:80** — сертификаты можно получить **не трогая 443**. Чтобы браузер ходил на стандартный HTTPS, nginx должен слушать **443** (вариант A) либо TLS терминирует Xray по SNI (вариант B).

**Рекомендация: вариант A** — кратко освободить 443 у Xray (остановить или перенести inbound на другой порт) и включить `listen 443 ssl` в nginx. Существующие server-блоки на `:80` и ваши `proxy_pass` / `root` **не ломаем**: только добавляем SSL и при желании редирект HTTP→HTTPS.

### 14.0. Перед началом — диагностика

```bash
# кто слушает 80 и 443
sudo ss -tlnp | grep -E ':80|:443'

# какие server_name уже есть у nginx (не удаляйте чужие сайты)
sudo nginx -T 2>/dev/null | grep -E 'server_name|listen '

# DNS должен указывать на этот VPS
dig +short app.harkonlord.su A
dig +short api.harkonlord.su A
curl -sS -o /dev/null -w "app HTTP %{http_code}\n" http://app.harkonlord.su/
curl -sS -o /dev/null -w "api HTTP %{http_code}\n" http://api.harkonlord.su/api/health
```

Ожидание: оба HTTP отвечают **200** (или health JSON у API). Если нет — сначала почините §13 / API-прокси, TLS не поможет.

### 14.1. Установка certbot

```bash
sudo apt update
sudo apt install -y certbot python3-certbot-nginx
```

### 14.2. Выпуск сертификатов по HTTP-01 (порт 80, без 443)

Важно: используйте **`certonly`** — только получить файлы сертификата, **без** авто-правки nginx на `listen 443` (пока 443 занят Xray, `certbot --nginx` без `certonly` упадёт или сломает конфиг).

```bash
# Один сертификат на оба имени (удобно для одного ssl_certificate)
sudo certbot certonly --nginx \
  -d app.harkonlord.su \
  -d api.harkonlord.su \
  --agree-tos \
  --email ВАШ@EMAIL \
  --non-interactive
```

Если плагин nginx ругается — webroot (для `app` корень уже есть; для `api` добавьте **временный** ACME location, не трогая `proxy_pass` остального трафика):

```bash
# В server { ... } api.harkonlord.su на :80 — ДОБАВИТЬ выше proxy location:
#   location ^~ /.well-known/acme-challenge/ {
#     root /var/www/acme;
#     default_type "text/plain";
#   }
sudo mkdir -p /var/www/acme
sudo nginx -t && sudo systemctl reload nginx

sudo certbot certonly --webroot \
  -w /var/www/feed-time \
  -d app.harkonlord.su \
  --agree-tos --email ВАШ@EMAIL --non-interactive

sudo certbot certonly --webroot \
  -w /var/www/acme \
  -d api.harkonlord.su \
  --agree-tos --email ВАШ@EMAIL --non-interactive
```

Проверка файлов (пути появятся в выводе certbot; обычно так):

```bash
sudo ls -la /etc/letsencrypt/live/app.harkonlord.su/
# fullchain.pem  privkey.pem  ...
# если api выпускали отдельно — /etc/letsencrypt/live/api.harkonlord.su/
```

Продление: `certbot.timer` обычно уже активен. Проверка dry-run:

```bash
sudo certbot renew --dry-run
```

### 14.3. Вариант A (предпочтительно) — освободить 443 и отдать nginx

#### Шаг A1. Найти процесс на 443

```bash
sudo ss -tlnp | grep ':443'
# часто: xray / v2ray / sing-box
systemctl list-units --type=service --all | grep -iE 'xray|v2ray|sing'
```

#### Шаг A2a. Кратко остановить Xray (если VPN/прокси можно прервать на пару минут)

```bash
# подставьте реальное имя юнита из list-units, часто xray.service
sudo systemctl stop xray
sudo ss -tlnp | grep ':443' || echo "443 свободен"
```

#### Шаг A2b. Или перенести Xray на другой порт (безопаснее надолго)

1. Откройте конфиг Xray (часто `/usr/local/etc/xray/config.json` или `/etc/xray/config.json`):

```bash
sudo find /etc /usr/local/etc -name 'config.json' 2>/dev/null | head
```

2. В inbound, где `"port": 443` (или listen 443), смените на свободный порт, например **8443** или **2053**.
3. Откройте порт в ufw при необходимости: `sudo ufw allow 8443/tcp`
4. Обновите клиенты VPN на новый порт.
5. Перезапуск:

```bash
sudo systemctl restart xray
sudo ss -tlnp | grep -E ':443|:8443'
```

После этого **443 свободен для nginx**, Xray продолжает работать на новом порту.

#### Шаг A3. Включить SSL в nginx — **добавить**, не переписывая ваши location

**Не удаляйте** существующие `server { listen 80; … }`. Добавьте рядом блок на 443 с **тем же** `root` / `proxy_pass`, что уже работает на 80.

Пример для веб-UI (пути к сертификату — из `certbot certificates`):

```nginx
# ДОБАВИТЬ в /etc/nginx/sites-available/feed-time-web (или include)
server {
  listen 443 ssl http2;
  listen [::]:443 ssl http2;
  server_name app.harkonlord.su;

  ssl_certificate     /etc/letsencrypt/live/app.harkonlord.su/fullchain.pem;
  ssl_certificate_key /etc/letsencrypt/live/app.harkonlord.su/privkey.pem;
  include /etc/letsencrypt/options-ssl-nginx.conf;
  ssl_dhparam /etc/letsencrypt/ssl-dhparams.pem;

  root /var/www/feed-time;
  index index.html;

  location / {
    try_files $uri $uri/ /index.html;
  }
  location = /index.html {
    add_header Cache-Control "no-cache";
  }
}
```

Пример для API (те же `proxy_*`, что у вас на :80):

```nginx
# ДОБАВИТЬ рядом с существующим server api на :80
server {
  listen 443 ssl http2;
  listen [::]:443 ssl http2;
  server_name api.harkonlord.su;

  # если один сертификат на оба имени — тот же live/.../app.harkonlord.su/
  # если отдельный для api — live/api.harkonlord.su/
  ssl_certificate     /etc/letsencrypt/live/app.harkonlord.su/fullchain.pem;
  ssl_certificate_key /etc/letsencrypt/live/app.harkonlord.su/privkey.pem;
  include /etc/letsencrypt/options-ssl-nginx.conf;
  ssl_dhparam /etc/letsencrypt/ssl-dhparams.pem;

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

Если файлов `options-ssl-nginx.conf` / `ssl-dhparams.pem` нет:

```bash
sudo certbot install --cert-name app.harkonlord.su
# либо один раз: sudo openssl dhparam -out /etc/letsencrypt/ssl-dhparams.pem 2048
# и скачайте стандартный options-ssl-nginx.conf от Certbot / уберите две строки include/ssl_dhparam
```

Опционально — редирект HTTP→HTTPS **внутри уже существующих** `:80` блоков (добавить одной директивой в начало `server`, не трогая ACME, если ещё нужен webroot):

```nginx
# в server listen 80 для app / api — после выпуска сертификатов:
# return 301 https://$host$request_uri;
```

Либо оставьте `:80` рабочим параллельно (удобно для отладки).

```bash
sudo nginx -t && sudo systemctl reload nginx
```

#### Шаг A4. Вернуть Xray (если останавливали в A2a и хотите снова на 443)

Если nginx уже занял 443, **нельзя** снова повесить Xray на тот же порт без варианта B. Либо Xray остаётся на другом порту (A2b), либо переходите к B (SNI).

```bash
# только если 443 снова свободен И вы сознательно убираете nginx с 443
# sudo systemctl start xray
```

### 14.4. Вариант B — Xray остаётся на 443, HTTPS для сайтов через SNI/fallback

Используйте, если клиенты VPN **обязаны** ходить на `IP:443` и перенос порта неприемлем. Идея: Xray принимает TLS на 443 и по **SNI** (имя хоста в ClientHello) либо обслуживает свой протокол, либо **пробрасывает** трафик `app.` / `api.` на nginx с TLS на localhost.

Типичная схема:

1. nginx слушает **только localhost**, например `127.0.0.1:8443` с `ssl` для `app` и `api` (те же `ssl_certificate` / `root` / `proxy_pass`, что в A3, но `listen 127.0.0.1:8443 ssl`).
2. В inbound Xray на 443 включают **fallback / dokodemo-door / nginx SNI route** на `127.0.0.1:8443` для SNI `app.harkonlord.su` и `api.harkonlord.su`.
3. Остальной SNI (или Reality dest) — как было у вашего Xray.

**Точные ключи JSON зависят от вашей сборки Xray (VLESS+REALITY, VMess+WS+TLS и т.д.) — универсального «вставь и забудь» нет.** Ориентир для REALITY/fallback (псевдо-фрагмент, сверьте с [документацией Xray](https://xtls.github.io/) и своим конфигом):

```json
{
  "inbounds": [{
    "port": 443,
    "protocol": "vless",
    "settings": {
      "clients": [],
      "fallbacks": [
        { "name": "app.harkonlord.su", "dest": "127.0.0.1:8443" },
        { "name": "api.harkonlord.su", "dest": "127.0.0.1:8443" },
        { "dest": "80" }
      ]
    },
    "streamSettings": { "network": "tcp", "security": "reality" }
  }]
}
```

Перед правкой:

```bash
sudo cp -a /path/to/xray/config.json /path/to/xray/config.json.bak-$(date +%Y%m%d)
sudo nginx -t
# после правок Xray + nginx:
sudo systemctl restart xray
sudo systemctl reload nginx
```

Минусы B: сложнее отладка, ошибка в fallback ломает и VPN, и сайты; обновления Xray могут сменить синтаксис. **Предпочитайте A**, если можете сменить порт VPN-клиента.

### 14.5. CORS и клиент после HTTPS

В `/opt/feed-time/server/.env` убедитесь, что origin веб-UI с https разрешён (`CORS_ORIGIN=*` уже ок):

```bash
# пример явного списка:
# CORS_ORIGIN=https://app.harkonlord.su,http://app.harkonlord.su
cd /opt/feed-time/server && docker compose up -d
```

Обновите статику с дефолтом HTTPS:

```bash
cd /opt/feed-time && git pull origin main
sudo rsync -a --delete /opt/feed-time/web/ /var/www/feed-time/
```

В приложении / браузере: **Настройки → URL API** = `https://api.harkonlord.su` (без `/` в конце). WebSocket сам станет `wss://` из `https://`.

### 14.6. Проверка HTTPS

```bash
curl -sSI https://app.harkonlord.su/ | head -n 15
curl -sS https://api.harkonlord.su/api/health
# {"ok":true,"service":"feed-time-api",...}

# цепочка сертификата
echo | openssl s_client -connect app.harkonlord.su:443 -servername app.harkonlord.su 2>/dev/null | openssl x509 -noout -subject -dates
echo | openssl s_client -connect api.harkonlord.su:443 -servername api.harkonlord.su 2>/dev/null | openssl x509 -noout -subject -dates
```

В браузере: открыть `https://app.harkonlord.su` → замок → Настройки → API `https://api.harkonlord.su` → логин / health через сеть.

### 14.7. Откат

```bash
# убрать только добавленные server { listen 443 ... } (вернуть бэкап сайта)
sudo cp -a /etc/nginx/sites-available/feed-time-web.bak /etc/nginx/sites-available/feed-time-web
sudo nginx -t && sudo systemctl reload nginx

# вернуть Xray на 443 (если переносили порт — верните port в config.json)
sudo systemctl start xray
```

HTTP на `:80` при этом продолжает работать, если вы его не вырезали.
