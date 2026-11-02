package dev.ayoub.wristle.relay

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dev.ayoub.wristle.WristleApplication
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/** Receives the bridge's pushes. Runs on a background thread with a short time budget, hence the timeout. */
class WristleMessagingService : FirebaseMessagingService() {
    private val container get() = (application as WristleApplication).container

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        when (data["type"]) {
            // P2-M2a relay test: plaintext ping id.
            "ping" -> data["id"]?.let { id -> handle { container.relayTestRepository.answerPing(id) } }
            // Encrypted mailbox: the push only says which pairing has news.
            "msg" -> data["pairingId"]?.let { id -> handle { container.pairingRepository.handleIncoming(id) } }
        }
    }

    override fun onNewToken(token: String) {
        handle { container.relayTestRepository.tokenChanged(token) }
        handle { container.pairingRepository.tokenChanged(token) }
    }

    private fun handle(work: suspend () -> Unit) {
        runBlocking { withTimeoutOrNull(HANDLE_TIMEOUT_MS) { runCatching { work() } } }
    }

    private companion object {
        const val HANDLE_TIMEOUT_MS = 15_000L
    }
}
