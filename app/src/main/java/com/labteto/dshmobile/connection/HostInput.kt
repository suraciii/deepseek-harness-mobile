package com.labteto.dshmobile.connection

import com.labteto.dshmobile.core.wire.authorityOf
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * What the connect form's host field actually said.
 *
 * People paste what they have, and what they have is usually a URL: the line the harness printed,
 * the address bar of the working web GUI, a reverse proxy's `https://agent.home`. Handing that
 * string to DNS as a hostname is how "https://agent.home" came to be diagnosed as a computer that
 * does not exist (#6). This type keeps the three facts a URL can carry apart, so the caller can
 * decide what fills the gaps.
 */
data class HostInput(
    /** The bare host: an IPv4/IPv6 literal or a name, brackets and scheme stripped. */
    val host: String,
    /** A port the field itself named (`https://x:8443`, `x:3080`), or null. */
    val port: Int?,
    /** What the scheme said about TLS — `https://` true, `http://` false, no scheme null. */
    val useTls: Boolean?,
    val basePath: String = "",
)

/**
 * Read the host field leniently; null means nothing connectable was typed.
 *
 * Accepted: a bare host, `host:port`, an `http://` or `https://` URL with optional port and an
 * preserved proxy path, and IPv6 literals with or without brackets. A port named here is more explicit
 * than the separate port field (it was typed as part of an address, not left over from a previous
 * harness), so callers let it win. Any other scheme is refused rather than guessed at.
 */
internal fun parseHostInput(raw: String): HostInput? {
    val trimmed = raw.trim()
    if (trimmed.isBlank()) return null

    var useTls: Boolean? = null
    var rest = trimmed
    val schemeEnd = trimmed.indexOf("://")
    if (schemeEnd >= 0) {
        useTls = when (trimmed.take(schemeEnd).lowercase()) {
            "http" -> false
            "https" -> true
            else -> return null
        }
        rest = trimmed.substring(schemeEnd + 3)
    }

    val path = rest.substringBefore('?').substringBefore('#').substringAfter('/', "")
    if (rest.substringBefore('/').contains('@') || rest.contains('\\') || rest.any { it.isWhitespace() }) return null
    val basePath = if (path.isEmpty()) "" else {
        val url = ("https://placeholder/" + path).toHttpUrlOrNull() ?: return null
        url.encodedPath.trimEnd('/')
    }
    rest = rest.takeWhile { it != '/' && it != '?' && it != '#' }
    if (rest.isBlank()) return null

    val host: String
    var port: Int? = null
    when {
        // Bracketed IPv6, the URL form: [::1] or [::1]:3080.
        rest.startsWith("[") -> {
            val close = rest.indexOf(']')
            if (close <= 1) return null
            host = rest.substring(1, close)
            val after = rest.substring(close + 1)
            when {
                after.isEmpty() -> {}
                after.startsWith(":") -> port = after.drop(1).toValidPort() ?: return null
                else -> return null
            }
        }
        // More than one colon and no brackets can only be a bare IPv6 literal — a port after an
        // unbracketed one is unparseable, which is exactly why URLs bracket them.
        rest.count { it == ':' } > 1 -> host = rest
        rest.contains(':') -> {
            host = rest.substringBefore(':')
            port = rest.substringAfter(':').toValidPort() ?: return null
        }
        else -> host = rest
    }
    if (host.isBlank() || host.any { it.isWhitespace() }) return null
    return HostInput(host = host, port = port, useTls = useTls, basePath = basePath)
}

private fun String.toValidPort(): Int? = toIntOrNull()?.takeIf { it in 1..65535 }

/**
 * `host:port` as a URL requires it — an IPv6 literal goes back into brackets. The display
 * authority everywhere else stays bare; this form is only for building URLs.
 */
internal fun urlAuthority(host: String, port: Int): String = authorityOf(host, port)

/** The scheme-qualified base URL for one harness endpoint. */
internal fun harnessBaseUrl(host: String, port: Int, useTls: Boolean, basePath: String = ""): String =
    (if (useTls) "https://" else "http://") + urlAuthority(host, port) + basePath

internal fun endpointKey(host: String, port: Int, useTls: Boolean, basePath: String = ""): String =
    (if (useTls) "https://" else "") + urlAuthority(host, port) + basePath

/**
 * The endpoint one address field turns into: authority, TLS posture and whether it is this device.
 *
 * [parseHostInput] says what the field carried; this decides the gaps. An explicit scheme is
 * authoritative in both directions; a bare port of 443 means a TLS reverse proxy, because the
 * harness never serves there and plaintext to that port is an answer nobody can read. Everything
 * else defaults to the harness's own port. `null` when nothing connectable was typed — the same
 * refusal [parseHostInput] already makes for an unusable string.
 *
 * A free function so the mapping is pinned without a device: it is the whole of "what the address
 * means", and the connect screen no longer has a mode that would overrule it.
 */
internal fun resolveEndpoint(raw: String, defaultPort: Int): HostEndpoint? {
    val input = parseHostInput(raw) ?: return null
    val port = input.port ?: when (input.useTls) { true -> 443; false -> 80; null -> defaultPort }
    if (port !in 1..65535) return null
    val useTls = input.useTls ?: (port == 443)
    return HostEndpoint(
        host = input.host,
        port = port,
        useTls = useTls,
        basePath = input.basePath,
        authority = endpointKey(input.host, port, useTls, input.basePath),
        isLoopback = input.host == "127.0.0.1" || input.host == "localhost",
    )
}

/** One parsed address, ready to be attempted or remembered. */
data class HostEndpoint(
    val host: String,
    val port: Int,
    val useTls: Boolean,
    val basePath: String,
    /** Complete endpoint identity, including the reverse-proxy root. */
    val authority: String,
    /** The harness is on this device: `127.0.0.1` or `localhost`. */
    val isLoopback: Boolean,
) {
    /** Origin to address this endpoint by. */
    val baseUrl: String get() = harnessBaseUrl(host, port, useTls, basePath)

    /** Whether traffic to this endpoint travels in the clear. */
    val isPlaintext: Boolean get() = !useTls
}
