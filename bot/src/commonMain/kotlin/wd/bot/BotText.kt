package wd.bot

import wd.core.Action
import wd.core.ActionType
import wd.core.CityRef
import wd.core.DevelopmentReport
import wd.core.GameState
import wd.core.describe
import wd.core.ecologyChangeText
import wd.core.icon
import wd.core.playerText
import wd.core.reportFor
import wd.core.validate

/** Экран панели хода. */
enum class PanelView { MAIN, LAUNCH, QUEUE }

// Данные кнопок (лимит Telegram — 64 байта), поэтому города передаются индексами:
//   u:<ход>:<город>       улучшение своего города
//   s:<ход>:<город>       щит
//   r:<ход> p:<ход> e:<ход>   исследование, бомба, экология
//   l:<ход>:<страна>:<город>  запуск
//   x:<ход>:<id>          отмена приказа
//   v:<ход>:<экран>       переключить экран панели
//   ok:<ход>              готов / не готов

internal fun String.html(): String = replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

private fun GameState.can(type: ActionType, country: String, target: CityRef? = null) =
    runCatching { validate(type, country, target) }.isSuccess

private fun Action.line(): String = when (type) {
    ActionType.SHIELD -> "🛡 Щит: ${target?.city}"
    ActionType.UPGRADE -> "⬆ Улучшение: ${target?.city}"
    ActionType.RESEARCH -> "🔬 Исследование"
    ActionType.PRODUCE -> "💣 Производство бомбы"
    ActionType.ECOLOGY -> "🌿 Экология"
    ActionType.LAUNCH -> "🚀 Запуск → ${target?.city} (${target?.country})"
}

object BotText {

    /** Личный отчёт в начале хода. */
    fun report(state: GameState, country: String): String {
        val r = state.reportFor(country)
        val me = r.me
        return buildString {
            appendLine("🌍 <b>Ход ${r.turn}</b> · Экология ${r.ecology}%")
            r.activeEvent?.let {
                appendLine()
                appendLine("${it.icon()} <b>Ивент хода:</b> ${it.describe().html()}")
            }
            if (r.countryEvents.isNotEmpty()) {
                appendLine()
                appendLine("<b>События в стране</b>")
                for (e in r.countryEvents) appendLine("${e.icon()} ${e.playerText().html()}")
            }
            r.ecologyBefore?.let { before ->
                appendLine()
                appendLine("<b>События в мире</b>")
                appendLine("🌍 ${ecologyChangeText(before, r.ecology)}")
                for (e in r.worldEvents) appendLine("${e.icon()} ${e.playerText().html()}")
            }
            appendLine()
            appendLine("<b>Ваша страна — ${me.name.html()}</b>")
            if (!me.isAlive) {
                appendLine("☠ Страна уничтожена")
            } else {
                appendLine("💰 ${me.balance}$ · доход +${r.income}$/ход")
                val production = if (me.bombsInProduction > 0) " (+${me.bombsInProduction} в производстве)" else ""
                appendLine("💣 Бомбы: ${me.bombs}$production")
                val tech = when {
                    me.tech -> "есть"
                    me.techInResearch -> "исследуется"
                    else -> "нет"
                }
                appendLine("🔬 Технология: $tech")
                for (city in me.cities) {
                    if (city.alive) appendLine("• ${city.name.html()} — ур.${city.level}" + if (city.shield) " 🛡" else "")
                    else appendLine("• <s>${city.name.html()}</s> — уничтожен")
                }
            }
            appendLine()
            appendLine("<b>Другие страны</b>")
            for (other in r.others) {
                val cities = other.aliveCities.map { it.html() } + other.destroyedCities.map { "<s>${it.html()}</s>" }
                val dead = if (other.aliveCities.isEmpty()) " ☠" else ""
                appendLine("${other.name.html()}$dead: ${cities.joinToString(", ")}")
            }
            r.result?.let {
                appendLine()
                appendLine("<b>${it.describe().html()}</b>")
            }
        }.trimEnd()
    }

    /** Доклад о развитии стран. Один и тот же для всех. */
    fun development(report: DevelopmentReport): String = buildString {
        appendLine("📊 <b>Доклад международной службы об уровне жизни</b>")
        appendLine("Индекс развития стран, ход ${report.turn}:")
        appendLine()
        report.entries.forEachIndexed { i, e ->
            val change = e.change?.let { if (it >= 0) " (+$it)" else " (−${-it})" }.orEmpty()
            appendLine("${i + 1}. ${e.country.html()} — ${e.value}$change")
        }
    }.trimEnd()

