# Ручная заливка feed-time в GitHub (через PAT)

Репозиторий: https://github.com/harkonlord-psixoz/feed-time  

Cursor Connect GitHub сейчас недоступен (ошибка сервера) — агент **не** пушит. Вы заливаете с ПК.

Архив исходников: `docs/feed-time-export.tgz` в Context (Agent Store).

**Токен в чат не присылайте.**

---

## 1. Создать Personal Access Token

Fine-grained (предпочтительно):

1. https://github.com/settings/personal-access-tokens/new  
2. Token name: `feed-time-push`  
3. Expiration: 30–90 дней  
4. Resource owner: `harkonlord-psixoz`  
5. Repository access: Only select → **feed-time**  
6. Permissions → Repository → Contents: **Read and write** (Metadata: Read)  
7. Generate → скопировать токен один раз

Classic: https://github.com/settings/tokens/new → scope **`repo`**.

---

## 2. Скачать архив и распаковать

Скачайте `feed-time-export.tgz` из Context → docs (или скопируйте файл на ПК).

```bash
# пример: архив лежит в ~/Downloads
cd ~/Downloads
tar -xzf feed-time-export.tgz
# появится папка feed-time-export/
ls feed-time-export
# README.md  web/  android/  docs/  releases/  .gitignore
```

---

## 3. Клонировать репо и положить файлы

Если репозиторий **пустой** (нет коммитов) — можно инициализировать из архива:

```bash
cd ~/Downloads/feed-time-export

# Один раз на ПК (подставьте свои данные; email может быть noreply с GitHub):
git config --global user.name "HarkonLord"
git config --global user.email "ВАШ_EMAIL@example.com"
# или только для этой папки, без --global:
# git config user.name "HarkonLord"
# git config user.email "ВАШ_EMAIL@example.com"

git init
git branch -M main
git remote add origin https://github.com/harkonlord-psixoz/feed-time.git
git add .
git status
git commit -m "Initial open-source drop: web calendar + Android + docs"
```

Если репозиторий **уже существует** (есть README от GitHub):

```bash
cd ~
git clone https://github.com/harkonlord-psixoz/feed-time.git
cd feed-time

# скопировать содержимое архива поверх (не затирая .git)
cp -a ~/Downloads/feed-time-export/. .

git add .
git status
git commit -m "Initial open-source drop: web calendar + Android + docs"
```

---

## 4. Push (логин = GitHub username, пароль = PAT)

Ошибка `Permission to … denied` / **403** почти всегда значит: Windows подставил **старый пароль или чужой токен**, либо PAT без права **Contents: Read and write**.

### 4.1. Проверить PAT

Fine-grained на `feed-time`:

- Resource owner: `harkonlord-psixoz`
- Repository: только **feed-time**
- Contents: **Read and write** (не Read-only)
- Токен не истёк

Classic: scope **`repo`**.

### 4.2. Сбросить сохранённый пароль Windows (PowerShell)

```powershell
cmdkey /list | findstr git
# если есть git:https://github.com — удалить:
cmdkey /delete:git:https://github.com
```

Или: Панель управления → Учётные данные → Учётные данные Windows → удалить записи `git:https://github.com`.

### 4.3. Push заново

```powershell
cd C:\Users\HarkonLord\Downloads\feed-time-export
git push -u origin main
```

Когда спросит:

- **Username:** `harkonlord-psixoz`
- **Password:** вставить **новый PAT** (вставка в консоль часто не видна — это нормально)

Либо один раз через URL (токен не светить в чат и не коммитить):

```powershell
git push https://harkonlord-psixoz:СЮДА_PAT@github.com/harkonlord-psixoz/feed-time.git main
```

Также проверьте на GitHub: Settings репо → Collaborators / что аккаунт — **Owner**, нет блокировки SSO, репо не archived.
---

## 5. Проверка

Откройте: https://github.com/harkonlord-psixoz/feed-time  

Должны быть: `README.md`, `web/`, `android/`, `docs/`, `releases/mur-calendar.apk`.

Когда Cursor Connect заработает — напишите в чат «GitHub подключил»; дальше агенты смогут пушить сами.

---

## Структура архива

| Путь | Содержимое |
|------|------------|
| `web/index.html` | календарь |
| `android/` | Gradle-проект |
| `docs/` | заметки и планы |
| `releases/mur-calendar.apk` | debug APK |
| `README.md` | кратко RU+EN |
