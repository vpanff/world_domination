package wd.core

import kotlinx.serialization.Serializable
import kotlin.random.Random

// Все классы неизменяемые: любое действие возвращает новое состояние.
// Это упрощает отмену хода и безопасно при одновременной работе UI и бота.

/** Ссылка на город: имена городов уникальны только внутри страны. */
@Serializable
data class CityRef(val country: String, val city: String) {
    override fun toString() = "$city ($country)"
}

@Serializable
data class City(
    val name: String,
    val level: Int = 0,
    val alive: Boolean = true,
    val shield: Boolean = false,
)

@Serializable
data class Country(
    val name: String,
    val cities: List<City>,
    val balance: Int,
    val bombs: Int,
    val tech: Boolean = false,
    val techInResearch: Boolean = false,
    val bombsInProduction: Int = 0,
) {
    val isAlive: Boolean get() = cities.any { it.alive }

    fun city(name: String): City? = cities.find { it.name == name }

    fun income(rules: Rules): Int = cities.filter { it.alive }.sumOf { rules.income(it.level) }

    fun score(rules: Rules): Int = cities.filter { it.alive }.sumOf { rules.score(it.level) }

    fun updateCity(name: String, change: (City) -> City): Country =
        copy(cities = cities.map { if (it.name == name) change(it) else it })
}

@Serializable
enum class ActionType(val priority: Int) {
    SHIELD(1),
    UPGRADE(2),
    RESEARCH(2),
    PRODUCE(2),
    ECOLOGY(2),
    LAUNCH(3),
}

/**
 * Действие в очереди хода.
 * [target] — свой город для SHIELD/UPGRADE, любой город для LAUNCH, иначе null.
 */
@Serializable
data class Action(
    val id: Int,
    val type: ActionType,
    val country: String,
    val target: CityRef? = null,
)

@Serializable
sealed interface GameResult {
    @Serializable
    data class Winner(val country: String) : GameResult

    @Serializable
    data object EcoCatastrophe : GameResult

    @Serializable
    data object AllDestroyed : GameResult
}

/** Глава страны в Telegram. */
@Serializable
data class Leader(val telegramId: Long, val name: String)

/** События одного рассчитанного хода. */
@Serializable
data class TurnLog(val turn: Int, val events: List<GameEvent>)

@Serializable
data class GameState(
    val rules: Rules,
    val countries: List<Country>,
    val ecology: Int,
    val turn: Int = 1,
    val result: GameResult? = null,
    val pending: List<Action> = emptyList(),
    val nextActionId: Int = 1,
    val log: List<TurnLog> = emptyList(),

    /** Главы стран: страна → игрок в Telegram. */
    val leaders: Map<String, Leader> = emptyMap(),
    /** Коды приглашений: страна → код для ссылки t.me/<бот>?start=<код>. */
    val invites: Map<String, String> = emptyMap(),
    /** Страны, отметившие, что закончили ход. Сбрасывается при расчёте хода. */
    val ready: Set<String> = emptySet(),
) {
    val isOver: Boolean get() = result != null

    fun country(name: String): Country? = countries.find { it.name == name }

    fun city(ref: CityRef): City? = country(ref.country)?.city(ref.city)

    fun aliveCountries(): List<Country> = countries.filter { it.isAlive }

    fun pendingOf(country: String): List<Action> = pending.filter { it.country == country }

    /** Баланс за вычетом действий, уже стоящих в очереди. */
    fun availableBalance(country: String): Int {
        val c = country(country) ?: return 0
        return c.balance - pendingOf(country).sumOf { rules.cost(it.type) }
    }

    fun countryOfLeader(telegramId: Long): String? =
        leaders.entries.find { it.value.telegramId == telegramId }?.key

    fun countryByInvite(code: String): String? = invites.entries.find { it.value == code }?.key

    /** Добавляет недостающие коды приглашений (для старых сохранений). */
    fun withInvites(): GameState =
        if (countries.all { it.name in invites }) this
        else copy(invites = countries.associate { it.name to (invites[it.name] ?: inviteCode()) })

    fun updateCountry(name: String, change: (Country) -> Country): GameState =
        copy(countries = countries.map { if (it.name == name) change(it) else it })

    fun updateCity(ref: CityRef, change: (City) -> City): GameState =
        updateCountry(ref.country) { it.updateCity(ref.city, change) }
}

private const val CODE_CHARS = "abcdefghijkmnpqrstuvwxyz23456789"

fun inviteCode(): String = (1..8).map { CODE_CHARS[Random.nextInt(CODE_CHARS.length)] }.joinToString("")
