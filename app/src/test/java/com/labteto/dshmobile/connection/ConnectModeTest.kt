package com.labteto.dshmobile.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The persisted remote context is `direct`; only loopback and paired relay remain distinct endpoint
 * kinds. The legacy `lan` value is accepted as the same direct context rather than recreated as a
 * user-selectable network mode.
 */
class ConnectModeTest {

    private val lan = HostConfig(id = "a", name = "desk", host = "192.168.1.20", port = 3080)
    private val loopback = HostConfig(id = "b", name = "127.0.0.1", host = "127.0.0.1", port = 3080, isLoopback = true)
    private val relay = HostConfig(
        id = "c", name = "relay", host = "192.168.1.20", port = 3443, useTls = true, relayDeviceId = "dev",
    )

    @Test
    fun `legacy lan values migrate to direct and unknown values fall back to direct`() {
        assertEquals(ConnectMode.DIRECT, ConnectMode.of("lan"))
        assertEquals(ConnectMode.DIRECT, ConnectMode.of("direct"))
        assertEquals(ConnectMode.DIRECT, ConnectMode.of(null))
        assertEquals(ConnectMode.DIRECT, ConnectMode.of("usb"))
        assertEquals(ConnectMode.LOOPBACK, ConnectMode.of("loopback"))
        assertEquals(ConnectMode.RELAY, ConnectMode.of("relay"))
    }

    /** The canonical default is direct; loopback and relay stay distinct endpoint kinds. */
    @Test
    fun `the default stored context is direct`() {
        assertEquals(ConnectMode.DIRECT, AppSettings().connectMode)
        assertEquals("direct", ConnectMode.DIRECT)
        assertEquals("loopback", ConnectMode.LOOPBACK)
        assertEquals("relay", ConnectMode.RELAY)
    }

    /**
     * The three endpoint kinds are mutually exclusive and cover every host: what the endpoint *is*
     * decides which credential is presented, and nothing else does.
     */
    @Test
    fun `each host has exactly one endpoint kind`() {
        assertFalse(lan.isLoopback)
        assertFalse(lan.isRelay)
        assertTrue(loopback.isLoopback)
        assertFalse(loopback.isRelay)
        assertTrue(relay.isRelay)
        // A relay on loopback is still a relay: the device id outranks the address.
        val localRelay = relay.copy(host = "127.0.0.1", isLoopback = true)
        assertTrue(localRelay.isRelay)
    }

    /** The old `autoConnectLan` name is gone: direct auto-connect is the persisted setting now. */
    @Test
    fun `direct auto-connect replaced the lan setting`() {
        assertFalse(AppSettings().autoConnectDirect)
        assertTrue(AppSettings().autoConnectLast)
        assertTrue(AppSettings().autoConnectLoopback)
    }

    /** A remembered host's relay credential is not inferred from its address. */
    @Test
    fun `relay identity comes from the device id alone`() {
        val sameAddressPlain = relay.copy(relayDeviceId = null)
        assertFalse(sameAddressPlain.isRelay)
        assertTrue(relay.isRelay)
        assertFalse(relay.isPlaintext)
        assertTrue(lan.isPlaintext)
    }
}
