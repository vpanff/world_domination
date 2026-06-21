"""
engine.py — применение действий и разрешение хода.

Приоритет внутри хода:
  1. Щиты
  2. Улучшения / исследования / производство / экология
  3. Удары бомбами
  4. Начисление дохода + завершение производств
     tech_in_research начатое В ЭТОМ ходу → завершится только в СЛЕДУЮЩЕМ
"""

from model import (
    GameState, Country, City, Action, ActionType,
    ACTION_PRIORITY,
    COST_UPGRADE, COST_SHIELD, COST_RESEARCH, COST_PRODUCE, COST_ECOLOGY,
    INCOME_BY_LEVEL, MAX_LEVEL,
)
from typing import Optional


# ─── Валидация ────────────────────────────────────────────────────────────────

class ActionError(Exception):
    pass


def validate_action(state: GameState, action: Action) -> None:
    country = state.find_country(action.country_name)
    if not country:
        raise ActionError(f"Страна '{action.country_name}' не найдена")
    if not country.is_alive:
        raise ActionError(f"Страна '{action.country_name}' уничтожена")

    t = action.action_type

    if t == ActionType.UPGRADE:
        city = country.find_city(action.target_city_name)
        if not city:
            raise ActionError(f"Город '{action.target_city_name}' не найден в {country.name}")
        if not city.isalive:
            raise ActionError(f"Город '{action.target_city_name}' уничтожен")
        if city.level >= MAX_LEVEL:
            raise ActionError(f"Город '{action.target_city_name}' уже максимального уровня")
        if country.balance < COST_UPGRADE:
            raise ActionError(f"Недостаточно средств (нужно {COST_UPGRADE}$, есть {country.balance}$)")

    elif t == ActionType.SHIELD:
        city = country.find_city(action.target_city_name)
        if not city:
            raise ActionError(f"Город '{action.target_city_name}' не найден в {country.name}")
        if not city.isalive:
            raise ActionError(f"Город '{action.target_city_name}' уничтожен")
        if city.shield:
            raise ActionError(f"Щит уже стоит на '{action.target_city_name}'")
        if country.balance < COST_SHIELD:
            raise ActionError(f"Недостаточно средств (нужно {COST_SHIELD}$, есть {country.balance}$)")

    elif t == ActionType.RESEARCH:
        if country.tech:
            raise ActionError(f"{country.name} уже изучила технологию")
        if country.tech_in_research:
            raise ActionError(f"{country.name} уже ведёт исследование")
        if country.balance < COST_RESEARCH:
            raise ActionError(f"Недостаточно средств (нужно {COST_RESEARCH}$, есть {country.balance}$)")

    elif t == ActionType.PRODUCE:
        if not country.tech:
            raise ActionError(f"{country.name} не изучила технологию")
        if country.balance < COST_PRODUCE:
            raise ActionError(f"Недостаточно средств (нужно {COST_PRODUCE}$, есть {country.balance}$)")

    elif t == ActionType.ECOLOGY:
        if country.balance < COST_ECOLOGY:
            raise ActionError(f"Недостаточно средств (нужно {COST_ECOLOGY}$, есть {country.balance}$)")

    elif t == ActionType.LAUNCH:
        if country.bombs < 1:
            raise ActionError(f"У {country.name} нет бомб")
        result = state.find_city_anywhere(action.target_city_name)
        if not result:
            raise ActionError(f"Цель '{action.target_city_name}' не найдена")
        _, target_city = result
        if not target_city.isalive:
            raise ActionError(f"Город '{action.target_city_name}' уже уничтожен")


# ─── Очередь ─────────────────────────────────────────────────────────────────

def add_action(state: GameState, action: Action) -> None:
    validate_action(state, action)
    state.pending_actions.append(action)


def remove_action(state: GameState, index: int) -> Action:
    return state.pending_actions.pop(index)


# ─── Применение одного действия ──────────────────────────────────────────────

