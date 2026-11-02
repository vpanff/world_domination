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
./gradlew :app:packageDistributionForCurrentOS   # установщик для текущей ОС
./gradlew :androidApp:assembleRelease            # APK → androidApp/build/outputs/apk/release
./gradlew :androidApp:installRelease             # установить на подключённый телефон
```

Для Android нужен Android SDK; путь к нему — в `local.properties` (`sdk.dir=...`).

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
