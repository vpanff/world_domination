package wd.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import wd.core.GameState

private enum class Screen { MENU, NEW_GAME, LOAD, SETTINGS, GAME }

/** Системная кнопка «Назад»: (активна ли, обработчик). На ПК её нет. */
typealias BackHandler = @Composable (enabled: Boolean, onBack: () -> Unit) -> Unit

/**
 * Корень приложения ведущего.
 * [onExit] — выход из приложения; null, если платформа сама управляет закрытием (Android).
 */
@Composable
fun App(session: HostSession, onExit: (() -> Unit)? = null, backHandler: BackHandler = { _, _ -> }) {
    // Если партия уже открыта (Android: активити пересоздана, а бот работал в фоне) — сразу в игру
    var screen by remember { mutableStateOf(if (session.controller != null) Screen.GAME else Screen.MENU) }
    val controller = session.controller
    session.hotkeysEnabled = screen != Screen.NEW_GAME && screen != Screen.SETTINGS

    fun open(state: GameState) {
        session.open(state)
        screen = Screen.GAME
    }

    // «Назад»: консоль → закрыть, режим атаки → отмена, любой экран → меню, меню → выход
    backHandler(screen != Screen.MENU || session.consoleOpen) {
        if (session.consoleOpen) session.consoleOpen = false
        else if (screen == Screen.GAME && controller?.launchFrom != null) controller.launchFrom = null else screen = Screen.MENU
    }

    Box(Modifier.fillMaxSize().background(Palette.bg).safeDrawingPadding()) {
        Column(Modifier.fillMaxSize()) {
            when (screen) {
                Screen.MENU -> MainMenu(
                    canContinue = controller != null || session.loadAutosave() != null,
                    onContinue = {
                        if (controller == null) session.loadAutosave()?.let(::open) else screen = Screen.GAME
                    },
                    onNewGame = { screen = Screen.NEW_GAME },
                    onLoad = { screen = Screen.LOAD },
                    onSettings = { screen = Screen.SETTINGS },
                    onExit = onExit,
                )
                Screen.NEW_GAME -> NewGameScreen(onStart = ::open, onBack = { screen = Screen.MENU })
                Screen.LOAD -> LoadScreen(session.saves, onLoad = ::open, onBack = { screen = Screen.MENU })
                Screen.SETTINGS -> SettingsScreen(session, onBack = { screen = Screen.MENU })
                Screen.GAME -> GameScreen(session, onMenu = { screen = Screen.MENU })
            }
        }
        if (session.consoleOpen) Console(session)
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
private fun SettingsScreen(session: HostSession, onBack: () -> Unit) = CenteredColumn {
    var token by remember { mutableStateOf(session.settings.botToken) }
    var check by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Txt("НАСТРОЙКИ", size = 24.sp, bold = true)
    Spacer(Modifier.height(12.dp))
    Column(Modifier.widthIn(max = 600.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Txt("Telegram-бот", bold = true)
        Txt("Токен от @BotFather. Хранится только на этом устройстве.", color = Palette.dim, size = 13.sp)
        Field(token, { token = it; check = null }, Modifier.fillMaxWidth(), secret = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Btn("Проверить", {
                check = "Проверяю…"
                scope.launch {
                    check = session.checkToken(token).fold(
                        { "Бот найден: @$it" },
                        { "Не получилось: ${it.message}" },
                    )
                }
            }, enabled = token.isNotBlank())
            Btn("Сохранить", {
                session.updateToken(token)
                check = "Сохранено"
            }, enabled = token.trim() != session.settings.botToken)
        }
        check?.let { Txt(it, color = Palette.yellow) }
    }
    Spacer(Modifier.height(12.dp))
    Txt("Версия 2.0.0", color = Palette.dim)
    Btn("Назад", onBack)
}
