package org.olcbox.app

import android.app.Application
import android.content.Context
import org.olcbox.app.vpn.AndroidNativeTls

class App : Application() {
    companion object {
        lateinit var appContext: Context
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        AndroidNativeTls.initialize(base)
    }

    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext
    }
}
