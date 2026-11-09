# Feed Time — Android APK (v1.7.4)

## Что собрано

- **Файл:** [feed-time.apk](./feed-time.apk)  
  Абсолютный путь: `/cursor/stores/self/docs/feed-time.apk`  
  В репозитории: `releases/feed-time.apk`
- **Тип:** debug APK (стабильный общий debug-keystore в репозитории, можно ставить sideload)
- **Package:** `com.murcalendar.feeding`
- **Имя приложения:** Feed Time
- **Версия:** `1.7.4` (versionCode **13**)
- **Содержимое:** WebView + виджет + **локальные уведомления** (AlarmManager)
- **Содержимое HTML:** `assets/index.html` байт-в-байт с `web/index.html` / store `docs/cat-feeding-calendar.html`
- **Последняя пересборка:** CF-25 keystore + имя **Feed Time** + CF-24 скачивание APK из обновлений.

## Подпись APK и ошибка «конфликтует с другим пакетом» (CF-25)

Android не даёт обновить приложение, если новый APK подписан **другим** сертификатом (`INSTALL_FAILED_UPDATE_INCOMPATIBLE` / «приложение конфликтует с другим пакетом…»). Раньше каждый агент/машина подписывал debug-сборку своим локальным `~/.android/debug.keystore` — отсюда конфликт при обновлении.

### Что сделано

1. В репозитории зафиксирован keystore: `android/mur-calendar-debug.keystore` (alias `murcalendardebug`).
2. В `android/app/build.gradle` оба типа сборки (`debug` и `release`) используют `signingConfigs.murDebug`.
3. В `.gitignore` есть исключение `!android/mur-calendar-debug.keystore` — этот файл **коммитится**; остальные `*.keystore` / `*.jks` по-прежнему игнорируются.

### Одноразово на телефоне

Если уже стоит старая сборка с другим сертификатом:

1. **Удалите** «Feed Time» (или старое «Мур-календарь») (данные localStorage WebView сбросятся).
2. Установите новый `feed-time.apk` (v1.7.4+).
3. Дальнейшие обновления с тем же keystore ставятся **поверх** без удаления.

### Важно для сборщиков

- **Никогда не перегенерируйте** `android/mur-calendar-debug.keystore`. Новый ключ = снова конфликт у всех, у кого уже стоит APK с текущим сертификатом.
- SHA-256 сертификата (для проверки): `FA:B5:C4:F7:7D:4B:51:7F:C4:ED:A0:BB:70:46:F9:F0:FD:0B:6B:25:78:E9:84:0F:59:9F:92:CC:1B:3C:27:19`
- Пароли debug-keystore (не секрет Play Store, только sideload): store/key `murcalendar`.


## Падежи в оповещениях и чистый профиль (CF-22)

1. Тексты morning / remind / hungry / just-fed собираются в JS теми же хелперами, что чип и приветствия (`petPhraseAccusative` / nominative, `declineNickname`: Лия→Лию/Лии).
2. Android получает **уже склонённые фразы** (`notifyJustFed(body)`, `scheduleFeedReminders(lastMs, reminder, hungry, morning)`) и показывает их как есть.
3. Hungry: род глагола — `проголодалась` / `проголодался` / `проголодались`.
4. На Профиле и в Настройках скрыты месяц/год, ‹ › и «Сегодня» (`.period-bar[hidden]` — `display:flex` больше не перебивает `hidden`).

## Профиль и палитра (CF-19)

1. **Профиль:** сначала «Сколько питомцев», затем у каждого свой dropdown вида + кличка.
2. Смешанные виды: подписи вроде «кошка и собака»; падежи per-pet (кличка или вид в sg).
3. Миграция legacy `{ species, count, names[] }` → `{ version: 2, pets: [{ species, name }] }`.
4. **Палитра** перенесена с главной в **Настройки**.

## Уведомления (CF-20 → CF-22)

