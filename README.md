# Мировое господство

Цифровая версия настольной игры: приложение ведущего (ПК и Android) и
Telegram-бот для игроков.

- [Правила](docs/RULES.md)
- [План разработки](docs/PLAN.md)

## Структура

```
core/     — правила игры на Kotlin (без UI)
app/      — интерфейс приложения ведущего (Compose Multiplatform), запуск на ПК
androidApp/ — запуск на Android
legacy/   — Python-версия 1.0.0 (PyQt6)
```

## Сборка

Нужен JDK 17+.

```bash
./gradlew :app:run                # запустить приложение ведущего
./gradlew :core:jvmTest           # тесты ядра
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
