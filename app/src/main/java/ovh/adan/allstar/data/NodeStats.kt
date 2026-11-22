package ovh.adan.allstar.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class LinkedNode(val number: String, val mode: Char = 'T')

data class NodeDetails(
    val number: String,
    val callsign: String,
    val frequency: String,
    val affiliation: String,
    val location: String,
    val site: String,
    val keyed: Boolean,
    val links: List<LinkedNode>,
    val online: Boolean,
    val lat: Double?,
    val lon: Double?,
)

/** Live node status from the public stats.allstarlink.org API. */
object NodeStats {
    suspend fun fetch(node: String): Result<NodeDetails> = withContext(Dispatchers.IO) {
        runCatching {
            val root = JSONObject(Http.get("https://stats.allstarlink.org/api/stats/$node", 15000))
            val stats = root.optJSONObject("stats")
            val data = stats?.optJSONObject("data")
            val n = root.optJSONObject("node") ?: stats?.optJSONObject("user_node")
            val server = n?.optJSONObject("server")
            val links = ArrayList<LinkedNode>()
            // "nodes" carries the mode prefix (T transceive, R monitor, C connecting)
            data?.optString("nodes")?.split(',')?.map { it.trim() }?.filter { it.length > 1 }?.forEach {
                val mode = it[0]
                if (mode.isLetter()) links += LinkedNode(it.drop(1), mode) else links += LinkedNode(it)
            }
            if (links.isEmpty()) data?.optJSONArray("links")?.let { a ->
                for (i in 0 until a.length()) links += LinkedNode(a.getString(i))
            }
            val updated = stats?.optString("updated_at").orEmpty()
            NodeDetails(
                number = node,
                callsign = n?.optString("callsign").orEmpty(),
                frequency = n?.optString("node_frequency").orEmpty(),
                affiliation = server?.optString("Affiliation").orEmpty(),
                location = server?.optString("Location").orEmpty(),
                site = server?.optString("SiteName").orEmpty(),
                keyed = data?.optBoolean("keyed") ?: false,
                links = links.filter { it.number.isNotBlank() },
                online = isRecent(updated),
                lat = server?.optString("Latitude")?.toDoubleOrNull(),
                lon = server?.optString("Logitude")?.toDoubleOrNull(),
            )
        }
    }

    /** Stats are posted every minute or so; older than 15 min means the node is offline. */
    private fun isRecent(iso: String): Boolean = runCatching {
        val f = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
        f.timeZone = java.util.TimeZone.getTimeZone("UTC")
        val t = f.parse(iso.take(19))!!.time
        System.currentTimeMillis() - t < 15 * 60_000L
    }.getOrDefault(false)

    /** Parse app_rpt "L" link-list text frames: "L T2000,R3535281,C1234". */
    fun parseLinkList(text: String): List<LinkedNode>? {
        if (!text.startsWith("L ") && text != "L") return null
        return text.drop(1).trim().split(',').map { it.trim() }.filter { it.length > 1 }.map {
            if (it[0].isLetter()) LinkedNode(it.drop(1), it[0]) else LinkedNode(it)
        }
    }
}
