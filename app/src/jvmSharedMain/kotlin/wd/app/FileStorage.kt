package wd.app

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

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
