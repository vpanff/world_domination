# Мировое господство

Цифровая версия настольной игры: приложение ведущего (ПК и Android) и
Telegram-бот для игроков.

- [Правила](docs/RULES.md)
- [План разработки](docs/PLAN.md)

## Структура

```
core/     — правила игры на Kotlin (без UI)
bot/      — Telegram-бот (работает внутри приложения ведущего)
app/      — интерфейс приложения ведущего (Compose Multiplatform), запуск на ПК
androidApp/ — запуск на Android
legacy/   — Python-версия 1.0.0 (PyQt6)
```

## Сборка

Нужен JDK 17+.

```bash
./gradlew :app:run                # запустить приложение ведущего
./gradlew :core:jvmTest :bot:jvmTest   # тесты ядра и бота
./gradlew :app:desktopTest        # скриншоты экранов → app/build/screenshots
./gradlew :app:packageDistributionForCurrentOS   # установщик для текущей ОС → app/build/compose/binaries/main
./gradlew :androidApp:assembleRelease            # APK → androidApp/build/outputs/apk/release
./gradlew :androidApp:installRelease             # установить на подключённый телефон
```

Для Android нужен Android SDK; путь к нему — в `local.properties` (`sdk.dir=...`).

Установщик собирается только под ту ОС, на которой запущена сборка: `.deb` на
Linux, `.msi` на Windows, `.dmg` на macOS. Установка на Linux:
`sudo apt install ./app/build/compose/binaries/main/deb/world-domination_*_amd64.deb`

## Релиз

Готовые установщики — на странице [Releases](https://github.com/vpanff/world_domination/releases).

1. Один раз добавьте секрет `ANDROID_DEBUG_KEYSTORE` (см. `.github/workflows/release.yml`).
2. Поднимите версию в `app/build.gradle.kts` и `androidApp/build.gradle.kts`.
3. Запушьте тег: `git tag v2.1.0 && git push origin main v2.1.0`.
4. GitHub Actions соберёт `.deb`, `.msi`, `.dmg`, `.apk` и создаст черновик релиза —
   проверьте его и нажмите «Publish release».

Сохранения лежат в `~/.local/share/world-domination/saves` (Linux),
`%APPDATA%\world-domination\saves` (Windows),
`~/Library/Application Support/world-domination/saves` (macOS).

Старая версия: `pip install -r legacy/requirements.txt && python legacy/app.py`

## Telegram-бот

1. Создайте бота у [@BotFather](https://t.me/BotFather) (`/newbot`) и скопируйте токен.
2. В приложении: Меню → Настройки → вставьте токен → «Проверить» → «Сохранить».
3. В партии на панели «Telegram-бот» нажмите «Запустить».
4. Кнопкой «Ссылка» скопируйте приглашение для каждой страны и отправьте игроку.
   Игрок открывает ссылку и становится главой страны.

Сервер не нужен: бот работает, пока запущено приложение ведущего. На Android
он продолжает работать в фоне (уведомление «Telegram-бот принимает приказы»).

Бот должен работать только на одном устройстве: иначе часть сообщений уйдёт в
партию на другом устройстве, а игроки получат ответ «Вы не глава страны».

## Мини-приложение

Страница `miniapp/` публикуется на GitHub Pages при пуше в `main`.

1. Один раз: Settings → Pages → Source: «GitHub Actions».
2. В приложении: Меню → Настройки → вставьте адрес страницы
   (`https://vpanff.github.io/world_domination/`) → «Сохранить адрес».
3. Перезапустите бота.
