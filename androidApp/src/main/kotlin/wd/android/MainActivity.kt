package wd.android

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import wd.app.App
import wd.app.FileStorage
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Ведущий держит телефон открытым всю партию — экран не гаснет
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val storage = FileStorage(File(filesDir, "saves"))
        setContent {
            App(storage, backHandler = { enabled, onBack -> BackHandler(enabled, onBack) })
        }
    }
}
