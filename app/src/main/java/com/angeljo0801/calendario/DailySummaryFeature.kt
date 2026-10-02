package com.angeljo0801.calendario

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.CalendarContract
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

object DailySummaryPrefs {
    private const val PREFS = "daily_summary_prefs"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_HOUR = "hour"
    private const val KEY_MINUTE = "minute"
    private const val KEY_ALARM_AFTER = "alarm_after"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
    }

    fun hour(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_HOUR, 8)
            .coerceIn(0, 23)

    fun minute(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_MINUTE, 0)
            .coerceIn(0, 59)

    fun setTime(context: Context, hour: Int, minute: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_HOUR, hour.coerceIn(0, 23))
            .putInt(KEY_MINUTE, minute.coerceIn(0, 59))
            .apply()
    }

    fun alarmAfterSummary(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ALARM_AFTER, true)

    fun setAlarmAfterSummary(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ALARM_AFTER, enabled)
            .apply()
    }
}

object DailySummaryScheduler {
    private const val SUMMARY_REQUEST_CODE = 8100
    private const val FOLLOW_UP_REQUEST_CODE = 8101

    fun scheduleNext(context: Context) {
        if (!DailySummaryPrefs.isEnabled(context)) {
            cancel(context)
            return
        }

        val now = LocalDateTime.now()
        val time = LocalTime.of(
            DailySummaryPrefs.hour(context),
            DailySummaryPrefs.minute(context)
        )
        var next = now.toLocalDate().atTime(time)
        if (!next.isAfter(now)) next = next.plusDays(1)

        val triggerAt = next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            SUMMARY_REQUEST_CODE,
            Intent(context, DailySummaryReceiver::class.java).setAction(DailySummaryReceiver.ACTION_DAILY),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        scheduleBestEffortExact(alarmManager, triggerAt, pendingIntent)
    }

    fun scheduleFollowUpAlarm(context: Context, summary: String) {
        if (!DailySummaryPrefs.alarmAfterSummary(context)) return

        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val triggerAt = System.currentTimeMillis() + 60_000L
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            FOLLOW_UP_REQUEST_CODE,
            Intent(context, DailyAlarmReceiver::class.java)
                .setAction(DailyAlarmReceiver.ACTION_ALARM)
                .putExtra(DailyAlarmReceiver.EXTRA_SUMMARY, summary),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val showIntent = PendingIntent.getActivity(
            context,
            FOLLOW_UP_REQUEST_CODE + 100,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        runCatching {
            alarmManager.setAlarmClock(
                AlarmManager.AlarmClockInfo(triggerAt, showIntent),
                pendingIntent
            )
        }.onFailure {
            scheduleBestEffortExact(alarmManager, triggerAt, pendingIntent)
        }
    }

    fun cancel(context: Context) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val summaryIntent = PendingIntent.getBroadcast(
            context,
            SUMMARY_REQUEST_CODE,
            Intent(context, DailySummaryReceiver::class.java).setAction(DailySummaryReceiver.ACTION_DAILY),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val followUpIntent = PendingIntent.getBroadcast(
            context,
            FOLLOW_UP_REQUEST_CODE,
            Intent(context, DailyAlarmReceiver::class.java).setAction(DailyAlarmReceiver.ACTION_ALARM),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(summaryIntent)
        alarmManager.cancel(followUpIntent)
    }

    fun canScheduleExact(context: Context): Boolean {
        val manager = context.getSystemService(AlarmManager::class.java)
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()
    }

    private fun scheduleBestEffortExact(
        alarmManager: AlarmManager,
        triggerAt: Long,
        pendingIntent: PendingIntent
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (alarmManager.canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAt,
                    pendingIntent
                )
            } else {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAt,
                    pendingIntent
                )
            }
        } else {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAt,
                pendingIntent
            )
        }
    }
}

class DailySummaryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val isTest = intent?.action == ACTION_TEST
        if (!isTest && !DailySummaryPrefs.isEnabled(context)) return

        if (!isTest) DailySummaryScheduler.scheduleNext(context)

        val summaryResult = readTodayEvents(context)
        val summary = if (summaryResult.titles.isEmpty()) {
            "Hoy no tienes eventos registrados en tus calendarios."
        } else {
            buildString {
                append("EVENTOS DE HOY")
                summaryResult.titles.forEach { title ->
                    append("\n\n• ")
                    append(title)
                }
            }
        }

        SummaryNotifier.showDailySummary(
            context = context,
            summary = summary,
            hasEvents = summaryResult.titles.isNotEmpty()
        )
        showFloatingSummary(context, summary, summaryResult.titles.isNotEmpty())

        if (!isTest && summaryResult.titles.isNotEmpty()) {
            DailySummaryScheduler.scheduleFollowUpAlarm(context, summary)
        }
    }

    private fun readTodayEvents(context: Context): CalendarSummaryResult {
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_CALENDAR
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return CalendarSummaryResult(emptyList())
        }

        val zone = ZoneId.systemDefault()
        val start = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

        val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(builder, start)
        ContentUris.appendId(builder, end)

        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN
        )

        val seen = linkedSetOf<String>()
        val titles = mutableListOf<String>()

        runCatching {
            context.contentResolver.query(
                builder.build(),
                projection,
                "${CalendarContract.Calendars.VISIBLE}=1",
                null,
                "${CalendarContract.Instances.BEGIN} ASC"
            )?.use { cursor ->
                val eventIdIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_ID)
                val titleIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.TITLE)
                val beginIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.BEGIN)

                while (cursor.moveToNext()) {
                    val eventId = cursor.getLong(eventIdIndex)
                    val begin = cursor.getLong(beginIndex)
                    val title = cursor.getString(titleIndex)
                        ?.trim()
                        .orEmpty()
                        .ifBlank { "Evento sin título" }
                    val dedupeKey = "$eventId:$begin"
                    if (seen.add(dedupeKey)) titles += title
                }
            }
        }

        return CalendarSummaryResult(titles)
    }

    private fun showFloatingSummary(context: Context, summary: String, hasEvents: Boolean) {
        if (!Settings.canDrawOverlays(context)) return

        val serviceIntent = Intent(context, DailySummaryOverlayService::class.java)
            .putExtra(DailySummaryOverlayService.EXTRA_TEXT, summary)
            .putExtra(
                DailySummaryOverlayService.EXTRA_AUTO_HIDE_SECONDS,
                if (hasEvents) 60 else 10
            )
        runCatching { context.startService(serviceIntent) }
    }

    data class CalendarSummaryResult(val titles: List<String>)

    companion object {
        const val ACTION_DAILY = "com.angeljo0801.calendario.DAILY_SUMMARY"
        const val ACTION_TEST = "com.angeljo0801.calendario.DAILY_SUMMARY_TEST"
    }
}

class DailyAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val summary = intent?.getStringExtra(EXTRA_SUMMARY)
            .orEmpty()
            .ifBlank { "Tienes eventos programados para hoy." }
        SummaryNotifier.showAlarm(context, summary)
        DailySummaryOverlayService.showIfAllowed(context, summary, 60)
    }

    companion object {
        const val ACTION_ALARM = "com.angeljo0801.calendario.DAILY_SUMMARY_ALARM"
        const val EXTRA_SUMMARY = "summary"
    }
}

class CalendarBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED -> DailySummaryScheduler.scheduleNext(context)
        }
    }
}

object SummaryNotifier {
    private const val DAILY_CHANNEL = "daily_calendar_summary"
    private const val ALARM_CHANNEL = "daily_calendar_alarm"
    private const val DAILY_NOTIFICATION_ID = 8100
    private const val ALARM_NOTIFICATION_ID = 8101

