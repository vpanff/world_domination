package wd.core

import kotlin.test.*

class HardTest {
    private val uk = "Великобритания"
    private val fr = "Франция"
    private val de = "Германия"
    private val london = CityRef(uk, "Лондон")
    private val paris = CityRef(fr, "Париж")
    private fun rich(rules: HardRules = HardRules()) = newHardGame(rules.copy(startBalance = 10000))
    private fun GameState.dead(name: String) = updateCountry(name) { c -> c.copy(cities = c.cities.map { it.copy(alive = false, life = 0.0, development = 0.0, shield = false) }) }

    @Test fun fiveCountryEcologyMatchesOriginalGlobalPressure() {
        var state = newHardGame()
        for (c in state.countries) state = state.addAction(ActionType.RESEARCH, c.name)
        state = state.resolveTurn()
        assertEquals(82.0, state.hardEcology, 0.00001)
        state = state.copy(countries = state.countries.map { it.copy(bombs = 1) })
        for (c in state.countries) state = state.addAction(ActionType.LAUNCH, c.name, CityRef(c.name, c.cities.first().name))
        state = state.resolveTurn()
        assertEquals(59.5, state.hardEcology, 0.00001)
        assertEquals(450, state.hardRules.unAid)
        val dead = state.updateCountry("Великобритания") { c -> c.copy(cities = c.cities.map { it.copy(alive = false) }) }
        assertTrue(state.hardRules.unAid >= dead.rebuildCost("Великобритания"))
    }

    @Test fun selectedRoundLimitPersistsAndEndsAtThatRound() {
        var state = newGame(GameSetup(mode = GameMode.HARD, hardRules = HardRules(rounds = 3)))
        repeat(2) { state = state.resolveTurn(); assertNull(state.result) }
        state = gameFromJson(state.toJson())
        assertEquals(3, state.hardRules.rounds)
        assertIs<GameResult.Ranking>(state.resolveTurn().result)
        assertFailsWith<IllegalArgumentException> { newHardGame(HardRules(rounds = 0)) }
        assertFailsWith<IllegalArgumentException> { newHardGame(HardRules(rounds = 101)) }
    }

    @Test fun customNamesAreValidatedTrimmedAndSaved() {
        val countries = HARD_COUNTRIES.mapIndexed { i, c -> CountrySetup(" Страна $i ", c.cities.mapIndexed { j, _ -> " Город $j " }) }
        val state = newGame(GameSetup(countries = countries, mode = GameMode.HARD))
        assertEquals("Страна 0", state.countries.first().name)
        assertEquals("Город 0", state.countries.first().cities.first().name)
        assertEquals(state, gameFromJson(state.toJson()))
        assertTrue(state.invites.keys.contains("Страна 0"))
        assertFailsWith<IllegalArgumentException> { newHardGame(countries = countries.take(4)) }
        assertFailsWith<IllegalArgumentException> { newHardGame(countries = countries.map { it.copy(name = "Одна") }) }
        assertFailsWith<IllegalArgumentException> { newHardGame(countries = countries.map { it.copy(cities = listOf("", "Б", "В", "Г")) }) }
        assertFailsWith<IllegalArgumentException> { newHardGame(countries = countries.map { it.copy(cities = listOf("А", "А", "В", "Г")) }) }
    }

    @Test fun setupIsFixedAndClassicIsDefault() {
        val s = newGame(GameSetup(countries = HARD_COUNTRIES, mode = GameMode.HARD))
        assertEquals(5, s.countries.size)
        assertTrue(s.countries.all { it.cities.size == 4 && it.balance == 1000 && it.bombs == 0 })
        assertEquals(80.0, s.city(london)!!.life)
        assertEquals(1434, s.incomeOf(s.country(uk)!!))
        assertTrue(s.possibleEvents().isEmpty())
        assertFailsWith<ActionError> { s.triggerEvent(WorldEventType.CRISIS) }
        assertFalse(newGame().isHard)
        assertFalse(gameFromJson("""{"rules":{},"countries":[],"ecology":100}""").isHard)
    }

