package wd.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import wd.core.*
import kotlin.test.*

class SettingsTest {
    private class Memory : Storage {
        val files = mutableMapOf<String, String>()
        override fun read(name: String) = files[name]
        override fun write(name: String, text: String) { files[name] = text }
        override fun delete(name: String) { files.remove(name) }
        override fun list() = files.keys.map { SaveInfo(it, 0) }
    }

    @Test
    fun oldHardDefaultsUpgradeWithoutChangingSavesOrCustomBalance() {
        val config = Memory().apply { files["settings"] = """{"defaultHardRules":{"rounds":8,"techEcologyLoss":2.0,"bombLaunchEcologyLoss":2.5,"ecologyPer100":4.0,"unAid":400}}""" }
        val saves = Memory()
        val old = newHardGame(HardRules(techEcologyLoss = 2.0, bombLaunchEcologyLoss = 2.5, ecologyPer100 = 4.0, unAid = 400))
        saves.files["autosave"] = old.toJson()
        val session = HostSession(saves, config, CoroutineScope(Dispatchers.Unconfined))
        assertEquals(8, session.settings.defaultHardRules.rounds)
        assertEquals(3.6, session.settings.defaultHardRules.techEcologyLoss)
        assertEquals(7.2, session.settings.defaultHardRules.ecologyPer100)
        assertEquals(450, session.settings.defaultHardRules.unAid)
        assertEquals(old, session.loadAutosave())
        val customConfig = Memory().apply { files["settings"] = """{"defaultHardRules":{"techEcologyLoss":2.0,"bombLaunchEcologyLoss":2.5,"ecologyPer100":5.0,"unAid":400}}""" }
        val custom = HostSession(Memory(), customConfig, CoroutineScope(Dispatchers.Unconfined))
        assertEquals(5.0, custom.settings.defaultHardRules.ecologyPer100)
        assertEquals(400, custom.settings.defaultHardRules.unAid)
    }

    @Test
    fun hardDefaultsAndGameModePersistIndependently() {
        val config = Memory()
        val saves = Memory()
        val session = HostSession(saves, config, CoroutineScope(Dispatchers.Unconfined))
        val hard = HardRules(bombCost = 600, rounds = 7)
        session.updateDefaultHardRules(hard)
        session.open(newHardGame(hard))
        session.controller!!.add(ActionType.INVEST, "Великобритания", CityRef("Великобритания", "Лондон"), amount = 123)
        val restored = HostSession(saves, config, CoroutineScope(Dispatchers.Unconfined))
        assertEquals(hard, restored.settings.defaultHardRules)
        assertEquals(150, restored.settings.defaultRules.costProduce)
        val state = restored.loadAutosave()!!
        assertEquals(GameMode.HARD, state.mode)
        assertEquals(123, state.pending.single().amount)
        assertEquals(hard, state.hardRules)
    }

    @Test
    fun defaultRulesPersistWithoutChangingOpenGame() {
        val config = Memory()
        val saves = Memory()
        val session = HostSession(saves, config, CoroutineScope(Dispatchers.Unconfined))
        session.open(newGame())
        val custom = Rules(costProduce = 225, startBalance = 1500, developmentInterval = 3)
        session.updateDefaultRules(custom)
        val restored = HostSession(saves, config, CoroutineScope(Dispatchers.Unconfined))
        assertEquals(custom, restored.settings.defaultRules)
        assertEquals(150, session.state!!.rules.costProduce)
        assertEquals(150, restored.loadAutosave()!!.rules.costProduce)
        val new = newGame(GameSetup(rules = restored.settings.defaultRules))
        assertEquals(225, new.rules.costProduce)
        assertEquals(1500, new.countries.first().balance)
    }

    @Test
    fun manualEventIsSavedAndCanBeUndone() {
        val session = HostSession(Memory(), Memory(), CoroutineScope(Dispatchers.Unconfined))
        session.open(newGame())
        val controller = session.controller!!
        controller.triggerEvent(WorldEventType.BUILDING_BOOM)
        assertEquals(WorldEventType.BUILDING_BOOM, session.loadAutosave()!!.activeEvent!!.type)
        controller.triggerEvent(WorldEventType.CRISIS)
        assertEquals(WorldEventType.BUILDING_BOOM, controller.state.activeEvent!!.type)
        controller.undo()
        assertNull(session.loadAutosave()!!.activeEvent)
    }
}
