package wd.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EngineTest {
    private val ru = "Россия"
    private val us = "США"
    private val cn = "Китай"
    private val moscow = CityRef(ru, "Москва")
    private val ny = CityRef(us, "Нью-Йорк")

    private fun GameState.c(name: String) = country(name)!!
    private fun GameState.lastEvents() = log.last().events

    /** Стартовых бомб нет: тестам ударов бомба выдаётся сразу. */
    private fun armed() = newGame(GameSetup(rules = Rules(startBombs = 1)))
    private fun duel() = newGame(
        GameSetup(listOf(CountrySetup("A", listOf("a")), CountrySetup("B", listOf("b"))), Rules(startBombs = 1))
    )

    @Test
    fun defaultGame() {
        val s = newGame()
        assertEquals(5, s.countries.size)
        assertEquals(100, s.ecology)
        s.countries.forEach {
            assertEquals(1000, it.balance)
            assertEquals(0, it.bombs)
            assertEquals(4, it.cities.size)
        }
    }

    @Test
    fun upgradeAndIncome() {
        val s = newGame().addAction(ActionType.UPGRADE, ru, moscow).resolveTurn()
        assertEquals(1, s.city(moscow)!!.level)
        // 1000 − 150 + доход (70 за ур.1 + 3 × 35)
        assertEquals(1000 - 150 + 70 + 3 * 35, s.c(ru).balance)
        assertEquals(2, s.turn)
    }

    @Test
    fun availableBalanceCountsQueue() {
        var s = newGame()
        for (city in s.c(ru).cities) s = s.addAction(ActionType.SHIELD, ru, CityRef(ru, city.name))
        assertEquals(0, s.availableBalance(ru))
        assertFailsWith<ActionError> { s.addAction(ActionType.UPGRADE, ru, moscow) }

        val first = s.pending.first().id
        s = s.cancelAction(first)
        assertEquals(250, s.availableBalance(ru))
        s.addAction(ActionType.UPGRADE, ru, moscow)
    }

    @Test
    fun queuedUpgradesRespectMaxLevel() {
        var s = newGame(GameSetup(rules = Rules(startBalance = 10_000)))
        repeat(7) { s = s.addAction(ActionType.UPGRADE, ru, moscow) }
        assertFailsWith<ActionError> { s.addAction(ActionType.UPGRADE, ru, moscow) }
    }

    @Test
    fun cannotUpgradeForeignCity() {
        assertFailsWith<ActionError> { newGame().addAction(ActionType.UPGRADE, ru, ny) }
    }

    @Test
    fun researchFinishesNextTurn() {
        var s = newGame().addAction(ActionType.RESEARCH, ru).resolveTurn()
        assertEquals(100 + s.rules.ecologyResearch, s.ecology)
        assertFalse(s.c(ru).tech)
        assertFailsWith<ActionError> { s.addAction(ActionType.PRODUCE, ru) }
        assertFailsWith<ActionError> { s.addAction(ActionType.RESEARCH, ru) }

        s = s.resolveTurn()
        assertTrue(s.c(ru).tech)
        assertTrue(TechReady(ru) in s.lastEvents())
    }

    @Test
    fun bombReadyAtEndOfTurn() {
        var s = newGame().addAction(ActionType.RESEARCH, ru).resolveTurn().resolveTurn()
        s = s.addAction(ActionType.PRODUCE, ru)
        // Новая бомба ещё не готова
        assertFailsWith<ActionError> { s.addAction(ActionType.LAUNCH, ru, ny) }

        s = s.resolveTurn()
        assertEquals(1, s.c(ru).bombs)
        assertEquals(100 + s.rules.ecologyResearch + s.rules.ecologyProduce, s.ecology)
    }

    @Test
    fun shieldAbsorbsHitSecretly() {
        val s = armed()
            .addAction(ActionType.SHIELD, us, ny)
            .addAction(ActionType.LAUNCH, ru, ny)
            .resolveTurn()

        val city = s.city(ny)!!
        assertTrue(city.alive)
        assertFalse(city.shield)
        assertEquals(0, s.c(ru).bombs)

        val events = s.lastEvents()
        assertTrue(events.filter { it.visibleTo(ru) }.contains(LaunchResult(ru, ny, LaunchOutcome.SHIELD_HIT)))
        assertTrue(events.filter { it.visibleTo(us) }.contains(ShieldHit(ny)))
        assertTrue(events.filter { it.visibleTo(us) }.none { it is LaunchResult })
        assertTrue(events.none { it.visibleTo(cn) && (it is ShieldHit || it is LaunchResult) })
    }

    @Test
    fun destructionIsPublicButAttackerHidden() {
        val s = armed().addAction(ActionType.LAUNCH, ru, ny).resolveTurn()
        assertFalse(s.city(ny)!!.alive)
        assertEquals(35 * 3, s.c(us).income(s.rules))

        val seenByChina = s.lastEvents().filter { it.visibleTo(cn) }
        assertTrue(CityDestroyed(ny) in seenByChina)
        assertTrue(seenByChina.none { it is LaunchResult })
    }

    @Test
    fun upgradeLostWhenBombedSameTurn() {
        val s = armed()
            .addAction(ActionType.LAUNCH, ru, ny)
            .addAction(ActionType.UPGRADE, us, ny)
            .resolveTurn()
        assertFalse(s.city(ny)!!.alive)
        assertEquals(1000 - 150 + 35 * 3, s.c(us).balance)
    }

    @Test
    fun launchesAreSimultaneous() {
        val s = duel()
            .addAction(ActionType.LAUNCH, "A", CityRef("B", "b"))
            .addAction(ActionType.LAUNCH, "B", CityRef("A", "a"))
            .resolveTurn()
        assertEquals(GameResult.AllDestroyed, s.result)
    }

    @Test
    fun lastCountryWins() {
        val s = duel().addAction(ActionType.LAUNCH, "A", CityRef("B", "b")).resolveTurn()
        assertEquals(GameResult.Winner("A"), s.result)
        assertFailsWith<ActionError> { s.resolveTurn() }
    }

    @Test
    fun ecoCatastrophe() {
        val s = newGame().copy(ecology = 3).addAction(ActionType.RESEARCH, ru).resolveTurn()
        assertEquals(0, s.ecology)
        assertEquals(GameResult.EcoCatastrophe, s.result)
    }

    @Test
    fun ecologyCappedAt100() {
        val s = newGame().addAction(ActionType.ECOLOGY, ru).resolveTurn()
        assertEquals(100, s.ecology)
        assertTrue(s.lastEvents().none { it is EcologyChanged })
    }

    @Test
    fun reportShowsOnlyAliveCitiesOfOthers() {
        val s = armed()
            .addAction(ActionType.SHIELD, us, CityRef(us, "Чикаго"))
            .addAction(ActionType.LAUNCH, ru, ny)
            .resolveTurn()
        val r = s.reportFor(cn)
        val usa = r.others.single { it.name == us }
        assertEquals(listOf("Нью-Йорк"), usa.destroyedCities)
        assertEquals(3, usa.aliveCities.size)
        assertTrue(r.events.all { it.visibleTo(cn) })
        assertTrue(r.events.none { it is ShieldBuilt })
        assertNull(r.result)
    }

    @Test
    fun jsonRoundTrip() {
        val s = armed()
            .addAction(ActionType.SHIELD, us, ny)
            .addAction(ActionType.LAUNCH, ru, ny)
            .resolveTurn()
            .addAction(ActionType.UPGRADE, ru, moscow)
        assertEquals(s, gameFromJson(s.toJson()))
    }

    @Test
    fun invitesAndReady() {
        var s = newGame()
        assertEquals(5, s.invites.values.toSet().size)
        assertEquals(ru, s.countryByInvite(s.invites.getValue(ru)))

        s = s.copy(leaders = mapOf(ru to Leader(42, "Вова")), ready = setOf(ru))
        assertEquals(ru, s.countryOfLeader(42))
        s = s.resolveTurn()
        assertTrue(s.ready.isEmpty())
        assertEquals(42, s.leaders.getValue(ru).telegramId)

        val old = s.copy(invites = emptyMap()).withInvites()
        assertEquals(5, old.invites.size)
    }

    @Test
    fun rulesScaleWithCountries() {
        assertEquals(Rules(), Rules().forCountries(4))
        val two = Rules().forCountries(2)
        assertEquals(listOf(-10, -8, 40, -20), listOf(two.ecologyResearch, two.ecologyProduce, two.ecologyBoost, two.ecologyVolcano))
        val eight = Rules().forCountries(8)
        assertEquals(listOf(-3, -2, 10, -5), listOf(eight.ecologyResearch, eight.ecologyProduce, eight.ecologyBoost, eight.ecologyVolcano))
        assertEquals(Rules().costUpgrade, eight.costUpgrade)
        assertEquals(Rules().forCountries(5), newGame().rules)
    }

    @Test
    fun discountEventsChangeCostForOneTurn() {
        val s = newGame().triggerEvent(WorldEventType.BUILDING_BOOM)
        assertEquals(110, s.cost(ActionType.UPGRADE))
        assertEquals(s.rules.costResearch, s.cost(ActionType.RESEARCH))
        val after = s.addAction(ActionType.UPGRADE, ru, moscow).also { assertEquals(890, it.availableBalance(ru)) }.resolveTurn()
        assertEquals(1000 - 110 + 70 + 3 * 35, after.c(ru).balance)
        assertNull(after.activeEvent)
        assertEquals(150, after.cost(ActionType.UPGRADE))
        assertEquals(150, newGame().triggerEvent(WorldEventType.GREEN_SUMMIT).cost(ActionType.ECOLOGY))
        assertEquals(375, newGame().triggerEvent(WorldEventType.SCIENCE_BREAKTHROUGH).cost(ActionType.RESEARCH))
    }

    @Test
    fun crisisCutsIncomeForOneTurn() {
        var s = newGame().triggerEvent(WorldEventType.CRISIS).resolveTurn()
        assertEquals(1000 + 140 * 3 / 4, s.c(ru).balance)
        s = s.resolveTurn()
        assertEquals(1000 + 105 + 140, s.c(ru).balance)
    }

    @Test
    fun volcanoNeedsEnoughEcology() {
        val low = newGame().copy(ecology = 29)
        assertFalse(WorldEventType.VOLCANO in low.possibleEvents())
        assertFailsWith<ActionError> { low.triggerEvent(WorldEventType.VOLCANO) }
        repeat(50) { assertTrue(low.triggerRandomEvent().activeEvent!!.type != WorldEventType.VOLCANO) }

        val s = newGame().copy(ecology = 30).triggerEvent(WorldEventType.VOLCANO)
        assertEquals(30, s.ecology, "экология меняется при расчёте хода")
        val after = s.resolveTurn()
        assertEquals(30 + s.rules.ecologyVolcano, after.ecology)
        assertNull(after.result)
        assertTrue(EcologyChanged(30, after.ecology) in after.lastEvents())
    }

    @Test
    fun oneEventPerTurnAndLeakCountsBombs() {
        val s = armed().triggerEvent(WorldEventType.INTEL_LEAK)
        assertEquals(5, s.activeEvent!!.value)
        assertFailsWith<ActionError> { s.triggerRandomEvent() }
        val after = s.resolveTurn()
        assertTrue(WorldEventHappened(s.activeEvent) in after.lastEvents())
        after.triggerRandomEvent()
    }

    @Test
    fun reportSplitsCountryAndWorld() {
        val s = armed()
            .addAction(ActionType.UPGRADE, us, CityRef(us, "Чикаго"))
            .addAction(ActionType.UPGRADE, ru, moscow)
            .addAction(ActionType.RESEARCH, ru)
            .addAction(ActionType.LAUNCH, cn, ny)
            .addAction(ActionType.LAUNCH, us, moscow)
            .triggerEvent(WorldEventType.CRISIS)
            .resolveTurn()

        val r = s.reportFor(cn)
        assertEquals(100, r.ecologyBefore)
        assertEquals(s.ecology, 100 + s.rules.ecologyResearch)
        assertTrue(CityDestroyed(ny) in r.worldEvents)
        assertTrue(r.worldEvents.any { it is WorldEventHappened })
        assertTrue(r.worldEvents.none { it is CityUpgraded }, "чужие улучшения скрыты")
        assertTrue(r.countryEvents.any { it is LaunchResult })

        val mine = s.reportFor(ru)
        assertTrue(CityDestroyed(moscow) in mine.countryEvents, "свой город — в событиях страны")
        assertTrue(CityDestroyed(moscow) !in mine.worldEvents)
        assertNull(newGame().reportFor(ru).ecologyBefore)
    }

    @Test
    fun ecologyChangeTextIsExplicit() {
        assertEquals("Экология: 82% → 68% (−14)", ecologyChangeText(82, 68))
        assertEquals("Экология: 60% → 80% (+20)", ecologyChangeText(60, 80))
        assertEquals("Экология не изменилась: 70%", ecologyChangeText(70, 70))
    }

    @Test
    fun developmentReportOnOddTurnsFromThird() {
        var s = newGame()
        val published = mutableListOf<Int?>()
        repeat(6) {
            s = s.resolveTurn()
            published += s.developmentReport?.turn
        }
        assertEquals(listOf(null, 3, 3, 5, 5, 7), published)
    }

    @Test
    fun developmentIsRoundedAndTracksChange() {
        var s = newGame(GameSetup(rules = Rules(startBalance = 10_000, startBombs = 1)))
        s = s.resolveTurn().resolveTurn()   // начало хода 3
        val first = s.developmentReport!!
        assertEquals(3, first.turn)
        assertTrue(first.entries.all { it.value == 50 && it.change == null }, "40 округляется до 50")

        // Ур.0→1 (+18) у одного города может не изменить округлённый индекс
        s = s.addAction(ActionType.UPGRADE, ru, moscow).resolveTurn()
        assertEquals(first, s.developmentReport, "на чётном ходу новый доклад не выходит")

        s = s.addAction(ActionType.UPGRADE, ru, moscow).addAction(ActionType.LAUNCH, ru, ny).resolveTurn()
        val second = s.developmentReport!!
        assertEquals(5, second.turn)
        assertEquals(ru, second.entries.first().country)
        // Москва ур.2 (52) + 3 города ур.0 (30) = 82 → 75
        assertEquals(DevelopmentEntry(ru, 75, 25), second.entries.first())
        // США: 3 города ур.0 = 30 → 25
        assertEquals(DevelopmentEntry(us, 25, -25), second.entries.single { it.country == us })
    }

    @Test
    fun oldSaveKeepsWorking() {
        val old = """{"rules":{"scoreK":10,"startBombs":1},"countries":[],"ecology":50}"""
        val s = gameFromJson(old)
        assertEquals(50, s.ecology)
        assertNull(s.activeEvent)
        assertNull(s.developmentReport)
    }

    @Test
    fun setupValidation() {
        assertFailsWith<IllegalArgumentException> {
            newGame(GameSetup(listOf(CountrySetup("A", listOf("a", "a")), CountrySetup("B", listOf("b")))))
        }
        assertFailsWith<IllegalArgumentException> {
            newGame(GameSetup(listOf(CountrySetup("A", listOf("a")))))
        }
    }
}
