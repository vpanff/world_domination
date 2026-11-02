@file:OptIn(ExperimentalLayoutApi::class)

package wd.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.MutableStateFlow
import wd.bot.BotStatus

@Composable
fun BotPanel(session: HostSession, modifier: Modifier) {
    val controller = session.controller ?: return
    val state = controller.state
    val bot = session.bot
    val status by (bot?.status ?: stopped).collectAsState()
    @Suppress("DEPRECATION") val clipboard = LocalClipboardManager.current

    Panel(modifier.fillMaxWidth()) {
        Txt("TELEGRAM-БОТ", bold = true, color = Palette.dim)
        Spacer(Modifier.height(6.dp))
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (session.settings.botToken.isBlank()) {
                Txt("Укажите токен бота в настройках (главное меню → Настройки)", color = Palette.dim, size = 13.sp)
                return@Column
            }

            val (statusText, statusColor) = when (val s = status) {
                BotStatus.Stopped -> "выключен" to Palette.dim
                BotStatus.Connecting -> "подключение…" to Palette.yellow
                is BotStatus.Online -> "работает · @${s.username}" to Palette.green
                is BotStatus.Offline -> "${s.reason}, повтор через ${s.retryInSeconds} с" to Palette.yellow
                is BotStatus.Failed -> s.reason to Palette.red
            }
            Txt("● $statusText", color = statusColor, size = 13.sp)

            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (status == BotStatus.Stopped || status is BotStatus.Failed) {
                    Btn("Запустить", session::startBot, color = Palette.green.copy(alpha = 0.6f), size = 13.sp)
                } else {
                    Btn("Остановить", session::stopBot, size = 13.sp)
                    Btn("Перезапустить", session::restartBot, size = 13.sp)
                }
                Btn("Разослать отчёты", {
                    bot?.broadcastTurn()
                    controller.message = "Отчёты отправлены игрокам"
                }, enabled = bot != null && state.leaders.isNotEmpty(), size = 13.sp)
            }

            val alive = state.aliveCountries().map { it.name }
            val readyCount = alive.count { it in state.ready }
            Txt("Готовы: $readyCount из ${alive.size}", color = if (readyCount == alive.size) Palette.green else Palette.dim, size = 13.sp)

            for (country in state.countries) {
                val leader = state.leaders[country.name]
                val link = state.invites[country.name]?.let { bot?.inviteLink(it) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Column(Modifier.weight(1f)) {
                        Txt(country.name, bold = true, size = 13.sp, color = if (country.isAlive) Palette.text else Palette.dead)
                        Txt(
                            when {
                                leader == null -> "не подключена"
                                country.name in state.ready -> "${leader.name} · готов"
                                else -> leader.name
                            },
                            color = when {
                                leader == null -> Palette.dim
                                country.name in state.ready -> Palette.green
                                else -> Palette.blue
                            },
                            size = 12.sp,
                        )
                    }
                    Btn("Ссылка", {
                        clipboard.setText(AnnotatedString(link!!))
                        controller.message = "Ссылка для «${country.name}» скопирована: $link"
                    }, enabled = link != null, size = 12.sp)
                    if (leader != null) {
                        Btn("×", { controller.unbind(country.name) }, color = Palette.panel, textColor = Palette.red, size = 12.sp)
                    }
                }
            }
            if (bot?.username == null) Txt("Ссылки появятся после первого подключения бота", color = Palette.dim, size = 12.sp)
        }
    }
}

private val stopped = MutableStateFlow<BotStatus>(BotStatus.Stopped)
