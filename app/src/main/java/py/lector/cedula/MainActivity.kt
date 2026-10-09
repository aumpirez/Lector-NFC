package py.lector.cedula

import android.app.PendingIntent
import android.content.Intent
import android.graphics.BitmapFactory
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import net.sf.scuba.smartcards.CardService
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.jmrtd.BACKey
import org.jmrtd.PassportService
import org.jmrtd.lds.CardAccessFile
import org.jmrtd.lds.PACEInfo
import org.jmrtd.lds.icao.COMFile
import org.jmrtd.lds.icao.DG11File
import org.jmrtd.lds.icao.DG12File
import org.jmrtd.lds.icao.DG1File
import org.jmrtd.lds.icao.DG2File
import java.io.DataInputStream
import java.math.BigInteger
import java.security.Security

class MainActivity : AppCompatActivity() {

    private var nfcAdapter: NfcAdapter? = null
    private lateinit var etDoc: EditText
    private lateinit var etBirth: EditText
    private lateinit var etExpiry: EditText
    private lateinit var swDiag: SwitchCompat
    private lateinit var tvStatus: TextView
    private lateinit var tvResult: TextView
    private lateinit var ivPhoto: ImageView

    companion object {
        init {
            Security.removeProvider("BC")
            Security.insertProviderAt(BouncyCastleProvider(), 1)
        }

        private const val GUIDE =
            "Dorso de la cédula, 3 líneas al pie:\n\n" +
            "IEPRYAA1234567X<<<<<<<<<<<<<<<\n" +
            "     ^^^^^^^^^\n" +
            "     Nro. documento (9 caract.)\n\n" +
            "900305XM330112XPRY<<<<<<<<<<<X\n" +
            "^^^^^^  ^^^^^^\n" +
            "Nacim.  Vencimiento\n\n" +
            "APELLIDO<<NOMBRE<<<<<<<<<<<<<<\n\n" +
            "X = dígito verificador: NO se escribe.\n" +
            "Los números del ejemplo son ficticios."
    }

    private val scanLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val data = r.data
        if (r.resultCode == RESULT_OK && data != null) {
            etDoc.setText(data.getStringExtra("doc"))
            etBirth.setText(data.getStringExtra("birth"))
            etExpiry.setText(data.getStringExtra("expiry"))
            swDiag.isChecked = false
            tvStatus.text = "✅ Dorso leído. Ahora apoyá la cédula en la parte trasera del celular y no la muevas."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        etDoc = findViewById(R.id.etDocNumber)
        etBirth = findViewById(R.id.etBirth)
        etExpiry = findViewById(R.id.etExpiry)
        swDiag = findViewById(R.id.swDiag)
        tvStatus = findViewById(R.id.tvStatus)
        tvResult = findViewById(R.id.tvResult)
        ivPhoto = findViewById(R.id.ivPhoto)
        findViewById<TextView>(R.id.tvGuide).text = GUIDE

        findViewById<Button>(R.id.btnScan).setOnClickListener {
            scanLauncher.launch(Intent(this, ScanActivity::class.java))
        }
        swDiag.setOnCheckedChangeListener { _, checked ->
            tvStatus.text = if (checked)
                "Modo diagnóstico: apoyá la cédula. Se muestra sólo lo que el chip expone sin clave."
            else
                "Escaneá el dorso o completá los datos, y después apoyá la cédula."
        }

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        when {
            nfcAdapter == null -> tvStatus.text = "Este celular no tiene NFC."
            !nfcAdapter!!.isEnabled -> tvStatus.text = "Activá el NFC en Ajustes y volvé a la app."
        }
    }

