package com.vrca.discordbot.lab

import java.io.InputStream
import java.io.OutputStream
import java.security.Key
import java.security.KeyStore
import java.security.KeyStoreSpi
import java.security.Provider
import java.security.SecureRandom
import java.security.Security
import java.security.cert.Certificate
import java.security.spec.AlgorithmParameterSpec
import java.util.Collections
import java.util.Date
import java.util.Enumeration
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.KeyGeneratorSpi
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * Test-only stand-in for Android's hardware-backed "AndroidKeyStore" JCA provider.
 *
 * Robolectric has no AndroidKeyStore, so the production [com.vrca.discordbot.DiscordBotStore]
 * (EncryptedSharedPreferences + MasterKey) would silently return an empty config and the bot would
 * refuse to start. Registering this in-memory provider lets the REAL store code run unmodified:
 * MasterKey generates an AES key "in the keystore" (this map) and Tink wraps its keysets with it.
 * Keys live only for the harness process.
 */
internal object FakeAndroidKeyStore {
    private const val NAME = "AndroidKeyStore"
    private val keys = ConcurrentHashMap<String, Key>()

    fun install() {
        if (Security.getProvider(NAME) != null) return
        Security.addProvider(ProviderImpl())
    }

    private class ProviderImpl : Provider(NAME, 1.0, "Cardinal Lab in-memory AndroidKeyStore") {
        init {
            putService(object : Service(this, "KeyStore", NAME, StoreSpi::class.java.name, null, null) {
                override fun newInstance(constructorParameter: Any?): Any = StoreSpi()
            })
            putService(object : Service(this, "KeyGenerator", "AES", AesGenSpi::class.java.name, null, null) {
                override fun newInstance(constructorParameter: Any?): Any = AesGenSpi()
            })
        }
    }

    class StoreSpi : KeyStoreSpi() {
        override fun engineGetKey(alias: String, password: CharArray?): Key? = keys[alias]
        override fun engineGetCertificateChain(alias: String?): Array<Certificate>? = null
        override fun engineGetCertificate(alias: String?): Certificate? = null
        override fun engineGetCreationDate(alias: String?): Date = Date()
        override fun engineSetKeyEntry(alias: String, key: Key, password: CharArray?, chain: Array<out Certificate>?) {
            keys[alias] = key
        }
        override fun engineSetKeyEntry(alias: String?, key: ByteArray?, chain: Array<out Certificate>?) =
            throw UnsupportedOperationException("raw key entries are not supported")
        override fun engineSetCertificateEntry(alias: String?, cert: Certificate?) =
            throw UnsupportedOperationException("certificates are not supported")
        override fun engineDeleteEntry(alias: String) { keys.remove(alias) }
        override fun engineAliases(): Enumeration<String> = Collections.enumeration(keys.keys.toList())
        override fun engineContainsAlias(alias: String): Boolean = keys.containsKey(alias)
        override fun engineSize(): Int = keys.size
        override fun engineIsKeyEntry(alias: String): Boolean = keys.containsKey(alias)
        override fun engineIsCertificateEntry(alias: String?): Boolean = false
        override fun engineGetCertificateAlias(cert: Certificate?): String? = null
        override fun engineStore(stream: OutputStream?, password: CharArray?) {}
        override fun engineLoad(stream: InputStream?, password: CharArray?) {}
        override fun engineLoad(param: KeyStore.LoadStoreParameter?) {}
    }

    class AesGenSpi : KeyGeneratorSpi() {
        private var alias: String? = null
        private var size = 256

        override fun engineInit(random: SecureRandom?) {}

        override fun engineInit(params: AlgorithmParameterSpec?, random: SecureRandom?) {
            // android.security.keystore.KeyGenParameterSpec, read reflectively.
            if (params == null) return
            alias = runCatching { params.javaClass.getMethod("getKeystoreAlias").invoke(params) as? String }.getOrNull()
            runCatching { params.javaClass.getMethod("getKeySize").invoke(params) as? Int }.getOrNull()
                ?.takeIf { it > 0 }?.let { size = it }
        }

        override fun engineInit(keysize: Int, random: SecureRandom?) { size = keysize }

        override fun engineGenerateKey(): SecretKey {
            val bytes = ByteArray(size / 8).also { SecureRandom().nextBytes(it) }
            val key = SecretKeySpec(bytes, "AES")
            alias?.let { keys[it] = key }
            return key
        }
    }
}
