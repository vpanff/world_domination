package wd.bot

import wd.core.*

internal fun Keyboard.forGame(state: GameState): Keyboard = if (!state.isHard) this else copy(rows = rows.map { row -> row.map { it.copy(data = it.data + ":g" + state.gameId.take(8)) } })

/** Отдельная панель хард-режима; классические тексты остаются прежними. */
object HardBotText {
    fun report(state: GameState, country: String): String {
        val me = state.country(country)!!
        return buildString {
            appendLine("🌍 <b>Хард · раунд ${state.turn.coerceAtMost(state.hardRules.rounds)}/${state.hardRules.rounds}</b> · Экология ${state.hardEcology.short()}%")
            appendLine("<b>${country.html()}</b> · ${me.balance}$ · прогноз дохода +${state.incomeOf(me)}$ до санкций")
            appendLine("Жизнь ${me.averageLife().short()}% · развитие ${me.averageDevelopment().short()}")
            appendLine("Бомбы: ${me.bombs} · технология: ${if (me.tech) "есть" else "нет"}")
            if (!me.isAlive) appendLine("Нет живых городов. Доступны восстановление, переводы и помощь ООН.")
            me.cities.forEach { city -> appendLine("• ${city.name.html()}: " + if (city.alive) "жизнь ${city.life.short()}%, развитие ${city.development.short()}${if (city.shield) " 🛡" else ""}" else "уничтожен") }
            val events = state.log.lastOrNull()?.events.orEmpty().filter { it.visibleTo(country) }
            if (events.isNotEmpty()) {
                appendLine("\n<b>События прошлого раунда</b>")
                events.filter { it !is GameOver }.forEach { appendLine(it.playerText().html()) }
            }
            if (state.liveEvents.isNotEmpty()) {
                appendLine("\n<b>Переводы текущего раунда</b>")
                state.liveEvents.filter { it.visibleTo(country) }.forEach { appendLine(it.playerText().html()) }
            }
            appendLine("\n<b>Другие страны</b>")
            state.countries.filter { it.name != country }.forEach { c ->
                appendLine("${c.name.html()}: " + c.cities.joinToString { if (it.alive) it.name.html() else "<s>${it.name.html()}</s>" })
            }
            state.result?.let { appendLine("\n<b>${it.describe().html()}</b>") }
        }.trimEnd()
    }

    fun panel(state: GameState, country: String, view: PanelView): Pair<String, Keyboard?> {
        val me = state.country(country) ?: return "Страна не найдена" to null
        if (state.isOver) return "Игра окончена. ${state.result!!.describe().html()}" to null
        val t = state.turn
        val q = state.pendingOf(country)
        val text = buildString {
            appendLine("<b>Хард · раунд $t/${state.hardRules.rounds} · ${country.html()}</b>")
            appendLine("Доступно ${state.availableBalance(country)}$ из ${me.balance}$ · свободных бомб ${state.freeBombs(country)}")
            appendLine("Жизнь ${me.averageLife().short()}% · развитие ${me.averageDevelopment().short()}")
            if (!me.isAlive) appendLine("Можно восстанавливать города и переводить деньги.")
            appendLine()
            q.forEachIndexed { i, a -> appendLine("${i+1}. ${a.describe().html()}") }
            if (q.isEmpty()) appendLine("Приказов пока нет")
            appendLine(if (country in state.ready) "✅ Вы готовы" else "После приказов нажмите «Готово»")
            appendLine(when(view) { PanelView.LAUNCH -> "Выберите цель"; PanelView.SANCTIONS -> "Против кого ввести санкции?"; PanelView.TRANSFER -> "Кому перевести деньги немедленно?"; PanelView.QUEUE -> "Выберите приказ для отмены"; else -> "Инвестиции и экологию можно оплатить любой целой суммой." })
        }.trimEnd()
        val rows = mutableListOf<List<Button>>()
        fun can(type: ActionType, target: CityRef? = null, to: String? = null) = runCatching { state.validate(type, country, target, amount = 1, toCountry = to) }.isSuccess
        when(view) {
            PanelView.MAIN -> {
                me.cities.forEachIndexed { i, city ->
                    val ref = CityRef(country, city.name)
                    val row = mutableListOf<Button>()
                    if (can(ActionType.INVEST, ref)) row += Button("Вложить: ${city.name}", "iv:$t:$i")
                    if (can(ActionType.SHIELD, ref)) row += Button("Щит ${state.cost(ActionType.SHIELD)}$", "s:$t:$i")
                    if (can(ActionType.REBUILD, ref)) row += Button("Отстроить ${city.name}: ${state.rebuildCost(country)}$", "rb:$t:$i")
                    if (row.isNotEmpty()) rows += row
                }
                rows += listOfNotNull(
                    Button("Технология ${state.cost(ActionType.RESEARCH)}$", "r:$t").takeIf { can(ActionType.RESEARCH) },
                    Button("Бомба ${state.cost(ActionType.PRODUCE)}$", "p:$t").takeIf { can(ActionType.PRODUCE) })
                rows += listOfNotNull(
                    Button("Вложить в экологию", "he:$t").takeIf { can(ActionType.ECOLOGY) },
                    Button("Запуск", "v:$t:${PanelView.LAUNCH.ordinal}").takeIf { me.isAlive && state.freeBombs(country) > 0 })
                if (me.isAlive) rows += listOf(Button("Санкции", "v:$t:${PanelView.SANCTIONS.ordinal}"))
                if (state.availableBalance(country) > 0) rows += listOf(Button("Мгновенный перевод", "v:$t:${PanelView.TRANSFER.ordinal}"))
                rows += listOfNotNull(Button("Отменить приказ", "v:$t:${PanelView.QUEUE.ordinal}").takeIf { q.isNotEmpty() }, Button(if(country in state.ready) "Не готов" else "Готово", "ok:$t"))
            }
            PanelView.LAUNCH -> state.countries.forEachIndexed { ci, c ->
                rows += c.cities.mapIndexedNotNull { i, city -> if (can(ActionType.LAUNCH, CityRef(c.name, city.name))) Button("${c.name}: ${city.name}", "l:$t:$ci:$i") else null }.chunked(2)
            }
            PanelView.TRANSFER -> rows += state.countries.mapIndexedNotNull { i, c -> if(c.name != country) Button(c.name, "tr:$t:$i") else null }.chunked(2)
            PanelView.SANCTIONS -> rows += state.countries.mapIndexedNotNull { i, c -> if(can(ActionType.SANCTION, to = c.name)) Button(c.name, "sn:$t:$i") else null }.chunked(2)
            PanelView.QUEUE -> q.forEach { rows += listOf(Button("✖ ${it.describe()}", "x:$t:${it.id}")) }
        }
        if (view != PanelView.MAIN) rows += listOf(Button("Назад", "v:$t:${PanelView.MAIN.ordinal}"))
        return text to Keyboard(rows.filter { it.isNotEmpty() }).forGame(state)
    }
}
