package com.angeljo0801.calendario

import android.Manifest
import android.app.DatePickerDialog
import android.app.StatusBarManager
import android.app.TimePickerDialog
import android.content.ComponentName
import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.CalendarContract
import android.provider.Settings
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.angeljo0801.calendario.databinding.ActivityMainBinding
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import java.util.TimeZone

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var selectedDate: LocalDate = LocalDate.now()
    private var selectedTime: LocalTime = LocalTime.now()
    private var calendars: List<CalendarOption> = emptyList()
    private var lastEventUri: Uri? = null

    private val durationMinutes = intArrayOf(15, 30, 60, 90, 120)
    private val reminderMinutes = intArrayOf(-1, 0, 5, 15, 30, 60)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val readGranted = result[Manifest.permission.READ_CALENDAR] == true ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
        val writeGranted = result[Manifest.permission.WRITE_CALENDAR] == true ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED
        val granted = readGranted && writeGranted

        if (granted) {
            binding.permissionButton.visibility = View.GONE
            loadCalendars()
            if (DailySummaryPrefs.isEnabled(this)) DailySummaryScheduler.scheduleNext(this)
            setStatus("Permiso concedido. Elige dónde guardar el recordatorio.")
        } else {
            binding.permissionButton.visibility = View.VISIBLE
            setStatus("Necesito permiso de calendario para guardar eventos automáticamente.")
        }
        updateDailySummaryUi()
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        updateDailySummaryUi()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupStaticSpinners()
        setupActions()
        setupFloatingCapture()
        setupDailySummary()
        applyIncomingText(intent)
        ensureCalendarPermission()
    }

    override fun onResume() {
        super.onResume()
        if (::binding.isInitialized) {
            updateFloatingCaptureUi()
            updateDailySummaryUi()
            if (DailySummaryPrefs.isEnabled(this)) DailySummaryScheduler.scheduleNext(this)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyIncomingText(intent)
    }

    private fun setupStaticSpinners() {
        binding.durationSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            listOf("15 minutos", "30 minutos", "1 hora", "1 h 30 min", "2 horas")
        )
        binding.durationSpinner.setSelection(2)

        binding.reminderSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            listOf("Sin aviso", "Al momento", "5 min antes", "15 min antes", "30 min antes", "1 hora antes")
        )

        val savedReminderIndex = getPreferences(MODE_PRIVATE)
            .getInt(KEY_REMINDER_INDEX, 3)
            .coerceIn(0, reminderMinutes.lastIndex)
        binding.reminderSpinner.setSelection(savedReminderIndex)
    }

    private fun setupActions() {
        binding.dateButton.setOnClickListener {
            DatePickerDialog(
                this,
                { _, year, month, day ->
                    selectedDate = LocalDate.of(year, month + 1, day)
                    refreshDateTimeButtons()
                },
                selectedDate.year,
                selectedDate.monthValue - 1,
                selectedDate.dayOfMonth
            ).show()
        }

        binding.timeButton.setOnClickListener {
            TimePickerDialog(
                this,
                { _, hour, minute ->
                    selectedTime = LocalTime.of(hour, minute)
                    refreshDateTimeButtons()
                },
                selectedTime.hour,
                selectedTime.minute,
                android.text.format.DateFormat.is24HourFormat(this)
            ).show()
        }

        binding.permissionButton.setOnClickListener { requestCalendarPermission() }
        binding.saveButton.setOnClickListener { saveEvent() }
        binding.openCalendarButton.setOnClickListener {
            lastEventUri?.let { uri ->
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                    .onFailure { Toast.makeText(this, "No pude abrir ese evento.", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    private fun setupFloatingCapture() {
        binding.floatingModeButton.setOnClickListener {
            if (FloatingCapturePrefs.isEnabled(this)) {
                startService(
                    Intent(this, OverlayCaptureService::class.java)
                        .setAction(OverlayCaptureService.ACTION_STOP)
                )
                FloatingCapturePrefs.setEnabled(this, false)
                updateFloatingCaptureUi()
            } else {
                startActivity(Intent(this, CapturePermissionActivity::class.java))
            }
        }

        binding.addTileButton.setOnClickListener { requestQuickSettingsTile() }
        updateFloatingCaptureUi()
    }

    private fun updateFloatingCaptureUi() {
        val enabled = FloatingCapturePrefs.isEnabled(this)
        binding.floatingModeButton.text = if (enabled) {
            "Desactivar botón flotante"
        } else {
            "Activar botón flotante OCR"
        }
        binding.floatingStatusText.text = if (enabled) {
            "Activo: toca 🗓 sobre cualquier app para capturar la pantalla y seleccionar texto."
        } else {
            "Desactivado. Puedes iniciarlo aquí o desde el botón Calendario OCR del panel rápido."
        }
    }

    private fun setupDailySummary() {
        binding.dailySummarySwitch.isChecked = DailySummaryPrefs.isEnabled(this)
        binding.dailyAlarmSwitch.isChecked = DailySummaryPrefs.alarmAfterSummary(this)
        refreshDailySummaryTimeButton()

        binding.dailySummarySwitch.setOnCheckedChangeListener { _, enabled ->
            DailySummaryPrefs.setEnabled(this, enabled)
            if (enabled) {
                if (!hasCalendarPermission()) requestCalendarPermission()
                requestNotificationPermissionIfNeeded()
                DailySummaryScheduler.scheduleNext(this)
            } else {
                DailySummaryScheduler.cancel(this)
            }
            updateDailySummaryUi()
        }

        binding.dailyAlarmSwitch.setOnCheckedChangeListener { _, enabled ->
            DailySummaryPrefs.setAlarmAfterSummary(this, enabled)
            updateDailySummaryUi()
        }

        binding.dailySummaryTimeButton.setOnClickListener {
            TimePickerDialog(
                this,
                { _, hour, minute ->
                    DailySummaryPrefs.setTime(this, hour, minute)
                    refreshDailySummaryTimeButton()
                    if (DailySummaryPrefs.isEnabled(this)) DailySummaryScheduler.scheduleNext(this)
                    updateDailySummaryUi()
                },
                DailySummaryPrefs.hour(this),
                DailySummaryPrefs.minute(this),
                android.text.format.DateFormat.is24HourFormat(this)
            ).show()
        }

        binding.dailySummaryTestButton.setOnClickListener {
            if (!hasCalendarPermission()) {
                requestCalendarPermission()
                Toast.makeText(this, "Concede el permiso de calendario y vuelve a probar.", Toast.LENGTH_LONG).show()
            } else {
                requestNotificationPermissionIfNeeded()
                sendBroadcast(
                    Intent(this, DailySummaryReceiver::class.java)
                        .setAction(DailySummaryReceiver.ACTION_TEST)
                )
                Toast.makeText(this, "Resumen de prueba ejecutado.", Toast.LENGTH_SHORT).show()
            }
        }

        binding.dailyOverlayPermissionButton.setOnClickListener {
            runCatching {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            }
        }

        binding.dailyExactAlarmButton.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                runCatching {
                    startActivity(
                        Intent(
                            Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                            Uri.parse("package:$packageName")
                        )
                    )
                }
            }
        }

        updateDailySummaryUi()
    }

    private fun refreshDailySummaryTimeButton() {
        val time = LocalTime.of(
            DailySummaryPrefs.hour(this),
            DailySummaryPrefs.minute(this)
        )
        val formatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
            .withLocale(Locale.getDefault())
        binding.dailySummaryTimeButton.text = "Hora: ${time.format(formatter)}"
    }

    private fun updateDailySummaryUi() {
        if (!::binding.isInitialized) return

        val enabled = DailySummaryPrefs.isEnabled(this)
        if (binding.dailySummarySwitch.isChecked != enabled) {
            binding.dailySummarySwitch.isChecked = enabled
        }
        val alarmEnabled = DailySummaryPrefs.alarmAfterSummary(this)
        if (binding.dailyAlarmSwitch.isChecked != alarmEnabled) {
            binding.dailyAlarmSwitch.isChecked = alarmEnabled
        }
        refreshDailySummaryTimeButton()

        val overlayAllowed = Settings.canDrawOverlays(this)
        val exactAllowed = DailySummaryScheduler.canScheduleExact(this)
        val alarmText = if (alarmEnabled) {
            "alarma +1 min activada"
        } else {
            "sin alarma posterior"
        }

        binding.dailySummaryStatusText.text = if (enabled) {
            buildString {
                append("Activo • $alarmText • lee todos los calendarios visibles.")
                if (!overlayAllowed) append(" El aviso flotante necesita permiso de superposición.")
                if (!exactAllowed) append(" Android puede retrasar un poco la hora hasta que permitas alarmas exactas.")
            }
        } else {
            "Desactivado. Al activarlo revisará los eventos del día a la hora elegida."
        }

        binding.dailyOverlayPermissionButton.visibility =
            if (overlayAllowed) View.GONE else View.VISIBLE
        binding.dailyExactAlarmButton.visibility =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !exactAllowed) View.VISIBLE else View.GONE
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun requestQuickSettingsTile() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val statusBarManager = getSystemService(StatusBarManager::class.java)
            statusBarManager.requestAddTileService(
                ComponentName(this, CalendarTileService::class.java),
                "Calendario OCR",
                Icon.createWithResource(this, R.drawable.ic_qs_calendar),
                mainExecutor
            ) { result ->
                val message = if (result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED) {
                    "Calendario OCR agregado al panel rápido."
                } else {
                    "Si no aparece, edita el panel rápido y añade Calendario OCR."
                }
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        } else {
            Toast.makeText(
                this,
                "Edita el panel rápido del teléfono y arrastra Calendario OCR a tus botones.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun applyIncomingText(intent: Intent?) {
        val incoming = when (intent?.action) {
            Intent.ACTION_SEND -> intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            Intent.ACTION_PROCESS_TEXT -> intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
            else -> null
        }.orEmpty().trim()

        val defaultDateTime = LocalDateTime.now()
            .plusHours(1)
            .withMinute(0)
            .withSecond(0)
            .withNano(0)

        if (incoming.isNotBlank()) {
            binding.noteEdit.setText(incoming)
            binding.titleEdit.setText(makeTitle(incoming))
            val parsed = DateTimeParser.parse(incoming)
            selectedDate = parsed.date ?: defaultDateTime.toLocalDate()
            selectedTime = parsed.time ?: defaultDateTime.toLocalTime()
            setStatus("Texto recibido. Revisa los datos y toca Guardar en calendario.")
        } else {
            selectedDate = defaultDateTime.toLocalDate()
            selectedTime = defaultDateTime.toLocalTime()
        }

        refreshDateTimeButtons()
    }

    private fun makeTitle(text: String): String {
        val firstUsefulLine = text.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() }
            .orEmpty()

        if (firstUsefulLine.isBlank()) return "Recordatorio"
        return firstUsefulLine.take(80)
    }

    private fun refreshDateTimeButtons() {
        val dateFormatter = DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.getDefault())
        val timeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
            .withLocale(Locale.getDefault())

        binding.dateButton.text = selectedDate.format(dateFormatter)
        binding.timeButton.text = selectedTime.format(timeFormatter)
    }

    private fun ensureCalendarPermission() {
        if (hasCalendarPermission()) {
            binding.permissionButton.visibility = View.GONE
            loadCalendars()
        } else {
            binding.permissionButton.visibility = View.VISIBLE
            requestCalendarPermission()
        }
    }

    private fun hasCalendarPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestCalendarPermission() {
        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.READ_CALENDAR,
                Manifest.permission.WRITE_CALENDAR
            )
        )
    }

    private fun loadCalendars() {
        if (!hasCalendarPermission()) return

        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL
        )

        val found = mutableListOf<CalendarOption>()
        runCatching {
            contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                projection,
                "${CalendarContract.Calendars.VISIBLE}=1 AND ${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL}>=?",
                arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString()),
                "${CalendarContract.Calendars.CALENDAR_DISPLAY_NAME} ASC"
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(CalendarContract.Calendars._ID)
                val nameIndex = cursor.getColumnIndexOrThrow(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME)
                val accountIndex = cursor.getColumnIndexOrThrow(CalendarContract.Calendars.ACCOUNT_NAME)

                while (cursor.moveToNext()) {
                    found += CalendarOption(
                        id = cursor.getLong(idIndex),
                        name = cursor.getString(nameIndex).orEmpty().ifBlank { "Calendario" },
                        account = cursor.getString(accountIndex).orEmpty()
                    )
                }
            }
        }.onFailure {
            setStatus("No pude leer los calendarios: ${it.message.orEmpty()}")
        }

        calendars = found
        binding.calendarSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            calendars.map { option ->
                if (option.account.isBlank()) option.name else "${option.name} • ${option.account}"
            }
        )

        if (calendars.isEmpty()) {
            setStatus("No encontré un calendario en el que esta app pueda escribir.")
            binding.saveButton.isEnabled = false
            return
        }

        binding.saveButton.isEnabled = true
        val savedCalendarId = getPreferences(MODE_PRIVATE).getLong(KEY_CALENDAR_ID, -1L)
        val savedIndex = calendars.indexOfFirst { it.id == savedCalendarId }
        if (savedIndex >= 0) binding.calendarSpinner.setSelection(savedIndex)
    }

    private fun saveEvent() {
        if (!hasCalendarPermission()) {
            setStatus("Concede el permiso para guardar automáticamente.")
            requestCalendarPermission()
            return
        }

        val calendar = calendars.getOrNull(binding.calendarSpinner.selectedItemPosition)
        if (calendar == null) {
            loadCalendars()
            setStatus("Selecciona un calendario disponible.")
            return
        }

        val title = binding.titleEdit.text?.toString()?.trim().orEmpty().ifBlank { "Recordatorio" }
        val note = binding.noteEdit.text?.toString()?.trim().orEmpty()
        val duration = durationMinutes[binding.durationSpinner.selectedItemPosition]
        val reminderIndex = binding.reminderSpinner.selectedItemPosition
        val reminder = reminderMinutes[reminderIndex]

        val startMillis = selectedDate.atTime(selectedTime)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        val endMillis = startMillis + duration * 60_000L

        binding.saveButton.isEnabled = false

        runCatching {
            val eventValues = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calendar.id)
                put(CalendarContract.Events.TITLE, title)
                put(CalendarContract.Events.DESCRIPTION, note)
                put(CalendarContract.Events.DTSTART, startMillis)
                put(CalendarContract.Events.DTEND, endMillis)
                put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
                put(CalendarContract.Events.STATUS, CalendarContract.Events.STATUS_CONFIRMED)
                put(CalendarContract.Events.HAS_ALARM, if (reminder >= 0) 1 else 0)
            }

            val eventUri = contentResolver.insert(CalendarContract.Events.CONTENT_URI, eventValues)
                ?: error("Android no devolvió el evento creado.")

            if (reminder >= 0) {
                val eventId = ContentUris.parseId(eventUri)
                val reminderValues = ContentValues().apply {
                    put(CalendarContract.Reminders.EVENT_ID, eventId)
                    put(CalendarContract.Reminders.MINUTES, reminder)
                    put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
                }
                contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, reminderValues)
            }

            lastEventUri = eventUri
            getPreferences(MODE_PRIVATE).edit()
                .putLong(KEY_CALENDAR_ID, calendar.id)
                .putInt(KEY_REMINDER_INDEX, reminderIndex)
                .apply()

            eventUri
        }.onSuccess {
            binding.openCalendarButton.visibility = View.VISIBLE
            setStatus("✓ Guardado en ${calendar.name} para ${binding.dateButton.text} a las ${binding.timeButton.text}.")
            Toast.makeText(this, "Recordatorio guardado", Toast.LENGTH_SHORT).show()
        }.onFailure { error ->
            setStatus("No se pudo guardar: ${error.message ?: "error desconocido"}")
            Toast.makeText(this, "No se pudo guardar el recordatorio", Toast.LENGTH_LONG).show()
        }

        binding.saveButton.isEnabled = calendars.isNotEmpty()
    }

    private fun setStatus(message: String) {
        binding.statusText.text = message
    }

    data class CalendarOption(
        val id: Long,
        val name: String,
        val account: String
    )

    companion object {
        private const val KEY_CALENDAR_ID = "calendar_id"
        private const val KEY_REMINDER_INDEX = "reminder_index"
    }
}
