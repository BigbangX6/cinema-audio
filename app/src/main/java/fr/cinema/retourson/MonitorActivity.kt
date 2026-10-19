package fr.cinema.retourson

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

class MonitorActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var meter: LevelMeterView
    private lateinit var txtStatus: TextView
    private lateinit var btnRec: Button
    private lateinit var txtClip: TextView
    private lateinit var txtJitter: TextView
    private lateinit var txtDelay: TextView
    private lateinit var txtStats: TextView

    private var frame = 0

    private val tick = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 50)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_monitor)
        volumeControlStream = AudioManager.STREAM_MUSIC
        meter = findViewById(R.id.meter)
        txtStatus = findViewById(R.id.txtStatus)
        btnRec = findViewById(R.id.btnRec)
        txtClip = findViewById(R.id.txtClip)
        txtJitter = findViewById(R.id.txtJitter)
        txtDelay = findViewById(R.id.txtDelay)
        txtStats = findViewById(R.id.txtStats)

        val prefs = getSharedPreferences("monitor", Context.MODE_PRIVATE)
        MonitorService.jitterMs = prefs.getInt("jitter", 40)
        MonitorService.delayMs = prefs.getInt("delay", 0)

        // Tampon : 10 à 200 ms
        findViewById<SeekBar>(R.id.seekJitter).apply {
            max = 190
            progress = MonitorService.jitterMs - 10
            setOnSeekBarChangeListener(object : SimpleSeek() {
                override fun onProgressChanged(s: SeekBar, p: Int, user: Boolean) {
                    MonitorService.jitterMs = p + 10
                    prefs.edit().putInt("jitter", p + 10).apply()
                }
            })
        }
        // Retard ajouté : 0 à 1000 ms par pas de 10
        findViewById<SeekBar>(R.id.seekDelay).apply {
            max = 100
            progress = MonitorService.delayMs / 10
            setOnSeekBarChangeListener(object : SimpleSeek() {
                override fun onProgressChanged(s: SeekBar, p: Int, user: Boolean) {
                    MonitorService.delayMs = p * 10
                    prefs.edit().putInt("delay", p * 10).apply()
                }
            })
        }

        // Comme sur l'enregistreur : démarrer = immédiat, arrêter = confirmation.
        btnRec.setOnClickListener {
            val connected = SystemClock.elapsedRealtime() - MonitorService.lastPacketMs < 1000
            when {
                remoteBlocked() -> AlertDialog.Builder(this)
                    .setTitle("Enregistrement impossible")
                    .setMessage("Sur le téléphone micro : " + "Une autre application utilise le micro (Rode Central ?). Ferme-la : le micro revient tout seul.")
                    .setPositiveButton("OK", null)
                    .show()
                !connected -> Toast.makeText(this, "Pas connecté à l'enregistreur", Toast.LENGTH_SHORT).show()
                MonitorService.pendingRec != null -> {}
                MonitorService.remoteRecording -> AlertDialog.Builder(this)
                    .setTitle("Arrêter l'enregistrement ?")
                    .setMessage("La prise en cours sur le téléphone micro sera terminée.")
                    .setPositiveButton("Arrêter") { _, _ -> MonitorService.requestRec(false) }
                    .setNegativeButton("Continuer", null)
                    .show()
                else -> MonitorService.requestRec(true)
            }
        }
        val advanced = findViewById<View>(R.id.advanced)
        val btnAdvanced = findViewById<Button>(R.id.btnAdvanced)
        btnAdvanced.setOnClickListener {
            val show = advanced.visibility != View.VISIBLE
            advanced.visibility = if (show) View.VISIBLE else View.GONE
            btnAdvanced.text = if (show) "Réglages avancés ▴" else "Réglages avancés ▾"
        }

        findViewById<Button>(R.id.btnNext).setOnClickListener {
            val ips = MonitorService.recorders.keys.sorted()
            if (ips.isEmpty()) {
                Toast.makeText(this, "Aucun enregistreur trouvé sur le réseau", Toast.LENGTH_SHORT).show()
            } else {
                val i = ips.indexOf(MonitorService.target)
                MonitorService.target = ips[(i + 1) % ips.size]
            }
        }
        val edtIp = findViewById<EditText>(R.id.edtIp)
        findViewById<Button>(R.id.btnIp).setOnClickListener {
            val ip = edtIp.text.toString().trim()
            if (ip.matches(Regex("""\d{1,3}(\.\d{1,3}){3}"""))) MonitorService.target = ip
            else Toast.makeText(this, "Adresse IP invalide", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btnQuit).setOnClickListener {
            MonitorService.stop(this)
            finish()
        }

        if (!MonitorService.running) MonitorService.start(this)
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
        val now = SystemClock.elapsedRealtime()
        val connected = now - MonitorService.lastPacketMs < 1000
        meter.setPeak(if (connected) MonitorService.meterPeak else 0f)
        MonitorService.meterPeak = 0f
        if (frame++ % 4 != 0) return

        val target = MonitorService.target
        val name = target?.let { MonitorService.recorders[it]?.name } ?: ""
        txtStatus.setTextIfChanged(when {
            target == null -> "Recherche d'un enregistreur…"
            connected -> "● Connecté à $name ($target)"
            remoteBlocked() -> "⚠ Micro coupé sur $name ($target)"
            MonitorService.lastPacketMs == 0L -> "Connexion à $name ($target)…"
            else -> "⚠ Signal perdu — $name ($target)"
        })
        txtStatus.setTextColor(if (connected) Color.rgb(90, 220, 120) else Color.rgb(255, 170, 0))

        val pending = MonitorService.pendingRec
        when {
            !connected -> {
                btnRec.setTextIfChanged("ENREGISTRER")
                btnRec.setBgIfChanged(Color.rgb(45, 45, 45))
                btnRec.setTextColor(Color.GRAY)
            }
            pending != null -> {
                btnRec.setTextIfChanged(if (pending) "Démarrage…" else "Arrêt…")
                btnRec.setBgIfChanged(Color.rgb(70, 70, 70))
                btnRec.setTextColor(Color.WHITE)
            }
            MonitorService.remoteRecording -> {
                btnRec.setTextIfChanged("● REC  " + formatDuration(MonitorService.remoteElapsedMs.toLong()))
                btnRec.setBgIfChanged(Color.rgb(200, 30, 30))
                btnRec.setTextColor(Color.WHITE)
            }
            else -> {
                btnRec.setTextIfChanged("ENREGISTRER")
                btnRec.setBgIfChanged(Color.rgb(70, 70, 70))
                btnRec.setTextColor(Color.WHITE)
            }
        }
        if (MonitorService.commandFailed) {
            MonitorService.commandFailed = false
            Toast.makeText(this, "L'enregistreur n'a pas répondu — réessaie, ou vérifie qu'il est à jour", Toast.LENGTH_LONG).show()
        }
        when {
            remoteBlocked() -> {
                txtClip.setTextIfChanged("⚠ MICRO COUPÉ SUR L'ENREGISTREUR (autre appli)")
                txtClip.setBgIfChanged(Color.rgb(198, 40, 40))
                txtClip.visibility = View.VISIBLE
            }
            connected && MonitorService.remoteNotUsbMic -> {
                txtClip.setTextIfChanged("⚠ MICRO USB NON BRANCHÉ SUR L'ENREGISTREUR")
                txtClip.setBgIfChanged(Color.rgb(230, 120, 0))
                txtClip.visibility = View.VISIBLE
            }
            now < MonitorService.clipUntil -> {
                txtClip.setTextIfChanged("SATURATION")
                txtClip.setBgIfChanged(Color.rgb(198, 40, 40))
                txtClip.visibility = View.VISIBLE
            }
            else -> txtClip.visibility = View.INVISIBLE
        }

        txtJitter.setTextIfChanged("Tampon anti-coupures : ${MonitorService.jitterMs} ms")
        txtDelay.setTextIfChanged("Retard ajouté (calage sur l'image) : ${MonitorService.delayMs} ms")
        val net = MonitorService.bufferMs
        val outMs = MonitorService.outputLatencyMs
        txtStats.setTextIfChanged("Latence ≈ ${net + outMs + CAPTURE_AND_NETWORK_MS} ms (réseau $net + sortie $outMs + capture ~$CAPTURE_AND_NETWORK_MS)" +
            "\npaquets perdus ${MonitorService.lost} · coupures ${MonitorService.underruns}" +
            (MonitorService.error?.let { "\n$it" } ?: ""))
    }

    private fun remoteBlocked() = MonitorService.remoteBlocked()

    companion object {
        /** Estimation : tampon de capture Android (~20 ms) + paquet de 5 ms + transit Wi-Fi. */
        private const val CAPTURE_AND_NETWORK_MS = 30
    }

    private abstract class SimpleSeek : SeekBar.OnSeekBarChangeListener {
        override fun onStartTrackingTouch(s: SeekBar) {}
        override fun onStopTrackingTouch(s: SeekBar) {}
    }
}
