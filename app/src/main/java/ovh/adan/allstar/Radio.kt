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
import ovh.adan.allstar.audio.HamCallerId
import ovh.adan.allstar.data.LinkedNode
import ovh.adan.allstar.data.NodeDirectory
import ovh.adan.allstar.data.NodeStats
import ovh.adan.allstar.data.isEchoLinkNumber
import ovh.adan.allstar.data.Prefs
import ovh.adan.allstar.data.AslAccount
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
    /** elapsedRealtime when the transmit timeout cuts the transmission, once its warning has started; else 0. */
    val txEndsAt: Long = 0L,
    val connectedAt: Long = 0L,
    val links: List<LinkedNode> = emptyList(),
    /** Links talking to us right now (from Allmon3), if known. */
    val talkers: Set<String> = emptySet(),
    /** Last callsign decoded from a Ham CallerID burst, and when. */
    val cidCall: String? = null,
    val cidAt: Long = 0L,
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
    /** MainActivity is on screen (lets the service add the microphone type on Android 14+). */
    @Volatile var uiVisible = false
    /** Automatic (re)connection attempts left after a failure: used by auto-connect and start-on-boot. */
    @Volatile private var retriesLeft = 0
    private var retryJob: Job? = null
    /** The user wants the link up (set by connect, cleared by disconnect): drops are then retried. */
    @Volatile private var keepUp = false
    /** This link reached CONNECTED at least once: a later drop is retried without limit (with backoff). */
    @Volatile private var sessionUp = false
    @Volatile private var reconnectAttempt = 0
    /** Bumped on every dial: a slow token/DNS lookup from an older attempt is dropped. */
    @Volatile private var dialGen = 0
    /** Web Transceiver token format that worked (or to try first), and whether the other was tried. */
    @Volatile private var wtFormat: AslAccount.Format? = null
    @Volatile private var wtTriedOther = false
    private var stableJob: Job? = null
    private var lastNetwork: android.net.Network? = null
    private var allmon: ovh.adan.allstar.data.AllmonWatcher? = null

    /** Connect without user interaction (app start / phone boot), retrying a few times if the network is not ready. */
    fun autoConnect(profile: Profile) {
        if (_state.value.conn != Conn.DISCONNECTED || !profile.isComplete) return
        retriesLeft = 4
        connect(profile, auto = true)
    }
    @Volatile private var lastLoudRx = 0L
    @Volatile private var loudRun = 0

    fun init(ctx: Context) {
        if (::app.isInitialized) return
        app = ctx.applicationContext
        prefs = Prefs(app)
        directory = NodeDirectory(app)
        scope.launch { directory.load() }
        watchNetwork()
    }

    /** Re-dial at once when the phone changes network (Wi-Fi <-> mobile) or one comes back. */
    private fun watchNetwork() {
        val cm = app.getSystemService(android.net.ConnectivityManager::class.java) ?: return
        runCatching {
            cm.registerDefaultNetworkCallback(object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) {
                    val prev = lastNetwork
                    lastNetwork = network
                    if (prev == null || prev == network || !keepUp) return
                    val p = _state.value.profile ?: return
                    scope.launch {
                        delay(1500)  // let the new network settle (DNS, routes)
                        if (!keepUp) return@launch
                        when {
                            retryJob?.isActive == true -> { retryJob?.cancel(); reconnectAttempt = 0; connect(p, auto = true) }
                            _state.value.conn == Conn.CONNECTED -> {
                                log(Lang.strings(app).getString(R.string.ev_network_changed))
                                teardown()
                                connect(p, auto = true)
                            }
                        }
                    }
                }
            })
        }
    }

    /** Drop the current call quietly (no state change, service kept): used before re-dialling. */
    private fun teardown() {
        allmon?.stop(); allmon = null
        pttUp()
        pollJob?.cancel(); pollJob = null
        txGuardJob?.cancel()
        val c = client; client = null
        c?.hangup()
        audio?.stop(); audio = null
    }

    // ----------------------------------------------------------------- call control

    fun connect(profile: Profile, auto: Boolean = false) {
        if (!auto) { retriesLeft = 0; retryJob?.cancel(); sessionUp = false; reconnectAttempt = 0; wtFormat = null; wtTriedOther = false }
        val reconnecting = auto && keepUp && _state.value.conn != Conn.DISCONNECTED
        if (client != null) { if (reconnecting) teardown() else disconnect() }
        keepUp = true
        prefs.activeProfileId = profile.id
        remoteKeySeen = false
        _state.update { RadioState(conn = Conn.CONNECTING, profile = profile, events = it.events) }
        log(Lang.strings(app).getString(R.string.ev_connecting, profile.node, if (profile.account) "AllStarLink" else profile.host))
        // while re-dialling the foreground service is still up (and may not be restarted from the background)
        if (!reconnecting) LinkService.start(app)
        val gen = ++dialGen

        if (!profile.account) {
            if (prefs.sendCallerId && HamCallerId.cleanCallsign(profile.callsign).isEmpty())
                log(Lang.strings(app).getString(R.string.ev_no_callsign), LogEvent.Kind.ERROR)
            val caller = ("0" + profile.callsign.uppercase().filter { it.isLetterOrDigit() }).take(8)
                .let { if (it.length > 1) it else "0" }
            dial(profile, profile.host.trim(), profile.port, profile.username.trim(), profile.secret, profile.node.trim(), caller, "0")
            return
        }
        // Web Transceiver: find the node, get a token from AllStarLink, then call the node's [allstar-public]
        scope.launch(Dispatchers.IO) {
            val r = runCatching {
                val (host, port) = AslAccount.resolveNode(app, profile.node.trim())
                val first = wtFormat ?: AslAccount.Format.LEGACY
                val (fmt, name) = try {
                    first to AslAccount.callerName(first, profile.callsign, profile.secret, prefs.clientId)
                } catch (e: AslAccount.AuthException) {
                    // legacy service retired / app auth not live: use the other one
                    val other = if (first == AslAccount.Format.LEGACY) AslAccount.Format.APP else AslAccount.Format.LEGACY
                    other to AslAccount.callerName(other, profile.callsign, profile.secret, prefs.clientId)
                }
                wtFormat = fmt
                Triple(host, port, name)
            }
            if (gen != dialGen || !keepUp) return@launch
            r.onSuccess { (host, port, name) ->
                dial(profile.copy(host = host, port = port), host, port, AslAccount.IAX_USER, AslAccount.IAX_SECRET,
                    AslAccount.IAX_EXTEN, name, profile.node.trim())
            }.onFailure { e ->
                val bad = e is AslAccount.AuthException && e.badCredentials
                val reason = if (bad) Lang.strings(app).getString(R.string.wt_bad_login) else (e.message ?: e.javaClass.simpleName)
                onIaxState(IaxClient.State.CLOSED, reason, rejected = bad)
            }
        }
    }

    private fun dial(profile: Profile, host: String, port: Int, user: String, secret: String, called: String,
                     callerName: String, callingNumber: String) {
        val engine = AudioEngine(object : AudioEngine.Callbacks {
            override fun onMicFrame(ulaw: ByteArray) { client?.sendVoice(ulaw) }
            override fun onRxLevel(db: Float) {
                // Level-based RX detection (app_rpt sends no key/unkey to radio-less clients):
                // require two loud frames in a row to start, and hold 1.5 s through speech pauses.
                if (db > -40f) { if (++loudRun >= 2) lastLoudRx = SystemClock.elapsedRealtime() } else loudRun = 0
                _state.update { it.copy(rxLevel = db, rx = if (remoteKeySeen) it.rx else isRxByLevel()) }
            }
            override fun onTxLevel(db: Float) { _state.update { it.copy(txLevel = db) } }
            override fun onCallsign(callsign: String) {
                android.util.Log.i("HamCallerId", "decoded $callsign")
                val first = _state.value.cidCall != callsign || System.currentTimeMillis() - _state.value.cidAt > 5000
                _state.update { it.copy(cidCall = callsign, cidAt = System.currentTimeMillis()) }
                if (first) log(Lang.strings(app).getString(R.string.ev_cid, callsign), LogEvent.Kind.RX)
            }
        }).also { it.micGain = prefs.micGain }
        audio = engine
        _state.update { it.copy(profile = profile) }

        var self: IaxClient? = null
        client = IaxClient(host, port, user, secret, called, callerName, object : IaxClient.Listener {
                override fun onState(state: IaxClient.State, reason: String?) {
                    if (client === self) onIaxState(state, reason, self?.rejected == true)  // ignore a replaced call
                }
                override fun onVoice(ulaw: ByteArray, ts16: Int) { audio?.onNetworkVoice(ulaw, ts16) }
                override fun onRemoteKey(keyed: Boolean) {
                    remoteKeySeen = true
                    if (keyed != _state.value.rx) {
                        _state.update { it.copy(rx = keyed) }
                        if (keyed) log(Lang.strings(app).getString(R.string.ev_rx), LogEvent.Kind.RX)
                    }
                }
                override fun onText(text: String) {
                    NodeStats.parseLinkList(text)?.let { l -> setLinks(l, fromNode = true); return }
                    onNodeText(text)
                }
            }, callingNumber).also { self = it }
        client!!.start()
    }

    fun disconnect() {
        keepUp = false; sessionUp = false
        retriesLeft = 0; retryJob?.cancel()
        teardown()
        if (_state.value.conn != Conn.DISCONNECTED) log(Lang.strings(app).getString(R.string.ev_disconnected))
        _state.update { it.copy(conn = Conn.DISCONNECTED, rx = false, tx = false, rxLevel = -90f, txLevel = -90f, links = emptyList(), talkers = emptySet()) }
        LinkService.stop(app)
    }

    private fun onIaxState(s: IaxClient.State, reason: String?, rejected: Boolean) {
        when (s) {
            IaxClient.State.CONNECTED -> {
                retriesLeft = 0
                // a node that answers and hangs up at once (WT token refused) must not count as a working link
                stableJob?.cancel()
                stableJob = scope.launch { delay(STABLE_MS); if (_state.value.conn == Conn.CONNECTED) { sessionUp = true; reconnectAttempt = 0; wtTriedOther = false } }
                audio?.start()
                _state.update { it.copy(conn = Conn.CONNECTED, reason = null, connectedAt = System.currentTimeMillis()) }
                log(Lang.strings(app).getString(R.string.ev_connected, _state.value.profile?.node.orEmpty()))
                _state.value.profile?.let { p ->
                    allmon?.stop()
                    allmon = ovh.adan.allstar.data.AllmonWatcher(p) { who ->
                        if (_state.value.talkers != who) _state.update { it.copy(talkers = who) }
                    }.also { it.start(scope) }
                }
                startPolling()
            }
            IaxClient.State.CLOSED -> {
                stableJob?.cancel()
                allmon?.stop(); allmon = null
                val st = _state.value
                val p0 = st.profile
                val quickDrop = st.conn != Conn.CONNECTED || System.currentTimeMillis() - st.connectedAt < STABLE_MS
                if (p0 != null && p0.account && keepUp && quickDrop && client != null && !sessionUp) {
                    // the node ended the call before rpt ran: token not accepted in this format
                    client = null; audio?.stop(); audio = null; pollJob?.cancel(); txGuardJob?.cancel()
                    if (!wtTriedOther) {
                        wtTriedOther = true
                        wtFormat = if (wtFormat == AslAccount.Format.APP) AslAccount.Format.LEGACY else AslAccount.Format.APP
                        connect(p0, auto = true)
                        return
                    }
                    keepUp = false
                    log(Lang.strings(app).getString(R.string.ev_failed, Lang.strings(app).getString(R.string.wt_refused)), LogEvent.Kind.ERROR)
                    _state.update { it.copy(conn = Conn.DISCONNECTED, reason = Lang.strings(app).getString(R.string.wt_refused),
                        rx = false, tx = false, links = emptyList(), talkers = emptySet()) }
                    LinkService.stop(app)
                    return
                }
                val wasUp = st.conn != Conn.DISCONNECTED
                client = null
                audio?.stop(); audio = null
                pollJob?.cancel()
                txGuardJob?.cancel()
                if (wasUp) {
                    if (reason != null) log(Lang.strings(app).getString(R.string.ev_failed, reason), LogEvent.Kind.ERROR)
                    else log(Lang.strings(app).getString(R.string.ev_disconnected))
                }
                val p = _state.value.profile
                // a link that was up is re-dialled for as long as the user wants it (5, 10, 20, 30, 60 s…);
                // a first connection only by auto-connect / boot (4 tries); never after a rejection (credentials)
                val retry = reason != null && keepUp && p != null && !rejected && (sessionUp || retriesLeft > 0)
                if (retry) {
                    // keep the foreground service: it cannot be restarted from the background
                    val wait = if (sessionUp) listOf(5, 10, 20, 30, 60)[reconnectAttempt.coerceAtMost(4)] else 20
                    if (sessionUp) reconnectAttempt++ else retriesLeft--
                    _state.update { it.copy(conn = Conn.CONNECTING, reason = reason, rx = false, tx = false, links = emptyList(), talkers = emptySet()) }
                    log(Lang.strings(app).getString(R.string.ev_reconnecting, wait))
                    retryJob = scope.launch { delay(wait * 1000L); if (keepUp && client == null) connect(p!!, auto = true) }
                } else {
                    keepUp = false; sessionUp = false
                    _state.update { it.copy(conn = Conn.DISCONNECTED, reason = reason, rx = false, tx = false, links = emptyList(), talkers = emptySet()) }
                    LinkService.stop(app)
                }
            }
            else -> {}
        }
    }

    private val REKEY_MS = 2000L
    private val TOT_WARN_MS = 10_000L
    private const val STABLE_MS = 8000L
    private const val START_PREROLL = 2400   // 300 ms at 8 kHz

    /** Our Ham CallerID burst, if enabled and the profile has a callsign. */
    private fun cidBurst(): ShortArray? =
        if (prefs.sendCallerId) _state.value.profile?.callsign?.let { ovh.adan.allstar.audio.HamCallerId.modulate(it, 0.35f) } else null

    private fun isRxByLevel() = SystemClock.elapsedRealtime() - lastLoudRx < 1500

    // ----------------------------------------------------------------- PTT

    fun pttDown() {
        val c = client ?: return
        if (_state.value.conn != Conn.CONNECTED || _state.value.tx) return
        c.key()
        audio?.startCapture()
        // 300 ms of silence before the start burst: the first frames of a transmission reach the node
        // bunched up (the microphone starts in bursts) and its jitter buffer drops some of them,
        // which wrecked the start burst. The voice captured meanwhile follows the burst.
        cidBurst()?.let { audio?.injectBurst(ShortArray(START_PREROLL) + it) }
        _state.update { it.copy(tx = true, txStartedAt = SystemClock.elapsedRealtime(), txEndsAt = 0L) }
        txGuardJob?.cancel()
        txGuardJob = scope.launch {
            launch { while (true) { delay(REKEY_MS); client?.rekey() } }
            // Warn before the timeout: countdown on screen and a short vibration each second of the
            // last 5 (vibration, not a beep: the microphone is open and a beep would go on the air).
            val totMs = prefs.txTimeoutSec * 1000L
            val warnMs = TOT_WARN_MS.coerceAtMost(totMs / 3)
            delay(totMs - warnMs)
            _state.update { it.copy(txEndsAt = SystemClock.elapsedRealtime() + warnMs) }
            var left = warnMs
            while (left > 0) {
                if (left <= 5000) vibrate(60)
                val step = minOf(1000L, left)
                delay(step); left -= step
            }
            vibrate(400)
            log(Lang.strings(app).getString(R.string.ev_tot), LogEvent.Kind.ERROR)
            pttUp()
        }
    }

    fun pttUp() {
        if (!_state.value.tx) return
        txGuardJob?.cancel()
        val c = client
        val unkey = {
            scope.launch {
                delay(60)  // let the last captured frame go out before unkeying
                c?.unkey()
            }
            Unit
        }
        val burst = cidBurst()
        val a = audio
        if (burst != null && a != null) a.finishWithBurst(burst, unkey)   // callsign burst, then unkey
        else { a?.stopCapture(); unkey() }
        val secs = (SystemClock.elapsedRealtime() - _state.value.txStartedAt) / 1000.0
        _state.update { it.copy(tx = false, txLevel = -90f, txEndsAt = 0L) }
        if (secs >= 0.5) log(Lang.strings(app).getString(R.string.ev_tx, secs), LogEvent.Kind.TX)
    }

    fun togglePtt() = if (_state.value.tx) pttUp() else pttDown()

    private fun vibrate(ms: Long) {
        runCatching {
            val v = if (android.os.Build.VERSION.SDK_INT >= 31)
                app.getSystemService(android.os.VibratorManager::class.java).defaultVibrator
            else @Suppress("DEPRECATION") (app.getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator)
            // Alarm usage: a plain vibrate() counts as touch feedback and is dropped when the user
            // turned touch vibration off, which is exactly when this warning matters.
            val effect = if (android.os.Build.VERSION.SDK_INT >= 26)
                android.os.VibrationEffect.createOneShot(ms, android.os.VibrationEffect.DEFAULT_AMPLITUDE) else null
            when {
                android.os.Build.VERSION.SDK_INT >= 33 -> v.vibrate(effect!!,
                    android.os.VibrationAttributes.createForUsage(android.os.VibrationAttributes.USAGE_ALARM))
                effect != null -> @Suppress("DEPRECATION") v.vibrate(effect,
                    android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_ALARM).build())
                else -> @Suppress("DEPRECATION") v.vibrate(ms)
            }
        }
    }

    // ----------------------------------------------------------------- node commands

    fun dtmf(digits: String, label: String? = null) {
        val c = client ?: return
        if (_state.value.conn != Conn.CONNECTED) return
        c.sendDtmf(digits)
        log(label ?: Lang.strings(app).getString(R.string.ev_dtmf, digits), LogEvent.Kind.CMD)
        refreshLinksSoon()
        // *70 only answers by voice; show the link list the app already knows as text.
        if (digits == "*70") scope.launch {
            delay(4500)
            val l = _state.value.links.map { describe(it.number) }
            notify(if (l.isEmpty()) Lang.strings(app).getString(R.string.ev_no_links) else Lang.strings(app).getString(R.string.ev_links_now, l.joinToString(", ")))
        }
    }

    /** Telemetry text the node sends to IAX clients, e.g. "T 60596 STATS_TIME,1795376829" for *722. */
    private fun onNodeText(text: String) {
        val m = Regex("^T\\s+\\d+\\s+([A-Z_]+)(?:,(.*))?").find(text.trim()) ?: return
        val (what, arg) = m.destructured
        when (what) {
            // "T <node> CONNECTED,<node>,<peer>": the node tells us a link came up or went down.
            // Private nodes have no public stats, so this is the only way we learn about it.
            "CONNECTED", "DISCONNECTED", "REMDISC" -> {
                val peer = arg.split(',').map { it.trim() }.lastOrNull { it.isNotEmpty() }
                if (peer == null || peer == _state.value.profile?.node) return
                val cur = _state.value.links
                val next = if (what == "CONNECTED") cur.filter { it.number != peer } + LinkedNode(peer)
                           else cur.filter { it.number != peer }
                if (what != "CONNECTED") markPermanent(peer, false)
                setLinks(next, fromNode = false, announce = true)
            }
            "STATS_TIME" -> arg.trim().toLongOrNull()?.let { t ->
                notify(Lang.strings(app).getString(R.string.ev_node_time,
                    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(t * 1000))))
            }
            else -> notify(Lang.strings(app).getString(R.string.ev_node_said, (what + if (arg.isNotBlank()) " $arg" else "").trim()))
        }
    }

    private fun notify(text: String) {
        log(text, LogEvent.Kind.INFO)
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            android.widget.Toast.makeText(app, text, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    fun linkNode(n: String, monitorOnly: Boolean = false, permanent: Boolean = false) {
        addRecent(n)
        val code = _state.value.profile?.permLink.orEmpty()
        if (permanent && !monitorOnly && code.isNotBlank()) {
            markPermanent(n, true)
            dtmf(code + n, Lang.strings(app).getString(R.string.ev_link_perm, describe(n)))
            return
        }
        markPermanent(n, false)
        dtmf((if (monitorOnly) "*2" else "*3") + n,
            Lang.strings(app).getString(if (monitorOnly) R.string.ev_monitor else R.string.ev_link, describe(n)))
    }

    /** A permanent link ignores *1 (ilink 1): it needs the profile's permanent unlink code (ilink 11). */
    fun unlinkNode(n: String, permanent: Boolean = isPermanent(n)) {
        val code = _state.value.profile?.permUnlink.orEmpty()
        markPermanent(n, false)
        if (permanent && code.isNotBlank()) dtmf(code + n, Lang.strings(app).getString(R.string.ev_unlink_perm, describe(n)))
        else { dtmf("*1$n", Lang.strings(app).getString(R.string.ev_unlink, describe(n))); retryAsPermanent(listOf(n)) }
    }

    /** app_rpt ignores *1 for a link made permanent elsewhere (Allmon, another client, a script):
     *  if it is still there a few seconds later, send the permanent-unlink code, which drops both kinds. */
    private fun retryAsPermanent(nodes: List<String>, waitMs: Long = 7000) {
        val code = _state.value.profile?.permUnlink.orEmpty()
        if (nodes.isEmpty() || code.isBlank() || code == "*1") return
        scope.launch {
            delay(waitMs)
            if (_state.value.conn != Conn.CONNECTED) return@launch
            val still = nodes.filter { n -> _state.value.links.any { it.number == n } }
            still.forEachIndexed { i, n ->
                if (i > 0) delay(200L * (n.length + 2) + 1500)
                dtmf(code + n, Lang.strings(app).getString(R.string.ev_unlink_perm, describe(n)))
            }
        }
    }

    fun isPermanent(n: String): Boolean = _state.value.profile?.let { prefs.permanentLinks(it.id).contains(n) } ?: false

    private fun markPermanent(n: String, on: Boolean) {
        val id = _state.value.profile?.id ?: return
        val now = prefs.permanentLinks(id)
        val next = if (on) now + n else now - n
        if (next != now) prefs.setPermanentLinks(id, next)
    }
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
        log(Lang.strings(app).getString(R.string.ev_unlink_all), LogEvent.Kind.CMD)
        val permCode = _state.value.profile?.permUnlink.orEmpty()
        val codes = targets.associateWith { n -> if (isPermanent(n) && permCode.isNotBlank()) permCode else "*1" }
        targets.forEach { markPermanent(it, false) }
        Thread {
            targets.forEach { n ->
                c.sendDtmf(codes.getValue(n) + n)
                Thread.sleep(200L * (n.length + 2) + 1500)
            }
        }.apply { isDaemon = true }.start()
        refreshLinksSoon()
        val plain = targets.filter { codes.getValue(it) == "*1" }
        retryAsPermanent(plain, 7000 + targets.sumOf { 200L * (it.length + 2) + 1500 })
    }

    fun describe(n: String): String {
        val info = directory.lookup(n)
        return if (info != null && info.callsign.isNotBlank()) "$n ${info.callsign}" else n
    }

    fun setMicGain(g: Float) { prefs.micGain = g; audio?.micGain = g }

    private fun addRecent(n: String) { directory.rememberEchoLink(n); prefs.recents = listOf(n) + prefs.recents.filter { it != n } }

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

    private fun setLinks(list: List<LinkedNode>, fromNode: Boolean, announce: Boolean = false) {
        // hide iaxRpt-style clients (names starting with 0) and ourselves
        val clean = list.filter { !it.number.startsWith("0") }.distinctBy { it.number }
        val old = _state.value.links.map { it.number }.toSet()
        val now = clean.map { it.number }.toSet()
        if (_state.value.conn == Conn.CONNECTED && (_state.value.links.isNotEmpty() || old.isNotEmpty() || fromNode || announce)) {
            (now - old).forEach { log(Lang.strings(app).getString(R.string.ev_linked, describe(it))) }
            (old - now).forEach { log(Lang.strings(app).getString(R.string.ev_unlinked, describe(it))) }
        }
        _state.update { it.copy(links = clean, linksFromNode = it.linksFromNode || fromNode) }
        rememberLinkedEchoLink(now)
    }

    private var elFetchAt = 0L

    /** Keep the names of linked EchoLink nodes, so a conference still shows its name when it drops
     *  for a while out of the public EchoLink list (it does when its user count changes). */
    private fun rememberLinkedEchoLink(nums: Set<String>) {
        val el = nums.filter(::isEchoLinkNumber)
        if (el.isEmpty()) return
        el.forEach(directory::rememberEchoLink)
        val missing = el.filter { directory.lookup(it)?.callsign.isNullOrBlank() }
        val t = SystemClock.elapsedRealtime()
        if (missing.isEmpty() || (elFetchAt != 0L && t - elFetchAt < 5 * 60_000L)) return
        elFetchAt = t
        scope.launch(Dispatchers.IO) {
            directory.loadEchoLink()
            missing.forEach(directory::rememberEchoLink)
        }
    }

    // ----------------------------------------------------------------- log

    private fun log(text: String, kind: LogEvent.Kind = LogEvent.Kind.INFO) {
        _state.update { it.copy(events = (listOf(LogEvent(System.currentTimeMillis(), text, kind)) + it.events).take(100)) }
    }
}
