package fr.cinema.retourson

import android.content.ContentResolver
import android.content.ContentValues
import android.os.ParcelFileDescriptor
import android.os.Process
import android.provider.MediaStore
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Toutes les opérations disque (création du fichier, écriture, fermeture) se font ici,
 * sur un thread dédié : le thread de capture ne bloque jamais, donc le retour ne coupe pas
 * au démarrage d'une prise ni lors d'un ralentissement de la mémoire flash.
 */
class TakeWriter(
    private val resolver: ContentResolver,
    private val onEvent: (Event) -> Unit,
) {
    sealed class Event {
        data class Started(val name: String) : Event()
        data class Stopped(val name: String) : Event()
        data class Failed(val message: String) : Event()
    }

    private sealed class Cmd {
        data class Start(val name: String) : Cmd()
        class Data(val samples: FloatArray) : Cmd()
        object Stop : Cmd()
        object Quit : Cmd()
    }

    // 4000 blocs de 5 ms = 20 s de marge si la mémoire flash cale.
    private val queue = ArrayBlockingQueue<Cmd>(4000)
    private val thread = Thread({ loop() }, "take-writer")
    @Volatile var overflow = false; private set

    fun start() = thread.start()

    /** Appelé par le thread de capture : ne bloque jamais. */
    fun begin(): String {
        val name = "RS_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".wav"
        offer(Cmd.Start(name))
        return name
    }

    fun write(samples: FloatArray, count: Int) = offer(Cmd.Data(samples.copyOf(count)))

    fun end() = offer(Cmd.Stop)

    fun close() {
        queue.put(Cmd.Quit)
        thread.join(5000)
    }

    private fun offer(cmd: Cmd) {
        if (!queue.offer(cmd)) {
            overflow = true
            Log.e(Protocol.TAG, "File d'écriture pleine : échantillons perdus")
        }
    }

    private class Open(val name: String, val out: ParcelFileDescriptor.AutoCloseOutputStream, val wav: WavWriter)

    private fun loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        var open: Open? = null
        while (true) {
            val cmd = queue.poll(1, TimeUnit.SECONDS) ?: continue
            when (cmd) {
                is Cmd.Start -> {
                    open?.let { finish(it) }
                    open = create(cmd.name)
                }
                is Cmd.Data -> open?.let { o ->
                    try {
                        o.wav.write(cmd.samples, cmd.samples.size)
                    } catch (e: Exception) {
                        onEvent(Event.Failed("Écriture impossible : ${e.message}"))
                        finish(o)
                        open = null
                    }
                }
                Cmd.Stop -> {
                    open?.let { finish(it) }
                    open = null
                }
                Cmd.Quit -> {
                    open?.let { finish(it) }
                    return
                }
            }
        }
    }

    private fun create(name: String): Open? = try {
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, name)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/x-wav")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/RetourSon")
        }
        val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("MediaStore a refusé le fichier")
        val pfd = resolver.openFileDescriptor(uri, "rw") ?: throw IllegalStateException("Fichier inaccessible")
        val out = ParcelFileDescriptor.AutoCloseOutputStream(pfd)
        Open(name, out, WavWriter(out.channel, Protocol.SAMPLE_RATE, 1)).also { onEvent(Event.Started(name)) }
    } catch (e: Exception) {
        onEvent(Event.Failed("Création du fichier impossible : ${e.message}"))
        null
    }

    private fun finish(o: Open) {
        try {
            o.wav.close()
        } catch (e: Exception) {
            onEvent(Event.Failed("Fermeture du fichier : ${e.message}"))
        }
        try {
            o.out.close()
        } catch (_: Exception) {
        }
        onEvent(Event.Stopped(o.name))
    }
}
