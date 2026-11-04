package tech.handsy.phoneagent.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import tech.handsy.phoneagent.accessibility.AgentAccessibilityService
import tech.handsy.phoneagent.ai.OpenAIClient
import tech.handsy.phoneagent.store.SettingsStore
import kotlin.concurrent.thread

@Composable
fun SetupScreen(settings: SettingsStore, onDone: () -> Unit) {
    val context = LocalContext.current
    var accessibilityOn by remember { mutableStateOf(AgentAccessibilityService.instance != null) }
    // Poll so the checklist flips to "Enabled" as soon as the service connects.
    LaunchedEffect(Unit) {
        while (true) {
            accessibilityOn = AgentAccessibilityService.instance != null
            delay(1000)
        }
    }

    var endpoint by remember { mutableStateOf(settings.endpoint) }
    var apiKey by remember { mutableStateOf(settings.userApiKey().orEmpty()) }
    var model by remember { mutableStateOf(settings.model) }
    var plannerModel by remember { mutableStateOf(settings.plannerModel) }
    var usePlanner by remember { mutableStateOf(settings.usePlanner) }
    var disableThinking by remember { mutableStateOf(settings.disableThinking) }
    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var fetching by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun fetchModels() {
        fetching = true
        error = null
        thread(start = true) {
            try {
                val list = OpenAIClient(endpoint.trim(), apiKey.trim()).listModels()
                models = list
                if (model.isBlank() && list.isNotEmpty()) model = list.first()
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            } finally {
                fetching = false
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Setup", style = MaterialTheme.typography.headlineSmall)

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "1 · Let me see and touch the screen",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    if (accessibilityOn) {
                        Icon(AppIcons.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
                Text(
                    "Enable “Phone Agent” in Accessibility so the app can take screenshots " +
                        "and perform taps, swipes and text input.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(onClick = {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }) {
                    Text(if (accessibilityOn) "Enabled — manage" else "Open Accessibility settings")
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("2 · Give me a brain", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = endpoint,
                    onValueChange = { endpoint = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Endpoint (OpenAI-compatible)") },
                    placeholder = { Text("https://api.openai.com") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("API key(留空 = 使用内置体验密钥)") },
                    supportingText = { Text("内置密钥默认走 GLM-4V-Flash 免费模型;余额充足时自动升级 GLM-4.5V") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Vision model") },
                    placeholder = { Text("pick from the list or type a model id") },
                    singleLine = true,
                )
                if (models.isNotEmpty()) {
                    Text("From the endpoint:", style = MaterialTheme.typography.labelSmall)
                    Column {
                        models.take(12).forEach { candidate ->
                            AssistChip(
                                onClick = { model = candidate },
                                label = { Text(candidate, fontFamily = FontFamily.Monospace) },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = plannerModel,
                    onValueChange = { plannerModel = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Planner model (optional)") },
                    singleLine = true,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Use planner (adds a model round trip per run)",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(checked = usePlanner, onCheckedChange = { usePlanner = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Disable model thinking (GLM-4.5V etc. — much faster)",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(checked = disableThinking, onCheckedChange = { disableThinking = it })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = { fetchModels() },
                        enabled = !fetching && endpoint.isNotBlank() && apiKey.isNotBlank(),
                    ) {
                        if (fetching) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(AppIcons.Refresh, null)
                        }
                        Text("  Fetch models")
                    }
                    error?.let {
                        Text(
                            it,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val powerManager = remember {
                    context.getSystemService(android.os.PowerManager::class.java)
                }
                var batteryExempt by remember {
                    mutableStateOf(powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true)
                }
                LaunchedEffect(Unit) {
                    while (true) {
                        batteryExempt = powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true
                        delay(2000)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "3 · Keep me awake while I work",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    if (batteryExempt) {
                        Icon(AppIcons.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
                Text(
                    "MIUI/HyperOS freezes background apps — a running agent would stall mid-task. " +
                        "Tap below to exempt it from battery optimization, then (MIUI only) also set " +
                        "App info → Battery saver → No restrictions.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        try {
                            context.startActivity(
                                android.content.Intent(
                                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                    android.net.Uri.parse("package:${context.packageName}"),
                                ),
                            )
                        } catch (e: Exception) {
                            context.startActivity(
                                android.content.Intent(
                                    android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS,
                                ),
                            )
                        }
                    }) { Text("Battery exemption") }
                    OutlinedButton(onClick = {
                        context.startActivity(
                            android.content.Intent(
                                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                android.net.Uri.parse("package:${context.packageName}"),
                            ),
                        )
                    }) { Text("App info (MIUI)") }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                var shizukuState by remember {
                    mutableStateOf(tech.handsy.phoneagent.accessibility.ShellInput.statusText())
                }
                LaunchedEffect(Unit) {
                    while (true) {
                        shizukuState = tech.handsy.phoneagent.accessibility.ShellInput.statusText()
                        delay(2000)
                    }
                }
                Text("4 · 反风控输入(可选,推荐)", style = MaterialTheme.typography.titleMedium)
                Text(
                    "个别 App(如小红书)会丢弃无障碍合成触摸。授权 Shizuku 后,重试的点击将以 shell 权限注入," +
                        "事件不带无障碍标记,无法被识别。一次性设置:安装 Shizuku → 无线调试配对启动 → 回来点授权。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(shizukuState, style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = {
                    tech.handsy.phoneagent.accessibility.ShellInput.requestPermission()
                }) { Text("授权 Shizuku") }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                var hidState by remember { mutableStateOf("未连接") }
                LaunchedEffect(Unit) {
                    while (true) {
                        hidState = if (tech.handsy.phoneagent.accessibility.HidHands.attached(context)) {
                            "ESP32 手柄已连接 ✓ 物理触摸模式"
                        } else {
                            "未连接(插上刷好固件的 ESP32 开发板即自动启用)"
                        }
                        delay(2000)
                    }
                }
                Text("5 · 物理触摸外设(可选,最强)", style = MaterialTheme.typography.titleMedium)
                Text(
                    "把刷好固件的 XIAO ESP32S3 插进手机 USB 口:手机会把它识别为外接触摸屏+键盘," +
                        "Agent 的点击将以真实触摸事件执行(内核级,与手指无差别),还支持真回车键。详见 hardware/ 目录。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(hidState, style = MaterialTheme.typography.bodySmall)
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                var maxStepsText by remember {
                    mutableStateOf(settings.maxSteps.toString())
                }
                var autoResume by remember { mutableStateOf(settings.autoResume) }
                Text("5 · 长时任务(小时级)", style = MaterialTheme.typography.titleMedium)
                Text(
                    "工作流要连续跑 1-3 小时?调大步数上限,并开启断线自动提醒。手机建议插着充电器。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = maxStepsText,
                        onValueChange = { maxStepsText = it.filter { c -> c.isDigit() }.take(3) },
                        label = { Text("步数上限") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Text("步", style = MaterialTheme.typography.bodySmall)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "被系统打断后自动提醒续跑",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(checked = autoResume, onCheckedChange = { autoResume = it })
                }
                TextButton(onClick = {
                    settings.maxSteps = maxStepsText.toIntOrNull() ?: 60
                    settings.autoResume = autoResume
                }) { Text("保存长时设置") }
            }
        }

        Button(
            onClick = {
                settings.endpoint = endpoint.trim()
                settings.saveUserApiKey(apiKey.trim())
                settings.model = model.trim()
                settings.plannerModel = plannerModel.trim()
                settings.usePlanner = usePlanner
                settings.disableThinking = disableThinking
                onDone()
            },
            enabled = accessibilityOn && endpoint.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Save & continue")
        }
    }
}
