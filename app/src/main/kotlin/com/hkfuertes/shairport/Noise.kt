package com.hkfuertes.shairport

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.crypto.modes.AEADCipher
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.modes.GCMBlockCipher
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.math.ec.rfc7748.X25519

/**
 * Noise_KKpsk2_25519_{ChaChaPoly,AESGCM}_SHA256 (noiseprotocol.org, rev 34), Sendspin's handshake:
 * both static keys known up front, the PSK mixed in at the end of message 2. The server is the
 * initiator; the responder side is for the unit test's fake client. BouncyCastle for X25519 and
 * the AEADs: Android has neither before API 28/33.
 */
class Noise(
    private val suite: String,
    private val initiator: Boolean,
    private val static: ByteArray, // private key
    private val remoteStatic: ByteArray,
    prologue: ByteArray,
    private val psk: ByteArray,
) {
    private var h = name().let { if (it.size <= 32) it.copyOf(32) else sha256(it) }
    private var ck = h.copyOf()
    private var k: ByteArray? = null
    private var n = 0L
    private val ephemeral = ByteArray(32).also { X25519.generatePrivateKey(random, it) }
    private var remoteEphemeral = ByteArray(0)

    init {
        require(suite in SUITES) { "unknown suite $suite" }
        mixHash(prologue)
        val (first, second) = if (initiator) public(static) to remoteStatic else remoteStatic to public(static)
        mixHash(first)
        mixHash(second)
    }

    /** -> e, es, ss: the initiator's first message. */
    fun writeMessage1(payload: ByteArray): ByteArray {
        val e = public(ephemeral)
        mixHash(e)
        mixKey(e) // psk handshakes: e also feeds the key
        mixKey(dh(ephemeral, remoteStatic))
        mixKey(dh(static, remoteStatic))
        return e + encryptAndHash(payload)
    }

    fun readMessage1(message: ByteArray): ByteArray {
        remoteEphemeral = message.copyOf(32)
        mixHash(remoteEphemeral)
        mixKey(remoteEphemeral)
        mixKey(dh(static, remoteEphemeral))
        mixKey(dh(static, remoteStatic))
        return decryptAndHash(message.copyOfRange(32, message.size))
    }

    /** <- e, ee, se, psk: the responder's answer. [psk] may change after reading message 1. */
    fun writeMessage2(payload: ByteArray, psk: ByteArray = this.psk): ByteArray {
        val e = public(ephemeral)
        mixHash(e)
        mixKey(e)
        mixKey(dh(ephemeral, remoteEphemeral))
        mixKey(dh(ephemeral, remoteStatic))
        mixKeyAndHash(psk)
        return e + encryptAndHash(payload)
    }

    fun readMessage2(message: ByteArray): ByteArray {
        remoteEphemeral = message.copyOf(32)
        mixHash(remoteEphemeral)
        mixKey(remoteEphemeral)
        mixKey(dh(ephemeral, remoteEphemeral))
        mixKey(dh(static, remoteEphemeral))
        mixKeyAndHash(psk)
        return decryptAndHash(message.copyOfRange(32, message.size))
    }

    /** After message 2: (what this side sends with, what it receives with). */
    fun split(): Pair<Cipher, Cipher> {
        val (k1, k2) = hkdf(ck, ByteArray(0), 2)
        val (send, receive) = if (initiator) k1 to k2 else k2 to k1
        return Cipher(suite, send) to Cipher(suite, receive)
    }

    /** A transport direction: the nonce counts messages, so calls must follow wire order. */
    class Cipher(private val suite: String, private val key: ByteArray) {
        private var n = 0L
        fun encrypt(plain: ByteArray, ad: ByteArray = ByteArray(0)) = aead(suite, true, key, n++, ad, plain)
        fun decrypt(sealed: ByteArray, ad: ByteArray = ByteArray(0)) = aead(suite, false, key, n++, ad, sealed)
    }

    private fun name() = "Noise_KKpsk2_$suite".toByteArray()

    private fun mixHash(data: ByteArray) {
        h = sha256(h + data)
    }

    private fun mixKey(input: ByteArray) {
        val (newCk, key) = hkdf(ck, input, 2)
        ck = newCk
        k = key
        n = 0
    }

    private fun mixKeyAndHash(input: ByteArray) {
        val (newCk, hash, key) = hkdf(ck, input, 3)
        ck = newCk
        mixHash(hash)
        k = key
        n = 0
    }

    private fun encryptAndHash(plain: ByteArray): ByteArray {
        val sealed = k?.let { aead(suite, true, it, n++, h, plain) } ?: plain
        mixHash(sealed)
        return sealed
    }

    private fun decryptAndHash(sealed: ByteArray): ByteArray {
        val plain = k?.let { aead(suite, false, it, n++, h, sealed) } ?: sealed
        mixHash(sealed)
        return plain
    }

    companion object {
        val SUITES = setOf("25519_ChaChaPoly_SHA256", "25519_AESGCM_SHA256")
        /** Sendspin's published PSK for unpaired sessions, and its psk_id. */
        val SENTINEL_PSK: ByteArray = sha256("sendspin-sentinel-psk-v1".toByteArray())
        val SENTINEL_PSK_ID: String = base64url(sha256("sendspin-psk-id-v1".toByteArray() + SENTINEL_PSK))
        private val random = SecureRandom()

        fun privateKey(): ByteArray = ByteArray(32).also { X25519.generatePrivateKey(random, it) }

        fun public(private: ByteArray): ByteArray = ByteArray(32).also { X25519.generatePublicKey(private, 0, it, 0) }

        fun base64url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

        fun unbase64url(text: String): ByteArray = Base64.getUrlDecoder().decode(text)

        private fun dh(private: ByteArray, public: ByteArray) = ByteArray(32).also {
            check(X25519.calculateAgreement(private, 0, public, 0, it, 0)) { "low-order key" }
        }

        private fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

        private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
            Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)

        private fun hkdf(ck: ByteArray, input: ByteArray, outputs: Int): List<ByteArray> {
            val temp = hmac(ck, input)
            val out = mutableListOf(hmac(temp, byteArrayOf(1)))
            while (out.size < outputs) out += hmac(temp, out.last() + byteArrayOf((out.size + 1).toByte()))
            return out
        }

        /** Noise's nonce: 4 zero bytes, then the counter, little-endian for ChaChaPoly, big for AESGCM. */
        private fun aead(suite: String, encrypt: Boolean, key: ByteArray, n: Long, ad: ByteArray, input: ByteArray): ByteArray {
            val chacha = suite.contains("ChaChaPoly")
            val nonce = ByteArray(12)
            for (i in 0 until 8) nonce[if (chacha) 4 + i else 11 - i] = (n ushr (8 * i)).toByte()
            val cipher: AEADCipher = if (chacha) ChaCha20Poly1305() else GCMBlockCipher.newInstance(AESEngine.newInstance())
            cipher.init(encrypt, AEADParameters(KeyParameter(key), 128, nonce, ad))
            val out = ByteArray(cipher.getOutputSize(input.size))
            val length = cipher.processBytes(input, 0, input.size, out, 0)
            cipher.doFinal(out, length) // throws on a bad tag
            return out
        }
    }
}
