@file:OptIn(ExperimentalCoroutinesApi::class)

package wd.bot

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import wd.core.ActionType
import wd.core.GameState
import wd.core.Leader
import wd.core.newGame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Поддельный Telegram: отвечает по сценарию и записывает запросы. */
private class FakeTelegram : HttpTransport {
    val requests = mutableListOf<Pair<String, JsonObject>>()
    var updates: suspend () -> String = { awaitCancellation() }
    var onSend: () -> HttpResult = { ok("{}") }
    var onGetMe: () -> HttpResult = { ok("""{"id":1,"first_name":"Bot","username":"wd_test_bot"}""") }

    override suspend fun postJson(url: String, body: String, timeoutSeconds: Int): HttpResult {
        val method = url.substringAfterLast('/')
        requests += method to Json.parseToJsonElement(body).jsonObject
        return when (method) {
            "getMe" -> onGetMe()
            "getUpdates" -> ok(updates())
            "deleteWebhook", "setMyCommands" -> ok("true")
            else -> onSend()
        }
    }

    fun sent(method: String) = requests.filter { it.first == method }.map { it.second }
    fun texts() = sent("sendMessage").map { it["text"]!!.jsonPrimitive.content }

    companion object {
        fun ok(result: String) = HttpResult(200, """{"ok":true,"result":$result}""")
    }
}

private class FakeHost(override var state: GameState?) : GameHost {
    override fun update(transform: (GameState) -> GameState) {
        state = transform(state!!)
    }
}

private fun message(userId: Long, text: String) = TgUpdate(
    updateId = 1,
    message = TgMessage(10, TgChat(userId, "private"), TgUser(userId, "Игрок$userId"), text),
)

private fun callback(userId: Long, data: String) = TgUpdate(
    updateId = 2,
    callback = TgCallback("cb", TgUser(userId, "Игрок$userId"), TgMessage(55, TgChat(userId, "private")), data),
)

class GameBotTest {
    private val telegram = FakeTelegram()
    private val host = FakeHost(newGame())

    private fun TestScope.startBot(): GameBot =
        GameBot(TelegramApi("TOKEN", telegram), host, backgroundScope).also {
            it.start()
            runCurrent()
        }

    @Test
    fun bindByInvite() = runTest {
        val bot = startBot()
        val code = host.state!!.invites.getValue("Россия")
        bot.handle(message(7, "/start $code"))
        runCurrent()

        assertEquals(Leader(7, "Игрок7"), host.state!!.leaders["Россия"])
        val texts = telegram.texts()
        assertTrue(texts[0].contains("глава страны"))
        assertTrue(texts[1].contains("Ваша страна — Россия"))
        assertTrue(texts[2].contains("Доступно: 1000$"))
        assertEquals("https://t.me/wd_test_bot?start=$code", bot.inviteLink(code))
    }

    @Test
    fun takenCountryAndBadCode() = runTest {
        host.state = host.state!!.copy(leaders = mapOf("Россия" to Leader(1, "А")))
        val bot = startBot()
        bot.handle(message(2, "/start ${host.state!!.invites.getValue("Россия")}"))
        bot.handle(message(2, "/start nonsense"))
        runCurrent()

        assertEquals(1, host.state!!.leaders.getValue("Россия").telegramId)
        assertTrue(telegram.texts()[0].contains("уже есть глава"))
        assertTrue(telegram.texts()[1].contains("недействительна"))
    }

    @Test
    fun ordersFromButtons() = runTest {
        host.state = host.state!!.copy(leaders = mapOf("Россия" to Leader(7, "А")), ready = setOf("Россия"))
        val bot = startBot()
        bot.handle(callback(7, "u:1:0"))
        bot.handle(callback(7, "l:1:1:2"))
        runCurrent()

        val s = host.state!!
        assertEquals(listOf(ActionType.UPGRADE, ActionType.LAUNCH), s.pending.map { it.type })
        assertEquals("Вашингтон", s.pending[1].target!!.city)
        assertTrue("Россия" !in s.ready, "новый приказ снимает отметку «готов»")
        val edit = telegram.sent("editMessageText").last()
        assertEquals(55, edit["message_id"]!!.jsonPrimitive.long)
        assertTrue(edit["text"]!!.jsonPrimitive.content.contains("Доступно: 850$"))
    }

