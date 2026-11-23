package ovh.adan.allstar.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Ham CallerID burst v0 (github.com/pyopower/ham-callerid, docs/SPEC.md §5): the callsign as a
 * ~150 ms Bell 202 AFSK burst at the start and end of a transmission, so whoever receives the
 * audio (another app, a node, a bridge) can show who is talking even on analog links.
 *
 * Frame: mark idle 10 ms · 0x55 0x55 0x55 · sync 0x2D 0xD4 · (version<<4 | type) · length ·
 * payload · CRC-16/CCITT-FALSE · 2 mark bits. Bytes are asynchronous (start 0, 8 bits LSB
 * first, stop 1). Mark = 1200 Hz, space = 2200 Hz, 1200 baud, continuous phase, 8 kHz.
 * Must stay byte-identical with hamcid/burst.py.
 */
object HamCallerId {
    private const val RATE = 8000
    private const val SPB = RATE / 1200.0
    private const val MARK = 1200.0
    private const val SPACE = 2200.0
    private val PREAMBLE = byteArrayOf(0x55, 0x55, 0x55)
    private val SYNC = byteArrayOf(0x2d, 0xd4.toByte())
    private const val VERSION = 0
    const val TYPE_CALLSIGN = 1
    private const val MAX_LEN = 32

    fun crc16(data: ByteArray): Int {
        var crc = 0xFFFF
        for (b in data) {
            crc = crc xor ((b.toInt() and 0xff) shl 8)
            repeat(8) { crc = if (crc and 0x8000 != 0) ((crc shl 1) xor 0x1021) and 0xFFFF else (crc shl 1) and 0xFFFF }
        }
        return crc
    }

    /** Characters allowed in a callsign burst; anything else is dropped. */
    fun cleanCallsign(s: String) = s.uppercase().filter { it in 'A'..'Z' || it in '0'..'9' || it == '/' || it == '-' }.take(MAX_LEN)

    private fun frame(payload: String, kind: Int): ByteArray {
        val p = payload.toByteArray(Charsets.US_ASCII)
        val body = byteArrayOf(((VERSION shl 4) or kind).toByte(), p.size.toByte()) + p
        val c = crc16(body)
        return PREAMBLE + SYNC + body + byteArrayOf((c shr 8).toByte(), c.toByte())
    }

    /** 16-bit PCM at 8 kHz, or null if the callsign is empty after cleaning. */
    fun modulate(callsign: String, amp: Float = 0.5f): ShortArray? {
        val cs = cleanCallsign(callsign)
        if (cs.isEmpty()) return null
        val bits = ArrayList<Int>()
        repeat(12) { bits.add(1) }
        for (b in frame(cs, TYPE_CALLSIGN)) {
            val v = b.toInt() and 0xff
            bits.add(0); for (i in 0 until 8) bits.add((v shr i) and 1); bits.add(1)
        }
        bits.add(1); bits.add(1)
        val total = kotlin.math.ceil(bits.size * SPB).toInt()
        val out = ShortArray(total)
        var phase = 0.0
        for (n in 0 until total) {
            val b = bits[minOf((n / SPB).toInt(), bits.size - 1)]
            phase += 2 * PI * (if (b == 1) MARK else SPACE) / RATE
            out[n] = (amp * 32767 * sin(phase)).roundToInt().toShort()
        }
        return out
    }

    // ------------------------------------------------------------------ decoding

    private val W = SPB.roundToInt()
    private val cosM = DoubleArray(RATE) { cos(2 * PI * MARK * it / RATE) }
    private val sinM = DoubleArray(RATE) { sin(2 * PI * MARK * it / RATE) }
    private val cosS = DoubleArray(RATE) { cos(2 * PI * SPACE * it / RATE) }
    private val sinS = DoubleArray(RATE) { sin(2 * PI * SPACE * it / RATE) }

