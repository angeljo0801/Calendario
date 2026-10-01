package com.angeljo0801.calendario

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

class OverlayCaptureService : Service() {

    private lateinit var windowManager: WindowManager
    private var bubbleView: TextView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var projectionCallback: MediaProjection.Callback? = null

    private lateinit var captureThread: HandlerThread
    private lateinit var captureHandler: Handler
    private val mainHandler = Handler(android.os.Looper.getMainLooper())
    private val captureRequested = AtomicBoolean(false)

    private var screenWidth = 0
    private var screenHeight = 0
    private var densityDpi = 0

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        captureThread = HandlerThread("CalendarioCapture").also { it.start() }
        captureHandler = Handler(captureThread.looper)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            FloatingCapturePrefs.setEnabled(this, false)
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundForProjection()

        if (mediaProjection == null) {
            val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE) ?: Int.MIN_VALUE
            val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent?.getParcelableExtra(EXTRA_RESULT_DATA)
            }

            if (resultCode == Int.MIN_VALUE || resultData == null) {
                Toast.makeText(this, "Falta autorización para capturar la pantalla.", Toast.LENGTH_LONG).show()
                stopSelf()
                return START_NOT_STICKY
            }

            startProjection(resultCode, resultData)
        }

        showBubble()
        FloatingCapturePrefs.setEnabled(this, true)
        return START_NOT_STICKY
    }

    private fun startForegroundForProjection() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_qs_calendar)
            .setContentTitle("Calendario OCR activo")
            .setContentText("Toca el botón flotante para capturar y seleccionar texto.")
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Captura OCR de Calendario",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Mantiene disponible el botón flotante de captura OCR."
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun startProjection(resultCode: Int, resultData: Intent) {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        densityDpi = metrics.densityDpi

        val projectionManager = getSystemService(MediaProjectionManager::class.java)
        mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)

        projectionCallback = object : MediaProjection.Callback() {
            override fun onStop() {
                mainHandler.post {
                    FloatingCapturePrefs.setEnabled(this@OverlayCaptureService, false)
                    stopSelf()
                }
            }
        }.also { callback ->
            mediaProjection?.registerCallback(callback, mainHandler)
        }

        imageReader = ImageReader.newInstance(
            screenWidth,
            screenHeight,
            PixelFormat.RGBA_8888,
            2
        ).also { reader ->
            reader.setOnImageAvailableListener({ source ->
                val image = source.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    if (captureRequested.compareAndSet(true, false)) {
                        saveAndOpenSelection(image)
                    }
                } finally {
                    image.close()
                }
            }, captureHandler)
        }

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "Calendario OCR",
            screenWidth,
            screenHeight,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            captureHandler
        )
    }

    private fun showBubble() {
        if (bubbleView != null) return

        val size = dp(58)
        val bubble = TextView(this).apply {
            text = "🗓"
            textSize = 28f
            gravity = Gravity.CENTER
            background = getDrawable(R.drawable.bubble_background)
            elevation = dp(8).toFloat()
            contentDescription = "Capturar pantalla para OCR"
        }

        val params = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = FloatingCapturePrefs.bubbleX(this@OverlayCaptureService)
            y = FloatingCapturePrefs.bubbleY(this@OverlayCaptureService)
        }

        var startTouchX = 0f
        var startTouchY = 0f
        var startWindowX = 0
        var startWindowY = 0
        var moved = false

        bubble.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startTouchX = event.rawX
                    startTouchY = event.rawY
                    startWindowX = params.x
                    startWindowY = params.y
                    moved = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - startTouchX).toInt()
                    val dy = (event.rawY - startTouchY).toInt()
                    if (abs(dx) > dp(4) || abs(dy) > dp(4)) moved = true
                    params.x = startWindowX + dx
                    params.y = startWindowY + dy
                    runCatching { windowManager.updateViewLayout(bubble, params) }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    FloatingCapturePrefs.saveBubblePosition(
                        this@OverlayCaptureService,
                        params.x,
                        params.y
                    )
                    if (!moved) requestScreenshot()
                    true
                }

                else -> false
            }
        }

        windowManager.addView(bubble, params)
        bubbleView = bubble
        bubbleParams = params
    }

    private fun requestScreenshot() {
        val bubble = bubbleView ?: return
        if (captureRequested.get()) return

        bubble.visibility = View.INVISIBLE
        mainHandler.postDelayed({
            captureRequested.set(true)
            mainHandler.postDelayed({
                if (captureRequested.compareAndSet(true, false)) {
                    bubble.visibility = View.VISIBLE
                    Toast.makeText(this, "No pude obtener la captura. Inténtalo otra vez.", Toast.LENGTH_SHORT).show()
                }
            }, 1800)
        }, 220)
    }

    private fun saveAndOpenSelection(image: Image) {
        val plane = image.planes.firstOrNull() ?: return showBubbleAgain()
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * screenWidth
        val bitmapWidth = screenWidth + rowPadding / pixelStride

        var paddedBitmap: Bitmap? = null
        var croppedBitmap: Bitmap? = null

        try {
            paddedBitmap = Bitmap.createBitmap(bitmapWidth, screenHeight, Bitmap.Config.ARGB_8888)
            paddedBitmap.copyPixelsFromBuffer(buffer)
            croppedBitmap = Bitmap.createBitmap(paddedBitmap, 0, 0, screenWidth, screenHeight)

            val file = File(cacheDir, "ocr_capture_${System.currentTimeMillis()}.jpg")
            FileOutputStream(file).use { output ->
                croppedBitmap.compress(Bitmap.CompressFormat.JPEG, 94, output)
            }

            mainHandler.post {
                bubbleView?.visibility = View.VISIBLE
                val openIntent = Intent(this, OcrSelectionActivity::class.java).apply {
                    putExtra(OcrSelectionActivity.EXTRA_IMAGE_PATH, file.absolutePath)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                runCatching { startActivity(openIntent) }
                    .onFailure {
                        Toast.makeText(this, "No pude abrir la selección OCR.", Toast.LENGTH_LONG).show()
                    }
            }
        } catch (_: Throwable) {
            mainHandler.post {
                bubbleView?.visibility = View.VISIBLE
                Toast.makeText(this, "No pude procesar la captura.", Toast.LENGTH_LONG).show()
            }
        } finally {
            if (croppedBitmap !== paddedBitmap) croppedBitmap?.recycle()
            paddedBitmap?.recycle()
        }
    }

    private fun showBubbleAgain() {
        mainHandler.post { bubbleView?.visibility = View.VISIBLE }
    }

    override fun onDestroy() {
        captureRequested.set(false)
        FloatingCapturePrefs.setEnabled(this, false)

        bubbleView?.let { view -> runCatching { windowManager.removeView(view) } }
        bubbleView = null

        imageReader?.setOnImageAvailableListener(null, null)
        imageReader?.close()
        imageReader = null

        virtualDisplay?.release()
        virtualDisplay = null

        projectionCallback?.let { callback ->
            runCatching { mediaProjection?.unregisterCallback(callback) }
        }
        projectionCallback = null
        runCatching { mediaProjection?.stop() }
        mediaProjection = null

        captureThread.quitSafely()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    companion object {
        const val ACTION_STOP = "com.angeljo0801.calendario.STOP_CAPTURE"
        const val EXTRA_RESULT_CODE = "projection_result_code"
        const val EXTRA_RESULT_DATA = "projection_result_data"

        private const val CHANNEL_ID = "calendar_ocr_capture"
        private const val NOTIFICATION_ID = 4102
    }
}
