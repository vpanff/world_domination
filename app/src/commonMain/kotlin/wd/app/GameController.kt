package wd.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import wd.core.ActionError
import wd.core.ActionType
import wd.core.CityRef
import wd.core.GameState
import wd.core.addAction
import wd.core.cancelAction
import wd.core.describe
import wd.core.resolveTurn
import wd.core.triggerRandomEvent
import wd.core.validate

private const val HISTORY_LIMIT = 100

/**
 * Состояние партии на экране ведущего: текущее состояние, история для отмены, режим атаки.
 * [onChange] получает (старое, новое) состояние после каждого изменения.
 */
class GameController(initial: GameState, private val onChange: (GameState, GameState) -> Unit) {
    var state by mutableStateOf(initial)
        private set

    /** Сообщение об ошибке или результате последней операции. */
    var message by mutableStateOf<String?>(null)

    /** Страна, выбирающая цель для запуска; null — обычный режим. */
    var launchFrom by mutableStateOf<String?>(null)

    var canUndo by mutableStateOf(false)
        private set

    private val history = ArrayDeque<GameState>()

    fun canAdd(type: ActionType, country: String, target: CityRef? = null): Boolean =
        runCatching { state.validate(type, country, target) }.isSuccess

    fun add(type: ActionType, country: String, target: CityRef? = null) =
        change { it.addAction(type, country, target) }

    fun cancel(id: Int) = change { it.cancelAction(id) }

    fun resolve() {
        launchFrom = null
        change { it.resolveTurn() }
    }

    fun triggerRandomEvent() {
        change { it.triggerRandomEvent() }
        state.activeEvent?.let { message = it.describe() }
    }

    fun unbind(country: String) = change { it.copy(leaders = it.leaders - country, ready = it.ready - country) }

    fun startLaunch(country: String) {
        launchFrom = if (launchFrom == country) null else country
    }

    fun launchAt(target: CityRef) {
        val from = launchFrom ?: return
        launchFrom = null
        add(ActionType.LAUNCH, from, target)
    }

    /** Откат на шаг назад. Привязки игроков к странам не откатываются. */
    fun undo() {
        val previous = history.removeLastOrNull() ?: return
        launchFrom = null
        apply(previous.copy(leaders = state.leaders, invites = state.invites))
        message = "Отменено"
    }

    /** Изменение от бота: ошибка не показывается ведущему, а бросается дальше. */
    fun update(transform: (GameState) -> GameState) {
        val next = transform(state)
        history.addLast(state)
        if (history.size > HISTORY_LIMIT) history.removeFirst()
        apply(next)
    }

    private fun change(transform: (GameState) -> GameState) {
        try {
            update(transform)
            message = null
        } catch (e: ActionError) {
            message = e.message
        }
    }

    private fun apply(next: GameState) {
        val old = state
        state = next
        canUndo = history.isNotEmpty()
        onChange(old, next)
    }
}
