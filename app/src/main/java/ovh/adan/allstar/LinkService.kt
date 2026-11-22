package ovh.adan.allstar

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Keeps the process (network + audio) alive while a node connection is up. */
class LinkService : Service() {
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    private val scope = CoroutineScope(Dispatchers.Main)
    private var watch: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT) {
            Radio.disconnect()
            return START_NOT_STICKY
        }
        createChannel()
        val micOk = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val types = if (Build.VERSION.SDK_INT >= 30) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or (if (micOk) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
        } else 0
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(Radio.state.value), types)
        acquireLocks()
        if (watch == null) watch = scope.launch {
            Radio.state.map { Triple(it.conn, it.rx, it.tx) }.distinctUntilChanged().collect {
                getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(Radio.state.value))
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        watch?.cancel()
        runCatching { wake?.release() }
        runCatching { wifi?.release() }
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        if (wake == null) wake = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AllStarMobile:link").apply { setReferenceCounted(false); acquire() }
        if (wifi == null) {
            val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifi = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                .createWifiLock(mode, "AllStarMobile:link").apply { setReferenceCounted(false); acquire() }
        }
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun buildNotification(s: RadioState): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val disc = PendingIntent.getService(this, 1, Intent(this, LinkService::class.java).setAction(ACTION_DISCONNECT),
            PendingIntent.FLAG_IMMUTABLE)
        val node = s.profile?.node.orEmpty()
        val text = when {
            s.tx -> getString(R.string.status_tx)
            s.rx -> getString(R.string.status_rx)
            s.conn == Conn.CONNECTED -> getString(R.string.status_connected)
            else -> getString(R.string.status_connecting)
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(getString(R.string.notif_title, node))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .addAction(0, getString(R.string.disconnect), disc)
            .build()
    }

    companion object {
        private const val CHANNEL = "link"
        private const val NOTIF_ID = 1
        private const val ACTION_DISCONNECT = "ovh.adan.allstar.DISCONNECT"

        fun start(ctx: Context) = ContextCompat.startForegroundService(ctx, Intent(ctx, LinkService::class.java))
        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, LinkService::class.java)) }
    }
}
