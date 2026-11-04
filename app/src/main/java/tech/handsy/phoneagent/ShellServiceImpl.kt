package tech.handsy.phoneagent

/** Runs in the Shizuku shell process: injects taps via the shell `input` command. */
class ShellServiceImpl : IShellService.Stub() {
    override fun runTap(x: Int, y: Int): Int = try {
        Runtime.getRuntime().exec(arrayOf("input", "tap", x.toString(), y.toString())).waitFor()
    } catch (e: Exception) {
        -1
    }

    override fun runCmd(cmd: String): String = try {
        val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
        val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
        p.waitFor()
        out
    } catch (e: Exception) {
        "err: ${e.message}"
    }

    override fun destroy() = Unit
}
