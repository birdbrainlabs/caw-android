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

/** A paired computer, as stored. */
data class PairedDesktop(val id: String, val name: String, val fingerprintHex: String, val host: String, val port: Int)

/**
 * Caw's remote settings in SharedPreferences "caw_remote". Every paired computer is kept
 * ([desktops], [PairedDesktops] JSON, in pairing order); one of them is the active one, the only
 * one Caw connects and sends to ([desktop]). Each computer has its own shortcut cache.
 *
 * Up to 0.3 the phone kept one computer in separate keys (desktop_id, desktop_name, …) and one
 * shortcut cache; the first [RemoteStore] after the update moves them into the list, as the active
 * computer ([migrate]), so a paired phone stays paired.
 */
class RemoteStore(private val prefs: SharedPreferences) {

    constructor(context: Context) : this(context.getSharedPreferences("caw_remote", Context.MODE_PRIVATE))

    init {
        migrate()
    }

    /** Every paired computer, in the order they were first paired. */
    fun desktops(): List<PairedDesktop> = PairedDesktops.decode(prefs.getString(DESKTOPS, null))

    /** The active computer; null when none is paired or none is chosen. */
    fun desktop(): PairedDesktop? {
        val id = prefs.getString(ACTIVE, null) ?: return null
        return desktops().firstOrNull { it.id == id }
    }

    /** Adds [d], or updates the computer with its id in place. Which computer is active does not change. */
    fun saveDesktop(d: PairedDesktop) {
        prefs.edit().putString(DESKTOPS, PairedDesktops.encode(PairedDesktops.with(desktops(), d))).apply()
    }

    /** Makes the computer [id] the active one; null: none. */
    fun setActive(id: String?) {
        prefs.edit().apply { if (id != null) putString(ACTIVE, id) else remove(ACTIVE) }.apply()
    }

    /**
     * Forgets the computer [id] and its shortcut cache. If it was the active one, none is active
     * now; with no computer left the switch goes off, as on a phone that was never paired.
     */
    fun remove(id: String) {
        val left = PairedDesktops.without(desktops(), id)
        val e = prefs.edit().putString(DESKTOPS, PairedDesktops.encode(left)).remove(SHORTCUTS_PREFIX + id)
        if (prefs.getString(ACTIVE, null) == id) e.remove(ACTIVE)
        if (left.isEmpty()) e.putBoolean(ENABLED, false)
        e.apply()
    }

    /** The shortcut cache ([ShortcutCache]) for the computer [desktopId]; null when none is kept for it. */
    fun shortcuts(desktopId: String): List<Shortcut>? = ShortcutCache.decode(prefs.getString(SHORTCUTS_PREFIX + desktopId, null))

    fun saveShortcuts(desktopId: String, shortcuts: List<Shortcut>?) {
        prefs.edit().putString(SHORTCUTS_PREFIX + desktopId, ShortcutCache.encode(shortcuts)).apply()
    }

    /** The 0.3 keys (one computer) into the list, once: that computer becomes the first, active entry. */
    private fun migrate() {
        if (prefs.contains(DESKTOPS)) return
        val e = prefs.edit()
        val id = prefs.getString(OLD_ID, null)
        val fp = prefs.getString(OLD_FP, null)
        val host = prefs.getString(OLD_HOST, null)
        if (id != null && fp != null && host != null) {
            val d = PairedDesktop(id, prefs.getString(OLD_NAME, null) ?: "computer", fp, host, prefs.getInt(OLD_PORT, PairTarget.DEFAULT_PORT))
            e.putString(DESKTOPS, PairedDesktops.encode(listOf(d))).putString(ACTIVE, id)
            // 0.3 kept one cache, marked with the computer it was for.
            if (prefs.getString(OLD_SHORTCUTS_FOR, null) == id) {
                prefs.getString(OLD_SHORTCUTS, null)?.let { e.putString(SHORTCUTS_PREFIX + id, it) }
            }
        } else {
            e.putString(DESKTOPS, PairedDesktops.encode(emptyList()))
        }
        e.remove(OLD_ID).remove(OLD_NAME).remove(OLD_FP).remove(OLD_HOST).remove(OLD_PORT)
            .remove(OLD_SHORTCUTS_FOR).remove(OLD_SHORTCUTS).apply()
    }

    /** The "Send to computer" switch. On by default; pairing turns it on again (RemoteLink.pairNow). */
    var enabled: Boolean
        get() = prefs.getBoolean(ENABLED, true)
        set(value) {
            prefs.edit().putBoolean(ENABLED, value).apply()
        }

    /**
     * Natural scrolling on the touchpad (phone-only; the Settings screen's switch). On by default.
     * Kept when a computer is removed.
     */
    var naturalScrolling: Boolean
        get() = prefs.getBoolean(NATURAL_SCROLLING, true)
        set(value) {
            prefs.edit().putBoolean(NATURAL_SCROLLING, value).apply()
        }

    private companion object {
        const val DESKTOPS = "desktops"
        const val ACTIVE = "active_desktop_id"
        const val ENABLED = "send_to_computer"
        const val NATURAL_SCROLLING = "natural_scrolling"
        /** + the computer's id: its shortcut cache. */
        const val SHORTCUTS_PREFIX = "shortcuts:"

        // Up to 0.3 (one computer); read once by migrate().
        const val OLD_ID = "desktop_id"
        const val OLD_NAME = "desktop_name"
        const val OLD_FP = "desktop_fingerprint"
        const val OLD_HOST = "desktop_host"
        const val OLD_PORT = "desktop_port"
        const val OLD_SHORTCUTS_FOR = "shortcuts_desktop_id"
        const val OLD_SHORTCUTS = "shortcuts"
    }
}
