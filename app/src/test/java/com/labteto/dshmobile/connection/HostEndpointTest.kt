package com.labteto.dshmobile.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One address field, one endpoint.
 *
 * The connect screen has no network-type choice, so everything the user pasted has to be turned
 * into a single endpoint here. These are the rules that used to be spread between the parser, the
 * port field and the subnet guard — and the guard is gone, so an address is judged only by whether
 * it names a reachable harness, never by which network it is on.
 */
class HostEndpointTest {

    private fun endpoint(raw: String) = resolveEndpoint(raw, defaultPort = 3080)

    /** A LAN address is not special: it parses exactly like any other direct endpoint. */
    @Test
    fun `a lan address is an ordinary direct endpoint`() {
        val lan = endpoint("192.168.1.20")!!
        assertEquals("192.168.1.20", lan.host)
        assertEquals(3080, lan.port)
        assertTrue(lan.isPlaintext)
        assertFalse(lan.isLoopback)
        assertEquals("192.168.1.20:3080", lan.authority)
        assertEquals("http://192.168.1.20:3080", lan.baseUrl)
    }

    /**
     * The case the subnet gate used to refuse up front: an address outside the phone's own /24.
     * It is a plain endpoint to attempt, so nothing about it may be rejected here.
     */
    @Test
    fun `an address far outside the local subnet is still an endpoint`() {
        for (raw in listOf("10.0.0.5", "172.16.9.9", "8.8.8.8")) {
            val far = endpoint(raw)!!
            assertEquals(raw, far.host)
            assertFalse(far.isLoopback)
            assertEquals("$raw:3080", far.authority)
        }
    }

    /** A Tailnet-style name and a public HTTPS hostname are the same kind of thing. */
    @Test
    fun `a hostname and an https url are direct endpoints`() {
        val tailnet = endpoint("agent.tail1234.ts.net")!!
        assertEquals("agent.tail1234.ts.net", tailnet.host)
        assertFalse(tailnet.useTls)
        assertEquals("agent.tail1234.ts.net:3080", tailnet.authority)

        val public = endpoint("https://agent.example.com")!!
        assertTrue(public.useTls)
        assertEquals(443, public.port)
        assertFalse(public.isPlaintext)
        assertEquals("https://agent.example.com:443", public.authority)
        assertEquals("https://agent.example.com:443", public.baseUrl)
    }

    /** A reverse proxy's path prefix is part of the endpoint's identity, not of its host. */
    @Test
    fun `a proxy path is kept as part of the endpoint identity`() {
        val proxied = endpoint("https://gateway:8443/team/dsh")!!
        assertEquals("gateway", proxied.host)
        assertEquals("/team/dsh", proxied.basePath)
        assertEquals("https://gateway:8443/team/dsh", proxied.authority)
        assertEquals("https://gateway:8443/team/dsh", proxied.baseUrl)
    }

    /**
     * Port 443 without a scheme means TLS: the harness never serves there, so plaintext to it
     * would produce an answer nobody can read. An explicit `http://` on the same port is still
     * plaintext — the scheme is authoritative in both directions.
     */
    @Test
    fun `a bare 443 implies tls but an explicit scheme wins`() {
        val byPort = endpoint("agent.example.com:443")!!
        assertTrue(byPort.useTls)
        assertEquals(443, byPort.port)

        val explicit = endpoint("http://agent.example.com:443")!!
        assertFalse(explicit.useTls)
    }

    @Test
    fun `a port named in the field wins over the default`() {
        val named = endpoint("192.168.1.20:8443")!!
        assertEquals(8443, named.port)
        assertTrue(named.isPlaintext)

        val secure = endpoint("https://192.168.1.20:8443")!!
        assertEquals(8443, secure.port)
        assertTrue(secure.useTls)
    }

    /** The whole `dsh web` startup line is accepted as-is; the token is stripped before parsing. */
    @Test
    fun `a printed startup line resolves to its bare address`() {
        val line = "dsh web: http://192.168.1.20:3080/?token=abc123"
        val fromLine = resolveEndpoint(
            com.labteto.dshmobile.core.wire.HarnessLaunchLine.parse(line)!!.baseUrl,
            defaultPort = 3080,
        )!!
        assertEquals("192.168.1.20", fromLine.host)
        assertEquals(3080, fromLine.port)
        assertTrue(fromLine.isPlaintext)
    }

    @Test
    fun `loopback addresses are recognised as this phone`() {
        assertTrue(endpoint("127.0.0.1")!!.isLoopback)
        assertTrue(endpoint("127.0.0.1:3080")!!.isLoopback)
        assertTrue(endpoint("localhost:3080")!!.isLoopback)
        assertFalse(endpoint("192.168.1.20")!!.isLoopback)
    }

    /** Nothing connectable was typed, so there is no endpoint to attempt. */
    @Test
    fun `unusable input resolves to nothing`() {
        assertNull(endpoint(""))
        assertNull(endpoint("   "))
        assertNull(endpoint("ftp://agent.home"))
        assertNull(endpoint("agent.home:notaport"))
        assertNull(endpoint("a b"))
    }
    /**
     * The same host reached over `http://` and over `https://` are distinct endpoints, so a
     * remembered plaintext session is never reused for an encrypted address.
     */
    @Test
    fun `the scheme is part of the endpoint identity`() {
        val plain = endpoint("http://agent.example.com")!!
        val secure = endpoint("https://agent.example.com")!!
        assertEquals("agent.example.com:80", plain.authority)
        assertEquals("https://agent.example.com:443", secure.authority)
        assertFalse(plain.authority == secure.authority)
        assertTrue(plain.isPlaintext)
        assertFalse(secure.isPlaintext)
    }
}
