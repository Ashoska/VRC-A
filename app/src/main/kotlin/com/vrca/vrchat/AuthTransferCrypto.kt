package com.vrca.vrchat

import org.json.JSONObject
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.MGF1ParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec

/**
 * Crypto for the admin-mediated "move VRChat login to another device" handoff.
 *
 * The sensitive material (username + password + trusted-device cookie) must cross Firestore, so
 * it is sealed to an EPHEMERAL keypair the TARGET device generates for this one transfer:
 *   - target makes an RSA-2048 keypair, publishes only the PUBLIC key,
 *   - the source device (which holds the credentials) hybrid-encrypts the bundle to that public
 *     key (random AES-256-GCM content key, RSA-OAEP-wrapped),
 *   - only the target's PRIVATE key (which never leaves the target, held transiently) can open it.
 * So the backend AND the admin only ever see ciphertext — neither can read the credentials. The
 * keypair is one-shot (discarded after the import) so a leaked ciphertext is worthless later.
 *
 * Pure JCA — no dependencies, works on all build variants.
 */
object AuthTransferCrypto {
    private const val RSA = "RSA"
    private const val RSA_OAEP = "RSA/ECB/OAEPPadding"
    private const val AES_GCM = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128

    // java.util.Base64 (API 26+, our minSdk) — no line wrapping, and works in plain JVM tests.
    private fun b64(bytes: ByteArray): String = java.util.Base64.getEncoder().encodeToString(bytes)
    private fun unb64(s: String): ByteArray = java.util.Base64.getDecoder().decode(s)

    /** Fresh RSA-2048 keypair for one transfer. */
    fun generateKeyPair(): KeyPair =
        KeyPairGenerator.getInstance(RSA).apply { initialize(2048) }.generateKeyPair()

    fun encodePublicKey(pub: PublicKey): String = b64(pub.encoded)                 // X.509
    fun encodePrivateKey(priv: PrivateKey): String = b64(priv.encoded)             // PKCS#8

    fun decodePublicKey(b64: String): PublicKey =
        KeyFactory.getInstance(RSA).generatePublic(X509EncodedKeySpec(unb64(b64)))

    fun decodePrivateKey(b64: String): PrivateKey =
        KeyFactory.getInstance(RSA).generatePrivate(PKCS8EncodedKeySpec(unb64(b64)))

    // SHA-256 OAEP (matched on both seal + open — the platform default MGF1 digest is SHA-1,
    // so it must be pinned explicitly or seal/open disagree).
    private fun oaepSpec() = OAEPParameterSpec(
        "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT
    )

    /** Seal a plaintext string to the target's public key. Returns a compact JSON envelope
     *  {v, wk, iv, ct} (all base64) safe to store in Firestore. */
    fun seal(plaintext: String, publicKeyB64: String): String {
        val pub = decodePublicKey(publicKeyB64)
        val aesKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val iv = ByteArray(12).also { java.security.SecureRandom().nextBytes(it) }
        val ct = Cipher.getInstance(AES_GCM).run {
            init(Cipher.ENCRYPT_MODE, aesKey, GCMParameterSpec(GCM_TAG_BITS, iv))
            doFinal(plaintext.toByteArray(Charsets.UTF_8))
        }
        val wrapped = Cipher.getInstance(RSA_OAEP).run {
            init(Cipher.ENCRYPT_MODE, pub, oaepSpec())
            doFinal(aesKey.encoded)
        }
        return JSONObject().apply {
            put("v", 1); put("wk", b64(wrapped)); put("iv", b64(iv)); put("ct", b64(ct))
        }.toString()
    }

    /** Open a sealed envelope with the target's private key. Returns null on any failure. */
    fun open(envelope: String, privateKeyB64: String): String? = try {
        val o = JSONObject(envelope)
        val priv = decodePrivateKey(privateKeyB64)
        val aesRaw = Cipher.getInstance(RSA_OAEP).run {
            init(Cipher.DECRYPT_MODE, priv, oaepSpec())
            doFinal(unb64(o.getString("wk")))
        }
        val pt = Cipher.getInstance(AES_GCM).run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(aesRaw, "AES"), GCMParameterSpec(GCM_TAG_BITS, unb64(o.getString("iv"))))
            doFinal(unb64(o.getString("ct")))
        }
        String(pt, Charsets.UTF_8)
    } catch (e: Exception) { null }
}
