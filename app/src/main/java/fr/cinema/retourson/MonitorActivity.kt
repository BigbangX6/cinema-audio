package fr.cinema.retourson

import android.app.Activity
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
    private lateinit var txtRec: TextView
    private lateinit var txtClip: TextView
    private lateinit var txtJitter: TextView
    private lateinit var txtDelay: TextView
    private lateinit var txtStats: TextView

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
        txtRec = findViewById(R.id.txtRec)
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

        val target = MonitorService.target
        val name = target?.let { MonitorService.recorders[it]?.name } ?: ""
        txtStatus.text = when {
            target == null -> "Recherche d'un enregistreur…"
            connected -> "● Connecté à $name ($target)"
            MonitorService.lastPacketMs == 0L -> "Connexion à $name ($target)…"
            else -> "⚠ Signal perdu — $name ($target)"
        }
        txtStatus.setTextColor(if (connected) Color.rgb(90, 220, 120) else Color.rgb(255, 170, 0))

        if (connected && MonitorService.remoteRecording) {
            txtRec.text = "● REC  " + formatDuration(MonitorService.remoteElapsedMs.toLong())
            txtRec.setTextColor(Color.rgb(255, 60, 60))
        } else {
            txtRec.text = if (connected) "Pas d'enregistrement en cours" else "—"
            txtRec.setTextColor(Color.GRAY)
        }
        when {
            connected && MonitorService.remoteMicBlocked -> {
                txtClip.text = "⚠ MICRO COUPÉ SUR L'ENREGISTREUR (autre appli)"
                txtClip.setBackgroundColor(Color.rgb(198, 40, 40))
                txtClip.visibility = View.VISIBLE
            }
            connected && MonitorService.remoteNotUsbMic -> {
                txtClip.text = "⚠ MICRO USB NON BRANCHÉ SUR L'ENREGISTREUR"
                txtClip.setBackgroundColor(Color.rgb(230, 120, 0))
                txtClip.visibility = View.VISIBLE
            }
            now < MonitorService.clipUntil -> {
                txtClip.text = "SATURATION"
                txtClip.setBackgroundColor(Color.rgb(198, 40, 40))
                txtClip.visibility = View.VISIBLE
            }
            else -> txtClip.visibility = View.INVISIBLE
        }

        txtJitter.text = "Tampon anti-coupures : ${MonitorService.jitterMs} ms"
        txtDelay.text = "Retard ajouté (calage sur l'image) : ${MonitorService.delayMs} ms"
        val net = MonitorService.bufferMs
        val outMs = MonitorService.outputLatencyMs
        txtStats.text = "Latence ≈ ${net + outMs + CAPTURE_AND_NETWORK_MS} ms (réseau $net + sortie $outMs + capture ~$CAPTURE_AND_NETWORK_MS)" +
            "\npaquets perdus ${MonitorService.lost} · coupures ${MonitorService.underruns}" +
            (MonitorService.error?.let { "\n$it" } ?: "")
    }

    companion object {
        /** Estimation : tampon de capture Android (~20 ms) + paquet de 5 ms + transit Wi-Fi. */
        private const val CAPTURE_AND_NETWORK_MS = 30
    }

    private abstract class SimpleSeek : SeekBar.OnSeekBarChangeListener {
        override fun onStartTrackingTouch(s: SeekBar) {}
        override fun onStopTrackingTouch(s: SeekBar) {}
    }
}
