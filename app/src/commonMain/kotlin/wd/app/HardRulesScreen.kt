package wd.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.*
import wd.core.HardRules

private val hardJson = Json { encodeDefaults = true }
private val hardLabels = mapOf(
    "rounds" to "Раундов", "startBalance" to "Начальный бюджет", "startLife" to "Начальный уровень жизни", "startDevelopment" to "Начальное развитие", "startEcology" to "Начальная экология",
    "incomeLifeWeight" to "Вес жизни в доходе", "incomeDevelopmentWeight" to "Вес развития в доходе", "incomeScale" to "Множитель дохода",
    "lifePer100" to "Жизнь за 100 вложенных", "developmentPer100" to "Развитие за 100 вложенных", "investmentFalloff" to "Убывающая отдача (0–0.95)",
    "techCost" to "Цена технологии", "bombCost" to "Цена бомбы", "shieldCost" to "Цена щита",
    "shieldHitLifeLoss" to "Потеря жизни при попадании в щит", "techLifePenalty" to "Потеря жизни от технологии", "bombBuildLifePenalty" to "Потеря жизни от одной бомбы",
    "techEcologyLoss" to "Потеря экологии от технологии", "bombBuildEcologyLoss" to "Потеря экологии от производства", "bombLaunchEcologyLoss" to "Потеря экологии от запуска",
    "ecologyThreshold" to "Порог экологии", "ecologyLifeLossPerPoint" to "Потеря жизни за пункт ниже порога", "ecologyPer100" to "Экология за 100 вложенных",
    "ecologyIncomeBonus" to "Бонус к доходу при 100% экологии, %", "ecologyIncomePenaltyPerPoint" to "Потеря дохода за пункт ниже порога, %",
    "sanctionPercent" to "Снижение дохода одной санкцией, %", "sanctionCapPercent" to "Предел санкций, %",
    "rebuildCost" to "Цена восстановления", "rebuildLife" to "Жизнь после восстановления", "rebuildDevelopment" to "Развитие после восстановления", "rebuildShield" to "Восстановление со щитом",
    "rebuildDiscountWhenEliminated" to "Скидка стране без городов, %", "unAid" to "Помощь ООН за раунд стране без городов",
    "maxBombsBuiltPerRound" to "Лимит производства (0 — без ограничения)", "launchLimit" to "Ограничить запуски", "maxLaunchesPerRound" to "Лимит запусков", "finalRoundStrikes" to "Разрешить удары в последнем раунде",
    "bombUpkeep" to "Содержание бомбы за раунд", "developmentDecay" to "Износ развития за раунд, %", "wealthTaxPer10" to "Налог за каждые 10 развития отрыва, %", "wealthTaxCapPercent" to "Предел налога, %",
)

@Composable
fun HardRulesScreen(session: HostSession, onBack: () -> Unit) {
    val shape = remember { hardJson.encodeToJsonElement(HardRules.serializer(), HardRules()).jsonObject }
    val values = remember { mutableStateMapOf<String, String>().apply {
        hardJson.encodeToJsonElement(HardRules.serializer(), session.settings.defaultHardRules).jsonObject.forEach { (k,v) -> put(k, v.jsonPrimitive.content) }
    } }
    var message by remember { mutableStateOf<String?>(null) }
    fun parse(): HardRules {
        val data = JsonObject(shape.mapValues { (key, value) ->
            val text = values.getValue(key).replace(',', '.')
            if(value.jsonPrimitive.booleanOrNull != null) JsonPrimitive(text.toBooleanStrict())
            else {
                val number = text.toDoubleOrNull() ?: error("${hardLabels[key]}: введите число")
                require(number.isFinite()) { "${hardLabels[key]}: некорректное число" }
                if (value.jsonPrimitive.content.contains('.')) JsonPrimitive(number)
                else JsonPrimitive(text.toIntOrNull() ?: error("${hardLabels[key]}: требуется целое число"))
            }
        })
        return hardJson.decodeFromJsonElement(HardRules.serializer(), data).also { it.validate() }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Txt("ПАРАМЕТРЫ ХАРД-РЕЖИМА", bold = true)
        Txt("5 стран по 4 города. Экология не масштабируется по числу стран. Денежные суммы — целые; показатели жизни и экологии могут быть дробными.")
        shape.forEach { (key, value) ->
            Txt(hardLabels[key] ?: key)
            if(value.jsonPrimitive.booleanOrNull != null) Btn(if(values[key] == "true") "Вкл" else "Выкл", { values[key] = (values[key] != "true").toString() })
            else Field(values[key].orEmpty(), { values[key] = it }, Modifier.fillMaxWidth())
        }
        message?.let { Txt(it, color = Palette.yellow) }
        Btn("Сохранить для новых партий", { message = runCatching { session.updateDefaultHardRules(parse()); "Сохранено" }.getOrElse { it.message } })
        val controller = session.controller
        if(controller != null && controller.state.isHard) {
            Txt("Применение к открытой партии сохраняет текущие города и ресурсы. Стартовые параметры действуют только при создании партии.")
            Btn("Применить к открытой партии", {
                message = runCatching {
                    val rules = parse()
                    require(!controller.state.isOver && controller.state.pending.isEmpty()) { "Сначала завершите раунд" }
                    require(rules.rounds >= controller.state.turn) { "Число раундов меньше текущего" }
                    controller.update { it.copy(hardRules = rules, ready = emptySet()) }
                    "Применено"
                }.getOrElse { it.message }
            }, enabled = !controller.state.isOver && controller.state.pending.isEmpty())
        }
        Btn("Стандартные значения", { hardJson.encodeToJsonElement(HardRules.serializer(), HardRules()).jsonObject.forEach { (k,v) -> values[k] = v.jsonPrimitive.content }; message = null })
        Btn("Назад", onBack)
    }
}
