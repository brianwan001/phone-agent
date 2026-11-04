package tech.handsy.phoneagent.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tech.handsy.phoneagent.store.RunRecord
import tech.handsy.phoneagent.store.RunStore
import tech.handsy.phoneagent.store.StepRecord

private val dateFormat = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RunsScreen(onOpen: (String) -> Unit) {
    val context = LocalContext.current
    val store = remember { RunStore(context) }
    var runs by remember { mutableStateOf<List<RunRecord>>(emptyList()) }
    var refresh by remember { mutableStateOf(0) }
    LaunchedEffect(refresh) {
        runs = withContext(Dispatchers.IO) { store.listRuns() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("运行记录") },
                actions = {
                    IconButton(onClick = { refresh++ }) {
                        Icon(AppIcons.Refresh, "Refresh")
                    }
                },
            )
        },
    ) { padding ->
        if (runs.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("No runs yet")
            }
        } else {
            LazyColumn(
                modifier = Modifier.padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(runs, key = { it.id }) { run ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpen(run.id) },
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(run.goal, style = MaterialTheme.typography.bodyLarge, maxLines = 2)
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(
                                    run.status,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = when (run.status) {
                                        "done" -> Color(0xFF4CAF50)
                                        "failed", "stopped" -> MaterialTheme.colorScheme.error
                                        else -> MaterialTheme.colorScheme.primary
                                    },
                                )
                                Text(dateFormat.format(Date(run.startedAt)), style = MaterialTheme.typography.labelMedium)
                                Text("${run.steps.size} steps", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RunDetailScreen(runId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { RunStore(context) }
    var run by remember { mutableStateOf<RunRecord?>(null) }
    LaunchedEffect(runId) {
        run = withContext(Dispatchers.IO) { store.load(runId) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(run?.goal ?: "Run", maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                actions = {
                    IconButton(onClick = {
                        val record = run ?: return@IconButton
                        val main = android.os.Handler(android.os.Looper.getMainLooper())
                        Thread {
                            try {
                                val zip = store.exportZip(record)
                                val uri = FileProvider.getUriForFile(
                                    context,
                                    "tech.handsy.phoneagent.files",
                                    zip,
                                )
                                val send = Intent(Intent.ACTION_SEND)
                                    .setType("application/zip")
                                    .putExtra(Intent.EXTRA_STREAM, uri)
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                main.post {
                                    try {
                                        context.startActivity(Intent.createChooser(send, "Export run"))
                                    } catch (e: Exception) {
                                        Toast.makeText(context, e.message, Toast.LENGTH_SHORT).show()
                                    }
                                }
                            } catch (e: Exception) {
                                main.post { Toast.makeText(context, e.message, Toast.LENGTH_SHORT).show() }
                            }
                        }.start()
                    }) { Icon(Icons.Filled.Share, "Export trace") }
                },
            )
        },
    ) { padding ->
        val record = run
        if (record == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("Run not found")
            }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "${record.status} · ${record.steps.size} steps · ${record.model}\n" +
                        (if (record.result.isNotBlank()) "Result: ${record.result}" else ""),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            items(record.steps, key = { it.index }) { step ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                            Text("Step ${step.index + 1}", style = MaterialTheme.typography.titleSmall)
                            ChangedBadge(step.changed)
                        }
                        step.screenshot?.let { name -> StepImage(store.screenshotFile(record, name)) }
                        if (step.think.isNotBlank()) {
                            Text(step.think, style = MaterialTheme.typography.bodySmall)
                        }
                        step.actions.forEach { line ->
                            Text(
                                line,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChangedBadge(changed: Boolean?) {
    if (changed == null) return
    Text(
        if (changed) "changed" else "no change",
        style = MaterialTheme.typography.labelSmall,
        color = if (changed) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error,
    )
}

@Composable
private fun StepImage(file: File) {
    val bitmap by produceState<ImageBitmap?>(null, file) {
        value = withContext(Dispatchers.IO) {
            BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()
        }
    }
    val bmp = bitmap
    if (bmp != null) {
        Image(
            bitmap = bmp,
            contentDescription = null,
            modifier = Modifier.fillMaxWidth(),
            contentScale = ContentScale.FillWidth,
        )
    } else {
        Spacer(Modifier.fillMaxWidth().height(120.dp))
    }
}
