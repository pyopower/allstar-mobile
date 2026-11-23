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
) {
    val isComplete get() = if (account) callsign.isNotBlank() && secret.isNotEmpty() && node.isNotBlank()
        else host.isNotBlank() && username.isNotBlank() && node.isNotBlank()
    /** Account profile with the credentials filled in (the node can be chosen later). */
    val accountReady get() = account && callsign.isNotBlank() && secret.isNotEmpty()
    val title get() = name.ifBlank { if (account) callsign else "Node $node" }

    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("host", host).put("port", port)
        .put("username", username).put("node", node).put("callsign", callsign).put("allmon", allmon)
        .apply {
            // the portal password is the user's account: keep it encrypted with an Android Keystore key
            if (account) put("account", true).put("secretEnc", AslAccount.seal(secret)) else put("secret", secret)
        }

    companion object {
        fun fromJson(o: JSONObject) = Profile(
            o.optString("id", UUID.randomUUID().toString()), o.optString("name"), o.optString("host"),
            o.optInt("port", 4569), o.optString("username"), o.optString("secret"),
            o.optString("node"), o.optString("callsign"), o.optString("allmon"),
        ).let { p -> if (o.optBoolean("account")) p.copy(account = true, secret = AslAccount.open(o.optString("secretEnc"))) else p }
    }
}

enum class PttMode { HOLD, TOGGLE }

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

    var recents: List<String>
        get() = sp.getString("recents", "")!!.split(',').filter { it.isNotBlank() }
        set(v) { sp.edit().putString("recents", v.take(20).joinToString(",")).apply() }

    var pttMode: PttMode
        get() = runCatching { PttMode.valueOf(sp.getString("pttMode", "HOLD")!!) }.getOrDefault(PttMode.HOLD)
        set(v) { sp.edit().putString("pttMode", v.name).apply() }

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
