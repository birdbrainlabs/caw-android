package com.caw.android.remote

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.math.BigInteger
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager
import javax.security.auth.x500.X500Principal

/**
 * The phone's long-lived identity: an EC P-256 key in Android Keystore and the self-signed
 * certificate Keystore made for it, presented as the TLS client certificate.
 * fingerprint = SHA-256 of the certificate's DER; device_id = its first 16 bytes, hex.
 */
class RemoteIdentity private constructor(private val key: PrivateKey, val certificate: X509Certificate) {

    val fingerprint: ByteArray = PairProof.fingerprint(certificate.encoded)
    val deviceId: String = PairProof.shortId(fingerprint)

    fun keyManager(): X509ExtendedKeyManager = object : X509ExtendedKeyManager() {
        private val chain = arrayOf(certificate)
        override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = ALIAS
        override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?) = ALIAS
        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(ALIAS)
        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
        override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null
        override fun getCertificateChain(alias: String?) = chain
        override fun getPrivateKey(alias: String?) = key
    }

    companion object {
        private const val ALIAS = "caw_remote_identity"

        @Volatile private var loaded: RemoteIdentity? = null

        /** Loads the key, making it the first time. Slow the first time (key generation); call off the main thread. */
        @Synchronized
        fun get(): RemoteIdentity {
            loaded?.let { return it }
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (!ks.containsAlias(ALIAS)) {
                val now = System.currentTimeMillis()
                val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    // NONE: TLS hands Keystore the digest it computed itself.
                    .setDigests(
                        KeyProperties.DIGEST_NONE,
                        KeyProperties.DIGEST_SHA256,
                        KeyProperties.DIGEST_SHA384,
                        KeyProperties.DIGEST_SHA512,
                    )
                    .setCertificateSubject(X500Principal("CN=Caw phone"))
                    .setCertificateSerialNumber(BigInteger.valueOf(now))
                    .setCertificateNotBefore(Date(now - 24L * 3600 * 1000))
                    .setCertificateNotAfter(Date(now + 30L * 365 * 24 * 3600 * 1000))
                    .build()
                KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").run {
                    initialize(spec)
                    generateKeyPair()
                }
            }
            val key = ks.getKey(ALIAS, null) as PrivateKey
            val cert = ks.getCertificate(ALIAS) as X509Certificate
            return RemoteIdentity(key, cert).also { loaded = it }
        }
    }
}

/** Trusts any server certificate: only for pairing, where the proofs check the certificates instead. */
internal object TrustAnyForPairing : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/** Trusts only the paired computer's certificate, by its SHA-256 fingerprint. */
internal class PinnedTrust(private val fingerprintHex: String) : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
        throw CertificateException("not a server")

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("no certificate")
        val got = PairProof.hex(PairProof.fingerprint(leaf.encoded))
        if (!got.equals(fingerprintHex, ignoreCase = true)) {
            throw CertificateException("the computer's certificate changed ($got)")
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/** The paired computer, as stored. */
data class PairedDesktop(val id: String, val name: String, val fingerprintHex: String, val host: String, val port: Int)

/** Caw's remote settings in SharedPreferences "caw_remote". */
class RemoteStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("caw_remote", Context.MODE_PRIVATE)

    fun desktop(): PairedDesktop? {
        val fp = prefs.getString(FP, null) ?: return null
        return PairedDesktop(
            id = prefs.getString(ID, null) ?: return null,
            name = prefs.getString(NAME, null) ?: "computer",
            fingerprintHex = fp,
            host = prefs.getString(HOST, null) ?: return null,
            port = prefs.getInt(PORT, PairTarget.DEFAULT_PORT),
        )
    }

    fun saveDesktop(d: PairedDesktop) {
        prefs.edit().putString(ID, d.id).putString(NAME, d.name).putString(FP, d.fingerprintHex)
            .putString(HOST, d.host).putInt(PORT, d.port).apply()
    }

    fun forget() {
        prefs.edit().remove(ID).remove(NAME).remove(FP).remove(HOST).remove(PORT).putBoolean(ENABLED, false)
            .remove(SHORTCUTS_FOR).remove(SHORTCUTS).apply()
    }

    /** The shortcut cache ([ShortcutCache]) for the computer [desktopId]; null when none is kept for it. */
    fun shortcuts(desktopId: String): List<Shortcut>? =
        if (prefs.getString(SHORTCUTS_FOR, null) == desktopId) ShortcutCache.decode(prefs.getString(SHORTCUTS, null)) else null

    fun saveShortcuts(desktopId: String, shortcuts: List<Shortcut>?) {
        prefs.edit().putString(SHORTCUTS_FOR, desktopId).putString(SHORTCUTS, ShortcutCache.encode(shortcuts)).apply()
    }

    /** The "Send to computer" switch. On by default; pairing turns it on again (RemoteLink.pairNow). */
    var enabled: Boolean
        get() = prefs.getBoolean(ENABLED, true)
        set(value) {
            prefs.edit().putBoolean(ENABLED, value).apply()
        }

    /**
     * Natural scrolling on the touchpad (phone-only; the Settings screen's switch). On by default.
     * Kept when the computer is forgotten.
     */
    var naturalScrolling: Boolean
        get() = prefs.getBoolean(NATURAL_SCROLLING, true)
        set(value) {
            prefs.edit().putBoolean(NATURAL_SCROLLING, value).apply()
        }

    private companion object {
        const val ID = "desktop_id"
        const val NAME = "desktop_name"
        const val FP = "desktop_fingerprint"
        const val HOST = "desktop_host"
        const val PORT = "desktop_port"
        const val ENABLED = "send_to_computer"
        const val NATURAL_SCROLLING = "natural_scrolling"
        const val SHORTCUTS_FOR = "shortcuts_desktop_id"
        const val SHORTCUTS = "shortcuts"
    }
}
