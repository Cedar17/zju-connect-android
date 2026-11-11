package io.github.cedar17.zjuconnect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxySettingsTest {
    @Test
    fun defaultSettingsAreValid() {
        assertTrue(ProxySettings().isValid())
    }

    @Test
    fun portValidationMatchesTheBridgeRules() {
        assertFalse(ProxySettings(enabled = true, socksPort = 0, httpPort = 11081).isValid())
        assertFalse(ProxySettings(enabled = true, socksPort = 70_000, httpPort = 11081).isValid())
        assertFalse(ProxySettings(enabled = true, socksPort = 11080, httpPort = -1).isValid())
        assertFalse(ProxySettings(enabled = true, socksPort = 11080, httpPort = 11080).isValid())
        assertTrue(ProxySettings(enabled = true, socksPort = 11080, httpPort = 0).isValid())
        assertTrue(ProxySettings(enabled = true, socksPort = 1, httpPort = 65_535).isValid())
    }

    @Test
    fun socksPortParsingAcceptsOnlyConcretePorts() {
        assertEquals(11080, parseProxyPort("11080"))
        assertEquals(1, parseProxyPort("1"))
        assertNull(parseProxyPort("0"))
        assertNull(parseProxyPort("65536"))
        assertNull(parseProxyPort(""))
        assertNull(parseProxyPort("11080x"))
    }

    @Test
    fun httpPortParsingAcceptsZeroToDisable() {
        assertEquals(0, parseProxyHttpPort("0"))
        assertEquals(11081, parseProxyHttpPort(" 11081 "))
        assertNull(parseProxyHttpPort("-1"))
        assertNull(parseProxyHttpPort("65536"))
        assertNull(parseProxyHttpPort("x"))
    }
}
