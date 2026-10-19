package fr.cinema.retourson

import android.app.Activity
import android.app.AlertDialog
import android.app.RecoverableSecurityException
import android.content.ClipData
import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.graphics.Color
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.InputType
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Liste des prises, groupées par jour, comme l'enregistreur du téléphone :
 * appui = écouter, appui long = sélection, puis partage groupé (Drive, Quick Share...), renommage, suppression.
 */
class TakesActivity : Activity() {

    private class Take(val uri: Uri, val name: String, val bytes: Long, modifiedSec: Long) {
        val durationMs = maxOf(0L, bytes - 44) * 1000 / (Protocol.SAMPLE_RATE * 3L)
        /**
         * Début de la prise = dernière écriture du fichier moins sa durée. La date d'ajout de
         * MediaStore ne convient pas : c'est la date où Android a découvert le fichier.
         */
        val dateSec = modifiedSec - durationMs / 1000
        val baseName get() = name.removeSuffix(".wav").removeSuffix(".WAV")
    }

    /** Ligne de la liste : en-tête de jour ou prise. */
    private sealed class Row {
        class Day(val label: String, val takes: List<Take>) : Row()
        class Item(val take: Take) : Row()
    }

    private val handler = Handler(Looper.getMainLooper())
    private val takes = ArrayList<Take>()
    private val rows = ArrayList<Row>()
    private val selected = LinkedHashSet<Uri>()
    private var player: MediaPlayer? = null
    private var playing: Uri? = null
    private lateinit var adapter: TakesAdapter
    private lateinit var empty: TextView
    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private lateinit var selBar: View
    private lateinit var btnCancelSel: Button
    private lateinit var btnRename: Button

    /** Action à refaire une fois que l'utilisateur a autorisé la modification (fichiers d'une ancienne installation). */
    private var afterPermission: (() -> Unit)? = null

    private val progressTick = object : Runnable {
        override fun run() {
            if (player == null) return
            updateProgress()
            handler.postDelayed(this, 250)
        }
    }

