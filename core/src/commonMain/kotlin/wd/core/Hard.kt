package wd.core

import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

@Serializable
enum class GameMode { CLASSIC, HARD }

/** Механики worldomination.app с балансом для пяти стран. */
@Serializable
data class HardRules(
    val rounds: Int = 6,
    val startBalance: Int = 1000,
    val startLife: Double = 80.0,
    val startDevelopment: Double = 80.0,
    val startEcology: Double = 100.0,
    val incomeLifeWeight: Double = 0.3,
    val incomeDevelopmentWeight: Double = 0.7,
    val incomeScale: Double = 3.2,
    val lifePer100: Double = 5.75,
    val developmentPer100: Double = 6.0,
    val investmentFalloff: Double = 0.4,
    val techCost: Int = 450,
    val bombCost: Int = 550,
    val shieldCost: Int = 350,
    val shieldHitLifeLoss: Double = 19.0,
    val techLifePenalty: Double = 6.0,
    val bombBuildLifePenalty: Double = 6.0,
    val techEcologyLoss: Double = 3.6,
    val bombBuildEcologyLoss: Double = 0.0,
    val bombLaunchEcologyLoss: Double = 4.5,
    val ecologyThreshold: Double = 60.0,
    val ecologyLifeLossPerPoint: Double = 0.45,
    val ecologyPer100: Double = 7.2,
    val ecologyIncomeBonus: Double = 40.0,
    val ecologyIncomePenaltyPerPoint: Double = 2.0,
    val sanctionPercent: Double = 12.0,
    val sanctionCapPercent: Double = 30.0,
    val rebuildCost: Int = 850,
    val rebuildLife: Double = 25.0,
    val rebuildDevelopment: Double = 30.0,
    val rebuildShield: Boolean = true,
    val rebuildDiscountWhenEliminated: Double = 50.0,
    val unAid: Int = 450,
    val maxBombsBuiltPerRound: Int = 3,
    val launchLimit: Boolean = false,
    val maxLaunchesPerRound: Int = 3,
    val finalRoundStrikes: Boolean = true,
    val bombUpkeep: Int = 50,
    val developmentDecay: Double = 0.0,
    val wealthTaxPer10: Double = 0.0,
    val wealthTaxCapPercent: Double = 30.0,
) {
    fun cost(type: ActionType): Int = when (type) {
        ActionType.RESEARCH -> techCost
        ActionType.PRODUCE -> bombCost
        ActionType.SHIELD -> shieldCost
        ActionType.REBUILD -> rebuildCost
        ActionType.INVEST, ActionType.ECOLOGY -> 100
        else -> 0
    }

    fun validate() {
        require(rounds in 1..100) { "Число раундов: 1–100" }
        require(listOf(startBalance, techCost, bombCost, shieldCost, rebuildCost, unAid, bombUpkeep).all { it in 0..1_000_000 }) { "Цены и ресурсы: 0–1000000" }
        require(listOf(startLife, startDevelopment, startEcology, shieldHitLifeLoss, techLifePenalty, bombBuildLifePenalty, techEcologyLoss, bombBuildEcologyLoss, bombLaunchEcologyLoss, ecologyThreshold, sanctionPercent, sanctionCapPercent, rebuildLife, rebuildDevelopment, rebuildDiscountWhenEliminated, developmentDecay, wealthTaxCapPercent).all { it.isFinite() && it in 0.0..100.0 }) { "Процентные показатели: 0–100" }
        require(listOf(incomeLifeWeight, incomeDevelopmentWeight, incomeScale, lifePer100, developmentPer100, ecologyLifeLossPerPoint, ecologyPer100, ecologyIncomeBonus, ecologyIncomePenaltyPerPoint, wealthTaxPer10).all { it.isFinite() && it in 0.0..100.0 }) { "Коэффициенты должны быть от 0 до 100" }
        require(investmentFalloff.isFinite() && investmentFalloff in 0.0..0.95) { "Убывающая отдача: 0–0.95" }
        require(maxBombsBuiltPerRound in 0..1000 && maxLaunchesPerRound in 0..1000) { "Лимиты: 0–1000" }
    }
}

val HARD_COUNTRIES = listOf(
    CountrySetup("Великобритания", listOf("Лондон", "Манчестер", "Бирмингем", "Ливерпуль")),
    CountrySetup("Франция", listOf("Париж", "Лион", "Марсель", "Тулуза")),
    CountrySetup("Германия", listOf("Берлин", "Мюнхен", "Гамбург", "Кёльн")),
    CountrySetup("Италия", listOf("Рим", "Милан", "Неаполь", "Турин")),
    CountrySetup("Испания", listOf("Мадрид", "Барселона", "Валенсия", "Севилья")),

)

