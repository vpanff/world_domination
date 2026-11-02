package wd.core

import kotlinx.serialization.json.Json

private val json = Json {
    prettyPrint = true
    encodeDefaults = true
    ignoreUnknownKeys = true   // старые сохранения читаются новыми версиями
}

fun GameState.toJson(): String = json.encodeToString(GameState.serializer(), this)

fun gameFromJson(text: String): GameState = json.decodeFromString(GameState.serializer(), text)
