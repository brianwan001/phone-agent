package tech.handsy.phoneagent.agent

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import tech.handsy.phoneagent.accessibility.AgentAccessibilityService
import tech.handsy.phoneagent.ai.OpenAIClient
import tech.handsy.phoneagent.store.RunRecord
import tech.handsy.phoneagent.store.RunStore
import tech.handsy.phoneagent.store.ExperienceStore
import tech.handsy.phoneagent.store.MemoryStore
import tech.handsy.phoneagent.store.SettingsStore
import tech.handsy.phoneagent.store.StepRecord
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import java.util.concurrent.TimeUnit

/**
 * The agent loop: screenshot → (plan) → actor model → parse THINK/DO →
 * perform actions → wait for the screen to settle → tell the model what changed.
 */
class AgentEngine(
    private val context: Context,
    private val store: RunStore,
    private val run: RunRecord,
    private val settings: SettingsStore,
    private val maxSteps: Int = settings.maxSteps,
) {
    @Volatile
    var cancelled = false

    private var replanRequested = false

    @Volatile
    private var lastTypeFailed = false

    /** Last tap target that produced no change, with its miss count (for zoom re-aim). */
    private var missedX = -1
    private var missedY = -1
    private var missCount = 0

    /** Operator-style visual memory: small copies of the last two screens. */
    private val historyShots = ArrayDeque<Bitmap>()

    @Volatile
    private var lastKnownApp: String = ""

    @Volatile
    private var zoomRequestX = -1
    @Volatile
    private var zoomRequestY = -1

    private var plan = ""

    private var lastSignature: String? = null
    private var streak = 0

    private val poisoned = java.util.concurrent.atomic.AtomicBoolean(false)


    private val client by lazy { OpenAIClient(settings.endpoint, settings.apiKey) }

    /** 兜底:主模型计费失败时,自动切免费模型(GLM-4V-Flash),保证人人可用。 */
    private var fallbackMode = false

    private val experience by lazy { ExperienceStore(context) }

    private val memory by lazy { MemoryStore(context) }

    fun execute() {
        if (!settings.isConfigured()) {
            pause("Model endpoint is not configured")
            return
        }
        try {
            runLoop()
        } catch (e: InterruptedException) {
            pause("系统把 App 冻结/卡住了 — 点恢复可继续")
        } catch (e: Throwable) {
            // Throwable on purpose: an OOM or any Error must pause the run, not kill the worker silently.
            pause(e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName)
        } finally {
            // Clear any watchdog interrupt left on this thread.
            Thread.interrupted()
        }
    }

    private fun runLoop() {
        // 无障碍服务重绑定可能比引擎启动慢(重装/重启/恢复场景),等它上线而不是立刻放弃
        var service = AgentAccessibilityService.instance
        var waited = 0
        while (service == null && waited < 20_000 && !cancelled) {
            Thread.sleep(500)
            waited += 500
            service = AgentAccessibilityService.instance
        }
        if (service == null) {
            Log.d(TAG, "a11y instance STILL null after wait")
            pause("Accessibility service is not enabled")
            return
        }
        val bounds = service.screenBounds()
        val hidReady = tech.handsy.phoneagent.accessibility.HidHands.attached(context)
        if (hidReady) Log.i(TAG, "ESP32 HID bridge active — physical touch mode")
        var plan = ""
        while (!cancelled && !poisoned.get() && run.steps.size < maxSteps) {
            val stepIndex = run.steps.size
            // A step must never hang the run: interrupt this thread after the budget.
            val engineThread = Thread.currentThread()
            val budget = Thread {
                try {
                    Thread.sleep(STEP_BUDGET_MS)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                if (!cancelled && run.finishedAt == null) {
                    Log.w(TAG, "step $stepIndex exceeded ${STEP_BUDGET_MS}ms; forcing pause")
                    // The worker may be wedged in a native call where interrupt is a no-op;
                    // persist the pause so the run stays resumable even if this thread never returns.
                    poisoned.set(true)
                    run.status = "paused"
                    run.result = "Step timed out (system stall). Resume to continue."
                    try {
                        store.persist(run)
                    } catch (_: Exception) {}
                    AgentBus.update(AgentStatus.Paused(run.id, run.goal, run.steps.size, run.result))
                    engineThread.interrupt()
                }
            }.apply { isDaemon = true; start() }
            try {
                runStep(service, bounds, stepIndex)
            } finally {
                budget.interrupt()
                Thread.interrupted()
            }
            if (run.finishedAt != null || run.status != "running") break
        }
        // Only finish here if the loop ended while still running; a pause (or watchdog
        // pause) must not be clobbered by the tail handler.
        if (run.status == "running") {
            if (cancelled) {
                finish(ok = false, "Stopped")
            } else {
                finish(ok = false, "Reached the step limit ($maxSteps steps)")
            }
        }
    }

    private fun runStep(service: AgentAccessibilityService, bounds: Rect, stepIndex: Int) {
        // 长时任务电量守护:低于10%且未充电 → 暂停等用户充电
        try {
            val bm = context.getSystemService(android.content.Context.BATTERY_SERVICE) as? android.os.BatteryManager
            val level = bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 100
            val status = bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_STATUS) ?: 0
            val charging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
                status == android.os.BatteryManager.BATTERY_STATUS_FULL
            if (level in 1..9 && !charging) {
                pause("电量仅 $level% 且未充电。插上充电器后点继续,长时任务不怕断。")
                return
            }
        } catch (_: Exception) {
        }
        publishRunning("Looking at the screen…", emptyList(), null)
        Log.d(TAG, "step $stepIndex: capturing")

        val full = screenshotWithRetry(service) ?: run {
            pause("Lost the screen (screenshot failed). Check the accessibility service.")
            return
        }

        if ((stepIndex == 0 || replanRequested) && settings.usePlanner) {
            publishRunning("Planning…", emptyList(), null)
            plan = requestPlan(full)
            replanRequested = false
        }

        val shot = downscale(full, 1100)
        val uiHints = service.uiTree()
        Log.d(TAG, "step $stepIndex: chat start")
        val t0 = System.currentTimeMillis()
        val reply = client.chat(
            model = settings.actorModelOrDefault,
            system = Prompts.actor(context) + "\n\n" + Prompts.appRules(context),
            userText = buildUserText(plan, uiHints),
            screenshot = shot,
            disableThinking = settings.disableThinking,
        )
        Log.d(TAG, "step $stepIndex: chat done in ${System.currentTimeMillis() - t0}ms, reply=${reply.take(120)}")
        val (think, actions) = ActionParser.parseBlock(reply)

        val signature = actions.map { ActionParser.describe(it) }.joinToString(";")
        if (signature == lastSignature && signature.isNotBlank()) streak++ else {
            lastSignature = signature
            streak = 0
        }
        if (streak >= 7) {
            pause("Stuck repeating: $signature — likely hitting the wrong spot (keyboard?).")
            return
        }

        val step = StepRecord(
            index = stepIndex,
            think = think,
            actions = actions.map { ActionParser.describe(it) },
        )
        step.screenshot = store.saveScreenshot(run, stepIndex, shot)
        run.steps.add(step)
        publishRunning(think.ifBlank { "Acting…" }, step.actions, null)

        val doneAction = actions.filterIsInstance<AgentAction.Done>().firstOrNull()
        val failAction = actions.filterIsInstance<AgentAction.Fail>().firstOrNull()
        if (doneAction != null) {
            finish(ok = true, doneAction.answer)
            return
        }
        if (failAction != null) {
            finish(ok = false, failAction.reason)
            return
        }

        // Reuse the frame the model just saw as the pre-action state (saves a throttled capture).
        val before = thumb(shot)
        if (actions.isNotEmpty()) {
            Log.d(TAG, "step $stepIndex: perform ${actions.map { ActionParser.describe(it) }}")
            perform(service, bounds, actions)
            if (actions.any { it is AgentAction.Replan }) replanRequested = true
        }
        Log.d(TAG, "step $stepIndex: settle")
        step.changed = if (!cancelled) settle(service, before) else null
        Log.d(TAG, "step $stepIndex: settle done changed=${step.changed}")
        store.persist(run)
        Log.d(TAG, "step $stepIndex: persisted")
    }

    private fun perform(service: AgentAccessibilityService, bounds: Rect, actions: List<AgentAction>): Boolean {
        var allTypesOk = true
        val pxX = { v: Int -> bounds.width() * v / 1000f }
        val pxY = { v: Int -> bounds.height() * v / 1000f }
        for (action in actions) {
            if (cancelled) return allTypesOk
            // Tier 0: ESP32 HID bridge — genuine touch events via USB, if attached.
            if (tech.handsy.phoneagent.accessibility.HidHands.ready()) {
                val hidHandled = when (action) {
                    is AgentAction.Tap -> tech.handsy.phoneagent.accessibility.HidHands.tap(action.x, action.y)
                    is AgentAction.LongPress -> tech.handsy.phoneagent.accessibility.HidHands.longPress(action.x, action.y)
                    is AgentAction.Swipe -> tech.handsy.phoneagent.accessibility.HidHands.swipe(
                        action.x1, action.y1, action.x2, action.y2,
                    )
                    is AgentAction.Key -> tech.handsy.phoneagent.accessibility.HidHands.key(action.name)
                    else -> false
                }
                if (hidHandled) {
                    Log.d(TAG, "hid: ${ActionParser.describe(action)}")
                    Thread.sleep(200)
                    continue
                }
            }
            when (action) {
                is AgentAction.Tap -> {
                    val sx = pxX(action.x)
                    val sy = pxY(action.y)
                    val retrying = missedX == action.x && missedY == action.y && missCount >= 1
                    var handled = false
                    if (retrying && missCount == 1) handled = service.clickNodeAt(sx, sy)
                    if (retrying && !handled && missCount >= 2) {
                        handled = tech.handsy.phoneagent.accessibility.ShellInput.tap(sx, sy)
                        Log.d(TAG, "shizuku shell tap -> $handled")
                    }
                    if (!handled) {
                        val (ax, ay) = service.snapToClickable(sx, sy)
                        service.tap(ax, ay)
                    }
                }
                is AgentAction.LongPress -> {
                    val (sx, sy) = service.snapToClickable(pxX(action.x), pxY(action.y))
                    service.longPress(sx, sy)
                }
                is AgentAction.Swipe -> service.swipe(pxX(action.x1), pxY(action.y1), pxX(action.x2), pxY(action.y2))
                is AgentAction.Stroke -> service.stroke(
                    action.points.chunked(2).map { pxX(it[0]) to pxY(it[1]) },
                )
                is AgentAction.Circle -> service.circle(pxX(action.x), pxY(action.y), pxX(action.r))
                is AgentAction.Type -> {
                    if (!service.typeText(action.text)) allTypesOk = false
                }
                is AgentAction.Key -> service.key(action.name)
                AgentAction.Home -> service.home()
                AgentAction.Back -> service.back()
                AgentAction.Recents -> service.recents()
                AgentAction.Wait -> Thread.sleep(1200)
                is AgentAction.Launch -> {
                    val ok = service.launchApp(action.name)
                    Log.d(TAG, "launch ${action.name} -> $ok")
                }
                is AgentAction.FindApp -> {
                    val ok = service.findAndOpenApp(action.name)
                    Log.d(TAG, "find_app ${action.name} -> $ok")
                }
                is AgentAction.WaitText -> {
                    val deadline = System.currentTimeMillis() + 8000
                    while (System.currentTimeMillis() < deadline && !cancelled) {
                        if (service.hasTextOnScreen(action.text)) break
                        Thread.sleep(500)
                    }
                }
                is AgentAction.Zoom -> {
                    zoomRequestX = action.x
                    zoomRequestY = action.y
                }
                AgentAction.Replan -> Unit
                is AgentAction.Done, is AgentAction.Fail -> Unit
            }
            Thread.sleep(80)
        }
        return allTypesOk
    }

    private fun requestPlan(firstScreen: Bitmap): String = try {
        client.chat(
            model = settings.plannerModelOrDefault,
            system = Prompts.planner(context),
            userText = "GOAL: ${run.goal}\nCurrent screen attached. Write the numbered plan only.",
            screenshot = firstScreen,
            disableThinking = settings.disableThinking,
        ).trim()
    } catch (e: Exception) {
        "" // the actor can still work without a plan; real errors surface on its call
    }

    /** Bucket coordinates so "tap 287 895" and "tap 288 892" count as the same action. */
    private fun normalizeAction(a: String): String {
        val parts = a.trim().split(Regex("\\s+"))
        fun b(v: String?, step: Int) = (v?.toIntOrNull()?.div(step)).toString()
        return when (parts.firstOrNull()) {
            "tap", "long_press" -> if (parts.size >= 3) "${parts[0]} ${b(parts[1], 25)} ${b(parts[2], 25)}" else a.trim()
            "swipe" -> if (parts.size >= 5)
                "swipe ${b(parts[1], 50)} ${b(parts[2], 50)} ${b(parts[3], 50)} ${b(parts[4], 50)}" else a.trim()
            else -> a.trim()
        }
    }

    private fun lastActionSignature(): String =
        run.steps.lastOrNull()?.actions?.joinToString(";") { normalizeAction(it) } ?: ""

    private fun repeatedActionCount(): Int {
        val sig = lastActionSignature()
        if (sig.isBlank()) return 0
        var count = 0
        for (step in run.steps.asReversed()) {
            val s2 = step.actions.joinToString(";") { normalizeAction(it) }
            if (s2 == sig) count++ else break
        }
        return count
    }

    /** Steps in a row where the screen provably did not change at all. */
    private fun noChangeStreak(): Int {
        var n = 0
        for (step in run.steps.asReversed()) {
            if (step.changed == false) n++ else break
        }
        return n
    }

    private fun buildUserText(plan: String, uiHints: String?): String {
        val sb = StringBuilder()
        if (streak >= 2) {
            sb.append("WARNING: you have repeated the exact same action ").append(streak + 1)
                .append(" times with no real progress. Those coordinates are almost certainly ")
                .append("wrong (you may be tapping the on-screen KEYBOARD, which adds characters ")
                .append("and fakes change). Look again at what is really at that spot, aim ")
                .append("differently, or use fail.\n\n")
        }
        sb.append("GOAL: ").append(run.goal).append("\n\n")
        sb.append("PLAN:\n").append(plan.ifBlank { "(no plan)" }).append("\n\n")
        if (!uiHints.isNullOrBlank()) {
            sb.append("UI HINTS (accessibility tree, may be partial or stale; coordinates are ")
                .append("0-1000 like the screenshot; use to aim, but trust the pixels first):\n")
                .append(uiHints).append("\n\n")
        }
        run.steps.takeLast(2).forEach { prev ->
            if (prev.think.isNotBlank()) {
                sb.append("YOUR LAST THOUGHT (step ").append(prev.index).append("): ")
                    .append(prev.think.take(280)).append("\n")
            }
        }
        val recent = run.steps.takeLast(8)
        if (recent.isEmpty()) {
            sb.append("RECENT ACTIONS: none yet.\n\n")
        } else {
            sb.append("RECENT ACTIONS (most recent last):\n")
            for (step in recent) {
                sb.append("step ").append(step.index).append(": ")
                sb.append(step.actions.ifEmpty { listOf("(no action parsed)") }.joinToString("; "))
                when (step.changed) {
                    true -> sb.append(" → screen changed")
                    false -> sb.append(" → screen did NOT change")
                    null -> Unit
                }
                sb.append("\n")
            }
            sb.append("\n")
        }
        val repeats = repeatedActionCount()
        val flat = noChangeStreak()
        val escalated = repeatedActionCount() >= 3 || noChangeStreak() >= 3
        if (escalated) {
            sb.append("ESCALATION: the primary actor model could not progress; you are the ")
                .append("stronger recovery model. Re-read the screen from scratch, question every ")
                .append("assumption, and chart a different path (or fail honestly).\n\n")
        }
        if (repeats >= 3 || flat >= 4) {
            sb.append("WARNING: you are stuck (same action repeated ").append(repeats)
                .append(" times; no screen change for ").append(flat).append(" steps). ")
                .append("Your taps are NOT landing. Causes: the button is disabled/grey, ")
                .append("a system dialog blocks input (try back to dismiss it), or the app ")
                .append("ignores synthetic touches. Do something DIFFERENT now: back, home, ")
                .append("another way in, or fail with a reason.")
                .append("\n\n")
        }
        sb.append("The current screenshot is attached. Reply with THINK/DO now.")
        return sb.toString()
    }

    private fun screenshotWithRetry(service: AgentAccessibilityService): Bitmap? =
        service.screenshot() // the service already retries throttled shots

    /** Waits for the screen to settle; returns whether anything changed since [before]. */
    private fun settle(service: AgentAccessibilityService, before: IntArray?): Boolean? {
        if (before == null) return null
        Thread.sleep(300)
        var changed = false
        var last: IntArray? = null
        val deadline = System.currentTimeMillis() + 2500
        while (System.currentTimeMillis() < deadline) {
            Log.d(TAG, "settle: poll")
            val current = service.screenshot()?.let { thumb(it) } ?: break
            if (diff(before, current) > CHANGE_THRESHOLD) changed = true
            if (last != null && diff(last, current) < 1.5f) break
            last = current
            Thread.sleep(450)
        }
        return changed
    }

    private fun publishRunning(think: String, dos: List<String>, changed: Boolean?) {
        AgentBus.update(
            AgentStatus.Running(run.id, run.goal, run.steps.size, think, dos, changed),
        )
    }

    private fun finish(ok: Boolean, message: String) {
        if (ok) {
            try {
                experience.recordSuccess(run.goal, run.steps)
            } catch (_: Exception) {
            }
        }
        reflectInBackground(ok, message)
        run.finishedAt = System.currentTimeMillis()
        run.status = when {
            ok -> "done"
            cancelled -> "stopped"
            else -> "failed"
        }
        run.result = message
        store.persist(run)
        AgentBus.update(AgentStatus.Finished(run.goal, ok, message, run.id))
    }

    /** Post-run retrospective: distill reusable semantic lessons from the trace. */
    private fun reflectInBackground(ok: Boolean, message: String) {
        val steps = run.steps.toList()
        if (steps.isEmpty()) return
        Thread {
            try {
                val digest = buildString {
                    append("GOAL: ").append(run.goal).append('\n')
                    append("OUTCOME: ").append(if (ok) "SUCCESS" else "FAILED/STOPPED")
                        .append(" - ").append(message).append('\n')
                    append("APP: ").append(lastKnownApp).append('\n')
                    val head = steps.take(3)
                    val tail = steps.takeLast(10)
                    (head + tail).distinctBy { it.index }.forEach { st ->
                        append("step ").append(st.index).append(": ")
                            .append(st.actions.joinToString("+").take(80))
                            .append(" [").append(
                                when (st.changed) {
                                    true -> "changed"; false -> "NO change"; null -> "?"
                                },
                            ).append("] ")
                            .append(st.think.take(120)).append('\n')
                    }
                }
                val reply = client.chat(
                    model = settings.plannerModelOrDefault,
                    allowThinking = true,
                    system = "You are the retrospective engine of a phone automation agent. " +
                        "Analyze this run trace and extract AT MOST 3 reusable lessons for future runs. " +
                        "Reply ONLY a JSON array like " +
                        "[{\"app\":\"package.or.human.name\",\"situation\":\"when this applies\"," +
                        "\"advice\":\"the concrete actionable lesson\",\"verdict\":\"good|bad\"}]. " +
                        "Lessons must be specific UI/flow facts (where buttons are, what blocks what, " +
                        "what sequence works), not platitudes. Empty array [] if nothing worth keeping.",
                    userText = digest,
                    screenshot = null,
                    disableThinking = settings.disableThinking,
                )
                // Lenient parse: thinking models wrap JSON in prose — brace-match the objects.
                val lessons = mutableListOf<MemoryStore.Lesson>()
                val sb = StringBuilder()
                var depth = 0
                for (ch in reply) {
                    when (ch) {
                        '{' -> {
                            depth++
                            if (depth == 1) sb.setLength(0)
                            sb.append(ch)
                        }
                        '}' -> {
                            sb.append(ch)
                            depth--
                            if (depth == 0) {
                                try {
                                    val o = org.json.JSONObject(sb.toString())
                                    val advice = o.optString("advice")
                                    if (advice.isNotBlank()) {
                                        lessons.add(
                                            MemoryStore.Lesson(
                                                app = o.optString("app"),
                                                situation = o.optString("situation"),
                                                advice = advice,
                                                verdict = o.optString("verdict"),
                                            ),
                                        )
                                    }
                                } catch (_: Exception) {
                                }
                            }
                        }
                        else -> if (depth > 0) sb.append(ch)
                    }
                }
                memory.absorb(lessons)
                Log.d(TAG, "retrospective stored ${lessons.size} lessons")
            } catch (e: Exception) {
                Log.d(TAG, "retrospective failed: ${e.message?.take(60)}")
            }
        }.apply { isDaemon = true }.start()
    }

    private fun pause(reason: String) {
        run.status = "paused"
        run.result = reason
        store.persist(run)
        AgentBus.update(AgentStatus.Paused(run.id, run.goal, run.steps.size, reason))
    }

    companion object {
        private const val TAG = "AgentEngine"
        private const val STEP_BUDGET_MS = 120_000L
        private const val CHANGE_THRESHOLD = 2.0f

        private fun downscale(bitmap: Bitmap, maxSide: Int): Bitmap {
            val scale = maxSide.toFloat() / max(bitmap.width, bitmap.height)
            return if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * scale).toInt().coerceAtLeast(1),
                    (bitmap.height * scale).toInt().coerceAtLeast(1),
                    true,
                )
            } else {
                bitmap
            }
        }

        /** 3x crop around a 0-1000 target for precise re-aiming. */
        private fun zoomCrop(shot: Bitmap, tx: Int, ty: Int): Bitmap {
            val cx = tx.toLong() * shot.width / 1000
            val cy = ty.toLong() * shot.height / 1000
            val side = (min(shot.width, shot.height) / 3).coerceAtLeast(200)
            val left = (cx - side / 2).coerceIn(0, (shot.width - side).toLong().coerceAtLeast(0)).toInt()
            val top = (cy - side / 2).coerceIn(0, (shot.height - side).toLong().coerceAtLeast(0)).toInt()
            val crop = Bitmap.createBitmap(shot, left, top, side, side)
            return Bitmap.createScaledBitmap(crop, side * 3, side * 3, true)
        }

        /** Tiny grayscale thumbnail for change detection. */
        private fun thumb(bitmap: Bitmap): IntArray {
            val width = 96
            val height = max(1, bitmap.height * width / bitmap.width)
            val small = Bitmap.createScaledBitmap(bitmap, width, height, true)
            val pixels = IntArray(width * height)
            small.getPixels(pixels, 0, width, 0, 0, width, height)
            val gray = IntArray(pixels.size)
            for (i in pixels.indices) {
                val p = pixels[i]
                gray[i] = ((p shr 16 and 0xFF) * 3 + (p shr 8 and 0xFF) * 6 + (p and 0xFF)) / 10
            }
            return gray
        }

        private fun diff(a: IntArray, b: IntArray): Float {
            if (a.size != b.size) return 255f
            var total = 0L
            for (i in a.indices) total += abs(a[i] - b[i])
            return total.toFloat() / a.size
        }
    }
}