    private val selectionMode get() = selected.isNotEmpty()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_takes)
        empty = findViewById(R.id.txtEmpty)
        title = findViewById(R.id.txtTitle)
        subtitle = findViewById(R.id.txtSubtitle)
        selBar = findViewById(R.id.selBar)
        btnCancelSel = findViewById(R.id.btnCancelSel)
        btnRename = findViewById(R.id.btnRename)
        adapter = TakesAdapter()
        findViewById<ListView>(R.id.list).adapter = adapter

        btnCancelSel.setOnClickListener { clearSelection() }
        findViewById<Button>(R.id.btnShare).setOnClickListener { share(selectedTakes()) }
        btnRename.setOnClickListener { selectedTakes().singleOrNull()?.let { rename(it) } }
        findViewById<Button>(R.id.btnDelete).setOnClickListener { confirmDelete(selectedTakes()) }
        if (checkSelfPermission(readPermission) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(readPermission), REQ_READ)
        }
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    /**
     * Sans cette permission, Android ne montre que les prises créées par cette installation :
     * après une réinstallation, les anciennes disparaîtraient de la liste.
     */
    private val readPermission =
        if (Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_AUDIO
        else android.Manifest.permission.READ_EXTERNAL_STORAGE

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == REQ_READ) load()
    }

    override fun onPause() {
        stopPlayback()
        super.onPause()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (selectionMode) clearSelection() else super.onBackPressed()
    }

    private fun load() {
        takes.clear()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATE_MODIFIED,
        )
        contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?",
            arrayOf("Music/RetourSon%"),
            "${MediaStore.Audio.Media.DATE_MODIFIED} DESC",
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
        selected.retainAll(takes.map { it.uri }.toSet())
        buildRows()
        empty.visibility = if (takes.isEmpty()) View.VISIBLE else View.GONE
        updateSelectionUi()
    }

    private fun buildRows() {
        rows.clear()
        val byDay = LinkedHashMap<Long, ArrayList<Take>>()
        for (t in takes) byDay.getOrPut(dayKey(t.dateSec)) { ArrayList() } += t
        for ((day, list) in byDay) {
            rows += Row.Day(dayLabel(day), list)
            list.forEach { rows += Row.Item(it) }
        }
    }

    private fun dayKey(sec: Long): Long = Calendar.getInstance().apply {
        timeInMillis = sec * 1000
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun dayLabel(dayMs: Long): String {
        val today = dayKey(System.currentTimeMillis() / 1000)
        val sameYear = Calendar.getInstance().apply { timeInMillis = dayMs }.get(Calendar.YEAR) ==
            Calendar.getInstance().get(Calendar.YEAR)
        val date = DateFormat.format(if (sameYear) "EEEE d MMMM" else "EEEE d MMMM yyyy", Date(dayMs)).toString()
        return when {
            dayMs == today -> "Aujourd'hui · $date"
            // 25 h : tolère le changement d'heure.
            today - dayMs in 1..25 * 3600_000L -> "Hier · $date"
            else -> date.replaceFirstChar { it.uppercase(Locale.FRANCE) }
        }
    }

    private fun selectedTakes() = takes.filter { it.uri in selected }

    private fun toggle(t: Take) {
        if (!selected.remove(t.uri)) selected += t.uri
        updateSelectionUi()
    }

    private fun toggleDay(d: Row.Day) {
        val uris = d.takes.map { it.uri }
        if (selected.containsAll(uris)) selected.removeAll(uris.toSet()) else selected.addAll(uris)
        updateSelectionUi()
    }

    private fun clearSelection() {
        selected.clear()
        updateSelectionUi()
    }

    private fun updateSelectionUi() {
        val n = selected.size
        if (n > 0) stopPlayback()
        title.text = if (n == 0) "Prises" else if (n == 1) "1 prise sélectionnée" else "$n prises sélectionnées"
        title.textSize = if (n == 0) 26f else 20f
        subtitle.visibility = if (n == 0) View.VISIBLE else View.GONE
        btnCancelSel.visibility = if (n == 0) View.GONE else View.VISIBLE
        selBar.visibility = if (n == 0) View.GONE else View.VISIBLE
        btnRename.isEnabled = n == 1
        btnRename.alpha = if (n == 1) 1f else 0.4f
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
            handler.post(progressTick)
        } catch (e: Exception) {
            Toast.makeText(this, "Lecture impossible : ${e.message}", Toast.LENGTH_LONG).show()
            stopPlayback()
        }
        adapter.notifyDataSetChanged()
    }

    private fun stopPlayback() {
        handler.removeCallbacks(progressTick)
        player?.release()
        player = null
        playing = null
        playingHolder = null
        if (::adapter.isInitialized) adapter.notifyDataSetChanged()
    }

    /** Partage natif Android : une ou plusieurs prises d'un coup (Drive, Quick Share, mail...). */
    private fun share(list: List<Take>) {
        if (list.isEmpty()) return
        val uris = ArrayList(list.map { it.uri })
        val send = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        }
        send.type = "audio/x-wav"
        // Le ClipData transmet l'autorisation de lecture pour chacun des fichiers.
        send.clipData = ClipData.newRawUri(list[0].name, uris[0]).apply {
            for (u in uris.drop(1)) addItem(ClipData.Item(u))
        }
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val label = if (list.size == 1) list[0].name else "${list.size} prises"
        startActivity(Intent.createChooser(send, label))
        clearSelection()
    }

    private fun rename(t: Take) {
        val input = EditText(this).apply {
            setText(t.baseName)
            setSelectAllOnFocus(true)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            hint = "ex. S12_P3_T2"
        }
        val d = resources.displayMetrics.density
        val box = LinearLayout(this).apply {
            setPadding((20 * d).toInt(), (8 * d).toInt(), (20 * d).toInt(), 0)
            addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Renommer la prise")
            .setView(box)
            .setPositiveButton("Renommer") { _, _ ->
                val clean = input.text.toString().trim().replace(Regex("""[\\/:*?"<>|]"""), "_")
                if (clean.isEmpty() || clean == t.baseName) return@setPositiveButton
                val newName = "$clean.wav"
                if (takes.any { it.uri != t.uri && it.name.equals(newName, ignoreCase = true) }) {
                    Toast.makeText(this, "Une prise s'appelle déjà $newName", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                modify(listOf(t)) {
                    contentResolver.update(t.uri, ContentValues().apply {
                        put(MediaStore.Audio.Media.DISPLAY_NAME, newName)
                    }, null, null)
                    clearSelection()
                    load()
                }
            }
            .setNegativeButton("Annuler", null)
            .create()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        // La touche OK du clavier valide directement.
        input.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
        input.setOnEditorActionListener { _, _, _ ->
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            true
        }
        dialog.show()
        input.requestFocus()
    }

    private fun confirmDelete(list: List<Take>) {
        if (list.isEmpty()) return
        val what = if (list.size == 1) "« ${list[0].name} »" else "ces ${list.size} prises"
        AlertDialog.Builder(this)
            .setTitle("Supprimer $what ?")
            .setMessage("Les fichiers seront effacés du téléphone. Vérifie qu'ils sont bien sur le Drive.")
            .setPositiveButton("Supprimer") { _, _ ->
                modify(list, isDelete = true) {
                    for (t in list) contentResolver.delete(t.uri, null, null)
                    clearSelection()
                    load()
                }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    /**
     * Les prises enregistrées par une installation précédente de l'appli n'appartiennent plus à
     * celle-ci : Android demande alors l'autorisation à l'utilisateur, puis on recommence.
     */
    private fun modify(list: List<Take>, isDelete: Boolean = false, action: () -> Unit) {
        try {
            action()
        } catch (e: SecurityException) {
            val uris = list.map { it.uri }
            val sender = when {
                // La demande de suppression efface elle-même les fichiers une fois acceptée.
                Build.VERSION.SDK_INT >= 30 && isDelete -> {
                    afterPermission = { clearSelection(); load() }
                    startIntentSenderForResult(MediaStore.createDeleteRequest(contentResolver, uris).intentSender, REQ_WRITE, null, 0, 0, 0)
                    return
                }
                Build.VERSION.SDK_INT >= 30 ->
                    MediaStore.createWriteRequest(contentResolver, uris).intentSender
                e is RecoverableSecurityException -> e.userAction.actionIntent.intentSender
                else -> null
            }
            if (sender == null) {
                Toast.makeText(this, "Modification refusée : ${e.message}", Toast.LENGTH_LONG).show()
                return
            }
            afterPermission = action
            startIntentSenderForResult(sender, REQ_WRITE, null, 0, 0, 0)
        } catch (e: Exception) {
            Toast.makeText(this, "Impossible : ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQ_WRITE) {
            val action = afterPermission
            afterPermission = null
            if (resultCode == RESULT_OK && action != null) {
                try {
                    action()
                } catch (e: Exception) {
                    Toast.makeText(this, "Impossible : ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showMenu(anchor: View, t: Take) {
        PopupMenu(this, anchor).apply {
            menu.add(0, 1, 0, "Partager")
            menu.add(0, 2, 1, "Renommer")
            menu.add(0, 3, 2, "Supprimer")
            setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> share(listOf(t))
                    2 -> rename(t)
                    3 -> confirmDelete(listOf(t))
                }
                true
            }
            show()
        }
    }

    private inner class DayHolder(val root: LinearLayout, val label: TextView, val info: TextView, val btn: Button)
    private inner class TakeHolder(
        val root: LinearLayout, val check: CheckBox, val name: TextView, val sub: TextView,
        val play: Button, val more: Button,
    ) {
        var take: Take? = null
    }

    /** Ligne de la prise en lecture : seule mise à jour pendant l'écoute. */
    private var playingHolder: TakeHolder? = null

    private fun updateProgress() {
        val h = playingHolder ?: return
        val t = h.take ?: return
        val p = player ?: return
        if (t.uri != playing) return
        h.sub.setTextIfChanged("▶ ${formatDuration(p.currentPosition.toLong())} / ${formatDuration(t.durationMs)}")
    }

    private inner class TakesAdapter : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(position: Int) = if (rows[position] is Row.Day) 0 else 1
        override fun isEnabled(position: Int) = false

        private val d = resources.displayMetrics.density

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
            when (val r = rows[position]) {
                is Row.Day -> ((convertView?.tag as? DayHolder) ?: newDay()).also { bindDay(it, r) }.root
                is Row.Item -> ((convertView?.tag as? TakeHolder) ?: newTake()).also { bindTake(it, r.take) }.root
            }

        private fun newDay(): DayHolder {
            val label = TextView(this@TakesActivity).apply {
                setTextColor(Color.rgb(144, 202, 249))
                textSize = 15f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
            val info = TextView(this@TakesActivity).apply {
                setTextColor(Color.GRAY)
                textSize = 12f
            }
            val btn = Button(this@TakesActivity).apply {
                isAllCaps = false
                textSize = 13f
                minWidth = 0
                minimumWidth = 0
            }
            val root = LinearLayout(this@TakesActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundColor(Color.rgb(28, 28, 28))
                setPadding((20 * d).toInt(), (10 * d).toInt(), (8 * d).toInt(), (6 * d).toInt())
                addView(LinearLayout(this@TakesActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    addView(label)
                    addView(info)
                })
                addView(btn)
            }
            return DayHolder(root, label, info, btn).also { root.tag = it }
        }

        private fun bindDay(h: DayHolder, day: Row.Day) {
            val all = selected.containsAll(day.takes.map { it.uri })
            val n = day.takes.size
            h.label.text = day.label
            h.info.text = "$n prise${if (n > 1) "s" else ""} · ${formatDuration(day.takes.sumOf { it.durationMs })}"
            h.btn.text = if (all) "✓ Tout le jour" else "Sélectionner le jour"
            h.btn.setTextColor(if (all) Color.rgb(144, 202, 249) else Color.WHITE)
            h.btn.setOnClickListener { toggleDay(day) }
        }

        private fun newTake(): TakeHolder {
            val check = CheckBox(this@TakesActivity).apply {
                isClickable = false
                isFocusable = false
            }
            val name = TextView(this@TakesActivity).apply {
                textSize = 17f
                isSingleLine = true
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            }
            val sub = TextView(this@TakesActivity).apply { textSize = 13f }
            val play = Button(this@TakesActivity).apply {
                textSize = 18f
                minWidth = 0
                minimumWidth = (56 * d).toInt()
            }
            val more = Button(this@TakesActivity).apply {
                text = "⋮"
                textSize = 20f
                minWidth = 0
                minimumWidth = (44 * d).toInt()
                setBackgroundColor(Color.TRANSPARENT)
                setTextColor(Color.LTGRAY)
            }
            val root = LinearLayout(this@TakesActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = (64 * d).toInt()
                addView(check)
                addView(LinearLayout(this@TakesActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    addView(name)
                    addView(sub)
                })
                addView(play)
                addView(more)
            }
            return TakeHolder(root, check, name, sub, play, more).also { root.tag = it }
        }

        private fun bindTake(h: TakeHolder, t: Take) {
            h.take = t
            val isSel = t.uri in selected
            val isPlaying = playing == t.uri
            if (isPlaying) playingHolder = h else if (playingHolder === h) playingHolder = null
            val sel = selectionMode
            h.root.setPadding(((if (sel) 8 else 20) * d).toInt(), (6 * d).toInt(), 0, (6 * d).toInt())
            h.root.setBgIfChanged(if (isSel) Color.rgb(21, 50, 90) else Color.TRANSPARENT)
            h.root.setOnClickListener { if (selectionMode) toggle(t) else togglePlay(t) }
            h.root.setOnLongClickListener { toggle(t); true }
            h.check.visibility = if (sel) View.VISIBLE else View.GONE
            h.check.isChecked = isSel
            h.name.text = t.baseName
            val color = if (isPlaying) Color.rgb(90, 220, 120) else Color.WHITE
            h.name.setTextColor(color)
            h.sub.setTextColor(if (isPlaying) color else Color.GRAY)
            if (isPlaying) {
                updateProgress()
            } else {
                val time = DateFormat.format("HH:mm", Date(t.dateSec * 1000))
                val mb = String.format(Locale.FRANCE, "%.1f Mo", t.bytes / 1_000_000.0)
                h.sub.text = "$time · ${formatDuration(t.durationMs)} · $mb"
            }
            h.play.visibility = if (sel) View.GONE else View.VISIBLE
            h.more.visibility = h.play.visibility
            h.play.text = if (isPlaying) "■" else "▶"
            h.play.setOnClickListener { togglePlay(t) }
            h.more.setOnClickListener { showMenu(it, t) }
        }
    }

    companion object {
        private const val REQ_WRITE = 10
        private const val REQ_READ = 11
    }
}
