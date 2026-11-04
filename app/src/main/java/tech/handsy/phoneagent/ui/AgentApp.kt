package tech.handsy.phoneagent.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import tech.handsy.phoneagent.R
import tech.handsy.phoneagent.agent.AgentBus
import tech.handsy.phoneagent.store.SettingsStore

@Composable
fun AgentApp() {
    val context = LocalContext.current
    val settings = remember { SettingsStore(context) }
    var tab by remember { mutableIntStateOf(if (settings.isConfigured()) 0 else 2) }
    var detailRunId by remember { mutableStateOf<String?>(null) }
    val status by AgentBus.status.collectAsState()

    Scaffold(
        containerColor = Color.Transparent,
        bottomBar = {
            NavigationBar(containerColor = Color(0xF0141826)) {
                NavigationBarItem(
                    selected = tab == 0 && detailRunId == null,
                    onClick = { tab = 0; detailRunId = null },
                    icon = { Icon(AppIcons.SmartToy, null) },
                    label = { Text("任务") },
                )
                NavigationBarItem(
                    selected = tab == 1 && detailRunId == null,
                    onClick = { tab = 1; detailRunId = null },
                    icon = { Icon(AppIcons.History, null) },
                    label = { Text("历史") },
                )
                NavigationBarItem(
                    selected = tab == 2 && detailRunId == null,
                    onClick = { tab = 2; detailRunId = null },
                    icon = { Icon(Icons.Filled.Settings, null) },
                    label = { Text("设置") },
                )
            }
        },
    ) { pad ->
        Box(Modifier.fillMaxSize()) {
            Image(
                painter = painterResource(R.drawable.bg_main),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(pad),
            ) {
                when {
                    detailRunId != null -> RunDetailScreen(detailRunId!!, onBack = { detailRunId = null })
                    tab == 0 -> TaskScreen(
                        settings = settings,
                        status = status,
                        onOpenSetup = { tab = 2 },
                        onOpenWorkflows = { tab = 1 },
                        onOpenRun = { detailRunId = it },
                    )
                    tab == 1 -> HistoryScreen(settings = settings, onOpenRun = { detailRunId = it })
                    else -> SetupScreen(settings, onDone = { tab = 0 })
                }
            }
        }
    }
}
