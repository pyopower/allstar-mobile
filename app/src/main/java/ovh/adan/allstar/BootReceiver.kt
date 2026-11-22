package ovh.adan.allstar

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** "Start when the phone is switched on": connects to the last used node in the background. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        Radio.init(context)
        if (!Radio.prefs.startOnBoot || Radio.prefs.activeProfile?.isComplete != true) return
        runCatching { LinkService.startOnBoot(context) }
    }
}
