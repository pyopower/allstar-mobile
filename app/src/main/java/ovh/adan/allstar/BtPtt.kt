package ovh.adan.allstar

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent

/**
 * PTT of Bluetooth speaker-mics like the Abbree (KST_vHMIC010), ported from ADN PoC: the button is
 * neither a key nor an intent, it arrives over AVRCP as FAST FORWARD when pressed (a burst: tap +
 * ~0.5 s held) and REWIND when released. A MediaSession of our own picks them up (PTT Bridge's trick):
 *  - Android hands media keys to the app that most recently STARTED playing, so a looped silence is
 *    kept and "restarted" (pause+play) every few seconds.
 *  - Mic: if enabled, transmitting opens the call channel (SCO) to the headset so the recording comes
 *    from the BT mic; it is closed when idle (receive stays on A2DP).
 * Opt-in. With PTT Bridge running it yields the button to it (PTT Bridge then sends PTT intents).
 */
class BtPtt(
    private val ctx: Context,
    private val useMic: () -> Boolean,
    private val onDown: () -> Unit,
    private val onUp: () -> Unit,
    private val log: (String) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var session: MediaSession? = null
    private var silence: AudioTrack? = null
    @Volatile var txing = false            // lo marca DmrService: reclamar más a menudo durante TX
    private var routed = false              // canal SCO abierto por nosotros
    @Volatile private var yieldUntil = 0L   // PTT Bridge vivo: no reclamar las teclas hasta esta hora

    /** PTT Bridge está corriendo (latido cada 3 s): le dejamos el botón y nos manda el PTT por intent. */
    fun yieldTo(bridge: Boolean) {
        val was = System.currentTimeMillis() < yieldUntil
        if (bridge) {
            yieldUntil = System.currentTimeMillis() + YIELD_MS
            if (!was) {
                try { silence?.pause() } catch (_: Exception) {}
                log("micro BT: PTT Bridge activo, él lleva el botón")
            }
        } else {
            yieldUntil = 0L
            if (was) { log("micro BT: PTT Bridge cerrado, la app recupera el botón"); main.post { reclaim() } }
        }
    }

    fun start() {
        if (session != null) return
        val s = MediaSession(ctx, "asl-btptt")
        s.setCallback(object : MediaSession.Callback() {
            override fun onMediaButtonEvent(i: Intent): Boolean {
                @Suppress("DEPRECATION")
                val e = i.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                    ?: return super.onMediaButtonEvent(i)
                return onKey(e) || super.onMediaButtonEvent(i)
            }
        })
        @Suppress("DEPRECATION")
        s.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS)
        s.setPlaybackState(PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_FAST_FORWARD or PlaybackState.ACTION_REWIND)
            .setState(PlaybackState.STATE_PLAYING, 0, 0f)
            .build())
        s.isActive = true
        session = s
        reclaim()
        main.postDelayed(reclaimer, RECLAIM_MS)
        log("micro BT: PTT activo (FF=pulsar, REWIND=soltar)")
    }

    fun stop() {
        main.removeCallbacksAndMessages(null)
        try { session?.release() } catch (_: Exception) {}
        session = null
        try { silence?.stop(); silence?.release() } catch (_: Exception) {}
        silence = null
        routeIdle()
    }

    private fun onKey(e: KeyEvent): Boolean {
        val down = when (e.keyCode) {
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> true
            KeyEvent.KEYCODE_MEDIA_REWIND -> false
            else -> return false
        }
        // la ráfaga FF trae dos DOWN + autorepetición: solo cuenta el primer DOWN; los UP no significan nada
        if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) main.post {
            log(if (down) "micro BT: pulsado (FF)" else "micro BT: soltado (REWIND)")
            if (down) onDown() else onUp()
        }
        return true
    }

    // ---- micro BT (SCO) solo durante TX ----
    private fun sco(): AudioDeviceInfo? =
        if (Build.VERSION.SDK_INT >= 31)
            am.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
        else null

    /** Abre el canal de llamada hacia el micro BT. true = hay que esperar a que suba (≤1,5 s). */
    fun routeTx(): Boolean {
        if (!useMic()) return false
        if (Build.VERSION.SDK_INT < 31) return false
        val d = sco() ?: return false
        if (am.communicationDevice?.id == d.id) { routed = true; return false }   // ya abierto
        return try { am.setCommunicationDevice(d).also { routed = it } } catch (_: Exception) { false }
    }

    fun routeIdle() {
        if (!routed) return
        routed = false
        if (Build.VERSION.SDK_INT >= 31) try { am.clearCommunicationDevice() } catch (_: Exception) {}
    }

    // ---- conservar las teclas multimedia ----
    private val reclaimer = object : Runnable {
        override fun run() {
            reclaim()
            main.postDelayed(this, if (txing) RECLAIM_TX_MS else RECLAIM_MS)
        }
    }

    /** Justo tras empezar a transmitir otra app/audio podría quedarse las teclas: reafirmar pronto. */
    fun reclaimSoon() {
        for (t in longArrayOf(150, 400)) main.postDelayed({ reclaim() }, t)
    }

    private fun reclaim() {
        if (System.currentTimeMillis() < yieldUntil) return
        try {
            val at = silence ?: run {
                val n = 8000 / 5                     // 200 ms en bucle
                (if (Build.VERSION.SDK_INT >= 23) AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(AudioFormat.Builder()
                        .setSampleRate(8000).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(n * 2)
                    .build()
                else @Suppress("DEPRECATION") AudioTrack(android.media.AudioManager.STREAM_MUSIC, 8000,   // Android 5.x
                    AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT, n * 2, AudioTrack.MODE_STATIC)).also {
                        it.write(ShortArray(n), 0, n)
                        it.setLoopPoints(0, n, -1)
                        silence = it
                    }
            }
            if (at.playState == AudioTrack.PLAYSTATE_PLAYING) at.pause()
            at.play()
        } catch (_: Exception) {
            try { silence?.release() } catch (_: Exception) {}
            silence = null
        }
    }

    companion object {
        const val RECLAIM_MS = 3000L
        const val RECLAIM_TX_MS = 700L
        const val YIELD_MS = 8000L          // sin latido de PTT Bridge en 8 s = cerrado (o colgado)
    }
}
