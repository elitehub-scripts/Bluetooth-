package com.example.core.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.google.crypto.tink.subtle.AesGcmJce
import com.google.crypto.tink.subtle.Ed25519Sign
import com.google.crypto.tink.subtle.Ed25519Verify
import com.google.crypto.tink.subtle.Hkdf
import com.google.crypto.tink.subtle.Random
import com.google.crypto.tink.subtle.X25519
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Collections
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class LocalIdentityKeys(
    val deviceId: String,
    val ed25519PublicKey: ByteArray,
    val ed25519PrivateKey: ByteArray,
    val x25519PublicKey: ByteArray,
    val x25519PrivateKey: ByteArray,
    val fingerprint: String,
    val keystoreBacked: Boolean
) {
    val ed25519PublicKeyBase64: String get() = CryptoEngine.toBase64(ed25519PublicKey)
    val x25519PublicKeyBase64: String get() = CryptoEngine.toBase64(x25519PublicKey)
}

@Serializable
private data class PersistedKeyBundle(
    val version: Int = 1,
    val keystoreWrapped: Boolean,
    val ivBase64: String,
    val encryptedPayloadBase64: String
)

@Serializable
private data class RawIdentitySecret(
    val deviceId: String,
    val ed25519PublicBase64: String,
    val ed25519PrivateBase64: String,
    val x25519PublicBase64: String,
    val x25519PrivateBase64: String
)

class ReplayGuard(private val maxEntries: Int = 2048) {
    private val seen = Collections.synchronizedMap(
        object : LinkedHashMap<String, Long>(maxEntries, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean {
                return size > maxEntries
            }
        }
    )

    fun checkAndRecord(nonceOrId: String, timestamp: Long, maxClockSkewMs: Long = 30L * 24 * 60 * 60 * 1000): Boolean {
        val now = System.currentTimeMillis()
        if (kotlin.math.abs(now - timestamp) > maxClockSkewMs) return false
        synchronized(seen) {
            if (seen.containsKey(nonceOrId)) return false
            seen[nonceOrId] = now
            return true
        }
    }
}

