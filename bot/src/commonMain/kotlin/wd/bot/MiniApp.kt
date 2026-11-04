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
import wd.core.ActionType
import wd.core.GameState
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** Приказ из мини-приложения: города и страны — индексами, как в кнопках бота. */
data class MiniOrder(val type: ActionType, val countryIndex: Int? = null, val cityIndex: Int? = null)

data class MiniOrders(val turn: Int, val orders: List<MiniOrder>, val ready: Boolean)

/**
 * Обмен с мини-приложением (miniapp/index.html).
 * Бот → приложение: состояние страны в параметре ссылки ?s= (JSON в base64url).
 * Приложение → бот: приказы через Telegram.WebApp.sendData.
 */
object MiniApp {
    private val base64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

    private val codes = mapOf(
        ActionType.UPGRADE to "u", ActionType.SHIELD to "s", ActionType.RESEARCH to "r",
        ActionType.PRODUCE to "p", ActionType.ECOLOGY to "e", ActionType.LAUNCH to "l",
    )
    private val types = codes.entries.associate { (k, v) -> v to k }

    /** Ссылка на мини-приложение с состоянием страны [country]. */
    fun url(base: String, state: GameState, country: String): String {
        val separator = if ('?' in base) '&' else '?'
        return "$base${separator}s=${base64.encode(encodeState(state, country).encodeToByteArray())}"
    }

    internal fun encodeState(state: GameState, country: String): String {
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
                listOf(r.costUpgrade, r.costShield, r.costResearch, r.costProduce, r.costEcology).forEach { add(it) }
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
        }.toString()
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
