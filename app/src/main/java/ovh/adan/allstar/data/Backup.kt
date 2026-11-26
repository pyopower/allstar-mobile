package ovh.adan.allstar.data

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import ovh.adan.allstar.Lang
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Settings file to move the app to another phone or keep a copy.
 * With a password the whole file is encrypted (AES-GCM, PBKDF2) and carries the profile passwords;
 * without one it is plain JSON and the passwords are left out.
 */
object Backup {
    private const val APP = "ovh.adan.allstar"
    private const val ITER = 120_000

    class BadPassword : Exception()
    class NotABackup : Exception()

    fun export(ctx: Context, prefs: Prefs, password: String): String {
        val withSecrets = password.isNotEmpty()
        val profiles = prefs.profiles
        val body = JSONObject()
            .put("profiles", JSONArray(profiles.map { p ->
                JSONObject().put("id", p.id).put("name", p.name).put("host", p.host).put("port", p.port)
                    .put("username", p.username).put("node", p.node).put("callsign", p.callsign).put("allmon", p.allmon)
                    .put("account", p.account).put("permLink", p.permLink).put("permUnlink", p.permUnlink)
                    .apply { if (withSecrets) put("secret", p.secret) }
            }))
            .put("active", prefs.activeProfileId ?: "")
            .put("favorites", JSONArray(prefs.favorites.toList()))
            .put("recents", JSONArray(prefs.recents))
            .put("perm", JSONObject().apply { profiles.forEach { put(it.id, JSONArray(prefs.permanentLinks(it.id).toList())) } })
            .put("settings", JSONObject()
                .put("pttMode", prefs.pttMode.name).put("totWarn", prefs.totWarn.name).put("micGain", prefs.micGain.toDouble())
                .put("volumeKeyPtt", prefs.volumeKeyPtt).put("btPtt", prefs.btPtt).put("btMic", prefs.btMic).put("txTimeout", prefs.txTimeoutSec)
                .put("autoConnect", prefs.autoConnect).put("startOnBoot", prefs.startOnBoot)
                .put("sendCallerId", prefs.sendCallerId).put("keepScreenOn", prefs.keepScreenOn)
                .put("lang", Lang.code(ctx)))
        val out = JSONObject().put("app", APP).put("v", 1)
        if (!withSecrets) return out.put("data", body).toString(2)
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key(password, salt))
        val sealed = c.iv + c.doFinal(body.toString().toByteArray())
        return out.put("enc", "pbkdf2-aes-gcm").put("iter", ITER)
            .put("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .put("sealed", Base64.encodeToString(sealed, Base64.NO_WRAP)).toString(2)
    }

    /** True when the file needs a password to be read. Throws [NotABackup] for other files. */
    fun needsPassword(text: String): Boolean = outer(text).has("sealed")

    /** Restores the file: profiles with the same id are replaced, the others are kept. Returns how many profiles came in. */
    fun import(ctx: Context, prefs: Prefs, text: String, password: String): Int {
        val o = outer(text)
        val body = if (o.has("sealed")) {
            val all = Base64.decode(o.getString("sealed"), Base64.NO_WRAP)
            val k = key(password, Base64.decode(o.getString("salt"), Base64.NO_WRAP), o.optInt("iter", ITER))
            val plain = runCatching {
                Cipher.getInstance("AES/GCM/NoPadding").run {
                    init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, all, 0, 12)); doFinal(all, 12, all.size - 12)
                }
            }.getOrElse { throw BadPassword() }
            JSONObject(String(plain))
        } else o.optJSONObject("data") ?: throw NotABackup()

        val old = prefs.profiles.associateBy { it.id }
        val arr = body.optJSONArray("profiles") ?: JSONArray()
        val incoming = (0 until arr.length()).map { i ->
            val j = arr.getJSONObject(i)
            val p = Profile.fromJson(j.apply { remove("secretEnc") }).copy(account = j.optBoolean("account"))
            // a file without passwords keeps the one already on this phone
            if (!j.has("secret")) p.copy(secret = old[p.id]?.secret.orEmpty()) else p.copy(secret = j.optString("secret"))
        }
        prefs.profiles = prefs.profiles.filter { p -> incoming.none { it.id == p.id } } + incoming
        body.optString("active").takeIf { a -> a.isNotEmpty() && prefs.profiles.any { it.id == a } }?.let { prefs.activeProfileId = it }
        body.optJSONArray("favorites")?.let { a -> prefs.favorites = prefs.favorites + (0 until a.length()).map { a.getString(it) } }
        body.optJSONArray("recents")?.let { a ->
            val r = (0 until a.length()).map { a.getString(it) }
            prefs.recents = (prefs.recents + r).distinct()
        }
        body.optJSONObject("perm")?.let { pm ->
            pm.keys().forEach { id -> pm.optJSONArray(id)?.let { a -> prefs.setPermanentLinks(id, (0 until a.length()).map { a.getString(it) }.toSet()) } }
        }
        body.optJSONObject("settings")?.let { s ->
            if (s.has("pttMode")) runCatching { prefs.pttMode = PttMode.valueOf(s.getString("pttMode")) }
            if (s.has("totWarn")) runCatching { prefs.totWarn = TotWarn.valueOf(s.getString("totWarn")) }
            if (s.has("micGain")) prefs.micGain = s.getDouble("micGain").toFloat().coerceIn(0.5f, 4f)
            if (s.has("volumeKeyPtt")) prefs.volumeKeyPtt = s.getBoolean("volumeKeyPtt")
            if (s.has("btPtt")) prefs.btPtt = s.getBoolean("btPtt")
            if (s.has("btMic")) prefs.btMic = s.getBoolean("btMic")
            if (s.has("txTimeout")) prefs.txTimeoutSec = s.getInt("txTimeout").coerceIn(30, 600)
            if (s.has("autoConnect")) prefs.autoConnect = s.getBoolean("autoConnect")
            if (s.has("startOnBoot")) prefs.startOnBoot = s.getBoolean("startOnBoot")
            if (s.has("sendCallerId")) prefs.sendCallerId = s.getBoolean("sendCallerId")
            if (s.has("keepScreenOn")) prefs.keepScreenOn = s.getBoolean("keepScreenOn")
            if (s.has("lang")) Lang.set(ctx, s.getString("lang"))
        }
        return incoming.size
    }

    private fun outer(text: String): JSONObject {
        val o = runCatching { JSONObject(text) }.getOrNull()
        if (o == null || o.optString("app") != APP) throw NotABackup()
        return o
    }

    private fun key(password: String, salt: ByteArray, iter: Int = ITER): SecretKeySpec {
        val f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
        val raw = f.generateSecret(PBEKeySpec(password.toCharArray(), salt, iter, 256)).encoded
        return SecretKeySpec(raw, "AES")
    }
}