class CryptoEngine(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }
    private val keyFile = File(context.filesDir, "bgs_identity_keystore.json")
    private val fallbackWrapFile = File(context.filesDir, "bgs_local_wrap.bin")

    @Volatile
    private var cachedIdentity: LocalIdentityKeys? = null

    @Synchronized
    fun getOrCreateIdentity(): LocalIdentityKeys {
        cachedIdentity?.let { return it }

        if (keyFile.exists()) {
            try {
                val loaded = loadPersistedIdentity()
                cachedIdentity = loaded
                return loaded
            } catch (_: Exception) {
                // Corrupt file; regenerate clean keys
            }
        }

        val edPair = Ed25519Sign.KeyPair.newKeyPair()
        val xPriv = X25519.generatePrivateKey()
        val xPub = X25519.publicFromPrivate(xPriv)
        val deviceId = deriveDeviceId(edPair.publicKey)
        val fingerprint = computeFingerprint(edPair.publicKey, xPub)

        val raw = RawIdentitySecret(
            deviceId = deviceId,
            ed25519PublicBase64 = toBase64(edPair.publicKey),
            ed25519PrivateBase64 = toBase64(edPair.privateKey),
            x25519PublicBase64 = toBase64(xPub),
            x25519PrivateBase64 = toBase64(xPriv)
        )
        val keystoreBacked = savePersistedIdentity(raw)

        return LocalIdentityKeys(
            deviceId = deviceId,
            ed25519PublicKey = edPair.publicKey,
            ed25519PrivateKey = edPair.privateKey,
            x25519PublicKey = xPub,
            x25519PrivateKey = xPriv,
            fingerprint = fingerprint,
            keystoreBacked = keystoreBacked
        ).also { cachedIdentity = it }
    }

    private fun savePersistedIdentity(raw: RawIdentitySecret): Boolean {
        val serialized = json.encodeToString(raw).toByteArray(Charsets.UTF_8)
        return try {
            val masterKey = getOrCreateAndroidKeystoreKey()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, masterKey)
            val encrypted = cipher.doFinal(serialized)
            val iv = cipher.iv
            // Verify platform KeyStore can decrypt what it just encrypted
            val verifyCipher = Cipher.getInstance("AES/GCM/NoPadding")
            verifyCipher.init(Cipher.DECRYPT_MODE, masterKey, GCMParameterSpec(128, iv))
            verifyCipher.doFinal(encrypted)

            val bundle = PersistedKeyBundle(
                keystoreWrapped = true,
                ivBase64 = toBase64(iv),
                encryptedPayloadBase64 = toBase64(encrypted)
            )
            keyFile.writeText(json.encodeToString(bundle))
            true
        } catch (_: Throwable) {
            // Fallback for headless JVM unit tests (Robolectric) where AndroidKeyStore isn't mounted
            val localKey = getOrCreateFallbackWrapKey()
            val encrypted = AesGcmJce(localKey).encrypt(serialized, FALLBACK_AAD)
            val bundle = PersistedKeyBundle(
                keystoreWrapped = false,
                ivBase64 = "",
                encryptedPayloadBase64 = toBase64(encrypted)
            )
            keyFile.writeText(json.encodeToString(bundle))
            false
        }
    }

    private fun loadPersistedIdentity(): LocalIdentityKeys {
        val bundle = json.decodeFromString<PersistedKeyBundle>(keyFile.readText())
        val plainBytes = if (bundle.keystoreWrapped) {
            val masterKey = getOrCreateAndroidKeystoreKey()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val iv = fromBase64(bundle.ivBase64)
            cipher.init(Cipher.DECRYPT_MODE, masterKey, GCMParameterSpec(128, iv))
            cipher.doFinal(fromBase64(bundle.encryptedPayloadBase64))
        } else {
            val localKey = getOrCreateFallbackWrapKey()
            AesGcmJce(localKey).decrypt(fromBase64(bundle.encryptedPayloadBase64), FALLBACK_AAD)
        }
        val raw = json.decodeFromString<RawIdentitySecret>(String(plainBytes, Charsets.UTF_8))
        val edPub = fromBase64(raw.ed25519PublicBase64)
        val edPriv = fromBase64(raw.ed25519PrivateBase64)
        val xPub = fromBase64(raw.x25519PublicBase64)
        val xPriv = fromBase64(raw.x25519PrivateBase64)
        return LocalIdentityKeys(
            deviceId = raw.deviceId,
            ed25519PublicKey = edPub,
            ed25519PrivateKey = edPriv,
            x25519PublicKey = xPub,
            x25519PrivateKey = xPriv,
            fingerprint = computeFingerprint(edPub, xPub),
            keystoreBacked = bundle.keystoreWrapped
        )
    }

    private fun getOrCreateAndroidKeystoreKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getKey(KEYSTORE_ALIAS, null) as? SecretKey
        if (existing != null) return existing

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEYSTORE_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    private fun getOrCreateFallbackWrapKey(): ByteArray {
        if (fallbackWrapFile.exists()) {
            val bytes = fallbackWrapFile.readBytes()
            if (bytes.size == 32) return bytes
        }
        val key = Random.randBytes(32)
        fallbackWrapFile.writeBytes(key)
        return key
    }

    // --- End-to-End Encrypted Message Operations ---

    fun encryptForRecipient(
        plaintext: String,
        recipientX25519PubBase64: String,
        messageId: String,
        recipientId: String,
        createdAt: Long,
        expiresAt: Long
    ): Pair<String, String> {
        val identity = getOrCreateIdentity()
        val recipientPub = fromBase64(recipientX25519PubBase64)
        require(recipientPub.size == 32) { "Invalid recipient X25519 key length" }

        val sharedSecret = X25519.computeSharedSecret(identity.x25519PrivateKey, recipientPub)
        val aad = buildEnvelopeAad(messageId, identity.deviceId, recipientId, createdAt, expiresAt)
        val aesKey = Hkdf.computeHkdf(
            "HMACSHA256",
            sharedSecret,
            messageId.toByteArray(Charsets.UTF_8),
            "BGS-E2EE-MSG-V1".toByteArray(Charsets.UTF_8),
            32
        )
        val ciphertext = AesGcmJce(aesKey).encrypt(plaintext.toByteArray(Charsets.UTF_8), aad)
        val ciphertextBase64 = toBase64(ciphertext)

        val signingTranscript = buildEnvelopeSigningTranscript(
            messageId = messageId,
            originId = identity.deviceId,
            recipientId = recipientId,
            ciphertextBase64 = ciphertextBase64,
            createdAt = createdAt,
            expiresAt = expiresAt
        )
        val signature = Ed25519Sign(identity.ed25519PrivateKey).sign(signingTranscript)
        return ciphertextBase64 to toBase64(signature)
    }

    fun decryptFromSender(
        ciphertextBase64: String,
        senderEd25519PubBase64: String,
        senderX25519PubBase64: String,
        signatureBase64: String,
        messageId: String,
        senderId: String,
        recipientId: String,
        createdAt: Long,
        expiresAt: Long
    ): String {
        val identity = getOrCreateIdentity()
        require(identity.deviceId == recipientId || identity.deviceId == senderId) {
            "Message not addressed to this device"
        }
        verifyEnvelopeSignature(
            messageId = messageId,
            originId = senderId,
            recipientId = recipientId,
            ciphertextBase64 = ciphertextBase64,
            createdAt = createdAt,
            expiresAt = expiresAt,
            senderEd25519PubBase64 = senderEd25519PubBase64,
            signatureBase64 = signatureBase64
        )
        val peerX25519Pub = fromBase64(senderX25519PubBase64)
        val sharedSecret = X25519.computeSharedSecret(identity.x25519PrivateKey, peerX25519Pub)
        val aesKey = Hkdf.computeHkdf(
            "HMACSHA256",
            sharedSecret,
            messageId.toByteArray(Charsets.UTF_8),
            "BGS-E2EE-MSG-V1".toByteArray(Charsets.UTF_8),
            32
        )
        val aad = buildEnvelopeAad(messageId, senderId, recipientId, createdAt, expiresAt)
        val plaintextBytes = AesGcmJce(aesKey).decrypt(fromBase64(ciphertextBase64), aad)
        return String(plaintextBytes, Charsets.UTF_8)
    }

    fun verifyEnvelopeSignature(
        messageId: String,
        originId: String,
        recipientId: String,
        ciphertextBase64: String,
        createdAt: Long,
        expiresAt: Long,
        senderEd25519PubBase64: String,
        signatureBase64: String
    ) {
        val pubBytes = fromBase64(senderEd25519PubBase64)
        val sigBytes = fromBase64(signatureBase64)
        if (pubBytes.size != 32 || sigBytes.size != 64) {
            throw GeneralSecurityException("Malformed Ed25519 key or signature length")
        }
        val transcript = buildEnvelopeSigningTranscript(
            messageId = messageId,
            originId = originId,
            recipientId = recipientId,
            ciphertextBase64 = ciphertextBase64,
            createdAt = createdAt,
            expiresAt = expiresAt
        )
        Ed25519Verify(pubBytes).verify(sigBytes, transcript)
    }

    // --- Delivery ACK Signing & Verification ---

    fun signDeliveryAck(messageId: String, originId: String, ackTimestamp: Long): String {
        val identity = getOrCreateIdentity()
        val data = "BGS-ACK-V1|$messageId|$originId|${identity.deviceId}|$ackTimestamp"
            .toByteArray(Charsets.UTF_8)
        return toBase64(Ed25519Sign(identity.ed25519PrivateKey).sign(data))
    }

    fun verifyDeliveryAck(
        messageId: String,
        originId: String,
        recipientId: String,
        recipientEd25519PubBase64: String,
        ackTimestamp: Long,
        signatureBase64: String
    ): Boolean {
        return try {
            val pub = fromBase64(recipientEd25519PubBase64)
            val sig = fromBase64(signatureBase64)
            if (deriveDeviceId(pub) != recipientId) return false
            val data = "BGS-ACK-V1|$messageId|$originId|$recipientId|$ackTimestamp"
                .toByteArray(Charsets.UTF_8)
            Ed25519Verify(pub).verify(sig, data)
            true
        } catch (_: Throwable) {
            false
        }
    }

    // --- Session Transport Key Agreement & Peer Challenge ---

    fun generateEphemeralX25519KeyPair(): Pair<ByteArray, ByteArray> {
        val priv = X25519.generatePrivateKey()
        val pub = X25519.publicFromPrivate(priv)
        return priv to pub
    }

    fun deriveTransportSessionKey(
        localEphemeralPriv: ByteArray,
        peerEphemeralPub: ByteArray,
        sessionSalt: ByteArray
    ): ByteArray {
        val shared = X25519.computeSharedSecret(localEphemeralPriv, peerEphemeralPub)
        return Hkdf.computeHkdf(
            "HMACSHA256",
            shared,
            sessionSalt,
            "BGS-TRANSPORT-SESSION-V1".toByteArray(Charsets.UTF_8),
            32
        )
    }

    fun signChallenge(nonceBytes: ByteArray): String {
        val identity = getOrCreateIdentity()
        val payload = "BGS-AUTH-CHALLENGE-V1".toByteArray(Charsets.UTF_8) + nonceBytes
        return toBase64(Ed25519Sign(identity.ed25519PrivateKey).sign(payload))
    }

    fun verifyPeerChallenge(
        peerDeviceId: String,
        peerEd25519PubBase64: String,
        nonceBytes: ByteArray,
        signatureBase64: String
    ): Boolean {
        return try {
            val pub = fromBase64(peerEd25519PubBase64)
            if (deriveDeviceId(pub) != peerDeviceId) return false
            val sig = fromBase64(signatureBase64)
            val payload = "BGS-AUTH-CHALLENGE-V1".toByteArray(Charsets.UTF_8) + nonceBytes
            Ed25519Verify(pub).verify(sig, payload)
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun encryptSessionBytes(sessionKey: ByteArray, plaintext: ByteArray, frameId: Long): ByteArray {
        val aad = "FRAME|$frameId".toByteArray(Charsets.UTF_8)
        return AesGcmJce(sessionKey).encrypt(plaintext, aad)
    }

    fun decryptSessionBytes(sessionKey: ByteArray, ciphertext: ByteArray, frameId: Long): ByteArray {
        val aad = "FRAME|$frameId".toByteArray(Charsets.UTF_8)
        return AesGcmJce(sessionKey).decrypt(ciphertext, aad)
    }

    // --- Hashing & Local Storage Encryption ---

    fun encryptLocalSecretText(plaintext: String, contextId: String): String {
        val identity = getOrCreateIdentity()
        val key = Hkdf.computeHkdf(
            "HMACSHA256",
            identity.ed25519PrivateKey,
            identity.deviceId.toByteArray(Charsets.UTF_8),
            "BGS-LOCAL-AT-REST-V1".toByteArray(Charsets.UTF_8),
            32
        )
        val encrypted = AesGcmJce(key).encrypt(
            plaintext.toByteArray(Charsets.UTF_8),
            contextId.toByteArray(Charsets.UTF_8)
        )
        return toBase64(encrypted)
    }

    fun decryptLocalSecretText(cipherBase64: String, contextId: String): String {
        val identity = getOrCreateIdentity()
        val key = Hkdf.computeHkdf(
            "HMACSHA256",
            identity.ed25519PrivateKey,
            identity.deviceId.toByteArray(Charsets.UTF_8),
            "BGS-LOCAL-AT-REST-V1".toByteArray(Charsets.UTF_8),
            32
        )
        val decrypted = AesGcmJce(key).decrypt(
            fromBase64(cipherBase64),
            contextId.toByteArray(Charsets.UTF_8)
        )
        return String(decrypted, Charsets.UTF_8)
    }

    fun randomBytes(length: Int): ByteArray = Random.randBytes(length)

    private fun buildEnvelopeAad(
        messageId: String,
        originId: String,
        recipientId: String,
        createdAt: Long,
        expiresAt: Long
    ): ByteArray = "$messageId|$originId|$recipientId|$createdAt|$expiresAt".toByteArray(Charsets.UTF_8)

    private fun buildEnvelopeSigningTranscript(
        messageId: String,
        originId: String,
        recipientId: String,
        ciphertextBase64: String,
        createdAt: Long,
        expiresAt: Long
    ): ByteArray =
        "BGS-ENV-V1|$messageId|$originId|$recipientId|$createdAt|$expiresAt|$ciphertextBase64"
            .toByteArray(Charsets.UTF_8)

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEYSTORE_ALIAS = "bgs_master_wrap_key_v1"
        private val FALLBACK_AAD = "BGS-FALLBACK-KEYSTORE-V1".toByteArray(Charsets.UTF_8)

        fun toBase64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

        fun fromBase64(text: String): ByteArray = Base64.decode(text, Base64.NO_WRAP)

        fun deriveDeviceId(ed25519PubKey: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(ed25519PubKey)
            return digest.take(12).joinToString("") { "%02x".format(it) }.uppercase()
        }

        fun computeFingerprint(ed25519PubKey: ByteArray, x25519PubKey: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(ed25519PubKey)
            digest.update(x25519PubKey)
            val hash = digest.digest()
            return hash.take(16)
                .chunked(2)
                .joinToString(" ") { pair -> "%02X%02X".format(pair[0], pair[1]) }
        }

        fun sha256Hex(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            return digest.joinToString("") { "%02x".format(it) }
        }

        fun sha256Hex(input: InputStream): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
