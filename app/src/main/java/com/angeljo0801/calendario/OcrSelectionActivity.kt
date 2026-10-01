package com.angeljo0801.calendario

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.angeljo0801.calendario.databinding.ActivityOcrSelectionBinding
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File

class OcrSelectionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOcrSelectionBinding
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private var bitmap: Bitmap? = null
    private var imagePath: String? = null
    private var panelCollapsed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOcrSelectionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyBottomInsets()

        imagePath = intent.getStringExtra(EXTRA_IMAGE_PATH)
        val path = imagePath
        if (path.isNullOrBlank()) {
            Toast.makeText(this, "No encontré la captura.", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        bitmap = BitmapFactory.decodeFile(path)
        val loaded = bitmap
        if (loaded == null) {
            Toast.makeText(this, "No pude abrir la captura.", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        binding.captureImage.setImageBitmap(loaded)
        binding.ocrOverlay.setImageSize(loaded.width, loaded.height)
        setupActions()
        runOcr(loaded)
    }

    private fun applyBottomInsets() {
        val panel = binding.bottomPanel
        val baseLeft = panel.paddingLeft
        val baseTop = panel.paddingTop
        val baseRight = panel.paddingRight
        val baseBottom = panel.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(panel) { view, insets ->
            val navigationBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            val safeBottom = maxOf(navigationBars.bottom, ime.bottom, cutout.bottom)

            view.setPadding(
                baseLeft,
                baseTop,
                baseRight,
                baseBottom + safeBottom
            )
            insets
        }
        ViewCompat.requestApplyInsets(panel)
    }

    private fun setupActions() {
        binding.ocrOverlay.onSelectionChanged = { text ->
            val hasText = text.isNotBlank()
            binding.copyButton.isEnabled = hasText
            binding.calendarButton.isEnabled = hasText
            binding.selectedText.text = if (hasText) {
                text
            } else {
                "Toca una palabra y arrastra hasta el final del texto que quieras."
            }
        }

        binding.collapsePanelButton.setOnClickListener {
            setPanelCollapsed(!panelCollapsed)
        }

        binding.selectAllButton.setOnClickListener {
            binding.ocrOverlay.selectAllWords()
        }

        binding.copyButton.setOnClickListener {
            val text = binding.ocrOverlay.selectedText()
            if (text.isBlank()) return@setOnClickListener
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Texto OCR", text))
            Toast.makeText(this, "Texto copiado", Toast.LENGTH_SHORT).show()
        }

        binding.calendarButton.setOnClickListener {
            val text = binding.ocrOverlay.selectedText()
            if (text.isBlank()) return@setOnClickListener
            val openCalendar = Intent(this, MainActivity::class.java).apply {
                action = Intent.ACTION_SEND
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(openCalendar)
            finish()
        }

        binding.closeButton.setOnClickListener { finish() }
    }

    private fun setPanelCollapsed(collapsed: Boolean) {
        panelCollapsed = collapsed
        val detailsVisibility = if (collapsed) View.GONE else View.VISIBLE
        binding.selectedText.visibility = detailsVisibility
        binding.actionsScroll.visibility = detailsVisibility
        binding.collapsePanelButton.text = if (collapsed) "⌃" else "⌄"
        binding.collapsePanelButton.contentDescription = if (collapsed) {
            "Expandir panel"
        } else {
            "Minimizar panel"
        }
    }

    private fun runOcr(source: Bitmap) {
        binding.progress.visibility = View.VISIBLE
        binding.statusText.text = "Reconociendo texto en la captura…"
        binding.copyButton.isEnabled = false
        binding.calendarButton.isEnabled = false
        binding.selectAllButton.isEnabled = false

        recognizer.process(InputImage.fromBitmap(source, 0))
            .addOnSuccessListener { result ->
                var order = 0
                val words = buildList {
                    result.textBlocks.forEach { block ->
                        block.lines.forEach { line ->
                            line.elements.forEach { element ->
                                val box = element.boundingBox ?: return@forEach
                                val text = element.text.trim()
                                if (text.isNotBlank()) {
                                    add(OcrOverlayView.WordBox(text, box, order++))
                                }
                            }
                        }
                    }
                }

                binding.ocrOverlay.setWords(words)
                binding.progress.visibility = View.GONE
                binding.selectAllButton.isEnabled = words.isNotEmpty()
                binding.statusText.text = if (words.isEmpty()) {
                    "No encontré texto legible en esta captura."
                } else {
                    "Texto detectado. Selecciona sobre la imagen."
                }
            }
            .addOnFailureListener { error ->
                binding.progress.visibility = View.GONE
                binding.statusText.text = "No pude reconocer el texto: ${error.message.orEmpty()}"
                Toast.makeText(this, "Falló el OCR", Toast.LENGTH_LONG).show()
            }
    }

    override fun onDestroy() {
        recognizer.close()
        binding.captureImage.setImageDrawable(null)
        bitmap?.recycle()
        bitmap = null
        imagePath?.let { path -> runCatching { File(path).delete() } }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_IMAGE_PATH = "ocr_image_path"
    }
}
