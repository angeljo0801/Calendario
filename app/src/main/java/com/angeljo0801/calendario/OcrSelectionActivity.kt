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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOcrSelectionBinding.inflate(layoutInflater)
        setContentView(binding.root)

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
