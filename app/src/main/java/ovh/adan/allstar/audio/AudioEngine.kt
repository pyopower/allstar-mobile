package ovh.adan.allstar.audio

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Process
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * 8 kHz mono audio for an IAX ulaw link: a small adaptive jitter buffer feeding an
 * AudioTrack, and an AudioRecord capture loop that hands out 20 ms ulaw frames.
 */
class AudioEngine(private val callbacks: Callbacks) {

    interface Callbacks {
        /** 20 ms of encoded mic audio (160 bytes ulaw). */
        fun onMicFrame(ulaw: ByteArray)
        /** Levels in dBFS (-90..0), roughly every 20 ms. */
        fun onRxLevel(db: Float)
        fun onTxLevel(db: Float)
        /** A Ham CallerID burst was decoded in the received audio. */
        fun onCallsign(callsign: String) {}
    }

    @Volatile var micGain = 1.0f
    @Volatile var speakerGain = 1.0f
    /** Receive keeps running (meter, Ham CallerID decoding) but nothing is played. */
    @Volatile var muted = false

    private val rxQueue = ArrayBlockingQueue<ShortArray>(MAX_QUEUE)
    @Volatile private var running = false
    @Volatile private var capturing = false
    private var playThread: Thread? = null
    private var micThread: Thread? = null

    fun start() {
        if (running) return
        running = true
        playThread = Thread({ playLoop() }, "audio-play").apply { start() }
    }

    fun stop() {
        running = false
        capturing = false
        playThread?.interrupt(); micThread?.interrupt()
        playThread = null; micThread = null
        rxQueue.clear()
    }

    /** Called from the network thread with incoming ulaw (normally 160 bytes). */
    fun onNetworkVoice(ulaw: ByteArray, ts16: Int = -1) {
        if (!running) return
        val pcm = Ulaw.decode(ulaw)
        runCatching {
            // lost frames: feed silence in their place so the callsign decoder keeps its timing
            if (ts16 >= 0 && lastTs16 >= 0) {
                val gap = ((ts16 - lastTs16) and 0xffff) / FRAME_MS - 1
                if (gap in 1..5) repeat(gap) { cidDecoder.feed(ShortArray(pcm.size)) }
            }
            if (ts16 >= 0) lastTs16 = ts16
            cidDecoder.feed(pcm)
        }
        if (!rxQueue.offer(pcm)) {
            rxQueue.poll()          // too much latency: drop the oldest frame
            rxQueue.offer(pcm)
        }
    }

    private val cidDecoder = HamCallerId.Decoder { callbacks.onCallsign(it) }
    private var lastTs16 = -1

    /** Burst samples that replace the microphone audio of the next frames (Ham CallerID). */
    @Volatile private var inject: ShortArray? = null
    private var injectPos = 0
    @Volatile private var afterInject: (() -> Unit)? = null

    /**
     * Microphone frames held back while the start burst goes out: the voice is not lost, it
     * follows the burst (the whole transmission is delayed by the burst length, ~150 ms).
     */
    private val delayed = java.util.ArrayDeque<ShortArray>()
    /** End burst waiting for the delayed voice to drain. */
    @Volatile private var endBurst: ShortArray? = null
    private var sendingEnd = false

    /** Send [burst] first; the microphone audio captured meanwhile follows it. */
    fun injectBurst(burst: ShortArray) { injectPos = 0; inject = burst }

    /**
     * End of transmission: send the voice still held back, then [burst], stop capturing and
     * run [done] (the unkey). Without a capture running, [done] runs at once.
     */
    fun finishWithBurst(burst: ShortArray, done: () -> Unit) {
        if (!capturing) { done(); return }
        afterInject = done
        endBurst = burst
    }

    fun startCapture() {
        if (capturing || !running) return
        capturing = true
        micThread = Thread({ micLoop() }, "audio-mic").apply { start() }
    }

    fun stopCapture() {
        capturing = false
        micThread = null
    }

