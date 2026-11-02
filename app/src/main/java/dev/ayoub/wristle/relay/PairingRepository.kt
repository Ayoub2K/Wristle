package dev.ayoub.wristle.relay

import android.content.Context
import androidx.core.content.edit
import com.anthropic.core.jsonMapper
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.messaging.FirebaseMessaging
import dev.ayoub.wristle.data.SecretCipher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

sealed interface PairingState {
    data object NotConfigured : PairingState

    data object Unpaired : PairingState

    data object Working : PairingState

    /** Waiting for `wristle-bridge pair <code>` on the computer. */
    data class ShowingCode(
        val code: String,
    ) : PairingState

    /** Both screens show [sas]; the user confirms on each. [waiting] once the watch said yes. */
    data class Confirming(
        val sas: String,
        val waiting: Boolean = false,
    ) : PairingState

    data object CancelledOnComputer : PairingState

    data class Paired(
        val computerName: String?,
    ) : PairingState

    data class Failed(
        val message: String,
    ) : PairingState
}

/**
 * Watch side of pairing with the computer bridge and of the encrypted mailbox (see bridge/src/pairing.ts
 * for the Firestore layout). Keys are stored encrypted with the Android Keystore through [SecretCipher].
 */
class PairingRepository(
    context: Context,
    private val cipher: SecretCipher,
    private val scope: CoroutineScope,
) {
    private class Stored(
        val pairingId: String,
        val w2bKey: ByteArray,
        val b2wKey: ByteArray,
        val nextSeq: Long,
        val lastSeqIn: Long,
        val computerName: String?,
    )

    private class Pending(
        val pairingId: String,
        val code: String,
        val keyPair: PairingCrypto.KeyPair,
        var keys: PairingCrypto.SessionKeys? = null,
        var confirmed: Boolean = false,
    )

    private val prefs = context.getSharedPreferences("pairing", Context.MODE_PRIVATE)
    private val db get() = FirebaseFirestore.getInstance()
    private val mailbox = Mutex()
    private var pending: Pending? = null
    private var listener: ListenerRegistration? = null

    private val mutableState = MutableStateFlow(initialState())
    val state: StateFlow<PairingState> = mutableState.asStateFlow()

    private val mutableMessages = MutableSharedFlow<JsonNode>(extraBufferCapacity = MESSAGE_BUFFER)

    /** Opened messages from the bridge that pairing itself doesn't handle (projects, session progress, …). */
    val messages: SharedFlow<JsonNode> = mutableMessages.asSharedFlow()

    private fun initialState(): PairingState =
        when {
            !RelayFirebase.configured -> PairingState.NotConfigured
            else -> load()?.let { PairingState.Paired(it.computerName) } ?: PairingState.Unpaired
        }

    /** Registers a fresh code and keys, then waits for the bridge to answer with its public key. */
    fun startPairing() {
        if (!RelayFirebase.configured || load() != null) return
        cancelPairing()
        mutableState.value = PairingState.Working
        scope.launch {
            try {
                val uid = RelayFirebase.signIn()
                val keyPair = PairingCrypto.generateKeyPair()
                val pairingId = UUID.randomUUID().toString()
                val ref = pairingDoc(pairingId)
                ref
                    .set(
                        mapOf(
                            "watchUid" to uid,
                            "watchPublic" to base64(keyPair.publicKey),
                            "fcmToken" to FirebaseMessaging.getInstance().token.await(),
                            "createdAt" to FieldValue.serverTimestamp(),
                        ),
                    ).await()
                val code = registerCode(uid, pairingId)
                val started = Pending(pairingId, code, keyPair)
                pending = started
                mutableState.value = PairingState.ShowingCode(code.chunked(4).joinToString("-"))
                listener = ref.addSnapshotListener { snapshot, error -> onPairingDocChanged(started, snapshot?.data, error) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableState.value = PairingState.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    // A code that already exists can't be overwritten (rules allow create only), so retry with another.
    private suspend fun registerCode(
        uid: String,
        pairingId: String,
    ): String {
        repeat(CODE_ATTEMPTS - 1) {
            runCatching { return createCode(uid, pairingId) }
        }
        return createCode(uid, pairingId)
    }

    private suspend fun createCode(
        uid: String,
        pairingId: String,
    ): String {
        val code = (1..CODE_LENGTH).map { CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)] }.joinToString("")
        db
            .collection(CODES)
            .document(code)
            .set(mapOf("watchUid" to uid, "pairingId" to pairingId, "expiresAt" to System.currentTimeMillis() + CODE_TTL_MS))
            .await()
        return code
    }

    private fun onPairingDocChanged(
        started: Pending,
        data: Map<String, Any>?,
        error: Exception?,
    ) {
        if (pending !== started) return
        when {
            error != null -> fail(error.message ?: "relay error")
            data == null -> {
                stopListening()
                pending = null
                mutableState.value = PairingState.CancelledOnComputer
            }
            started.confirmed && data["bridgeConfirmed"] == true -> finish(started)
            started.keys == null && data["bridgePublic"] is String -> {
                val bridgePublic = Base64.getDecoder().decode(data["bridgePublic"] as String)
                val keys =
                    try {
                        PairingCrypto.deriveSessionKeys(started.keyPair.privateKey, bridgePublic, started.keyPair.publicKey, bridgePublic)
                    } catch (e: CryptoException) {
                        fail(e.message ?: "invalid key")
                        return
                    }
                started.keys = keys
                mutableState.value = PairingState.Confirming(keys.sas)
            }
        }
    }

    /** The user saw the same security code on both screens; the pairing counts once the computer agrees too. */
    fun confirm() {
        val started = pending ?: return
        val keys = started.keys ?: return
        started.confirmed = true
        mutableState.value = PairingState.Confirming(keys.sas, waiting = true)
        scope.launch {
            runCatching { pairingDoc(started.pairingId).update("watchConfirmed", true).await() }
                .onFailure { fail(it.message ?: "relay error") }
        }
    }

    private fun finish(started: Pending) {
        val keys = started.keys ?: return
        stopListening()
        pending = null
        save(Stored(started.pairingId, keys.w2b, keys.b2w, nextSeq = 1, lastSeqIn = 0, computerName = null))
        mutableState.value = PairingState.Paired(null)
        // The bridge's hello may have arrived before the pairing was stored.
        scope.launch { runCatching { handleIncoming(started.pairingId) } }
    }

    /** The codes differ: tell the bridge, which then deletes the pairing. */
    fun reject() {
        val started = pending ?: return
        cancelPairing()
        mutableState.value = PairingState.Unpaired
        scope.launch { runCatching { pairingDoc(started.pairingId).update("watchConfirmed", false).await() } }
        startPairing()
    }

    /** Leaving the screen before pairing finished: remove the code and the half-made pairing. */
    fun cancelPairing() {
        val started = pending ?: return
        stopListening()
        pending = null
        if (mutableState.value !is PairingState.Paired) mutableState.value = PairingState.Unpaired
        scope.launch {
            runCatching {
                db
                    .collection(CODES)
                    .document(started.code)
                    .delete()
                    .await()
            }
            if (started.keys == null) runCatching { pairingDoc(started.pairingId).delete().await() }
        }
    }

    fun unpair() {
        val stored = load() ?: return
        prefs.edit { clear() }
        mutableState.value = PairingState.Unpaired
        scope.launch {
            runCatching {
                val ref = pairingDoc(stored.pairingId)
                for (direction in listOf(PairingCrypto.B2W, PairingCrypto.W2B)) {
                    ref
                        .collection(direction)
                        .get()
                        .await()
                        .documents
                        .forEach { it.reference.delete().await() }
                }
                ref.delete().await()
            }
        }
    }

    /** Called for the bridge's wake-up push: fetch, open and answer everything new in the mailbox. */
    suspend fun handleIncoming(pairingId: String) =
        mailbox.withLock {
            var stored = load()?.takeIf { it.pairingId == pairingId } ?: return@withLock
            val inbox =
                pairingDoc(pairingId)
                    .collection(PairingCrypto.B2W)
                    .whereGreaterThan("seq", stored.lastSeqIn)
                    .orderBy("seq")
                    .get()
                    .await()
            for (doc in inbox.documents) {
                val envelope = Envelope(doc.getLong("seq") ?: continue, doc.getString("n").orEmpty(), doc.getString("c").orEmpty())
                doc.reference.delete()
                val message =
                    try {
                        PairingCrypto.open(stored.b2wKey, pairingId, PairingCrypto.B2W, envelope, stored.lastSeqIn)
                    } catch (e: CryptoException) {
                        continue
                    }
                stored = stored.copy(lastSeqIn = envelope.seq)
                save(stored)
                stored = handle(stored, message)
            }
        }

    private suspend fun handle(
        stored: Stored,
        message: JsonNode,
    ): Stored =
        when (message["type"].asText()) {
            "hello" -> {
                val updated = stored.copy(computerName = message["name"]?.asText())
                save(updated)
                mutableState.update { if (it is PairingState.Paired) it.copy(computerName = updated.computerName) else it }
                updated
            }
            "ping" -> send(stored, PairingCrypto.message("pong").put("id", message["id"]?.asText()))
            else -> {
                mutableMessages.tryEmit(message)
                stored
            }
        }

    /** Seals [message] and puts it in the bridge's mailbox; the bridge daemon listens there. */
    suspend fun sendToBridge(message: ObjectNode) {
        mailbox.withLock {
            val stored = checkNotNull(load()) { "not paired" }
            send(stored, message)
        }
    }

    private suspend fun send(
        stored: Stored,
        message: ObjectNode,
    ): Stored {
        val seq = stored.nextSeq
        val envelope = PairingCrypto.seal(stored.w2bKey, stored.pairingId, PairingCrypto.W2B, seq, message)
        val updated = stored.copy(nextSeq = seq + 1)
        save(updated)
        pairingDoc(stored.pairingId)
            .collection(PairingCrypto.W2B)
            .document(seq.toString().padStart(SEQ_DIGITS, '0'))
            .set(mapOf("seq" to envelope.seq, "n" to envelope.n, "c" to envelope.c))
            .await()
        return updated
    }

    suspend fun tokenChanged(token: String) {
        val stored = load() ?: return
        pairingDoc(stored.pairingId).update("fcmToken", token).await()
    }

    private fun fail(message: String) {
        stopListening()
        pending = null
        mutableState.value = PairingState.Failed(message)
    }

    private fun stopListening() {
        listener?.remove()
        listener = null
    }

    private fun pairingDoc(pairingId: String): DocumentReference = db.collection(PAIRINGS).document(pairingId)

    private fun Stored.copy(
        nextSeq: Long = this.nextSeq,
        lastSeqIn: Long = this.lastSeqIn,
        computerName: String? = this.computerName,
    ) = Stored(pairingId, w2bKey, b2wKey, nextSeq, lastSeqIn, computerName)

    private fun load(): Stored? {
        val sealed = prefs.getString(KEY_PAIRING, null) ?: return null
        val json = cipher.decrypt(sealed)?.let { runCatching { jsonMapper().readTree(it) }.getOrNull() } ?: return null
        return Stored(
            pairingId = json["pairingId"].asText(),
            w2bKey = Base64.getDecoder().decode(json["w2bKey"].asText()),
            b2wKey = Base64.getDecoder().decode(json["b2wKey"].asText()),
            nextSeq = json["nextSeq"].asLong(),
            lastSeqIn = json["lastSeqIn"].asLong(),
            computerName = json["computerName"]?.takeUnless { it.isNull }?.asText(),
        )
    }

    // commit() rather than apply(): sequence numbers must be on disk before a message goes out.
    private fun save(stored: Stored) {
        val json =
            jsonMapper()
                .createObjectNode()
                .put("pairingId", stored.pairingId)
                .put("w2bKey", base64(stored.w2bKey))
                .put("b2wKey", base64(stored.b2wKey))
                .put("nextSeq", stored.nextSeq)
                .put("lastSeqIn", stored.lastSeqIn)
                .put("computerName", stored.computerName)
        prefs.edit(commit = true) { putString(KEY_PAIRING, cipher.encrypt(jsonMapper().writeValueAsString(json))) }
    }

    private fun base64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)

    private companion object {
        const val CODES = "pairingCodes"
        const val PAIRINGS = "pairings"
        const val KEY_PAIRING = "pairing"

        // Same alphabet as the bridge: no 0/O or 1/I/L.
        const val CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
        const val CODE_LENGTH = 8
        const val CODE_ATTEMPTS = 3
        const val CODE_TTL_MS = 10 * 60_000L
        const val SEQ_DIGITS = 12
        const val MESSAGE_BUFFER = 32
        val random = SecureRandom()
    }
}
