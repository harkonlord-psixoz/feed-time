<<<<<<< HEAD
# feed-time / Мур-календарь

Календарь кормления кошек (и других питомцев) + Android-обёртка WebView с виджетом.

Cat feeding calendar (and other pets) + Android WebView wrapper with a home-screen widget.

**License / Лицензия:** TBD (пока не зафиксирована; можно выбрать MIT при публикации).

---

## Что внутри / Layout

```
web/index.html          — веб-календарь (откройте в браузере)
android/                — исходники Gradle-проекта (пакет com.murcalendar.feeding)
docs/                   — планы и заметки (APK, sync, темы, GitHub)
releases/mur-calendar.apk — готовый debug APK (sideload)
```

---

## Веб / Open the HTML

**RU:** Откройте `web/index.html` двойным кликом или через локальный сервер. Данные хранятся в `localStorage` браузера.

**EN:** Open `web/index.html` in any modern browser (or serve locally). Data stays in browser `localStorage`.

```bash
# optional local server
cd web && python3 -m http.server 8080
# → http://localhost:8080/
```

---

## Android APK

### Готовый APK / Prebuilt

Файл: `releases/mur-calendar.apk` (debug-подпись, API 24+).  
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

Release: `./gradlew assembleRelease` + свой keystore (пока не настроен).

Подробнее: `docs/android-apk-notes.md`.

---

## Документация / Docs

| Файл | О чём |
|------|--------|
| `docs/android-apk-notes.md` | APK, виджет, пересборка |
| `docs/family-sync-and-notifications.md` | семейный sync / уведомления (план) |
| `docs/calendar-theme-variants.md` | варианты тем |
| `docs/github-connect-guide.md` | обход Connect GitHub через PAT |

Ручная заливка в GitHub: см. инструкцию рядом с архивом — `manual-git-push.md` в Context/docs.
=======
# feed-time
>>>>>>> e35609b57a5fc3d6bfea5351a812bf7009cc6cdf
