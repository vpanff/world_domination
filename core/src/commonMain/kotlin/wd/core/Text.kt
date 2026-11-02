package wd.core

// Тексты для лога ведущего и сообщений бота.
// Эмодзи отделены от текста: в Telegram они есть, а в приложении шрифт может их не содержать.

fun ActionType.title(): String = when (this) {
    ActionType.SHIELD -> "Щит"
    ActionType.UPGRADE -> "Улучшение"
    ActionType.RESEARCH -> "Исследование"
    ActionType.PRODUCE -> "Производство бомбы"
    ActionType.ECOLOGY -> "Экология"
    ActionType.LAUNCH -> "Запуск бомбы"
}

fun Action.describe(): String = when (type) {
    ActionType.SHIELD, ActionType.UPGRADE -> "${type.title()}: ${target?.city}"
    ActionType.LAUNCH -> "${type.title()} → $target"
    else -> type.title()
}

fun GameResult.describe(): String = when (this) {
    is GameResult.Winner -> "$country победила — единственная выжившая!"
    GameResult.EcoCatastrophe -> "ЭКОЛОГИЧЕСКАЯ КАТАСТРОФА — все проигрывают!"
    GameResult.AllDestroyed -> "Все страны уничтожены — ничья!"
}

fun GameEvent.describe(): String = when (this) {
    is ShieldBuilt -> "$country поставила щит на $city"
    is CityUpgraded -> "$country улучшила $city до уровня $level"
    is ResearchStarted -> "$country начала исследование (готово в следующем ходу)"
    is BombOrdered -> "$country запустила производство бомбы"
    is EcologyFunded -> "$country вложилась в экологию"
    is LaunchResult -> when (outcome) {
        LaunchOutcome.DESTROYED -> "$attacker уничтожила $target"
        LaunchOutcome.SHIELD_HIT -> "$attacker ударила по $target — щит поглотил удар"
        LaunchOutcome.ALREADY_DESTROYED -> "$attacker → $target: цель уже уничтожена, бомба сохранена"
    }
    is ShieldHit -> "Щит над ${target.city} поглотил удар и разрушен"
    is TechReady -> "$country: технология изучена"
    is BombsReady -> "$country: +$count бомб (всего $total)"
    is IncomeReceived -> "$country: +$amount$ → баланс $balance$"
    is ActionFailed -> "$country: $reason"
    is CityDestroyed -> "Город ${target.city} (${target.country}) уничтожен"
    is EcologyChanged -> "Экология: $from% → $to%"
    is GameOver -> result.describe()
}

fun GameEvent.icon(): String = when (this) {
    is ShieldBuilt, is ShieldHit -> "🛡"
    is CityUpgraded -> "⬆"
    is ResearchStarted -> "🔬"
    is BombOrdered -> "💣"
    is EcologyFunded -> "🌿"
    is LaunchResult -> when (outcome) {
        LaunchOutcome.DESTROYED -> "☢"
        LaunchOutcome.SHIELD_HIT -> "💥"
        LaunchOutcome.ALREADY_DESTROYED -> "⚠"
    }
    is TechReady, is BombsReady -> "✓"
    is IncomeReceived -> "💰"
    is ActionFailed -> "⚠"
    is CityDestroyed -> "☢"
    is EcologyChanged -> "🌍"
    is GameOver -> if (result is GameResult.Winner) "🏆" else "☠"
}
