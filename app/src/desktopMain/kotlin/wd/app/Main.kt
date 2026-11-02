package wd.app

import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

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

/** Сохранения — JSON-файлы в папке. Запись через временный файл, чтобы сбой не испортил сохранение. */
class FileStorage(private val dir: File) : Storage {
    init {
        dir.mkdirs()
    }

    private fun file(name: String) = File(dir, "$name.json")

    override fun read(name: String): String? = file(name).takeIf { it.exists() }?.readText()

    override fun write(name: String, text: String) {
        val tmp = File(dir, "$name.json.tmp")
        tmp.writeText(text)
        Files.move(tmp.toPath(), file(name).toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    override fun delete(name: String) {
        file(name).delete()
    }

    override fun list(): List<SaveInfo> =
        dir.listFiles { f -> f.extension == "json" }.orEmpty()
            .map { SaveInfo(it.nameWithoutExtension, it.lastModified()) }
}