    /** >0 mark, <0 space; non-coherent correlation over a 1-bit window, centred. */
    private fun discriminator(x: FloatArray): FloatArray {
        val n = x.size
        val d = FloatArray(n)
        val mr = DoubleArray(n); val mi = DoubleArray(n); val sr = DoubleArray(n); val si = DoubleArray(n)
        for (i in 0 until n) {
            val k = i % RATE
            mr[i] = x[i] * cosM[k]; mi[i] = -x[i] * sinM[k]; sr[i] = x[i] * cosS[k]; si[i] = -x[i] * sinS[k]
        }
        var amr = 0.0; var ami = 0.0; var asr = 0.0; var asi = 0.0
        val half = W / 2
        for (i in 0 until n) {
            amr += mr[i]; ami += mi[i]; asr += sr[i]; asi += si[i]
            if (i >= W) { amr -= mr[i - W]; ami -= mi[i - W]; asr -= sr[i - W]; asi -= si[i - W] }
            val j = i - half
            if (j >= 0) d[j] = (hypot(amr, ami) - hypot(asr, asi)).toFloat()
        }
        return d
    }

    private fun uartBytes(d: FloatArray, start: Double, count: Int): ByteArray? {
        val out = ByteArray(count)
        var pos = start
        for (b in 0 until count) {
            fun bit(k: Int): Int { val i = (pos + (k + 0.5) * SPB).toInt(); return if (i < d.size && d[i] > 0) 1 else 0 }
            if (bit(0) != 0 || bit(9) != 1) return null
            var v = 0
            for (i in 0 until 8) v = v or (bit(1 + i) shl i)
            out[b] = v.toByte()
            pos += 10 * SPB
            val lo = maxOf((pos - SPB / 2).toInt(), 1); val hi = minOf((pos + SPB / 2).toInt(), d.size)
            for (j in lo until hi) if (d[j - 1] > 0 && d[j] <= 0) { pos = j.toDouble(); break }
        }
        return out
    }

    class Hit(val type: Int, val text: String, val start: Int)

    fun demodulate(x: FloatArray): List<Hit> {
        if (x.size < 100) return emptyList()
        val d = discriminator(x)
        val found = ArrayList<Hit>()
        var i = 1
        while (i < d.size) {
            if (!(d[i - 1] > 0 && d[i] <= 0)) { i++; continue }
            val hdr = uartBytes(d, i.toDouble(), 2)
            if (hdr == null || !hdr.contentEquals(SYNC)) { i++; continue }
            val pos = i + 20 * SPB
            val lenb = uartBytes(d, pos, 2)
            val len = lenb?.get(1)?.toInt()?.and(0xff) ?: 0
            if (lenb == null || (lenb[0].toInt() and 0xff) shr 4 != VERSION || len !in 1..MAX_LEN) { i++; continue }
            val rest = uartBytes(d, pos, 2 + len + 2)
            if (rest == null) { i++; continue }
            val body = rest.copyOfRange(0, rest.size - 2)
            val crc = ((rest[rest.size - 2].toInt() and 0xff) shl 8) or (rest[rest.size - 1].toInt() and 0xff)
            if (crc16(body) == crc) {
                found.add(Hit(body[0].toInt() and 0x0f, String(body, 2, len, Charsets.US_ASCII), i))
                i = (pos + rest.size * 10 * SPB).toInt()
                continue
            }
            i++
        }
        return found
    }

    /**
     * Streaming decoder for the received audio: keeps the last 500 ms and scans it every
     * 100 ms, reporting each burst once.
     */
    class Decoder(private val onCallsign: (String) -> Unit) {
        private val buf = FloatArray(4000)
        private var filled = 0
        private var total = 0L            // samples seen so far
        private var sinceScan = 0
        private val recent = ArrayDeque<Long>()

        fun feed(pcm: ShortArray) {
            val n = pcm.size
            if (n >= buf.size) return
            System.arraycopy(buf, n, buf, 0, buf.size - n)
            for (i in 0 until n) buf[buf.size - n + i] = pcm[i] / 32768f
            filled = minOf(buf.size, filled + n)
            total += n
            sinceScan += n
            if (sinceScan < 800 || filled < 1600) return
            sinceScan = 0
            val win = if (filled == buf.size) buf.copyOf() else buf.copyOfRange(buf.size - filled, buf.size)
            val base = total - win.size
            for (h in demodulate(win)) {
                val abs = base + h.start
                if (recent.any { kotlin.math.abs(it - abs) < 200 }) continue
                recent.addLast(abs); while (recent.size > 8) recent.removeFirst()
                if (h.type == TYPE_CALLSIGN) onCallsign(h.text)
            }
        }

        fun reset() { filled = 0; sinceScan = 0 }
    }
}
