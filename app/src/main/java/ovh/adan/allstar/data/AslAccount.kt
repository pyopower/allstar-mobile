package ovh.adan.allstar.data

import android.content.Context
import android.net.ConnectivityManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URL
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.random.Random

/**
 * "Web Transceiver" access: call any AllStarLink node straight from the app with an
 * allstarlink.org portal account, without owning a node.
 *
 * The node's stock ASL dialplan ([allstar-public], IAX user allstar-public / secret allstar)
 * checks the caller-ID name with AllStarLink before running rpt(node|X):
 *  - legacy WT: the name is the account's WT token (www.allstarlink.org/api/v2/auth-wt-legacy.php);
 *    accepted by every node today, retired around June 2027.
 *  - ASL002 App Authentication: the name is "TOKEN/CALLSIGN" with a one-time token from
 *    api.allstarlink.org/v1/appauth/request; accepted by nodes with the updated dialplan.
 */
object AslAccount {
    const val IAX_USER = "allstar-public"
    const val IAX_SECRET = "allstar"
    const val IAX_EXTEN = "s"

    enum class Format { LEGACY, APP }

    class AuthException(msg: String, val badCredentials: Boolean) : Exception(msg)

    private const val LEGACY_URL = "https://www.allstarlink.org/api/v2/auth-wt-legacy.php"
    private const val APP_URL = "https://api.allstarlink.org/v1/appauth/request"
    private const val UA = "MobileASLClient/1.0 (Android)"

    /** Legacy tokens only change with the password: keep the last one for this run. */
    @Volatile private var legacyCache: Triple<String, String, String>? = null

    /** Caller-ID name for the IAX NEW in the given format. Blocking (network). */
    fun callerName(format: Format, callsign: String, password: String, clientId: String): String {
        val user = callsign.trim().uppercase()
        return when (format) {
            Format.LEGACY -> {
                legacyCache?.let { (u, p, t) -> if (u == user && p == password) return t }
                val o = post(LEGACY_URL, JSONObject().put("username", user).put("password", password))
                val tok = o.optString("token")
                if (o.optInt("auth", 0) != 1 || tok.isBlank())
                    throw AuthException(o.optString("msg").ifBlank { "login failed" }, badCredentials = o.optString("msg").contains("login", true))
                legacyCache = Triple(user, password, tok)
                tok
            }
            Format.APP -> {
                val o = post(APP_URL, JSONObject().put("username", user).put("password", password)
                    .put("client-id", clientId).put("request-id", Random.nextLong(1, Long.MAX_VALUE).toString(16)))
                val tok = o.optString("auth-token")
                if (!o.optBoolean("status", false) || tok.isBlank() || tok == "null")
                    throw AuthException(if (o.optInt("_http") == 429) "AllStarLink: too many requests" else "login failed",
                        badCredentials = o.optInt("_http") != 429)
                "$tok/$user"
            }
        }
    }

    fun forgetLegacy() { legacyCache = null }

    private fun post(url: String, body: JSONObject): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"; c.connectTimeout = 10_000; c.readTimeout = 15_000; c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("Accept", "application/json")
            c.setRequestProperty("User-Agent", UA)
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = c.responseCode
            val text = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            val o = runCatching { JSONObject(text) }.getOrElse {
                if (code == 404 || code == 410) throw AuthException("AllStarLink: service not available ($code)", false)
                throw java.io.IOException("AllStarLink: HTTP $code")
            }
            return o.put("_http", code)
        } finally { c.disconnect() }
    }

    // ------------------------------------------------------------ node address

    /**
     * Where node [node] listens: the TXT record of <node>.nodes.allstarlink.org ("IP=…", "PT=…"),
     * falling back to its A record and the default IAX port.
     */
    fun resolveNode(ctx: Context, node: String): Pair<String, Int> {
        val name = "$node.nodes.allstarlink.org"
        val txt = runCatching { queryTxt(ctx, name) }.getOrDefault(emptyList())
        val ip = txt.firstOrNull { it.startsWith("IP=") }?.substring(3)?.trim()
        val pt = txt.firstOrNull { it.startsWith("PT=") }?.substring(3)?.trim()?.toIntOrNull()
        if (!ip.isNullOrBlank()) return ip to (pt ?: 4569)
        val a = InetAddress.getByName(name).hostAddress ?: throw java.io.IOException("node $node not found")
        return a to 4569
    }

    /** Minimal DNS TXT lookup over UDP (Android has no TXT API before API 29). */
    private fun queryTxt(ctx: Context, name: String): List<String> {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val servers = cm?.getLinkProperties(cm.activeNetwork)?.dnsServers.orEmpty() +
            listOf(InetAddress.getByName("1.1.1.1"), InetAddress.getByName("8.8.8.8"))
        val id = Random.nextInt(0, 0xffff)
        val q = ByteArrayOutputStream().apply {
            write(byteArrayOf((id shr 8).toByte(), id.toByte(), 1, 0, 0, 1, 0, 0, 0, 0, 0, 0))
            name.split('.').forEach { l -> write(l.length); write(l.toByteArray()) }
            write(byteArrayOf(0, 0, 16, 0, 1))  // QTYPE TXT, QCLASS IN
        }.toByteArray()
        for (srv in servers) {
            val r = runCatching {
                DatagramSocket().use { s ->
                    s.soTimeout = 2500
                    s.send(DatagramPacket(q, q.size, InetSocketAddress(srv, 53)))
                    val buf = ByteArray(1500); val p = DatagramPacket(buf, buf.size); s.receive(p)
                    parseTxt(buf.copyOf(p.length), id)
                }
            }.getOrNull()
            if (!r.isNullOrEmpty()) return r
        }
        return emptyList()
    }

    private fun parseTxt(b: ByteArray, id: Int): List<String> {
        fun u16(i: Int) = ((b[i].toInt() and 0xff) shl 8) or (b[i + 1].toInt() and 0xff)
        if (b.size < 12 || u16(0) != id) return emptyList()
        val qd = u16(4); val an = u16(6)
        var i = 12
        fun skipName() {
            while (i < b.size) {
                val l = b[i].toInt() and 0xff
                if (l == 0) { i++; return }
                if (l and 0xc0 == 0xc0) { i += 2; return }
                i += l + 1
            }
        }
        repeat(qd) { skipName(); i += 4 }
        val out = ArrayList<String>()
        repeat(an) {
            skipName()
            if (i + 10 > b.size) return out
            val type = u16(i); val rdlen = u16(i + 8); i += 10
            val end = i + rdlen
            if (type == 16) {
                var j = i
                while (j < end && j < b.size) { val l = b[j].toInt() and 0xff; out.add(String(b, j + 1, minOf(l, b.size - j - 1))); j += l + 1 }
            }
            i = end
        }
        return out
    }

    // ------------------------------------------------------------ password at rest

    private const val KEY_ALIAS = "asl-portal"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }

    fun seal(plain: String): String {
        if (plain.isEmpty()) return ""
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key())
        return "v1:" + Base64.encodeToString(c.iv + c.doFinal(plain.toByteArray()), Base64.NO_WRAP)
    }

    /** Empty if it cannot be decrypted (e.g. restored onto another phone): the user types it again. */
    fun open(sealed: String): String = runCatching {
        if (!sealed.startsWith("v1:")) return@runCatching ""
        val all = Base64.decode(sealed.substring(3), Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, all, 0, 12))
        String(c.doFinal(all, 12, all.size - 12))
    }.getOrDefault("")
}
