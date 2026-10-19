package fr.cinema.retourson

import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Côté enregistreur : diffuse la balise de découverte et envoie l'audio aux retours abonnés.
 * Tout passe par une file non bloquante : le réseau ne peut jamais ralentir l'enregistrement.
 */
class AudioStreamer(
    private val deviceName: String,
    /** Commande REC (true) / STOP (false) reçue d'un retour, appelée hors du thread de capture. */
    private val onCommand: (rec: Boolean, fromName: String) -> Unit,
) {
    private val socket = DatagramSocket(null).apply {
        reuseAddress = true
        bind(InetSocketAddress(Protocol.AUDIO_PORT))
        soTimeout = 1000
    }
    private val beaconSocket = DatagramSocket().apply { broadcast = true }
    private val listeners = ConcurrentHashMap<InetSocketAddress, Long>()
    private val lastCommandId = HashMap<InetSocketAddress, Int>()
    private val queue = ArrayBlockingQueue<ByteArray>(100)

    @Volatile private var open = true
    @Volatile private var flags = 0
    /** Micro bloqué : annoncé dans la balise, qui part même quand le micro ne livre plus de son. */
    @Volatile var micBlocked = false
    private var seq = 0
    @Volatile private var maxSendGap = 0L

    /** Plus grand écart entre deux envois depuis le dernier appel (diagnostic). */
    fun takeMaxSendGap(): Long = maxSendGap.also { maxSendGap = 0 }

    val listenerCount: Int get() = listeners.size

    private val rx = Thread({ rxLoop() }, "stream-rx")
    private val tx = Thread({ txLoop() }, "stream-tx")

    fun start() {
        rx.start()
        tx.start()
    }

    /** Appelé par le thread de capture toutes les 5 ms. */
    fun push(samples: FloatArray, count: Int, peak: Float, flags: Int, recElapsedMs: Int) {
        this.flags = flags
        val s = seq++
        if (listeners.isEmpty()) return
        val b = ByteBuffer.allocate(Protocol.AUDIO_HEADER + count * 2)
        b.putInt(Protocol.AUDIO_MAGIC).putInt(s).put(flags.toByte()).putFloat(peak)
            .putInt(recElapsedMs).putShort(count.toShort())
        for (i in 0 until count) {
            b.putShort((samples[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort())
        }
        queue.offer(b.array())
    }

    fun close() {
        open = false
        socket.close()
        beaconSocket.close()
        rx.join(1500)
        tx.join(1500)
    }

    private fun rxLoop() {
        val buf = ByteArray(512)
        val p = DatagramPacket(buf, buf.size)
        while (open) {
            p.length = buf.size
            try {
                socket.receive(p)
            } catch (_: SocketTimeoutException) {
                continue
            } catch (_: Exception) {
                if (!open) break else continue
            }
            if (p.length < 4) continue
            val b = ByteBuffer.wrap(buf, 0, p.length)
            val addr = InetSocketAddress(p.address, p.port)
            when (b.int) {
                Protocol.HELLO_MAGIC ->
                    if (listeners.put(addr, SystemClock.elapsedRealtime()) == null) Log.i(Protocol.TAG, "Retour connecté : $addr")
                Protocol.COMMAND_MAGIC -> if (p.length >= 10) {
                    val id = b.int
                    val rec = b.get().toInt() != 0
                    val len = minOf(b.get().toInt() and 0xff, b.remaining())
                    val name = String(buf, b.position(), len, Charsets.UTF_8).ifEmpty { p.address.hostAddress ?: "?" }
                    // La commande est répétée par le retour : on ne l'applique qu'une fois.
                    if (lastCommandId.put(addr, id) != id) {
                        Log.i(Protocol.TAG, "Commande ${if (rec) "REC" else "STOP"} reçue de $name ($addr)")
                        onCommand(rec, name)
                    }
                }
            }
        }
    }

    private fun txLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        var nextBeacon = 0L
        var lastSend = 0L
        while (open) {
            val now = SystemClock.elapsedRealtime()
            if (now >= nextBeacon) {
                sendBeacon()
                listeners.entries.removeIf {
                    (now - it.value > Protocol.LISTENER_TIMEOUT_MS).also { gone -> if (gone) Log.i(Protocol.TAG, "Retour déconnecté : ${it.key}") }
                }
                nextBeacon = now + 1000
            }
            val data = queue.poll(200, TimeUnit.MILLISECONDS)
            if (data == null) {
                lastSend = 0L
                continue
            }
            SystemClock.elapsedRealtime().let { t ->
                if (lastSend != 0L && t - lastSend > maxSendGap) maxSendGap = t - lastSend
                lastSend = t
            }
            for (addr in listeners.keys) {
                try {
                    socket.send(DatagramPacket(data, data.size, addr))
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun sendBeacon() {
        val name = deviceName.toByteArray(Charsets.UTF_8).take(200).toByteArray()
        val b = ByteBuffer.allocate(6 + name.size)
        val f = (flags and Protocol.FLAG_MIC_BLOCKED.inv()) or (if (micBlocked) Protocol.FLAG_MIC_BLOCKED else 0)
        b.putInt(Protocol.BEACON_MAGIC).put(f.toByte()).put(name.size.toByte()).put(name)
        val data = b.array()
        for (addr in Net.broadcastAddresses()) {
            try {
                beaconSocket.send(DatagramPacket(data, data.size, addr, Protocol.DISCOVERY_PORT))
            } catch (_: Exception) {
            }
        }
    }
}
