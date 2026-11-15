package wd.core

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

/** Значения по умолчанию подобраны в реальных партиях на столько стран. */
private const val TUNED_FOR_COUNTRIES = 4

/**
 * Числовые параметры игры. Хранятся внутри сохранения, поэтому в будущем их
 * можно будет менять при создании игры (например, под число игроков).
 */
@Serializable
data class Rules(
    val incomeByLevel: List<Int> = listOf(35, 70, 140, 210, 280, 350, 420, 490),
    /** Развитие города ≈ 10 × (уровень + 1)^1,5: каждый следующий уровень даёт больше предыдущего. */
    val developmentByLevel: List<Int> = listOf(10, 28, 52, 80, 112, 147, 185, 226),
    /** Доклад округляет развитие стран, чтобы по нему нельзя было точно восстановить уровни. */
    val developmentRounding: Int = 25,
    val developmentFirstTurn: Int = 3,
    val developmentInterval: Int = 1,

    val startBalance: Int = 1000,
    val startBombs: Int = 0,
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

    /** Скидка или потеря дохода от ивентов, %. */
    val eventPercent: Int = 25,
    val ecologyVolcano: Int = -10,
    /** Супервулкан выпадает только при такой экологии или выше, поэтому не может закончить партию сам. */
    val volcanoMinEcology: Int = 30,

    val eventsEnabled: Boolean = true,
    val transfersEnabled: Boolean = true,
    val ecologyIncomeThresholds: List<Int> = listOf(80, 60, 40, 20, 1),
    val ecologyIncomePercents: List<Int> = listOf(100, 90, 75, 50, 25),
) {
    val maxLevel: Int get() = incomeByLevel.size - 1

    fun cost(type: ActionType): Int = when (type) {
        ActionType.SHIELD -> costShield
        ActionType.UPGRADE -> costUpgrade
        ActionType.RESEARCH -> costResearch
        ActionType.PRODUCE -> costProduce
        ActionType.ECOLOGY -> costEcology
        ActionType.LAUNCH -> costLaunch
        ActionType.TRANSFER -> 0   // сумма у каждого перевода своя, см. GameState.cost(Action)
    }

    fun income(level: Int): Int = incomeByLevel[level]

    fun development(level: Int): Int = developmentByLevel[level]

    /**
     * Правила для партии на [countries] стран. Деньги у каждой страны свои, а экология общая,
     * поэтому масштабируется только она: одно действие меняет её пропорционально 4 / число стран.
     */
    fun forCountries(countries: Int): Rules {
        fun scale(value: Int): Int =
            if (value == 0) 0 else value.sign * (abs(value) * TUNED_FOR_COUNTRIES.toDouble() / countries).roundToInt().coerceAtLeast(1)
        return copy(
            ecologyResearch = scale(ecologyResearch),
            ecologyProduce = scale(ecologyProduce),
            ecologyBoost = scale(ecologyBoost),
            ecologyVolcano = scale(ecologyVolcano),
        )
    }
}

/** Проверка пользовательских правил перед созданием или изменением партии. */
fun Rules.validateRules() {
    require(incomeByLevel.isNotEmpty() && incomeByLevel.size == developmentByLevel.size) { "Доходы и развитие: нужны списки одинаковой ненулевой длины" }
    require((incomeByLevel + developmentByLevel).all { it in 0..1_000_000 }) { "Доходы и развитие должны быть от 0 до 1000000" }
    require(listOf(startBalance, startBombs, costUpgrade, costShield, costResearch, costProduce, costEcology, costLaunch).all { it in 0..1_000_000 }) { "Цены и стартовые ресурсы должны быть от 0 до 1000000" }
    require(maxEcology in 1..100 && startEcology in 0..maxEcology) { "Экология: максимум от 1 до 100, старт от 0 до максимума" }
    require(developmentRounding in 1..1_000_000 && developmentFirstTurn >= 1 && developmentInterval >= 1) { "Параметры докладов должны быть положительными" }
    require(eventPercent in 0..100 && volcanoMinEcology in 0..maxEcology) { "Процент события: 0–100; порог вулкана: в пределах экологии" }
    require(ecologyResearch in -100..0 && ecologyProduce in -100..0 && ecologyVolcano in -100..0 && ecologyBoost in 0..100) { "Потери экологии: от −100 до 0; восстановление: от 0 до 100" }
    require(ecologyIncomeThresholds.isNotEmpty() && ecologyIncomeThresholds.size == ecologyIncomePercents.size && ecologyIncomeThresholds.last() == 1 && ecologyIncomeThresholds.all { it in 1..100 } && ecologyIncomeThresholds.zipWithNext().all { (a,b) -> a > b }) { "Пороги дохода: убывающий список от 1 до 100, последний порог 1" }
    require(ecologyIncomePercents.all { it in 0..100 } && ecologyIncomePercents.zipWithNext().all { (a,b) -> a >= b }) { "Проценты дохода: убывающий список от 0 до 100" }
}