    @Test fun investmentsUseDiminishingReturnsAndAggregateByCity() {
        val one = rich().addAction(ActionType.INVEST, uk, london, 200).resolveTurn()
        val two = rich().addAction(ActionType.INVEST, uk, london, 100).addAction(ActionType.INVEST, uk, london, 100).resolveTurn()
        assertEquals(87.82, one.city(london)!!.life, 0.00001)
        assertEquals(88.16, one.city(london)!!.development, 0.00001)
        assertEquals(one.countries, two.countries)
        assertFailsWith<ActionError> { rich().addAction(ActionType.INVEST, fr, london, 100) }
        assertFailsWith<ActionError> { rich().addAction(ActionType.INVEST, uk, london, 0) }
    }

    @Test fun technologyOpensNextRoundAndNewBombCanLaunchImmediately() {
        val queued = rich().addAction(ActionType.RESEARCH, uk)
        assertFailsWith<ActionError> { queued.addAction(ActionType.PRODUCE, uk) }
        val second = queued.resolveTurn()
        assertTrue(second.country(uk)!!.tech)
        assertEquals(74.0, second.city(london)!!.life)
        assertEquals(96.4, second.hardEcology)
        val third = second.addAction(ActionType.PRODUCE, uk).addAction(ActionType.LAUNCH, uk, paris).resolveTurn()
        assertFalse(third.city(paris)!!.alive)
        assertEquals(0, third.country(uk)!!.bombs)
        assertEquals(91.9, third.hardEcology)
        assertEquals(68.0, third.city(london)!!.life)
    }

    @Test fun productionLimitAndCancellationDependencies() {
        var s = rich().updateCountry(uk) { it.copy(tech = true) }
        repeat(3) { s = s.addAction(ActionType.PRODUCE, uk) }
        assertFailsWith<ActionError> { s.addAction(ActionType.PRODUCE, uk) }
        s = s.addAction(ActionType.LAUNCH, uk, paris).addAction(ActionType.LAUNCH, uk, paris).addAction(ActionType.LAUNCH, uk, paris)
        assertEquals(0, s.freeBombs(uk))
        assertFailsWith<ActionError> { s.cancelAction(s.pending.first().id) }
        val cancelled = s.cancelAction(s.pending.last().id).cancelAction(s.pending.first().id)
        assertEquals(0, cancelled.freeBombs(uk))
    }

    @Test fun shieldAndRepeatedHitsPreserveUnusedBombs() {
        val s = rich().updateCountry(uk) { it.copy(bombs = 3) }
            .addAction(ActionType.SHIELD, fr, paris)
            .addAction(ActionType.LAUNCH, uk, paris).addAction(ActionType.LAUNCH, uk, paris)
            .addAction(ActionType.LAUNCH, uk, paris).resolveTurn()
        assertFalse(s.city(paris)!!.alive)
        assertEquals(1, s.country(uk)!!.bombs)
        assertEquals(91.0, s.hardEcology)
        assertTrue(s.log.last().events.any { it is LaunchResult && it.outcome == LaunchOutcome.ALREADY_DESTROYED })
        val shield = rich().updateCountry(uk) { it.copy(bombs = 1) }.addAction(ActionType.SHIELD, fr, paris)
            .addAction(ActionType.LAUNCH, uk, paris).resolveTurn()
        assertEquals(61.0, shield.city(paris)!!.life)
        assertFalse(shield.city(paris)!!.shield)
    }

