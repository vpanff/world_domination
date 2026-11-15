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
import wd.core.addTransfer
import wd.core.cancelAction
import wd.core.describe
import wd.core.icon
import wd.core.validateTransfer
import wd.core.transferNow
import wd.core.MoneyReceived
import wd.core.short

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

/** Текущее время ЧЧ:ММ:СС для журнала. */
expect fun timestamp(): String

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
    /** Журнал для консоли ведущего: строки уже с временем. */
    private val onLog: (String) -> Unit = {},
    /** Адрес мини-приложения (GitHub Pages); null — только кнопки в чате. */
    private val miniAppUrl: String? = null,
) {
    private fun log(message: String) = onLog("${timestamp()}  $message")

    private val _status = MutableStateFlow<BotStatus>(BotStatus.Stopped)
    val status: StateFlow<BotStatus> = _status

    var username: String? = null
        private set

    /** Кто сейчас вводит сумму перевода: игрок → получатель и ход. */
    private data class TransferDraft(val to: String, val turn: Int, val gameId: String)
    private data class AmountDraft(val type: ActionType, val target: CityRef?, val turn: Int, val gameId: String)
    private val transferDrafts = mutableMapOf<Long, TransferDraft>()
    private val amountDrafts = mutableMapOf<Long, AmountDraft>()

    /** Очередь переживает перезапуск бота: что не успело уйти, уйдёт после старта. */
    private val outbox = Channel<Outgoing>(Channel.UNLIMITED)
    private var jobs: List<Job> = emptyList()

    val isRunning: Boolean get() = jobs.any { it.isActive }

    fun inviteLink(code: String): String? = username?.let { "https://t.me/$it?start=$code" }

    fun start() {
        if (isRunning) return
        log("Запуск бота")
        jobs = listOf(scope.launch { pollLoop() }, scope.launch { sendLoop() })
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs = emptyList()
        if (_status.value != BotStatus.Stopped) log("Бот остановлен")
        _status.value = BotStatus.Stopped
    }

    // ─── Рассылки по инициативе ведущего ─────────────────────────────────────

    /** Каждому главе — личный отчёт, доклад о развитии (если он вышел в этом ходу) и новая панель хода. */
    fun broadcastTurn() {
        val state = host.state ?: return
        for ((country, leader) in state.leaders) sendReport(state, country, leader.telegramId, withDevelopment = true)
    }

    /** Ивент выпал или ведущий его отменил: цены изменились, поэтому панель и кнопка мини-приложения — свежие. */
    fun broadcastEvent() {
        val state = host.state ?: return
        val event = state.activeEvent
        val text = if (event != null) "${event.icon()} <b>Мировое событие</b>\n${event.describe().html()}"
        else "↩ Ведущий отменил мировое событие этого хода."
        for ((country, leader) in state.leaders) {
            enqueue(Outgoing.Send(leader.telegramId, text, reply = miniAppKeyboard(state, country)))
            if (canOrder(state, country)) sendPanel(state, country, leader.telegramId)
        }
    }

    fun broadcastTransfers(old: GameState, new: GameState) {
        if (!new.isHard || old.gameId != new.gameId || new.liveEvents.size <= old.liveEvents.size) return
        for (event in new.liveEvents.drop(old.liveEvents.size).filterIsInstance<MoneyReceived>()) {
            for (country in listOf(event.from, event.to)) {
                val chatId = new.leaders[country]?.telegramId ?: continue
                enqueue(Outgoing.Send(chatId, "💸 ${event.from.html()} → ${event.to.html()}: ${event.amount}$, перевод выполнен.", reply = miniAppKeyboard(new, country)))
                sendPanel(new, country, chatId)
            }
        }
    }

    private fun sendReport(state: GameState, country: String, chatId: Long, withDevelopment: Boolean = false) {
        enqueue(Outgoing.Send(chatId, BotText.report(state, country), reply = miniAppKeyboard(state, country), removeReply = !canOrder(state, country)))
        val development = state.developmentReport?.takeIf { withDevelopment && it.turn == state.turn }
        development?.let { enqueue(Outgoing.Send(chatId, BotText.development(it))) }
        sendPanel(state, country, chatId)
    }

    private fun canOrder(state: GameState, country: String) = !state.isOver && (state.isHard || state.country(country)?.isAlive == true)

    /** Кнопка мини-приложения со свежим состоянием страны. */
    private fun miniAppKeyboard(state: GameState, country: String): ReplyKeyboard? {
        val base = miniAppUrl?.takeIf { it.isNotBlank() } ?: return null
        if (!canOrder(state, country)) return null
        val url = MiniApp.url(base, state, country)
        return ReplyKeyboard(listOf(listOf(KeyboardButton("🗺 Приказы на ход ${state.turn}", WebAppInfo(url)))))
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
                    log("Подключено как @$username")
                    api.deleteWebhook()
                    api.setCommands(
                        listOf(
                            "panel" to "Панель хода",
                            "report" to "Отчёт о положении дел",
                        )
                    )
                }
                if (_status.value !is BotStatus.Online) log("Связь с Telegram есть, жду сообщений")
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
                        log("Ошибка при обработке: ${e::class.simpleName}: ${e.message}")
                    }
                    offset = update.updateId + 1
                    onOffset(offset)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: TelegramException) {
                if (e.code == 401 || e.code == 404) {
                    log("Telegram отклонил токен (${e.code}): ${e.message}")
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
        log("$reason — повтор через $seconds с")
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
                    if (message !is Outgoing.Answer) log("→ ${message.describe()}")
                    break
                } catch (e: CancellationException) {
                    throw e
                } catch (e: TelegramException) {
                    if (e.code == 429) {
                        log("Лимит Telegram, жду ${e.retryAfter ?: 1} с")
                        delay((e.retryAfter ?: 1) * 1000L)
                        continue
                    }
                    // Остальные ошибки Telegram (сообщение не изменилось, игрок заблокировал бота…) — не повторяем
                    if (e.message?.contains("not modified") != true) log("Не отправлено (${message.describe()}): ${e.message}")
                    break
                } catch (e: Exception) {
                    if (failures == 0) log("Нет связи при отправке (${message.describe()}), повторяю")
                    delay(BACKOFF[failures.coerceAtMost(BACKOFF.lastIndex)] * 1000L)
                    failures++
                }
            }
        }
    }

    // ─── Обработка ───────────────────────────────────────────────────────────

    internal fun handle(update: TgUpdate) {
        update.message?.let { m -> log("← ${m.from?.displayName} (${m.from?.id}): ${m.text ?: "[не текст]"}") }
        update.callback?.let { c -> log("← ${c.from.displayName} (${c.from.id}): кнопка ${c.data}") }
        update.message?.let(::handleMessage)
        update.callback?.let(::handleCallback)
    }

    private fun handleMessage(message: TgMessage) {
        if (message.chat.type != "private") return
        val user = message.from ?: return
        message.webAppData?.let {
            handleOrders(user, it.data, message.chat.id)
            return
        }
        val text = message.text?.trim() ?: return
        val chatId = message.chat.id
        val state = host.state
        if (state == null) {
            enqueue(Outgoing.Send(chatId, "Сейчас нет активной партии. Дождитесь, пока ведущий начнёт игру."))
            return
        }

        if (user.id in amountDrafts) {
            val draft = amountDrafts.remove(user.id)!!
            if (!text.startsWith("/")) {
                val country = state.countryOfLeader(user.id)
                val amount = text.removeSuffix("$").trim().toIntOrNull()
                val error = runCatching {
                    if (country == null || state.turn != draft.turn || state.gameId != draft.gameId) throw ActionError("Приказ устарел")
                    if (amount == null) throw ActionError("Введите целую положительную сумму")
                    host.update { it.addAction(draft.type, country, draft.target, amount).copy(ready = it.ready - country) }
                }.exceptionOrNull()
                if (error != null) enqueue(Outgoing.Send(chatId, error.message.orEmpty().html()))
                if (country != null) sendPanel(host.state!!, country, chatId)
                return
            }
        }
        // Команда отменяет ввод суммы, любой другой текст считается суммой
        if (user.id in transferDrafts) {
            if (!text.startsWith("/")) {
                handleTransferAmount(user, text, chatId)
                return
            }
            transferDrafts.remove(user.id)
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
        log("Привязка: ${user.displayName} (${user.id}) → $country")
        host.update { s ->
            // Один игрок — одна страна: старая привязка снимается
            val others = s.leaders.filterValues { it.telegramId != user.id }
            s.copy(leaders = others + (country to Leader(user.id, user.displayName)))
        }
        val updated = host.state ?: return
        enqueue(Outgoing.Send(chatId, "Вы — глава страны <b>${country.html()}</b>. Удачи!"))
        sendReport(updated, country, chatId)
    }

    private fun handleTransferAmount(user: TgUser, text: String, chatId: Long) {
        val state = host.state ?: return
        val draft = transferDrafts.getValue(user.id)
        val (to, turn) = draft
        val country = state.countryOfLeader(user.id)
        if (country == null || turn != state.turn || draft.gameId != state.gameId || !canOrder(state, country)) {
            transferDrafts.remove(user.id)
            enqueue(Outgoing.Send(chatId, "Этот ход уже завершён — перевод не принят."))
            return
        }
        val amount = text.removeSuffix("$").replace(" ", "").toIntOrNull()
        if (amount == null) {
            enqueue(Outgoing.Send(chatId, "Отправьте сумму целым числом, например 100."))
            return
        }
        try {
            host.update { it.addTransfer(country, to, amount).copy(ready = it.ready - country) }
        } catch (e: ActionError) {
            log("Отклонено для $country: ${e.message}")
            enqueue(Outgoing.Send(chatId, "${e.message.orEmpty().html()}. Отправьте другую сумму или вернитесь к панели через /panel."))
            return
        }
        transferDrafts.remove(user.id)
        sendPanel(host.state!!, country, chatId)
    }

    /** Приказы из мини-приложения заменяют все приказы страны на этот ход. */
    private fun handleOrders(user: TgUser, data: String, chatId: Long) {
        val state = host.state ?: return
        val country = state.countryOfLeader(user.id)
        if (country == null) {
            enqueue(Outgoing.Send(chatId, "Вы не глава страны в текущей партии."))
            return
        }
        val parsed = runCatching { MiniApp.parseOrders(data) }.getOrElse {
            log("Не разобраны приказы от $country: ${it.message}")
            enqueue(Outgoing.Send(chatId, "Не удалось прочитать приказы. Откройте приказы заново."))
            return
        }
        if ((state.isHard && parsed.gameId != state.gameId) || (parsed.gameId != null && parsed.gameId != state.gameId)) {
            enqueue(Outgoing.Send(chatId, "Это мини-приложение другой партии. Откройте /panel."))
            return
        }
        if (parsed.turn != state.turn || !canOrder(state, country)) {
            enqueue(Outgoing.Send(chatId, "Ход ${parsed.turn} уже завершён — приказы не приняты."))
            sendReport(state, country, chatId)
            return
        }

        if (parsed.transfer != null) {
            val transfer = parsed.transfer
            val error = runCatching {
                if (!state.isHard) throw ActionError("Мгновенные переводы доступны только в хард-режиме")
                val to = state.countries.getOrNull(transfer.countryIndex)?.name ?: throw ActionError("Получатель не найден")
                host.update { it.transferNow(country, to, transfer.amount, transfer.requestId) }
            }.exceptionOrNull()
            enqueue(Outgoing.Send(chatId, error?.message?.html() ?: "Перевод выполнен.", reply = miniAppKeyboard(host.state!!, country)))
            sendPanel(host.state!!, country, chatId)
            return
        }
        val errors = mutableListOf<String>()
        host.update { s ->
            var next = s.copy(pending = s.pending.filterNot { it.country == country })
            // Производство перед запуском, независимо от порядка элементов в форме.
            val orders = if (s.isHard) parsed.orders.sortedBy { it.type.priority } else parsed.orders
            for (order in orders) {
                try {
                    if (order.type == ActionType.TRANSFER) {
                        if (s.isHard) throw ActionError("Отправляйте мгновенный перевод отдельно от очереди")
                        next = next.addTransfer(country, next.countries[order.countryIndex!!].name, order.amount!!)
                        continue
                    }
                    val target = when (order.type) {
                        ActionType.UPGRADE, ActionType.SHIELD, ActionType.INVEST, ActionType.REBUILD ->
                            CityRef(country, next.country(country)!!.cities[order.cityIndex!!].name)
                        ActionType.LAUNCH -> next.countries[order.countryIndex!!]
                            .let { CityRef(it.name, it.cities[order.cityIndex!!].name) }
                        else -> null
                    }
                    next = next.addAction(order.type, country, target, amount = order.amount ?: 100,
                        toCountry = if (order.type == ActionType.SANCTION) next.countries[order.countryIndex!!].name else null)
                } catch (e: ActionError) {
                    errors += e.message.orEmpty()
                } catch (e: IndexOutOfBoundsException) {
                    errors += "неизвестный город"
                }
            }
            next.copy(ready = if (parsed.ready && (!s.isHard || errors.isEmpty())) next.ready + country else next.ready - country)
        }
        val updated = host.state!!
        val accepted = updated.pendingOf(country).size
        log("Приказы из мини-приложения от $country: принято $accepted, отклонено ${errors.size}")

        val text = buildString {
            append("✅ Приказы приняты: $accepted")
            if (country in updated.ready) append(" · вы готовы")
            if (errors.isNotEmpty()) {
                appendLine()
                appendLine("Не приняты:")
                errors.forEach { appendLine("• ${it.html()}") }
            }
        }
        enqueue(Outgoing.Send(chatId, text.trimEnd(), reply = miniAppKeyboard(updated, country)))
        sendPanel(updated, country, chatId)
    }

    private fun handleCallback(callback: TgCallback) {
        transferDrafts.remove(callback.from.id)
        amountDrafts.remove(callback.from.id)
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

        val gameToken = parts.lastOrNull()?.takeIf { it.startsWith("g") }
        if ((state.isHard && gameToken != "g" + state.gameId.take(8)) || (gameToken != null && gameToken != "g" + state.gameId.take(8))) {
            enqueue(Outgoing.Answer(callback.id, "Кнопка другой партии. Откройте /panel", alert = true))
            return
        }
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
                    "iv", "he" -> {
                        if (!state.isHard) throw ActionError("Только для хард-режима")
                        val type = if (parts[0] == "iv") ActionType.INVEST else ActionType.ECOLOGY
                        val target = if (type == ActionType.INVEST) ownCity(parts[2].toInt()) else null
                        amountDrafts[callback.from.id] = AmountDraft(type, target, state.turn, state.gameId)
                        enqueue(Outgoing.Answer(callback.id))
                        enqueue(Outgoing.Send(message.chat.id, "Отправьте целую сумму ${if (target == null) "на экологию" else "для ${target.city.html()}"}. Доступно ${state.availableBalance(country)}$. /panel — отмена."))
                        return
                    }
                    "rb" -> order(ActionType.REBUILD, ownCity(parts[2].toInt()))
                    "sn" -> host.update { it.addAction(ActionType.SANCTION, country, toCountry = state.countries[parts[2].toInt()].name).copy(ready = it.ready - country) }
                    "u" -> order(ActionType.UPGRADE, ownCity(parts[2].toInt()))
                    "s" -> order(ActionType.SHIELD, ownCity(parts[2].toInt()))
                    "r" -> order(ActionType.RESEARCH)
                    "p" -> order(ActionType.PRODUCE)
                    "e" -> order(ActionType.ECOLOGY)
                    "l", "lc" -> {
                        val target = state.countries[parts[2].toInt()]
                        val ref = CityRef(target.name, target.cities[parts[3].toInt()].name)
                        if (target.name == country && parts[0] == "l") {
                            enqueue(Outgoing.Answer(callback.id))
                            enqueue(Outgoing.Edit(message.chat.id, message.messageId,
                                "Вы уверены, что хотите себя бомбить?\nЦель: ${ref.city.html()}",
                                Keyboard(listOf(listOf(
                                    Button("Да", "lc:${state.turn}:${parts[2]}:${parts[3]}"),
                                    Button("Нет", "v:${state.turn}:${PanelView.LAUNCH.ordinal}"),
                                ))).forGame(state)))
                            return
                        }
                        order(ActionType.LAUNCH, ref)
                    }
                    "x" -> {
                        val id = parts[2].toInt()
                        if (state.pendingOf(country).none { it.id == id }) throw ActionError("Приказ уже отменён")
                        host.update { it.cancelAction(id).copy(ready = it.ready - country) }
                        if (host.state!!.pendingOf(country).isNotEmpty()) view = PanelView.QUEUE
                    }
                    "tr" -> {
                        val to = state.countries[parts[2].toInt()].name
                        state.validateTransfer(country, to, 1)
                        transferDrafts[callback.from.id] = TransferDraft(to, state.turn, state.gameId)
                        enqueue(Outgoing.Answer(callback.id))
                        val (text, keyboard) = BotText.transferPrompt(state, country, to)
                        enqueue(Outgoing.Edit(message.chat.id, message.messageId, text, keyboard))
                        return
                    }
                    "v" -> view = PanelView.entries[parts[2].toInt()]
                    "ok" -> host.update {
                        it.copy(ready = if (country in it.ready) it.ready - country else it.ready + country)
                    }
                }
            } catch (e: ActionError) {
                log("Отклонено для $country: ${e.message}")
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
