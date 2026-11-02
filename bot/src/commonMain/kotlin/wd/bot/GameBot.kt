package wd.bot

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import wd.core.ActionError
import wd.core.ActionType
import wd.core.CityRef
import wd.core.GameState
import wd.core.Leader
import wd.core.addAction
import wd.core.cancelAction

/** Партия, которой управляет бот. Все вызовы — из главного потока. */
interface GameHost {
    /** Текущее состояние; null — партия не открыта. */
    val state: GameState?

    /** Изменить состояние. Бросает [ActionError], если действие недопустимо. */
    fun update(transform: (GameState) -> GameState)
}

sealed interface BotStatus {
    data object Stopped : BotStatus
    data object Connecting : BotStatus
    data class Online(val username: String) : BotStatus
    data class Offline(val reason: String, val retryInSeconds: Int) : BotStatus
    data class Failed(val reason: String) : BotStatus
}

private const val POLL_TIMEOUT = 25
private val BACKOFF = listOf(1, 2, 4, 8, 15, 30)

/**
 * Telegram-бот партии. Работает в [scope] (главный поток): сетевые вызовы
 * приостанавливаются, а не блокируют, поэтому состояние игры трогается только из одного потока.
 *
 * Устойчивость к сети: при ошибке — повтор с нарастающей паузой; исходящие сообщения
 * стоят в очереди и отправляются, когда связь вернётся; offset сохраняется через [onOffset],
 * чтобы после перезапуска не обработать сообщения повторно.
 */
