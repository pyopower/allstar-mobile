package ovh.adan.allstar.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** AllStar numbers EchoLink nodes as 3 + the 6-digit EchoLink node number. */
fun isEchoLinkNumber(n: String) = n.length == 7 && n.startsWith("3") && n.all { it.isDigit() }

private val MULTI_SPACE = Regex("\\s{2,}")

enum class ElType { REPEATER, LINK, CONFERENCE }

/** A station logged in to EchoLink right now (from echolink.org/logins.jsp). */
data class EchoStation(val node: String, val callsign: String, val info: String, val busy: Boolean, val type: ElType) {
    /** AllStar dial number: 3 + 6-digit EchoLink node. */
    val number get() = "3" + node.padStart(6, '0')
    fun toInfo() = NodeInfo(number, callsign, info, "")
}

data class NodeInfo(val number: String, val callsign: String, val description: String, val location: String) {
    val isEchoLink get() = number.length == 7 && number.startsWith("3")
    val displayNumber get() = if (isEchoLink) "EL ${number.drop(1)}" else number
}

/**
 * The public AllStarLink node list (allmondb, "number|callsign|description|location"),
 * cached on disk and refreshed once a day.
 */
class NodeDirectory(ctx: Context) {
    private val cache = File(ctx.filesDir, "astdb.txt")
    private val _nodes = MutableStateFlow<List<NodeInfo>>(emptyList())
    val nodes: StateFlow<List<NodeInfo>> = _nodes
    private var byNumber: Map<String, NodeInfo> = emptyMap()

    val ageMs get() = if (cache.exists()) System.currentTimeMillis() - cache.lastModified() else Long.MAX_VALUE

    // AllStar nodes reporting to stats.allstarlink.org right now (≈ online)
    private val onlineCache = File(ctx.filesDir, "online.txt")
    private val _online = MutableStateFlow<Set<String>>(emptySet())
    val online: StateFlow<Set<String>> = _online
    /** true/false when the online list is known, null otherwise. */
    fun isOnline(n: String): Boolean? = _online.value.takeIf { it.isNotEmpty() }?.contains(n)

    // EchoLink stations logged in right now
    private val elCache = File(ctx.filesDir, "echolink.tsv")
    private val elKnownFile = File(ctx.filesDir, "echolink-known.tsv")
    private val _echo = MutableStateFlow<List<EchoStation>>(emptyList())
    val echo: StateFlow<List<EchoStation>> = _echo
    private var echoByNumber: Map<String, EchoStation> = emptyMap()
    private var elKnown: MutableMap<String, NodeInfo>? = null
    val echoAgeMs get() = if (elCache.exists()) System.currentTimeMillis() - elCache.lastModified() else Long.MAX_VALUE

    fun echoStation(number: String): EchoStation? = echoByNumber[number]

    fun lookup(number: String): NodeInfo? =
        byNumber[number] ?: echoByNumber[number]?.toInfo() ?: known()[number]

    private fun known(): MutableMap<String, NodeInfo> = elKnown ?: synchronized(this) {
        val m = HashMap<String, NodeInfo>()
        runCatching {
            if (elKnownFile.exists()) elKnownFile.readLines().forEach { l ->
                val p = l.split('\t'); if (p.size >= 2) m[p[0]] = NodeInfo(p[0], p[1], p.getOrElse(2) { "" }, "")
            }
        }
        elKnown = m; m
    }

    /** Keep the name of an EchoLink favorite/recent so it still shows when the station is offline. */
    fun rememberEchoLink(number: String) {
        val st = echoByNumber[number] ?: return
        val m = known()
        if (m[number]?.callsign == st.callsign && m[number]?.description == st.info) return
        m[number] = st.toInfo()
        runCatching { elKnownFile.writeText(m.values.joinToString("\n") { "${it.number}\t${it.callsign}\t${it.description.replace('\t', ' ')}" }) }
    }