    /** Панель хода: краткое состояние и кнопки приказов. */
    fun panel(state: GameState, country: String, view: PanelView = PanelView.MAIN): Pair<String, Keyboard?> {
        val me = state.country(country) ?: return "Страна не найдена" to null
        val t = state.turn
        if (state.isOver) return "Игра окончена. ${state.result!!.describe().html()}" to null
        if (!me.isAlive) return "☠ Ваша страна уничтожена. Вы можете продолжать переговоры." to null

        val pending = state.pendingOf(country)
        val freeBombs = me.bombs - pending.count { it.type == ActionType.LAUNCH }
        val ready = country in state.ready

        val text = buildString {
            appendLine("<b>Ход $t · ${country.html()}</b>")
            appendLine("💰 Доступно: ${state.availableBalance(country)}$ из ${me.balance}$")
            appendLine("💣 Свободных бомб: $freeBombs")
            state.activeEvent?.let { appendLine("${it.icon()} ${it.describe().html()}") }
            appendLine()
            if (pending.isEmpty()) appendLine("Приказов пока нет")
            else {
                appendLine("<b>Приказы:</b>")
                pending.forEachIndexed { i, a -> appendLine("${i + 1}. ${a.line().html()}") }
            }
            appendLine()
            when (view) {
                PanelView.MAIN -> appendLine(if (ready) "✅ Вы готовы. Ждём остальных." else "Когда закончите — нажмите «Готово».")
                PanelView.LAUNCH -> appendLine("🚀 <b>Выберите цель</b>")
                PanelView.QUEUE -> appendLine("Нажмите на приказ, чтобы отменить его")
            }
        }.trimEnd()

        val rows = mutableListOf<List<Button>>()
        when (view) {
            PanelView.MAIN -> {
                me.cities.forEachIndexed { i, city ->
                    if (!city.alive) return@forEachIndexed
                    val ref = CityRef(country, city.name)
                    val row = mutableListOf<Button>()
                    val queuedUp = pending.count { it.type == ActionType.UPGRADE && it.target == ref }
                    if (state.can(ActionType.UPGRADE, country, ref))
                        row += Button("⬆ ${city.name} ${city.level + queuedUp}→${city.level + queuedUp + 1}", "u:$t:$i")
                    if (state.can(ActionType.SHIELD, country, ref))
                        row += Button("🛡 ${city.name}", "s:$t:$i")
                    if (row.isNotEmpty()) rows += row
                }
                rows += listOfNotNull(
                    Button("🔬 Исследование ${state.cost(ActionType.RESEARCH)}$", "r:$t").takeIf { state.can(ActionType.RESEARCH, country) },
                    Button("💣 Бомба ${state.cost(ActionType.PRODUCE)}$", "p:$t").takeIf { state.can(ActionType.PRODUCE, country) },
                )
                rows += listOfNotNull(
                    Button("🌿 Экология ${state.cost(ActionType.ECOLOGY)}$", "e:$t").takeIf { state.can(ActionType.ECOLOGY, country) },
                    Button("🚀 Запуск", "v:$t:${PanelView.LAUNCH.ordinal}").takeIf { freeBombs > 0 },
                )
                rows += listOfNotNull(
                    Button("📋 Отменить приказ", "v:$t:${PanelView.QUEUE.ordinal}").takeIf { pending.isNotEmpty() },
                    Button(if (ready) "↩ Не готов" else "✅ Готово", "ok:$t"),
                )
            }

            PanelView.LAUNCH -> {
                val targets = mutableListOf<Button>()
                state.countries.forEachIndexed { ci, c ->
                    if (c.name == country) return@forEachIndexed
                    c.cities.forEachIndexed { i, city ->
                        if (city.alive) targets += Button("${c.name}: ${city.name}", "l:$t:$ci:$i")
                    }
                }
                rows += targets.chunked(2)
                rows += listOf(Button("« Назад", "v:$t:${PanelView.MAIN.ordinal}"))
            }

            PanelView.QUEUE -> {
                for (a in pending) rows += listOf(Button("✖ ${a.line()}", "x:$t:${a.id}"))
                rows += listOf(Button("« Назад", "v:$t:${PanelView.MAIN.ordinal}"))
            }
        }
        return text to Keyboard(rows.filter { it.isNotEmpty() })
    }
}
