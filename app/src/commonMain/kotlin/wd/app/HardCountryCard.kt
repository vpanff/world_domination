@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package wd.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import wd.core.*

@Composable
fun HardCountryCard(c: GameController, country: Country) {
    val state = c.state
    val name = country.name
    var investmentCity by remember { mutableStateOf<String?>(null) }
    var ecologyForm by remember { mutableStateOf(false) }
    var sanctionsForm by remember { mutableStateOf(false) }
    var amountText by remember { mutableStateOf("100") }
    val amount = amountText.toIntOrNull() ?: 0
    Panel(Modifier.fillMaxWidth()) {
        Txt(name, size = 17.sp, bold = true)
        state.leaders[name]?.let { Txt("глава: ${it.name}" + if(name in state.ready) " · готов" else "", color = Palette.blue, size = 13.sp) }
        Txt("${country.balance}$ · доступно ${state.availableBalance(name)}$", color = Palette.yellow)
        Txt("Жизнь ${country.averageLife().short()}% · развитие ${country.averageDevelopment().short()}", size = 13.sp)
        Txt("Прогноз дохода +${state.incomeOf(country)}$ до санкций", size = 13.sp, color = Palette.dim)
        Txt("Бомбы ${country.bombs} · свободно ${state.freeBombs(name)} · технология ${if(country.tech) "есть" else "нет"}", size = 13.sp)
        if (!country.isAlive) Txt("Без городов: восстановление, переводы; помощь ООН ${state.hardRules.unAid}$/раунд", color = Palette.yellow, size = 13.sp)
        country.cities.forEach { city ->
            val ref = CityRef(name, city.name)
            val target = c.launchFrom != null && city.alive
            Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Txt((if(target) "> " else "") + city.name + if(city.shield) " [щит]" else "",
                    if(target) Modifier.clickable { c.launchAt(ref) } else Modifier,
                    bold = true, color = if(target) Palette.red else Palette.text)
                if (city.alive) Txt("Жизнь ${city.life.short()}% · развитие ${city.development.short()}", size = 13.sp)
                else Txt("Уничтожен", color = Palette.dead, size = 13.sp)
                if (c.launchFrom == null) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if(city.alive) {
                        Btn("Вложить", { investmentCity = city.name; ecologyForm = false }, enabled = c.canAdd(ActionType.INVEST, name, ref, 1), size = 13.sp)
                        Btn("Щит ${state.cost(ActionType.SHIELD)}$", { c.add(ActionType.SHIELD, name, ref) }, enabled = c.canAdd(ActionType.SHIELD, name, ref), size = 13.sp)
                    } else Btn("Отстроить ${state.rebuildCost(name)}$", { c.add(ActionType.REBUILD, name, ref) }, enabled = c.canAdd(ActionType.REBUILD, name, ref), size = 13.sp)
                }
            }
        }
        if (investmentCity != null || ecologyForm) {
            val type = if(ecologyForm) ActionType.ECOLOGY else ActionType.INVEST
            val ref = investmentCity?.let { CityRef(name, it) }
            Txt(if(ecologyForm) "Сумма на экологию" else "Инвестиции: $investmentCity", size = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Field(amountText, { amountText = it.filter(Char::isDigit).take(7) }, Modifier.weight(1f), numeric = true)
                Btn("Добавить", { c.add(type, name, ref, amount); investmentCity = null; ecologyForm = false }, enabled = c.canAdd(type, name, ref, amount), size = 13.sp)
                Btn("×", { investmentCity = null; ecologyForm = false })
            }
        }
        FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Btn("Исслед. ${state.cost(ActionType.RESEARCH)}$", { c.add(ActionType.RESEARCH, name) }, enabled = c.canAdd(ActionType.RESEARCH, name), size = 13.sp)
            Btn("Бомба ${state.cost(ActionType.PRODUCE)}$", { c.add(ActionType.PRODUCE, name) }, enabled = c.canAdd(ActionType.PRODUCE, name), size = 13.sp)
            Btn("Экология", { ecologyForm = !ecologyForm; investmentCity = null }, enabled = c.canAdd(ActionType.ECOLOGY, name, amount = 1), size = 13.sp)
            Btn(if(c.launchFrom == name) "Отмена удара" else "Запуск", { c.startLaunch(name) }, enabled = !state.isOver && country.isAlive && state.freeBombs(name) > 0, size = 13.sp)
            Btn("Санкции", { sanctionsForm = !sanctionsForm }, enabled = !state.isOver && country.isAlive, size = 13.sp)
            Btn("Перевод", { c.transferFrom = if(c.transferFrom == name) null else name }, enabled = !state.isOver, size = 13.sp)
        }
        if (sanctionsForm) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            state.countries.filter { it.name != name && it.isAlive }.forEach { other ->
                Btn(other.name, { c.add(ActionType.SANCTION, name, toCountry = other.name) }, enabled = c.canAdd(ActionType.SANCTION, name, toCountry = other.name), size = 13.sp)
            }
        }
        if(c.transferFrom == name) TransferForm(c, name)
    }
}