fun newHardGame(rules: HardRules = HardRules(), countries: List<CountrySetup> = HARD_COUNTRIES): GameState {
    require(countries.size == 5 && countries.all { it.cities.size == 4 }) { "В хард-режиме нужно ровно 5 стран по 4 города" }
    validateCountryNames(countries)
    rules.validate()
    return GameState(
        rules = Rules(eventsEnabled = false), mode = GameMode.HARD, hardRules = rules,
        ecology = rules.startEcology.roundToInt(), hardEcology = rules.startEcology,
        gameId = inviteCode() + inviteCode(),
        countries = countries.map { c -> Country(c.name.trim(), c.cities.map {
            City(it.trim(), life = rules.startLife, development = rules.startDevelopment)
        }, balance = rules.startBalance, bombs = 0) },
    ).withInvites()
}

fun Double.short(): String = (this * 10).roundToInt().let { if (it % 10 == 0) "${it / 10}" else "${it / 10}.${kotlin.math.abs(it % 10)}" }
fun Country.averageLife(): Double = cities.sumOf { if (it.alive) it.life else 0.0 } / cities.size.coerceAtLeast(1)
fun Country.averageDevelopment(): Double = cities.sumOf { if (it.alive) it.development else 0.0 } / cities.size.coerceAtLeast(1)
fun GameState.rebuildCost(country: String): Int = (hardRules.rebuildCost *
    (if (country(country)?.isAlive == false) 1 - hardRules.rebuildDiscountWhenEliminated / 100 else 1.0)).roundToInt()

/** Доход-прогноз не учитывает скрытые приказы других стран. В расчёте передаём санкции. */
fun GameState.hardIncome(country: Country, sanctions: Int = 0): Int {
    val r = hardRules
    val base = country.cities.filter { it.alive }.sumOf { ((r.incomeLifeWeight * it.life + r.incomeDevelopmentWeight * it.development) * r.incomeScale).roundToInt() }
    val bonus = 1 + r.ecologyIncomeBonus / 100 * hardEcology / 100
    val penalty = (1 - (r.ecologyThreshold - hardEcology).coerceAtLeast(0.0) * r.ecologyIncomePenaltyPerPoint / 100).coerceAtLeast(0.0)
    val sanctionsFactor = 1 - (sanctions * r.sanctionPercent).coerceAtMost(r.sanctionCapPercent) / 100
    val worldDevelopment = countries.map { it.averageDevelopment() }.average()
    val gap = (country.averageDevelopment() - worldDevelopment).coerceAtLeast(0.0)
    val taxFactor = 1 - ((gap / 10).toInt() * r.wealthTaxPer10).coerceAtMost(r.wealthTaxCapPercent) / 100
    return (base * bonus * penalty * sanctionsFactor * taxFactor).roundToInt().coerceAtLeast(0)
}

fun GameState.validateHard(type: ActionType, name: String, target: CityRef?, amount: Int, to: String?) {
    if (isOver) throw ActionError("Игра окончена")
    val c = country(name) ?: throw ActionError("Страна не найдена")
    val queued = pendingOf(name)
    if (!c.isAlive && type !in listOf(ActionType.REBUILD, ActionType.TRANSFER)) throw ActionError("Нет живых городов: доступны восстановление и переводы")
    if (type == ActionType.UPGRADE) throw ActionError("В хард-режиме используйте инвестиции")
    val price = when(type) {
        ActionType.INVEST, ActionType.ECOLOGY -> amount
        ActionType.REBUILD -> rebuildCost(name)
        else -> cost(type)
    }
    if (type in listOf(ActionType.INVEST, ActionType.ECOLOGY) && amount !in 1..1_000_000) throw ActionError("Сумма: от 1 до 1000000")
    if (availableBalance(name) < price) throw ActionError("Недостаточно средств (нужно $price$, доступно ${availableBalance(name)}$)")
    when(type) {
        ActionType.SHIELD, ActionType.INVEST, ActionType.REBUILD -> {
            val ref = target ?: throw ActionError("Не выбран город")
            if (ref.country != name) throw ActionError("Это не ваш город")
            val city = city(ref) ?: throw ActionError("Город не найден")
            if (type == ActionType.REBUILD) {
                if (city.alive || queued.any { it.type == type && it.target == ref }) throw ActionError("Город жив или уже восстанавливается")
            } else {
                if (!city.alive) throw ActionError("Город уничтожен")
                if (type == ActionType.SHIELD && (city.shield || queued.any { it.type == type && it.target == ref })) throw ActionError("Щит уже есть")
                if (type == ActionType.INVEST && city.life >= 100 && city.development >= 100) throw ActionError("Город полностью развит")
            }
        }
        ActionType.RESEARCH -> if (c.tech || c.techInResearch || queued.any { it.type == type }) throw ActionError("Технология уже есть или исследуется")
        ActionType.PRODUCE -> {
            if (!c.tech) throw ActionError("Технология будет доступна со следующего раунда")
            if (hardRules.maxBombsBuiltPerRound > 0 && queued.count { it.type == type } >= hardRules.maxBombsBuiltPerRound) throw ActionError("Достигнут лимит производства")
        }
        ActionType.LAUNCH -> {
            if (!hardRules.finalRoundStrikes && turn == hardRules.rounds) throw ActionError("Удары в последнем раунде отключены")
            if (freeBombs(name) <= 0) throw ActionError("Нет свободных бомб")
            if (hardRules.launchLimit && queued.count { it.type == type } >= hardRules.maxLaunchesPerRound) throw ActionError("Достигнут лимит запусков")
            if (target == null || city(target)?.alive != true) throw ActionError("Цель должна быть живым городом")
        }
        ActionType.SANCTION -> {
            if (to == null || to == name || country(to)?.isAlive != true) throw ActionError("Выберите другую живую страну")
            if (queued.any { it.type == type && it.toCountry == to }) throw ActionError("Санкция против этой страны уже есть")
        }
        ActionType.TRANSFER -> throw ActionError("Для перевода нужны получатель и сумма")
        ActionType.ECOLOGY -> Unit
        ActionType.UPGRADE -> Unit
    }
}