class GameBot(
    private val api: TelegramApi,
    private val host: GameHost,
    private val scope: CoroutineScope,
    private var offset: Long = 0,
    private val onOffset: (Long) -> Unit = {},
) {
    private val _status = MutableStateFlow<BotStatus>(BotStatus.Stopped)
    val status: StateFlow<BotStatus> = _status

    var username: String? = null
        private set

    /** Очередь переживает перезапуск бота: что не успело уйти, уйдёт после старта. */
    private val outbox = Channel<Outgoing>(Channel.UNLIMITED)
    private var jobs: List<Job> = emptyList()

    val isRunning: Boolean get() = jobs.any { it.isActive }

    fun inviteLink(code: String): String? = username?.let { "https://t.me/$it?start=$code" }

    fun start() {
        if (isRunning) return
        jobs = listOf(scope.launch { pollLoop() }, scope.launch { sendLoop() })
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs = emptyList()
        _status.value = BotStatus.Stopped
    }

    // ─── Рассылки по инициативе ведущего ─────────────────────────────────────

    /** Каждому главе — личный отчёт и новая панель хода. */
    fun broadcastTurn() {
        val state = host.state ?: return
        for ((country, leader) in state.leaders) sendReport(state, country, leader.telegramId)
    }

    private fun sendReport(state: GameState, country: String, chatId: Long) {
        enqueue(Outgoing.Send(chatId, BotText.report(state, country)))
        sendPanel(state, country, chatId)
    }

    private fun sendPanel(state: GameState, country: String, chatId: Long) {
        val (text, keyboard) = BotText.panel(state, country)
        enqueue(Outgoing.Send(chatId, text, keyboard))
    }

    private fun enqueue(message: Outgoing) {
        outbox.trySend(message)
    }

    // ─── Приём ───────────────────────────────────────────────────────────────

    private suspend fun pollLoop() {
        var failures = 0
        _status.value = BotStatus.Connecting
        while (currentCoroutineContext().isActive) {
            try {
                if (username == null) {
                    username = api.getMe().username
                    api.deleteWebhook()
                    api.setCommands(
                        listOf(
                            "panel" to "Панель хода",
                            "report" to "Отчёт о положении дел",
                        )
                    )
                }
                _status.value = BotStatus.Online(username.orEmpty())
                failures = 0
                val updates = api.getUpdates(offset, POLL_TIMEOUT)
                for (update in updates) {
                    try {
                        handle(update)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Ошибка в одном сообщении не должна останавливать бота
                    }
                    offset = update.updateId + 1
                    onOffset(offset)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: TelegramException) {
                if (e.code == 401 || e.code == 404) {
                    _status.value = BotStatus.Failed("Неверный токен бота")
                    return
                }
                val reason = when (e.code) {
                    409 -> "Этот бот уже запущен на другом устройстве"
                    else -> e.message ?: "Ошибка Telegram"
                }
                failures = waitBeforeRetry(reason, failures, e.retryAfter)
            } catch (e: NetworkException) {
                failures = waitBeforeRetry("Нет связи с Telegram", failures)
            } catch (e: Exception) {
                failures = waitBeforeRetry(e.message ?: "Ошибка", failures)
            }
        }
    }

    private suspend fun waitBeforeRetry(reason: String, failures: Int, retryAfter: Int? = null): Int {
        val seconds = retryAfter ?: BACKOFF[failures.coerceAtMost(BACKOFF.lastIndex)]
        _status.value = BotStatus.Offline(reason, seconds)
        delay(seconds * 1000L)
        return failures + 1
    }

    // ─── Отправка ────────────────────────────────────────────────────────────

    private suspend fun sendLoop() {
        for (message in outbox) {
            var failures = 0
            while (true) {
                try {
                    api.send(message)
                    break
                } catch (e: CancellationException) {
                    throw e
                } catch (e: TelegramException) {
                    if (e.code == 429) {
                        delay((e.retryAfter ?: 1) * 1000L)
                        continue
                    }
                    // Остальные ошибки Telegram (сообщение не изменилось, игрок заблокировал бота…) — не повторяем
                    break
                } catch (e: Exception) {
                    delay(BACKOFF[failures.coerceAtMost(BACKOFF.lastIndex)] * 1000L)
                    failures++
                }
            }
        }
    }

    // ─── Обработка ───────────────────────────────────────────────────────────

    internal fun handle(update: TgUpdate) {
        update.message?.let(::handleMessage)
        update.callback?.let(::handleCallback)
    }

    private fun handleMessage(message: TgMessage) {
        if (message.chat.type != "private") return
        val user = message.from ?: return
        val text = message.text?.trim() ?: return
        val chatId = message.chat.id
        val state = host.state
        if (state == null) {
            enqueue(Outgoing.Send(chatId, "Сейчас нет активной партии. Дождитесь, пока ведущий начнёт игру."))
            return
        }

        val command = text.substringBefore(' ').substringBefore('@')
        val argument = text.substringAfter(' ', "").trim()
        if (command == "/start" && argument.isNotEmpty()) {
            bind(user, argument, chatId)
            return
        }

        val country = state.countryOfLeader(user.id)
        if (country == null) {
            enqueue(Outgoing.Send(chatId, "Вы пока не глава страны. Откройте ссылку-приглашение от ведущего."))
            return
        }
        when (command) {
            "/report" -> sendReport(state, country, chatId)
            else -> sendPanel(state, country, chatId)
        }
    }

    private fun bind(user: TgUser, code: String, chatId: Long) {
        val state = host.state ?: return
        val country = state.countryByInvite(code)
        if (country == null) {
            enqueue(Outgoing.Send(chatId, "Ссылка недействительна. Попросите у ведущего новую."))
            return
        }
        val current = state.leaders[country]
        if (current != null && current.telegramId != user.id) {
            enqueue(Outgoing.Send(chatId, "У страны ${country.html()} уже есть глава. Обратитесь к ведущему."))
            return
        }
        host.update { s ->
            // Один игрок — одна страна: старая привязка снимается
            val others = s.leaders.filterValues { it.telegramId != user.id }
            s.copy(leaders = others + (country to Leader(user.id, user.displayName)))
        }
        val updated = host.state ?: return
        enqueue(Outgoing.Send(chatId, "Вы — глава страны <b>${country.html()}</b>. Удачи!"))
        sendReport(updated, country, chatId)
    }

    private fun handleCallback(callback: TgCallback) {
        val state = host.state
        val message = callback.message
        val country = state?.countryOfLeader(callback.from.id)
        if (state == null || message == null || country == null) {
            enqueue(Outgoing.Answer(callback.id, "Вы не глава страны в текущей партии", alert = true))
            return
        }

        val parts = callback.data.orEmpty().split(':')
        val turn = parts.getOrNull(1)?.toIntOrNull()
        var view = PanelView.MAIN
        var notice: String? = null

        if (turn != state.turn || state.isOver) {
            notice = "Этот ход уже завершён — вот актуальная панель"
        } else {
            val me = state.country(country)!!
            fun ownCity(index: Int) = CityRef(country, me.cities[index].name)
            fun order(type: ActionType, target: CityRef? = null) = host.update {
                it.addAction(type, country, target).copy(ready = it.ready - country)
            }
            try {
                when (parts[0]) {
                    "u" -> order(ActionType.UPGRADE, ownCity(parts[2].toInt()))
                    "s" -> order(ActionType.SHIELD, ownCity(parts[2].toInt()))
                    "r" -> order(ActionType.RESEARCH)
                    "p" -> order(ActionType.PRODUCE)
                    "e" -> order(ActionType.ECOLOGY)
                    "l" -> {
                        val target = state.countries[parts[2].toInt()]
                        order(ActionType.LAUNCH, CityRef(target.name, target.cities[parts[3].toInt()].name))
                    }
                    "x" -> {
                        val id = parts[2].toInt()
                        if (state.pendingOf(country).none { it.id == id }) throw ActionError("Приказ уже отменён")
                        host.update { it.cancelAction(id).copy(ready = it.ready - country) }
                        if (host.state!!.pendingOf(country).isNotEmpty()) view = PanelView.QUEUE
                    }
                    "v" -> view = PanelView.entries[parts[2].toInt()]
                    "ok" -> host.update {
                        it.copy(ready = if (country in it.ready) it.ready - country else it.ready + country)
                    }
                }
            } catch (e: ActionError) {
                enqueue(Outgoing.Answer(callback.id, e.message, alert = true))
                return
            } catch (e: IndexOutOfBoundsException) {
                notice = "Кнопка устарела"
            } catch (e: NumberFormatException) {
                notice = "Кнопка устарела"
            }
        }

        enqueue(Outgoing.Answer(callback.id, notice))
        val (text, keyboard) = BotText.panel(host.state!!, country, view)
        enqueue(Outgoing.Edit(message.chat.id, message.messageId, text, keyboard))
    }
}
