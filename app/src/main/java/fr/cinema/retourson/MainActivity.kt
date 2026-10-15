package fr.cinema.retourson

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.btnRecorder).setOnClickListener {
            val missing = buildList {
                if (!granted(Manifest.permission.RECORD_AUDIO)) add(Manifest.permission.RECORD_AUDIO)
                if (needsNotifPermission()) add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (missing.isEmpty()) openRecorder() else requestPermissions(missing.toTypedArray(), REQ_RECORDER)
        }
        findViewById<Button>(R.id.btnMonitor).setOnClickListener {
            if (needsNotifPermission()) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_MONITOR)
            } else {
                openMonitor()
            }
        }

        findViewById<Button>(R.id.btnTakes).setOnClickListener {
            startActivity(Intent(this, TakesActivity::class.java))
        }

        // Si un mode tourne déjà en arrière-plan, on y retourne directement.
        if (savedInstanceState == null) {
            if (CaptureService.running) openRecorder()
            else if (MonitorService.running) openMonitor()
        }
    }

    override fun onResume() {
        super.onResume()
        val ips = Net.localIpv4()
        findViewById<TextView>(R.id.txtIp).text =
            if (ips.isEmpty()) "Pas de réseau local détecté" else "Ce téléphone : " + ips.joinToString(", ")
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        when (requestCode) {
            REQ_RECORDER ->
                if (granted(Manifest.permission.RECORD_AUDIO)) openRecorder()
                else Toast.makeText(this, "L'accès au micro est nécessaire pour enregistrer", Toast.LENGTH_LONG).show()
            REQ_MONITOR -> openMonitor()
        }
    }

    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun needsNotifPermission() =
        Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)

    private fun openRecorder() = startActivity(Intent(this, RecorderActivity::class.java))
    private fun openMonitor() = startActivity(Intent(this, MonitorActivity::class.java))

    companion object {
        private const val REQ_RECORDER = 1
        private const val REQ_MONITOR = 2
    }
}
