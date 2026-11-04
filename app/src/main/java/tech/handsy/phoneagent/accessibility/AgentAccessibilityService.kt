package tech.handsy.phoneagent.accessibility

import android.accessibilityservice.AccessibilityService
import tech.handsy.phoneagent.R
import android.content.Intent
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.hypot

/**
 * The agent's eyes and hands: screenshots via takeScreenshot,
 * taps/swipes/drawing via dispatchGesture, and system navigation.
 */
class AgentAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: AgentAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        try {
            tech.handsy.phoneagent.QuickOpenNotification.show(this)
        } catch (e: Exception) {
        }
        // 断线自愈:服务重新连上时,若有被中断的任务,发"继续"通知
        try {
            val runs = tech.handsy.phoneagent.store.RunStore(this).listRuns()
            val interrupted = runs.firstOrNull { it.status == "running" || it.status == "paused" }
            if (interrupted != null &&
                tech.handsy.phoneagent.store.SettingsStore(this).autoResume
            ) {
                val pi = android.app.PendingIntent.getService(
                    this, 7,
                    android.content.Intent(this, tech.handsy.phoneagent.service.AgentService::class.java)
                        .setAction(tech.handsy.phoneagent.service.AgentService.ACTION_RESUME),
                    android.app.PendingIntent.FLAG_IMMUTABLE,
                )
                val nm = getSystemService(android.app.NotificationManager::class.java)
                nm.createNotificationChannel(
                    android.app.NotificationChannel("agent_interrupted", "任务中断提醒", android.app.NotificationManager.IMPORTANCE_DEFAULT),
                )
                nm.notify(
                    44,
                    android.app.Notification.Builder(this, "agent_interrupted")
                        .setSmallIcon(R.drawable.ic_notification)
                        .setContentTitle("检测到未完成的任务")
                        .setContentText(interrupted.goal.take(60))
                        .setContentIntent(
                            android.app.PendingIntent.getActivity(
                                this, 1,
                                Intent(this, tech.handsy.phoneagent.MainActivity::class.java),
                                android.app.PendingIntent.FLAG_IMMUTABLE,
                            ),
                        )
                        .addAction(0, "继续执行", pi)
                        .setAutoCancel(true)
                        .build(),
                )
            }
        } catch (e: Exception) {
        }
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    fun screenBounds(): Rect =
        getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds

    /**
     * Current screen as a software bitmap, or null on failure.
     * The system throttles consecutive screenshots; small failures are retried here.
     */
    fun screenshot(timeoutMs: Long = 4000): Bitmap? {
        repeat(3) { attempt ->
            val shot = screenshotOnce(timeoutMs)
            if (shot != null) return shot
            if (AgentAccessibilityService.instance == null) return null
            Thread.sleep(400L * (attempt + 1))
        }
        return null
    }

    private fun screenshotOnce(timeoutMs: Long): Bitmap? {
        val latch = CountDownLatch(1)
        var result: Bitmap? = null
        // takeScreenshot's binder call can stall in system_server (seen after tapping
        // freshly-launched apps on MIUI). Run it on a sacrificial thread and bound the wait.
        val shotThread = Thread {
            try {
                takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    mainExecutor,
                    object : TakeScreenshotCallback {
                        override fun onSuccess(screenshot: ScreenshotResult) {
                            val hardware = Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                            Thread {
                                result = hardware?.copy(Bitmap.Config.ARGB_8888, false)
                                latch.countDown()
                            }.start()
                            try {
                                if (latch.await(3, TimeUnit.SECONDS)) {
                                    screenshot.hardwareBuffer.close()
                                } else {
                                    android.util.Log.w("AgentShot", "screenshot copy stalled; abandoning")
                                }
                            } catch (e: InterruptedException) {
                                latch.countDown()
                            }
                        }

                        override fun onFailure(errorCode: Int) {
                            android.util.Log.d("AgentShot", "screenshot failure code=$errorCode")
                            latch.countDown()
                        }
                    },
                )
            } catch (e: Exception) {
                latch.countDown()
            }
        }
        shotThread.isDaemon = true
        shotThread.start()
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            android.util.Log.w("AgentShot", "screenshot binder stall; abandoning shot thread")
        }
        android.util.Log.d("AgentShot", "shot resolved ok=${result != null}")
        return result
    }

    /**
     * Calibration equivalent of the iOS original: the vision model aims by pixels and can be
     * a few dozen off; snap to the center of the nearest clickable node when one is close.
     */
    fun snapToClickable(x: Float, y: Float, maxDistPx: Float = 140f): Pair<Float, Float> {
        val root = rootInActiveWindow ?: return x to y
        var best: Pair<Float, Float>? = null
        var bestDist = maxDistPx
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        val seen = mutableSetOf(System.identityHashCode(root))
        queue.add(root)
        var scanned = 0
        while (queue.isNotEmpty() && scanned < 1200) {
            val node = queue.removeFirst()
            scanned++
            if (node.isClickable || node.isLongClickable) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                if (rect.width() > 0 && rect.height() > 0) {
                    val cx = rect.exactCenterX()
                    val cy = rect.exactCenterY()
                    val dist = hypot((cx - x).toDouble(), (cy - y).toDouble()).toFloat()
                    if (dist < bestDist) {
                        bestDist = dist
                        best = cx to cy
                    }
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let {
                    if (seen.add(System.identityHashCode(it))) queue.add(it)
                }
            }
        }
        val result = best ?: (x to y)
        if (best != null && (abs(result.first - x) > 2f || abs(result.second - y) > 2f)) {
            android.util.Log.d(
                "AgentShot",
                "snap (${x.toInt()},${y.toInt()}) -> (${result.first.toInt()},${result.second.toInt()})",
            )
        }
        return result
    }

    /**
     * Anti-bot bypass: finds the clickable node nearest to (x, y) and fires its
     * onClick listener directly via ACTION_CLICK — no MotionEvent is generated,
     * so apps that filter synthetic touches cannot drop it.
     */
    fun clickNodeAt(x: Float, y: Float, maxDistPx: Float = 160f): Boolean {
        val root = rootInActiveWindow ?: return false
        var best: AccessibilityNodeInfo? = null
        var bestDist = maxDistPx
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        val seen = mutableSetOf(System.identityHashCode(root))
        queue.add(root)
        var scanned = 0
        while (queue.isNotEmpty() && scanned < 1200) {
            val node = queue.removeFirst()
            scanned++
            if (node.isClickable || node.isLongClickable) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                if (rect.width() > 0 && rect.height() > 0) {
                    val dist = hypot((rect.exactCenterX() - x).toDouble(), (rect.exactCenterY() - y).toDouble()).toFloat()
                    if (dist < bestDist) {
                        bestDist = dist
                        best = node
                    }
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let {
                    if (seen.add(System.identityHashCode(it))) queue.add(it)
                }
            }
        }
        val target = best ?: return false
        val clicked = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        android.util.Log.d("AgentShot", "ACTION_CLICK at (${x.toInt()},${y.toInt()}) -> $clicked")
        return clicked
    }

    fun tap(x: Float, y: Float): Boolean {
        val path = Path().apply {
            moveTo(x, y)
            lineTo(x + 0.5f, y + 0.5f)
        }
        return dispatch(path, 60)
    }

    fun longPress(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        return dispatch(path, 1000)
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float): Boolean {
        val length = hypot((x2 - x1).toDouble(), (y2 - y1).toDouble()).toFloat()
        val duration = (200f + length * 0.6f).coerceIn(350f, 2600f)
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        return dispatch(path, duration.toLong())
    }

    fun stroke(points: List<Pair<Float, Float>>): Boolean {
        if (points.size < 2) return false
        var total = 0f
        for (i in 1 until points.size) {
            total += hypot(
                (points[i].first - points[i - 1].first).toDouble(),
                (points[i].second - points[i - 1].second).toDouble(),
            ).toFloat()
        }
        val duration = (250f + total * 0.9f).coerceIn(400f, 6000f)
        val path = Path().apply {
            moveTo(points[0].first, points[0].second)
            for (i in 1 until points.size) lineTo(points[i].first, points[i].second)
        }
        return dispatch(path, duration.toLong())
    }

    fun circle(cx: Float, cy: Float, radius: Float): Boolean {
        val segments = 16
        val path = Path()
        val circumference = 2.0 * Math.PI * radius
        val duration = (400 + circumference * 1.4).coerceIn(600.0, 6000.0).toLong()
        for (i in 0..segments) {
            val angle = Math.PI * 2 * i / segments
            val x = cx + (radius * Math.cos(angle)).toFloat()
            val y = cy + (radius * Math.sin(angle)).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        return dispatch(path, duration)
    }

    private fun dispatch(path: Path, durationMs: Long): Boolean {
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }

    /** Where are we right now — package + window title, for the model's orientation. */
    fun currentApp(): String = try {
        val root = rootInActiveWindow
        val pkg = root?.packageName ?: "unknown"
        val title = ""
        "$pkg ${if (title.isNotBlank()) "($title)" else ""}".trim()
    } catch (e: Exception) {
        "unknown"
    }

    /** 所有窗口的根节点(应用 + 输入法 + 系统弹窗),微信等自定义输入场景必须全窗口扫描。 */
    private fun allRoots(): List<AccessibilityNodeInfo> {
        val roots = mutableListOf<AccessibilityNodeInfo>()
        val seen = mutableSetOf<Int>()
        try {
            rootInActiveWindow?.let {
                roots.add(it); seen.add(System.identityHashCode(it))
            }
            for (w in windows) {
                try {
                    val r = w.root ?: continue
                    if (seen.add(System.identityHashCode(r))) roots.add(r)
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }
        return roots
    }

    /** 在当前屏幕的无障碍树里找文本匹配的图标/节点并点击,返回命中中心坐标。 */
    private fun tapNodeWithText(target: String): Pair<Float, Float>? {
        val root = rootInActiveWindow ?: return null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        val seen = mutableSetOf(System.identityHashCode(root))
        queue.add(root)
        var scanned = 0
        while (queue.isNotEmpty() && scanned < 1200) {
            val node = queue.removeFirst()
            scanned++
            val t = node.text?.toString()
            val d = node.contentDescription?.toString()
            val hit = (!t.isNullOrBlank() && (t.equals(target, true) || t.contains(target))) ||
                (!d.isNullOrBlank() && (d.equals(target, true) || d.contains(target)))
            if (hit && (node.isClickable || node.isLongClickable)) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                if (rect.width() > 0 && rect.height() > 0) {
                    val path = android.graphics.Path().apply {
                        moveTo(rect.exactCenterX(), rect.exactCenterY())
                        lineTo(rect.exactCenterX() + 0.5f, rect.exactCenterY() + 0.5f)
                    }
                    val gesture = GestureDescription.Builder()
                        .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
                        .build()
                    dispatchGesture(gesture, null, null)
                    return rect.exactCenterX() to rect.exactCenterY()
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let {
                    if (seen.add(System.identityHashCode(it))) queue.add(it)
                }
            }
        }
        return null
    }

    private fun swipeOnScreen(x1: Float, y1: Float, x2: Float, y2: Float, duration: Long = 350) {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()
        dispatchGesture(gesture, null, null)
    }

    /**
     * 确定性寻页:回桌面 → 逐页扫描图标 → 抽屉逐页扫描 → 兜底直启。
     * 解决"回桌面落在非第一页、模型不会左右滑"的问题。
     */
    fun findAndOpenApp(name: String, maxHomePages: Int = 5, maxDrawerPages: Int = 4): Boolean {
        val target = name.trim()
        val bounds = screenBounds()
        val cx = bounds.width() / 2f
        val iconMap = tech.handsy.phoneagent.store.IconMapStore(this)

        // ① 记忆直达:已知页码 → home → 横向翻到该页 → 原坐标点击
        val known = iconMap.load()[target.lowercase()]
        if (known != null) {
            performGlobalAction(GLOBAL_ACTION_HOME)
            Thread.sleep(900)
            repeat(known.page) {
                swipeOnScreen(cx + bounds.width() * 0.3f, bounds.height() * 0.5f, cx - bounds.width() * 0.3f, bounds.height() * 0.5f, 300)
                Thread.sleep(600)
            }
            val hit = tapNodeWithText(target)
            if (hit != null) {
                iconMap.remember(target, known.page, hit.first.toInt(), hit.second.toInt())
                return true
            }
            // 记忆失效(图标被移动):继续全量扫描并覆盖记忆
        }

        // ② 全量扫描:桌面横向逐页 → 抽屉纵向逐页
        performGlobalAction(GLOBAL_ACTION_HOME)
        Thread.sleep(900)
        for (page in 0 until maxHomePages) {
            val hit = tapNodeWithText(target)
            if (hit != null) {
                iconMap.remember(target, page, hit.first.toInt(), hit.second.toInt())
                return true
            }
            swipeOnScreen(cx + bounds.width() * 0.3f, bounds.height() * 0.5f, cx - bounds.width() * 0.3f, bounds.height() * 0.5f)
            Thread.sleep(700)
        }
        performGlobalAction(GLOBAL_ACTION_HOME)
        Thread.sleep(700)
        swipeOnScreen(cx, bounds.height() * 0.85f, cx, bounds.height() * 0.25f, 400)
        Thread.sleep(900)
        for (page in 0 until maxDrawerPages) {
            val hit = tapNodeWithText(target)
            if (hit != null) {
                // 抽屉项不记页码(抽屉可搜索),直接算找到
                return true
            }
            swipeOnScreen(cx, bounds.height() * 0.75f, cx, bounds.height() * 0.25f, 400)
            Thread.sleep(800)
        }
        // ③ 兜底:按名称/别名解析直启
        return launchApp(target)
    }

    /**
     * Opens an app by label (小红书) or package (com.xingin.xhs) directly —
     * no drawer hunting. Falls back to a shell start via Shizuku when the
     * background-activity-start restriction bites.
     */
    fun launchApp(nameOrPackage: String): Boolean {
        val pm = packageManager
        val target = nameOrPackage.trim()
        var pkg = if (target.contains('.')) target else null
        if (pkg == null) {
            // Common Chinese app names → package fragments (labels may be localized).
            val aliases = mapOf(
                "美团外卖" to listOf("takeoutnew"), "美团" to listOf("meituan", "sankuai"),
                "微信" to listOf("tencent.mm"), "淘宝" to listOf("taobao"),
                "京东" to listOf("com.jingdong", "im.jm"), "拼多多" to listOf("xunmeng"),
                "抖音" to listOf("aweme", "douyin"), "快手" to listOf("smile.gifmaker"),
                "小红书" to listOf("xingin"), "哔哩哔哩" to listOf("bili"),
                "B站" to listOf("bili"), "微博" to listOf("weibo"),
                "支付宝" to listOf("alipay"), "高德" to listOf("autonavi"),
                "百度地图" to listOf("baidu.map"), "百度" to listOf("baidu"),
                "qq" to listOf("mobileqq"), "QQ" to listOf("mobileqq"),
                "网易云音乐" to listOf("cloudmusic"), "音乐" to listOf("music"),
                "滴滴" to listOf("didapinche", "sf."), "携程" to listOf("ctrip"),
                "飞猪" to listOf("fleapalace"), "去哪儿" to listOf("qunar"),
                "设置" to listOf("com.android.settings"), "计算器" to listOf("calculator"),
                "相机" to listOf("camera2", "camera"), "日历" to listOf("calendar"),
                "时钟" to listOf("deskclock", "clock"), "文件管理" to listOf("fileexplorer"),
            )
            val fragments = aliases[target]
            if (fragments != null) {
                for (info in pm.getInstalledApplications(0)) {
                    if (fragments.any { info.packageName.lowercase().contains(it) }) {
                        pkg = info.packageName
                        break
                    }
                }
            }
        }
        if (pkg == null) {
            val apps = pm.getInstalledApplications(0)
            for (info in apps) {
                val label = try {
                    pm.getApplicationLabel(info).toString()
                } catch (e: Exception) {
                    ""
                }
                if (label.contains(target) || target.contains(label) && label.length > 1) {
                    pkg = info.packageName
                    break
                }
            }
        }
        if (pkg == null) return false
        val intent = pm.getLaunchIntentForPackage(pkg) ?: return false
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            startActivity(intent)
            true
        } catch (e: Exception) {
            // Background activity launch blocked — retry as shell (Shizuku).
            val comp = intent.component
            val out = tech.handsy.phoneagent.accessibility.ShellInput.exec(
                "am start -n ${comp?.packageName}/${comp?.className}",
            )
            out?.contains("Starting:", true) == true || out?.contains("Warning", true) == true
        }
    }

    /** True when TEXT is visible anywhere in the accessibility tree. */
    fun hasTextOnScreen(text: String): Boolean {
        val needle = text.trim()
        if (needle.isEmpty()) return true
        val root = rootInActiveWindow ?: return false
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        val seen = mutableSetOf(System.identityHashCode(root))
        queue.add(root)
        var scanned = 0
        while (queue.isNotEmpty() && scanned < 900) {
            val node = queue.removeFirst()
            scanned++
            val t = node.text?.toString()
            if (t != null && t.contains(needle)) return true
            val d = node.contentDescription?.toString()
            if (d != null && d.contains(needle)) return true
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let {
                    if (seen.add(System.identityHashCode(it))) queue.add(it)
                }
            }
        }
        return false
    }

    fun home(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)
    fun back(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    fun recents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)

    /**
     * Replaces the focused field's content with [text]. Some apps use custom editors
     * where ACTION_SET_TEXT silently doesn't register — verify, then fall back to
     * clipboard paste.
     */
    fun typeText(text: String): Boolean {
        val node = findFocusedEditable() ?: return false
        if (setText(node, text)) {
            try {
                node.refresh()
                val now = node.text?.toString().orEmpty()
                if (now.contains(text.trim())) return true
            } catch (_: Exception) {
            }
            android.util.Log.w("AgentShot", "SET_TEXT not registered; trying clipboard paste")
        }
        return try {
            val cm = getSystemService(android.content.ClipboardManager::class.java)
            cm.setPrimaryClip(android.content.ClipData.newPlainText("agent", text))
            node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        } catch (e: Exception) {
            false
        }
    }

    fun key(name: String): Boolean = when (name) {
        "enter" -> {
            val node = findFocusedEditable() ?: return false
            // No key-injection API for accessibility services; newline is the reliable substitute.
            setText(node, node.text?.toString().orEmpty() + "\n")
        }
        "backspace", "del", "delete" -> {
            val node = findFocusedEditable() ?: return false
            val existing = node.text?.toString().orEmpty()
            if (existing.isEmpty()) true else setText(node, existing.dropLast(1))
        }
        else -> false
    }

    /**
     * Compact accessibility-tree hints: visible interactive nodes with labels and
     * 0–1000 coordinates (same space as the screenshots). Null when nothing useful.
     */
    fun uiTree(maxNodes: Int = 40): String? {
        val root = rootInActiveWindow ?: return null
        val bounds = screenBounds()
        if (bounds.width() <= 0 || bounds.height() <= 0) return null
        val lines = mutableListOf<String>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        val seen = mutableSetOf(System.identityHashCode(root))
        queue.add(root)
        var scanned = 0
        while (queue.isNotEmpty() && lines.size < maxNodes && scanned < 1500) {
            val node = queue.removeFirst()
            scanned++
            val rect = Rect()
            node.getBoundsInScreen(rect)
            val visible = rect.width() > 0 && rect.height() > 0 && Rect.intersects(rect, bounds)
            val label = (node.text?.toString() ?: node.contentDescription?.toString())
                ?.trim()?.replace(Regex("\\s+"), " ")?.take(40).orEmpty()
            if (visible && (node.isClickable || node.isLongClickable || node.isScrollable ||
                    node.isEditable || label.isNotEmpty())
            ) {
                val x = ((rect.centerX().coerceIn(bounds.left, bounds.right) - bounds.left) * 1000 /
                    bounds.width()).coerceIn(0, 1000)
                val y = ((rect.centerY().coerceIn(bounds.top, bounds.bottom) - bounds.top) * 1000 /
                    bounds.height()).coerceIn(0, 1000)
                val kind = when {
                    node.isEditable -> "input"
                    node.isScrollable -> "scroll"
                    node.isClickable || node.isLongClickable -> "click"
                    else -> "text"
                }
                lines.add("[$kind] ($x,$y) \"$label\"")
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let {
                    if (seen.add(System.identityHashCode(it))) queue.add(it)
                }
            }
        }
        if (lines.isEmpty()) return null
        val texts = mutableListOf<String>()
        val q2 = ArrayDeque<AccessibilityNodeInfo>()
        val seen2 = mutableSetOf(System.identityHashCode(root))
        q2.add(root)
        var n2 = 0
        while (q2.isNotEmpty() && texts.size < 18 && n2 < 1200) {
            val node = q2.removeFirst()
            n2++
            val t = node.text?.toString()?.trim().orEmpty()
            if (t.isNotEmpty() && t.length <= 60) texts.add(t)
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let {
                    if (seen2.add(System.identityHashCode(it))) q2.add(it)
                }
            }
        }
        return buildString {
            append(lines.joinToString("\n"))
            if (texts.isNotEmpty()) {
                append("\nTEXTS ON SCREEN: ")
                append(texts.distinct().take(18).joinToString(" | "))
            }
        }
    }

    private fun setText(node: AccessibilityNodeInfo, value: String): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun findFocusedEditable(): AccessibilityNodeInfo? {
        // 遍历所有窗口:键盘弹起时活动窗口可能是输入法,微信输入框在应用窗口里
        for (root in allRoots()) {
            var fallback: AccessibilityNodeInfo? = null
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            val seen = mutableSetOf(System.identityHashCode(root))
            queue.add(root)
            var scanned = 0
            while (queue.isNotEmpty() && scanned < 1500) {
                val node = queue.removeFirst()
                scanned++
                if (node.isEditable) {
                    if (node.isFocused) return node
                    if (fallback == null) fallback = node
                }
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let {
                        if (seen.add(System.identityHashCode(it))) queue.add(it)
                    }
                }
            }
        }
        return null
    }
}
