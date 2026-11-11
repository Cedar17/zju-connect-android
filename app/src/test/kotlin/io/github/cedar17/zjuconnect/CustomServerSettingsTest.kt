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
}