    private fun playLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val minBuf = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder().setSampleRate(RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
            )
            .setBufferSizeInBytes(maxOf(minBuf, FRAME * 2 * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        val silence = ShortArray(FRAME)
        val conceal = ShortArray(FRAME)
        var last: ShortArray? = null
        var buffering = true
        var quietFrames = 0
        var concealed = 0
        // Adaptive playout depth: audio relayed from bridged networks (DVSwitch/USRP) arrives
        // very bursty, so grow the buffer on every underrun and shrink it slowly when stable.
        var target = PREFILL
        var stableFrames = 0
        try {
            track.play()
            while (running) {
                if (buffering) {
                    if (rxQueue.size < target) {
                        // keep the track fed with silence while (re)filling the buffer
                        track.write(silence, 0, FRAME)
                        if (++quietFrames % 5 == 0) {
                            callbacks.onRxLevel(-90f)
                            // the transmission ended: scan the tail (an end-of-TX callsign burst)
                            runCatching { cidDecoder.flush() }
                        }
                        continue
                    }
                    buffering = false
                }
                val f = rxQueue.poll(25, TimeUnit.MILLISECONDS)
                if (f == null) {
                    val l = last
                    if (l != null && concealed < 3) {
                        // short gap: repeat the last frame, fading, instead of a hard cut
                        concealed++
                        val k = 0.6f / concealed
                        for (i in l.indices) conceal[i] = clip(l[i] * k)
                        track.write(conceal, 0, FRAME)
                        continue
                    }
                    if (l != null) target = minOf(target + 2, MAX_TARGET)   // real underrun mid-stream
                    last = null; concealed = 0; stableFrames = 0
                    buffering = true
                    continue
                }
                if (concealed > 0) { target = minOf(target + 1, MAX_TARGET); concealed = 0 }
                // too much queued (a burst arrived): drop a frame to keep latency bounded
                if (rxQueue.size > target + 6) rxQueue.poll()
                if (++stableFrames > 1500 && target > PREFILL) { target--; stableFrames = 0 }   // 30 s ok
                quietFrames = 0
                val g = speakerGain
                if (g != 1.0f) for (i in f.indices) f[i] = clip(f[i] * g)
                callbacks.onRxLevel(levelDb(f))
                track.write(if (muted) silence else f, 0, f.size)
                last = f
            }
        } catch (_: InterruptedException) {
        } finally {
            runCatching { track.stop() }
            track.release()
        }
    }

    @SuppressLint("MissingPermission")
    private fun micLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        // Prefer native 8 kHz; fall back to 16 kHz with 2:1 decimation.
        var rate = RATE
        var minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) {
            rate = 16000
            minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        }
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, rate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, rate / 5 * 2))
        } catch (e: Exception) { capturing = false; return }
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); capturing = false; return }
        val step = rate / RATE
        val raw = ShortArray(FRAME * step)
        val pcm = ShortArray(FRAME)
        val out = ByteArray(FRAME)
        try {
            rec.startRecording()
            while (capturing && running) {
                var got = 0
                while (got < raw.size && capturing) {
                    val n = rec.read(raw, got, raw.size - got)
                    if (n <= 0) break
                    got += n
                }
                if (got < raw.size) continue
                val g = micGain
                for (i in 0 until FRAME) {
                    val s = if (step == 2) (raw[2 * i] + raw[2 * i + 1]) / 2 else raw[i].toInt()
                    pcm[i] = clip(s * g)
                }
                val ending = endBurst != null || sendingEnd
                // after PTT release the microphone is no longer part of the transmission
                if (!ending) delayed.addLast(pcm.copyOf())
                if (endBurst != null && inject == null && delayed.isEmpty()) {
                    injectPos = 0; inject = endBurst; endBurst = null; sendingEnd = true
                }
                val inj = inject
                val frame: ShortArray = if (inj != null) {
                    ShortArray(FRAME) { i -> if (injectPos + i < inj.size) inj[injectPos + i] else 0 }.also {
                        injectPos += FRAME
                        if (injectPos >= inj.size) inject = null
                    }
                } else delayed.pollFirst() ?: ShortArray(FRAME)
                // meter = the microphone now, not the frame going out (with Ham CallerID the voice is
                // sent ~0.5 s late, behind the start burst, and the meter lagged the voice that much)
                callbacks.onTxLevel(levelDb(if (ending) frame else pcm))
                callbacks.onMicFrame(Ulaw.encode(frame, out).copyOf())
                if (sendingEnd && inject == null) {
                    sendingEnd = false
                    afterInject?.let { afterInject = null; capturing = false; it() }
                }
            }
        } finally {
            delayed.clear(); endBurst = null; sendingEnd = false
            afterInject?.let { afterInject = null; inject = null; it() }
            runCatching { rec.stop() }
            rec.release()
            callbacks.onTxLevel(-90f)
        }
    }

    companion object {
        const val RATE = 8000
        const val FRAME = 160          // 20 ms
        private const val FRAME_MS = 20
        private const val PREFILL = 4      // 80 ms initial playout depth
        private const val MAX_TARGET = 14  // 280 ms
        private const val MAX_QUEUE = 30

        private fun clip(v: Float): Short = v.coerceIn(-32768f, 32767f).toInt().toShort()
        private fun clip(v: Int): Short = v.coerceIn(-32768, 32767).toShort()

        fun levelDb(f: ShortArray): Float {
            var sum = 0.0
            for (s in f) sum += s.toDouble() * s
            val rms = sqrt(sum / f.size) / 32768.0
            return if (rms < 1e-5) -90f else (20 * log10(rms)).toFloat().coerceAtLeast(-90f)
        }
    }
}
