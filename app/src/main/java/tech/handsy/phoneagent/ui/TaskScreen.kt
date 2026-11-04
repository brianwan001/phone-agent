package tech.handsy.phoneagent.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import tech.handsy.phoneagent.ui.AppIcons.Mic
import tech.handsy.phoneagent.ui.AppIcons.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import tech.handsy.phoneagent.accessibility.AgentAccessibilityService
import tech.handsy.phoneagent.agent.AgentStatus
import tech.handsy.phoneagent.service.AgentService
import tech.handsy.phoneagent.store.RunStore
import tech.handsy.phoneagent.store.SettingsStore
import java.io.File

// ── 品牌色 ──
private val GBlue = Color(0xFF4F7CFF)
private val GPurple = Color(0xFF9B5CFF)
private val Brand = Brush.linearGradient(listOf(GBlue, GPurple))
private val Green = Color(0xFF30D158)
private val Amber = Color(0xFFFFB74D)

// ── 深空背景 + 双色光晕 ──
@Composable
private fun DeepBackground() {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF07090F)),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(Color(0xFF2A3B8F).copy(alpha = 0.35f), Color.Transparent),
                        center = androidx.compose.ui.geometry.Offset(0f, 0f),
                        radius = 1400f,
                    ),
                ),
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(Color(0xFF6B3FA0).copy(alpha = 0.30f), Color.Transparent),
                        center = androidx.compose.ui.geometry.Offset(1100f, 2100f),
                        radius = 1600f,
                    ),
                ),
        )
    }
}

// ── 玻璃卡容器 ──
@Composable
private fun StatusDot(color: Color) {
    Box(Modifier.size(10.dp).background(color, CircleShape))
}

@Composable
private fun GlassCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(26.dp))
            .border(1.dp, Color.White.copy(alpha = 0.09f), RoundedCornerShape(26.dp))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) { content() }
}

/** 常用工作流胶囊。 */
private data class Bento(
    val ch: String, val title: String,
    val color: Color, val goal: String, val more: Boolean = false,
)

private val BENTO_ITEMS = listOf(
    Bento("活", "找活动", Color(0xFFFF7A45), "打开小红书,搜索「深圳创业沙龙」,打开第一篇笔记,告诉我标题、作者和大概内容,然后 done"),
    Bento("酒", "酒店建联", Color(0xFF34C759), "在小红书搜索「东莞 酒店」,打开第一篇笔记,点开评论框,输入「请问这个酒店还有空房吗?想了解一下详情」,确认文字已显示,但不要点发送,然后 done"),
    Bento("联", "私信建联", Color(0xFF5856D6), "在小红书搜索「深圳组局」,打开一个组局笔记,进作者主页,点私信,输入「你好,我对你们的组局很感兴趣,想了解一下细节」打为草稿,不要发送,然后 done"),
    Bento("抖", "抖音调研", Color(0xFF17171B), "打开抖音,搜索「深圳创业沙龙」,看第一条相关内容,告诉我标题和作者,然后 done"),
    Bento("招", "招聘调研", Color(0xFF00C7BE), "在小红书搜索「深圳创业公司 招聘」,打开第一篇笔记,告诉我公司名和岗位,然后 done"),
    Bento("咖", "找咖啡馆", Color(0xFFBF5AF2), "在小红书搜索「深圳 安静 咖啡馆」,打开第一篇笔记,告诉我店名和位置,然后 done"),
    Bento("展", "找展会", Color(0xFFFF9500), "在小红书搜索「深圳 创业 展会」,打开第一篇笔记,告诉我展会名称和时间,然后 done"),
    Bento("美", "美团速览", Color(0xFFFF2D55), "launch 美团,等 App 打开后告诉我首页推荐了什么内容,然后 done"),
    Bento("算", "计算器", Color(0xFFFF9500), "打开计算器,计算 12×34,读出屏幕上的结果,然后 done"),
    Bento("W", "Wi-Fi", Color(0xFF0A84FF), "打开设置,进入 WLAN(Wi-Fi)设置页面,然后 done"),
    Bento("地", "地图找位", Color(0xFF34C759), "打开高德地图,搜索「深圳 创业空间」,告诉我第一个结果的名字和地址,然后 done"),
    Bento("＋", "更多", Color(0xFF8E8E93), "", more = true),
)

private data class AgentShot(val file: File, val bmp: ImageBitmap?)

