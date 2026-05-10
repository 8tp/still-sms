package dev.chuds.stillsms

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.chuds.stillsms.ui.theme.StillTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )

        val initialThreadId = consumeOpenThreadExtra(intent)

        setContent {
            StillTheme {
                StillSmsApp(initialThreadId = initialThreadId)
            }
        }
    }

    private fun consumeOpenThreadExtra(intent: Intent?): Long? {
        if (intent?.action != ACTION_OPEN_THREAD) return null
        val id = intent.getLongExtra(EXTRA_THREAD_ID, -1L)
        intent.action = null
        intent.removeExtra(EXTRA_THREAD_ID)
        return id.takeIf { it > 0 }
    }

    companion object {
        const val ACTION_OPEN_THREAD = "dev.chuds.stillsms.action.OPEN_THREAD"
        const val EXTRA_THREAD_ID = "thread_id"
    }
}
