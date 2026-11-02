package dev.ayoub.wristle.relay

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dev.ayoub.wristle.WristleApplication
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/** Receives the bridge's pushes. Runs on a background thread with a short time budget, hence the timeout. */
class WristleMessagingService : FirebaseMessagingService() {
    private val relayTest get() = (application as WristleApplication).container.relayTestRepository

    override fun onMessageReceived(message: RemoteMessage) {
        val id = message.data["id"] ?: return
        if (message.data["type"] != "ping") return
        runBlocking { withTimeoutOrNull(HANDLE_TIMEOUT_MS) { runCatching { relayTest.answerPing(id) } } }
    }

    override fun onNewToken(token: String) {
        runBlocking { withTimeoutOrNull(HANDLE_TIMEOUT_MS) { runCatching { relayTest.tokenChanged(token) } } }
    }

    private companion object {
        const val HANDLE_TIMEOUT_MS = 15_000L
    }
}