@Composable
private fun liveShotLoader(runStore: RunStore, runId: String?): androidx.compose.runtime.State<AgentShot?> =
    produceState<AgentShot?>(null, runId) {
        if (runId == null) return@produceState
        while (true) {
            val f = withContext(Dispatchers.IO) { runStore.latestScreenshotFile(runId) }
            if (f != null && f != value?.file) {
                val bmp = withContext(Dispatchers.IO) {
                    BitmapFactory.decodeFile(f.absolutePath)?.asImageBitmap()
                }
                value = AgentShot(f, bmp)
            }
            delay(1200)
        }
    }

@Composable
fun TaskScreen(
    settings: SettingsStore,
    status: AgentStatus,
    onOpenSetup: () -> Unit,
    onOpenWorkflows: () -> Unit,
    onOpenRun: (String) -> Unit,
) {
    val context = LocalContext.current
    val runStore = remember { RunStore(context) }
    var goal by rememberSaveable { mutableStateOf("") }
    var accessibilityOn by remember { mutableStateOf(AgentAccessibilityService.instance != null) }
    LaunchedEffect(Unit) {
        while (true) {
            accessibilityOn = AgentAccessibilityService.instance != null
            delay(1000)
        }
    }
    val notifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    val running = status is AgentStatus.Running
    val paused = status is AgentStatus.Paused
    val activeRunId = when (status) {
        is AgentStatus.Running -> status.runId
        is AgentStatus.Paused -> status.runId
        else -> null
    }
    val shotState = if (activeRunId != null) liveShotLoader(runStore, activeRunId) else null

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Spacer(Modifier.height(14.dp))

        // ── 顶栏 ──
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    "AI 手机操作员",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color(0xFF7FA4FF),
                )
                Text(
                    "Phone Agent",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White,
                )
            }
            Surface(
                shape = CircleShape,
                color = if (accessibilityOn) Green.copy(alpha = 0.15f)
                else MaterialTheme.colorScheme.error.copy(alpha = 0.15f),
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    StatusDot(if (accessibilityOn) Green else MaterialTheme.colorScheme.error)
                    Text(
                        if (accessibilityOn) "在线" else "离线",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (accessibilityOn) Green else MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        // ── Agent 对话卡 ──
        GlassCard {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    Modifier
                        .size(46.dp)
                        .background(Brand, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("🤖", fontSize = 20.sp)
                }
                Column {
                    Text("Agent", fontWeight = FontWeight.Bold, color = Color.White)
                    when (status) {
                        is AgentStatus.Running -> Text(
                            "执行中 · 第 ${status.step + 1} 步",
                            style = MaterialTheme.typography.labelMedium,
                            color = Green,
                        )
                        is AgentStatus.Paused -> Text(
                            "已暂停 · 第 ${status.step + 1} 步",
                            style = MaterialTheme.typography.labelMedium,
                            color = Amber,
                        )
                        is AgentStatus.Finished -> Text(
                            if (status.ok) "已完成" else "已失败",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (status.ok) Green else MaterialTheme.colorScheme.error,
                        )
                        AgentStatus.Idle -> Text(
                            "待命中 · 给我一个目标",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            when (val s = status) {
                is AgentStatus.Running -> {
                    if (s.lastThink.isNotBlank()) {
                        Text(
                            "“${s.lastThink}”",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFFB9C4D8),
                        )
                    } else {
                        Text("正在看屏幕…", color = Color(0xFF8E97A8))
                    }
                    if (s.lastDo.isNotEmpty()) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color.White.copy(alpha = 0.07f),
                        ) {
                            Text(
                                s.lastDo.joinToString("\n"),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = Color(0xFF9FE870),
                            )
                        }
                    }
                    OutlinedButton(
                        onClick = { AgentService.stop(context) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Icon(AppIcons.Stop, null, tint = Color.White)
                        Spacer(Modifier.width(6.dp))
                        Text("停止执行", color = Color.White)
                    }
                }
                is AgentStatus.Paused -> {
                    Text(
                        s.reason,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFB9C4D8),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = { AgentService.resume(context) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = GBlue, contentColor = Color.White),
                        ) { Text("继续执行") }
                        OutlinedButton(
                            onClick = { AgentService.stop(context) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp),
                        ) { Text("放弃", color = Color.White) }
                    }
                }
                is AgentStatus.Finished -> {
                    Text(
                        (if (s.ok) "✅ " else "❌ ") + s.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFB9C4D8),
                    )
                }
                AgentStatus.Idle -> Unit
            }
        }

        // ── 常用工作流胶囊 ──
        Text(
            "常用工作流 · 点击自动填充",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White,
        )
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BENTO_ITEMS.forEach { t ->
                Surface(
                    onClick = {
                        if (t.more) onOpenWorkflows() else goal = t.goal
                    },
                    enabled = !running,
                    shape = RoundedCornerShape(18.dp),
                    color = t.color,
                ) {
                    Row(
                        Modifier.padding(start = 8.dp, end = 16.dp, top = 7.dp, bottom = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(
                            Modifier
                                .size(26.dp)
                                .background(Color.White.copy(alpha = 0.25f), CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(t.ch, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                        Text(t.title, fontSize = 14.sp, color = Color.White)
                    }
                }
            }
        }

        // ── Agent 实时视角 ──
        if (activeRunId != null && shotState != null) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(24.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.09f), RoundedCornerShape(24.dp)),
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("👁 Agent 视角", style = MaterialTheme.typography.labelMedium, color = Color(0xFFB9C4D8))
                    Box(
                        Modifier
                            .background(Green.copy(alpha = 0.18f), RoundedCornerShape(50))
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    ) {
                        Text(
                            "LIVE",
                            fontSize = 10.sp,
                            color = Green,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                val bmp = shotState.value?.bmp
                if (bmp != null) {
                    Image(
                        bmp,
                        null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 10.dp, end = 10.dp, bottom = 10.dp),
                        contentScale = ContentScale.FillWidth,
                    )
                } else {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(130.dp)
                            .padding(start = 10.dp, end = 10.dp, bottom = 10.dp)
                            .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(16.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("正在截取画面…", color = Color(0xFF8E97A8))
                    }
                }
            }
        }

        // ── 自定义任务 ──
        Text("自定义任务", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White)
        OutlinedTextField(
            value = goal,
            onValueChange = { goal = it },
            modifier = Modifier.fillMaxWidth(),
            enabled = !running,
            minLines = 2,
            shape = RoundedCornerShape(20.dp),
            label = { Text("让 Agent 做什么?", color = Color(0xFF8E97A8)) },
            placeholder = { Text("打字,或点 🎤 说一句话", color = Color(0xFF5A6373)) },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = GBlue,
                unfocusedBorderColor = Color(0xFF2A3244),
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                cursorColor = GBlue,
            ),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            Box(
                Modifier
                    .size(56.dp)
                    .background(Brand, RoundedCornerShape(18.dp))
                    .clickable {
                        // 说完直接执行
                        context.startActivity(
                            Intent(context, tech.handsy.phoneagent.VoiceEntryActivity::class.java),
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(AppIcons.Mic, "语音", tint = Color.White)
            }
            Button(
                onClick = {
                    if (Build.VERSION.SDK_INT >= 33 &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                        PackageManager.PERMISSION_GRANTED
                    ) {
                        notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    AgentService.start(context, goal.trim())
                },
                enabled = !running && goal.isNotBlank() && settings.isConfigured() && accessibilityOn,
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp)
                    .background(Brand, RoundedCornerShape(18.dp)),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Transparent,
                    contentColor = Color.White,
                ),
            ) {
                Icon(Icons.Filled.PlayArrow, null)
                Spacer(Modifier.width(6.dp))
                Text(
                    if (paused) "开始新任务" else "启动 Agent",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        // ── 无障碍恢复 ──
        if (!accessibilityOn) {
            GlassCard {
                Text(
                    "无障碍服务已关闭,Agent 无法操作手机",
                    color = MaterialTheme.colorScheme.error,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            Thread {
                                try {
                                    tech.handsy.phoneagent.accessibility.ShellInput.ensureAccessibility()
                                } catch (_: Exception) {
                                }
                            }.start()
                        },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = GBlue, contentColor = Color.White),
                    ) { Text("⚡ 一键恢复") }
                    OutlinedButton(
                        onClick = {
                            context.startActivity(
                                Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS),
                            )
                        },
                        shape = RoundedCornerShape(12.dp),
                    ) { Text("手动") }
                }
            }
        }
        if (!settings.isConfigured()) {
            Text(
                "模型未配置 — 请先到「设置」填写",
                color = MaterialTheme.colorScheme.error,
            )
        }

        Spacer(Modifier.height(8.dp))
        Text(
            "🧠 GLM-4.5V + GLM-5.3-Flash · 越用越懂你的手机",
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF5A6373),
        )
    }
}