    @Test fun eliminatedCountriesRecoverAndReceiveAid() {
        val dead = rich().dead(uk).updateCountry(uk) { it.copy(balance = 500) }
        assertEquals(425, dead.rebuildCost(uk))
        assertFailsWith<ActionError> { dead.addAction(ActionType.ECOLOGY, uk) }
        assertFailsWith<ActionError> { dead.addAction(ActionType.LAUNCH, fr, london) }
        val rebuilt = dead.addAction(ActionType.REBUILD, uk, london).resolveTurn()
        assertTrue(rebuilt.city(london)!!.alive)
        assertTrue(rebuilt.city(london)!!.shield)
        assertEquals(25.0, rebuilt.city(london)!!.life)
        assertEquals(30.0, rebuilt.city(london)!!.development)
        assertEquals(500 - 425 + rebuilt.incomeOf(rebuilt.country(uk)!!), rebuilt.country(uk)!!.balance)
        assertEquals(950, dead.resolveTurn().country(uk)!!.balance)
    }

    @Test fun instantTransfersRespectReservationsPersistAndDoNotRepeat() {
        val s = newHardGame().addAction(ActionType.INVEST, uk, london, 900)
        assertFailsWith<ActionError> { s.transferNow(uk, fr, 101, "id") }
        assertFailsWith<ActionError> { s.transferNow(uk, uk, 10, "id") }
        val sent = s.transferNow(uk, fr, 100, "id")
        assertEquals(1100, sent.availableBalance(fr))
        val restored = gameFromJson(sent.toJson()).transferNow(uk, fr, 100, "id")
        assertEquals(sent, restored)
        assertEquals(2, restored.liveEvents.size)
        val next = restored.resolveTurn()
        assertTrue(next.liveEvents.isEmpty())
        assertEquals(1, next.log.last().events.filterIsInstance<MoneySent>().size)
        assertTrue(restored.fullLogText().contains("перевела"))
    }

    @Test fun eliminatedCanSendAndReceiveTransfers() {
        val s = newHardGame().dead(uk).transferNow(fr, uk, 100, "one").transferNow(uk, de, 50, "two")
        assertEquals(1050, s.country(uk)!!.balance)
        assertEquals(1050, s.country(de)!!.balance)
    }

    @Test fun sanctionsAreCappedPrivateAndExpire() {
        var s = rich()
        for (name in listOf(uk, de, "Италия")) s = s.addAction(ActionType.SANCTION, name, toCountry = fr)
        assertFailsWith<ActionError> { s.addAction(ActionType.SANCTION, uk, toCountry = fr) }
        assertFailsWith<ActionError> { s.addAction(ActionType.SANCTION, uk, toCountry = uk) }
        val next = s.resolveTurn()
        assertEquals(10000 + 1004, next.country(fr)!!.balance) // 1024 × 1.4 × .7 = 1003.52
        val sanctions = next.log.last().events.filterIsInstance<HardEvent>().filter { it.text.contains("санкции") }
        assertTrue(sanctions.filter { it.audience == fr }.any { it.text.contains(uk) })
        assertTrue(sanctions.none { it.visibleTo("Швеция") })
        assertEquals(1434, next.resolveTurn().country(fr)!!.balance - next.country(fr)!!.balance)
    }

    @Test fun ecologyIsFractionalAndLowEcologyHurtsLifeAndIncome() {
        val s = rich().copy(hardEcology = 50.0).resolveTurn()
        assertEquals(75.5, s.city(london)!!.life)
        assertTrue(s.incomeOf(s.country(uk)!!) < rich().incomeOf(rich().country(uk)!!))
        val zero = rich().copy(hardEcology = 0.0).resolveTurn()
        assertNull(zero.result, "В хард-режиме при нуле экологии партия продолжается")
        assertEquals(0, zero.incomeOf(zero.country(uk)!!))
        assertEquals(57.2, rich().copy(hardEcology = 50.0).addAction(ActionType.ECOLOGY, uk, amount = 100).resolveTurn().hardEcology)
    }

    @Test fun upkeepAndFinalRoundRanking() {
        val s = newHardGame(HardRules(rounds = 1)).updateCountry(uk) { it.copy(bombs = 2) }.resolveTurn()
        assertEquals(2334, s.country(uk)!!.balance)
        val result = assertIs<GameResult.Ranking>(s.result)
        assertFalse(uk in result.winners, "При равной жизни и развитии побеждает больший бюджет")
        assertEquals(4, result.winners.size)
        assertFailsWith<ActionError> { s.resolveTurn() }
    }

