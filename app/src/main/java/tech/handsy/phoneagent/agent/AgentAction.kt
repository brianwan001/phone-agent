package tech.handsy.phoneagent.agent

sealed class AgentAction {
    data class Tap(val x: Int, val y: Int) : AgentAction()
    data class LongPress(val x: Int, val y: Int) : AgentAction()
    data class Swipe(val x1: Int, val y1: Int, val x2: Int, val y2: Int) : AgentAction()
    data class Stroke(val points: List<Int>) : AgentAction()
    data class Circle(val x: Int, val y: Int, val r: Int) : AgentAction()
    data class Type(val text: String) : AgentAction()
    data class Launch(val name: String) : AgentAction()
    data class FindApp(val name: String) : AgentAction()
    data class WaitText(val text: String) : AgentAction()
    data class Zoom(val x: Int, val y: Int) : AgentAction()
    data class Key(val name: String) : AgentAction()
    data object Home : AgentAction()
    data object Back : AgentAction()
    data object Recents : AgentAction()
    data object Wait : AgentAction()
    data object Replan : AgentAction()
    data class Done(val answer: String) : AgentAction()
    data class Fail(val reason: String) : AgentAction()
}

object ActionParser {

    fun parse(line: String): AgentAction? {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#")) return null
        val parts = trimmed.split(Regex("\\s+"))
        val cmd = parts[0].lowercase()
        val nums = parts.drop(1).mapNotNull { it.toIntOrNull() }
        val rest = parts.drop(1).joinToString(" ")
        fun need(n: Int) = nums.size >= n
        return when (cmd) {
            "tap" -> if (need(2)) AgentAction.Tap(nums[0], nums[1]) else null
            "long_press", "longpress" -> if (need(2)) AgentAction.LongPress(nums[0], nums[1]) else null
            "swipe" -> if (need(4)) AgentAction.Swipe(nums[0], nums[1], nums[2], nums[3]) else null
            "path" -> if (nums.size >= 4 && nums.size % 2 == 0) AgentAction.Stroke(nums) else null
            "circle" -> if (need(3)) AgentAction.Circle(nums[0], nums[1], nums[2]) else null
            "type" -> if (parts.size >= 2) AgentAction.Type(rest) else null
            "launch" -> if (parts.size >= 2) AgentAction.Launch(rest) else null
            "find_app" -> if (parts.size >= 2) AgentAction.FindApp(rest) else null
            "wait_text" -> if (parts.size >= 2) AgentAction.WaitText(rest) else null
            "zoom" -> if (need(2)) AgentAction.Zoom(nums[0], nums[1]) else null
            "key" -> if (parts.size >= 2) AgentAction.Key(parts[1].lowercase()) else null
            "home" -> AgentAction.Home
            "back" -> AgentAction.Back
            "recents" -> AgentAction.Recents
            "wait" -> AgentAction.Wait
            "replan" -> AgentAction.Replan
            "done" -> AgentAction.Done(rest.ifEmpty { "done" })
            "fail" -> AgentAction.Fail(rest.ifEmpty { "failed" })
            else -> null
        }
    }

    /** Parses a THINK/DO model reply. Returns (think, do lines). */
    fun parseBlock(text: String): Pair<String, List<AgentAction>> {
        var think = ""
        val dos = mutableListOf<AgentAction>()
        var mode = 0
        for (raw in text.lines()) {
            val line = raw.trim()
            val upper = line.uppercase()
            when {
                upper.startsWith("THINK:") -> {
                    mode = 1
                    think = line.substring(6).trim()
                }
                upper.startsWith("DO:") -> {
                    mode = 2
                    parse(line.substring(3))?.let { dos.add(it) }
                }
                else -> when (mode) {
                    1 -> if (line.isNotEmpty()) think = (think + " " + line).trim()
                    2 -> if (line.isNotEmpty()) parse(line)?.let { dos.add(it) }
                }
            }
        }
        return think to dos
    }

    fun describe(action: AgentAction): String = when (action) {
        is AgentAction.Tap -> "tap ${action.x} ${action.y}"
        is AgentAction.LongPress -> "long_press ${action.x} ${action.y}"
        is AgentAction.Swipe -> "swipe ${action.x1} ${action.y1} ${action.x2} ${action.y2}"
        is AgentAction.Stroke -> "path " + action.points.joinToString(" ")
        is AgentAction.Circle -> "circle ${action.x} ${action.y} ${action.r}"
        is AgentAction.Type -> "type ${action.text}"
        is AgentAction.Launch -> "launch ${action.name}"
        is AgentAction.FindApp -> "find_app ${action.name}"
        is AgentAction.WaitText -> "wait_text ${action.text}"
        is AgentAction.Zoom -> "zoom ${action.x} ${action.y}"
        is AgentAction.Key -> "key ${action.name}"
        AgentAction.Home -> "home"
        AgentAction.Back -> "back"
        AgentAction.Recents -> "recents"
        AgentAction.Wait -> "wait"
        AgentAction.Replan -> "replan"
        is AgentAction.Done -> "done ${action.answer}"
        is AgentAction.Fail -> "fail ${action.reason}"
    }
}
