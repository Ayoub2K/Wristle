package dev.ayoub.wristle.relay

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessaging
import dev.ayoub.wristle.BuildConfig
import kotlinx.coroutines.tasks.await
import kotlin.concurrent.thread

/**
 * Sets up the owner's Firebase project from build config (local.properties) instead of google-services.json,
 * so the public repo builds without it and the relay is simply off when it isn't configured.
 */
object RelayFirebase {
    @Volatile private var ready = false
    private var appContext: Context? = null

    val configured: Boolean
        get() =
            listOf(
                BuildConfig.FIREBASE_PROJECT_ID,
                BuildConfig.FIREBASE_APP_ID,
                BuildConfig.FIREBASE_API_KEY,
                BuildConfig.FIREBASE_SENDER_ID,
            ).all { it.isNotBlank() }

    /** Anonymous sign-in; the user id stays the same until the app's data is cleared. */
    suspend fun signIn(): String {
        val auth = auth()
        auth.currentUser?.let { return it.uid }
        return checkNotNull(auth.signInAnonymously().await().user).uid
    }

    fun auth(): FirebaseAuth {
        ensureInitialized()
        return FirebaseAuth.getInstance()
    }

    fun firestore(): FirebaseFirestore {
        ensureInitialized()
        return FirebaseFirestore.getInstance()
    }

    fun messaging(): FirebaseMessaging {
        ensureInitialized()
        return FirebaseMessaging.getInstance()
    }

    /** Setting up Firebase takes up to half a second on the watch, so it no longer holds up the app's first frame. */
    fun initInBackground(context: Context) {
        appContext = context.applicationContext
        if (configured) thread(name = "firebase-init") { ensureInitialized() }
    }

    /** Waits for (or does) the setup; every Firebase call goes through here. */
    fun ensureInitialized() {
        if (ready || !configured) return
        synchronized(this) {
            if (ready) return
            val context = checkNotNull(appContext) { "initInBackground was not called" }
            if (FirebaseApp.getApps(context).isEmpty()) {
                FirebaseApp.initializeApp(
                    context,
                    FirebaseOptions
                        .Builder()
                        .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
                        .setApplicationId(BuildConfig.FIREBASE_APP_ID)
                        .setApiKey(BuildConfig.FIREBASE_API_KEY)
                        .setGcmSenderId(BuildConfig.FIREBASE_SENDER_ID)
                        .build(),
                )
            }
            ready = true
        }
    }
}
