package fr.cinema.retourson

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.AudioRecordingConfiguration
import android.media.AudioRouting
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import kotlin.math.abs

/**
 * Mode Enregistreur. La capture tourne en continu (le réalisateur entend aussi les répétitions),
 * l'écriture du fichier démarre et s'arrête à la demande.
 */
class CaptureService : Service() {

    companion object {
        const val ACTION_START = "start"
        const val ACTION_REC = "rec"
        const val ACTION_STOP_REC = "stop_rec"

        @Volatile var running = false; private set
        @Volatile var recording = false; private set
        @Volatile var recStartMs = 0L; private set
        @Volatile var fileName: String? = null; private set
        @Volatile var inputName = ""; private set
        @Volatile var inputIsUsb = false; private set
        /** Le micro ne livre plus que du silence (coupé par Android au profit d'une autre appli). */
        @Volatile var micBlocked = false; private set
        @Volatile var captureLatencyMs = -1; private set
        @Volatile var formatName = ""; private set
        @Volatile var error: String? = null
            private set(v) {
                field = v
                if (v != null) Log.e(Protocol.TAG, v)
            }
        @Volatile var clipUntil = 0L; private set
        /** Crête depuis la dernière lecture par l'interface (remise à 0 par l'interface). */
        @Volatile var meterPeak = 0f

        @Volatile private var streamer: AudioStreamer? = null
        val listenerCount: Int get() = streamer?.listenerCount ?: 0

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, CaptureService::class.java).setAction(ACTION_START))
        }

        fun command(ctx: Context, action: String) {
            if (running) ctx.startService(Intent(ctx, CaptureService::class.java).setAction(action))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, CaptureService::class.java))
        }
    }


    @Volatile private var wantRecording = false
    private var thread: Thread? = null
    private lateinit var locks: NetLocks

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        locks = NetLocks(this, "RetourSon:capture", multicast = false)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> if (!running) begin()
            ACTION_REC -> wantRecording = true
            ACTION_STOP_REC -> wantRecording = false
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        thread?.join(3000)
        thread = null
        locks.release()
        super.onDestroy()
    }

    private fun begin() {
        startForeground(
            Notifications.CAPTURE_ID,
            Notifications.build(this, "Enregistreur actif", "Micro ouvert, prêt à enregistrer", RecorderActivity::class.java),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        )
        locks.acquire()
        error = null
        running = true
        thread = Thread({ captureLoop() }, "capture").also { it.start() }
    }

    private fun notify(text: String) {
        getSystemService(NotificationManager::class.java).notify(
            Notifications.CAPTURE_ID,
            Notifications.build(this, "Enregistreur actif", text, RecorderActivity::class.java),
        )
    }

    private fun captureLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val opened = openRecord()
        if (opened == null) {
            error = "Impossible d'ouvrir le micro"
            running = false
            stopSelf()
            return
        }
        val (rec, isFloat) = opened
        val net = try {
            AudioStreamer(Build.MODEL).also { it.start() }
        } catch (e: Exception) {
            error = "Réseau indisponible : ${e.message}"
            null
        }
        streamer = net

        val writer = TakeWriter(contentResolver) { ev ->
            when (ev) {
                is TakeWriter.Event.Started -> notify("● REC  ${ev.name}")
                is TakeWriter.Event.Stopped -> notify("Micro ouvert, prêt à enregistrer")
                is TakeWriter.Event.Failed -> {
                    error = ev.message
                    wantRecording = false
                }
            }
        }.also { it.start() }

        val n = Protocol.FRAMES_PER_PACKET
        val f = FloatArray(n)
        val s = ShortArray(n)
        var inTake = false
        try {
            rec.startRecording()
            updateInput(rec.routedDevice)
            // Si le câble du micro se débranche, Android bascule en silence sur le micro du téléphone :
            // on le signale, et on revient sur le micro USB dès qu'il est rebranché.
            rec.addOnRoutingChangedListener(routingListener, mainHandler)
            deviceCallback = object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) {
                    added.firstOrNull { it.isSource && isUsb(it) }?.let {
                        Log.i(Protocol.TAG, "Micro USB rebranché : ${it.productName}")
                        rec.preferredDevice = it
                    }
                }

                override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) {
                    if (removed.any { it.isSource && isUsb(it) }) Log.w(Protocol.TAG, "Micro USB débranché !")
                    updateInput(rec.routedDevice)
                }
            }.also { audioManager.registerAudioDeviceCallback(it, mainHandler) }
            // Android ne laisse qu'une appli entendre le micro : si une autre (ex. Rode Central)
            // l'ouvre, on reçoit du silence. On le détecte pour alerter tout le monde.
            sessionId = rec.audioSessionId
            audioManager.registerAudioRecordingCallback(recordingCallback, mainHandler)
            var zeroChunks = 0
            Log.i(Protocol.TAG, "Capture démarrée : entrée=$inputName usb=$inputIsUsb · $formatName")
            var nextStats = SystemClock.elapsedRealtime() + 5000
            var maxPeak = 0f
            var lastRead = SystemClock.elapsedRealtime()
            var maxReadGap = 0L
            var framesRead = 0L
            val ts = AudioTimestamp()

            while (running) {
                val got = if (isFloat) {
                    rec.read(f, 0, n, AudioRecord.READ_BLOCKING)
                } else {
                    rec.read(s, 0, n).also { g -> for (i in 0 until g) f[i] = s[i] / 32768f }
                }
                if (got < 0) {
                    error = "Erreur micro ($got)"
                    break
                }
                if (got == 0) continue
                framesRead += got
                SystemClock.elapsedRealtime().let { t ->
                    if (t - lastRead > maxReadGap) maxReadGap = t - lastRead
                    lastRead = t
                }

                // Les 100 premières ms contiennent parfois un clic d'ouverture du micro (~0 dBFS) :
                // on les met en silence pour éviter une fausse saturation et un claquement dans le retour.
                if (framesRead <= Protocol.SAMPLE_RATE / 10) f.fill(0f, 0, got)

                var peak = 0f
                for (i in 0 until got) {
                    val a = abs(f[i])
                    if (a > peak) peak = a
                }
                if (peak > meterPeak) meterPeak = peak
                // Filet de sécurité : un vrai micro n'est jamais à zéro absolu pendant 1 s.
                zeroChunks = if (peak == 0f) zeroChunks + 1 else 0
                val blocked = systemSilenced || zeroChunks > 200
                if (blocked != micBlocked) {
                    micBlocked = blocked
                    if (blocked) Log.e(Protocol.TAG, "MICRO COUPÉ : silence reçu (autre appli sur le micro ?)")
                    else Log.i(Protocol.TAG, "Micro rétabli")
                    mainHandler.post {
                        if (blocked) {
                            Notifications.alert(
                                this, "⚠ Micro coupé — la prise est muette",
                                "Une autre appli (Rode Central ?) a pris le micro. Touche ici pour le récupérer.",
                                RecorderActivity::class.java,
                            )
                        } else {
                            Notifications.cancelAlert(this)
                        }
                    }
                }
                if (peak > maxPeak) maxPeak = peak
                val tNow = SystemClock.elapsedRealtime()
                if (tNow >= nextStats) {
                    // Latence de capture : âge de la dernière trame lue, d'après l'horloge du convertisseur.
                    if (rec.getTimestamp(ts, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS) {
                        val capturedAtNs = ts.nanoTime + (framesRead - ts.framePosition) * 1_000_000_000L / Protocol.SAMPLE_RATE
                        captureLatencyMs = ((System.nanoTime() - capturedAtNs) / 1_000_000).toInt()
                    }
                    Log.i(Protocol.TAG, "capture: crête 5 s=%.1f dBFS, retours=%d, rec=%b, latence capture=%d ms, écart max lecture=%d ms, écart max envoi=%d ms".format(20 * kotlin.math.log10(maxOf(maxPeak, 1e-6f)), listenerCount, inTake, captureLatencyMs, maxReadGap, net?.takeMaxSendGap() ?: -1))
                    maxPeak = 0f
                    maxReadGap = 0
                    nextStats = tNow + 5000
                }
                if (peak >= Protocol.CLIP_LEVEL) clipUntil = SystemClock.elapsedRealtime() + 2000

                if (wantRecording && !inTake) {
                    fileName = writer.begin()
                    recStartMs = SystemClock.elapsedRealtime()
                    inTake = true
                    recording = true
                    Log.i(Protocol.TAG, "REC début : $fileName")
                } else if (!wantRecording && inTake) {
                    writer.end()
                    inTake = false
                    recording = false
                    Log.i(Protocol.TAG, "REC fin : $fileName (${formatDuration(SystemClock.elapsedRealtime() - recStartMs)})")
                }
                if (inTake) writer.write(f, got)
                if (writer.overflow) error = "Stockage trop lent : des échantillons ont été perdus"

                val recFlags = (if (inTake) Protocol.FLAG_RECORDING else 0) or
                    (if (inputIsUsb) 0 else Protocol.FLAG_NOT_USB_MIC) or
                    (if (micBlocked) Protocol.FLAG_MIC_BLOCKED else 0)
                val elapsed = if (inTake) (SystemClock.elapsedRealtime() - recStartMs).toInt() else 0
                net?.push(f, got, peak, recFlags, elapsed)
            }
        } finally {
            if (inTake) writer.end()
            writer.close()
            recording = false
            wantRecording = false
            rec.removeOnRoutingChangedListener(routingListener)
            audioManager.unregisterAudioRecordingCallback(recordingCallback)
            micBlocked = false
            systemSilenced = false
            deviceCallback?.let { audioManager.unregisterAudioDeviceCallback(it) }
            deviceCallback = null
            try {
                rec.stop()
            } catch (_: Exception) {
            }
            rec.release()
            streamer = null
            net?.close()
            running = false
            if (error != null) stopSelf()
        }
    }

    /**
     * Préfère la source sans traitement (pas de compresseur ni de réduction de bruit du téléphone)
     * et le micro USB s'il est branché.
     */
    @SuppressLint("MissingPermission")
    private fun openRecord(): Pair<AudioRecord, Boolean>? {
        val am = getSystemService(AudioManager::class.java)
        val usb = am.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull {
            it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }
        val sources = buildList {
            if (am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true") {
                add(MediaRecorder.AudioSource.UNPROCESSED to "brut")
            }
            add(MediaRecorder.AudioSource.VOICE_PERFORMANCE to "direct")
            add(MediaRecorder.AudioSource.VOICE_RECOGNITION to "reconnaissance")
            add(MediaRecorder.AudioSource.MIC to "micro standard")
        }
        val rate = Protocol.SAMPLE_RATE
        for ((source, sourceName) in sources) {
            for (encoding in listOf(AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_16BIT)) {
                try {
                    val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, encoding)
                    if (min <= 0) continue
                    val isFloat = encoding == AudioFormat.ENCODING_PCM_FLOAT
                    // Tampon de 2 s : absorbe les ralentissements de la mémoire flash sans perdre d'échantillons.
                    val bytes = maxOf(min, rate * (if (isFloat) 4 else 2) * 2)
                    val rec = AudioRecord.Builder()
                        .setAudioSource(source)
                        .setAudioFormat(
                            AudioFormat.Builder()
                                .setSampleRate(rate)
                                .setEncoding(encoding)
                                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                                .build()
                        )
                        .setBufferSizeInBytes(bytes)
                        .build()
                    if (rec.state != AudioRecord.STATE_INITIALIZED) {
                        rec.release()
                        continue
                    }
                    if (usb != null) rec.preferredDevice = usb
                    formatName = "Source $sourceName · capture ${if (isFloat) "32 bits flottant" else "16 bits"} · WAV 24 bits 48 kHz mono"
                    return rec to isFloat
                } catch (_: Exception) {
                }
            }
        }
        return null
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioManager get() = getSystemService(AudioManager::class.java)
    private var deviceCallback: AudioDeviceCallback? = null
    private val routingListener = AudioRouting.OnRoutingChangedListener { r -> updateInput(r.routedDevice) }

    private var sessionId = 0
    @Volatile private var systemSilenced = false
    private val recordingCallback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
            val mine = configs.firstOrNull { it.clientAudioSessionId == sessionId } ?: return
            if (mine.isClientSilenced != systemSilenced) {
                systemSilenced = mine.isClientSilenced
                Log.w(Protocol.TAG, "Android : micro ${if (systemSilenced) "coupé" else "rendu"} à Retour Son")
            }
        }
    }

    private fun isUsb(d: AudioDeviceInfo) =
        d.type == AudioDeviceInfo.TYPE_USB_DEVICE || d.type == AudioDeviceInfo.TYPE_USB_HEADSET

    private fun updateInput(dev: AudioDeviceInfo?) {
        val wasUsb = inputIsUsb
        inputIsUsb = dev != null && isUsb(dev)
        inputName = dev?.let { "${it.productName} (${typeName(it.type)})" } ?: "inconnu"
        if (wasUsb != inputIsUsb || inputName.isNotEmpty()) Log.i(Protocol.TAG, "Entrée : $inputName (usb=$inputIsUsb)")
    }

    private fun typeName(type: Int) = when (type) {
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> "USB"
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "micro interne"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "jack"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth"
        else -> "type $type"
    }
}
