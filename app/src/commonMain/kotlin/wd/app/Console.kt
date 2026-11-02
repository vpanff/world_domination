package wd.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Консоль с журналом бота поверх экрана. Текст можно выделить и скопировать. */
@Composable
fun BoxScope.Console(session: HostSession) {
    val lines = session.botLog
    val list = rememberLazyListState()
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) list.scrollToItem(lines.lastIndex)
    }

    Column(
        Modifier
            .align(Alignment.TopCenter)
            .fillMaxWidth()
            .fillMaxHeight(0.45f)
            .background(Color(0xF0101018))
            .padding(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Txt("КОНСОЛЬ БОТА", Modifier.weight(1f), bold = true, color = Palette.green, size = 13.sp)
            Btn("Очистить", { lines.clear() }, size = 12.sp)
            Btn("Закрыть", { session.consoleOpen = false }, size = 12.sp)
        }
        SelectionContainer(Modifier.weight(1f).padding(top = 6.dp)) {
            LazyColumn(state = list) {
                if (lines.isEmpty()) item { Txt("Журнал пуст. Запустите бота на экране игры.", color = Palette.dim, size = 12.sp) }
                items(lines) { line ->
                    val color = when {
                        "Ошибка" in line || "Не отправлено" in line || "отклонил" in line -> Palette.red
                        "повтор" in line || "Нет связи" in line || "Лимит" in line -> Palette.yellow
                        "←" in line -> Palette.blue
                        else -> Palette.text
                    }
                    Txt(line, color = color, size = 12.sp)
                }
            }
        }
    }
}
