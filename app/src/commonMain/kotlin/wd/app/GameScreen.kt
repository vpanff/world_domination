@file:OptIn(ExperimentalLayoutApi::class)

package wd.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import wd.core.ActionType
import wd.core.City
import wd.core.CityRef
import wd.core.Country
import wd.core.describe

/** Ширина, меньше которой экран переключается на вкладки (телефон). */
private val NARROW = 900.dp

@Composable
fun GameScreen(session: HostSession, onMenu: () -> Unit) {
    val controller = session.controller ?: return
    val saves = session.saves
    BoxWithConstraints(Modifier.fillMaxSize().padding(8.dp)) {
        val narrow = maxWidth < NARROW
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TopBar(controller, saves, onMenu, narrow)
            if (narrow) NarrowBody(session, controller) else WideBody(session, controller)
        }
    }
}

// ─── Раскладки ───────────────────────────────────────────────────────────────

@Composable
private fun WideBody(session: HostSession, c: GameController) {
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CountryGrid(c, Modifier.weight(1f).fillMaxHeight())
        Column(Modifier.width(340.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            QueuePanel(c, Modifier.weight(1f))
            BotPanel(session, Modifier.weight(1f))
            LogPanel(c, Modifier.weight(1f))
        }
    }
}

@Composable
private fun NarrowBody(session: HostSession, c: GameController) {
    var tab by remember { mutableStateOf(0) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Страны", "Очередь (${c.state.pending.size})", "Бот", "Лог").forEachIndexed { i, title ->
                Btn(title, { tab = i }, color = if (tab == i) Palette.accent else Palette.button)
            }
        }
        when (tab) {
            0 -> CountryGrid(c, Modifier.fillMaxSize())
            1 -> QueuePanel(c, Modifier.fillMaxSize())
            2 -> BotPanel(session, Modifier.fillMaxSize())
            else -> LogPanel(c, Modifier.fillMaxSize())
        }
    }
}

// ─── Верхняя панель ──────────────────────────────────────────────────────────

@Composable
private fun TopBar(c: GameController, saves: Saves, onMenu: () -> Unit, narrow: Boolean) {
    val state = c.state
    val gap = if (narrow) 6.dp else 10.dp
    Panel(Modifier.fillMaxWidth()) {
        // Узкий экран: строка состояния и строка кнопок; широкий — всё в одну строку
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(gap)) {
            Btn("Меню", onMenu)
            Txt("ХОД ${state.turn}", size = if (narrow) 16.sp else 18.sp, bold = true)
            if (!narrow) Txt("ЭКОЛОГИЯ", color = Palette.dim)
            Bar(state.ecology / 100f, ecologyColor(state.ecology), if (narrow) Modifier.weight(1f) else Modifier.width(140.dp))
            Txt("${state.ecology}%", color = ecologyColor(state.ecology), bold = true)
            if (!narrow) TurnButtons(c, saves, Modifier, "ПРИМЕНИТЬ ХОД")
        }
        if (narrow) {
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(gap)) {
                TurnButtons(c, saves, Modifier.weight(1f), "ПРИМЕНИТЬ")
            }
        }
        c.launchFrom?.let { from ->
            Row(
                Modifier.padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Txt("АТАКА: $from — выберите цель", Modifier.weight(1f, fill = false), color = Palette.red, bold = true)
                Btn("Отмена", { c.launchFrom = null })
            }
        }
        state.result?.let { Txt(it.describe(), Modifier.padding(top = 6.dp), color = Palette.yellow, bold = true) }
        c.message?.let { Txt(it, Modifier.padding(top = 6.dp), color = Palette.yellow) }
    }
}

@Composable
private fun TurnButtons(c: GameController, saves: Saves, modifier: Modifier, resolveTitle: String) {
    Btn("Отменить", c::undo, modifier, enabled = c.canUndo)
    Btn("Сохранить", { c.message = "Сохранено: ${saves.save(c.state)}" }, modifier)
    Btn(resolveTitle, c::resolve, modifier, enabled = !c.state.isOver, color = Palette.accent)
}

// ─── Страны ──────────────────────────────────────────────────────────────────

@Composable
private fun CountryGrid(c: GameController, modifier: Modifier) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(300.dp),
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(c.state.countries, key = { it.name }) { CountryCard(c, it) }
    }
}

