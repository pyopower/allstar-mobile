package ovh.adan.allstar.audio

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class HamCallerIdTest {
    private fun f(s: ShortArray) = FloatArray(s.size) { s[it] / 32768f }

    @Test fun roundTrip() {
        val b = HamCallerId.modulate("c31ag")!!
        val x = FloatArray(400) + f(b) + FloatArray(400)
        assertEquals(listOf("C31AG"), HamCallerId.demodulate(x).map { it.text })
    }

    @Test fun ulawRoundTrip() {
        val b = HamCallerId.modulate("EA3IGD/P", 0.1f)!!
        val x = f(Ulaw.decode(Ulaw.encode(ShortArray(300) + b + ShortArray(300))))
        assertEquals(listOf("EA3IGD/P"), HamCallerId.demodulate(x).map { it.text })
    }

    @Test fun streamingDecoderReportsOnce() {
        val got = ArrayList<String>()
        val d = HamCallerId.Decoder { got.add(it) }
        val all = ShortArray(1600) + HamCallerId.modulate("HP3ICC")!! + ShortArray(4000)
        for (i in 0 until all.size / 160) d.feed(all.copyOfRange(i * 160, i * 160 + 160))
        assertEquals(listOf("HP3ICC"), got)
    }

    /** Interop with the Python reference: writes a raw file for hamcid/burst.py and reads one from it. */
    @Test fun interop() {
        val dir = System.getenv("HCID_INTEROP") ?: return
        val out = HamCallerId.modulate("C31AG/M")!!
        File(dir, "kotlin.s16").writeBytes(ByteArray(out.size * 2) { i -> (out[i / 2].toInt() shr (8 * (i % 2))).toByte() })
        val inp = File(dir, "python.s16").readBytes()
        val s = FloatArray(inp.size / 2) { i -> (((inp[2 * i + 1].toInt()) shl 8) or (inp[2 * i].toInt() and 0xff)).toShort() / 32768f }
        assertEquals(listOf("EA5GVK"), HamCallerId.demodulate(s).map { it.text })
    }
}
