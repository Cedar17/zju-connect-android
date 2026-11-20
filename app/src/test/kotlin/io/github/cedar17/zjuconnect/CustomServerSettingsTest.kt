package io.github.cedar17.zjuconnect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomServerSettingsTest {
    @Test
    fun disabledSettingsFallBackToZjuDefault() {
        val target = CustomServerSettings(enabled = false, host = "", port = 1).effectiveTarget()
        assertEquals(CustomServerTarget.DEFAULT, target)
    }

    @Test
    fun enabledValidSettingsResolveToCustomTarget() {
        val settings = CustomServerSettings(enabled = true, host = "atrust.example.edu", port = 8443)
        assertEquals(CustomServerTarget("atrust.example.edu", 8443), settings.effectiveTarget())
    }

    @Test
    fun enabledInvalidSettingsBlockTheConnection() {
        assertNull(CustomServerSettings(enabled = true, host = "", port = 8443).effectiveTarget())
        assertNull(
            CustomServerSettings(enabled = true, host = "https://atrust.example.edu", port = 8443)
                .effectiveTarget(),
        )
        assertNull(CustomServerSettings(enabled = true, host = "atrust.example.edu", port = 0).effectiveTarget())
        assertNull(CustomServerSettings(enabled = true, host = "atrust.example.edu", port = 70_000).effectiveTarget())
    }

    @Test
    fun hostValidationMatchesTheGoBridgeRules() {
        assertTrue(isValidCustomServerHost("vpn.zju.edu.cn"))
        assertTrue(isValidCustomServerHost("atrust.example.edu."))
        assertTrue(isValidCustomServerHost("10.10.0.1"))
        assertTrue(isValidCustomServerHost("::1"))
        assertTrue(isValidCustomServerHost("[::1]"))
        assertTrue(isValidCustomServerHost("host-1.example"))
        assertFalse(isValidCustomServerHost(""))
        assertFalse(isValidCustomServerHost("https://vpn.zju.edu.cn"))
        assertFalse(isValidCustomServerHost("vpn.zju.edu.cn/path"))
        assertFalse(isValidCustomServerHost("vpn.zju.edu.cn:8443"))
        assertFalse(isValidCustomServerHost("under_score.example"))
        assertFalse(isValidCustomServerHost("-leading.example"))
        assertFalse(isValidCustomServerHost("trailing-.example"))
        assertFalse(isValidCustomServerHost("vpn example"))
        assertFalse(isValidCustomServerHost("a".repeat(254)))
        assertFalse(isValidCustomServerHost("10.0.0.256"))
        assertFalse(isValidCustomServerHost("01.2.3.4"))
    }

    @Test
    fun hostNormalizationReducesPastedUrlsToBareHosts() {
        assertEquals("vpn.zju.edu.cn", normalizeCustomServerHost("vpn.zju.edu.cn"))
        assertEquals("vpn.zju.edu.cn", normalizeCustomServerHost("  vpn.zju.edu.cn  "))
        assertEquals("vpn.zju.edu.cn", normalizeCustomServerHost("https://vpn.zju.edu.cn"))
        assertEquals("vpn.zju.edu.cn", normalizeCustomServerHost("https://vpn.zju.edu.cn/"))
        assertEquals("vpn.zju.edu.cn", normalizeCustomServerHost("https://vpn.zju.edu.cn/portal"))
        assertEquals("vpn.zju.edu.cn", normalizeCustomServerHost("https://user:pass@vpn.zju.edu.cn"))
        assertEquals("vpn.zju.edu.cn", normalizeCustomServerHost("vpn.zju.edu.cn:8443"))
        assertEquals("[::1]", normalizeCustomServerHost("[::1]"))
        assertNull(normalizeCustomServerHost("   "))
        assertNull(normalizeCustomServerHost("https://"))
    }

    @Test
    fun portParsingAcceptsOnlyConcretePorts() {
        assertEquals(443, parseCustomServerPort("443"))
        assertEquals(8443, parseCustomServerPort(" 8443 "))
        assertEquals(1, parseCustomServerPort("1"))
        assertEquals(65535, parseCustomServerPort("65535"))
        assertNull(parseCustomServerPort("0"))
        assertNull(parseCustomServerPort("65536"))
        assertNull(parseCustomServerPort("-1"))
        assertNull(parseCustomServerPort("443x"))
        assertNull(parseCustomServerPort(""))
    }

    @Test
    fun clipboardImportRecognizesBareHostsAndHostPortPairs() {
        assertEquals(ParsedServerInput("vpn.zju.edu.cn", null), parseCustomServerInput("vpn.zju.edu.cn"))
        assertEquals(
            ParsedServerInput("vpn.zju.edu.cn", 8443),
            parseCustomServerInput("vpn.zju.edu.cn:8443"),
        )
        assertEquals(
            ParsedServerInput("vpn.zju.edu.cn", 8443),
            parseCustomServerInput("  vpn.zju.edu.cn:8443  "),
        )
        assertEquals(
            ParsedServerInput("vpn.zju.edu.cn", 8443),
            parseCustomServerInput("vpn.zju.edu.cn:8443/portal"),
        )
        assertEquals(
            ParsedServerInput("vpn.zju.edu.cn", null),
            parseCustomServerInput("vpn.zju.edu.cn/portal"),
        )
        assertEquals(ParsedServerInput("10.10.0.1", 8080), parseCustomServerInput("10.10.0.1:8080"))
    }

    @Test
    fun clipboardImportRecognizesUrlsWithAndWithoutScheme() {
        assertEquals(
            ParsedServerInput("vpn.zju.edu.cn", 8443),
            parseCustomServerInput("https://vpn.zju.edu.cn:8443"),
        )
        assertEquals(
            ParsedServerInput("vpn.zju.edu.cn", 8443),
            parseCustomServerInput("https://vpn.zju.edu.cn:8443/portal/index.html?q=1#frag"),
        )
        // An explicit port beats the scheme default; a scheme without one maps
        // to its well-known port.
        assertEquals(ParsedServerInput("vpn.zju.edu.cn", 443), parseCustomServerInput("https://vpn.zju.edu.cn"))
        assertEquals(ParsedServerInput("vpn.zju.edu.cn", 80), parseCustomServerInput("http://vpn.zju.edu.cn"))
        assertEquals(
            ParsedServerInput("vpn.zju.edu.cn", 443),
            parseCustomServerInput("https://user:pass@vpn.zju.edu.cn"),
        )
        // Scheme-less URLs have no default port, so the current port stays.
        assertEquals(
            ParsedServerInput("vpn.zju.edu.cn", null),
            parseCustomServerInput("user:pass@vpn.zju.edu.cn/portal"),
        )
        // Unknown schemes carry no port hint.
        assertEquals(
            ParsedServerInput("vpn.zju.edu.cn", null),
            parseCustomServerInput("ftp://vpn.zju.edu.cn"),
        )
    }

    @Test
    fun clipboardImportHandlesIpv6AndRejectsGarbage() {
        assertEquals(ParsedServerInput("[::1]", 8443), parseCustomServerInput("[::1]:8443"))
        // A scheme without an explicit port maps to its well-known default,
        // exactly like "https://host" does.
        assertEquals(ParsedServerInput("[::1]", 443), parseCustomServerInput("https://[::1]/portal"))
        assertEquals(ParsedServerInput("[::1]", null), parseCustomServerInput("[::1]/portal"))
        assertNull(parseCustomServerInput(""))
        assertNull(parseCustomServerInput("   "))
        assertNull(parseCustomServerInput("https://"))
        assertNull(parseCustomServerInput("not a host"))
        assertNull(parseCustomServerInput("https://vpn.zju.edu.cn:70000"))
        assertNull(parseCustomServerInput("10.0.0.256:8443"))
        assertNull(parseCustomServerInput("https://[::1"))
    }
}
