package ovh.adan.allstar

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/** App language, selectable in Settings ("" = follow the system). Applied to the screens, the service and the log. */
object Lang {
    /** code -> name in its own language */
    val OPTIONS = listOf("en" to "English", "es" to "Español", "pt" to "Português", "it" to "Italiano",
        "fr" to "Français", "de" to "Deutsch")

    private fun sp(ctx: Context) = ctx.getSharedPreferences("allstar", Context.MODE_PRIVATE)
    fun code(ctx: Context): String = sp(ctx).getString("lang", "") ?: ""
    fun set(ctx: Context, code: String) { sp(ctx).edit().putString("lang", code).commit(); cached = null }

    @Volatile private var cached: Pair<String, Context>? = null

    /** System locale at start-up, to go back to it when the user picks "System language" again. */
    private val systemLocale: Locale = Locale.getDefault()

    fun wrap(base: Context): Context {
        val c = code(base)
        // also dates, "updated 5 minutes ago", number formats
        Locale.setDefault(if (c.isEmpty()) systemLocale else Locale(c))
        if (c.isEmpty()) return base
        val cfg = Configuration(base.resources.configuration).apply { setLocale(Locale(c)) }
        return base.createConfigurationContext(cfg)
    }

    /** Localized context for strings outside an Activity (log lines, toasts). */
    fun strings(app: Context): Context {
        val c = code(app)
        cached?.let { if (it.first == c) return it.second }
        return wrap(app).also { cached = c to it }
    }
}
