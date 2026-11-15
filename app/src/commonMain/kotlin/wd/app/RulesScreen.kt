package wd.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.*
import wd.core.Rules
import wd.core.validateRules

private val ruleJson = Json { encodeDefaults = true }
private val ruleLabels = linkedMapOf(
    "incomeByLevel" to "Доход городов по уровням, через запятую",
    "developmentByLevel" to "Развитие городов по уровням, через запятую",
    "developmentRounding" to "Шаг округления развития",
    "developmentFirstTurn" to "Первый ход с докладом",
    "developmentInterval" to "Интервал докладов в ходах",
    "startBalance" to "Начальный баланс",
    "startBombs" to "Начальные бомбы",
    "startEcology" to "Начальная экология",
    "maxEcology" to "Максимальная экология",
    "costUpgrade" to "Стоимость улучшения города",
    "costShield" to "Стоимость щита",
    "costResearch" to "Стоимость исследования",
    "costProduce" to "Стоимость бомбы",
    "costEcology" to "Стоимость восстановления экологии",
    "costLaunch" to "Стоимость запуска бомбы",
    "ecologyResearch" to "Изменение экологии от исследования",
    "ecologyProduce" to "Изменение экологии от производства",
    "ecologyBoost" to "Восстановление экологии",
    "eventPercent" to "Скидка / потеря дохода от события, %",
    "ecologyVolcano" to "Изменение экологии от вулкана",
    "volcanoMinEcology" to "Минимальная экология для вулкана",
    "eventsEnabled" to "Мировые события",
    "transfersEnabled" to "Переводы денег",
    "ecologyIncomeThresholds" to "Нижние границы экологии, по убыванию",
    "ecologyIncomePercents" to "Доход в % для каждой границы",
)

@Composable
fun RulesScreen(session: HostSession, onBack: () -> Unit) {
    var source by remember { mutableStateOf(session.settings.defaultRules) }
    val values = remember(source) {
        mutableStateMapOf<String, String>().apply {
            ruleJson.encodeToJsonElement(Rules.serializer(), source).jsonObject.forEach { (key, value) ->
                put(key, if (value is JsonArray) value.joinToString(", ") { it.jsonPrimitive.content } else value.jsonPrimitive.content)
            }
        }
    }
    var message by remember { mutableStateOf<String?>(null) }
    fun parse(): Rules {
        val shape = ruleJson.encodeToJsonElement(Rules.serializer(), Rules()).jsonObject
        val result = JsonObject(shape.mapValues { (key, value) ->
            val text = values.getValue(key)
            when {
                value is JsonArray -> JsonArray(text.split(',').map { JsonPrimitive(it.trim().toIntOrNull() ?: error("${ruleLabels[key]}: введите целые числа через запятую")) })
                value.jsonPrimitive.booleanOrNull != null -> JsonPrimitive(text.toBooleanStrict())
                else -> JsonPrimitive(text.toIntOrNull() ?: error("${ruleLabels[key]}: введите целое число"))
            }
        })
        return ruleJson.decodeFromJsonElement(Rules.serializer(), result).also { it.validateRules() }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Txt("ПАРАМЕТРЫ ИГРЫ", bold = true)
        Txt("Сохранённые параметры используются в новых партиях. Экология действий указана для 4 стран и масштабируется при создании партии.")
        Txt("Доход: первая подходящая нижняя граница экологии → соответствующий процент. Итог округляется до ближайших 5.")
        ruleLabels.forEach { (key, label) ->
            val value = values[key] ?: return@forEach
            Column(Modifier.fillMaxWidth()) {
                Txt(label)
                if (value == "true" || value == "false") {
                    Btn(if (value == "true") "Вкл" else "Выкл", { values[key] = (value != "true").toString() })
                } else Field(value, { values[key] = it }, Modifier.fillMaxWidth())
            }
        }
        message?.let { Txt(it, color = Palette.yellow) }
        Btn("Сохранить для новых партий", {
            message = runCatching { session.updateDefaultRules(parse()); "Параметры сохранены" }.getOrElse { it.message ?: "Проверьте значения" }
        })
        val controller = session.controller
        if (controller != null && !controller.state.isHard) {
            Txt("Применить к открытой партии можно при пустой очереди и без события. Баланс, бомбы и уровни сохранятся; стартовые значения действуют только в новых партиях.")
            Btn("Применить к открытой партии", {
                message = runCatching {
                    val rules = parse().forCountries(controller.state.countries.size)
                    require(controller.state.pending.isEmpty() && controller.state.activeEvent == null && !controller.state.isOver) { "Сначала завершите ход" }
                    require(controller.state.countries.flatMap { it.cities }.all { it.level <= rules.maxLevel }) { "В партии есть города выше нового максимального уровня" }
                    require(controller.state.ecology <= rules.maxEcology) { "Максимальная экология ниже текущей" }
                    controller.update { it.copy(rules = rules, ready = emptySet()) }
                    "Параметры открытой партии обновлены"
                }.getOrElse { it.message ?: "Проверьте значения" }
            }, enabled = controller.state.pending.isEmpty() && controller.state.activeEvent == null && !controller.state.isOver)
        }
        Btn("Вернуть стандартные значения", { source = Rules(); values.clear(); ruleJson.encodeToJsonElement(Rules.serializer(), source).jsonObject.forEach { (k,v) -> values[k] = if (v is JsonArray) v.joinToString(", ") { it.jsonPrimitive.content } else v.jsonPrimitive.content }; message = null })
        Btn("Назад", onBack)
    }
}
