package tech.handsy.phoneagent.accessibility

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import rikka.shizuku.Shizuku
import tech.handsy.phoneagent.IShellService

/**
 * Anti-bot escape hatch #2: shell-privileged `input tap` through a Shizuku
 * UserService. Shell-injected motion events carry no accessibility flag, so
 * apps that drop synthetic accessibility gestures cannot tell them apart from
 * a USB debugging session — and no PC is needed after one-time pairing.
 */
object ShellInput {

    @Volatile
    private var svc: IShellService? = null

    private val userServiceArgs by lazy {
        Shizuku.UserServiceArgs(
            ComponentName("tech.handsy.phoneagent", "tech.handsy.phoneagent.ShellServiceImpl"),
        )
            .processNameSuffix("shell")
            .debuggable(true)
            .version(2)
    }

    fun binderAlive(): Boolean = try {
        Shizuku.pingBinder()
    } catch (e: Throwable) {
        false
    }

    fun granted(): Boolean = try {
        binderAlive() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (e: Throwable) {
        false
    }

    fun requestPermission() {
        try {
            if (binderAlive() && !granted()) Shizuku.requestPermission(42)
        } catch (e: Exception) {
        }
    }

    fun statusText(): String = when {
        !binderAlive() -> "Shizuku 未运行(先装 Shizuku App 并启动)"
        !granted() -> "Shizuku 已运行,未授权"
        else -> "Shizuku 已授权 ✓ 反风控输入可用"
    }

    fun ensureBound() {
        if (svc != null || !granted()) return
        try {
            Shizuku.bindUserService(
                userServiceArgs,
                object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                        svc = IShellService.Stub.asInterface(binder)
                    }

                    override fun onServiceDisconnected(name: ComponentName) {
                        svc = null
                    }
                },
            )
        } catch (e: Exception) {
        }
    }

    fun exec(cmd: String): String? {
        ensureBound()
        val service = svc ?: return null
        return try {
            service.runCmd(cmd)
        } catch (e: Exception) {
            svc = null
            null
        }
    }

    /**
     * One-tap accessibility restore: shell identity holds WRITE_SECURE_SETTINGS,
     * so the same command the PC uses works on-device via Shizuku.
     */
    fun ensureAccessibility(): Boolean {
        val current = exec("settings get secure enabled_accessibility_services")?.trim().orEmpty()
        val ours = "tech.handsy.phoneagent/tech.handsy.phoneagent.accessibility.AgentAccessibilityService"
        if (current.contains("phoneagent")) return true
        if (current == "null" || current.isBlank()) {
            exec("settings put secure enabled_accessibility_services '$ours'")
        } else {
            exec("settings put secure enabled_accessibility_services '$current:$ours'")
        }
        exec("settings put secure accessibility_enabled 1")
        return true
    }

    fun tap(x: Float, y: Float): Boolean {
        ensureBound()
        val service = svc ?: return false
        return try {
            service.runTap(x.toInt(), y.toInt()) == 0
        } catch (e: Exception) {
            svc = null
            false
        }
    }
}
