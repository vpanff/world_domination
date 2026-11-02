package wd.app

import wd.core.GameState
import wd.core.gameFromJson
import wd.core.toJson

data class SaveInfo(val name: String, val modifiedMillis: Long)

/** Хранилище сохранений. Реализация своя для каждой платформы. */
interface Storage {
    fun read(name: String): String?
    fun write(name: String, text: String)
    fun delete(name: String)
    fun list(): List<SaveInfo>
}

private const val AUTOSAVE = "autosave"

class Saves(private val storage: Storage) {
    fun loadAutosave(): GameState? = load(AUTOSAVE)

    fun autosave(state: GameState) = storage.write(AUTOSAVE, state.toJson())

    fun load(name: String): GameState? =
        storage.read(name)?.let { runCatching { gameFromJson(it) }.getOrNull() }

    /** Сохраняет под именем «Ход N», при совпадении добавляет номер. Возвращает имя. */
    fun save(state: GameState): String {
        val base = "Ход ${state.turn}"
        val taken = storage.list().map { it.name }.toSet()
        val name = generateSequence(1) { it + 1 }
            .map { if (it == 1) base else "$base ($it)" }
            .first { it !in taken }
        storage.write(name, state.toJson())
        return name
    }

    fun delete(name: String) = storage.delete(name)

    fun list(): List<SaveInfo> =
        storage.list().filter { it.name != AUTOSAVE }.sortedByDescending { it.modifiedMillis }
}
