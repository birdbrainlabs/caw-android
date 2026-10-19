package com.caw.android.remote

import com.caw.android.remote.AddressOrder.Family
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AddressOrderTest {

    private val v4 = "192.168.1.20"
    private val linkLocal = "fe80::1c2b:3aff:fe4d:5e6f%wlan0"

    @Test
    fun families() {
        assertEquals(Family.V4, AddressOrder.family(v4))
        assertEquals(Family.V6, AddressOrder.family(linkLocal))
        assertEquals(Family.V6, AddressOrder.family("fe80::1%23"))
        assertEquals(Family.V6, AddressOrder.family("[2001:db8::1]"))
        assertEquals(Family.V6, AddressOrder.family("::ffff:192.168.1.20"))
        assertEquals(Family.NAME, AddressOrder.family("Desk.local"))
        assertEquals(Family.NAME, AddressOrder.family("1.2.3"))
    }

    @Test
    fun theBugFromOct11StoredIpv4IsStillTriedFirstWhenTheLookupFoundOnlyLinkLocal() {
        // The lookup after the Wi-Fi drop found only the link-local address.
        assertEquals(listOf(v4, linkLocal), AddressOrder.candidates(v4, listOf(linkLocal)))
        // And it does not replace the stored IPv4 address.
        assertEquals(v4, AddressOrder.toStore(v4, listOf(linkLocal)))
    }

    @Test
    fun ipv4ThenNamesThenIpv6EachOnce() {
        val got = AddressOrder.candidates(linkLocal, listOf("2001:db8::5", "192.168.1.50", "Desk.local", "FE80::1C2B:3AFF:FE4D:5E6F%wlan0", "192.168.1.50"))
        assertEquals(listOf("192.168.1.50", "Desk.local", linkLocal, "2001:db8::5"), got)
    }

    @Test
    fun theStoredAddressLeadsItsFamily() {
        assertEquals(listOf(v4, "192.168.1.99", linkLocal), AddressOrder.candidates(v4, listOf(linkLocal, "192.168.1.99")))
    }

    @Test
    fun scopeIdsAndBracketsSurvive() {
        assertEquals(listOf("fe80::1%23"), AddressOrder.candidates("[fe80::1%23]", emptyList()))
        assertEquals(linkLocal, AddressOrder.bare("[$linkLocal]"))
    }

    @Test
    fun whatToStoreAfterALookup() {
        // A new IPv4 address replaces the old one (DHCP moved the computer).
        assertEquals("192.168.1.99", AddressOrder.toStore(v4, listOf(linkLocal, "192.168.1.99")))
        // A stored IPv6 address (from before the fix) gives way to any IPv4 address found.
        assertEquals(v4, AddressOrder.toStore(linkLocal, listOf("2001:db8::5", v4)))
        // Only IPv6 known: the lookup's first.
        assertEquals("2001:db8::5", AddressOrder.toStore(linkLocal, listOf("2001:db8::5")))
        // A name typed when pairing stays.
        assertEquals("Desk.local", AddressOrder.toStore("Desk.local", listOf(v4)))
        // Nothing found: keep what is stored.
        assertEquals(v4, AddressOrder.toStore(v4, emptyList()))
    }

    @Test
    fun whatToStoreAfterAConnection() {
        assertEquals(v4, AddressOrder.afterSuccess(linkLocal, v4))
        assertEquals("192.168.1.99", AddressOrder.afterSuccess(v4, "192.168.1.99"))
        assertNull(AddressOrder.afterSuccess(v4, v4))
        assertNull(AddressOrder.afterSuccess(v4, linkLocal))
        assertNull(AddressOrder.afterSuccess("Desk.local", v4))
    }
}
