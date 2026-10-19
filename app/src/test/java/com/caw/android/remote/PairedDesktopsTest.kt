package com.caw.android.remote

import org.junit.Assert.assertEquals
import org.junit.Test

class PairedDesktopsTest {

    private val desk = PairedDesktop("0123456789abcdef0123456789abcdef", "Desk", "ab".repeat(32), "192.168.1.20", 47820)
    private val lab = PairedDesktop("fedcba9876543210fedcba9876543210", "Lab", "cd".repeat(32), "fe80::1%wlan0", 47821)

    @Test
    fun roundTrips() {
        assertEquals(listOf(desk, lab), PairedDesktops.decode(PairedDesktops.encode(listOf(desk, lab))))
        assertEquals(emptyList<PairedDesktop>(), PairedDesktops.decode(PairedDesktops.encode(emptyList())))
    }

    @Test
    fun nothingOrGarbageIsNoComputers() {
        assertEquals(emptyList<PairedDesktop>(), PairedDesktops.decode(null))
        assertEquals(emptyList<PairedDesktop>(), PairedDesktops.decode("not json"))
    }

    @Test
    fun incompleteEntriesAreLeftOut() {
        val stored = """[{"id":"","name":"x","fingerprint":"ab","host":"h"},{"id":"${desk.id}","fingerprint":"${desk.fingerprintHex}","host":"${desk.host}"}]"""
        assertEquals(listOf(desk.copy(name = "computer", port = PairTarget.DEFAULT_PORT)), PairedDesktops.decode(stored))
    }

    @Test
    fun withAddsAtTheEndOrReplacesInPlace() {
        assertEquals(listOf(desk, lab), PairedDesktops.with(listOf(desk), lab))
        val renamed = desk.copy(name = "Desk 2")
        assertEquals(listOf(renamed, lab), PairedDesktops.with(listOf(desk, lab), renamed))
    }

    @Test
    fun without() {
        assertEquals(listOf(lab), PairedDesktops.without(listOf(desk, lab), desk.id))
        assertEquals(listOf(desk, lab), PairedDesktops.without(listOf(desk, lab), "nope"))
    }

    @Test
    fun labelsAddTheAddressOnlyWhenTwoShareAName() {
        assertEquals("Desk", PairedDesktops.label(desk, listOf(desk, lab)))
        val twin = lab.copy(name = "desk")
        assertEquals("Desk (192.168.1.20)", PairedDesktops.label(desk, listOf(desk, twin)))
        assertEquals("desk (fe80::1%wlan0)", PairedDesktops.label(twin, listOf(desk, twin)))
    }
}
