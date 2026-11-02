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

    @Test
    fun defaultGame() {
        val s = newGame()
        assertEquals(5, s.countries.size)
        assertEquals(100, s.ecology)
        s.countries.forEach {
            assertEquals(1000, it.balance)
            assertEquals(1, it.bombs)
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
        assertEquals(95, s.ecology)
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
        // Новая бомба ещё не готова: свободна только стартовая
        s = s.addAction(ActionType.LAUNCH, ru, ny)
        assertFailsWith<ActionError> { s.addAction(ActionType.LAUNCH, ru, ny) }

        s = s.resolveTurn()
        assertEquals(1, s.c(ru).bombs)
        assertEquals(100 - 5 - 4, s.ecology)
    }

    @Test
    fun shieldAbsorbsHitSecretly() {
        val s = newGame()
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
        val s = newGame().addAction(ActionType.LAUNCH, ru, ny).resolveTurn()
        assertFalse(s.city(ny)!!.alive)
        assertEquals(35 * 3, s.c(us).income(s.rules))

        val seenByChina = s.lastEvents().filter { it.visibleTo(cn) }
        assertTrue(CityDestroyed(ny) in seenByChina)
        assertTrue(seenByChina.none { it is LaunchResult })
    }

    @Test
    fun upgradeLostWhenBombedSameTurn() {
        val s = newGame()
            .addAction(ActionType.LAUNCH, ru, ny)
            .addAction(ActionType.UPGRADE, us, ny)
            .resolveTurn()
        assertFalse(s.city(ny)!!.alive)
        assertEquals(1000 - 150 + 35 * 3, s.c(us).balance)
    }

    @Test
    fun launchesAreSimultaneous() {
        val setup = GameSetup(listOf(CountrySetup("A", listOf("a")), CountrySetup("B", listOf("b"))))
        val s = newGame(setup)
            .addAction(ActionType.LAUNCH, "A", CityRef("B", "b"))
            .addAction(ActionType.LAUNCH, "B", CityRef("A", "a"))
            .resolveTurn()
        assertEquals(GameResult.AllDestroyed, s.result)
    }

    @Test
    fun lastCountryWins() {
        val setup = GameSetup(listOf(CountrySetup("A", listOf("a")), CountrySetup("B", listOf("b"))))
        val s = newGame(setup).addAction(ActionType.LAUNCH, "A", CityRef("B", "b")).resolveTurn()
        assertEquals(GameResult.Winner("A"), s.result)
        assertFailsWith<ActionError> { s.resolveTurn() }
    }

    @Test
    fun ecoCatastrophe() {
        val s = newGame().copy(ecology = 5).addAction(ActionType.RESEARCH, ru).resolveTurn()
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
        val s = newGame()
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
        val s = newGame()
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
    fun setupValidation() {
        assertFailsWith<IllegalArgumentException> {
            newGame(GameSetup(listOf(CountrySetup("A", listOf("a", "a")), CountrySetup("B", listOf("b")))))
        }
        assertFailsWith<IllegalArgumentException> {
            newGame(GameSetup(listOf(CountrySetup("A", listOf("a")))))
        }
    }
}