@Composable
private fun CountryCard(c: GameController, country: Country) {
    val state = c.state
    val name = country.name
    val alive = country.isAlive
    val attacking = c.launchFrom == name

    Panel(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Txt(name, Modifier.weight(1f), size = 17.sp, bold = true, color = if (alive) Palette.text else Palette.dead, strike = !alive)
            if (!alive) Txt("уничтожена", color = Palette.dead)
        }
        state.leaders[name]?.let { leader ->
            val ready = name in state.ready
            Txt("глава: ${leader.name}" + if (ready) " · готов" else "", color = if (ready) Palette.green else Palette.blue, size = 13.sp)
        }
        if (alive) {
            val available = state.availableBalance(name)
            Txt(
                "${country.balance}$" + if (available != country.balance) " (доступно $available$)" else "",
                color = Palette.yellow,
            )
            Txt("доход +${state.incomeOf(country)}$   развитие ${country.development(state.rules)}", color = Palette.dim, size = 13.sp)
            val tech = when {
                country.tech -> "есть"
                country.techInResearch -> "исследуется"
                else -> "нет"
            }
            val production = if (country.bombsInProduction > 0) " (+${country.bombsInProduction})" else ""
            Txt("бомбы ${country.bombs}$production   технология: $tech", color = Palette.dim, size = 13.sp)
        }

        Spacer(Modifier.height(6.dp))
        for (city in country.cities) CityRow(c, country, city)

        if (alive) {
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ActionBtn(c, "Исслед. ${state.cost(ActionType.RESEARCH)}$", ActionType.RESEARCH, name)
                ActionBtn(c, "Бомба ${state.cost(ActionType.PRODUCE)}$", ActionType.PRODUCE, name)
                ActionBtn(c, "Эко ${state.cost(ActionType.ECOLOGY)}$", ActionType.ECOLOGY, name)
                val freeBombs = country.bombs - state.pendingOf(name).count { it.type == ActionType.LAUNCH }
                Btn(
                    if (attacking) "Отмена" else "Запуск",
                    { c.startLaunch(name) },
                    enabled = !state.isOver && (attacking || freeBombs > 0),
                    color = Palette.red.copy(alpha = 0.6f),
                )
            }
        }
    }
}

@Composable
private fun ActionBtn(c: GameController, title: String, type: ActionType, country: String, target: CityRef? = null) {
    Btn(title, { c.add(type, country, target) }, enabled = c.canAdd(type, country, target), size = 13.sp)
}

@Composable
private fun CityRow(c: GameController, country: Country, city: City) {
    val ref = CityRef(country.name, city.name)
    val queued = c.state.pendingOf(country.name).filter { it.target == ref }
    val upgrades = queued.count { it.type == ActionType.UPGRADE }
    val shieldQueued = queued.any { it.type == ActionType.SHIELD }
    val targetMode = c.launchFrom != null && city.alive

    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val nameModifier = if (targetMode) {
            Modifier
                .background(Palette.red.copy(alpha = 0.25f), RoundedCornerShape(3.dp))
                .clickable { c.launchAt(ref) }
                .pointerHoverIcon(PointerIcon.Hand)
                .padding(horizontal = 4.dp)
        } else Modifier.padding(horizontal = 4.dp)
        Txt(
            (if (targetMode) "> " else "") + city.name,
            nameModifier.weight(1f),
            color = when {
                !city.alive -> Palette.dead
                targetMode -> Palette.red
                else -> Palette.text
            },
            strike = !city.alive,
        )
        if (!city.alive) return@Row

        Txt("ур.${city.level}" + if (upgrades > 0) "+$upgrades" else "", color = Palette.blue, size = 13.sp)
        if (city.shield) Txt("[щит]", color = Palette.green, size = 13.sp)
        if (shieldQueued) Txt("[щит?]", color = Palette.yellow, size = 13.sp)
        if (c.launchFrom == null) {
            ActionBtn(c, "+", ActionType.UPGRADE, country.name, ref)
            ActionBtn(c, "Щ", ActionType.SHIELD, country.name, ref)
        }
    }
}

// ─── Очередь и лог ───────────────────────────────────────────────────────────

@Composable
private fun SidePanel(title: String, modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Panel(modifier.fillMaxWidth()) {
        Txt(title, bold = true, color = Palette.dim)
        Spacer(Modifier.height(6.dp))
        content()
    }
}

@Composable
private fun QueuePanel(c: GameController, modifier: Modifier) = SidePanel("ОЧЕРЕДЬ ДЕЙСТВИЙ", modifier) {
    val pending = c.state.pending
    if (pending.isEmpty()) Txt("пусто", color = Palette.dim)
    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items(pending, key = { it.id }) { action ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Column(Modifier.weight(1f)) {
                    Txt(action.country, bold = true, size = 13.sp)
                    Txt(action.describe(), size = 13.sp)
                }
                Btn("×", { c.cancel(action.id) }, color = Palette.panel, textColor = Palette.red)
            }
        }
    }
}

@Composable
private fun LogPanel(c: GameController, modifier: Modifier) = SidePanel("ЛОГ", modifier) {
    val state = c.state
    Btn("Случайный ивент", c::triggerRandomEvent, Modifier.fillMaxWidth(), enabled = !state.isOver && state.activeEvent == null)
    state.activeEvent?.let { Txt(it.describe(), Modifier.padding(top = 6.dp), color = Palette.yellow, size = 13.sp) }
    Spacer(Modifier.height(6.dp))
    LazyColumn(contentPadding = PaddingValues(bottom = 8.dp)) {
        for (turnLog in c.state.log.asReversed()) {
            item { Txt("── Ход ${turnLog.turn} ──", Modifier.padding(top = 6.dp), color = Palette.accent, bold = true) }
            items(turnLog.events) { Txt(it.describe(), size = 13.sp) }
        }
    }
}
