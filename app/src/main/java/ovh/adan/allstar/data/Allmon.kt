package ovh.adan.allstar.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Who is talking: app_rpt does not tell IAX clients which link is keyed, but Allmon3 (installed with
 * ASL3) streams the node status every second over a WebSocket, with PTT per connection.
 * The status stream needs no login. The base URL is the profile's, or found next to the node host.
 */
class AllmonWatcher(private val profile: Profile, private val onTalkers: (Set<String>) -> Unit) {
    private val http = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS).build()
    private var job: Job? = null
    @Volatile private var ws: WebSocket? = null

    fun start(scope: CoroutineScope) {
        job = scope.launch(Dispatchers.IO) {
            var base: String? = null
            var failures = 0
            while (isActive) {
                if (base == null) base = candidates().firstOrNull { probe(it) }
                if (base == null) {
                    android.util.Log.i("Allmon", "no Allmon3 found for node ${profile.node}")
                    delay(if (failures++ < 2) 30_000 else 300_000); continue
                }
                android.util.Log.i("Allmon", "using $base")
                val port = runCatching { statPort(base) }.getOrNull()
                if (port == null) { base = null; delay(30_000); continue }
                val closed = Channel<Unit>(1)
                val url = base.replaceFirst("http", "ws") + "ws/$port"
                ws = http.newWebSocket(Request.Builder().url(url).header("User-Agent", Http.UA).build(), object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) { parse(text) }
                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { closed.trySend(Unit) }
                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { closed.trySend(Unit) }
                })
                closed.receive()
                onTalkers(emptySet())
                delay(10_000)
            }
        }
    }

    fun stop() {
        job?.cancel(); job = null
        ws?.cancel(); ws = null
        http.dispatcher.executorService.shutdown()
    }

    private fun candidates(): List<String> {
        profile.allmon.trim().takeIf { it.isNotEmpty() }?.let { u ->
            val b = if (u.startsWith("http")) u else "https://$u"
            return listOf(if (b.endsWith("/")) b else "$b/")
        }
        val h = profile.host.trim()
        return listOf("https://$h/", "https://$h/allmon3/", "http://$h/", "http://$h/allmon3/")
    }

    private fun get(url: String): String? = runCatching {
        http.newCall(Request.Builder().url(url).header("User-Agent", Http.UA).build()).execute().use { r ->
            if (r.isSuccessful) r.body?.string() else null
        }
    }.getOrNull()

    private fun probe(base: String): Boolean {
        val t = get(base + "master/node/listall") ?: return false
        return runCatching { JSONObject(t).optJSONArray("SUCCESS")?.let { a -> (0 until a.length()).any { a.get(it).toString() == profile.node } } }
            .getOrNull() == true
    }

    private fun statPort(base: String): Int? =
        get(base + "master/node/${profile.node}/config")?.let { JSONObject(it).optJSONObject("SUCCESS")?.optInt("statport") }
            ?.takeIf { it > 0 }

    private fun parse(text: String) {
        val n = runCatching { JSONObject(text).optJSONObject(profile.node) }.getOrNull() ?: return
        // CONNS.<node>.PTT is "1" while that link keys us (Allmon3 SawStat); several can be keyed at once
        val conns = n.optJSONObject("CONNS") ?: return onTalkers(emptySet())
        onTalkers(conns.keys().asSequence().filter { conns.optJSONObject(it)?.optString("PTT") == "1" }.toSet())
    }
}
