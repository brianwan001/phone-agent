package tech.handsy.phoneagent.service

import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import tech.handsy.phoneagent.VoiceEntryActivity

/**
 * Quick-settings tile: pull down the shade, tap "Agent 语音" and speak the task.
 */
class AgentVoiceTile : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.let {
            it.state = if (AgentService.active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            it.updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        // One tap straight into the app.
        startActivityAndCollapse(
            Intent(this, tech.handsy.phoneagent.MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
