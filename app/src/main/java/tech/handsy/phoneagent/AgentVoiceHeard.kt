package tech.handsy.phoneagent

/** Last text captured by the in-app voice button, newest-first by timestamp. */
object AgentVoiceHeard {
    @Volatile
    var text: String = ""

    @Volatile
    var timestamp: Long = 0

    fun set(value: String) {
        text = value
        timestamp = System.currentTimeMillis()
    }
}
