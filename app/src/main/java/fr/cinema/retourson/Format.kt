package fr.cinema.retourson

import java.util.Locale

fun formatDuration(ms: Long): String {
    val s = ms / 1000
    return String.format(Locale.US, "%02d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
}

/** N'écrit le texte que s'il a changé : évite une remise en page à chaque rafraîchissement d'écran. */
fun android.widget.TextView.setTextIfChanged(s: CharSequence) {
    if (text.toString() != s.toString()) text = s
}

/** Idem pour la couleur de fond (setBackgroundColor recrée le fond à chaque appel). */
fun android.view.View.setBgIfChanged(color: Int) {
    if (getTag(R.id.tag_bg) != color) {
        setTag(R.id.tag_bg, color)
        setBackgroundColor(color)
    }
}
