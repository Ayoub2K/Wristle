package dev.ayoub.wristle

import android.app.Application
import dev.ayoub.wristle.relay.RelayFirebase

class WristleApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        RelayFirebase.init(this)
    }
}
