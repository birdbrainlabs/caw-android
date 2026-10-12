package com.caw.android.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingTest {

    // ---- code normalization ----

    @Test
    fun normalizeDropsDashesAndSpaces() = assertEquals("ABCDEFGHJKMNPQRS", PairingCode.normalize("ABCD-EFGH JKMN - PQRS"))

    @Test
    fun normalizeUppercases() = assertEquals("ABCDEFGHJKMNPQRS", PairingCode.normalize("abcd-efgh-jkmn-pqrs"))

    @Test
    fun normalizeOIsZero() = assertEquals("0000", PairingCode.normalize("oO0o"))

    @Test
    fun normalizeIAndLAreOne() = assertEquals("1111", PairingCode.normalize("iIlL"))

    @Test
    fun normalizeTabsAndNewlines() = assertEquals("AB12", PairingCode.normalize(" a\tb\n1-2 "))

    @Test
    fun validCodeHasNoProblem() {
        assertNull(PairingCode.problem("7k3m-o9il-zz2q-hx4w"))
        assertNull(PairingCode.problem("ABCDEFGHJKMNPQRS"))
    }

    @Test
    fun wrongLengthIsAProblem() {
        assertNotNull(PairingCode.problem("ABCD-EFGH-JKMN"))
        assertNotNull(PairingCode.problem(""))
        assertNotNull(PairingCode.problem("ABCD-EFGH-JKMN-PQRS-T"))
    }

    @Test
    fun letterUIsNotCrockford() = assertNotNull(PairingCode.problem("UBCD-EFGH-JKMN-PQRS"))

    @Test
    fun displayGroupsInFours() = assertEquals("7K3M-0911-ZZ2Q-HX4W", PairingCode.display("7k3mo9ilzz2qhx4w"))

    // ---- proofs ----
    //
    // Vectors computed independently in Python (hmac + hashlib), not with this code:
    //
    //   import hmac, hashlib, base64
    //   K = "7K3M0911ZZ2QHX4W"                       # normalize("7k3m-o9il-zz2q-hx4w")
    //   D = bytes(range(32)); P = bytes(range(32, 64))
    //   base64.b64encode(hmac.new(K.encode(), b"caw-pair-phone" + D + P, hashlib.sha256).digest())
    //     -> 7yT2zeUhNAbJO9a65yzX5vxAAX7cH+vJkDPP9ghfBss=
    //   base64.b64encode(hmac.new(K.encode(), b"caw-pair-desktop" + D + P, hashlib.sha256).digest())
    //     -> UqV+DCPvZgAbhr8hQHA8t5dMt3UjWdd19TNM/vvtAEw=
    //   fp = hashlib.sha256(b"hello cert").digest()
    //     -> 14c3a7d71f96922f8d744b77f18fa6e315cdf0e15cca4a6a78de61cd0e13ee49, id = fp[:16].hex()

    private val d = ByteArray(32) { it.toByte() }
    private val p = ByteArray(32) { (it + 32).toByte() }

    @Test
    fun phoneProofMatchesPython() =
        assertEquals("7yT2zeUhNAbJO9a65yzX5vxAAX7cH+vJkDPP9ghfBss=", PairProof.phone("7k3m-o9il-zz2q-hx4w", d, p))

    @Test
    fun desktopProofMatchesPython() =
        assertEquals("UqV+DCPvZgAbhr8hQHA8t5dMt3UjWdd19TNM/vvtAEw=", PairProof.desktop("7K3M-0911-ZZ2Q-HX4W", d, p))

    @Test
    fun desktopProofIsChecked() {
        assertTrue(PairProof.desktopMatches("UqV+DCPvZgAbhr8hQHA8t5dMt3UjWdd19TNM/vvtAEw=", "7k3m-o9il-zz2q-hx4w", d, p))
        // Without padding too.
        assertTrue(PairProof.desktopMatches("UqV+DCPvZgAbhr8hQHA8t5dMt3UjWdd19TNM/vvtAEw", "7k3m-o9il-zz2q-hx4w", d, p))
        // The phone's own proof is not the desktop's.
        assertFalse(PairProof.desktopMatches("7yT2zeUhNAbJO9a65yzX5vxAAX7cH+vJkDPP9ghfBss=", "7k3m-o9il-zz2q-hx4w", d, p))
        // Another code, swapped certificates, garbage.
        assertFalse(PairProof.desktopMatches("UqV+DCPvZgAbhr8hQHA8t5dMt3UjWdd19TNM/vvtAEw=", "7k3m-o9il-zz2q-hx4x", d, p))
        assertFalse(PairProof.desktopMatches("UqV+DCPvZgAbhr8hQHA8t5dMt3UjWdd19TNM/vvtAEw=", "7k3m-o9il-zz2q-hx4w", p, d))
        assertFalse(PairProof.desktopMatches("not base64 !!", "7k3m-o9il-zz2q-hx4w", d, p))
    }

    @Test
    fun fingerprintAndIdMatchPython() {
        val fp = PairProof.fingerprint("hello cert".toByteArray())
        assertEquals("14c3a7d71f96922f8d744b77f18fa6e315cdf0e15cca4a6a78de61cd0e13ee49", PairProof.hex(fp))
        assertEquals("14c3a7d71f96922f8d744b77f18fa6e3", PairProof.shortId(fp))
    }

    // ---- QR link ----

    @Test
    fun qrLinkParses() {
        val t = PairTarget.parse("caw://pair?h=10.0.0.36&p=47820&c=ABCDEFGHJKMNPQRS&n=my-laptop")
        assertEquals(PairTarget("10.0.0.36", 47820, "ABCDEFGHJKMNPQRS", "my-laptop"), t)
    }

    @Test
    fun qrNameIsUrlDecoded() {
        val t = PairTarget.parse("caw://pair?h=10.0.0.2&p=5000&c=abcd-efgh-jkmn-pqrs&n=Alex%27s%20laptop")!!
        assertEquals("Alex's laptop", t.name)
        assertEquals(5000, t.port)
    }

    @Test
    fun qrWithoutPortUsesDefaultAndWithoutNameIsNull() {
        val t = PairTarget.parse("caw://pair?c=ABCDEFGHJKMNPQRS&h=10.0.0.2")!!
        assertEquals(47820, t.port)
        assertNull(t.name)
    }

    @Test
    fun otherLinksAreNotPairing() {
        assertNull(PairTarget.parse("https://example.com/?h=1&c=2"))
        assertNull(PairTarget.parse("caw://other?h=1.2.3.4&c=ABCDEFGHJKMNPQRS"))
        assertNull(PairTarget.parse("caw://pair?h=1.2.3.4"))
        assertNull(PairTarget.parse("caw://pair?h=1.2.3.4&p=x&c=ABCDEFGHJKMNPQRS"))
        assertNull(PairTarget.parse("hello"))
    }
}
