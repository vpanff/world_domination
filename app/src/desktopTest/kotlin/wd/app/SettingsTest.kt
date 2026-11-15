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
