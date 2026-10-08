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
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import net.sf.scuba.smartcards.CardService
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.jmrtd.BACKey
import org.jmrtd.PassportService
import org.jmrtd.lds.CardAccessFile
import org.jmrtd.lds.PACEInfo
import org.jmrtd.lds.icao.DG1File
import org.jmrtd.lds.icao.DG2File
import java.io.DataInputStream
import java.security.Security

class MainActivity : AppCompatActivity() {

    private var nfcAdapter: NfcAdapter? = null
    private lateinit var etDoc: EditText
    private lateinit var etBirth: EditText
    private lateinit var etExpiry: EditText
    private lateinit var tvStatus: TextView
    private lateinit var tvResult: TextView
    private lateinit var ivPhoto: ImageView

    companion object {
        init {
            // Reemplaza el BouncyCastle recortado de Android por el completo (necesario para BAC/PACE)
            Security.removeProvider("BC")
            Security.insertProviderAt(BouncyCastleProvider(), 1)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        etDoc = findViewById(R.id.etDocNumber)
        etBirth = findViewById(R.id.etBirth)
        etExpiry = findViewById(R.id.etExpiry)
        tvStatus = findViewById(R.id.tvStatus)
        tvResult = findViewById(R.id.tvResult)
        ivPhoto = findViewById(R.id.ivPhoto)

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

        val doc = etDoc.text.toString().trim().uppercase()
        val birth = etBirth.text.toString().trim()
        val expiry = etExpiry.text.toString().trim()
        if (doc.isEmpty() || birth.length != 6 || expiry.length != 6) {
            tvStatus.text = "Completá número de documento y fechas (formato AAMMDD)."
            return
        }

        tvStatus.text = "Leyendo chip… no muevas la cédula."
        tvResult.text = ""
        ivPhoto.visibility = View.GONE

        Thread { readCard(tag, BACKey(doc, birth, expiry)) }.start()
    }

    private fun readCard(tag: Tag, key: BACKey) {
        try {
            val isoDep = IsoDep.get(tag).apply { timeout = 10_000 }
            val cardService = CardService.getInstance(isoDep)
            cardService.open()

            val service = PassportService(
                cardService,
                PassportService.NORMAL_MAX_TRANCEIVE_LENGTH,
                PassportService.DEFAULT_MAX_BLOCKSIZE,
                false, false
            )
            service.open()

            // 1) Intentar PACE (más moderno); si falla, usar BAC
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
            } catch (_: Exception) { /* el chip no soporta PACE, seguimos con BAC */ }

            service.sendSelectApplet(paceOk)
            if (!paceOk) service.doBAC(key)

            // 2) DG1: datos personales (MRZ)
            ui { tvStatus.text = "Leyendo datos personales…" }
            val dg1 = DG1File(service.getInputStream(PassportService.EF_DG1, PassportService.DEFAULT_MAX_BLOCKSIZE))
            val mrz = dg1.mrzInfo
            val texto = buildString {
                appendLine("Apellidos: ${mrz.primaryIdentifier.replace("<", " ").trim()}")
                appendLine("Nombres: ${mrz.secondaryIdentifier.replace("<", " ").trim()}")
                appendLine("Nro. documento: ${mrz.documentNumber}")
                appendLine("Nro. personal / opcional: ${mrz.personalNumber ?: mrz.optionalData1 ?: "-"}")
                appendLine("Nacionalidad: ${mrz.nationality}")
                appendLine("País emisor: ${mrz.issuingState}")
                appendLine("Sexo: ${mrz.gender}")
                appendLine("Nacimiento: ${formatDate(mrz.dateOfBirth)}")
                appendLine("Vencimiento: ${formatDate(mrz.dateOfExpiry)}")
                appendLine("Tipo doc.: ${mrz.documentCode}")
                appendLine("Acceso: ${if (paceOk) "PACE" else "BAC"}")
            }
            ui { tvResult.text = texto; tvStatus.text = "Leyendo foto…" }

            // 3) DG2: foto
            try {
                val dg2 = DG2File(service.getInputStream(PassportService.EF_DG2, PassportService.DEFAULT_MAX_BLOCKSIZE))
                val img = dg2.faceInfos.firstOrNull()?.faceImageInfos?.firstOrNull()
                if (img != null) {
                    val bytes = ByteArray(img.imageLength)
                    DataInputStream(img.imageInputStream).readFully(bytes)
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ui {
                        if (bmp != null) {
                            ivPhoto.setImageBitmap(bmp); ivPhoto.visibility = View.VISIBLE
                            tvStatus.text = "✅ Lectura completa."
                        } else {
                            tvStatus.text = "✅ Datos leídos. Foto en formato ${img.mimeType} (requiere decodificador JPEG2000)."
                        }
                    }
                } else ui { tvStatus.text = "✅ Datos leídos (sin foto)." }
            } catch (e: Exception) {
                ui { tvStatus.text = "✅ Datos leídos. No se pudo leer la foto: ${e.message}" }
            }

            service.close()
        } catch (e: Exception) {
            ui {
                tvStatus.text = "❌ Error: ${e.javaClass.simpleName}\n${e.message ?: ""}\n\n" +
                    "Revisá que el número de documento y las fechas coincidan exactamente con la MRZ, " +
                    "y mantené la cédula quieta sobre la antena NFC."
            }
        }
    }

    private fun formatDate(yymmdd: String): String =
        if (yymmdd.length == 6) "${yymmdd.substring(4, 6)}/${yymmdd.substring(2, 4)}/${yymmdd.substring(0, 2)}" else yymmdd

    private fun ui(block: () -> Unit) = runOnUiThread(block)
}
