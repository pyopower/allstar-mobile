package ovh.adan.allstar.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

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

    fun lookup(number: String): NodeInfo? = byNumber[number]

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
    }
}

object Http {
    const val UA = "AllStarMobile/0.1 (Android)"

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
