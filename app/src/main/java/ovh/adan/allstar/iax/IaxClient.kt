package ovh.adan.allstar.iax

import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.security.MessageDigest
import kotlin.random.Random

/**
 * Minimal IAX2 client (RFC 5456) speaking to an AllStarLink app_rpt node the way
 * iaxRpt does ("radio-less" login into the node's iaxrpt-style dialplan context).
 *
 * Supports: call tokens, MD5/plaintext auth, ulaw voice (full + mini frames),
 * retransmission of reliable frames, PING/LAGRQ, app_rpt NEWKEY keying
 * (RADIO_KEY/UNKEY control frames), DTMF and TEXT frames.
 */
class IaxClient(
    private val host: String,
    private val port: Int,
    private val username: String,
    private val secret: String,
    private val node: String,
    private val callerId: String,
    private val listener: Listener,
) {
    enum class State { IDLE, CONNECTING, AUTHENTICATING, CONNECTED, CLOSED }

    interface Listener {
        fun onState(state: State, reason: String? = null)
        fun onVoice(ulaw: ByteArray)
        fun onRemoteKey(keyed: Boolean)
        fun onText(text: String)
    }

    @Volatile var state = State.IDLE; private set

    private var socket: DatagramSocket? = null
    private var thread: Thread? = null
    private val lock = Object()

    private val srcCall = Random.nextInt(1, 0x7fff)
    private var dstCall = 0
    private var oseq = 0
    private var iseq = 0
    private val startNs = System.nanoTime()
    private var lastRxMs = 0L
    /** The server refused the call (bad credentials, unknown user): not worth retrying. */
    @Volatile var rejected = false; private set
    private var lastPingMs = 0L
    private var lastVoiceTs = -1L
    private var lastFullVoiceHigh = -1L
    private var needFullVoice = true
    private var authSent = false

    private class Pending(val seq: Int, val data: ByteArray, var sentAt: Long, var tries: Int = 1)
    private val pending = ArrayList<Pending>()

    fun start() {
        if (thread != null) return
        thread = Thread({ runLoop() }, "iax-client").apply { isDaemon = true; start() }
    }

    fun hangup(reason: String = "Bye") {
        synchronized(lock) {
            if (state == State.CONNECTED || state == State.AUTHENTICATING || state == State.CONNECTING) {
                runCatching { sendFull(FT_IAX, IAX_HANGUP, ie(IE_CAUSE, reason)) }
            }
        }
        close(null)
    }

    /** Key the node (start of a transmission). */
    fun key() = synchronized(lock) {
        if (state != State.CONNECTED) return@synchronized
        needFullVoice = true
        sendFull(FT_CONTROL, CTRL_RADIO_KEY)
    }

    /**
     * Repeat RADIO_KEY while transmitting. After "!NEWKEY!" app_rpt uses redundant keying:
     * it re-sends key state every 2 s and unkeys a link that has sent no RADIO_KEY for 10 s,
     * whatever the audio (app_rpt.c, REDUNDANT_TX_TIME * 5).
     */
    fun rekey() = synchronized(lock) {
        if (state != State.CONNECTED) return@synchronized
        sendFull(FT_CONTROL, CTRL_RADIO_KEY)
    }

    fun unkey() = synchronized(lock) {
        if (state != State.CONNECTED) return@synchronized
        sendFull(FT_CONTROL, CTRL_RADIO_UNKEY)
    }

    /** Send one 20 ms frame of ulaw audio (160 bytes). */
    fun sendVoice(ulaw: ByteArray) = synchronized(lock) {
        if (state != State.CONNECTED) return@synchronized
        // Voice timestamps follow the audio clock (+20 ms per frame), not the time the frame was
        // handed over: AudioRecord delivers frames in bursts, and the node's jitter buffer drops
        // frames whose timestamps collide or bunch up. Resync to the wall clock after a pause
        // or when the two clocks drift apart by more than 200 ms.
        val now = nowMs()
        val next = lastVoiceTs + FRAME_MS
        val ts = if (lastVoiceTs < 0 || kotlin.math.abs(now - next) > 200) maxOf(now, lastVoiceTs + FRAME_MS) else next
        lastVoiceTs = ts
        val high = ts and 0xffff0000L
        if (needFullVoice || high != lastFullVoiceHigh) {
            needFullVoice = false
            lastFullVoiceHigh = high
            sendFull(FT_VOICE, FMT_ULAW, ulaw, ts)
        } else {
            val b = ByteArray(4 + ulaw.size)
            b[0] = (srcCall shr 8).toByte(); b[1] = srcCall.toByte()
            b[2] = (ts shr 8).toByte(); b[3] = ts.toByte()
            System.arraycopy(ulaw, 0, b, 4, ulaw.size)
            raw(b)
        }
    }

    /** Send DTMF digits (app_rpt treats them as link commands, e.g. *3nnnn). */
    fun sendDtmf(digits: String) {
        Thread(Runnable {
            for (c in digits) {
                synchronized(lock) {
                    if (state != State.CONNECTED) return@Runnable
                    sendFull(FT_DTMF_BEGIN, c.code)
                }
                Thread.sleep(90)
                synchronized(lock) { sendFull(FT_DTMF, c.code) }
                Thread.sleep(110)
            }
        }, "iax-dtmf").start()
    }

    fun sendText(text: String) = synchronized(lock) {
        if (state != State.CONNECTED) return@synchronized
        sendFull(FT_TEXT, 0, text.toByteArray() + 0)
    }

    // ---------------------------------------------------------------- internals

    private fun nowMs() = (System.nanoTime() - startNs) / 1_000_000L and 0xffffffffL

    private fun setState(s: State, reason: String? = null) {
        if (state == s) return
        state = s
        listener.onState(s, reason)
    }

    private fun close(reason: String?) {
        val s = socket
        socket = null
        if (state != State.CLOSED) setState(State.CLOSED, reason)
        runCatching { s?.close() }
    }

    private fun runLoop() {
        try {
            val addr = InetAddress.getByName(host)
            val s = DatagramSocket()
            s.connect(InetSocketAddress(addr, port))
            s.soTimeout = 200
            socket = s
            setState(State.CONNECTING)
            lastRxMs = nowMs()
            synchronized(lock) { sendNew(ByteArray(0)) }
            val buf = ByteArray(2048)
            val pkt = DatagramPacket(buf, buf.size)
            while (state != State.CLOSED) {
                try {
                    s.receive(pkt)
                    lastRxMs = nowMs()
                    handle(buf.copyOf(pkt.length))
                } catch (_: SocketTimeoutException) {
                }
                timers()
            }
        } catch (e: Exception) {
            if (state != State.CLOSED) close(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun timers() = synchronized(lock) {
        val now = nowMs()
        val timeout = if (state == State.CONNECTED) 35_000 else 10_000
        if (now - lastRxMs > timeout) {
            close(if (state == State.CONNECTED) "Connection lost" else "No answer from server")
            return@synchronized
        }
        val it = pending.iterator()
        while (it.hasNext()) {
            val p = it.next()
            if (now - p.sentAt > 1000) {
                if (p.tries >= 6) { it.remove(); continue }
                p.tries++
                p.sentAt = now
                // retransmission: set the R bit in the destination call number
                p.data[2] = (p.data[2].toInt() or 0x80).toByte()
                raw(p.data)
            }
        }
        if (state == State.CONNECTED && now - lastPingMs > 20_000) {
            lastPingMs = now
            sendFull(FT_IAX, IAX_PING)
        }
    }

    private fun handle(b: ByteArray) {
        if (b.size < 4) return
        if (b[0].toInt() and 0x80 == 0) {
            // mini voice frame
            if (b.size > 4) listener.onVoice(b.copyOfRange(4, b.size))
            return
        }
        if (b.size < 12) return
        val src = ((b[0].toInt() and 0x7f) shl 8) or (b[1].toInt() and 0xff)
        val ts = ((b[4].toLong() and 0xff) shl 24) or ((b[5].toLong() and 0xff) shl 16) or
            ((b[6].toLong() and 0xff) shl 8) or (b[7].toLong() and 0xff)
        val rOseq = b[8].toInt() and 0xff
        val rIseq = b[9].toInt() and 0xff
        val type = b[10].toInt() and 0xff
        var sub = b[11].toInt() and 0xff
        if (sub and 0x80 != 0) sub = 1 shl (sub and 0x7f)
        val body = b.copyOfRange(12, b.size)

        synchronized(lock) {
            // implicit acknowledgement of everything before rIseq
            pending.removeAll { ((rIseq - it.seq) and 0xff) in 1..128 }

            if (type == FT_IAX && sub == IAX_ACK) return
            if (type == FT_IAX && sub == IAX_CALLTOKEN) {
                // restart the handshake with the token the server gave us
                oseq = 0; iseq = 0; pending.clear()
                sendNew(parseIes(body)[IE_CALLTOKEN] ?: ByteArray(0))
                return
            }
            if (type == FT_IAX && (sub == IAX_INVAL || sub == IAX_VNAK)) return

            if (rOseq != iseq) {
                // duplicate (already seen) -> re-ack; future frame -> ignore, it will be resent
                if (((iseq - rOseq) and 0xff) in 1..128) ack(ts)
                return
            }
            iseq = (iseq + 1) and 0xff
            if (dstCall == 0 && src != 0) dstCall = src

            when (type) {
                FT_IAX -> handleIax(sub, ts, parseIes(body))
                FT_CONTROL -> {
                    ack(ts)
                    when (sub) {
                        CTRL_ANSWER -> {
                            setState(State.CONNECTED)
                            lastPingMs = nowMs()
                            // ask app_rpt for RADIO_KEY/UNKEY keying in both directions
                            sendFull(FT_TEXT, 0, NEWKEY.toByteArray() + 0)
                        }
                        CTRL_RADIO_KEY -> listener.onRemoteKey(true)
                        CTRL_RADIO_UNKEY -> listener.onRemoteKey(false)
                        CTRL_HANGUP, CTRL_BUSY, CTRL_CONGESTION -> {
                            close(if (sub == CTRL_BUSY) "Busy" else "Call ended by node")
                        }
                    }
                }
                FT_VOICE -> { ack(ts); listener.onVoice(body) }
                FT_TEXT -> {
                    ack(ts)
                    val t = String(body).trimEnd('\u0000')
                    if (t.startsWith("!NEWKEY")) return
                    listener.onText(t)
                }
                else -> ack(ts)
            }
        }
    }

    private fun handleIax(sub: Int, ts: Long, ies: Map<Int, ByteArray>) {
        when (sub) {
            IAX_AUTHREQ -> {
                ack(ts)
                if (authSent) return
                authSent = true
                setState(State.AUTHENTICATING)
                val methods = ies[IE_AUTHMETHODS]?.let { toInt(it) } ?: 0
                val challenge = ies[IE_CHALLENGE] ?: ByteArray(0)
                if (methods and AUTH_MD5 != 0) {
                    val md = MessageDigest.getInstance("MD5")
                    md.update(challenge); md.update(secret.toByteArray())
                    val hex = md.digest().joinToString("") { "%02x".format(it) }
                    sendFull(FT_IAX, IAX_AUTHREP, ie(IE_MD5_RESULT, hex))
                } else if (methods and AUTH_PLAINTEXT != 0) {
                    sendFull(FT_IAX, IAX_AUTHREP, ie(IE_PASSWORD, secret))
                } else {
                    close("Unsupported authentication method")
                }
            }
            IAX_ACCEPT -> ack(ts)
            IAX_PING -> sendFull(FT_IAX, IAX_PONG, ByteArray(0), ts)
            IAX_LAGRQ -> sendFull(FT_IAX, IAX_LAGRP, ByteArray(0), ts)
            IAX_PONG, IAX_LAGRP -> ack(ts)
            IAX_REJECT, IAX_HANGUP -> {
                ack(ts)
                val cause = ies[IE_CAUSE]?.let { String(it) }
                if (sub == IAX_REJECT) rejected = true
                close(cause ?: if (sub == IAX_REJECT) "Rejected" else "Hung up")
            }
            else -> ack(ts)
        }
    }

    private fun sendNew(token: ByteArray) {
        val out = ByteArrayOutputStream()
        out.write(ie(IE_VERSION, byteArrayOf(0, 2)))
        out.write(ie(IE_CALLED_NUMBER, node))
        out.write(ie(IE_CALLING_NUMBER, "0"))
        out.write(ie(IE_CALLING_NAME, callerId))
        out.write(ie(IE_USERNAME, username))
        out.write(ie(IE_CAPABILITY, int32(FMT_ULAW)))
        out.write(ie(IE_FORMAT, int32(FMT_ULAW)))
        out.write(ie(IE_CALLTOKEN, token))
        sendFull(FT_IAX, IAX_NEW, out.toByteArray())
    }

    private fun ack(ts: Long) = sendFull(FT_IAX, IAX_ACK, ByteArray(0), ts, reliable = false)

    private fun sendFull(type: Int, sub: Int, data: ByteArray = ByteArray(0), ts: Long = nowMs(), reliable: Boolean = true) {
        val b = ByteArray(12 + data.size)
        b[0] = (0x80 or (srcCall shr 8)).toByte(); b[1] = srcCall.toByte()
        b[2] = ((dstCall shr 8) and 0x7f).toByte(); b[3] = dstCall.toByte()
        b[4] = (ts shr 24).toByte(); b[5] = (ts shr 16).toByte(); b[6] = (ts shr 8).toByte(); b[7] = ts.toByte()
        b[8] = oseq.toByte(); b[9] = iseq.toByte()
        b[10] = type.toByte(); b[11] = sub.toByte()
        System.arraycopy(data, 0, b, 12, data.size)
        if (reliable) {
            pending.add(Pending(oseq, b, nowMs()))
            oseq = (oseq + 1) and 0xff
        }
        raw(b)
    }

    private fun raw(b: ByteArray) {
        runCatching { socket?.send(DatagramPacket(b, b.size)) }
    }

    companion object {
        const val NEWKEY = "!NEWKEY!"

        const val FT_DTMF = 1; const val FT_VOICE = 2; const val FT_CONTROL = 4
        const val FRAME_MS = 20L
        const val FT_IAX = 6; const val FT_TEXT = 7; const val FT_DTMF_BEGIN = 12

        const val IAX_NEW = 1; const val IAX_PING = 2; const val IAX_PONG = 3; const val IAX_ACK = 4
        const val IAX_HANGUP = 5; const val IAX_REJECT = 6; const val IAX_ACCEPT = 7
        const val IAX_AUTHREQ = 8; const val IAX_AUTHREP = 9; const val IAX_INVAL = 10
        const val IAX_LAGRQ = 11; const val IAX_LAGRP = 12; const val IAX_VNAK = 18
        const val IAX_CALLTOKEN = 40

        const val CTRL_HANGUP = 1; const val CTRL_ANSWER = 4; const val CTRL_BUSY = 5
        const val CTRL_CONGESTION = 8; const val CTRL_RADIO_KEY = 12; const val CTRL_RADIO_UNKEY = 13

        const val IE_CALLED_NUMBER = 1; const val IE_CALLING_NUMBER = 2; const val IE_CALLING_NAME = 4
        const val IE_USERNAME = 6; const val IE_PASSWORD = 7; const val IE_CAPABILITY = 8
        const val IE_FORMAT = 9; const val IE_VERSION = 11; const val IE_AUTHMETHODS = 14
        const val IE_CHALLENGE = 15; const val IE_MD5_RESULT = 16; const val IE_CAUSE = 22
        const val IE_CALLTOKEN = 54

        const val AUTH_PLAINTEXT = 1; const val AUTH_MD5 = 2
        const val FMT_ULAW = 4

        private fun int32(v: Int) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
        private fun ie(t: Int, v: String) = ie(t, v.toByteArray())
        private fun ie(t: Int, v: ByteArray): ByteArray {
            val n = minOf(v.size, 255)
            return byteArrayOf(t.toByte(), n.toByte()) + v.copyOf(n)
        }
        private fun toInt(b: ByteArray) = b.fold(0) { acc, x -> (acc shl 8) or (x.toInt() and 0xff) }
        private fun parseIes(b: ByteArray): Map<Int, ByteArray> {
            val m = HashMap<Int, ByteArray>()
            var i = 0
            while (i + 2 <= b.size) {
                val t = b[i].toInt() and 0xff
                val l = b[i + 1].toInt() and 0xff
                if (i + 2 + l > b.size) break
                m[t] = b.copyOfRange(i + 2, i + 2 + l)
                i += 2 + l
            }
            return m
        }
    }
}
