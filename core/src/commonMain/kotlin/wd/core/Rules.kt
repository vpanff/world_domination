package wd.core

import kotlinx.serialization.Serializable

/**
 * Числовые параметры игры. Хранятся внутри сохранения, поэтому в будущем их
 * можно будет менять при создании игры (например, под число игроков).
 */
@Serializable
data class Rules(
    val incomeByLevel: List<Int> = listOf(35, 70, 140, 210, 280, 350, 420, 490),
    /** Очки города = уровень² × scoreK. */
    val scoreK: Int = 10,

    val startBalance: Int = 1000,
    val startBombs: Int = 1,
    val startEcology: Int = 100,
    val maxEcology: Int = 100,

    val costUpgrade: Int = 150,
    val costShield: Int = 250,
    val costResearch: Int = 500,
    val costProduce: Int = 150,
    val costEcology: Int = 200,
    val costLaunch: Int = 0,

    val ecologyResearch: Int = -5,
    val ecologyProduce: Int = -4,
    val ecologyBoost: Int = 20,
) {
    val maxLevel: Int get() = incomeByLevel.size - 1

    fun cost(type: ActionType): Int = when (type) {
        ActionType.SHIELD -> costShield
        ActionType.UPGRADE -> costUpgrade
        ActionType.RESEARCH -> costResearch
        ActionType.PRODUCE -> costProduce
        ActionType.ECOLOGY -> costEcology
        ActionType.LAUNCH -> costLaunch
    }

    fun income(level: Int): Int = incomeByLevel[level]

    fun score(level: Int): Int = level * level * scoreK
}
