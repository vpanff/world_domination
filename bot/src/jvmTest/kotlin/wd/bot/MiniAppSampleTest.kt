package wd.bot

import wd.core.ActionType
import wd.core.CityRef
import wd.core.GameSetup
import wd.core.Rules
import wd.core.WorldEventType
import wd.core.addAction
import wd.core.newGame
import wd.core.resolveTurn
import wd.core.triggerEvent
import java.io.File
import kotlin.test.Test

/** Пишет ссылку на локальное мини-приложение с настоящим состоянием — для ручной проверки в браузере. */
class MiniAppSampleTest {
    @Test
    fun writeSampleUrl() {
        val state = newGame(GameSetup(rules = Rules(startBombs = 1)))
            .addAction(ActionType.LAUNCH, "США", CityRef("Россия", "Новосибирск"))
            .addAction(ActionType.UPGRADE, "Россия", CityRef("Россия", "Москва"))
            .resolveTurn()
            .addAction(ActionType.UPGRADE, "Россия", CityRef("Россия", "Москва"))
            .resolveTurn()
            .triggerEvent(WorldEventType.BUILDING_BOOM)
            .addAction(ActionType.SHIELD, "Россия", CityRef("Россия", "Москва"))
        val page = File("../miniapp/index.html").canonicalFile
        File("build/miniapp-sample-url.txt").writeText(MiniApp.url("file://$page", state, "Россия"))
    }
}
