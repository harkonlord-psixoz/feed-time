# feed-time / Feed Time

Календарь кормления кошек (и других питомцев) + Android-обёртка WebView с виджетом.

Cat feeding calendar (and other pets) + Android WebView wrapper with a home-screen widget.

**License / Лицензия:** TBD (пока не зафиксирована; можно выбрать MIT при публикации).

---

## Что внутри / Layout

```
web/index.html          — веб-календарь (откройте в браузере)
android/                — исходники Gradle-проекта (пакет com.murcalendar.feeding)
server/                 — свой API (auth, семья, синк кормлений) для VPS
docs/                   — планы и заметки (APK, sync, деплой, темы, GitHub)
releases/feed-time.apk — готовый debug APK (sideload)
```

---

## Семейный синк на своём VPS

Не Supabase: поднимаете `server/` на своём сервере (Docker Compose + Postgres + опционально Caddy).

Подробно: **[docs/vps-api-deploy.md](docs/vps-api-deploy.md)**.

В приложении: **Настройки → Сервер и семья** — URL, регистрация/вход, создать семью или код приглашения. Тема и прочие ключи `mur-calendar-*` остаются локальными; календарь синкается с офлайн-очередью.

---

## Веб / Open the HTML

**RU:** Полный клиент — `web/index.html` (тот же UI, что в APK). Локально: двойной клик или `python3 -m http.server`. На VPS: раздайте статикой через nginx с `web/` (поддомен `app.` предпочтительнее, чтобы не трогать placeholder на apex) — см. **[docs/vps-api-deploy.md](docs/vps-api-deploy.md) §13**.

Сайт (UI) и API — разные URL. Дефолт API в настройках: `http://api.harkonlord.su` (meta `feed-time-default-api`; на VPS API часто HTTP, пока 443 занят Xray).

**EN:** Full client is `web/index.html` (same UI as the APK). Serve the `web/` folder via any static host / nginx; default sync API is `http://api.harkonlord.su`.

```bash
# optional local server
cd web && python3 -m http.server 8080
# → http://localhost:8080/
```

---

## Android APK

### Готовый APK / Prebuilt

Файл: `releases/feed-time.apk` (debug-подпись, API 24+).  
Скопируйте на телефон и установите (разрешить «неизвестные источники»).

### Сборка из исходников / Build

Нужны: JDK 17+, Android SDK (API 34+), `ANDROID_HOME`.

```bash
cd android
# обновить HTML во встроенных assets (если меняли web/):
cp ../web/index.html app/src/main/assets/index.html
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
```

Release sideload: `./gradlew assembleRelease` подписан тем же `android/mur-calendar-debug.keystore` (CF-25). Для Play Store — отдельный keystore.

Подробнее: `docs/android-apk-notes.md`.

---

## Проверка обновлений (GitHub Releases)

В приложении: **Настройки → Проверить обновления**.  
Запрос идёт к `GET /repos/harkonlord-psixoz/feed-time/releases/latest`.  
Опциональный GitHub PAT хранится только в `localStorage` устройства (ключ `mur-calendar-github-pat`) — **никогда не коммитьте токены**.

Публичные Releases работают без токена; PAT нужен при лимитах API (60/час без auth) или для private repo.

### Как опубликовать Release с APK

1. Соберите debug APK (`./gradlew assembleDebug`) или возьмите `releases/feed-time.apk`.
2. На GitHub: **Releases → Draft a new release**.
3. Tag: `v1.7.0` (или следующий semver; совпадает с `versionName` / `APP_VERSION` в HTML).
4. Title / notes — что изменилось.
5. **Attach binaries**: загрузите `feed-time.apk` (имя с `.apk` — приложение найдёт вложение).
6. Publish release.

После публикации «Проверить обновления» покажет tag, ссылку на релиз и прямую ссылку на APK.

В Android WebView тап по «Скачать APK» идёт через мост `MurCalendarAndroid.openUrl` → `DownloadManager` (или внешний браузер), а не через внутреннюю загрузку WebView.

Текущая версия приложения в коде: **1.8.0** (`web/index.html` → `APP_VERSION`, Android `versionName`).

---

## Документация / Docs

| Файл | О чём |
|------|--------|
| `docs/vps-api-deploy.md` | деплой API на Ubuntu VPS (Docker, TLS, бэкапы) |
| `docs/android-apk-notes.md` | APK, виджет, пересборка |
| `docs/family-sync-and-notifications.md` | семейный sync / уведомления (план) |
| `docs/calendar-theme-variants.md` | варианты тем |
| `docs/github-connect-guide.md` | обход Connect GitHub через PAT |

Ручная заливка в GitHub: `docs/manual-git-push.md` (если remote недоступен из агента).