    override fun onResume() {
        super.onResume()
        val adapter = nfcAdapter ?: return
        val intent = Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val pending = PendingIntent.getActivity(this, 0, intent, flags)
        adapter.enableForegroundDispatch(this, pending, null, arrayOf(arrayOf(IsoDep::class.java.name)))
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableForegroundDispatch(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val tag: Tag? = if (Build.VERSION.SDK_INT >= 33)
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)

        if (tag == null || !tag.techList.contains(IsoDep::class.java.name)) {
            tvStatus.text = "El chip detectado no es compatible (no es ISO-DEP)."
            return
        }

        tvResult.text = ""
        ivPhoto.visibility = View.GONE

        if (swDiag.isChecked) {
            tvStatus.text = "Analizando chip…"
            Thread { diagnose(tag) }.start()
            return
        }

        val doc = etDoc.text.toString().trim().uppercase().replace(" ", "")
        val birth = etBirth.text.toString().trim()
        val expiry = etExpiry.text.toString().trim()
        val problem = when {
            doc.isEmpty() -> "Falta el número de documento: son los 9 caracteres que siguen a IEPRY en la línea 1 (incluidas las letras)."
            birth.length != 6 -> "La fecha de nacimiento tiene que tener 6 números (AAMMDD): son los primeros 6 de la línea 2."
            expiry.length != 6 -> "La fecha de vencimiento tiene que tener 6 números (AAMMDD): son los 6 que siguen a la M o F en la línea 2."
            else -> null
        }
        if (problem != null) {
            tvStatus.text = "⚠️ $problem\n\nO usá el botón «Escanear dorso con la cámara»."
            return
        }

        tvStatus.text = "Leyendo chip… no muevas la cédula."
        Thread { readCard(tag, BACKey(doc, birth, expiry)) }.start()
    }

    private fun openService(tag: Tag): PassportService {
        val isoDep = IsoDep.get(tag)
        isoDep.timeout = 10_000
        val cardService = CardService.getInstance(isoDep)
        cardService.open()
        val service = PassportService(
            cardService,
            PassportService.NORMAL_MAX_TRANCEIVE_LENGTH,
            PassportService.DEFAULT_MAX_BLOCKSIZE,
            false, false
        )
        service.open()
        return service
    }

