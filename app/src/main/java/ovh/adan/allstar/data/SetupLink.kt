package ovh.adan.allstar.data

import android.net.Uri

/** `mobileasl://add?host=..&port=..&user=..&secret=..&node=..` as printed by tools/setup-node.sh. */
object SetupLink {
    const val SCRIPT_URL = "https://raw.githubusercontent.com/pyopower/allstar-mobile/main/tools/setup-node.sh"
    const val COMMAND = "curl -fsSL $SCRIPT_URL | sudo bash"

    fun parse(text: String?): Profile? {
        val raw = text?.let { Regex("mobileasl://add\\?\\S+").find(it)?.value } ?: return null
        val u = runCatching { Uri.parse(raw) }.getOrNull() ?: return null
        val host = u.getQueryParameter("host")?.trim().orEmpty()
        val node = u.getQueryParameter("node")?.trim().orEmpty()
        val user = u.getQueryParameter("user")?.trim().orEmpty()
        if (host.isEmpty() || user.isEmpty() || !node.all { it.isDigit() } || node.isEmpty()) return null
        val port = u.getQueryParameter("port")?.toIntOrNull()?.takeIf { it in 1..65535 } ?: 4569
        return Profile(host = host, port = port, username = user, secret = u.getQueryParameter("secret").orEmpty(), node = node)
    }
}
