@file:OptIn(ExperimentalCoroutinesApi::class, kotlin.io.encoding.ExperimentalEncodingApi::class)

package wd.bot

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import wd.core.ActionType
import wd.core.DevelopmentEntry
import wd.core.DevelopmentReport
import wd.core.GameSetup
import wd.core.GameState
import wd.core.Leader
import wd.core.Rules
import wd.core.WorldEventType
import wd.core.addAction
import wd.core.addTransfer
import wd.core.newGame
import wd.core.resolveTurn
import wd.core.triggerEvent
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
    /** Стартовых бомб нет: тестам ударов бомба выдаётся сразу. */
    private val host = FakeHost(newGame(GameSetup(rules = Rules(startBombs = 1))))

    private fun TestScope.startBot(miniApp: String? = null): GameBot =
        GameBot(TelegramApi("TOKEN", telegram), host, backgroundScope, miniAppUrl = miniApp).also {
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

    private fun webApp(userId: Long, data: String) = TgUpdate(
        updateId = 3,
        message = TgMessage(11, TgChat(userId, "private"), TgUser(userId, "Игрок$userId"), webAppData = TgWebAppData(data)),
    )

    @Test
    fun miniAppStateRoundTrip() {
        val s = host.state!!.addAction(ActionType.UPGRADE, "Россия", wd.core.CityRef("Россия", "Владивосток"))
            .addAction(ActionType.LAUNCH, "Россия", wd.core.CityRef("Китай", "Ухань"))
        val json = Json.parseToJsonElement(MiniApp.encodeState(s, "Россия")).jsonObject
        assertEquals("""[["u",1],["l",2,2]]""", json["q"].toString())
        assertEquals(4, json["o"]!!.jsonArray.size, "4 чужие страны")

        val url = MiniApp.url("https://example.org/app/", s, "Россия")
        assertTrue(url.startsWith("https://example.org/app/?s="))
        assertTrue(url.substringAfter("?s=").none { it in "+/=" }, "base64url без паддинга")
    }

    @Test
    fun ordersFromMiniAppReplaceQueue() = runTest {
        host.state = host.state!!.copy(leaders = mapOf("Россия" to Leader(7, "А")))
            .addAction(ActionType.ECOLOGY, "Россия")
        val bot = startBot("https://example.org/app/")
        // Улучшение Владивостока, щит Москве, запуск по Ухани и заведомо невозможная бомба
        bot.handle(webApp(7, """{"t":1,"q":[["u",1],["s",0],["l",2,2],["p"]],"r":true}"""))
        runCurrent()

        val s = host.state!!
        assertEquals(listOf(ActionType.UPGRADE, ActionType.SHIELD, ActionType.LAUNCH), s.pending.map { it.type })
        assertEquals("Ухань", s.pending[2].target!!.city)
        assertEquals(setOf("Россия"), s.ready)
        val confirm = telegram.sent("sendMessage").first()
        assertTrue(confirm["text"]!!.jsonPrimitive.content.contains("Приказы приняты: 3"))
        assertTrue(confirm["text"]!!.jsonPrimitive.content.contains("Технология"))
        val button = confirm["reply_markup"]!!.jsonObject["keyboard"]!!.jsonArray[0].jsonArray[0].jsonObject
        assertTrue(button["web_app"]!!.jsonObject["url"]!!.jsonPrimitive.content.startsWith("https://example.org/app/?s="))
    }

    @Test
    fun staleMiniAppOrdersRejected() = runTest {
        host.state = host.state!!.copy(leaders = mapOf("Россия" to Leader(7, "А")), turn = 2)
        val bot = startBot("https://example.org/app/")
        bot.handle(webApp(7, """{"t":1,"q":[["e"]],"r":false}"""))
        bot.handle(webApp(7, "мусор"))
        runCurrent()

        assertTrue(host.state!!.pending.isEmpty())
        assertTrue(telegram.texts()[0].contains("уже завершён"))
        assertTrue(telegram.texts().any { it.contains("Не удалось прочитать") })
    }

    @Test
    fun reportCarriesMiniAppButton() = runTest {
        host.state = host.state!!.copy(leaders = mapOf("Россия" to Leader(7, "А")))
        val bot = startBot("https://example.org/app/")
        bot.broadcastTurn()
        runCurrent()
        val report = telegram.sent("sendMessage").first()
        assertTrue(report["reply_markup"]!!.jsonObject.containsKey("keyboard"))
    }

    @Test
    fun reportHasCountryAndWorldSections() {
        val s = host.state!!
            .addAction(ActionType.UPGRADE, "Россия", wd.core.CityRef("Россия", "Москва"))
            .addAction(ActionType.UPGRADE, "США", wd.core.CityRef("США", "Чикаго"))
            .addAction(ActionType.LAUNCH, "Китай", wd.core.CityRef("США", "Нью-Йорк"))
            .addAction(ActionType.RESEARCH, "Китай")
            .resolveTurn()
        val report = BotText.report(s, "Россия")
        assertTrue(report.contains("<b>События в стране</b>"))
        assertTrue(report.contains("Москва: улучшен до ур.1"))
        assertTrue(report.contains("<b>События в мире</b>"))
        assertTrue(report.contains("Экология: 100% → ${s.ecology}% (−${100 - s.ecology})"))
        assertTrue(report.contains("Нью-Йорк (США): город уничтожен"))
        assertTrue(!report.contains("Чикаго: улучшен"), "чужие улучшения скрыты")
    }

    @Test
    fun developmentReportGoesToEveryoneOnItsTurn() = runTest {
        host.state = host.state!!.copy(leaders = mapOf("Россия" to Leader(7, "А"), "США" to Leader(8, "Б")))
        val bot = startBot()
        bot.broadcastTurn()
        runCurrent()
        assertTrue(telegram.texts().none { it.contains("Доклад") }, "на первом ходу доклада нет")

        host.state = host.state!!.resolveTurn().resolveTurn()
        bot.broadcastTurn()
        runCurrent()
        val reports = telegram.sent("sendMessage").filter { it["text"]!!.jsonPrimitive.content.contains("Доклад") }
        assertEquals(setOf(7L, 8L), reports.map { it["chat_id"]!!.jsonPrimitive.long }.toSet())
    }

    @Test
    fun developmentTextShowsChange() {
        val text = BotText.development(DevelopmentReport(5, listOf(DevelopmentEntry("А", 100, 25), DevelopmentEntry("Б", 50, -25), DevelopmentEntry("В", 25))))
        assertTrue(text.contains("1. А — 100 (+25)"))
        assertTrue(text.contains("2. Б — 50 (−25)"))
        assertTrue(text.contains("3. В — 25\n") || text.endsWith("3. В — 25"))
    }

    @Test
    fun eventIsAnnouncedWithFreshPrices() = runTest {
        host.state = host.state!!.copy(leaders = mapOf("Россия" to Leader(7, "А")))
        val bot = startBot("https://example.org/app/")
        host.state = host.state!!.triggerEvent(WorldEventType.SCIENCE_BREAKTHROUGH)
        bot.broadcastEvent()
        runCurrent()

        val sent = telegram.sent("sendMessage")
        assertTrue(sent[0]["text"]!!.jsonPrimitive.content.contains("Научный прорыв"))
        assertTrue(sent[0]["reply_markup"]!!.jsonObject.containsKey("keyboard"), "новая кнопка мини-приложения со скидкой")
        val buttons = sent[1]["reply_markup"].toString()
        assertTrue(buttons.contains("Исследование 375$"))

        val state = Json.parseToJsonElement(MiniApp.encodeState(host.state!!, "Россия")).jsonObject
        assertEquals(375, state["cost"]!!.jsonArray[2].jsonPrimitive.content.toInt())
        assertTrue(state["ev"]!!.jsonPrimitive.content.contains("Научный прорыв"))
    }

    @Test
    fun miniAppCarriesCompactReport() {
        val s = host.state!!
            .addAction(ActionType.UPGRADE, "Россия", wd.core.CityRef("Россия", "Владивосток"))
            .addAction(ActionType.LAUNCH, "Китай", wd.core.CityRef("США", "Нью-Йорк"))
            .addAction(ActionType.LAUNCH, "Россия", wd.core.CityRef("Китай", "Ухань"))
            .resolveTurn().resolveTurn()
        val json = Json.parseToJsonElement(MiniApp.encodeState(s, "Россия")).jsonObject
        assertEquals(s.ecology, json["ep"]!!.jsonPrimitive.content.toInt())
        assertEquals(3, json["dt"]!!.jsonPrimitive.content.toInt())
        assertEquals(5, json["dv"]!!.jsonArray.size)
        assertTrue(json["rc"]!!.jsonArray.any { it.jsonArray[0].jsonPrimitive.content == "in" })

        val first = s.copy(log = s.log.dropLast(1))
        val before = Json.parseToJsonElement(MiniApp.encodeState(first, "Россия")).jsonObject
        val codes = before["rc"]!!.jsonArray.map { it.jsonArray[0].jsonPrimitive.content }
        assertTrue("up" in codes && "ld" in codes)
        assertTrue(before["rw"]!!.jsonArray.any { it.toString() == """["cd",1,1]""" })
    }

    @Test
    fun longUrlDropsReport() {
        val long = "https://example.org/" + "x".repeat(2000)
        val url = MiniApp.url(long, host.state!!.resolveTurn().resolveTurn(), "Россия")
        val payload = url.substringAfter("?s=")
        val decoded = kotlin.io.encoding.Base64.UrlSafe
            .withPadding(kotlin.io.encoding.Base64.PaddingOption.ABSENT).decode(payload).decodeToString()
        assertTrue(!decoded.contains("\"rc\""))
    }

    private fun leaderRussia() {
        host.state = host.state!!.copy(leaders = mapOf("Россия" to Leader(7, "А")))
    }

    @Test
    fun transferFromChat() = runTest {
        leaderRussia()
        val bot = startBot()
        val (_, panel) = BotText.panel(host.state!!, "Россия")
        assertTrue(panel!!.rows.flatten().any { it.text == "💸 Перевод" })
        val (_, targets) = BotText.panel(host.state!!, "Россия", PanelView.TRANSFER)
        assertEquals(listOf("США", "Китай", "Израиль", "Германия"), targets!!.rows.flatten().map { it.text }.dropLast(1))

        bot.handle(callback(7, "tr:1:2"))
        bot.handle(message(7, "много"))
        runCurrent()
        assertTrue(host.state!!.pending.isEmpty())
        assertTrue(telegram.texts().last().contains("целым числом"))

        bot.handle(message(7, "5000"))
        runCurrent()
        assertTrue(host.state!!.pending.isEmpty())
        assertTrue(telegram.texts().last().contains("Недостаточно средств"))

        bot.handle(message(7, "300"))
        runCurrent()
        val action = host.state!!.pending.single()
        assertEquals("Китай" to 300, action.toCountry to action.amount)
        assertEquals(700, host.state!!.availableBalance("Россия"))
        assertTrue(telegram.texts().last().contains("Перевод → Китай: 300$"))

        // Следующее число — уже не сумма
        bot.handle(message(7, "100"))
        runCurrent()
        assertEquals(1, host.state!!.pending.size)
    }

    @Test
    fun transferDraftCancelledByButtonOrCommandOrNewTurn() = runTest {
        leaderRussia()
        val bot = startBot()
        bot.handle(callback(7, "tr:1:2"))
        bot.handle(callback(7, "v:1:0"))
        bot.handle(message(7, "100"))
        bot.handle(callback(7, "tr:1:2"))
        bot.handle(message(7, "/panel"))
        bot.handle(message(7, "100"))
        runCurrent()
        assertTrue(host.state!!.pending.isEmpty())

        bot.handle(callback(7, "tr:1:2"))
        host.state = host.state!!.resolveTurn()
        bot.handle(message(7, "100"))
        runCurrent()
        assertTrue(host.state!!.pending.isEmpty())
        assertTrue(telegram.texts().last().contains("уже завершён"))
    }

    @Test
    fun transferDisabledHidesButtonAndRejects() = runTest {
        host.state = newGame(GameSetup(rules = Rules(transfersEnabled = false)))
            .copy(leaders = mapOf("Россия" to Leader(7, "А")))
        val (_, panel) = BotText.panel(host.state!!, "Россия")
        assertTrue(panel!!.rows.flatten().none { it.text == "💸 Перевод" })
        val bot = startBot("https://example.org/app/")
        bot.handle(callback(7, "tr:1:2"))
        bot.handle(message(7, "100"))
        bot.handle(webApp(7, """{"t":1,"q":[["t",2,100]],"r":false}"""))
        runCurrent()
        assertTrue(host.state!!.pending.isEmpty())
        val state = Json.parseToJsonElement(MiniApp.encodeState(host.state!!, "Россия")).jsonObject
        assertEquals("0", state["tr"]!!.jsonPrimitive.content)
    }

    @Test
    fun transferFromMiniApp() = runTest {
        leaderRussia()
        val bot = startBot("https://example.org/app/")
        bot.handle(webApp(7, """{"t":1,"q":[["t",2,300],["t",1,5000],["t",0,5]],"r":false}"""))
        runCurrent()
        val action = host.state!!.pending.single()
        assertEquals("Китай" to 300, action.toCountry to action.amount)
        val text = telegram.texts().first()
        assertTrue(text.contains("Приказы приняты: 1") && text.contains("Недостаточно средств") && text.contains("самому себе"))

        // Очередь уходит в мини-приложение и возвращается без потерь
        val state = Json.parseToJsonElement(MiniApp.encodeState(host.state!!, "Россия")).jsonObject
        assertEquals("""[["t",2,300]]""", state["q"].toString())
        assertEquals(MiniOrder(ActionType.TRANSFER, 2, amount = 300), MiniApp.parseOrders("""{"t":1,"q":[["t",2,300]]}""").orders.single())
    }

    @Test
    fun transferInReportAndMiniAppReport() {
        val s = host.state!!.addTransfer("Россия", "Китай", 100).resolveTurn()
        assertTrue(BotText.report(s, "Россия").contains("Перевод в Китай: −100$"))
        assertTrue(BotText.report(s, "Китай").contains("Перевод от Россия: +100$"))
        assertTrue(!BotText.report(s, "США").contains("Перевод"))
        val sent = Json.parseToJsonElement(MiniApp.encodeState(s, "Россия")).jsonObject["rc"]!!.jsonArray
        assertTrue(sent.any { it.toString() == """["ms",2,100]""" })
        val received = Json.parseToJsonElement(MiniApp.encodeState(s, "Китай")).jsonObject["rc"]!!.jsonArray
        assertTrue(received.any { it.toString() == """["mr",0,100]""" })
    }
}
