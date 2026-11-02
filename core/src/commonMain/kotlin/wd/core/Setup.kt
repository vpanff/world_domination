package wd.core

import kotlinx.serialization.Serializable

@Serializable
data class CountrySetup(val name: String, val cities: List<String>)

/** Параметры новой игры: названия стран и городов, числовые правила. */
@Serializable
data class GameSetup(
    val countries: List<CountrySetup> = DEFAULT_COUNTRIES,
    val rules: Rules = Rules(),
) {
    companion object {
        val DEFAULT_COUNTRIES = listOf(
            CountrySetup("Россия", listOf("Москва", "Владивосток", "Санкт-Петербург", "Новосибирск")),
            CountrySetup("США", listOf("Лос-Анджелес", "Нью-Йорк", "Вашингтон", "Чикаго")),
            CountrySetup("Китай", listOf("Пекин", "Шанхай", "Ухань", "Гуанчжоу")),
            CountrySetup("Израиль", listOf("Тель-Авив", "Иерусалим", "Хайфа", "Биробиджан")),
            CountrySetup("Германия", listOf("Берлин", "Мюнхен", "Гамбург", "Кёльн")),
        )
    }
}

/** Создаёт игру. Бросает IllegalArgumentException при некорректных названиях. */
fun newGame(setup: GameSetup = GameSetup()): GameState {
    val names = setup.countries.map { it.name.trim() }
    require(names.size >= 2) { "Нужно минимум 2 страны" }
    require(names.none { it.isEmpty() }) { "У страны пустое название" }
    require(names.toSet().size == names.size) { "Названия стран повторяются" }
    for (c in setup.countries) {
        val cities = c.cities.map { it.trim() }
        require(cities.isNotEmpty()) { "У страны ${c.name} нет городов" }
        require(cities.none { it.isEmpty() }) { "В стране ${c.name} город без названия" }
        require(cities.toSet().size == cities.size) { "В стране ${c.name} названия городов повторяются" }
    }

    val rules = setup.rules
    return GameState(
        rules = rules,
        ecology = rules.startEcology,
        countries = setup.countries.map { c ->
            Country(
                name = c.name.trim(),
                cities = c.cities.map { City(it.trim()) },
                balance = rules.startBalance,
                bombs = rules.startBombs,
            )
        },
    )
}
