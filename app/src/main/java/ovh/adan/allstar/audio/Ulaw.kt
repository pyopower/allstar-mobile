package ovh.adan.allstar.audio

/** G.711 mu-law codec (8 kHz, 8-bit), as used by AllStarLink IAX links. */
object Ulaw {
    private const val BIAS = 0x84
    private const val CLIP = 32635

    private val decodeTable = ShortArray(256) { i ->
        val u = i.inv() and 0xff
        val sign = u and 0x80
        val exponent = (u shr 4) and 0x07
        val mantissa = u and 0x0f
        var sample = ((mantissa shl 3) + BIAS) shl exponent
        sample -= BIAS
        (if (sign != 0) -sample else sample).toShort()
    }

    private val expLut = IntArray(256) { i ->
        var e = 0
        var v = i
        while (v > 1) { v = v shr 1; e++ }
        e
    }

    fun encode(sample: Int): Byte {
        var s = sample
        val sign = if (s < 0) { s = -s; 0x80 } else 0
        if (s > CLIP) s = CLIP
        s += BIAS
        val exponent = expLut[(s shr 7) and 0xff]
        val mantissa = (s shr (exponent + 3)) and 0x0f
        return (sign or (exponent shl 4) or mantissa).inv().toByte()
    }

    fun decode(b: Byte): Short = decodeTable[b.toInt() and 0xff]

    fun encode(pcm: ShortArray, out: ByteArray = ByteArray(pcm.size)): ByteArray {
        for (i in pcm.indices) out[i] = encode(pcm[i].toInt())
        return out
    }

    fun decode(ulaw: ByteArray, out: ShortArray = ShortArray(ulaw.size)): ShortArray {
        for (i in ulaw.indices) out[i] = decodeTable[ulaw[i].toInt() and 0xff]
        return out
    }
}
