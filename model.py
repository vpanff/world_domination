"""
model.py — чистая игровая логика, без UI.
"""

from __future__ import annotations
from dataclasses import dataclass, field
from typing import Optional
from enum import Enum, auto


# ─── Уровни и очки ───────────────────────────────────────────────────────────

INCOME_BY_LEVEL: tuple[int, ...] = (35, 70, 140, 210, 280, 350, 420, 490)
MAX_LEVEL = len(INCOME_BY_LEVEL) - 1

# Очки: level² × K (нелинейно — высокие уровни ценнее)
SCORE_K = 10

def city_score(level: int) -> int:
    return level ** 2 * SCORE_K


# ─── Стоимости действий ──────────────────────────────────────────────────────

COST_UPGRADE  = 150
COST_SHIELD   = 250
COST_RESEARCH = 500
COST_PRODUCE  = 150
COST_ECOLOGY  = 200


# ─── Типы и приоритеты действий ──────────────────────────────────────────────

class ActionType(Enum):
    SHIELD   = auto()  # приоритет 1
    UPGRADE  = auto()  # приоритет 2
    RESEARCH = auto()  # приоритет 2
    PRODUCE  = auto()  # приоритет 2
    ECOLOGY  = auto()  # приоритет 2
    LAUNCH   = auto()  # приоритет 3

ACTION_PRIORITY: dict[ActionType, int] = {
    ActionType.SHIELD:   1,
    ActionType.UPGRADE:  2,
    ActionType.RESEARCH: 2,
    ActionType.PRODUCE:  2,
    ActionType.ECOLOGY:  2,
    ActionType.LAUNCH:   3,
}


# ─── Действие в очереди ──────────────────────────────────────────────────────

@dataclass
class Action:
    action_type: ActionType
    country_name: str
    target_city_name: Optional[str] = None


# ─── Город ───────────────────────────────────────────────────────────────────

@dataclass
class City:
    name: str
    level: int = 0
    isalive: bool = True
    shield: bool = False

    @property
    def income(self) -> int:
        return INCOME_BY_LEVEL[self.level] if self.isalive else 0

    @property
    def score(self) -> int:
        return city_score(self.level) if self.isalive else 0

    def __repr__(self):
        status = "жив" if self.isalive else "уничтожен"
        shield_str = " [щит]" if self.shield else ""
        return f"{self.name} ур.{self.level}{shield_str} ({status})"


# ─── Страна ──────────────────────────────────────────────────────────────────

@dataclass
class Country:
    name: str
    cities: list[City]
    balance: int = 1000
    tech: bool = False
    tech_in_research: bool = False   # True = идёт исследование, завершится в конце следующего хода
    bombs: int = 1
    bombs_in_production: int = 0

    @property
    def income(self) -> int:
        return sum(c.income for c in self.cities)

    @property
    def score(self) -> int:
        return sum(c.score for c in self.cities)

    @property
    def is_alive(self) -> bool:
        return any(c.isalive for c in self.cities)

    def find_city(self, name: str) -> Optional[City]:
        for city in self.cities:
            if city.name == name:
                return city
        return None

    def __repr__(self):
        return (f"{self.name}: баланс={self.balance}$  доход={self.income}$  "
                f"бомбы={self.bombs}  tech={'да' if self.tech else 'нет'}")


# ─── Глобальное состояние ────────────────────────────────────────────────────

@dataclass
class GameState:
    countries: list[Country]
    ecology: int = 100
    turn: int = 1
    game_over: bool = False
    winner: Optional[str] = None

    pending_actions: list[Action] = field(default_factory=list)
    event_log: list[str] = field(default_factory=list)

    def find_country(self, name: str) -> Optional[Country]:
        for country in self.countries:
            if country.name == name:
                return country
        return None

    def find_city_anywhere(self, city_name: str) -> Optional[tuple[Country, City]]:
        for country in self.countries:
            city = country.find_city(city_name)
            if city:
                return country, city
        return None

    def alive_countries(self) -> list[Country]:
        return [c for c in self.countries if c.is_alive]


# ─── Начальное состояние ─────────────────────────────────────────────────────

def make_default_game() -> GameState:
    russia = Country("Россия", [
        City("Москва"), City("Владивосток"),
        City("Санкт-Петербург"), City("Новосибирск"),
    ])
    usa = Country("США", [
        City("Лос-Анджелес"), City("Нью-Йорк"),
        City("Вашингтон"), City("Чикаго"),
    ])
    china = Country("Китай", [
        City("Пекин"), City("Шанхай"),
        City("Ухань"), City("Гуанчжоу"),
    ])
    israel = Country("Израиль", [
        City("Тель-Авив"), City("Иерусалим"),
        City("Хайфа"), City("Биробиджан"),
    ])
    germany = Country("Германия", [
        City("Берлин"), City("Мюнхен"),
        City("Гамбург"), City("Кёльн"),
    ])
    return GameState(countries=[russia, usa, china, israel, germany])
