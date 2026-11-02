package dev.ayoub.wristle.relay

import com.anthropic.core.jsonMapper
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.google.crypto.tink.subtle.Hkdf
import com.google.crypto.tink.subtle.X25519
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

class CryptoException(
    message: String,
) : Exception(message)

/** A sealed message as stored in Firestore: sequence number, base64 nonce, base64 ciphertext with tag. */
data class Envelope(
    val seq: Long,
    val n: String,
    val c: String,
)

/**
 * End-to-end encryption with the computer bridge; the same protocol as bridge/src/crypto.ts, kept in step
 * by relay/test-vectors.json. X25519, HKDF-SHA256 into a key per direction plus a 6-digit security code,
 * and AES-256-GCM bound to the pairing, direction and sequence number.
 */
object PairingCrypto {
    const val W2B = "w2b"
    const val B2W = "b2w"
    const val MAX_CLOCK_SKEW_MS = 5 * 60_000L

    class KeyPair(
        val privateKey: ByteArray,
        val publicKey: ByteArray,
    )

    class SessionKeys(
        val w2b: ByteArray,
        val b2w: ByteArray,
        val sas: String,
    )

    private val random = SecureRandom()

    fun generateKeyPair(): KeyPair {
        val private = X25519.generatePrivateKey()
        return KeyPair(private, X25519.publicFromPrivate(private))
    }

    fun publicKeyOf(privateKey: ByteArray): ByteArray = X25519.publicFromPrivate(privateKey)

    fun deriveSessionKeys(
        ownPrivate: ByteArray,
        peerPublic: ByteArray,
        watchPublic: ByteArray,
        bridgePublic: ByteArray,
    ): SessionKeys {
        val shared =
            try {
                X25519.computeSharedSecret(ownPrivate, peerPublic)
            } catch (e: GeneralSecurityException) {
                throw CryptoException("invalid public key")
            }
        if (shared.all { it == 0.toByte() }) throw CryptoException("invalid public key")
        val salt = watchPublic + bridgePublic
        val keys = Hkdf.computeHkdf(HMAC, shared, salt, "wristle/v1/keys".toByteArray(), 2 * KEY_BYTES)
        val sasBytes = Hkdf.computeHkdf(HMAC, shared, salt, "wristle/v1/sas".toByteArray(), 4)
        val sas = (ByteBuffer.wrap(sasBytes).int.toLong() and 0xFFFFFFFFL) % 1_000_000
        return SessionKeys(
            w2b = keys.copyOfRange(0, KEY_BYTES),
            b2w = keys.copyOfRange(KEY_BYTES, 2 * KEY_BYTES),
            sas = sas.toString().padStart(6, '0'),
        )
    }

    fun aad(
        pairingId: String,
        direction: String,
        seq: Long,
    ): ByteArray = "wristle/v1|$pairingId|$direction|$seq".toByteArray(Charsets.UTF_8)

    fun seal(
        key: ByteArray,
        pairingId: String,
        direction: String,
        seq: Long,
        message: ObjectNode,
        nonce: ByteArray = ByteArray(NONCE_BYTES).also(random::nextBytes),
    ): Envelope {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(aad(pairingId, direction, seq))
        val sealed = cipher.doFinal(jsonMapper().writeValueAsBytes(message))
        return Envelope(seq, base64.encodeToString(nonce), base64.encodeToString(sealed))
    }

    /** [lastSeq] is the highest sequence number already accepted in this direction; anything at or below is a replay. */
    fun open(
        key: ByteArray,
        pairingId: String,
        direction: String,
        envelope: Envelope,
        lastSeq: Long,
        now: Long = System.currentTimeMillis(),
    ): JsonNode {
        if (envelope.seq <= lastSeq) throw CryptoException("replayed or out-of-order message (seq ${envelope.seq})")
        val plain =
            try {
                val nonce = Base64.getDecoder().decode(envelope.n)
                if (nonce.size != NONCE_BYTES) throw CryptoException("bad nonce")
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
                cipher.updateAAD(aad(pairingId, direction, envelope.seq))
                cipher.doFinal(Base64.getDecoder().decode(envelope.c))
            } catch (e: GeneralSecurityException) {
                throw CryptoException("message failed authentication")
            } catch (e: IllegalArgumentException) {
                throw CryptoException("message is not valid base64")
            }
        val message = jsonMapper().readTree(plain)
        val ts = message.get("ts")
        if (message.get("type")?.isTextual != true || ts?.isNumber != true) throw CryptoException("malformed message")
        if (abs(now - ts.asLong()) > MAX_CLOCK_SKEW_MS) throw CryptoException("message is too old or from the future")
        return message
    }

    fun message(
        type: String,
        now: Long = System.currentTimeMillis(),
    ): ObjectNode = jsonMapper().createObjectNode().put("type", type).put("ts", now)

    private val base64 = Base64.getEncoder()
    private const val HMAC = "HMACSHA256"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val KEY_BYTES = 32
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128
}