1. Мост: `notifyJustFed(body)`, `scheduleFeedReminders(lastMs, reminderBody, hungryBody, morningBody)` — фразы из JS.
2. Нативные: `FeedNotifier`, `FeedReminderScheduler`, `FeedAlarmReceiver` (reminder ~3.5ч, hungry 5ч, morning 06:15, quiet 23–06).
3. Web: stub Notification API + timers (только открытая вкладка) — см. `family-sync-and-notifications.md`.
4. **Потом:** настройка интервала 3–4 ч в UI.

## Проверка обновлений (CF-18 → CF-24)

1. Меню → **Настройки**: поле PAT (localStorage, опционально) и кнопка **«Проверить обновления»**.
2. Запрос: `https://api.github.com/repos/harkonlord-psixoz/feed-time/releases/latest` (`Authorization: Bearer …` если токен задан).
3. UI показывает текущую `APP_VERSION` vs `tag_name`, ссылку на релиз и APK-asset (если прикреплён).
4. WebView: `AllowUniversalAccessFromFileURLs` — чтобы fetch с `file:///android_asset/` доходил до api.github.com.
5. **CF-24:** тап «Скачать APK» / «Открыть релиз» в WebView больше не глотается: JS вызывает `MurCalendarAndroid.openUrl(url)`; нативно — `DownloadManager` для `.apk` (файл в «Загрузки») или `ACTION_VIEW` во внешнем браузере; запасной путь — `shouldOverrideUrlLoading` + `DownloadListener` в `MainActivity`.
6. Секреты в git не коммитить (см. `.gitignore`: `.env`, чужие keystore, `local.properties`). Исключение: стабильный `android/mur-calendar-debug.keystore` (CF-25).


## Падежи и скролл списков (CF-16)

1. **Падежи:** вид — nom/acc/gen/dat (+ fed); клички `declineNickname` — Муся→Муси/Мусе/Мусю, Лия→Лии/…/Лию, Барсик→Барсика/Барсику. Чип «Для …», кнопка «Покормить …», приветствия, «Кормили … в».
2. **Скролл `.dd`:** причина прыжка — `positionMenuFixed` на каждом scroll (в т.ч. из меню); стиль сбрасывал `scrollTop`. Теперь: репозиция только на resize; скролл меню игнор; скролл страницы — закрытие меню; `scrollTop` в position не пишем.
3. Имя приложения: **Feed Time** (package по-прежнему `com.murcalendar.feeding`).

## Скролл списков и падежи (CF-15)

1. Меню вида / палитры / месяца / года: `max-height` + `overflow-y: auto`, портал в `body`.
2. Чип «для …» — родительный; CF-16 расширил падежи и устранил прыжок скролла.

## Профиль питомцев (CF-14 → CF-19)

1. CF-14: общий вид + счётчик + клички. CF-19: счётчик сверху, **вид на каждого**, mixed labels, миграция v2.
2. Данные в `localStorage` (`mur-calendar-profile`).
3. Кнопка / приветствие / «последнее кормление» склоняются с учётом mixed pets.
4. Виджет: префикс через JS-мост (4-й аргумент).

## Боковое меню (CF-13)

1. Кнопка-бургер слева вверху (три линии) открывает выезжающую панель.
2. Пункты: **Главная**, **Профиль**, **Настройки** (заглушка).
3. Четвёртая строка — тумблер солнце/луна (раньше был справа у часов).
4. Закрытие: фон-оверлей, Escape, крестик, повторный бургер; `aria-expanded` у кнопки.
5. В шапке справа остались только часы.

## Виджет на рабочий стол

1. Установите/обновите APK (см. ниже).
2. На рабочем столе Android: **долгое нажатие** по пустому месту → **Виджеты** (или «Widgets»).
3. Найдите **«Feed Time»** → перетащите на экран (примерно 3×2 ячейки).
4. На виджете:
   - **логотип и название** приложения;
   - **сегодняшняя дата** (день недели + число + месяц);
   - строка последнего кормления с учётом профиля («Кошек кормили в HH:MM», «Кормили Мурзик в …») или **«ещё не кормили»**;
   - кнопка **«Обновить»** — принудительно перечитать сохранённые данные;
   - нажатие по телу виджета открывает приложение.
