package wd.core

/** Что игрок знает о чужой стране: только какие города живы. */
data class OtherCountry(
    val name: String,
    val aliveCities: List<String>,
    val destroyedCities: List<String>,
)

/** Личный отчёт главе страны в начале хода. */
data class PlayerReport(
    val turn: Int,
    val ecology: Int,
    val me: Country,
    val income: Int,
    val availableBalance: Int,
    val pending: List<Action>,
    val others: List<OtherCountry>,
    /** События прошлого хода, видимые этому игроку. */
    val events: List<GameEvent>,
    val result: GameResult?,
)

fun GameState.reportFor(countryName: String): PlayerReport {
    val me = country(countryName) ?: throw ActionError("Страна '$countryName' не найдена")
    return PlayerReport(
        turn = turn,
        ecology = ecology,
        me = me,
        income = me.income(rules),
        availableBalance = availableBalance(countryName),
        pending = pendingOf(countryName),
        others = countries.filter { it.name != countryName }.map { c ->
            OtherCountry(
                name = c.name,
                aliveCities = c.cities.filter { it.alive }.map { it.name },
                destroyedCities = c.cities.filter { !it.alive }.map { it.name },
            )
        },
        events = log.lastOrNull()?.events.orEmpty().filter { it.visibleTo(countryName) },
        result = result,
    )
}
