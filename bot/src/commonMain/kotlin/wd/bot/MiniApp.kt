@file:OptIn(ExperimentalEncodingApi::class)

package wd.bot

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import wd.core.Action
import wd.core.ActionFailed
import wd.core.ActionType
import wd.core.BombOrdered
import wd.core.BombsReady
import wd.core.CityDestroyed
import wd.core.CityRef
import wd.core.CityUpgraded
import wd.core.EcologyFunded
import wd.core.GameEvent
import wd.core.GameState
import wd.core.IncomeReceived
import wd.core.LaunchOutcome
import wd.core.LaunchResult
import wd.core.ResearchStarted
import wd.core.ShieldBuilt
import wd.core.ShieldHit
import wd.core.TechReady
import wd.core.WorldEventHappened
import wd.core.describe
import wd.core.icon
import wd.core.reportFor
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** Приказ из мини-приложения: города и страны — индексами, как в кнопках бота. */
data class MiniOrder(val type: ActionType, val countryIndex: Int? = null, val cityIndex: Int? = null)

data class MiniOrders(val turn: Int, val orders: List<MiniOrder>, val ready: Boolean)

/**
 * Обмен с мини-приложением (miniapp/index.html).
 * Бот → приложение: состояние страны в параметре ссылки ?s= (JSON в base64url).
 * Приложение → бот: приказы через Telegram.WebApp.sendData.
 *
 * Отчёт идёт в ссылке короткими кодами, а тексты собирает страница (reportLine):
 * русский текст в base64 раздул бы ссылку в несколько раз.
 */
object MiniApp {
    private val base64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

    private val codes = mapOf(
        ActionType.UPGRADE to "u", ActionType.SHIELD to "s", ActionType.RESEARCH to "r",
        ActionType.PRODUCE to "p", ActionType.ECOLOGY to "e", ActionType.LAUNCH to "l",
    )
    private val types = codes.entries.associate { (k, v) -> v to k }

    /** Длиннее ссылка идёт без отчёта: он всё равно есть в чате. Предел Telegram не документирован, берём с запасом. */
    private const val MAX_URL_WITH_REPORT = 2000

    /** Ссылка на мини-приложение с состоянием страны [country]. */
    fun url(base: String, state: GameState, country: String): String {
        val separator = if ('?' in base) '&' else '?'
        fun build(withReport: Boolean) =
            "$base${separator}s=${base64.encode(encodeState(state, country, withReport).encodeToByteArray())}"
        return build(withReport = true).takeIf { it.length <= MAX_URL_WITH_REPORT } ?: build(withReport = false)
    }

    internal fun encodeState(state: GameState, country: String, withReport: Boolean = true): String {
        val me = state.country(country)!!
        val r = state.rules
        return buildJsonObject {
            put("v", 1)
            put("t", state.turn)
            put("c", country)
            put("e", state.ecology)
            put("b", me.balance)
            put("m", me.bombs)
            put("tech", if (me.tech) 2 else if (me.techInResearch) 1 else 0)
            putJsonArray("cost") {
                listOf(ActionType.UPGRADE, ActionType.SHIELD, ActionType.RESEARCH, ActionType.PRODUCE, ActionType.ECOLOGY)
                    .forEach { add(state.cost(it)) }
            }
            put("ml", r.maxLevel)
            putJsonArray("ci") {
                for (city in me.cities) addJsonArray {
                    add(city.name); add(city.level); add(if (city.shield) 1 else 0); add(if (city.alive) 1 else 0)
                }
            }
            putJsonArray("o") {
                state.countries.forEachIndexed { ki, c ->
                    if (c.name == country) return@forEachIndexed
                    addJsonArray {
                        add(ki); add(c.name)
                        addJsonArray { for (city in c.cities) addJsonArray { add(city.name); add(if (city.alive) 1 else 0) } }
                    }
                }
            }
            putJsonArray("q") { for (a in state.pendingOf(country)) add(encodeOrder(state, a)) }
            put("r", country in state.ready)
            state.activeEvent?.let { put("ev", "${it.icon()} ${it.describe()}") }
            if (withReport) {
                val report = state.reportFor(country)
                report.ecologyBefore?.let { put("ep", it) }
                putJsonArray("rc") { report.countryEvents.forEach { add(encodeEvent(state, it)) } }
                putJsonArray("rw") { report.worldEvents.forEach { add(encodeEvent(state, it)) } }
                report.developmentReport?.let { dev ->
                    put("dt", dev.turn)
                    putJsonArray("dv") {
                        for (e in dev.entries) addJsonArray {
                            add(state.countries.indexOfFirst { it.name == e.country }); add(e.value)
                            e.change?.let { add(it) }
                        }
                    }
                }
            }
        }.toString()
    }

