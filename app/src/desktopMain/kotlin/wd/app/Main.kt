package wd.app

import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.io.File

fun main() = application {
    val storage = remember { FileStorage(File(dataDir(), "saves")) }
    Window(
        onCloseRequest = ::exitApplication,
        title = "Мировое господство",
        state = rememberWindowState(width = 1400.dp, height = 900.dp),
    ) {
        App(storage, onExit = ::exitApplication)
    }
}

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
