package ovh.adan.allstar

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ovh.adan.allstar.audio.AudioEngine
import ovh.adan.allstar.data.LinkedNode
import ovh.adan.allstar.data.NodeDirectory
import ovh.adan.allstar.data.NodeStats
import ovh.adan.allstar.data.Prefs
import ovh.adan.allstar.data.Profile
import ovh.adan.allstar.iax.IaxClient
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class Conn { DISCONNECTED, CONNECTING, CONNECTED }

data class LogEvent(val time: Long, val text: String, val kind: Kind = Kind.INFO) {
    enum class Kind { INFO, RX, TX, CMD, ERROR }
    val clock: String get() = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(time))
}

data class RadioState(
    val conn: Conn = Conn.DISCONNECTED,
    val profile: Profile? = null,
    val reason: String? = null,
    val rx: Boolean = false,
    val rxLevel: Float = -90f,
    val tx: Boolean = false,
    val txLevel: Float = -90f,
    val txStartedAt: Long = 0L,
    val connectedAt: Long = 0L,
    val links: List<LinkedNode> = emptyList(),
    val linksFromNode: Boolean = false,
    val events: List<LogEvent> = emptyList(),
)

/**
 * Process-wide radio controller: owns the IAX call and the audio engine and exposes
 * everything the UI needs as a StateFlow. The foreground [LinkService] only keeps the
 * process alive while a call is up.
 */
object Radio {
    private lateinit var app: Context
    lateinit var prefs: Prefs; private set
    lateinit var directory: NodeDirectory; private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(RadioState())
    val state: StateFlow<RadioState> = _state

    private var client: IaxClient? = null
    private var audio: AudioEngine? = null
    private var pollJob: Job? = null
    private var txGuardJob: Job? = null
    @Volatile private var remoteKeySeen = false
    @Volatile private var lastLoudRx = 0L

    fun init(ctx: Context) {
        if (::app.isInitialized) return
        app = ctx.applicationContext
        prefs = Prefs(app)
        directory = NodeDirectory(app)
        scope.launch { directory.load() }
    }

    // ----------------------------------------------------------------- call control

    fun connect(profile: Profile) {
        if (_state.value.conn != Conn.DISCONNECTED) disconnect()
        prefs.activeProfileId = profile.id
        remoteKeySeen = false
        _state.update { RadioState(conn = Conn.CONNECTING, profile = profile, events = it.events) }
        log(app.getString(R.string.ev_connecting, profile.node, profile.host))

        val engine = AudioEngine(object : AudioEngine.Callbacks {
            override fun onMicFrame(ulaw: ByteArray) { client?.sendVoice(ulaw) }
            override fun onRxLevel(db: Float) {
                if (db > -42f) lastLoudRx = SystemClock.elapsedRealtime()
                _state.update { it.copy(rxLevel = db, rx = if (remoteKeySeen) it.rx else isRxByLevel()) }
            }
            override fun onTxLevel(db: Float) { _state.update { it.copy(txLevel = db) } }
        }).also { it.micGain = prefs.micGain }
        audio = engine

        val caller = ("0" + profile.callsign.uppercase().filter { it.isLetterOrDigit() }).take(8)
            .let { if (it.length > 1) it else "0" }
        client = IaxClient(profile.host.trim(), profile.port, profile.username.trim(), profile.secret,
            profile.node.trim(), caller, object : IaxClient.Listener {
                override fun onState(state: IaxClient.State, reason: String?) = onIaxState(state, reason)
                override fun onVoice(ulaw: ByteArray) { audio?.onNetworkVoice(ulaw) }
                override fun onRemoteKey(keyed: Boolean) {
                    remoteKeySeen = true
                    if (keyed != _state.value.rx) {
                        _state.update { it.copy(rx = keyed) }
                        if (keyed) log(app.getString(R.string.ev_rx), LogEvent.Kind.RX)
                    }
                }
                override fun onText(text: String) {
                    NodeStats.parseLinkList(text)?.let { l -> setLinks(l, fromNode = true); return }
                }
            })
        LinkService.start(app)
        client!!.start()
    }

    fun disconnect() {
        pttUp()
        pollJob?.cancel(); pollJob = null
        client?.hangup(); client = null
        audio?.stop(); audio = null
        if (_state.value.conn != Conn.DISCONNECTED) log(app.getString(R.string.ev_disconnected))
        _state.update { it.copy(conn = Conn.DISCONNECTED, rx = false, tx = false, rxLevel = -90f, txLevel = -90f, links = emptyList()) }
        LinkService.stop(app)
    }

    private fun onIaxState(s: IaxClient.State, reason: String?) {
        when (s) {
            IaxClient.State.CONNECTED -> {
                audio?.start()
                _state.update { it.copy(conn = Conn.CONNECTED, reason = null, connectedAt = System.currentTimeMillis()) }
                log(app.getString(R.string.ev_connected, _state.value.profile?.node.orEmpty()))
                startPolling()
            }
            IaxClient.State.CLOSED -> {
                val wasUp = _state.value.conn != Conn.DISCONNECTED
                client = null
                audio?.stop(); audio = null
                pollJob?.cancel()
                txGuardJob?.cancel()
                if (wasUp) {
                    if (reason != null) log(app.getString(R.string.ev_failed, reason), LogEvent.Kind.ERROR)
                    else log(app.getString(R.string.ev_disconnected))
                }
                _state.update { it.copy(conn = Conn.DISCONNECTED, reason = reason, rx = false, tx = false, links = emptyList()) }
                LinkService.stop(app)
            }
            else -> {}
        }
    }

