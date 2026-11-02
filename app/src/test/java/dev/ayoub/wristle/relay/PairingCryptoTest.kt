package dev.ayoub.wristle.relay

import com.anthropic.core.jsonMapper
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.io.File
import java.util.Base64

/** Checks the watch against the same bytes as the bridge (relay/test-vectors.json). */
class PairingCryptoTest {
    private val vectors = jsonMapper().readTree(File("../relay/test-vectors.json"))
    private val message = vectors["message"]
    private val envelope = message["envelope"].let { Envelope(it["seq"].asLong(), it["n"].asText(), it["c"].asText()) }
    private val pairingId = message["pairingId"].asText()
    private val sentAt = 1793600000000L

    private fun bytes(field: String) = Base64.getDecoder().decode(vectors[field].asText())

    private fun hex(text: String) = text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun publicKeyMatchesRfc7748() {
        assertArrayEquals(
            hex("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a"),
            PairingCrypto.publicKeyOf(hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")),
        )
    }

    @Test
    fun derivesTheSameKeysAsTheBridge() {
        assertArrayEquals(bytes("watchPublic"), PairingCrypto.publicKeyOf(bytes("watchPrivate")))
        val keys =
            PairingCrypto.deriveSessionKeys(
                bytes("watchPrivate"),
                bytes("bridgePublic"),
                bytes("watchPublic"),
                bytes("bridgePublic"),
            )
        assertArrayEquals(bytes("w2bKey"), keys.w2b)
        assertArrayEquals(bytes("b2wKey"), keys.b2w)
        assertEquals(vectors["sas"].asText(), keys.sas)
    }

    @Test
    fun bothSidesAgree() {
        val watch = PairingCrypto.generateKeyPair()
        val bridge = PairingCrypto.generateKeyPair()
        val a = PairingCrypto.deriveSessionKeys(watch.privateKey, bridge.publicKey, watch.publicKey, bridge.publicKey)
        val b = PairingCrypto.deriveSessionKeys(bridge.privateKey, watch.publicKey, watch.publicKey, bridge.publicKey)
        assertArrayEquals(a.w2b, b.w2b)
        assertEquals(a.sas, b.sas)
        assertNotEquals(a.w2b.toList(), a.b2w.toList())
    }

    @Test
    fun opensTheBridgesMessage() {
        val opened = PairingCrypto.open(bytes("b2wKey"), pairingId, PairingCrypto.B2W, envelope, 0, sentAt)
        assertEquals(jsonMapper().readTree(message["plaintext"].asText()), opened)
    }

    @Test
    fun roundTrips() {
        val key = bytes("w2bKey")
        val sent = PairingCrypto.message("pong", sentAt).put("id", "x")
        val sealed = PairingCrypto.seal(key, "p", PairingCrypto.W2B, 1, sent)
        assertEquals(sent, PairingCrypto.open(key, "p", PairingCrypto.W2B, sealed, 0, sentAt))
    }

    @Test
    fun rejectsReplaysOtherRoutesTamperingAndStaleMessages() {
        val key = bytes("b2wKey")
        val tampered = Base64.getDecoder().decode(envelope.c).also { it[0] = (it[0].toInt() xor 1).toByte() }
        val bad =
            listOf(
                { PairingCrypto.open(key, pairingId, PairingCrypto.B2W, envelope, 7, sentAt) },
                { PairingCrypto.open(key, pairingId, PairingCrypto.W2B, envelope, 0, sentAt) },
                { PairingCrypto.open(key, "other", PairingCrypto.B2W, envelope, 0, sentAt) },
                { PairingCrypto.open(key, pairingId, PairingCrypto.B2W, envelope.copy(seq = 8), 0, sentAt) },
                {
                    PairingCrypto.open(
                        key,
                        pairingId,
                        PairingCrypto.B2W,
                        envelope.copy(c = Base64.getEncoder().encodeToString(tampered)),
                        0,
                        sentAt,
                    )
                },
                {
                    PairingCrypto.open(key, pairingId, PairingCrypto.B2W, envelope, 0, sentAt + PairingCrypto.MAX_CLOCK_SKEW_MS + 1)
                },
            )
        bad.forEachIndexed { index, attempt ->
            val failed = runCatching { attempt() }.exceptionOrNull()
            assert(failed is CryptoException) { "case $index should be rejected, got $failed" }
        }
    }
}
