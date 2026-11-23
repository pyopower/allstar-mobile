package ovh.adan.allstar.audio

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Ham CallerID burst v1 (github.com/pyopower/ham-callerid, docs/SPEC.md): the callsign as a
 * short Bell 202 AFSK burst at the start and end of a transmission, so whoever receives the
 * audio (another app, a node, a bridge) can show who is talking even on analog links.
 *
 * Frame: mark lead 24 bits (20 ms) · preamble 0101… (16) · sync 0x1ACFFC1D (32) ·
 * k ≥ 8 Golay(24,12) words, interleaved (column by column). Data before coding:
 * version(3) | type(3) | length(6) | payload | CRC-16/CCITT-FALSE over header (2 bytes) + payload.
 * Synchronous NRZ, mark = 1200 Hz = 1, space = 2200 Hz = 0, 1200 baud, continuous phase, 8 kHz.
 * Must stay bit-identical with hamcid/burst.py.
 */
object HamCallerId {
    private const val RATE = 8000
    private const val SPB = RATE / 1200.0
    private const val MARK = 1200.0
    private const val SPACE = 2200.0
    private const val VERSION = 1
    const val TYPE_CALLSIGN = 1
    private const val MAX_LEN = 32
    private const val LEAD_BITS = 24
    private val PREAMBLE = IntArray(16) { it and 1 }
    private const val SYNC_WORD = 0x1ACFFC1DL
    private val SYNC = IntArray(32) { ((SYNC_WORD shr (31 - it)) and 1L).toInt() }
    private const val MIN_WORDS = 8
    private const val MAX_WORDS = 24

    // ------------------------------------------------------------------ Golay (24,12)

    private const val GOLAY_POLY = 0xC75

    fun golayEncode(data12: Int): Int {
        var r = data12 shl 11
        for (i in 22 downTo 11) if (r and (1 shl i) != 0) r = r xor (GOLAY_POLY shl (i - 11))
        val cw23 = (data12 shl 11) or r
        return (cw23 shl 1) or (Integer.bitCount(cw23) and 1)
    }

    private fun syndrome(w: Int) = (w and 0xFFF) xor (golayEncode(w ushr 12) and 0xFFF)

    /** syndrome → error pattern of weight ≤ 3 (-1 = uncorrectable). */
    private val golayTable: IntArray by lazy {
        val t = IntArray(4096) { -1 }
        t[0] = 0
        fun put(e: Int) { val s = syndrome(e); if (t[s] == -1) t[s] = e }
        for (a in 0 until 24) put(1 shl a)
        for (a in 0 until 24) for (b in a + 1 until 24) put((1 shl a) or (1 shl b))
        for (a in 0 until 24) for (b in a + 1 until 24) for (c in b + 1 until 24) put((1 shl a) or (1 shl b) or (1 shl c))
        t
    }

    /** 12 data bits, or -1 if not correctable. */
    fun golayDecode(w: Int): Int {
        val e = golayTable[syndrome(w)]
        return if (e < 0) -1 else (w xor e) ushr 12
    }

    // ------------------------------------------------------------------ frame

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

    private fun wordsFor(nBits: Int) = maxOf(MIN_WORDS, (nBits + 11) / 12)

    private fun blockBits(text: String, kind: Int): IntArray {
        val p = text.toByteArray(Charsets.US_ASCII)
        val hdr = (VERSION shl 9) or (kind shl 6) or p.size
        val crc = crc16(byteArrayOf((hdr shr 8).toByte(), hdr.toByte()) + p)
        val data = ArrayList<Int>()
        for (i in 0 until 12) data.add((hdr shr (11 - i)) and 1)
        for (b in p + byteArrayOf((crc shr 8).toByte(), crc.toByte())) for (i in 0 until 8) data.add(((b.toInt() and 0xff) shr (7 - i)) and 1)
        val k = wordsFor(data.size)
        while (data.size < 12 * k) data.add(0)
        val words = IntArray(k) { w -> var v = 0; for (i in 0 until 12) v = (v shl 1) or data[12 * w + i]; golayEncode(v) }
        return IntArray(24 * k) { idx -> val c = idx / k; val w = idx % k; (words[w] shr (23 - c)) and 1 }
    }

