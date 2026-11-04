@file:OptIn(ExperimentalLayoutApi::class)

package wd.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import wd.core.CountrySetup
import wd.core.GameSetup
import wd.core.GameState
import wd.core.Rules
import wd.core.newGame

private const val MAX_COUNTRIES = 8
private const val MAX_START_BOMBS = 10

@Composable
fun NewGameScreen(onStart: (GameState) -> Unit, onBack: () -> Unit) {
    var countries by remember { mutableStateOf(GameSetup.DEFAULT_COUNTRIES) }
    var rules by remember { mutableStateOf(Rules()) }
    var error by remember { mutableStateOf<String?>(null) }

    fun edit(index: Int, change: (CountrySetup) -> CountrySetup) {
        countries = countries.mapIndexed { i, c -> if (i == index) change(c) else c }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Txt("НОВАЯ ИГРА", size = 24.sp, bold = true)
        Txt("Названия стран и городов можно изменить", color = Palette.dim)

        countries.forEachIndexed { i, country ->
            Panel(Modifier.widthIn(max = 900.dp).fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field(country.name, { v -> edit(i) { it.copy(name = v) } }, Modifier.weight(1f))
                    Btn("Убрать", {
                        countries = countries.filterIndexed { j, _ -> j != i }
                    }, enabled = countries.size > 2, color = Palette.panel, textColor = Palette.red)
                }
                FlowRow(
                    Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    country.cities.forEachIndexed { j, city ->
                        Field(city, { v ->
                            edit(i) { c -> c.copy(cities = c.cities.mapIndexed { k, old -> if (k == j) v else old }) }
                        }, Modifier.width(140.dp))
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Btn("+ Страна", {
                val n = countries.size + 1
                countries = countries + CountrySetup("Страна $n", (1..4).map { "Город $n-$it" })
            }, enabled = countries.size < MAX_COUNTRIES)
            Btn("По умолчанию", {
                countries = GameSetup.DEFAULT_COUNTRIES
                error = null
            })
        }

        Panel(Modifier.widthIn(max = 900.dp).fillMaxWidth()) {
            Txt("ПРАВИЛА", bold = true, color = Palette.dim)
            Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                RuleToggle("Случайные ивенты", rules.eventsEnabled) { rules = rules.copy(eventsEnabled = it) }
                RuleToggle("Переводы денег между странами", rules.transfersEnabled) { rules = rules.copy(transfersEnabled = it) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Txt("Начальных бомб у каждой страны:")
                    Btn("−", { rules = rules.copy(startBombs = rules.startBombs - 1) }, enabled = rules.startBombs > 0)
                    Txt("${rules.startBombs}", bold = true)
                    Btn("+", { rules = rules.copy(startBombs = rules.startBombs + 1) }, enabled = rules.startBombs < MAX_START_BOMBS)
                }
            }
        }

        error?.let { Txt(it, color = Palette.red) }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Btn("Назад", onBack)
            Btn("НАЧАТЬ", {
                try {
                    onStart(newGame(GameSetup(countries, rules)))
                } catch (e: IllegalArgumentException) {
                    error = e.message
                }
            }, color = Palette.accent)
        }
    }
}

@Composable
private fun RuleToggle(title: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Btn(if (value) "Вкл" else "Выкл", { onChange(!value) }, Modifier.width(64.dp), color = if (value) Palette.green.copy(alpha = 0.6f) else Palette.button)
        Txt(title)
    }
}
