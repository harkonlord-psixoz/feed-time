# GitHub для feed-time — если Connect выдаёт ошибку сервера

Карточка **Connect GitHub** в Cursor — это OAuth Cursor ↔ GitHub, **не** ввод API-ключа в чат. При «ошибке сервера» чаще всего падает сам Cursor/OAuth, а не ваш аккаунт.

---

## A. Что сделать с Connect (повторить)

1. Другой браузер / окно инкогнито, аккаунт **harkonlord-psixoz**.
2. Cursor → **Settings → Integrations / Account → GitHub**: Disconnect (если есть) → Connect заново.
3. Обновить Cursor до последней версии, перелогиниться в Cursor.
4. Через 10–15 минут снова нажать Connect (сбои на стороне Cursor бывают).
5. После успеха написать в чат: **«GitHub подключил»**.

Проверка App: https://github.com/settings/installations → Cursor → есть `feed-time`.

Пока Connect не зелёный, агенты **не могут** пушить в репо сами — это ограничение доступа Cursor, не отсутствие «ключа» у Джарвиса.

---

## B. Обходной путь: вы пушите сами (PAT)

Пока Connect чинят — код можно залить с вашего ПК.

### 1. Создать токен GitHub (для `git push` с вашего компьютера)

Fine-grained (предпочтительно):

1. Откройте https://github.com/settings/personal-access-tokens/new  
   (или Settings → Developer settings → Fine-grained tokens → Generate).
2. **Token name:** `feed-time-push`
3. **Expiration:** 30–90 дней (или по вкусу).
4. **Resource owner:** `harkonlord-psixoz`
5. **Repository access:** Only select → **`feed-time`**
6. **Permissions → Repository:**
   - Contents: **Read and write**
   - Metadata: Read
   - (опционально) Pull requests: Read and write
7. Generate → **скопируйте токен сразу** (потом не покажут).

Classic (если fine-grained неудобен):

1. https://github.com/settings/tokens/new  
2. Scope: **`repo`**  
3. Generate → скопировать.

**Никогда не присылайте токен Джарвису в чат** и не коммитьте его в файлы.

### 2. Залить код с ПК

Когда Джарвис положит архив исходников в Context (или вы скачаете HTML/APK из docs):

```bash
git clone https://github.com/harkonlord-psixoz/feed-time.git
cd feed-time
# скопировать файлы приложения в репозиторий
git add .
git commit -m "Initial open-source drop: calendar + Android"
git push origin main
```

При запросе логина: username = `harkonlord-psixoz`, password = **вставить PAT** (не пароль от GitHub).

Либо HTTPS URL вида:  
`https://harkonlord-psixoz:ВАШ_ТОКЕН@github.com/harkonlord-psixoz/feed-time.git`  
(токен не сохранять в скриптах навсегда).

### 3. Токен «в Настройках приложения» (вариант A — позже)

Это **другой** (или тот же read-only) токен:

- Contents: **Read** достаточно для публичных Releases.
- Вставите в UI Настроек Мур-календаря, когда Джарвис добавит поле после появления кода в git.

Для **public** Releases проверка обновлений часто работает **без** ключа; ключ — запас на лимиты API.

---

## C. Что написать Джарвису

| Ситуация | Сообщение |
|----------|-----------|
| Connect заработал | «GitHub подключил» |
| Connect всё ещё ошибка | «Connect падает — сделай архив для ручного push» |
| Уже запушили сами | ссылка на commit / «код на GitHub» |

---

## Кратко

| Цель | Инструмент |
|------|------------|
| Чтобы агент пушил сам | Connect GitHub в Cursor (OAuth), не PAT в чат |
| Чтобы вы пушили сами сейчас | Fine-grained PAT Contents: R/W на `feed-time` |
| Обновления в приложении (A) | Позже: поле в Настройках + Releases; Read-токен опционален |
