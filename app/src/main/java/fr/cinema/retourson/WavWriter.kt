package fr.cinema.retourson

import android.os.SystemClock
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.roundToInt

/**
 * WAV PCM 24 bits. L'en-tête est réécrit chaque seconde : si l'appli ou le téléphone
 * plante, le fichier reste lisible jusqu'à la dernière seconde écrite.
 */
class WavWriter(
    private val channel: FileChannel,
    private val sampleRate: Int,
    private val channels: Int,
) {
    private val buf = ByteBuffer.allocate(64 * 1024).order(ByteOrder.LITTLE_ENDIAN)
    private var dataBytes = 0L
    private var lastHeader = SystemClock.elapsedRealtime()
    private var lastSync = lastHeader

    init {
        writeHeader()
        channel.position(HEADER_SIZE.toLong())
    }

    fun write(samples: FloatArray, count: Int) {
        if (buf.remaining() < count * 3) flush()
        for (i in 0 until count) {
            val s = (samples[i].coerceIn(-1f, 1f) * 8388607f).roundToInt()
            buf.put(s.toByte())
            buf.put((s shr 8).toByte())
            buf.put((s shr 16).toByte())
        }
        dataBytes += count * 3L

        val now = SystemClock.elapsedRealtime()
        if (now - lastHeader >= 1000) {
            flush()
            writeHeader()
            lastHeader = now
            if (now - lastSync >= 5000) {
                channel.force(false)
                lastSync = now
            }
        }
    }

    fun close() {
        flush()
        writeHeader()
        channel.force(true)
    }

    private fun flush() {
        buf.flip()
        while (buf.hasRemaining()) channel.write(buf)
        buf.clear()
    }

    private fun writeHeader() {
        val data = dataBytes.coerceAtMost(0xFFFFFFFFL - 36)
        val blockAlign = channels * 3
        val h = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt((36 + data).toInt())
        h.put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()).putInt(16)
        h.putShort(1).putShort(channels.toShort())
        h.putInt(sampleRate).putInt(sampleRate * blockAlign)
        h.putShort(blockAlign.toShort()).putShort(24)
        h.put("data".toByteArray()).putInt(data.toInt())
        h.flip()
        var pos = 0L
        while (h.hasRemaining()) pos += channel.write(h, pos)
    }

    companion object {
        private const val HEADER_SIZE = 44
    }
}
