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
    /** Started by BOOT_COMPLETED: Android never lets such a service gain the microphone type. */
    private var fromBoot = false

    override fun attachBaseContext(base: Context) = super.attachBaseContext(Lang.wrap(base))

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT) {
            Radio.disconnect()
            return START_NOT_STICKY
        }
        instance = this
        if (intent?.action == ACTION_BOOT) fromBoot = true
        createChannel()
        enterForeground()
        acquireLocks()
        if (intent?.action == ACTION_BOOT) bootConnect()
        if (watch == null) watch = scope.launch {
            Radio.state.map { Triple(it.conn, it.rx, it.tx) }.distinctUntilChanged().collect {
                getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(Radio.state.value))
            }
        }
        return START_NOT_STICKY
    }

    /**
     * Android 14+ forbids microphone/media foreground services started from the background
     * (phone boot), so those start as "specialUse" (listen only) and gain the microphone type
     * the next time the app is on screen.
     */
    fun enterForeground() {
        val micOk = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val types = when {
            Build.VERSION.SDK_INT >= 34 && !Radio.uiVisible -> ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            Build.VERSION.SDK_INT >= 30 ->
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or (if (micOk) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
            else -> 0
        }
        runCatching { ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(Radio.state.value), types) }
            .onFailure { android.util.Log.w("LinkService", "startForeground types=$types failed", it); if (types != 0 && Build.VERSION.SDK_INT >= 34)
                ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(Radio.state.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) }
    }

    /** After boot: wait for a working network, then connect to the last used node. */
    private fun bootConnect() {
        val p = Radio.prefs.activeProfile ?: return stopSelf()
        scope.launch {
            val cm = getSystemService(android.net.ConnectivityManager::class.java)
            repeat(90) {
                val caps = cm.getNetworkCapabilities(cm.activeNetwork)
                if (caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true) return@repeat
                kotlinx.coroutines.delay(2000)
            }
            Radio.autoConnect(p)
        }
    }

    override fun onDestroy() {
        if (instance === this) instance = null
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
        const val ACTION_BOOT = "ovh.adan.allstar.BOOT"
        @Volatile private var instance: LinkService? = null

        /** Start the service, or just refresh its foreground types if it is already running. */
        fun start(ctx: Context) {
            instance?.let { s ->
                if (s.fromBoot && Radio.uiVisible && Build.VERSION.SDK_INT >= 34) {
                    // re-create it from the visible app so it may use the microphone; the IAX
                    // connection lives in Radio and is not affected
                    ctx.stopService(Intent(ctx, LinkService::class.java))
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        if (Radio.state.value.conn != Conn.DISCONNECTED && instance == null)
                            ContextCompat.startForegroundService(ctx, Intent(ctx, LinkService::class.java))
                    }, 400)
                } else s.enterForeground()
                return
            }
            ContextCompat.startForegroundService(ctx, Intent(ctx, LinkService::class.java))
        }

        fun startOnBoot(ctx: Context) =
            ContextCompat.startForegroundService(ctx, Intent(ctx, LinkService::class.java).setAction(ACTION_BOOT))
        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, LinkService::class.java)) }
    }
}