    // ---------- Lectura completa (con los datos de la MRZ) ----------
    private fun readCard(tag: Tag, key: BACKey) {
        try {
            val service = openService(tag)

            var paceOk = false
            try {
                val cardAccess = CardAccessFile(
                    service.getInputStream(PassportService.EF_CARD_ACCESS, PassportService.DEFAULT_MAX_BLOCKSIZE)
                )
                for (info in cardAccess.securityInfos) {
                    if (info is PACEInfo) {
                        service.doPACE(key, info.objectIdentifier, PACEInfo.toParameterSpec(info.parameterId), null)
                        paceOk = true
                        break
                    }
                }
            } catch (_: Exception) { }

            service.sendSelectApplet(paceOk)
            if (!paceOk) service.doBAC(key)

            ui { tvStatus.text = "Leyendo datos personales…" }
            val dg1 = DG1File(service.getInputStream(PassportService.EF_DG1, PassportService.DEFAULT_MAX_BLOCKSIZE))
            val mrz = dg1.mrzInfo
            val texto = StringBuilder()
            texto.appendLine("Apellidos: ${mrz.primaryIdentifier.replace("<", " ").trim()}")
            texto.appendLine("Nombres: ${mrz.secondaryIdentifier.replace("<", " ").trim()}")
            texto.appendLine("Nro. documento: ${mrz.documentNumber}")
            texto.appendLine("Dato opcional: ${mrz.optionalData1?.replace("<", "")?.ifEmpty { "-" } ?: "-"}")
            texto.appendLine("Nacionalidad: ${mrz.nationality}")
            texto.appendLine("País emisor: ${mrz.issuingState}")
            texto.appendLine("Sexo: ${mrz.gender}")
            texto.appendLine("Nacimiento: ${formatDate(mrz.dateOfBirth)}")
            texto.appendLine("Vencimiento: ${formatDate(mrz.dateOfExpiry)}")
            texto.appendLine("Tipo doc.: ${mrz.documentCode}")
            texto.appendLine("Acceso: ${if (paceOk) "PACE" else "BAC"}")

            // Qué grupos de datos trae el chip
            try {
                val com = COMFile(service.getInputStream(PassportService.EF_COM, PassportService.DEFAULT_MAX_BLOCKSIZE))
                texto.appendLine()
                texto.appendLine("Contenido del chip:")
                for (t in com.tagList) texto.appendLine("  • ${dgName(t)}")
            } catch (_: Exception) { }

            // DG11: datos personales adicionales (si existen)
            try {
                val dg11 = DG11File(service.getInputStream(PassportService.EF_DG11, PassportService.DEFAULT_MAX_BLOCKSIZE))
                texto.appendLine()
                texto.appendLine("Datos adicionales (DG11):")
                dg11.nameOfHolder?.let { texto.appendLine("  Nombre completo: ${it.replace("<", " ").trim()}") }
                dg11.personalNumber?.let { texto.appendLine("  Nro. personal: $it") }
                dg11.fullDateOfBirth?.let { texto.appendLine("  Fecha nac. completa: $it") }
                dg11.placeOfBirth?.let { if (it.isNotEmpty()) texto.appendLine("  Lugar de nacimiento: ${it.joinToString(", ")}") }
                dg11.permanentAddress?.let { if (it.isNotEmpty()) texto.appendLine("  Domicilio: ${it.joinToString(", ")}") }
                dg11.telephone?.let { texto.appendLine("  Teléfono: $it") }
                dg11.profession?.let { texto.appendLine("  Profesión: $it") }
            } catch (_: Exception) { }

            // DG12: datos del documento (si existen)
            try {
                val dg12 = DG12File(service.getInputStream(PassportService.EF_DG12, PassportService.DEFAULT_MAX_BLOCKSIZE))
                texto.appendLine()
                texto.appendLine("Datos del documento (DG12):")
                dg12.issuingAuthority?.let { texto.appendLine("  Autoridad emisora: $it") }
                dg12.dateOfIssue?.let { texto.appendLine("  Fecha de emisión: $it") }
            } catch (_: Exception) { }

            ui { tvResult.text = texto.toString(); tvStatus.text = "Leyendo foto…" }

            try {
                val dg2 = DG2File(service.getInputStream(PassportService.EF_DG2, PassportService.DEFAULT_MAX_BLOCKSIZE))
                val img = dg2.faceInfos.firstOrNull()?.faceImageInfos?.firstOrNull()
                if (img != null) {
                    val bytes = ByteArray(img.imageLength)
                    DataInputStream(img.imageInputStream).readFully(bytes)
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ui {
                        if (bmp != null) {
                            ivPhoto.setImageBitmap(bmp)
                            ivPhoto.visibility = View.VISIBLE
                            tvStatus.text = "✅ Lectura completa."
                        } else {
                            tvStatus.text = "✅ Datos leídos. La foto viene en formato ${img.mimeType} y necesita un decodificador extra."
                        }
                    }
                } else ui { tvStatus.text = "✅ Datos leídos (sin foto)." }
            } catch (e: Exception) {
                ui { tvStatus.text = "✅ Datos leídos. No se pudo leer la foto: ${e.message}" }
            }

            service.close()
        } catch (e: Exception) {
            ui {
                tvStatus.text = "❌ No se pudo abrir el chip (${e.javaClass.simpleName}).\n${e.message ?: ""}\n\n" +
                    "Lo más común: algún dato no coincide con la MRZ. Probá con «Escanear dorso con la cámara», " +
                    "o revisá que el número tenga los 9 caracteres después de IEPRY. " +
                    "Si los datos están bien, mantené la cédula quieta y probá moverla un poco sobre la antena NFC."
            }
        }
    }

