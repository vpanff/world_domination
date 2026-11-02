package wd.app

import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.MainScope
import java.io.File

fun main() = application {
    val session = remember {
        HostSession(FileStorage(File(dataDir(), "saves")), FileStorage(File(dataDir(), "config")), MainScope())
    }
    Window(
        onCloseRequest = {
            session.stopBot()
            exitApplication()
        },
        title = "Мировое господство",
        onPreviewKeyEvent = { event ->
            // ` (на русской раскладке — ё) открывает консоль бота
            val grave = event.key == Key.Grave || event.utf16CodePoint in GRAVE_CHARS
            if (grave && event.type == KeyEventType.KeyDown && session.hotkeysEnabled) {
                session.toggleConsole()
                true
            } else false
        },
        state = rememberWindowState(width = 1400.dp, height = 900.dp),
    ) {
        App(session, onExit = {
            session.stopBot()
            exitApplication()
        })
    }
}

private val GRAVE_CHARS = setOf('`'.code, '~'.code, 'ё'.code, 'Ё'.code)

/** Папка данных приложения по соглашениям каждой ОС. */
private fun dataDir(): File {
    val os = System.getProperty("os.name").lowercase()
    val home = System.getProperty("user.home")
    val base = when {
        "win" in os -> System.getenv("APPDATA") ?: home
        "mac" in os -> "$home/Library/Application Support"
        else -> System.getenv("XDG_DATA_HOME") ?: "$home/.local/share"
    }
    return File(base, "world-domination")
}
