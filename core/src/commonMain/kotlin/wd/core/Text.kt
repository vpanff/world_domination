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
    ActionType.TRANSFER -> "Перевод"
}

fun Action.describe(): String = when (type) {
    ActionType.SHIELD, ActionType.UPGRADE -> "${type.title()}: ${target?.city}"
    ActionType.LAUNCH -> "${type.title()} → $target"
    ActionType.TRANSFER -> "${type.title()} → $toCountry: $amount$"
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
    is MoneySent -> "$from перевела $to: $amount$"
    is MoneyReceived -> "$to получила от $from: $amount$"
    is ActionFailed -> "$country: $reason"
    is CityDestroyed -> "Город ${target.city} (${target.country}) уничтожен"
    is EcologyChanged -> "Экология: $from% → $to%"
    is WorldEventHappened -> event.describe()
    is GameOver -> result.describe()
}

/** Строка личного отчёта. Те же тексты повторяет miniapp/index.html (см. reportLine). */
fun GameEvent.playerText(): String = when (this) {
    is ShieldBuilt -> "$city: поставлен щит"
    is CityUpgraded -> "$city: улучшен до ур.$level"
    is ResearchStarted -> "Начато исследование, будет готово в конце следующего хода"
    is BombOrdered -> "Заказана бомба"
    is EcologyFunded -> "Вложение в экологию"
    is LaunchResult -> when (outcome) {
        LaunchOutcome.DESTROYED -> "Удар по $target: город уничтожен"
        LaunchOutcome.SHIELD_HIT -> "Удар по $target: щит поглотил удар"
        LaunchOutcome.ALREADY_DESTROYED -> "Удар по $target: цель уже уничтожена, бомба сохранена"
    }
    is ShieldHit -> "${target.city}: щит поглотил удар и разрушен"
    is TechReady -> "Технология изучена"
    is BombsReady -> "Готово бомб: +$count, всего $total"
    is IncomeReceived -> "Доход: +$amount$, баланс $balance$"
    is MoneySent -> "Перевод в $to: −$amount$"
    is MoneyReceived -> "Перевод от $from: +$amount$"
    is ActionFailed -> reason.replaceFirstChar { it.uppercase() }
    is CityDestroyed -> "$target: город уничтожен"
    else -> describe()
}

/** Изменение экологии за ход, для отчёта. */
fun ecologyChangeText(from: Int, to: Int): String {
    val delta = to - from
    return when {
        delta > 0 -> "Экология: $from% → $to% (+$delta)"
        delta < 0 -> "Экология: $from% → $to% (−${-delta})"
        else -> "Экология не изменилась: $to%"
    }
}

fun WorldEvent.describe(): String = when (type) {
    WorldEventType.CRISIS -> "Мировой кризис: доход всех стран −$value% на один ход"
    WorldEventType.VOLCANO -> "Извержение супервулкана: экология −$value%"
    WorldEventType.BUILDING_BOOM -> "Строительный бум: улучшение городов на $value% дешевле на один ход"
    WorldEventType.GREEN_SUMMIT -> "Зелёный саммит: вложения в экологию на $value% дешевле на один ход"
    WorldEventType.SCIENCE_BREAKTHROUGH -> "Научный прорыв: исследование на $value% дешевле на один ход"
    WorldEventType.INTEL_LEAK -> "Утечка разведданных: всего в мире готовых бомб — $value"
}

fun WorldEvent.icon(): String = when (type) {
    WorldEventType.CRISIS -> "📉"
    WorldEventType.VOLCANO -> "🌋"
    WorldEventType.BUILDING_BOOM -> "🏗"
    WorldEventType.GREEN_SUMMIT -> "🌿"
    WorldEventType.SCIENCE_BREAKTHROUGH -> "🔬"
    WorldEventType.INTEL_LEAK -> "🕵"
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
    is MoneySent, is MoneyReceived -> "💸"
    is ActionFailed -> "⚠"
    is CityDestroyed -> "☢"
    is EcologyChanged -> "🌍"
    is WorldEventHappened -> event.icon()
    is GameOver -> if (result is GameResult.Winner) "🏆" else "☠"
}
