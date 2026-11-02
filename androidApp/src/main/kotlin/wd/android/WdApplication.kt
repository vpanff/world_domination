package wd.android

import android.app.Application
import android.content.Intent
import kotlinx.coroutines.MainScope
import wd.app.FileStorage
import wd.app.HostSession
import java.io.File

/** Партия и бот живут на уровне приложения: закрытие экрана их не останавливает. */
class WdApplication : Application() {
    lateinit var session: HostSession
        private set

    override fun onCreate() {
        super.onCreate()
        session = HostSession(FileStorage(File(filesDir, "saves")), FileStorage(File(filesDir, "config")), MainScope())
        session.onBotRunningChanged = { running ->
            val intent = Intent(this, BotService::class.java)
            if (running) startForegroundService(intent) else stopService(intent)
        }
    }
}
