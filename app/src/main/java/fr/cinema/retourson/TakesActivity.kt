package fr.cinema.retourson

import android.app.Activity
import android.content.ContentUris
import android.content.Intent
import android.graphics.Color
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.util.Date
import java.util.Locale

/** Liste des prises : écoute et partage (Quick Share, Drive, mail...). */
class TakesActivity : Activity() {

    private class Take(val uri: Uri, val name: String, val bytes: Long, val dateSec: Long) {
        val durationMs get() = maxOf(0L, bytes - 44) * 1000 / (Protocol.SAMPLE_RATE * 3L)
    }

    private val takes = ArrayList<Take>()
    private var player: MediaPlayer? = null
    private var playing: Uri? = null
    private lateinit var adapter: TakesAdapter
    private lateinit var empty: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_takes)
        empty = findViewById(R.id.txtEmpty)
        adapter = TakesAdapter()
        findViewById<ListView>(R.id.list).adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    override fun onPause() {
        stopPlayback()
        super.onPause()
    }

    private fun load() {
        takes.clear()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATE_ADDED,
        )
        contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?",
            arrayOf("Music/RetourSon%"),
            "${MediaStore.Audio.Media.DATE_ADDED} DESC",
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                takes += Take(
                    ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id),
                    c.getString(1), c.getLong(2), c.getLong(3),
                )
            }
        }
        // La prise en cours d'enregistrement n'est pas encore complète.
        if (CaptureService.recording) takes.removeAll { it.name == CaptureService.fileName }
        empty.visibility = if (takes.isEmpty()) View.VISIBLE else View.GONE
        adapter.notifyDataSetChanged()
    }

    private fun togglePlay(t: Take) {
        if (playing == t.uri) {
            stopPlayback()
            return
        }
        stopPlayback()
        try {
            player = MediaPlayer().apply {
                setDataSource(this@TakesActivity, t.uri)
                setOnCompletionListener { stopPlayback() }
                prepare()
                start()
            }
            playing = t.uri
        } catch (e: Exception) {
            Toast.makeText(this, "Lecture impossible : ${e.message}", Toast.LENGTH_LONG).show()
            stopPlayback()
        }
        adapter.notifyDataSetChanged()
    }

    private fun stopPlayback() {
        player?.release()
        player = null
        playing = null
        if (::adapter.isInitialized) adapter.notifyDataSetChanged()
    }

    private fun share(t: Take) {
        val send = Intent(Intent.ACTION_SEND)
            .setType("audio/x-wav")
            .putExtra(Intent.EXTRA_STREAM, t.uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, t.name))
    }

    private inner class TakesAdapter : BaseAdapter() {
        override fun getCount() = takes.size
        override fun getItem(position: Int) = takes[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val t = takes[position]
            val d = resources.displayMetrics.density
            val row = LinearLayout(this@TakesActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, (8 * d).toInt(), 0, (8 * d).toInt())
            }
            val info = LinearLayout(this@TakesActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(this@TakesActivity).apply {
                    val date = DateFormat.format("dd/MM HH:mm", Date(t.dateSec * 1000))
                    text = "$date · ${formatDuration(t.durationMs)}"
                    setTextColor(Color.WHITE)
                    textSize = 17f
                })
                addView(TextView(this@TakesActivity).apply {
                    val mb = String.format(Locale.FRANCE, "%.1f Mo", t.bytes / 1_000_000.0)
                    text = "${t.name} · $mb"
                    setTextColor(Color.GRAY)
                    textSize = 12f
                    isSingleLine = true
                    ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                })
            }
            val play = Button(this@TakesActivity).apply {
                text = if (playing == t.uri) "■" else "▶"
                textSize = 18f
                minWidth = 0
                minimumWidth = (64 * d).toInt()
                setOnClickListener { togglePlay(t) }
            }
            val share = Button(this@TakesActivity).apply {
                text = "Partager"
                isAllCaps = false
                minWidth = 0
                minimumWidth = 0
                setOnClickListener { share(t) }
            }
            row.addView(info)
            row.addView(play)
            row.addView(share)
            return row
        }
    }
}