    // ---------- Modo diagnóstico (sin datos) ----------
    private fun diagnose(tag: Tag) {
        val sb = StringBuilder()
        sb.appendLine("Información pública del chip:")
        sb.appendLine("UID: ${hex(tag.id)} (${tag.id.size} bytes)")
        if (tag.id.isNotEmpty() && tag.id[0] == 0x08.toByte()) {
            sb.appendLine("  Es aleatorio: cambia en cada lectura para que no puedan rastrearte.")
        }
        sb.appendLine("Tecnologías: ${tag.techList.joinToString { it.substringAfterLast('.') }}")
        val iso = IsoDep.get(tag)
        iso.historicalBytes?.let { sb.appendLine("Historical bytes: ${hex(it)}") }
        iso.hiLayerResponse?.let { sb.appendLine("Hi-layer response: ${hex(it)}") }
        sb.appendLine("Tamaño máx. de transmisión: ${iso.maxTransceiveLength} bytes")

        try {
            val service = openService(tag)
            sb.appendLine()
            try {
                val ca = CardAccessFile(
                    service.getInputStream(PassportService.EF_CARD_ACCESS, PassportService.DEFAULT_MAX_BLOCKSIZE)
                )
                sb.appendLine("Seguridad del chip (EF.CardAccess):")
                for (info in ca.securityInfos) {
                    if (info is PACEInfo) {
                        sb.appendLine("  • PACE ${paceName(info.objectIdentifier)}")
                        sb.appendLine("    Curva/parámetros: ${paramName(info.parameterId)}")
                    } else {
                        sb.appendLine("  • $info")
                    }
                }
                sb.appendLine("→ Usa PACE, el protocolo moderno de acceso.")
            } catch (_: Exception) {
                sb.appendLine("Sin EF.CardAccess → el chip usa BAC (protocolo clásico).")
            }

            sb.appendLine()
            try {
                service.sendSelectApplet(false)
                sb.appendLine("Aplicación ICAO (documento de viaje/identidad): presente ✔")
                try {
                    val input = service.getInputStream(PassportService.EF_COM, PassportService.DEFAULT_MAX_BLOCKSIZE)
                    input.read()
                    sb.appendLine("Datos legibles sin clave (inusual).")
                } catch (_: Exception) {
                    sb.appendLine("Datos personales: protegidos ✔ (hace falta la clave de la MRZ).")
                }
            } catch (e: Exception) {
                sb.appendLine("Aplicación ICAO: no se pudo seleccionar sin clave (${e.message}).")
            }
            service.close()
        } catch (e: Exception) {
            sb.appendLine("Error de comunicación: ${e.message}. Mantené la cédula quieta y probá de nuevo.")
        }

        ui {
            tvResult.text = sb.toString()
            tvStatus.text = "Diagnóstico completo."
        }
    }

    private fun paceName(oid: String): String {
        val mapping = when {
            oid.startsWith("0.4.0.127.0.7.2.2.4.1.") -> "DH Generic Mapping"
            oid.startsWith("0.4.0.127.0.7.2.2.4.2.") -> "ECDH Generic Mapping"
            oid.startsWith("0.4.0.127.0.7.2.2.4.3.") -> "DH Integrated Mapping"
            oid.startsWith("0.4.0.127.0.7.2.2.4.4.") -> "ECDH Integrated Mapping"
            oid.startsWith("0.4.0.127.0.7.2.2.4.6.") -> "ECDH Chip Authentication Mapping"
            else -> oid
        }
        val cipher = when (oid.substringAfterLast('.')) {
            "1" -> "3DES"
            "2" -> "AES-128"
            "3" -> "AES-192"
            "4" -> "AES-256"
            else -> ""
        }
        return "$mapping $cipher".trim()
    }

    private fun paramName(id: BigInteger?): String = when (id?.toInt()) {
        0 -> "DH 1024-bit"
        1 -> "DH 2048/224"
        2 -> "DH 2048/256"
        8 -> "NIST P-192"
        9 -> "Brainpool P192r1"
        10 -> "NIST P-224"
        11 -> "Brainpool P224r1"
        12 -> "NIST P-256"
        13 -> "Brainpool P256r1"
        14 -> "Brainpool P320r1"
        15 -> "NIST P-384"
        16 -> "Brainpool P384r1"
        17 -> "Brainpool P512r1"
        18 -> "NIST P-521"
        else -> id?.toString() ?: "?"
    }

    private fun dgName(tag: Int): String = when (tag) {
        0x61 -> "DG1: datos de la MRZ"
        0x75 -> "DG2: foto"
        0x63 -> "DG3: huellas (protegidas por el gobierno)"
        0x76 -> "DG4: iris (protegido)"
        0x65 -> "DG5: retrato adicional"
        0x67 -> "DG7: firma"
        0x6B -> "DG11: datos personales adicionales"
        0x6C -> "DG12: datos del documento"
        0x6D -> "DG13: datos nacionales"
        0x6E -> "DG14: claves de seguridad del chip"
        0x6F -> "DG15: autenticación activa"
        0x70 -> "DG16: contactos"
        else -> "Grupo 0x" + Integer.toHexString(tag)
    }

    private fun hex(b: ByteArray): String = b.joinToString("") { "%02X".format(it) }

    private fun formatDate(yymmdd: String): String =
        if (yymmdd.length == 6) "${yymmdd.substring(4, 6)}/${yymmdd.substring(2, 4)}/${yymmdd.substring(0, 2)}" else yymmdd

    private fun ui(block: () -> Unit) = runOnUiThread(block)
}