    @Test fun destroyedCitiesCountAsZeroAndTieBreakersWork() {
        var s = rich().updateCity(london) { it.copy(alive = false) }
        assertEquals(60.0, s.country(uk)!!.averageLife())
        assertEquals(uk, s.hardStandings().last().country)
        s = s.updateCountry(fr) { c -> c.copy(cities = c.cities.map { it.copy(development = 81.0) }) }
        assertEquals(fr, s.hardStandings().first().country)
    }

    @Test fun hardSaveRoundTripAndIndependentRules() {
        val s = rich().addAction(ActionType.INVEST, uk, london, 125).addAction(ActionType.SANCTION, uk, toCountry = fr)
        assertEquals(s, gameFromJson(s.toJson()))
        assertEquals(GameMode.HARD, gameFromJson(s.toJson()).mode)
        assertFailsWith<ActionError> { newGame().addAction(ActionType.INVEST, "Россия", CityRef("Россия", "Москва")) }
        assertFailsWith<IllegalArgumentException> { HardRules(rounds = 0).validate() }
        assertFailsWith<IllegalArgumentException> { HardRules(investmentFalloff = Double.NaN).validate() }
    }

    @Test fun retaliatoryStrikesSurviveCompleteDestruction() {
        var state = rich().updateCountry(uk) { it.copy(bombs = 4) }.updateCountry(fr) { it.copy(bombs = 4) }
        state.country(fr)!!.cities.forEach { state = state.addAction(ActionType.LAUNCH, uk, CityRef(fr, it.name)) }
        state.country(uk)!!.cities.forEach { state = state.addAction(ActionType.LAUNCH, fr, CityRef(uk, it.name)) }
        val result = state.resolveTurn()
        assertFalse(result.country(uk)!!.isAlive)
        assertFalse(result.country(fr)!!.isAlive)
        assertEquals(0, result.country(fr)!!.bombs)
        assertEquals(10450, result.country(uk)!!.balance)
        assertNull(result.result)
    }

    @Test fun optionalDecayAndLeaderTaxUseOneWorldSnapshot() {
        val decayed = rich(HardRules(developmentDecay = 50.0)).resolveTurn()
        assertEquals(40.0, decayed.city(london)!!.development)
        assertEquals(10930, decayed.country(uk)!!.balance)
        var taxed = rich(HardRules(wealthTaxPer10 = 5.0))
        taxed = taxed.copy(countries = taxed.countries.map { c -> c.copy(cities = c.cities.map { it.copy(development = if(c.name == uk) 100.0 else 0.0) }) })
        assertEquals(1180, taxed.incomeOf(taxed.country(uk)!!))
    }

    @Test fun sixRoundsFinishWithPersistentRankingAndValidIndicators() {
        var state = newHardGame()
        repeat(6) {
            for (c in state.countries) state = state.addAction(ActionType.INVEST, c.name, CityRef(c.name, c.cities.first().name), 100)
            state = state.resolveTurn()
            assertTrue(state.countries.all { it.balance >= 0 && it.cities.all { city -> city.life in 0.0..100.0 && city.development in 0.0..100.0 } })
        }
        assertEquals(6, state.log.size)
        assertEquals(5, assertIs<GameResult.Ranking>(state.result).winners.size)
        assertEquals(state, gameFromJson(state.toJson()))
    }

    @Test fun limitsAreConfigurable() {
        val s = rich(HardRules(finalRoundStrikes = false, rounds = 1)).updateCountry(uk) { it.copy(bombs = 2) }
        assertFailsWith<ActionError> { s.addAction(ActionType.LAUNCH, uk, paris) }
        val limited = rich(HardRules(launchLimit = true, maxLaunchesPerRound = 1)).updateCountry(uk) { it.copy(bombs = 2) }.addAction(ActionType.LAUNCH, uk, paris)
        assertFailsWith<ActionError> { limited.addAction(ActionType.LAUNCH, uk, paris) }
    }
}