    private fun frameBits(text: String, kind: Int) =
        IntArray(LEAD_BITS) { 1 } + PREAMBLE + SYNC + blockBits(text, kind) + intArrayOf(1, 1)

    /** 16-bit PCM at 8 kHz, or null if the callsign is empty after cleaning. */
    fun modulate(callsign: String, amp: Float = 0.5f): ShortArray? {
        val cs = cleanCallsign(callsign)
        if (cs.isEmpty()) return null
        val bits = frameBits(cs, TYPE_CALLSIGN)
        val total = ceil(bits.size * SPB).toInt()
        val out = ShortArray(total)
        var phase = 0.0
        for (n in 0 until total) {
            val b = bits[minOf((n / SPB).toInt(), bits.size - 1)]
            phase += 2 * PI * (if (b == 1) MARK else SPACE) / RATE
            out[n] = (amp * 32767 * sin(phase)).roundToInt().toShort()
        }
        return out
    }

    // ------------------------------------------------------------------ demodulation

    private val W = SPB.roundToInt()
    private val cosM = DoubleArray(RATE) { cos(2 * PI * MARK * it / RATE) }
    private val sinM = DoubleArray(RATE) { sin(2 * PI * MARK * it / RATE) }
    private val cosS = DoubleArray(RATE) { cos(2 * PI * SPACE * it / RATE) }
    private val sinS = DoubleArray(RATE) { sin(2 * PI * SPACE * it / RATE) }

    /** >0 mark, <0 space; non-coherent correlation over a 1-bit window, centred. */
    private fun discriminator(x: FloatArray): FloatArray {
        val n = x.size
        val d = FloatArray(n)
        var a = 0.0; var b = 0.0; var c = 0.0; var e = 0.0
        val half = W / 2
        for (i in 0 until n) {
            val k = i % RATE
            a += x[i] * cosM[k]; b += x[i] * sinM[k]; c += x[i] * cosS[k]; e += x[i] * sinS[k]
            if (i >= W) {
                val u = x[i - W]; val q = (i - W) % RATE
                a -= u * cosM[q]; b -= u * sinM[q]; c -= u * cosS[q]; e -= u * sinS[q]
            }
            val j = i - half
            if (j >= 0) d[j] = (hypot(a, b) - hypot(c, e)).toFloat()
        }
        return d
    }

    private val REF = PREAMBLE.copyOfRange(8, 16) + SYNC

    private fun findSync(d: FloatArray, maxErr: Int = 3): List<Int> {
        val found = ArrayList<Int>()
        val span = (REF.size * SPB).toInt() + 2
        var o = 0
        while (o + span < d.size) {
            var err = 0
            for (k in REF.indices) {
                val bit = if (d[(o + (k + 0.5) * SPB).toInt()] > 0) 1 else 0
                if (bit != REF[k] && ++err > maxErr) break
            }
            if (err <= maxErr) {
                var bestF = o; var bestE = Int.MAX_VALUE; var bestM = -1.0
                for (f in o until o + 7) {
                    if (f + span >= d.size) break
                    var e2 = 0; var m = 0.0
                    for (k in REF.indices) {
                        val v = d[(f + (k + 0.5) * SPB).toInt()]
                        if ((v > 0) != (REF[k] == 1)) e2++
                        m += kotlin.math.abs(v)
                    }
                    if (e2 < bestE || (e2 == bestE && m > bestM)) { bestE = e2; bestM = m; bestF = f }
                }
                found.add(bestF)
                o = bestF + span
                continue
            }
            o++
        }
        return found
    }

