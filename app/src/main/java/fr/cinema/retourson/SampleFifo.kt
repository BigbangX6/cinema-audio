package fr.cinema.retourson

/** File circulaire d'échantillons ; en cas de débordement, les plus anciens sont jetés. */
class SampleFifo(private val capacity: Int) {
    private val buf = ShortArray(capacity)
    private var read = 0
    private var write = 0
    private var size = 0

    @Synchronized fun size() = size

    @Synchronized fun push(src: ShortArray, count: Int) {
        for (i in 0 until count) put(src[i])
    }

    @Synchronized fun pushSilence(count: Int) {
        repeat(minOf(count, capacity)) { put(0) }
    }

    @Synchronized fun pop(dst: ShortArray, count: Int): Int {
        val k = minOf(count, size)
        for (i in 0 until k) {
            dst[i] = buf[read]
            read = (read + 1) % capacity
        }
        size -= k
        return k
    }

    @Synchronized fun skip(count: Int) {
        val k = minOf(count, size)
        read = (read + k) % capacity
        size -= k
    }

    @Synchronized fun clear() {
        read = 0
        write = 0
        size = 0
    }

    private fun put(v: Short) {
        if (size == capacity) {
            read = (read + 1) % capacity
            size--
        }
        buf[write] = v
        write = (write + 1) % capacity
        size++
    }
}
