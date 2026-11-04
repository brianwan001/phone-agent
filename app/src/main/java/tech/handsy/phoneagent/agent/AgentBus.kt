package tech.handsy.phoneagent.agent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

sealed interface AgentStatus {
    data object Idle : AgentStatus
    data class Running(
        val runId: String,
        val goal: String,
        val step: Int,
        val lastThink: String,
        val lastDo: List<String>,
        val changed: Boolean?,
    ) : AgentStatus

    data class Paused(val runId: String, val goal: String, val step: Int, val reason: String) : AgentStatus
    data class Finished(
        val goal: String,
        val ok: Boolean,
        val message: String,
        val runId: String?,
    ) : AgentStatus
}

object AgentBus {
    private val _status = MutableStateFlow<AgentStatus>(AgentStatus.Idle)
    val status: StateFlow<AgentStatus> = _status

    fun update(status: AgentStatus) {
        _status.value = status
    }
}