    private fun readBits(d: FloatArray, start: Double, n: Int): IntArray {
        val out = ArrayList<Int>(n)
        var pos = start
        var prev = -1
        for (k in 0 until n) {
            val i = (pos + 0.5 * SPB).toInt()
            if (i >= d.size) break
            val v = if (d[i] > 0) 1 else 0
            if (prev >= 0 && v != prev) {
                val lo = maxOf((pos - SPB / 2).toInt(), 1); val hi = minOf((pos + SPB / 2).toInt(), d.size)
                for (j in lo until hi) if ((d[j - 1] > 0) != (d[j] > 0)) { pos += 0.25 * (j - pos); break }
            }
            out.add(v); prev = v
            pos += SPB
        }
        return out.toIntArray()
    }

    private fun decodeBlock(bits: IntArray): Pair<Int, String>? {
        for (k in MIN_WORDS..MAX_WORDS) {
            if (bits.size < 24 * k) break
            val words = IntArray(k)
            for (c in 0 until 24) for (w in 0 until k) words[w] = (words[w] shl 1) or bits[c * k + w]
            val data = IntArray(k)
            var ok = true
            for (w in 0 until k) { val r = golayDecode(words[w]); if (r < 0) { ok = false; break }; data[w] = r }
            if (!ok) continue
            val hdr = data[0]
            val ver = hdr ushr 9; val kind = (hdr ushr 6) and 7; val n = hdr and 0x3F
            if (ver != VERSION || n !in 1..MAX_LEN || wordsFor(12 + 8 * n + 16) != k) continue
            val flat = IntArray(12 * (k - 1)) { idx -> (data[1 + idx / 12] shr (11 - idx % 12)) and 1 }
            val by = ByteArray(n + 2) { i -> var v = 0; for (b in 0 until 8) v = (v shl 1) or flat[8 * i + b]; v.toByte() }
            val crc = ((by[n].toInt() and 0xff) shl 8) or (by[n + 1].toInt() and 0xff)
            if (crc16(byteArrayOf((hdr shr 8).toByte(), hdr.toByte()) + by.copyOfRange(0, n)) != crc) continue
            return kind to String(by, 0, n, Charsets.US_ASCII)
        }
        return null
    }

    class Hit(val type: Int, val text: String, val start: Int)

    fun demodulate(x: FloatArray): List<Hit> {
        if (x.size < 200) return emptyList()
        val d = discriminator(x)
        val out = ArrayList<Hit>()
        for (pos in findSync(d)) {
            val r = decodeBlock(readBits(d, pos + REF.size * SPB, 24 * MAX_WORDS)) ?: continue
            out.add(Hit(r.first, r.second, maxOf(0, (pos - (LEAD_BITS + 8) * SPB).toInt())))
        }
        return out
    }

    /**
     * Streaming decoder for the received audio: keeps the last 700 ms and scans it every
     * 100 ms, reporting each burst once.
     */
    class Decoder(private val onCallsign: (String) -> Unit) {
        private val buf = FloatArray(5600)
        private var filled = 0
        private var total = 0L
        private var sinceScan = 0
        private val recent = ArrayDeque<Long>()

        @Synchronized fun feed(pcm: ShortArray) {
            val n = pcm.size
            if (n >= buf.size) return
            System.arraycopy(buf, n, buf, 0, buf.size - n)
            for (i in 0 until n) buf[buf.size - n + i] = pcm[i] / 32768f
            filled = minOf(buf.size, filled + n)
            total += n
            sinceScan += n
            if (sinceScan < 800 || filled < 2000) return
            scan()
        }

        /** Scan what arrived since the last scan (call when the audio stops). */
        @Synchronized fun flush() {
            if (sinceScan > 0 && filled >= 1500) scan()
        }

        private fun scan() {
            sinceScan = 0
            val win = if (filled == buf.size) buf.copyOf() else buf.copyOfRange(buf.size - filled, buf.size)
            val base = total - win.size
            for (h in demodulate(win)) {
                val abs = base + h.start
                if (recent.any { kotlin.math.abs(it - abs) < 400 }) continue
                recent.addLast(abs); while (recent.size > 8) recent.removeFirst()
                if (h.type == TYPE_CALLSIGN) onCallsign(h.text)
            }
        }

        fun reset() { filled = 0; sinceScan = 0 }
    }
}
