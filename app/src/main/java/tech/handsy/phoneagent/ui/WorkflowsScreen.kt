package tech.handsy.phoneagent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import tech.handsy.phoneagent.service.AgentService
import tech.handsy.phoneagent.store.SettingsStore
import tech.handsy.phoneagent.store.WorkflowsStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val wfDate = SimpleDateFormat("M月d日 HH:mm", Locale.CHINA)

@Composable
fun WorkflowsScreen(
    settings: SettingsStore,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val store = remember { WorkflowsStore(context) }
    var workflows by remember { mutableStateOf(store.load()) }
    var showNew by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<WorkflowsStore.Workflow?>(null) }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { showNew = true }) {
                Icon(Icons.Filled.Add, "新建工作流")
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Spacer(Modifier.height(14.dp))
            Text(
                "工作流",
                style = MaterialTheme.typography.headlineLarge,
                modifier = Modifier.padding(horizontal = 18.dp),
            )
            Text(
                "保存的任务流程,点 ▶ 一键重跑",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp),
            )
            if (workflows.isEmpty()) {
                Spacer(Modifier.weight(1f))
                Text(
                    "还没有工作流\n跑完一个任务后点「存为工作流」,或点右下角 ➕ 新建",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(18.dp),
                )
                Spacer(Modifier.weight(1f))
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 18.dp, end = 18.dp, top = 6.dp, bottom = 90.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(workflows, key = { it.id }) { wf ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(wf.name, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    wf.goal,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 3,
                                )
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        "${wfDate.format(Date(wf.createdAt))} · 跑过 ${wf.runCount} 次" +
                                            (if (wf.lastStatus.isNotBlank()) " · ${wf.lastStatus}" else ""),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Row {
                                        IconButton(onClick = {
                                            AgentService.start(context, wf.goal)
                                            store.markRun(wf.id, "已启动")
                                            workflows = store.load()
                                        }) {
                                            Icon(
                                                Icons.Filled.PlayArrow,
                                                "运行",
                                                tint = MaterialTheme.colorScheme.primary,
                                            )
                                        }
                                        IconButton(onClick = { editTarget = wf }) {
                                            Icon(
                                                Icons.Filled.Edit,
                                                "编辑",
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        IconButton(onClick = {
                                            store.delete(wf.id)
                                            workflows = store.load()
                                        }) {
                                            Icon(
                                                Icons.Filled.Delete,
                                                "删除",
                                                tint = MaterialTheme.colorScheme.error,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // 新建 / 编辑对话框
    val editing = editTarget
    if (showNew || editing != null) {
        var name by remember(showNew, editing?.id) { mutableStateOf(editing?.name.orEmpty()) }
        var goal by remember(showNew, editing?.id) { mutableStateOf(editing?.goal.orEmpty()) }
        AlertDialog(
            onDismissRequest = { showNew = false; editTarget = null },
            title = { Text(if (editing == null) "新建工作流" else "编辑工作流") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("名称") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = goal,
                        onValueChange = { goal = it },
                        label = { Text("任务内容") },
                        minLines = 4,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (goal.isNotBlank()) {
                        val wf = if (editing == null) store.save(name, goal) else {
                            editing.name = name.ifBlank { editing.name }
                            editing.goal = goal.trim()
                            store.persistUpdate(editing)
                            editing
                        }
                        store.markRun(wf.id, wf.lastStatus.ifBlank { "就绪" })
                        workflows = store.load()
                    }
                    showNew = false; editTarget = null
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { showNew = false; editTarget = null }) { Text("取消") }
            },
        )
    }
}