fun GameState.transferNow(from: String, to: String, amount: Int, requestId: String = inviteCode() + inviteCode()): GameState {
    if (!isHard) throw ActionError("Мгновенные переводы доступны в хард-режиме")
    if (requestId.isBlank()) throw ActionError("Нет номера перевода")
    if (requestId in processedTransfers) return this
    validateTransfer(from, to, amount)
    if (country(to)!!.balance.toLong() + amount > Int.MAX_VALUE) throw ActionError("Слишком большой баланс получателя")
    return updateCountry(from) { it.copy(balance = it.balance - amount) }
        .updateCountry(to) { it.copy(balance = it.balance + amount) }
        .copy(liveEvents = liveEvents + MoneySent(from, to, amount) + MoneyReceived(from, to, amount),
            processedTransfers = processedTransfers + requestId, ready = ready - from - to)
}

@Serializable
data class HardStanding(val country: String, val life: Double, val development: Double, val balance: Int)

fun GameState.hardStandings(): List<HardStanding> = countries.map {
    HardStanding(it.name, (it.averageLife() * 1_000_000).roundToInt() / 1_000_000.0,
        (it.averageDevelopment() * 1_000_000).roundToInt() / 1_000_000.0, it.balance)
}.sortedWith(compareByDescending<HardStanding> { it.life }.thenByDescending { it.development }.thenByDescending { it.balance })

