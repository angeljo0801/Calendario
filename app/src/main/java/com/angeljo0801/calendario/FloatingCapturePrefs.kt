package com.angeljo0801.calendario

import android.content.Context

object FloatingCapturePrefs {
    private const val PREFS = "floating_capture"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_X = "bubble_x"
    private const val KEY_Y = "bubble_y"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
    }

    fun bubbleX(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_X, 24)

    fun bubbleY(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_Y, 280)

    fun saveBubblePosition(context: Context, x: Int, y: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_X, x)
            .putInt(KEY_Y, y)
            .apply()
    }
}