def _apply_action(state: GameState, action: Action) -> Optional[str]:
    country = state.find_country(action.country_name)
    if not country or not country.is_alive:
        return None

    t = action.action_type

    if t == ActionType.SHIELD:
        city = country.find_city(action.target_city_name)
        if not city or not city.isalive or city.shield:
            return None
        if country.balance < COST_SHIELD:
            return f"⚠ {country.name}: не хватает денег на щит для {city.name}"
        city.shield = True
        country.balance -= COST_SHIELD
        return f"🛡 {country.name} поставила щит на {city.name}"

    elif t == ActionType.UPGRADE:
        city = country.find_city(action.target_city_name)
        if not city or not city.isalive:
            return f"⚠ {country.name}: {action.target_city_name} уничтожен до улучшения"
        if city.level >= MAX_LEVEL or country.balance < COST_UPGRADE:
            return None
        city.level += 1
        country.balance -= COST_UPGRADE
        return f"⬆ {country.name} улучшила {city.name} до уровня {city.level}"

    elif t == ActionType.RESEARCH:
        if country.tech or country.tech_in_research or country.balance < COST_RESEARCH:
            return None
        country.tech_in_research = True
        country.balance -= COST_RESEARCH
        state.ecology = max(0, state.ecology - 5)
        return f"🔬 {country.name} начала исследование (технология будет готова в следующем ходу, экология −5)"

    elif t == ActionType.PRODUCE:
        if not country.tech or country.balance < COST_PRODUCE:
            return None
        country.bombs_in_production += 1
        country.balance -= COST_PRODUCE
        state.ecology = max(0, state.ecology - 4)
        return f"💣 {country.name} запустила производство бомбы (экология −4)"

    elif t == ActionType.ECOLOGY:
        if country.balance < COST_ECOLOGY:
            return None
        country.balance -= COST_ECOLOGY
        state.ecology = min(100, state.ecology + 20)
        return f"🌿 {country.name} улучшила экологию (+20 → {state.ecology}%)"

    elif t == ActionType.LAUNCH:
        if country.bombs < 1:
            return None
        result = state.find_city_anywhere(action.target_city_name)
        if not result:
            return None
        target_country, target_city = result
        if not target_city.isalive:
            return f"⚠ {country.name} → {action.target_city_name}: цель уже уничтожена"
        country.bombs -= 1
        if target_city.shield:
            target_city.shield = False
            return f"💥 {country.name} ударила по {action.target_city_name} — щит уничтожен!"
        else:
            target_city.isalive = False
            return f"☢ {country.name} уничтожила {action.target_city_name}!"

    return None


# ─── Разрешение хода ─────────────────────────────────────────────────────────

def resolve_turn(state: GameState) -> list[str]:
    """
    1. Снимаем снапшот tech_in_research ДО применения действий.
    2. Применяем действия (среди них может быть новый RESEARCH).
    3. В итогах завершаем только те исследования, что были активны ДО хода.
    """
    log: list[str] = [f"━━━ Ход {state.turn} ━━━"]

    # Снапшот: какие страны УЖЕ вели исследование до этого хода
    researching_before = {
        c.name for c in state.countries if c.tech_in_research
    }

    ordered = sorted(state.pending_actions, key=lambda a: ACTION_PRIORITY[a.action_type])
    for action in ordered:
        result = _apply_action(state, action)
        if result:
            log.append(result)

    state.pending_actions.clear()

    log.append("── Итоги хода ──")
    for country in state.countries:
        if not country.is_alive:
            continue

        # Завершаем только исследования начатые В ПРОШЛОМ ходу
        if country.name in researching_before:
            country.tech = True
            country.tech_in_research = False
            log.append(f"  ✓ {country.name}: технология изучена")

        # Завершение производства бомб
        if country.bombs_in_production > 0:
            country.bombs += country.bombs_in_production
            log.append(f"  ✓ {country.name}: +{country.bombs_in_production} бомб (всего {country.bombs})")
            country.bombs_in_production = 0

        # Доход
        country.balance += country.income
        log.append(f"  💰 {country.name}: +{country.income}$ → баланс {country.balance}$")

    victory_log = _check_victory(state)
    log.extend(victory_log)

    state.turn += 1
    state.event_log.extend(log)
    return log


# ─── Победа ──────────────────────────────────────────────────────────────────

def _check_victory(state: GameState) -> list[str]:
    log = []

    if state.ecology <= 0:
        state.game_over = True
        state.winner = None
        log.append("☠ ЭКОЛОГИЧЕСКАЯ КАТАСТРОФА — все проигрывают!")
        return log

    alive = state.alive_countries()

    if len(alive) == 1:
        state.game_over = True
        state.winner = alive[0].name
        log.append(f"🏆 {alive[0].name} победила — единственная выжившая!")
        return log

    if len(alive) == 0:
        state.game_over = True
        state.winner = None
        log.append("☠ Все страны уничтожены — ничья!")

    return log


def compute_scores(state: GameState) -> dict[str, int]:
    return {c.name: c.score for c in state.countries}
