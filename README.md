# Мировое господство

Цифровая версия настольной игры: приложение ведущего (ПК и Android) и
Telegram-бот для игроков.

- [Правила](docs/RULES.md)
- [План разработки](docs/PLAN.md)

## Структура

```
core/     — правила игры на Kotlin (без UI)
legacy/   — Python-версия 1.0.0 (PyQt6)
```

## Сборка

Нужен JDK 17+.

```bash
./gradlew :core:jvmTest    # тесты ядра
```

Старая версия: `pip install -r legacy/requirements.txt && python legacy/app.py`
