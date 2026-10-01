package com.angeljo0801.calendario

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class CalendarTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateState()
    }

    override fun onClick() {
        super.onClick()

        if (FloatingCapturePrefs.isEnabled(this)) {
            startService(
                Intent(this, OverlayCaptureService::class.java)
                    .setAction(OverlayCaptureService.ACTION_STOP)
            )
            FloatingCapturePrefs.setEnabled(this, false)
            updateState()
            return
        }

        val intent = Intent(this, CapturePermissionActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = PendingIntent.getActivity(
                this,
                2001,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun updateState() {
        qsTile?.apply {
            label = "Calendario OCR"
            state = if (FloatingCapturePrefs.isEnabled(this@CalendarTileService)) {
                Tile.STATE_ACTIVE
            } else {
                Tile.STATE_INACTIVE
            }
            updateTile()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        requestListeningState(
            this,
            ComponentName(this, CalendarTileService::class.java)
        )
    }
}
