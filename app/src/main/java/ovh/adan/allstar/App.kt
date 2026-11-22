package ovh.adan.allstar

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Radio.init(this)
    }
}
