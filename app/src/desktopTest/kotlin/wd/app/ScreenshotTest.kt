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
import wd.core.CityRef
import wd.core.newGame
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

    private val memory = object : Storage {
        val files = mutableMapOf<String, String>()
        override fun read(name: String) = files[name]
        override fun write(name: String, text: String) { files[name] = text }
        override fun delete(name: String) { files.remove(name) }
        override fun list() = files.keys.map { SaveInfo(it, 0) }
    }

    private fun midGame(): GameController {
        val c = GameController(newGame()) {}
        c.add(ActionType.UPGRADE, "Россия", CityRef("Россия", "Москва"))
        c.add(ActionType.SHIELD, "США", CityRef("США", "Нью-Йорк"))
        c.add(ActionType.RESEARCH, "Китай")
        c.add(ActionType.LAUNCH, "Россия", CityRef("США", "Нью-Йорк"))
        c.add(ActionType.LAUNCH, "Германия", CityRef("Израиль", "Хайфа"))
        c.resolve()
        c.add(ActionType.UPGRADE, "Россия", CityRef("Россия", "Москва"))
        c.add(ActionType.ECOLOGY, "Израиль")
        return c
    }

    @Test
    fun screens() {
        shot("menu", 1400, 900) { App(memory) }
        shot("new_game", 1400, 900) { NewGameScreen({}, {}) }
        shot("game_wide", 1400, 900) { GameScreen(midGame(), Saves(memory)) {} }
        shot("game_narrow", 360, 780) { GameScreen(midGame(), Saves(memory)) {} }
        shot("game_narrow_attack", 360, 780) { GameScreen(midGame().apply { startLaunch("США") }, Saves(memory)) {} }
        shot("new_game_narrow", 360, 780) { NewGameScreen({}, {}) }
    }
}
