package wd.core

/*
 * Применение действий и расчёт хода.
 *
 * Порядок внутри хода:
 *   1. Щиты
 *   2. Улучшения / исследования / производство / экология (в порядке очереди)
 *   3. Запуски бомб — одновременно: страна, уничтоженная в этой фазе,
 *      всё равно наносит свои удары
 *   4. Итоги: завершение исследований прошлого хода, готовые бомбы, доход
 *   5. Проверка конца игры
 */

class ActionError(message: String) : Exception(message)

// ─── Очередь ─────────────────────────────────────────────────────────────────

/** Проверяет действие с учётом уже стоящих в очереди. Бросает [ActionError]. */
fun GameState.validate(type: ActionType, countryName: String, target: CityRef? = null) {
    if (isOver) throw ActionError("Игра окончена")
    val country = country(countryName) ?: throw ActionError("Страна '$countryName' не найдена")
    if (!country.isAlive) throw ActionError("Страна '$countryName' уничтожена")

    val queued = pendingOf(countryName)
    fun queuedCount(t: ActionType, ref: CityRef? = null) =
        queued.count { it.type == t && (ref == null || it.target == ref) }

    val cost = rules.cost(type)
    val available = availableBalance(countryName)
    if (available < cost) throw ActionError("Недостаточно средств (нужно $cost$, доступно $available$)")

    when (type) {
        ActionType.UPGRADE, ActionType.SHIELD -> {
            val ref = target ?: throw ActionError("Не выбран город")
            if (ref.country != countryName) throw ActionError("${ref.city} — не ваш город")
            val city = country.city(ref.city) ?: throw ActionError("Город '${ref.city}' не найден")
            if (!city.alive) throw ActionError("Город '${city.name}' уничтожен")
            if (type == ActionType.UPGRADE && city.level + queuedCount(type, ref) >= rules.maxLevel)
                throw ActionError("Город '${city.name}' достигнет максимального уровня")
            if (type == ActionType.SHIELD && (city.shield || queuedCount(type, ref) > 0))
                throw ActionError("Щит на '${city.name}' уже есть")
        }

        ActionType.RESEARCH -> {
            if (country.tech) throw ActionError("Технология уже изучена")
            if (country.techInResearch || queuedCount(type) > 0) throw ActionError("Исследование уже идёт")
        }

        ActionType.PRODUCE -> {
            if (!country.tech) throw ActionError("Технология не изучена")
        }

        ActionType.ECOLOGY -> {}

        ActionType.LAUNCH -> {
            if (country.bombs - queuedCount(type) < 1) throw ActionError("Нет свободных бомб")
            val ref = target ?: throw ActionError("Не выбрана цель")
            val city = city(ref) ?: throw ActionError("Цель '$ref' не найдена")
            if (!city.alive) throw ActionError("Город '$ref' уже уничтожен")
        }
    }
}

fun GameState.addAction(type: ActionType, country: String, target: CityRef? = null): GameState {
    validate(type, country, target)
    val action = Action(nextActionId, type, country, target)
    return copy(pending = pending + action, nextActionId = nextActionId + 1)
}

fun GameState.cancelAction(id: Int): GameState {
    if (pending.none { it.id == id }) throw ActionError("Действие уже отменено или применено")
    return copy(pending = pending.filterNot { it.id == id })
}

// ─── Применение одного действия ──────────────────────────────────────────────