    private fun cityIndexes(state: GameState, ref: CityRef): Pair<Int, Int> {
        val ki = state.countries.indexOfFirst { it.name == ref.country }
        return ki to state.countries[ki].cities.indexOfFirst { it.name == ref.city }
    }

    /** Код события: см. reportLine в miniapp/index.html. Города — индексами (страна, город), как в приказах. */
    private fun encodeEvent(state: GameState, e: GameEvent): JsonArray = kotlinx.serialization.json.buildJsonArray {
        fun code(code: String, ref: CityRef) {
            val (ki, ci) = cityIndexes(state, ref)
            add(code); add(ki); add(ci)
        }
        when (e) {
            is ShieldBuilt -> code("sb", CityRef(e.country, e.city))
            is CityUpgraded -> { code("up", CityRef(e.country, e.city)); add(e.level) }
            is ResearchStarted -> add("rs")
            is BombOrdered -> add("bo")
            is EcologyFunded -> add("ef")
            is LaunchResult -> when (e.outcome) {
                LaunchOutcome.DESTROYED -> code("ld", e.target)
                LaunchOutcome.SHIELD_HIT -> code("ls", e.target)
                LaunchOutcome.ALREADY_DESTROYED -> code("la", e.target)
            }
            is ShieldHit -> code("sh", e.target)
            is TechReady -> add("tr")
            is BombsReady -> { add("br"); add(e.count); add(e.total) }
            is IncomeReceived -> { add("in"); add(e.amount); add(e.balance) }
            is ActionFailed -> { add("af"); add(e.reason) }
            is CityDestroyed -> code("cd", e.target)
            is WorldEventHappened -> { add("ev"); add("${e.event.icon()} ${e.event.describe()}") }
            else -> error("Событие не входит в отчёт: $e")
        }
    }

    private fun encodeOrder(state: GameState, a: Action): JsonArray {
        val code = codes.getValue(a.type)
        val t = a.target
        return kotlinx.serialization.json.buildJsonArray {
            add(code)
            when (a.type) {
                ActionType.UPGRADE, ActionType.SHIELD -> add(state.country(a.country)!!.cities.indexOfFirst { it.name == t!!.city })
                ActionType.LAUNCH -> {
                    val ki = state.countries.indexOfFirst { it.name == t!!.country }
                    add(ki); add(state.countries[ki].cities.indexOfFirst { it.name == t!!.city })
                }
                else -> {}
            }
        }
    }

    /** Разбирает данные sendData. Бросает исключение при неверном формате. */
    fun parseOrders(data: String): MiniOrders {
        val root = Json.parseToJsonElement(data).jsonObject
        val orders = root.getValue("q").jsonArray.map { item ->
            val parts = item.jsonArray
            val type = types[parts[0].jsonPrimitive.content] ?: error("Неизвестный приказ")
            when (type) {
                ActionType.UPGRADE, ActionType.SHIELD -> MiniOrder(type, cityIndex = parts[1].jsonPrimitive.int)
                ActionType.LAUNCH -> MiniOrder(type, parts[1].jsonPrimitive.int, parts[2].jsonPrimitive.int)
                else -> MiniOrder(type)
            }
        }
        return MiniOrders(root.getValue("t").jsonPrimitive.int, orders, root["r"]?.jsonPrimitive?.boolean ?: false)
    }
}
