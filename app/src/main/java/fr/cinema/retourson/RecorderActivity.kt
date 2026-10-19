package fr.cinema.retourson

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast

class RecorderActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var meter: LevelMeterView
    private lateinit var txtInput: TextView
    private lateinit var txtFormat: TextView
    private lateinit var txtClip: TextView
    private lateinit var btnRec: Button
    private lateinit var txtFile: TextView
    private lateinit var txtNet: TextView
    private lateinit var txtError: TextView

    private var frame = 0
    private var ipText = ""
    private var nextIpCheck = 0L

    /** L'IP ne change presque jamais : on ne relit les interfaces réseau que toutes les 2 s. */
    private fun localIp(): String {
        val now = SystemClock.elapsedRealtime()
        if (now >= nextIpCheck) {
            ipText = Net.localIpv4().joinToString(", ").ifEmpty { "aucune" }
            nextIpCheck = now + 2000
        }
        return ipText
    }

    private val tick = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 50)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_recorder)
        meter = findViewById(R.id.meter)
        txtInput = findViewById(R.id.txtInput)
        txtFormat = findViewById(R.id.txtFormat)
        txtClip = findViewById(R.id.txtClip)
        btnRec = findViewById(R.id.btnRec)
        txtFile = findViewById(R.id.txtFile)
        txtNet = findViewById(R.id.txtNet)
        txtError = findViewById(R.id.txtError)

        if (!CaptureService.running) CaptureService.start(this)

        // Démarrer = immédiat. Arrêter = confirmation, pour éviter de couper une prise par erreur.
        btnRec.setOnClickListener {
            when {
                CaptureService.recording -> confirmStop()
                // Comme l'enregistreur du téléphone : on refuse plutôt que d'enregistrer une prise muette.
                CaptureService.micBlocked -> AlertDialog.Builder(this)
                    .setTitle("Enregistrement impossible")
                    .setMessage("Une autre application utilise le micro (Rode Central ?). Ferme-la : le micro revient tout seul.")
                    .setPositiveButton("OK", null)
                    .show()
                else -> CaptureService.command(this, CaptureService.ACTION_REC)
            }
        }
        findViewById<Button>(R.id.btnTakes).setOnClickListener {
            startActivity(Intent(this, TakesActivity::class.java))
        }
        findViewById<Button>(R.id.btnQuit).setOnClickListener {
            if (CaptureService.recording) {
                Toast.makeText(this, "Arrête d'abord l'enregistrement", Toast.LENGTH_SHORT).show()
            } else {
                CaptureService.stop(this)
                finish()
            }
        }
    }

    private fun confirmStop() {
        AlertDialog.Builder(this)
            .setTitle("Arrêter l'enregistrement ?")
            .setMessage(CaptureService.fileName)
            .setPositiveButton("Arrêter") { _, _ ->
                CaptureService.command(this, CaptureService.ACTION_STOP_REC)
            }
            .setNegativeButton("Continuer", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        handler.post(tick)
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        super.onPause()
    }

    private fun refresh() {
        meter.setPeak(CaptureService.meterPeak)
        CaptureService.meterPeak = 0f
        if (frame++ % 4 != 0) return
        val now = SystemClock.elapsedRealtime()

        if (CaptureService.running) {
            txtInput.setTextIfChanged("Entrée : ${CaptureService.inputName}")
            txtInput.setTextColor(if (CaptureService.inputIsUsb) Color.LTGRAY else Color.rgb(255, 170, 0))
            if (!CaptureService.inputIsUsb && CaptureService.inputName.isNotEmpty()) {
                txtInput.setTextIfChanged("⚠ Micro USB non détecté — entrée : ${CaptureService.inputName}")
            }
        } else {
            txtInput.setTextIfChanged("Micro arrêté")
        }
        txtFormat.setTextIfChanged(CaptureService.formatName)
        when {
            CaptureService.running && CaptureService.micBlocked -> {
                txtClip.setTextIfChanged("⚠ MICRO COUPÉ — ferme Rode Central ou l'autre appli qui utilise le micro")
                txtClip.setBgIfChanged(Color.rgb(198, 40, 40))
                txtClip.visibility = View.VISIBLE
            }
            CaptureService.running && !CaptureService.inputIsUsb && CaptureService.inputName.isNotEmpty() -> {
                txtClip.setTextIfChanged("⚠ MICRO USB NON BRANCHÉ")
                txtClip.setBgIfChanged(Color.rgb(230, 120, 0))
                txtClip.visibility = View.VISIBLE
            }
            now < CaptureService.clipUntil -> {
                txtClip.setTextIfChanged("SATURATION")
                txtClip.setBgIfChanged(Color.rgb(198, 40, 40))
                txtClip.visibility = View.VISIBLE
            }
            else -> txtClip.visibility = View.INVISIBLE
        }

        if (CaptureService.recording) {
            btnRec.setTextIfChanged("● REC  " + formatDuration(now - CaptureService.recStartMs))
            btnRec.setBgIfChanged(Color.rgb(200, 30, 30))
            txtFile.setTextIfChanged(CaptureService.fileName ?: "")
        } else {
            btnRec.setTextIfChanged("ENREGISTRER")
            btnRec.setBgIfChanged(Color.rgb(70, 70, 70))
            txtFile.setTextIfChanged(CaptureService.fileName?.let { "Dernier fichier : Music/RetourSon/$it" } ?: "")
        }

        val n = CaptureService.listenerCount
        txtNet.setTextIfChanged((if (n == 0) "Aucun retour connecté" else "$n retour(s) connecté(s)") +
            "\nIP : " + localIp() +
            (CaptureService.remoteInfo?.let { "\n$it" } ?: ""))
        txtError.setTextIfChanged(CaptureService.error ?: "")
    }
}
