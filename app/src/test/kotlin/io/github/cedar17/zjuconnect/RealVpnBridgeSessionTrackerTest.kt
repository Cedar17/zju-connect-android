package io.github.cedar17.zjuconnect

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RealVpnBridgeSessionTrackerTest {
    @Test
    fun onlyNewestSessionGenerationIsAccepted() {
        val tracker = RealVpnBridgeSessionTracker()
        val first = tracker.beginSession()
        assertTrue(tracker.accepts(first))

        val second = tracker.beginSession()

        assertFalse(tracker.accepts(first))
        assertTrue(tracker.accepts(second))
    }
}
