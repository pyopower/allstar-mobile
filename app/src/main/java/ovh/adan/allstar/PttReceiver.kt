package ovh.adan.allstar

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** PTT from hardware buttons / bridge apps (e.g. PTT Bridge, rugged phones). */
class PttReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Radio.init(context)
        when (intent.action) {
            "android.intent.action.PTT.down", "ovh.adan.allstar.PTT_DOWN" -> Radio.pttDown()
            "android.intent.action.PTT.up", "ovh.adan.allstar.PTT_UP" -> Radio.pttUp()
        }
    }
}
