package tech.handsy.phoneagent.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import tech.handsy.phoneagent.service.AgentService
import tech.handsy.phoneagent.store.RunStore
import tech.handsy.phoneagent.store.SettingsStore
import tech.handsy.phoneagent.store.WorkflowsStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val fmt = SimpleDateFormat("M月d日 HH:mm", Locale.CHINA)

/** 历史页 = 保存的工作流(一键重跑) + 运行记录(点开回放)。 */
@Composable
fun HistoryScreen(
    settings: SettingsStore,
    onOpenRun: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val wfStore = remember { WorkflowsStore(context) }
    val runStore = remember { RunStore(context) }
    var workflows by remember { mutableStateOf(wfStore.load()) }
    var runs by remember { mutableStateOf(runStore.listRuns()) }
    LaunchedEffect(Unit) {
        while (true) {
            workflows = wfStore.load()
            runs = runStore.listRuns()
            delay(2000)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Spacer(Modifier.height(14.dp))
        Text(
            "历史",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            modifier = Modifier.padding(horizontal = 18.dp),
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (workflows.isEmpty() && runs.isEmpty()) {
                item {
                    Text(
                        "还没有历史。跑一个任务试试。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 40.dp),
                    )
                }
            }
            if (workflows.isNotEmpty()) {
                item {
                    Text(
                        "⚡ 工作流 · 点 ▶ 一键重跑",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
                items(workflows, key = { "wf-" + it.id }) { wf ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(wf.name, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    wf.goal,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                )
                                Text(
                                    "跑过 ${wf.runCount} 次",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = {
                                AgentService.start(context, wf.goal)
                                wfStore.markRun(wf.id, "已启动")
                                workflows = wfStore.load()
                            }) {
                                Icon(
                                    Icons.Filled.PlayArrow,
                                    "运行",
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                            IconButton(onClick = {
                                wfStore.delete(wf.id)
                                workflows = wfStore.load()
                            }) {
                                Icon(
                                    Icons.Filled.Delete,
                                    "删除",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                item {
                    Text(
                        "📜 运行记录 · 点开回放",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
                    )
                }
            }
            items(runs, key = { "run-" + it.id }) { run ->
                Card(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onOpenRun(run.id) },
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(run.goal, style = MaterialTheme.typography.bodyLarge, maxLines = 2)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                run.status,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (run.status == "done") {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                            Text(fmt.format(Date(run.startedAt)), style = MaterialTheme.typography.labelMedium)
                            Text("${run.steps.size} 步", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }
}