5. Данные виджета обновляются:
   - сразу при сохранении кормления в приложении (JS → `SharedPreferences` → `AppWidget` update);
   - при открытии/возврате в приложение;
   - по кнопке «Обновить»;
   - периодически (~30 мин, системный `updatePeriodMillis`).

> Важно: откройте приложение хотя бы раз после установки, чтобы WebView записал текущее состояние в хранилище виджета. Без этого виджет покажет «ещё не кормили», пока не будет первого сохранения из календаря.

## Стек (рекомендация v1)

**Выбрано:** минимальный нативный Android-проект (Java + системный `WebView` + Gradle / AGP 8.5 + `AppWidgetProvider`).  
Почему: быстрее всего получить APK из уже готового HTML без Node/Capacitor CLI и без лишних зависимостей.

**Альтернативы (кратко):**

| Вариант | Когда имеет смысл |
|--------|-------------------|
| **Capacitor** | Нужны плагины (уведомления, файлы, Share) и единый веб→мобильный пайплайн |
| **PWA** | Установка из браузера без APK; слабее «как приложение» на Android |
| **TWA (Trusted Web Activity)** | Хостится на HTTPS-домене; для чисто локального HTML не подходит |

## Установка (sideload)

1. Скопируйте `feed-time.apk` на телефон (USB, Telegram, облако и т.п.).
2. Разрешите установку из неизвестных источников для того приложения, из которого открываете файл (Файлы / Chrome / Telegram):  
   **Настройки → Безопасность → Установка неизвестных приложений** (формулировки зависят от производителя).
3. Откройте APK → **Установить**. Если система пишет «конфликтует с другим пакетом» — сначала удалите старое приложение (см. раздел CF-25 выше), затем поставьте снова. После перехода на стабильный ключ обновления поверх сохраняют данные.
4. При предупреждении о debug/неизвестном издателе — это нормально для debug-сборки.

Минимальная версия Android: **7.0 (API 24)**.

## Ограничения

- Только **локальные данные** в WebView (`localStorage`) + зеркало последнего кормления в `SharedPreferences` для виджета — без облака и синхронизации между телефонами.
- Шрифты тянутся с **Google Fonts** (нужен интернет при первом показе; без сети приложение откроется, но fallback-шрифты).
- Нет нативных пушей и бэкапа в аккаунт Google.
- Debug-подпись: не для публикации в Google Play; для Play нужна release-подпись и, обычно, AAB.

## Как пересобрать позже

Исходники проекта (архив без `build/`):  
`/cursor/stores/self/internal/android-build/mur-calendar-android-src.tgz`  
Рабочая копия на VM сборки: `/agent/mur-calendar-android/mur-calendar-android`

```bash
# Android SDK (пример)
export ANDROID_HOME=/home/ubuntu/android-sdk
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"

# Распаковать / открыть проект, обновить HTML:
cp /path/to/cat-feeding-calendar.html app/src/main/assets/index.html
echo "sdk.dir=$ANDROID_HOME" > local.properties

./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Release sideload: `./gradlew assembleRelease` — тоже подписан `mur-calendar-debug.keystore` (тот же сертификат, что debug). Для Google Play понадобится отдельный release-keystore (не коммитить).

## Вёрстка web vs app (CF-12)

Один HTML, разные CSS/UA-ветки:

- **Desktop browser** (`data-shell=web`): `align-items: start`; «сегодня» span 2 (крупнее), соседи по ряду остаются низкой высоты — без пустых «дыр» под карточками.
- **Android WebView** (`data-shell=android`, также флаг из `MainActivity`): 2 колонки, «сегодня» на всю ширину, hole-fill `is-pre-today-alone` как раньше.
- **Шрифты:** глобальные надписи (бренд, заголовок месяца, meta, лейблы слотов/полей) — **Caveat**; ввод и мелкий UI — Quicksand / Fredoka.
- Данные по-прежнему локальные; семейный синк web↔Android↔iOS — через облако (см. `family-sync-and-notifications.md` §4), не через порт WebView.
