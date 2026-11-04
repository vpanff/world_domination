package wd.bot

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject

// Минимальный клиент Telegram Bot API: только то, что нужно игре.

@Serializable
data class TgUser(
    val id: Long,
    @SerialName("first_name") val firstName: String = "",
    @SerialName("last_name") val lastName: String? = null,
    val username: String? = null,
) {
    val displayName: String
        get() = listOfNotNull(firstName.ifBlank { null }, lastName).joinToString(" ")
            .ifBlank { username ?: id.toString() }
}

@Serializable
data class TgChat(val id: Long, val type: String)

@Serializable
data class TgWebAppData(val data: String, @SerialName("button_text") val buttonText: String = "")

@Serializable
data class TgMessage(
    @SerialName("message_id") val messageId: Long,
    val chat: TgChat,
    val from: TgUser? = null,
    val text: String? = null,
    /** Данные, отправленные мини-приложением через Telegram.WebApp.sendData. */
    @SerialName("web_app_data") val webAppData: TgWebAppData? = null,
)

@Serializable
data class TgCallback(
    val id: String,
    val from: TgUser,
    val message: TgMessage? = null,
    val data: String? = null,
)

@Serializable
data class TgUpdate(
    @SerialName("update_id") val updateId: Long,
    val message: TgMessage? = null,
    @SerialName("callback_query") val callback: TgCallback? = null,
)

@Serializable
data class Button(val text: String, @SerialName("callback_data") val data: String)

@Serializable
data class Keyboard(@SerialName("inline_keyboard") val rows: List<List<Button>>)

@Serializable
data class WebAppInfo(val url: String)

@Serializable
data class KeyboardButton(val text: String, @SerialName("web_app") val webApp: WebAppInfo? = null)

/** Клавиатура под полем ввода. Только из неё мини-приложение может отправить данные боту. */
@Serializable
data class ReplyKeyboard(
    val keyboard: List<List<KeyboardButton>>,
    @SerialName("resize_keyboard") val resize: Boolean = true,
    @SerialName("is_persistent") val persistent: Boolean = true,
)

@Serializable
private data class RemoveKeyboard(@SerialName("remove_keyboard") val remove: Boolean = true)

@Serializable
private data class ResponseParameters(@SerialName("retry_after") val retryAfter: Int? = null)

@Serializable
private data class Response(
    val ok: Boolean,
    val result: JsonElement? = null,
    val description: String? = null,
    @SerialName("error_code") val errorCode: Int? = null,
    val parameters: ResponseParameters? = null,
)

/** Telegram ответил ошибкой (неверный токен, конфликт, лимит запросов…). */
class TelegramException(val code: Int, message: String, val retryAfter: Int? = null) : Exception(message)

/** Нет связи с Telegram. */
class NetworkException(cause: Throwable) : Exception(cause.message, cause)

data class HttpResult(val code: Int, val body: String)

interface HttpTransport {
    /** POST с JSON-телом. Бросает [NetworkException] при проблемах сети. */
    suspend fun postJson(url: String, body: String, timeoutSeconds: Int): HttpResult
}

expect fun defaultTransport(): HttpTransport

class TelegramApi(private val token: String, private val http: HttpTransport = defaultTransport()) {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    private suspend fun <T> call(method: String, params: JsonObject, result: KSerializer<T>, timeout: Int = 20): T {
        val response = http.postJson("https://api.telegram.org/bot$token/$method", params.toString(), timeout)
        val parsed = try {
            json.decodeFromString(Response.serializer(), response.body)
        } catch (e: Exception) {
            throw TelegramException(response.code, "Непонятный ответ Telegram (HTTP ${response.code})")
        }
        if (!parsed.ok || parsed.result == null) {
            throw TelegramException(
                parsed.errorCode ?: response.code,
                parsed.description ?: "Ошибка Telegram",
                parsed.parameters?.retryAfter,
            )
        }
        return json.decodeFromJsonElement(result, parsed.result)
    }

    suspend fun getMe(): TgUser = call("getMe", JsonObject(emptyMap()), TgUser.serializer())

    suspend fun getUpdates(offset: Long, timeoutSeconds: Int): List<TgUpdate> = call(
        "getUpdates",
        buildJsonObject {
            put("offset", offset)
            put("timeout", timeoutSeconds)
            putJsonArray("allowed_updates") { add("message"); add("callback_query") }
        },
        ListSerializer(TgUpdate.serializer()),
        timeout = timeoutSeconds + 15,
    )

    suspend fun deleteWebhook() {
        call("deleteWebhook", JsonObject(emptyMap()), JsonElement.serializer())
    }

    suspend fun setCommands(commands: List<Pair<String, String>>) {
        call(
            "setMyCommands",
            buildJsonObject {
                putJsonArray("commands") {
                    for ((command, description) in commands) addJsonObject {
                        put("command", command)
                        put("description", description)
                    }
                }
            },
            JsonElement.serializer(),
        )
    }

    suspend fun send(message: Outgoing) {
        when (message) {
            is Outgoing.Send -> call("sendMessage", buildJsonObject {
                put("chat_id", message.chatId)
                put("text", message.text)
                put("parse_mode", "HTML")
                val markup = when {
                    message.keyboard != null -> json.encodeToJsonElement(message.keyboard)
                    message.reply != null -> json.encodeToJsonElement(message.reply)
                    message.removeReply -> json.encodeToJsonElement(RemoveKeyboard())
                    else -> null
                }
                markup?.let { put("reply_markup", it) }
            }, JsonElement.serializer())

            is Outgoing.Edit -> call("editMessageText", buildJsonObject {
                put("chat_id", message.chatId)
                put("message_id", message.messageId)
                put("text", message.text)
                put("parse_mode", "HTML")
                message.keyboard?.let { put("reply_markup", json.encodeToJsonElement(it)) }
            }, JsonElement.serializer())

            is Outgoing.Answer -> call("answerCallbackQuery", buildJsonObject {
                put("callback_query_id", message.callbackId)
                message.text?.let { put("text", it) }
                put("show_alert", message.alert)
            }, JsonElement.serializer())
        }
    }
}

/** Исходящее сообщение. Все они идут через очередь с повторами. */
sealed interface Outgoing {
    /** Кратко для журнала. */
    fun describe(): String = when (this) {
        is Send -> "сообщение в чат $chatId: ${text.lineSequence().first().take(60)}"
        is Edit -> "обновление панели в чате $chatId"
        is Answer -> "ответ на кнопку"
    }

    data class Send(
        val chatId: Long,
        val text: String,
        val keyboard: Keyboard? = null,
        val reply: ReplyKeyboard? = null,
        val removeReply: Boolean = false,
    ) : Outgoing
    data class Edit(val chatId: Long, val messageId: Long, val text: String, val keyboard: Keyboard? = null) : Outgoing
    data class Answer(val callbackId: String, val text: String? = null, val alert: Boolean = false) : Outgoing
}
