package com.hkfuertes.shairport

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick Settings tile: the same start/stop as the "AirPlay receiver" switch. */
class ReceiverTileService : TileService() {
    override fun onStartListening() = refresh()

    override fun onClick() {
        val preferences = Prefs.get(this)
        val enabled = !preferences.getBoolean(Prefs.RECEIVER_ENABLED, true)
        preferences.edit().putBoolean(Prefs.RECEIVER_ENABLED, enabled).commit()
        // Starting needs the service; a running service stops itself when the setting turns off.
        if (enabled) ReceiverService.start(this)
        refresh()
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val enabled = Prefs.get(this).getBoolean(Prefs.RECEIVER_ENABLED, true)
        tile.state = if (enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.updateTile()
    }
}
