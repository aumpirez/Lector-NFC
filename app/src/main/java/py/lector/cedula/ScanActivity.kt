package py.lector.cedula

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Size
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

data class MrzData(val doc: String, val birth: String, val expiry: String)

/** Lee la MRZ de la cédula paraguaya (formato TD1, 3 líneas de 30 caracteres). */
object MrzParser {

    fun checkDigit(s: String): Int {
        val weights = intArrayOf(7, 3, 1)
        var sum = 0
        for (i in s.indices) {
            val c = s[i]
            val v = when (c) {
                in '0'..'9' -> c - '0'
                in 'A'..'Z' -> c - 'A' + 10
                else -> 0
            }
            sum += v * weights[i % 3]
        }
        return sum % 10
    }

    // Corrige confusiones típicas del OCR en campos que sólo pueden ser números
    private fun toDigits(s: String): String {
        val sb = StringBuilder()
        for (c in s) {
            sb.append(
                when (c) {
                    'O', 'Q', 'D', 'U' -> '0'
                    'I', 'L', 'T' -> '1'
                    'Z' -> '2'
                    'S' -> '5'
                    'B' -> '8'
                    'G' -> '6'
                    else -> c
                }
            )
        }
        return sb.toString()
    }

    private fun clean(s: String): String =
        s.uppercase().replace(" ", "").replace("\n", "").replace('«', '<')

    private val LINE1 = Regex("[I1][A-Z<]PRY([A-Z0-9<]{9})([0-9<])([A-Z0-9<]{0,15})")
    private const val DIG = "[0-9OQDUILTZSBG]"
    private val LINE2 = Regex("(${DIG}{6})(${DIG})([MFX<])(${DIG}{6})(${DIG})")

    fun parseDocNumber(text: String): String? {
        val m = LINE1.find(clean(text)) ?: return null
        val first = m.groupValues[1]
        val cd = m.groupValues[2][0]
        val opt = m.groupValues[3]
        if (cd != '<') {
            return if (checkDigit(first) == cd - '0') first.trimEnd('<') else null
        }
        // Número de documento de más de 9 caracteres (continúa en el campo opcional)
        val ext = opt.substringBefore('<')
        if (ext.length < 2) return null
        val full = first + ext.dropLast(1)
        val c = ext.last()
        return if (c.isDigit() && checkDigit(full) == c - '0') full else null
    }

    private fun validDate(d: String): Boolean {
        val mm = d.substring(2, 4).toIntOrNull() ?: return false
        val dd = d.substring(4, 6).toIntOrNull() ?: return false
        return mm in 1..12 && dd in 1..31
    }

    fun parseDates(text: String): Pair<String, String>? {
        for (m in LINE2.findAll(clean(text))) {
            val birth = toDigits(m.groupValues[1])
            val cb = toDigits(m.groupValues[2])[0] - '0'
            val exp = toDigits(m.groupValues[4])
            val ce = toDigits(m.groupValues[5])[0] - '0'
            if (checkDigit(birth) == cb && checkDigit(exp) == ce && validDate(birth) && validDate(exp)) {
                return Pair(birth, exp)
            }
        }
        return null
    }

    fun parse(texts: List<String>): MrzData? {
        var doc: String? = null
        var dates: Pair<String, String>? = null
        for (t in texts) {
            if (doc == null) doc = parseDocNumber(t)
            if (dates == null) dates = parseDates(t)
        }
        val d = doc
        val ds = dates
        return if (d != null && ds != null) MrzData(d, ds.first, ds.second) else null
    }
}

class ScanActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var tvBottom: TextView
    private lateinit var executor: ExecutorService
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    @Volatile private var done = false
    @Volatile private var lastResult: MrzData? = null

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else {
            Toast.makeText(this, "Sin permiso de cámara no se puede escanear.", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Escanear dorso de la cédula"
        executor = Executors.newSingleThreadExecutor()

        val root = FrameLayout(this)
        previewView = PreviewView(this)
        root.addView(previewView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Recuadro del tamaño de la cédula
        val frameW = resources.displayMetrics.widthPixels * 90 / 100
        val frameH = frameW * 54 / 86
        val frame = View(this)
        frame.background = GradientDrawable().apply {
            setStroke(dp(3), Color.WHITE)
            cornerRadius = dp(14).toFloat()
            setColor(Color.TRANSPARENT)
        }
        root.addView(frame, FrameLayout.LayoutParams(frameW, frameH, Gravity.CENTER))

        // Franja amarilla: donde tienen que quedar las 3 líneas de la MRZ
        val band = View(this)
        band.background = GradientDrawable().apply {
            setStroke(dp(2), Color.YELLOW)
            cornerRadius = dp(6).toFloat()
            setColor(0x22FFEB3B)
        }
        val bandParams = FrameLayout.LayoutParams(frameW - dp(16), frameH * 40 / 100, Gravity.CENTER)
        bandParams.topMargin = frameH * 26 / 100
        root.addView(band, bandParams)

        val tvTop = TextView(this).apply {
            text = "Poné el DORSO de la cédula dentro del recuadro blanco.\n" +
                "Las 3 líneas con letras y < (empiezan con IEPRY) tienen que quedar en la franja amarilla.\n" +
                "Buena luz, sin reflejos y con la cédula plana."
            setTextColor(Color.WHITE)
            setBackgroundColor(0xAA000000.toInt())
            setPadding(dp(16), dp(14), dp(16), dp(14))
            textSize = 15f
        }
        root.addView(tvTop, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        tvBottom = TextView(this).apply {
            text = "Buscando las líneas MRZ…"
            setTextColor(Color.WHITE)
            setBackgroundColor(0xAA000000.toInt())
            setPadding(dp(16), dp(14), dp(16), dp(14))
            gravity = Gravity.CENTER
            textSize = 15f
        }
        root.addView(tvBottom, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        setContentView(root)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            permLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    @Suppress("DEPRECATION")
    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build()
            preview.setSurfaceProvider(previewView.surfaceProvider)
            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(1080, 1920))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(executor) { proxy -> analyze(proxy) }
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        }, ContextCompat.getMainExecutor(this))
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun analyze(proxy: ImageProxy) {
        val media = proxy.image
        if (media == null || done) {
            proxy.close()
            return
        }
        val input = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
        recognizer.process(input)
            .addOnSuccessListener { text ->
                val texts = ArrayList<String>()
                for (block in text.textBlocks) {
                    texts.add(block.text)
                    for (line in block.lines) texts.add(line.text)
                }
                val result = MrzParser.parse(texts)
                if (result != null && !done) {
                    // Se confirma con dos lecturas iguales seguidas para evitar errores del OCR
                    if (result == lastResult) {
                        done = true
                        val data = Intent()
                            .putExtra("doc", result.doc)
                            .putExtra("birth", result.birth)
                            .putExtra("expiry", result.expiry)
                        setResult(RESULT_OK, data)
                        finish()
                    } else {
                        lastResult = result
                        tvBottom.text = "Leyendo… mantené la cédula quieta"
                    }
                }
            }
            .addOnCompleteListener { proxy.close() }
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdown()
        recognizer.close()
    }
}