    suspend fun loadOnline(force: Boolean = false): Result<Int> = withContext(Dispatchers.IO) {
        if (_online.value.isEmpty() && onlineCache.exists() &&
            System.currentTimeMillis() - onlineCache.lastModified() < 3 * 3600_000L) publishOnline(onlineCache.readText())
        val age = if (onlineCache.exists()) System.currentTimeMillis() - onlineCache.lastModified() else Long.MAX_VALUE
        if (!force && age < 10 * 60_000L && _online.value.isNotEmpty()) return@withContext Result.success(_online.value.size)
        runCatching {
            val text = Http.get(ONLINE_URL, 30000)
            val ids = text.lineSequence().map { it.substringBefore('\t').trim() }.filter { it.isNotEmpty() && it.all(Char::isDigit) }.joinToString("\n")
            require(ids.count { it == '\n' } > 500) { "Online list looks incomplete" }
            File(onlineCache.path + ".tmp").apply { writeText(ids); renameTo(onlineCache) }
            publishOnline(ids)
            _online.value.size
        }
    }

    private fun publishOnline(ids: String) {
        _online.value = ids.lineSequence().filter { it.isNotBlank() }.toHashSet()
    }

    suspend fun loadEchoLink(force: Boolean = false): Result<Int> = withContext(Dispatchers.IO) {
        if (_echo.value.isEmpty() && elCache.exists() && echoAgeMs < 3600_000L) publishEcho(elCache.readLines().mapNotNull(::fromTsv))
        if (!force && echoAgeMs < 5 * 60_000L && _echo.value.isNotEmpty()) return@withContext Result.success(_echo.value.size)
        runCatching {
            val list = parseLogins(Http.get(ECHOLINK_URL, 30000))
            require(list.size > 200) { "EchoLink list looks incomplete" }
            File(elCache.path + ".tmp").apply {
                writeText(list.joinToString("\n") { "${it.node}\t${it.callsign}\t${it.info.replace('\t', ' ')}\t${if (it.busy) 1 else 0}" })
                renameTo(elCache)
            }
            publishEcho(list)
            list.size
        }.recoverCatching { e -> if (_echo.value.isNotEmpty()) _echo.value.size else throw e }
    }

    private fun publishEcho(list: List<EchoStation>) {
        echoByNumber = list.associateBy { it.number }
        _echo.value = list
        val k = known()
        if (k.keys.any { it in echoByNumber }) k.keys.filter { it in echoByNumber }.forEach { rememberEchoLink(it) }
    }

    private fun fromTsv(l: String): EchoStation? {
        val p = l.split('\t'); if (p.size < 4) return null
        return station(p[0], p[1], p[2], p[3] == "1")
    }

    private fun station(node: String, call: String, info: String, busy: Boolean): EchoStation? {
        val type = when {
            call.startsWith("*") && call.endsWith("*") -> ElType.CONFERENCE
            call.endsWith("-R") -> ElType.REPEATER
            call.endsWith("-L") -> ElType.LINK
            else -> return null           // individual users: not listed
        }
        if (node.isEmpty() || node.length > 6) return null
        return EchoStation(node, call, info, busy, type)
    }

    /** Rows look like <td>CALL</td><td>info</td><td>ON|BUSY</td><td ..>time</td><td ..>node</td>. */
    private fun parseLogins(html: String): List<EchoStation> {
        val out = ArrayList<EchoStation>(6000)
        val cells = arrayOfNulls<String>(5)
        var i = html.indexOf("<tr")
        while (i >= 0) {
            val end = html.indexOf("</tr>", i).let { if (it < 0) html.length else it }
            var p = i; var n = 0
            while (n < 5) {
                val td = html.indexOf("<td", p); if (td < 0 || td >= end) break
                val gt = html.indexOf('>', td); val close = html.indexOf("</td>", gt)
                if (gt < 0 || close < 0 || close > end) break
                cells[n++] = html.substring(gt + 1, close)
                p = close + 5
            }
            if (n == 5 && (cells[2] == "ON" || cells[2] == "BUSY")) {
                val node = cells[4]!!.trim()
                if (node.isNotEmpty() && node.all(Char::isDigit)) {
                    station(node, unescape(cells[0]!!.trim()), unescape(cells[1]!!.trim().replace(MULTI_SPACE, " ")),
                        cells[2] == "BUSY")?.let(out::add)
                }
            }
            i = html.indexOf("<tr", end)
        }
        return out
    }

    private fun unescape(t: String) =
        if ('&' in t) android.text.Html.fromHtml(t, android.text.Html.FROM_HTML_MODE_LEGACY).toString() else t