private fun GameState.apply(action: Action, events: MutableList<GameEvent>): GameState {
    val country = country(action.country) ?: return this
    val cost = rules.cost(action.type)
    if (country.balance < cost) {
        events += ActionFailed(country.name, "не хватило денег на «${action.type.title()}»")
        return this
    }
    val paid = updateCountry(country.name) { it.copy(balance = it.balance - cost) }

    return when (action.type) {
        ActionType.SHIELD -> {
            val ref = action.target!!
            val city = city(ref)
            if (city == null || !city.alive || city.shield) return this
            events += ShieldBuilt(country.name, ref.city)
            paid.updateCity(ref) { it.copy(shield = true) }
        }

        ActionType.UPGRADE -> {
            val ref = action.target!!
            val city = city(ref)
            if (city == null || !city.alive || city.level >= rules.maxLevel) return this
            events += CityUpgraded(country.name, ref.city, city.level + 1)
            paid.updateCity(ref) { it.copy(level = it.level + 1) }
        }

        ActionType.RESEARCH -> {
            if (country.tech || country.techInResearch) return this
            events += ResearchStarted(country.name)
            paid.updateCountry(country.name) { it.copy(techInResearch = true) }
                .changeEcology(rules.ecologyResearch)
        }

        ActionType.PRODUCE -> {
            if (!country.tech) return this
            events += BombOrdered(country.name)
            paid.updateCountry(country.name) { it.copy(bombsInProduction = it.bombsInProduction + 1) }
                .changeEcology(rules.ecologyProduce)
        }

        ActionType.ECOLOGY -> {
            events += EcologyFunded(country.name)
            paid.changeEcology(rules.ecologyBoost)
        }

        ActionType.LAUNCH -> {
            val ref = action.target!!
            val city = city(ref) ?: return this
            if (country.bombs < 1) return this
            if (!city.alive) {
                // Бомба не тратится, как в настольной версии
                events += LaunchResult(country.name, ref, LaunchOutcome.ALREADY_DESTROYED)
                return this
            }
            val fired = paid.updateCountry(country.name) { it.copy(bombs = it.bombs - 1) }
            if (city.shield) {
                events += LaunchResult(country.name, ref, LaunchOutcome.SHIELD_HIT)
                events += ShieldHit(ref)
                fired.updateCity(ref) { it.copy(shield = false) }
            } else {
                events += LaunchResult(country.name, ref, LaunchOutcome.DESTROYED)
                events += CityDestroyed(ref)
                fired.updateCity(ref) { it.copy(alive = false) }
            }
        }
    }
}

private fun GameState.changeEcology(delta: Int): GameState =
    copy(ecology = (ecology + delta).coerceIn(0, rules.maxEcology))

// ─── Расчёт хода ─────────────────────────────────────────────────────────────

fun GameState.resolveTurn(): GameState {
    if (isOver) throw ActionError("Игра окончена")
    val events = mutableListOf<GameEvent>()

    // Исследования, начатые в этом ходу, завершатся только в следующем
    val researchingBefore = countries.filter { it.techInResearch }.map { it.name }.toSet()
    val ecologyBefore = ecology

    var state = copy(pending = emptyList(), ready = emptySet())
    val ordered = pending.sortedBy { it.type.priority }   // сортировка стабильная

    for (action in ordered.filter { it.type != ActionType.LAUNCH }) {
        if (state.country(action.country)?.isAlive == true) state = state.apply(action, events)
    }
    val canLaunch = state.aliveCountries().map { it.name }.toSet()
    for (action in ordered.filter { it.type == ActionType.LAUNCH }) {
        if (action.country in canLaunch) state = state.apply(action, events)
    }

    // Итоги хода
    state = state.copy(countries = state.countries.map { c ->
        if (!c.isAlive) return@map c
        var country = c
        if (country.name in researchingBefore) {
            country = country.copy(tech = true, techInResearch = false)
            events += TechReady(country.name)
        }
        if (country.bombsInProduction > 0) {
            country = country.copy(bombs = country.bombs + country.bombsInProduction, bombsInProduction = 0)
            events += BombsReady(country.name, c.bombsInProduction, country.bombs)
        }
        val income = country.income(rules)
        country = country.copy(balance = country.balance + income)
        events += IncomeReceived(country.name, income, country.balance)
        country
    })

    if (state.ecology != ecologyBefore) events += EcologyChanged(ecologyBefore, state.ecology)

    val result = state.checkResult()
    if (result != null) events += GameOver(result)

    return state.copy(
        turn = turn + 1,
        result = result,
        log = log + TurnLog(turn, events),
    )
}

private fun GameState.checkResult(): GameResult? {
    if (ecology <= 0) return GameResult.EcoCatastrophe
    val alive = aliveCountries()
    return when (alive.size) {
        0 -> GameResult.AllDestroyed
        1 -> GameResult.Winner(alive.single().name)
        else -> null
    }
}