    @Test
    fun cancelAndReady() = runTest {
        host.state = host.state!!.copy(leaders = mapOf("Россия" to Leader(7, "А")))
        val bot = startBot()
        bot.handle(callback(7, "r:1"))
        val id = host.state!!.pending.single().id
        bot.handle(callback(7, "x:1:$id"))
        bot.handle(callback(7, "ok:1"))
        runCurrent()

        assertTrue(host.state!!.pending.isEmpty())
        assertEquals(setOf("Россия"), host.state!!.ready)
    }

    @Test
    fun invalidOrderShowsAlert() = runTest {
        host.state = host.state!!.copy(leaders = mapOf("Россия" to Leader(7, "А")))
        val bot = startBot()
        bot.handle(callback(7, "p:1"))   // технологии нет
        runCurrent()

        val answer = telegram.sent("answerCallbackQuery").single()
        assertTrue(answer["text"]!!.jsonPrimitive.content.contains("Технология"))
        assertTrue(host.state!!.pending.isEmpty())
    }

    @Test
    fun staleTurnAndStranger() = runTest {
        host.state = host.state!!.copy(leaders = mapOf("Россия" to Leader(7, "А")), turn = 3)
        val bot = startBot()
        bot.handle(callback(7, "u:1:0"))
        bot.handle(callback(99, "u:3:0"))
        runCurrent()

        assertTrue(host.state!!.pending.isEmpty())
        val answers = telegram.sent("answerCallbackQuery").map { it["text"]!!.jsonPrimitive.content }
        assertTrue(answers[0].contains("завершён"))
        assertTrue(answers[1].contains("не глава"))
    }

    @Test
    fun recoversFromNetworkErrors() = runTest {
        var calls = 0
        telegram.updates = {
            calls++
            if (calls <= 2) throw NetworkException(Exception("offline"))
            awaitCancellation()
        }
        val bot = startBot()
        assertIs<BotStatus.Offline>(bot.status.value)
        advanceTimeBy(10_000)
        runCurrent()
        assertIs<BotStatus.Online>(bot.status.value)
        assertEquals(3, calls)
    }

    @Test
    fun outboxRetriesUntilDelivered() = runTest {
        host.state = host.state!!.copy(leaders = mapOf("Россия" to Leader(7, "А")))
        var attempts = 0
        telegram.onSend = {
            attempts++
            if (attempts == 1) throw NetworkException(Exception("offline"))
            FakeTelegram.ok("{}")
        }
        val bot = startBot()
        bot.broadcastTurn()
        advanceTimeBy(5_000)
        runCurrent()

        // 1-я попытка (отчёт) упала, затем отчёт повторён и отправлена панель
        assertEquals(3, attempts)
        val texts = telegram.texts()
        assertEquals(texts[0], texts[1])
        assertTrue(texts[2].contains("Доступно"))
    }

    @Test
    fun badTokenStops() = runTest {
        telegram.onGetMe = { HttpResult(401, """{"ok":false,"error_code":401,"description":"Unauthorized"}""") }
        val bot = startBot()
        runCurrent()
        assertIs<BotStatus.Failed>(bot.status.value)
    }

    @Test
    fun reportHidesForeignDetails() {
        val s = host.state!!.copy(leaders = mapOf("Россия" to Leader(7, "А")))
        val report = BotText.report(s, "Россия")
        assertTrue(report.contains("США: Лос-Анджелес, Нью-Йорк"))
        assertTrue(!report.contains("ур.0 🛡"))
        val (_, keyboard) = BotText.panel(s, "Россия")
        assertTrue(keyboard!!.rows.flatten().none { it.text.startsWith("💣") }, "без технологии нет кнопки бомбы")
    }
}
