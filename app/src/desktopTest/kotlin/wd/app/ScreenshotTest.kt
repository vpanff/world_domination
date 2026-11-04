package wd.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import org.jetbrains.skia.EncodedImageFormat
import wd.core.ActionType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import wd.core.CityRef
import wd.core.GameSetup
import wd.core.Leader
import wd.core.Rules
import wd.core.WorldEventType
import wd.core.newGame
import wd.core.triggerEvent
import java.io.File
import kotlin.test.Test

/** Рисует экраны в PNG (app/build/screenshots) без открытия окна — для визуальной проверки. */
class ScreenshotTest {
    private val out = File("build/screenshots").apply { mkdirs() }

    private fun shot(name: String, width: Int, height: Int, content: @Composable () -> Unit) {
        val scene = ImageComposeScene(width, height, Density(1f)) {
            Box(Modifier.fillMaxSize().background(Palette.bg)) { content() }
        }
        scene.render()   // первый кадр — компоновка
        val image = scene.render()
        File(out, "$name.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        scene.close()
    }

    private class Memory : Storage {
        val files = mutableMapOf<String, String>()
        override fun read(name: String) = files[name]
        override fun write(name: String, text: String) { files[name] = text }
        override fun delete(name: String) { files.remove(name) }
        override fun list() = files.keys.map { SaveInfo(it, 0) }
    }

    private fun session() = HostSession(Memory(), Memory(), CoroutineScope(Dispatchers.Unconfined))

    private fun midGame(): HostSession {
        val session = session()
        session.open(newGame(GameSetup(rules = Rules(startBombs = 1))))
        val c = session.controller!!
        c.add(ActionType.UPGRADE, "Россия", CityRef("Россия", "Москва"))
        c.add(ActionType.SHIELD, "США", CityRef("США", "Нью-Йорк"))
        c.add(ActionType.RESEARCH, "Китай")
        c.add(ActionType.LAUNCH, "Россия", CityRef("США", "Нью-Йорк"))
        c.add(ActionType.LAUNCH, "Германия", CityRef("Израиль", "Хайфа"))
        c.resolve()
        c.add(ActionType.UPGRADE, "Россия", CityRef("Россия", "Москва"))
        c.add(ActionType.ECOLOGY, "Израиль")
        c.transfer("Китай", "Израиль", 100)
        c.update { it.triggerEvent(WorldEventType.BUILDING_BOOM) }
        c.update { it.copy(leaders = mapOf("Россия" to Leader(1, "Вова"), "США" to Leader(2, "Анна")), ready = setOf("США")) }
        return session
    }

    @Test
    fun screens() {
        shot("menu", 1400, 900) { App(session()) }
        shot("console", 1000, 700) {
            val session = midGame()
            session.botLog += listOf(
                "12:00:01  Запуск бота",
                "12:00:02  Подключено как @wd_test_bot",
                "12:00:02  Связь с Telegram есть, жду сообщений",
                "12:00:10  ← Вова (42): /start abcd2345",
                "12:00:10  Привязка: Вова (42) → Россия",
                "12:00:11  → сообщение в чат 42: Вы — глава страны Россия",
                "12:01:00  Нет связи с Telegram — повтор через 2 с",
                "12:01:05  Не отправлено (сообщение в чат 7): Forbidden: bot was blocked by the user",
            )
            session.consoleOpen = true
            App(session)
        }
        shot("bot_panel", 360, 600) {
            val config = Memory().apply { files["settings"] = """{"botToken":"123:abc"}""" }
            val session = HostSession(Memory(), config, CoroutineScope(Dispatchers.Unconfined))
            session.open(midGame().controller!!.state)
            BotPanel(session, Modifier.fillMaxSize())
        }
        shot("new_game", 1400, 900) { NewGameScreen({}, {}) }
        shot("game_wide", 1400, 900) { GameScreen(midGame()) {} }
        shot("game_transfer", 1400, 900) { GameScreen(midGame().apply { controller!!.transferFrom = "Россия" }) {} }
        shot("game_narrow_transfer", 360, 780) { GameScreen(midGame().apply { controller!!.transferFrom = "Россия" }) {} }
        shot("game_narrow", 360, 780) { GameScreen(midGame()) {} }
        shot("game_narrow_attack", 360, 780) { GameScreen(midGame().apply { controller!!.startLaunch("США") }) {} }
        shot("new_game_narrow", 360, 780) { NewGameScreen({}, {}) }
    }
}
