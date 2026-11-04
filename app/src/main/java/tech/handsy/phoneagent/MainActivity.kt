package tech.handsy.phoneagent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import tech.handsy.phoneagent.service.AgentService
import tech.handsy.phoneagent.ui.AgentApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AgentApp()
        }
        handleDebugIntent(intent)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleDebugIntent(intent)
    }

    /** Debug/demo hook: `am start -n ... -a tech.handsy.phoneagent.AUTO_RUN --es goal "..."` */
    private fun handleDebugIntent(intent: android.content.Intent?) {
        val goal = intent?.getStringExtra("goal")?.trim().orEmpty()
        if (intent?.action == "tech.handsy.phoneagent.AUTO_RUN" && goal.isNotEmpty()) {
            AgentService.start(this, goal)
        }
    }
}
