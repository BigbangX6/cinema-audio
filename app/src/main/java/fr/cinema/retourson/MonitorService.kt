package fr.cinema.retourson

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

/**
 * Mode Retour : découvre les enregistreurs, s'abonne à l'un d'eux et joue le son
 * avec un tampon anti-coupures et un retard réglable (pour caler sur l'image).
 */
class MonitorService : Service() {

    class Recorder(val ip: String, val name: String) {
        @Volatile var lastSeen = 0L
        @Volatile var flags = 0
    }

    companion object {
        @Volatile var running = false; private set
        val recorders = ConcurrentHashMap<String, Recorder>()
        /** IP de l'enregistreur écouté ; null = prendre le premier trouvé. */
        @Volatile var target: String? = null

        @Volatile var jitterMs = 40
        @Volatile var delayMs = 0

        @Volatile var meterPeak = 0f
        @Volatile var clipUntil = 0L; private set
        @Volatile var lastPacketMs = 0L; private set
        @Volatile var remoteRecording = false; private set
        @Volatile var remoteNotUsbMic = false; private set
        @Volatile var remoteMicBlocked = false; private set
        @Volatile var remoteElapsedMs = 0; private set
        @Volatile var bufferMs = 0; private set
        @Volatile var lost = 0L; private set
        @Volatile var underruns = 0L; private set
        /** Latence mesurée entre l'écriture et la sortie du son (ms). */
        @Volatile var outputLatencyMs = 0; private set
        @Volatile var outputBufferMs = 0; private set
        @Volatile var error: String? = null
            private set(v) {
                field = v
                if (v != null) Log.e(Protocol.TAG, v)
            }

        /** Commande REC/STOP en attente de confirmation par l'enregistreur (null = aucune). */
        @Volatile var pendingRec: Boolean? = null; private set
        @Volatile private var pendingUntil = 0L
        @Volatile private var pendingId = 0
        /** La dernière commande n'a pas été confirmée à temps. */
        @Volatile var commandFailed = false

        /** Demande à l'enregistreur écouté de démarrer (true) ou d'arrêter (false) la prise. */
        fun requestRec(rec: Boolean) {
            commandFailed = false
            pendingId = (SystemClock.elapsedRealtime() and 0x7fffffff).toInt()
            pendingUntil = SystemClock.elapsedRealtime() + COMMAND_TIMEOUT_MS
            pendingRec = rec
            wake.offer(Unit)
        }

        /** Réveille la boucle d'envoi dès qu'une commande est demandée. */
        private val wake = java.util.concurrent.LinkedBlockingQueue<Unit>(1)

        private const val COMMAND_TIMEOUT_MS = 3000L

        /**
         * Micro de l'enregistreur pris par une autre appli : d'après l'audio s'il arrive, sinon d'après
         * la balise (l'enregistreur n'envoie plus de son quand son micro est bloqué).
         */
        fun remoteBlocked(): Boolean {
            val now = SystemClock.elapsedRealtime()
            if (now - lastPacketMs < 1000) return remoteMicBlocked
            val r = target?.let { recorders[it] } ?: return false
            return now - r.lastSeen < 3000 && r.flags and Protocol.FLAG_MIC_BLOCKED != 0
        }

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, MonitorService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, MonitorService::class.java))
        }
    }

    private val fifo = SampleFifo(Protocol.SAMPLE_RATE * 3)
    @Volatile private var resetRequested = false
    private val threads = ArrayList<Thread>()
    private var audioSocket: DatagramSocket? = null
    private var discoverySocket: DatagramSocket? = null
    private lateinit var locks: NetLocks

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        locks = NetLocks(this, "RetourSon:monitor", multicast = true)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!running) begin()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        Notifications.cancelAlert(this)
        audioSocket?.close()
        discoverySocket?.close()
        threads.forEach { it.join(1500) }
        threads.clear()
        fifo.clear()
        locks.release()
        super.onDestroy()
    }

    private fun begin() {
        startForeground(
            Notifications.MONITOR_ID,
            Notifications.build(this, "Retour son actif", "Écoute de l'enregistreur", MonitorActivity::class.java),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )
        locks.acquire()
        error = null
        lost = 0
        underruns = 0
        try {
            discoverySocket = DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                bind(InetSocketAddress(Protocol.DISCOVERY_PORT))
                soTimeout = 1000
            }
            audioSocket = DatagramSocket().apply {
                receiveBufferSize = 256 * 1024
                soTimeout = 500
            }
        } catch (e: Exception) {
            error = "Réseau indisponible : ${e.message}"
        }
        running = true
        discoverySocket?.let { s -> threads += Thread({ discoveryLoop(s) }, "discovery") }
        audioSocket?.let { s ->
            threads += Thread({ helloLoop(s) }, "hello")
            threads += Thread({ receiveLoop(s) }, "receive")
        }
        threads += Thread({ playbackLoop() }, "playback")
        threads.forEach { it.start() }
    }

    private fun discoveryLoop(sock: DatagramSocket) {
        val buf = ByteArray(512)
        val p = DatagramPacket(buf, buf.size)
        while (running) {
            p.length = buf.size
            try {
                sock.receive(p)
            } catch (_: SocketTimeoutException) {
                continue
            } catch (_: Exception) {
                if (!running) break else continue
            }
            if (p.length < 6) continue
            val b = ByteBuffer.wrap(buf, 0, p.length)
            if (b.int != Protocol.BEACON_MAGIC) continue
            val flags = b.get().toInt()
            val len = b.get().toInt() and 0xff
            if (b.remaining() < len) continue
            val name = String(buf, b.position(), len, Charsets.UTF_8)
            val ip = p.address.hostAddress ?: continue
            recorders.getOrPut(ip) { Recorder(ip, name) }.also {
                it.lastSeen = SystemClock.elapsedRealtime()
                it.flags = flags
            }
            if (target == null) target = ip
        }
    }

    private fun helloLoop(sock: DatagramSocket) {
        val hello = ByteBuffer.allocate(4).putInt(Protocol.HELLO_MAGIC).array()
        val myName = android.os.Build.MODEL.toByteArray(Charsets.UTF_8).take(200).toByteArray()
        var nextHello = 0L
        var alerted = false
        fun send(data: ByteArray, ip: String) {
            try {
                sock.send(DatagramPacket(data, data.size, InetAddress.getByName(ip), Protocol.AUDIO_PORT))
            } catch (_: Exception) {
            }
        }
        while (running) {
            val now = SystemClock.elapsedRealtime()
            val ip = target
            if (ip != null && now >= nextHello) {
                send(hello, ip)
                recorders.entries.removeIf { it.key != target && now - it.value.lastSeen > 5000 }
                nextHello = now + 500
            }
            // Le réalisateur a souvent le téléphone en poche : alerte qui vibre, même écran éteint.
            val blocked = remoteBlocked()
            if (blocked != alerted) {
                alerted = blocked
                if (blocked) {
                    Notifications.alert(
                        this, "⚠ Micro coupé sur l'enregistreur",
                        "Une autre appli (Rode Central ?) a pris le micro du téléphone enregistreur : rien n'est enregistré.",
                        MonitorActivity::class.java,
                    )
                } else {
                    Notifications.cancelAlert(this)
                }
            }
            // Commande REC/STOP : répétée toutes les 100 ms jusqu'à ce que l'état REC reçu corresponde.
            pendingRec?.let { want ->
                when {
                    now - lastPacketMs < 1000 && remoteRecording == want -> pendingRec = null
                    now > pendingUntil -> {
                        pendingRec = null
                        commandFailed = true
                        Log.w(Protocol.TAG, "Commande ${if (want) "REC" else "STOP"} non confirmée par l'enregistreur")
                    }
                    ip != null -> send(
                        ByteBuffer.allocate(10 + myName.size).putInt(Protocol.COMMAND_MAGIC).putInt(pendingId)
                            .put((if (want) 1 else 0).toByte()).put(myName.size.toByte()).put(myName).array(),
                        ip,
                    )
                }
            }
            try {
                // Réveil toutes les 100 ms seulement pendant une commande, sinon toutes les 500 ms.
                wake.poll(if (pendingRec != null) 100L else maxOf(10L, nextHello - SystemClock.elapsedRealtime()), java.util.concurrent.TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    private fun receiveLoop(sock: DatagramSocket) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val buf = ByteArray(4096)
        val p = DatagramPacket(buf, buf.size)
        val samples = ShortArray(Protocol.FRAMES_PER_PACKET * 4)
        var expected = 0
        var synced = false
        var lastFrom: String? = null
        while (running) {
            p.length = buf.size
            try {
                sock.receive(p)
            } catch (_: SocketTimeoutException) {
                continue
            } catch (_: Exception) {
                if (!running) break else continue
            }
            val from = p.address.hostAddress
            if (from != target) continue
            if (from != lastFrom) {
                lastFrom = from
                synced = false
            }
            if (p.length < Protocol.AUDIO_HEADER) continue
            val b = ByteBuffer.wrap(buf, 0, p.length)
            if (b.int != Protocol.AUDIO_MAGIC) continue
            val seq = b.int
            val flags = b.get().toInt()
            val peak = b.float
            val elapsed = b.int
            val n = b.short.toInt()
            if (n <= 0 || n > samples.size || b.remaining() < n * 2) continue

            if (synced) {
                val d = seq - expected
                if (d < 0) {
                    if (d > -200) continue // paquet en retard ou dupliqué
                    synced = false         // l'enregistreur a redémarré
                } else if (d > 0) {
                    lost += d
                    if (d <= 40) fifo.pushSilence(d * n) else synced = false
                }
            }
            if (!synced) {
                Log.i(Protocol.TAG, "Retour : synchronisation sur $from (seq=$seq)")
                fifo.clear()
                resetRequested = true
                synced = true
            }
            expected = seq + 1

            b.asShortBuffer().get(samples, 0, n)
            fifo.push(samples, n)

            val now = SystemClock.elapsedRealtime()
            lastPacketMs = now
            if (peak > meterPeak) meterPeak = peak
            if (peak >= Protocol.CLIP_LEVEL) clipUntil = now + 2000
            remoteRecording = flags and Protocol.FLAG_RECORDING != 0
            remoteNotUsbMic = flags and Protocol.FLAG_NOT_USB_MIC != 0
            remoteMicBlocked = flags and Protocol.FLAG_MIC_BLOCKED != 0
            remoteElapsedMs = elapsed
        }
    }

    private fun playbackLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val rate = Protocol.SAMPLE_RATE
        val perMs = rate / 1000
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(rate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(
                    AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
                )
                .build()
        } catch (e: Exception) {
            error = "Sortie audio impossible : ${e.message}"
            return
        }

        // Tampon de sortie au plus court (2 périodes matérielles), agrandi seulement si la carte son
        // manque de données : par défaut Android prend ~40 ms, surtout vers une sortie USB.
        val burst = getSystemService(AudioManager::class.java)
            .getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull()?.takeIf { it > 0 } ?: 240
        val initialFrames = track.bufferSizeInFrames
        track.bufferSizeInFrames = burst * 2
        var lastHwUnderruns = track.underrunCount
        var nextTune = SystemClock.elapsedRealtime() + 500
        val ts = AudioTimestamp()
        var framesWritten = 0L
        fun play(data: ShortArray, n: Int) {
            val w = track.write(data, 0, n)
            if (w > 0) framesWritten += w
        }

        val chunk = Protocol.FRAMES_PER_PACKET
        val out = ShortArray(chunk + 1)
        val silence = ShortArray(chunk)
        var prebuffering = true
        var nextStats = SystemClock.elapsedRealtime() + 5000
        Log.i(Protocol.TAG, "Lecture : tampon sortie ${initialFrames} -> ${track.bufferSizeInFrames} trames (période $burst, capacité ${track.bufferCapacityInFrames}), mode=${track.performanceMode}")
        track.play()
        try {
            while (running) {
                if (resetRequested) {
                    resetRequested = false
                    prebuffering = true
                }
                val tNow = SystemClock.elapsedRealtime()
                if (tNow >= nextTune) {
                    val hw = track.underrunCount
                    if (hw > lastHwUnderruns && track.bufferSizeInFrames < track.bufferCapacityInFrames) {
                        track.bufferSizeInFrames = track.bufferSizeInFrames + burst
                        Log.i(Protocol.TAG, "Sortie en manque : tampon sortie -> ${track.bufferSizeInFrames} trames")
                    }
                    lastHwUnderruns = hw
                    // Latence de sortie = trames écrites pas encore entendues.
                    if (track.getTimestamp(ts)) {
                        val ageMs = (System.nanoTime() - ts.nanoTime) / 1_000_000.0
                        outputLatencyMs = ((framesWritten - ts.framePosition) * 1000.0 / rate - ageMs).toInt().coerceAtLeast(0)
                    }
                    outputBufferMs = track.bufferSizeInFrames * 1000 / rate
                    nextTune = tNow + 500
                }
                if (tNow >= nextStats) {
                    Log.i(Protocol.TAG, "retour: tampon=${bufferMs} ms (cible ${jitterMs}+${delayMs}), sortie=${outputLatencyMs} ms (tampon ${outputBufferMs} ms), perdus=$lost, coupures=$underruns, manques sortie=${track.underrunCount}")
                    nextStats = tNow + 5000
                }
                val target = maxOf(jitterMs + delayMs, 5) * perMs
                val fill = fifo.size()
                bufferMs = fill / perMs

                if (prebuffering) {
                    if (fill >= target) {
                        prebuffering = false
                    } else {
                        play(silence, chunk)
                        continue
                    }
                }
                if (fill < chunk) {
                    underruns++
                    prebuffering = true
                    play(silence, chunk)
                    continue
                }

                when {
                    // Gros écart (réglage modifié, rafale réseau) : on recale d'un coup.
                    fill > target + 50 * perMs -> fifo.skip(fill - target)
                    fill < target - 50 * perMs -> {
                        prebuffering = true
                        continue
                    }
                    // Petite dérive d'horloge entre les deux téléphones : on jette ou double un échantillon.
                    fill > target + 10 * perMs -> {
                        fifo.pop(out, chunk + 1)
                        play(out, chunk)
                        continue
                    }
                    fill < target - 10 * perMs -> {
                        fifo.pop(out, chunk - 1)
                        out[chunk - 1] = out[chunk - 2]
                        play(out, chunk)
                        continue
                    }
                }
                val got = fifo.pop(out, chunk)
                play(out, got)
            }
        } finally {
            track.stop()
            track.release()
        }
    }
}