/** Независимый расчёт хард-режима: классические правила не участвуют. */
fun GameState.resolveHardTurn(): GameState {
    if (isOver) throw ActionError("Игра окончена")
    val r = hardRules
    val events = liveEvents.toMutableList()
    val startingAlive = aliveCountries().map { it.name }.toSet()
    var state = copy(pending = emptyList(), ready = emptySet(), liveEvents = emptyList())
    fun note(country: String?, text: String) { events += HardEvent(country, text) }
    fun eco(delta: Double) { state = state.copy(hardEcology = (state.hardEcology + delta).coerceIn(0.0, 100.0)) }
    fun lifeLoss(name: String, loss: Double) {
        state = state.updateCountry(name) { it.copy(cities = it.cities.map { c -> if (c.alive) c.copy(life = (c.life - loss).coerceAtLeast(0.0)) else c }) }
    }
    // Все траты зарезервированы заранее. Скидка восстановления определяется состоянием начала раунда.
    for (c in countries) {
        val price = pendingOf(c.name).sumOf { cost(it) }
        if (price > c.balance) throw ActionError("Очередь ${c.name} превышает баланс")
        state = state.updateCountry(c.name) { it.copy(balance = it.balance - price) }
    }
    for (a in pending.filter { it.type == ActionType.REBUILD }) {
        val ref = a.target!!
        state = state.updateCity(ref) { it.copy(alive = true, life = r.rebuildLife, development = r.rebuildDevelopment, shield = r.rebuildShield) }
        note(null, "${ref.country} восстановила ${ref.city}")
    }
    for (a in pending.filter { it.type == ActionType.SHIELD }) {
        state = state.updateCity(a.target!!) { it.copy(shield = true) }
        events += ShieldBuilt(a.country, a.target.city)
    }
    // Инвестиции в один город суммируются: дробление приказа не меняет отдачу.
    for ((ref, actions) in pending.filter { it.type == ActionType.INVEST }.groupBy { it.target!! }) {
        val amount = actions.sumOf { it.amount }
        val before = state.city(ref)!!
        val life = (before.life + amount / 100.0 * r.lifePer100 * (1 - r.investmentFalloff * before.life / 100)).coerceIn(0.0, 100.0)
        val development = (before.development + amount / 100.0 * r.developmentPer100 * (1 - r.investmentFalloff * before.development / 100)).coerceIn(0.0, 100.0)
        state = state.updateCity(ref) { it.copy(life = life, development = development) }
        note(ref.country, "${ref.country}: инвестиции $amount$ в ${ref.city}; жизнь ${life.short()}%, развитие ${development.short()}")
    }
    for (a in pending.filter { it.type == ActionType.RESEARCH }) {
        state = state.updateCountry(a.country) { it.copy(tech = true, techInResearch = false) }
        lifeLoss(a.country, r.techLifePenalty); eco(-r.techEcologyLoss)
        events += ResearchStarted(a.country); events += TechReady(a.country)
    }
    for (a in pending.filter { it.type == ActionType.PRODUCE }) {
        state = state.updateCountry(a.country) { it.copy(bombs = it.bombs + 1) }
        lifeLoss(a.country, r.bombBuildLifePenalty); eco(-r.bombBuildEcologyLoss)
        events += BombOrdered(a.country)
    }
    // Восстановление даёт иммунитет: целью может быть только город, живой в начале раунда.
    for (a in pending.filter { it.type == ActionType.LAUNCH }) {
        val ref = a.target!!
        val city = state.city(ref)!!
        if (!city.alive || city(ref)?.alive != true) {
            events += LaunchResult(a.country, ref, LaunchOutcome.ALREADY_DESTROYED)
            continue
        }
        if (a.country !in startingAlive) continue
        state = state.updateCountry(a.country) { it.copy(bombs = it.bombs - 1) }
        eco(-r.bombLaunchEcologyLoss)
        if (city.shield) {
            state = state.updateCity(ref) { it.copy(shield = false, life = (it.life - r.shieldHitLifeLoss).coerceAtLeast(0.0)) }
            events += LaunchResult(a.country, ref, LaunchOutcome.SHIELD_HIT); events += ShieldHit(ref)
        } else {
            state = state.updateCity(ref) { it.copy(alive = false, life = 0.0, development = 0.0) }
            events += LaunchResult(a.country, ref, LaunchOutcome.DESTROYED); events += CityDestroyed(ref)
        }
    }
    // Экологические вложения применяются после суммарных ядерных потерь, одинаково для всех стран.
    val ecologyInvestment = pending.filter { it.type == ActionType.ECOLOGY }.sumOf { it.amount }
    eco(ecologyInvestment / 100.0 * r.ecologyPer100)
    for (a in pending.filter { it.type == ActionType.ECOLOGY }) note(a.country, "${a.country} вложила ${a.amount}$ в экологию")
    val sanctions = pending.filter { it.type == ActionType.SANCTION }
    for (a in sanctions) {
        note(a.country, "${a.country} ввела санкции против ${a.toCountry}")
        note(a.toCountry, "${a.toCountry}: санкции от ${a.country}")
    }
    val ecologyLoss = (r.ecologyThreshold - state.hardEcology).coerceAtLeast(0.0) * r.ecologyLifeLossPerPoint
    state = state.copy(countries = state.countries.map { c -> c.copy(cities = c.cities.map {
        if (it.alive) it.copy(life = (it.life - ecologyLoss).coerceAtLeast(0.0), development = it.development * (1 - r.developmentDecay / 100)) else it
    }) })
    val incomeState = state // Налог считается по одному снимку всех стран.
    for (c in incomeState.countries) {
        val income = incomeState.hardIncome(c, sanctions.count { it.toCountry == c.name })
        val aid = if (!c.isAlive) r.unAid else 0
        val upkeep = (c.bombs.toLong() * r.bombUpkeep).coerceAtMost(c.balance.toLong() + income + aid).toInt()
        state = state.updateCountry(c.name) { it.copy(balance = it.balance + income + aid - upkeep) }
        events += IncomeReceived(c.name, income, state.country(c.name)!!.balance)
        if (aid > 0) note(c.name, "${c.name}: помощь ООН +$aid$")
        if (upkeep > 0) note(c.name, "${c.name}: содержание бомб −$upkeep$")
        note(c.name, "${c.name}: средняя жизнь ${c.averageLife().short()}%, развитие ${c.averageDevelopment().short()}")
    }
    note(null, "Экология: ${hardEcology.short()}% → ${state.hardEcology.short()}%")
    val result = if (turn >= r.rounds) {
        val ranking = state.hardStandings()
        val first = ranking.first()
        GameResult.Ranking(ranking, ranking.filter { it.life == first.life && it.development == first.development && it.balance == first.balance }.map { it.country })
    } else null
    if (result != null) events += GameOver(result)
    return state.copy(turn = turn + 1, ecology = state.hardEcology.roundToInt(), result = result,
        log = log + TurnLog(turn, events), activeEvent = null, developmentReport = null)
}
