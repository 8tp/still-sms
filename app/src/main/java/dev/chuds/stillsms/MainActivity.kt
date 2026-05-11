package dev.chuds.stillsms

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.chuds.stillsms.ui.theme.StillTheme

class MainActivity : ComponentActivity() {

    private var openThreadRequest by mutableStateOf<Long?>(null)
    private var composeRecipientRequest by mutableStateOf<String?>(null)
    private var composePrefillRequest by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )

        consumeLaunchIntent(intent)

        setContent {
            StillTheme {
                StillSmsApp(
                    initialThreadId = openThreadRequest,
                    initialAddress = composeRecipientRequest,
                    initialPrefillBody = composePrefillRequest,
                    onInitialThreadHandled = { openThreadRequest = null },
                    onInitialComposeHandled = {
                        composeRecipientRequest = null
                        composePrefillRequest = null
                    },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeLaunchIntent(intent)
    }

    private fun consumeLaunchIntent(intent: Intent?) {
        val threadId = consumeOpenThreadExtra(intent)
        if (threadId != null) {
            openThreadRequest = threadId
            return
        }

        val composeRecipient = consumeComposeRecipient(intent)
        val composePrefill = consumeComposePrefill(intent)
        if (composeRecipient != null) {
            composeRecipientRequest = composeRecipient
            composePrefillRequest = composePrefill
        }
    }

    private fun consumeOpenThreadExtra(intent: Intent?): Long? {
        if (intent?.action != ACTION_OPEN_THREAD) return null
        val id = intent.getLongExtra(EXTRA_THREAD_ID, -1L)
        intent.action = null
        intent.removeExtra(EXTRA_THREAD_ID)
        return id.takeIf { it > 0 }
    }

    private fun consumeComposeRecipient(intent: Intent?): String? {
        if (intent?.action != ComposeActivity.ACTION_COMPOSE) return null
        val r = intent.getStringExtra(ComposeActivity.EXTRA_RECIPIENT)
        return r?.takeIf { it.isNotBlank() }
    }

    private fun consumeComposePrefill(intent: Intent?): String? {
        if (intent?.action != ComposeActivity.ACTION_COMPOSE) return null
        val body = intent.getStringExtra(ComposeActivity.EXTRA_PREFILL_BODY)
        intent.action = null
        intent.removeExtra(ComposeActivity.EXTRA_RECIPIENT)
        intent.removeExtra(ComposeActivity.EXTRA_PREFILL_BODY)
        return body?.takeIf { it.isNotBlank() }
    }

    companion object {
        const val ACTION_OPEN_THREAD = "dev.chuds.stillsms.action.OPEN_THREAD"
        const val EXTRA_THREAD_ID = "thread_id"
    }
}
