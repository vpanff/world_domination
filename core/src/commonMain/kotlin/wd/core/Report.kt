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
    /** Что произошло в своей стране: улучшения, щиты, доход, удары по своим городам… */
    val countryEvents: List<GameEvent>,
    /** Что произошло в мире: чужие уничтоженные города, ивенты. */
    val worldEvents: List<GameEvent>,
    /** Экология в начале прошлого хода; null — прошлого хода ещё не было. */
    val ecologyBefore: Int?,
    val activeEvent: WorldEvent?,
    val developmentReport: DevelopmentReport?,
    val result: GameResult?,
)

fun GameState.reportFor(countryName: String): PlayerReport {
    val me = country(countryName) ?: throw ActionError("Страна '$countryName' не найдена")
    val last = log.lastOrNull()?.events
    val events = last.orEmpty().filter { it.visibleTo(countryName) }
    // Экология и конец игры показываются отдельно
    val listed = events.filter { it !is EcologyChanged && it !is GameOver }
    val (world, own) = listed.partition { it is WorldEventHappened || (it is CityDestroyed && it.target.country != countryName) }
    return PlayerReport(
        turn = turn,
        ecology = ecology,
        me = me,
        income = incomeOf(me),
        availableBalance = availableBalance(countryName),
        pending = pendingOf(countryName),
        others = countries.filter { it.name != countryName }.map { c ->
            OtherCountry(
                name = c.name,
                aliveCities = c.cities.filter { it.alive }.map { it.name },
                destroyedCities = c.cities.filter { !it.alive }.map { it.name },
            )
        },
        events = events,
        countryEvents = own,
        worldEvents = world,
        ecologyBefore = last?.let { e -> e.filterIsInstance<EcologyChanged>().firstOrNull()?.from ?: ecology },
        activeEvent = activeEvent,
        developmentReport = developmentReport,
        result = result,
    )
}
