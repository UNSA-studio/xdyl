package www.xdyl.hygge.com

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        LogManager.log("Application started")
    }
}
