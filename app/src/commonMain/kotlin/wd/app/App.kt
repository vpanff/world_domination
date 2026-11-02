package wd.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import wd.core.GameState

private enum class Screen { MENU, NEW_GAME, LOAD, SETTINGS, GAME }

/**
 * Корень приложения ведущего.
 * [onExit] — выход из приложения; null, если платформа сама управляет закрытием (Android).
 */
@Composable
fun App(storage: Storage, onExit: (() -> Unit)? = null) {
    val saves = remember { Saves(storage) }
    var screen by remember { mutableStateOf(Screen.MENU) }
    var controller by remember { mutableStateOf<GameController?>(null) }

    fun open(state: GameState) {
        saves.autosave(state)
        controller = GameController(state, onChange = saves::autosave)
        screen = Screen.GAME
    }

    Column(Modifier.fillMaxSize().background(Palette.bg)) {
        when (screen) {
            Screen.MENU -> MainMenu(
                canContinue = controller != null || saves.loadAutosave() != null,
                onContinue = {
                    if (controller == null) saves.loadAutosave()?.let(::open) else screen = Screen.GAME
                },
                onNewGame = { screen = Screen.NEW_GAME },
                onLoad = { screen = Screen.LOAD },
                onSettings = { screen = Screen.SETTINGS },
                onExit = onExit,
            )
            Screen.NEW_GAME -> NewGameScreen(onStart = ::open, onBack = { screen = Screen.MENU })
            Screen.LOAD -> LoadScreen(saves, onLoad = ::open, onBack = { screen = Screen.MENU })
            Screen.SETTINGS -> SettingsScreen(onBack = { screen = Screen.MENU })
            Screen.GAME -> GameScreen(controller!!, saves, onMenu = { screen = Screen.MENU })
        }
    }
}

@Composable
private fun CenteredColumn(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
    ) { content() }
}

@Composable
private fun MainMenu(
    canContinue: Boolean,
    onContinue: () -> Unit,
    onNewGame: () -> Unit,
    onLoad: () -> Unit,
    onSettings: () -> Unit,
    onExit: (() -> Unit)?,
) = CenteredColumn {
    Txt("МИРОВОЕ ГОСПОДСТВО", size = 32.sp, bold = true, color = Palette.accent)
    Spacer(Modifier.height(24.dp))
    val wide = Modifier.width(280.dp)
    if (canContinue) Btn("Продолжить", onContinue, wide)
    Btn("Новая игра", onNewGame, wide)
    Btn("Загрузить", onLoad, wide)
    Btn("Настройки", onSettings, wide)
    if (onExit != null) Btn("Выход", onExit, wide)
}

@Composable
private fun LoadScreen(saves: Saves, onLoad: (GameState) -> Unit, onBack: () -> Unit) = CenteredColumn {
    var list by remember { mutableStateOf(saves.list()) }
    var error by remember { mutableStateOf<String?>(null) }

    Txt("ЗАГРУЗКА", size = 24.sp, bold = true)
    if (list.isEmpty()) Txt("Сохранений пока нет", color = Palette.dim)
    for (save in list) {
        Row(
            Modifier.widthIn(max = 500.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Txt(save.name, Modifier.weight(1f))
            Btn("Загрузить", {
                val state = saves.load(save.name)
                if (state != null) onLoad(state) else error = "Не удалось прочитать «${save.name}»"
            })
            Btn("Удалить", {
                saves.delete(save.name)
                list = saves.list()
            }, color = Palette.panel, textColor = Palette.red)
        }
    }
    error?.let { Txt(it, color = Palette.red) }
    Spacer(Modifier.height(12.dp))
    Btn("Назад", onBack)
}

@Composable
private fun SettingsScreen(onBack: () -> Unit) = CenteredColumn {
    Txt("НАСТРОЙКИ", size = 24.sp, bold = true)
    Txt("Здесь появятся настройки Telegram-бота и звука.", color = Palette.dim)
    Txt("Версия 2.0.0", color = Palette.dim)
    Spacer(Modifier.height(12.dp))
    Btn("Назад", onBack)
}
