package dev.ayoub.wristle.relay

import android.os.Build
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

sealed interface RelayTestState {
    data object NotConfigured : RelayTestState

    data object Idle : RelayTestState

    data object Connecting : RelayTestState

    data class Ready(
        val watchId: String,
        val pings: Int = 0,
        val lastPingAt: Long? = null,
    ) : RelayTestState

    data class Failed(
        val message: String,
    ) : RelayTestState
}

/**
 * P2-M2a relay test, watch side: sign in anonymously, register the FCM token in Firestore,
 * and answer the bridge's ping pushes by writing a pong back.
 */
class RelayTestRepository(
    private val scope: CoroutineScope,
) {
    private val mutableState =
        MutableStateFlow<RelayTestState>(if (RelayFirebase.configured) RelayTestState.Idle else RelayTestState.NotConfigured)
    val state: StateFlow<RelayTestState> = mutableState.asStateFlow()

    fun start() {
        if (!RelayFirebase.configured) return
        mutableState.value = RelayTestState.Connecting
        scope.launch {
            try {
                val uid = signIn()
                val token = FirebaseMessaging.getInstance().token.await()
                document(uid)
                    .set(
                        mapOf("fcmToken" to token, "device" to Build.MODEL, "updatedAt" to FieldValue.serverTimestamp()),
                        SetOptions.merge(),
                    ).await()
                mutableState.value = RelayTestState.Ready(uid)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableState.value = RelayTestState.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    suspend fun answerPing(id: String) {
        val uid = signIn()
        document(uid).set(mapOf("pongId" to id, "pongAt" to FieldValue.serverTimestamp()), SetOptions.merge()).await()
        mutableState.update {
            if (it is RelayTestState.Ready) it.copy(pings = it.pings + 1, lastPingAt = System.currentTimeMillis()) else it
        }
    }

    suspend fun tokenChanged(token: String) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        document(uid).set(mapOf("fcmToken" to token, "updatedAt" to FieldValue.serverTimestamp()), SetOptions.merge()).await()
    }

    private suspend fun signIn(): String {
        val auth = FirebaseAuth.getInstance()
        auth.currentUser?.let { return it.uid }
        return checkNotNull(auth.signInAnonymously().await().user).uid
    }

    private fun document(uid: String) = FirebaseFirestore.getInstance().collection(TESTS).document(uid)

    private companion object {
        const val TESTS = "tests"
    }
}
