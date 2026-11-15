package wd.bot

import wd.core.ActionType
import wd.core.CityRef
import wd.core.GameSetup
import wd.core.Rules
import wd.core.WorldEventType
import wd.core.addAction
import wd.core.addTransfer
import wd.core.newGame
import wd.core.resolveTurn
import wd.core.triggerEvent
import java.io.File
import kotlin.test.Test

/** Пишет ссылку на локальное мини-приложение с настоящим состоянием — для ручной проверки в браузере. */
class MiniAppSampleTest {
    @Test fun hardSample() {
        val state = wd.core.newHardGame().updateCountry("Великобритания") { it.copy(tech = true, balance = 5000, bombs = 1) }
        val page = File("../miniapp/hard.html").canonicalFile
        File("build/hard-miniapp-sample-url.txt").writeText(MiniApp.url("file://$page", state, "Великобритания"))
        val ruined = state.updateCountry("Великобритания") { c -> c.copy(cities = c.cities.map { it.copy(alive = false, life = 0.0, development = 0.0) }) }
        File("build/hard-miniapp-ruined-url.txt").writeText(MiniApp.url("file://$page", ruined, "Великобритания"))
    }

    @Test
    fun writeSampleUrl() {
        val state = newGame(GameSetup(rules = Rules(startBombs = 1)))
            .addAction(ActionType.LAUNCH, "США", CityRef("Россия", "Новосибирск"))
            .addAction(ActionType.UPGRADE, "Россия", CityRef("Россия", "Москва"))
            .addTransfer("Китай", "Россия", 200)
            .resolveTurn()
            .addAction(ActionType.UPGRADE, "Россия", CityRef("Россия", "Москва"))
            .resolveTurn()
            .triggerEvent(WorldEventType.BUILDING_BOOM)
            .addAction(ActionType.SHIELD, "Россия", CityRef("Россия", "Москва"))
            .addTransfer("Россия", "Китай", 100)
        val page = File("../miniapp/index.html").canonicalFile
        File("build/miniapp-sample-url.txt").writeText(MiniApp.url("file://$page", state, "Россия"))
    }
}