    fun showDailySummary(context: Context, summary: String, hasEvents: Boolean) {
        ensureChannels(context)
        if (!canNotify(context)) return

        val openApp = PendingIntent.getActivity(
            context,
            8110,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (hasEvents) "Eventos de hoy" else "Calendario"
        val notification = NotificationCompat.Builder(context, DAILY_CHANNEL)
            .setSmallIcon(R.drawable.ic_qs_calendar)
            .setContentTitle(title)
            .setContentText(summary.lineSequence().drop(1).firstOrNull()?.trim()?.removePrefix("• ") ?: summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(summary))
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        NotificationManagerCompat.from(context).notify(DAILY_NOTIFICATION_ID, notification)
    }

    fun showAlarm(context: Context, summary: String) {
        ensureChannels(context)
        if (!canNotify(context)) return

        val openApp = PendingIntent.getActivity(
            context,
            8111,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, ALARM_CHANNEL)
            .setSmallIcon(R.drawable.ic_qs_calendar)
            .setContentTitle("Eventos de hoy")
            .setContentText("Revisa tu resumen diario del calendario")
            .setStyle(NotificationCompat.BigTextStyle().bigText(summary))
            .setContentIntent(openApp)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setAutoCancel(true)
            .setVibrate(longArrayOf(0, 500, 250, 500))
            .build()

        NotificationManagerCompat.from(context).notify(ALARM_NOTIFICATION_ID, notification)
    }

    private fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = context.getSystemService(NotificationManager::class.java)
        val daily = NotificationChannel(
            DAILY_CHANNEL,
            "Resumen diario del calendario",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Muestra el resumen de los eventos del día."
        }

        val alarmSound: Uri = Settings.System.DEFAULT_ALARM_ALERT_URI
        val alarmAudio = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .build()
        val alarm = NotificationChannel(
            ALARM_CHANNEL,
            "Alarma de eventos del día",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Alarma un minuto después del resumen diario cuando hay eventos."
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 500, 250, 500)
            setSound(alarmSound, alarmAudio)
        }

        manager.createNotificationChannel(daily)
        manager.createNotificationChannel(alarm)
    }

    private fun canNotify(context: Context): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
    }
}

class DailySummaryOverlayService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var overlayView: View? = null
    private var windowManager: WindowManager? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val text = intent?.getStringExtra(EXTRA_TEXT).orEmpty()
        val seconds = intent?.getIntExtra(EXTRA_AUTO_HIDE_SECONDS, 60) ?: 60
        if (text.isBlank() || !Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        showOverlay(text, seconds)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        removeOverlay()
        super.onDestroy()
    }

    private fun showOverlay(text: String, seconds: Int) {
        removeOverlay()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val density = resources.displayMetrics.density
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                (18 * density).toInt(),
                (18 * density).toInt(),
                (18 * density).toInt(),
                (18 * density).toInt()
            )
            background = GradientDrawable().apply {
                setColor(Color.argb(235, 34, 34, 34))
                cornerRadius = 16 * density
            }
            elevation = 12 * density
        }

        val textView = TextView(this).apply {
            this.text = "$text\n\nToca este mensaje para cerrarlo."
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.START
        }
        container.addView(textView)
        container.setOnClickListener {
            removeOverlay()
            stopSelf()
        }

        val width = (resources.displayMetrics.widthPixels * 0.92f).toInt()
        val params = WindowManager.LayoutParams(
            width,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        overlayView = container
        runCatching { windowManager?.addView(container, params) }
            .onFailure {
                overlayView = null
                stopSelf()
                return
            }

        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            removeOverlay()
            stopSelf()
        }, seconds.coerceAtLeast(1) * 1_000L)
    }

    private fun removeOverlay() {
        handler.removeCallbacksAndMessages(null)
        overlayView?.let { view ->
            runCatching { windowManager?.removeView(view) }
        }
        overlayView = null
    }

    companion object {
        const val EXTRA_TEXT = "text"
        const val EXTRA_AUTO_HIDE_SECONDS = "auto_hide_seconds"

        fun showIfAllowed(context: Context, text: String, seconds: Int) {
            if (!Settings.canDrawOverlays(context)) return
            val intent = Intent(context, DailySummaryOverlayService::class.java)
                .putExtra(EXTRA_TEXT, text)
                .putExtra(EXTRA_AUTO_HIDE_SECONDS, seconds)
            runCatching { context.startService(intent) }
        }
    }
}