    fun searchEchoLink(query: String, type: ElType? = null): List<EchoStation> {
        val all = _echo.value.let { l -> if (type == null) l else l.filter { it.type == type } }
        val q = query.trim().lowercase()
        if (q.isEmpty()) return all.sortedBy { it.callsign }
        val digits = q.removePrefix("el").trim()
        if (digits.isNotEmpty() && digits.all(Char::isDigit)) return all.filter { it.node.startsWith(digits) }.sortedBy { it.node.length }
        val terms = q.split(Regex("\\s+"))
        return all.filter { st -> val hay = "${st.callsign} ${st.info}".lowercase(); terms.all { hay.contains(it) } }
            .sortedBy { if (it.callsign.lowercase().startsWith(q)) 0 else 1 }
    }

    suspend fun load(forceRefresh: Boolean = false): Result<Int> = withContext(Dispatchers.IO) {
        if (_nodes.value.isEmpty() && cache.exists()) publish(cache.readText())
        if (!forceRefresh && ageMs < 24 * 3600_000L && _nodes.value.isNotEmpty()) return@withContext Result.success(_nodes.value.size)
        runCatching {
            val text = Http.get(DB_URL)
            require(text.count { it == '\n' } > 1000) { "Node list looks incomplete" }
            File(cache.path + ".tmp").apply { writeText(text); renameTo(cache) }
            publish(text)
            _nodes.value.size
        }.recoverCatching { e -> if (_nodes.value.isNotEmpty()) _nodes.value.size else throw e }
    }

    private fun publish(text: String) {
        val list = ArrayList<NodeInfo>(45000)
        text.lineSequence().forEach { line ->
            val p = line.split('|')
            if (p.size >= 2 && p[0].isNotBlank() && p[0].all { it.isDigit() }) {
                list += NodeInfo(p[0], p[1].trim(), p.getOrElse(2) { "" }.trim(), p.getOrElse(3) { "" }.trim())
            }
        }
        byNumber = list.associateBy { it.number }
        _nodes.value = list
    }

    /**
     * Numbers typed by the user that are not in the public list but can still be linked:
     * "EL 677857" / "echolink 677857" -> 3677857, an unlisted AllStar number, or a 4-6 digit
     * number that may be an EchoLink node. (The public list has no EchoLink entries.)
     */
    fun directCandidates(query: String): List<String> {
        val t = query.trim().lowercase().replace(" ", "").replace("-", "")
        Regex("^(el|echolink)(\\d{1,6})$").matchEntire(t)?.let { return listOf("3" + it.groupValues[2].padStart(6, '0')) }
        if (t.isEmpty() || !t.all { it.isDigit() } || t.length !in 4..7) return emptyList()
        val out = mutableListOf<String>()
        if (byNumber[t] == null) out += t
        if (t.length <= 6) out += "3" + t.padStart(6, '0')
        return out.filter { byNumber[it] == null }
    }

    fun search(query: String, limit: Int = 300): List<NodeInfo> {
        val q = query.trim()
        val all = _nodes.value
        if (q.isEmpty()) return emptyList()
        if (q.all { it.isDigit() }) {
            val exact = byNumber[q]
            val starts = all.asSequence().filter { it.number.startsWith(q) && it.number != q }.take(limit).toList()
            return listOfNotNull(exact) + starts
        }
        val terms = q.lowercase().split(Regex("\\s+"))
        return all.asSequence().filter { n ->
            val hay = "${n.callsign} ${n.description} ${n.location}".lowercase()
            terms.all { hay.contains(it) }
        }.sortedBy { if (it.callsign.equals(q, true)) 0 else 1 }.take(limit).toList()
    }

    companion object {
        const val DB_URL = "https://allmondb.allstarlink.org/allmondb.php"
        const val ONLINE_URL = "https://stats.allstarlink.org/api/stats/mapData"
        const val ECHOLINK_URL = "https://www.echolink.org/logins.jsp"
    }
}

object Http {
    const val UA = "MobileASLClient/0.1 (Android)"

    fun get(url: String, timeoutMs: Int = 20000): String {
        var u = URL(url)
        repeat(4) {
            val c = (u.openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs; readTimeout = timeoutMs
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Accept", "*/*")
            }
            try {
                when (val code = c.responseCode) {
                    in 200..299 -> return c.inputStream.bufferedReader().use { it.readText() }
                    301, 302, 303, 307, 308 -> u = URL(u, c.getHeaderField("Location"))
                    else -> throw RuntimeException("HTTP $code")
                }
            } finally { c.disconnect() }
        }
        throw RuntimeException("Too many redirects")
    }
}
