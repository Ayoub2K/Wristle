package dev.ayoub.wristle

import android.app.Application

class WristleApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}
