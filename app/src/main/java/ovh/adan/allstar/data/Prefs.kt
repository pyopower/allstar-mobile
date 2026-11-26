package ovh.adan.allstar.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** One "my node" login: the AllStar node this phone connects to over IAX. */
data class Profile(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val host: String = "",
    val port: Int = 4569,
    val username: String = "",
    val secret: String = "",
    val node: String = "",
    val callsign: String = "",
    /** Allmon3 base URL (optional): used to show which link is talking. Empty = look for it next to the host. */
    val allmon: String = "",
    /**
     * AllStarLink account (Web Transceiver) profile: no own node; [callsign] + portal password
     * in [secret], and [node] is the node to call (changed from the Nodes tab).
     */
    val account: Boolean = false,
    /** DTMF codes for permanent links (ilink 13 / ilink 11). Stock ASL3 has none: the sysop adds them to rpt.conf. */
    val permLink: String = DEFAULT_PERM_LINK,
    val permUnlink: String = DEFAULT_PERM_UNLINK,
) {
    val isComplete get() = if (account) callsign.isNotBlank() && secret.isNotEmpty() && node.isNotBlank()
        else host.isNotBlank() && username.isNotBlank() && node.isNotBlank()
    /** Account profile with the credentials filled in (the node can be chosen later). */
    val accountReady get() = account && callsign.isNotBlank() && secret.isNotEmpty()
    val title get() = name.ifBlank { if (account) callsign else "Node $node" }

    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("host", host).put("port", port)
        .put("username", username).put("node", node).put("callsign", callsign).put("allmon", allmon)
        .put("permLink", permLink).put("permUnlink", permUnlink)
        .apply {
            // the portal password is the user's account: keep it encrypted with an Android Keystore key
            if (account) put("account", true).put("secretEnc", AslAccount.seal(secret)) else put("secret", secret)
        }

    companion object {
        /** The codes the ASL3 rpt.conf template suggests (commented out there). */
        const val DEFAULT_PERM_LINK = "*813"
        const val DEFAULT_PERM_UNLINK = "*811"

        fun fromJson(o: JSONObject) = Profile(
            o.optString("id", UUID.randomUUID().toString()), o.optString("name"), o.optString("host"),
            o.optInt("port", 4569), o.optString("username"), o.optString("secret"),
            o.optString("node"), o.optString("callsign"), o.optString("allmon"),
            permLink = o.optString("permLink", DEFAULT_PERM_LINK), permUnlink = o.optString("permUnlink", DEFAULT_PERM_UNLINK),
        ).let { p -> if (o.optBoolean("account")) p.copy(account = true, secret = AslAccount.open(o.optString("secretEnc"))) else p }
    }
}

enum class PttMode { HOLD, TOGGLE }

/** How the app warns that the transmit timeout is about to cut the transmission. */
enum class TotWarn { VIBRATE, BEEP, NONE }

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("allstar", Context.MODE_PRIVATE)

    var profiles: List<Profile>
        get() = runCatching {
            val a = JSONArray(sp.getString("profiles", "[]"))
            (0 until a.length()).map { Profile.fromJson(a.getJSONObject(it)) }
        }.getOrDefault(emptyList())
        set(v) { sp.edit().putString("profiles", JSONArray(v.map { it.toJson() }).toString()).apply() }

    var activeProfileId: String?
        get() = sp.getString("active", null)
        set(v) { sp.edit().putString("active", v).apply() }

    val activeProfile: Profile? get() = profiles.let { l -> l.firstOrNull { it.id == activeProfileId } ?: l.firstOrNull() }

    var favorites: Set<String>
        get() = sp.getStringSet("favorites", emptySet())!!.toSet()
        set(v) { sp.edit().putStringSet("favorites", v).apply() }

    /** Nodes this app linked permanently, per profile: unlinking them needs the permanent code. */
    fun permanentLinks(profileId: String): Set<String> = sp.getStringSet("perm_$profileId", emptySet())!!.toSet()
    fun setPermanentLinks(profileId: String, nodes: Set<String>) { sp.edit().putStringSet("perm_$profileId", nodes).apply() }

    var recents: List<String>
        get() = sp.getString("recents", "")!!.split(',').filter { it.isNotBlank() }
        set(v) { sp.edit().putString("recents", v.take(20).joinToString(",")).apply() }

    var pttMode: PttMode
        get() = runCatching { PttMode.valueOf(sp.getString("pttMode", "HOLD")!!) }.getOrDefault(PttMode.HOLD)
        set(v) { sp.edit().putString("pttMode", v.name).apply() }
    var totWarn: TotWarn
        get() = runCatching { TotWarn.valueOf(sp.getString("totWarn", "VIBRATE")!!) }.getOrDefault(TotWarn.VIBRATE)
        set(v) { sp.edit().putString("totWarn", v.name).apply() }

    var micGain: Float
        get() = sp.getFloat("micGain", 1.0f)
        set(v) { sp.edit().putFloat("micGain", v).apply() }

    var volumeKeyPtt: Boolean
        get() = sp.getBoolean("volumeKeyPtt", false)
        set(v) { sp.edit().putBoolean("volumeKeyPtt", v).apply() }

    var txTimeoutSec: Int
        get() = sp.getInt("txTimeout", 180)
        set(v) { sp.edit().putInt("txTimeout", v).apply() }

    var autoConnect: Boolean
        get() = sp.getBoolean("autoConnect", false)
        set(v) { sp.edit().putBoolean("autoConnect", v).apply() }

    var startOnBoot: Boolean
        get() = sp.getBoolean("startOnBoot", false)
        set(v) { sp.edit().putBoolean("startOnBoot", v).apply() }

    /** Random id sent with App Authentication token requests (AllStarLink uses it for rate limiting). */
    val clientId: String get() = sp.getString("clientId", null) ?: UUID.randomUUID().toString().also {
        sp.edit().putString("clientId", it).apply()
    }

    /** Send our callsign as a Ham CallerID burst at the start and end of each transmission. */
    var sendCallerId: Boolean
        get() = sp.getBoolean("sendCallerId", true)
        set(v) { sp.edit().putBoolean("sendCallerId", v).apply() }

    var keepScreenOn: Boolean
        get() = sp.getBoolean("keepScreenOn", false)
        set(v) { sp.edit().putBoolean("keepScreenOn", v).apply() }
}
