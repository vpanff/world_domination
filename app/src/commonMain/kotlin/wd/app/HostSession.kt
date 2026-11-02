package wd.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import wd.bot.GameBot
import wd.bot.GameHost
import wd.bot.TelegramApi
import wd.core.ActionError
import wd.core.GameState

@Serializable
data class AppSettings(
    val botToken: String = "",
    /** Номер последнего обработанного сообщения Telegram — чтобы не обработать его повторно. */
    val botOffset: Long = 0,
)

private const val SETTINGS = "settings"
private const val LOG_LIMIT = 500
private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/**
 * Всё, что живёт дольше одного экрана: открытая партия, настройки, бот.
 * Создаётся платформой один раз. [scope] — главный поток приложения.
 */
class HostSession(saveStorage: Storage, private val config: Storage, private val scope: CoroutineScope) : GameHost {
    val saves = Saves(saveStorage)

    var settings by mutableStateOf(loadSettings())
        private set

    var controller by mutableStateOf<GameController?>(null)
        private set

    var bot by mutableStateOf<GameBot?>(null)
        private set

    /** Журнал бота для консоли (клавиша ` или кнопка «Логи»). */
    val botLog = mutableStateListOf<String>()

    var consoleOpen by mutableStateOf(false)

    /** Горячие клавиши выключены на экранах с полями ввода. */
    var hotkeysEnabled = true

    fun toggleConsole() {
        consoleOpen = !consoleOpen
    }

    private fun log(line: String) {
        botLog += line
        if (botLog.size > LOG_LIMIT) botLog.removeRange(0, botLog.size - LOG_LIMIT)
    }

    /** Платформа узнаёт, что бот запущен или остановлен (Android держит фоновую службу). */
    var onBotRunningChanged: (Boolean) -> Unit = {}

    // ─── Партия ──────────────────────────────────────────────────────────────

    override val state: GameState? get() = controller?.state

    override fun update(transform: (GameState) -> GameState) {
        val c = controller ?: throw ActionError("Партия не открыта")
        c.update(transform)
    }

    fun open(state: GameState) {
        val withInvites = state.withInvites()
        saves.autosave(withInvites)
        controller = GameController(withInvites, ::onStateChanged)
    }

    fun loadAutosave(): GameState? = saves.loadAutosave()

    private fun onStateChanged(old: GameState, new: GameState) {
        saves.autosave(new)
        if (new.turn != old.turn) bot?.broadcastTurn()
    }

    // ─── Бот ─────────────────────────────────────────────────────────────────

    fun startBot() {
        if (settings.botToken.isBlank()) return
        bot?.stop()
        val newBot = GameBot(
            TelegramApi(settings.botToken),
            host = this,
            scope = scope,
            offset = settings.botOffset,
            onOffset = { saveSettings(settings.copy(botOffset = it)) },
            onLog = ::log,
        )
        bot = newBot
        newBot.start()
        onBotRunningChanged(true)
    }

    fun stopBot() {
        bot?.stop()
        onBotRunningChanged(false)
    }

    /** Перезапуск, например после долгих проблем с сетью. Неотправленные сообщения сохраняются. */
    fun restartBot() {
        val current = bot
        if (current == null || current.username == null) {
            startBot()
            return
        }
        current.stop()
        current.start()
        onBotRunningChanged(true)
    }

    /** Проверить токен: возвращает имя бота или текст ошибки. */
    suspend fun checkToken(token: String): Result<String> =
        runCatching { TelegramApi(token.trim()).getMe().username ?: "без имени" }

    fun updateToken(token: String) {
        val trimmed = token.trim()
        if (trimmed == settings.botToken) return
        stopBot()
        bot = null
        saveSettings(AppSettings(botToken = trimmed))   // новый бот — offset с нуля
    }

    // ─── Настройки ───────────────────────────────────────────────────────────

    private fun loadSettings(): AppSettings =
        config.read(SETTINGS)?.let { runCatching { json.decodeFromString(AppSettings.serializer(), it) }.getOrNull() }
            ?: AppSettings()

    private fun saveSettings(value: AppSettings) {
        settings = value
        config.write(SETTINGS, json.encodeToString(AppSettings.serializer(), value))
    }
}
