package wd.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Событие хода. Ведущий видит все события, игрок — только публичные
 * и адресованные его стране (см. [visibleTo]).
 */
@Serializable
sealed interface GameEvent {
    /** Страна, которой видно событие; null — видно всем. */
    val audience: String?

    fun visibleTo(country: String): Boolean = audience == null || audience == country
}

// ─── Личные события ──────────────────────────────────────────────────────────

@Serializable @SerialName("shield_built")
data class ShieldBuilt(val country: String, val city: String) : GameEvent {
    override val audience get() = country
}

@Serializable @SerialName("city_upgraded")
data class CityUpgraded(val country: String, val city: String, val level: Int) : GameEvent {
    override val audience get() = country
}

@Serializable @SerialName("research_started")
data class ResearchStarted(val country: String) : GameEvent {
    override val audience get() = country
}

@Serializable @SerialName("bomb_ordered")
data class BombOrdered(val country: String) : GameEvent {
    override val audience get() = country
}

@Serializable @SerialName("ecology_funded")
data class EcologyFunded(val country: String) : GameEvent {
    override val audience get() = country
}

enum class LaunchOutcome { DESTROYED, SHIELD_HIT, ALREADY_DESTROYED }

/** Результат запуска знает только атакующий. */
@Serializable @SerialName("launch")
data class LaunchResult(val attacker: String, val target: CityRef, val outcome: LaunchOutcome) : GameEvent {
    override val audience get() = attacker
}

/** Владелец узнаёт, что щит сработал, но не знает, кто ударил. */
@Serializable @SerialName("shield_hit")
data class ShieldHit(val target: CityRef) : GameEvent {
    override val audience get() = target.country
}

@Serializable @SerialName("tech_ready")
data class TechReady(val country: String) : GameEvent {
    override val audience get() = country
}

@Serializable @SerialName("bombs_ready")
data class BombsReady(val country: String, val count: Int, val total: Int) : GameEvent {
    override val audience get() = country
}

@Serializable @SerialName("income")
data class IncomeReceived(val country: String, val amount: Int, val balance: Int) : GameEvent {
    override val audience get() = country
}

@Serializable @SerialName("action_failed")
data class ActionFailed(val country: String, val reason: String) : GameEvent {
    override val audience get() = country
}

// ─── Публичные события ───────────────────────────────────────────────────────

@Serializable @SerialName("city_destroyed")
data class CityDestroyed(val target: CityRef) : GameEvent {
    override val audience: String? get() = null
}

@Serializable @SerialName("ecology_changed")
data class EcologyChanged(val from: Int, val to: Int) : GameEvent {
    override val audience: String? get() = null
}

@Serializable @SerialName("world_event")
data class WorldEventHappened(val event: WorldEvent) : GameEvent {
    override val audience: String? get() = null
}

@Serializable @SerialName("game_over")
data class GameOver(val result: GameResult) : GameEvent {
    override val audience: String? get() = null
}
