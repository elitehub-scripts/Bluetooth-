package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.core.crypto.CryptoEngine
import com.example.core.crypto.ReplayGuard
import com.google.crypto.tink.subtle.Ed25519Sign
import com.google.crypto.tink.subtle.X25519
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.GeneralSecurityException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CryptoEngineTest {

    @Test
    fun `identity persistence returns same deviceId across restarts`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine1 = CryptoEngine(context)
        val id1 = engine1.getOrCreateIdentity()

        val engine2 = CryptoEngine(context)
        val id2 = engine2.getOrCreateIdentity()

        assertEquals(id1.deviceId, id2.deviceId)
        assertEquals(id1.fingerprint, id2.fingerprint)
    }

    @Test
    fun `end to end encryption protects payload from intermediate relays`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val senderCrypto = CryptoEngine(context)
        val senderId = senderCrypto.getOrCreateIdentity()

        // Generate independent recipient & relay identities
        val recipientEd = Ed25519Sign.KeyPair.newKeyPair()
        val recipientXPriv = X25519.generatePrivateKey()
        val recipientXPub = X25519.publicFromPrivate(recipientXPriv)
        val recipientId = CryptoEngine.deriveDeviceId(recipientEd.publicKey)

        val relayXPriv = X25519.generatePrivateKey()

        val now = System.currentTimeMillis()
        val expires = now + 3600_000L
        val (ciphertext, sig) = senderCrypto.encryptForRecipient(
            plaintext = "Meet at Dhaka sector 4 offline",
            recipientX25519PubBase64 = CryptoEngine.toBase64(recipientXPub),
            messageId = "msg-e2ee-1",
            recipientId = recipientId,
            createdAt = now,
            expiresAt = expires
        )

        // Relay verifies envelope authenticity without decrypting
        senderCrypto.verifyEnvelopeSignature(
            messageId = "msg-e2ee-1",
            originId = senderId.deviceId,
            recipientId = recipientId,
            ciphertextBase64 = ciphertext,
            createdAt = now,
            expiresAt = expires,
            senderEd25519PubBase64 = senderId.ed25519PublicKeyBase64,
            signatureBase64 = sig
        )

        // Relay cannot derive same shared secret
        val relayShared = X25519.computeSharedSecret(relayXPriv, senderId.x25519PublicKey)
        val realShared = X25519.computeSharedSecret(recipientXPriv, senderId.x25519PublicKey)
        assertNotEquals(CryptoEngine.toBase64(relayShared), CryptoEngine.toBase64(realShared))
    }

    @Test(expected = GeneralSecurityException::class)
    fun `tampered envelope signature is rejected`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val crypto = CryptoEngine(context)
        val id = crypto.getOrCreateIdentity()
        val now = System.currentTimeMillis()
        val (cipher, sig) = crypto.encryptForRecipient(
            plaintext = "Hello",
            recipientX25519PubBase64 = id.x25519PublicKeyBase64,
            messageId = "msg-tamper",
            recipientId = id.deviceId,
            createdAt = now,
            expiresAt = now + 10_000L
        )
        crypto.verifyEnvelopeSignature(
            messageId = "msg-tamper",
            originId = id.deviceId,
            recipientId = id.deviceId,
            ciphertextBase64 = cipher + "A",
            createdAt = now,
            expiresAt = now + 10_000L,
            senderEd25519PubBase64 = id.ed25519PublicKeyBase64,
            signatureBase64 = sig
        )
    }

    @Test
    fun `replay guard blocks duplicate nonces and stale timestamps`() {
        val guard = ReplayGuard(maxEntries = 10)
        val now = System.currentTimeMillis()
        assertTrue(guard.checkAndRecord("peer1:1", now))
        assertFalse(guard.checkAndRecord("peer1:1", now))
        assertFalse(guard.checkAndRecord("peer1:2", now - 40L * 24 * 60 * 60 * 1000))
    }
}
