package com.caw.android.remote

import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** The pairing code: 16 Crockford base32 characters. */
object PairingCode {
    const val LENGTH = 16
    private const val CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    /** Uppercase, drop dashes and spaces, O -> 0, I and L -> 1. */
    fun normalize(code: String): String =
        code.uppercase()
            .filterNot { it == '-' || it.isWhitespace() }
            .replace('O', '0')
            .replace('I', '1')
            .replace('L', '1')

    /** Null when [code] normalizes to a valid code; otherwise what is wrong with it, in words. */
    fun problem(code: String): String? {
        val n = normalize(code)
        return when {
            n.isEmpty() -> "Type the code the computer shows."
            n.length != LENGTH -> "The code has $LENGTH letters and digits; this one has ${n.length}."
            n.any { it !in CROCKFORD } -> "The code has a character the computer never shows (${n.first { it !in CROCKFORD }})."
            else -> null
        }
    }

    /** XXXX-XXXX-XXXX-XXXX, as the computer shows it. */
    fun display(code: String): String = normalize(code).chunked(4).joinToString("-")
}

/** What a pairing QR code carries: `caw://pair?h=<ip>&p=<port>&c=<code>&n=<name>`. */
data class PairTarget(val host: String, val port: Int, val code: String, val name: String?) {
    companion object {
        const val DEFAULT_PORT = 47820

        /** Null if [text] is not a Caw pairing link. */
        fun parse(text: String): PairTarget? {
            val uri = try {
                URI(text.trim())
            } catch (e: Exception) {
                return null
            }
            if (!uri.scheme.equals("caw", ignoreCase = true)) return null
            // caw://pair?... : "pair" is the authority.
            if (!uri.rawAuthority.equals("pair", ignoreCase = true)) return null
            val query = (uri.rawQuery ?: return null).split('&').filter { it.isNotEmpty() }.associate { part ->
                val eq = part.indexOf('=')
                if (eq < 0) decode(part) to "" else decode(part.substring(0, eq)) to decode(part.substring(eq + 1))
            }
            val host = query["h"]?.trim().orEmpty()
            val code = query["c"]?.trim().orEmpty()
            if (host.isEmpty() || code.isEmpty()) return null
            val port = query["p"]?.trim()?.takeIf { it.isNotEmpty() }?.let { it.toIntOrNull() ?: return null } ?: DEFAULT_PORT
            if (port !in 1..65535) return null
            return PairTarget(host, port, code, query["n"]?.takeIf { it.isNotBlank() })
        }

        private fun decode(s: String): String = URLDecoder.decode(s, "UTF-8")
    }
}

/** Fingerprints and pairing proofs (Caw Remote Protocol identities and pairing). */
object PairProof {
    val PHONE_LABEL = "caw-pair-phone".toByteArray(Charsets.UTF_8)
    val DESKTOP_LABEL = "caw-pair-desktop".toByteArray(Charsets.UTF_8)

    /** SHA-256 of the DER certificate. */
    fun fingerprint(der: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(der)

    /** device_id / desktop_id: the first 16 bytes of the fingerprint, lowercase hex. */
    fun shortId(fingerprint: ByteArray): String = hex(fingerprint.copyOf(16))

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    /** HMAC-SHA256(K = normalized code as UTF-8, label ‖ D ‖ P). */
    fun mac(code: String, label: ByteArray, desktopFp: ByteArray, phoneFp: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(PairingCode.normalize(code).toByteArray(Charsets.UTF_8), "HmacSHA256"))
        mac.update(label)
        mac.update(desktopFp)
        mac.update(phoneFp)
        return mac.doFinal()
    }

    /** The phone's proof for pair_request, base64. */
    fun phone(code: String, desktopFp: ByteArray, phoneFp: ByteArray): String =
        Base64.getEncoder().encodeToString(mac(code, PHONE_LABEL, desktopFp, phoneFp))

    /** The desktop's proof in pair_ok, base64. */
    fun desktop(code: String, desktopFp: ByteArray, phoneFp: ByteArray): String =
        Base64.getEncoder().encodeToString(mac(code, DESKTOP_LABEL, desktopFp, phoneFp))

    /** True if [proof] (base64, padded or not) is the desktop's proof for this code and these certificates. */
    fun desktopMatches(proof: String, code: String, desktopFp: ByteArray, phoneFp: ByteArray): Boolean {
        val got = try {
            Base64.getDecoder().decode(proof.trim().trimEnd('='))
        } catch (e: IllegalArgumentException) {
            return false
        }
        // Constant time.
        return MessageDigest.isEqual(got, mac(code, DESKTOP_LABEL, desktopFp, phoneFp))
    }
}
