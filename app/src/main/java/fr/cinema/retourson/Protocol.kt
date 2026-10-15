package fr.cinema.retourson

/**
 * Protocole réseau (UDP, big-endian).
 *
 * Balise (enregistreur -> broadcast:DISCOVERY_PORT, 1/s) :
 *   magic BEACON | flags u8 | len u8 | nom UTF-8
 * Hello (retour -> enregistreur:AUDIO_PORT, 2/s) : magic HELLO
 *   L'enregistreur n'envoie l'audio qu'aux retours vus depuis moins de 3 s.
 * Audio (enregistreur -> retour, 1 paquet = 5 ms) :
 *   magic AUDIO | seq i32 | flags u8 | crête f32 (0..1) | durée prise ms i32 | n i16 | n x PCM i16
 */
object Protocol {
    const val TAG = "RetourSon"

    const val SAMPLE_RATE = 48000
    const val FRAMES_PER_PACKET = 240
    const val DISCOVERY_PORT = 47000
    const val AUDIO_PORT = 47001

    const val BEACON_MAGIC = 0x52534231 // "RSB1"
    const val HELLO_MAGIC = 0x52534831  // "RSH1"
    const val AUDIO_MAGIC = 0x52534131  // "RSA1"
    const val AUDIO_HEADER = 19

    const val FLAG_RECORDING = 1
    /** L'enregistreur n'utilise pas le micro USB (débranché ?). */
    const val FLAG_NOT_USB_MIC = 2
    /** Android coupe le micro de l'enregistreur (une autre appli l'a pris) : la prise est muette. */
    const val FLAG_MIC_BLOCKED = 4

    /** -0,5 dBFS : au-delà on considère que ça sature. */
    const val CLIP_LEVEL = 0.944f

    const val LISTENER_TIMEOUT_MS = 3000L
}