    private fun isRxByLevel() = SystemClock.elapsedRealtime() - lastLoudRx < 400

    // ----------------------------------------------------------------- PTT

    fun pttDown() {
        val c = client ?: return
        if (_state.value.conn != Conn.CONNECTED || _state.value.tx) return
        c.key()
        audio?.startCapture()
        _state.update { it.copy(tx = true, txStartedAt = SystemClock.elapsedRealtime()) }
        txGuardJob?.cancel()
        txGuardJob = scope.launch {
            delay(prefs.txTimeoutSec * 1000L)
            log(app.getString(R.string.ev_tot), LogEvent.Kind.ERROR)
            pttUp()
        }
    }

    fun pttUp() {
        if (!_state.value.tx) return
        txGuardJob?.cancel()
        audio?.stopCapture()
        val c = client
        scope.launch {
            delay(60)  // let the last captured frame go out before unkeying
            c?.unkey()
        }
        val secs = (SystemClock.elapsedRealtime() - _state.value.txStartedAt) / 1000.0
        _state.update { it.copy(tx = false, txLevel = -90f) }
        if (secs >= 0.5) log(app.getString(R.string.ev_tx, secs), LogEvent.Kind.TX)
    }

    fun togglePtt() = if (_state.value.tx) pttUp() else pttDown()

    // ----------------------------------------------------------------- node commands

    fun dtmf(digits: String, label: String? = null) {
        val c = client ?: return
        if (_state.value.conn != Conn.CONNECTED) return
        c.sendDtmf(digits)
        log(label ?: app.getString(R.string.ev_dtmf, digits), LogEvent.Kind.CMD)
        refreshLinksSoon()
    }

    fun linkNode(n: String, monitorOnly: Boolean = false) {
        addRecent(n)
        dtmf((if (monitorOnly) "*2" else "*3") + n,
            app.getString(if (monitorOnly) R.string.ev_monitor else R.string.ev_link, describe(n)))
    }

    fun unlinkNode(n: String) = dtmf("*1$n", app.getString(R.string.ev_unlink, describe(n)))
    /**
     * Stock ASL3 has no "disconnect all" DTMF code (*76 only exists on some older/custom configs),
     * so unlink each known link with *1, one after another.
     */
    fun unlinkAll() {
        val c = client ?: return
        if (_state.value.conn != Conn.CONNECTED) return
        val self = _state.value.profile?.node
        val targets = _state.value.links.map { it.number }.filter { it != self }.distinct()
        if (targets.isEmpty()) return
        log(app.getString(R.string.ev_unlink_all), LogEvent.Kind.CMD)
        Thread {
            targets.forEach { n ->
                c.sendDtmf("*1$n")
                Thread.sleep(200L * (n.length + 2) + 1500)
            }
        }.apply { isDaemon = true }.start()
        refreshLinksSoon()
    }

    fun describe(n: String): String {
        val info = directory.lookup(n)
        return if (info != null && info.callsign.isNotBlank()) "$n ${info.callsign}" else n
    }

    fun setMicGain(g: Float) { prefs.micGain = g; audio?.micGain = g }

    private fun addRecent(n: String) { prefs.recents = listOf(n) + prefs.recents.filter { it != n } }

    // ----------------------------------------------------------------- link list

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                pollLinks()
                delay(30_000)
            }
        }
    }

    private fun refreshLinksSoon() {
        scope.launch { delay(4000); pollLinks(); delay(12000); pollLinks() }
    }

    private suspend fun pollLinks() {
        if (_state.value.linksFromNode) return
        val node = _state.value.profile?.node ?: return
        NodeStats.fetch(node).onSuccess { d -> setLinks(d.links, fromNode = false) }
    }

    private fun setLinks(list: List<LinkedNode>, fromNode: Boolean) {
        // hide iaxRpt-style clients (names starting with 0) and ourselves
        val clean = list.filter { !it.number.startsWith("0") }.distinctBy { it.number }
        val old = _state.value.links.map { it.number }.toSet()
        val now = clean.map { it.number }.toSet()
        if (_state.value.conn == Conn.CONNECTED && (_state.value.links.isNotEmpty() || old.isNotEmpty() || fromNode)) {
            (now - old).forEach { log(app.getString(R.string.ev_linked, describe(it))) }
            (old - now).forEach { log(app.getString(R.string.ev_unlinked, describe(it))) }
        }
        _state.update { it.copy(links = clean, linksFromNode = it.linksFromNode || fromNode) }
    }

    // ----------------------------------------------------------------- log

    private fun log(text: String, kind: LogEvent.Kind = LogEvent.Kind.INFO) {
        _state.update { it.copy(events = (listOf(LogEvent(System.currentTimeMillis(), text, kind)) + it.events).take(100)) }
    }
}
