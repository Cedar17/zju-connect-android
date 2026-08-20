package io.github.cedar17.zjuconnect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickSettingsTilePolicyTest {
    @Test
    fun retryableErrorsOfferConnect() {
        assertEquals(
            QuickSettingsTilePresentation(
                QuickSettingsTileVisualState.INACTIVE,
                QuickSettingsTileAction.CONNECT,
                QuickSettingsTileStatus.DISCONNECTED,
            ),
            quickSettingsTilePresentation(RealVpnUiState(state = "error"), entryInProgress = false),
        )
    }

    @Test
    fun transientStatesStayClickableButDoNotStartAnotherOperation() {
        listOf("preparing", "attaching", "starting", "stopping").forEach { state ->
            assertEquals(
                QuickSettingsTilePresentation(
                    QuickSettingsTileVisualState.INACTIVE,
                    QuickSettingsTileAction.NONE,
                    QuickSettingsTileStatus.CONNECTING,
                ),
                quickSettingsTilePresentation(RealVpnUiState(state = state), entryInProgress = false),
            )
        }
        assertEquals(
            QuickSettingsTilePresentation(
                QuickSettingsTileVisualState.INACTIVE,
                QuickSettingsTileAction.NONE,
                QuickSettingsTileStatus.CONNECTING,
            ),
            quickSettingsTilePresentation(RealVpnUiState(state = "idle"), entryInProgress = true),
        )
    }

    @Test
    fun freshIdlePresentationOffersConnectAfterProcessLocalEntryStateIsGone() {
        assertEquals(
            QuickSettingsTilePresentation(
                QuickSettingsTileVisualState.INACTIVE,
                QuickSettingsTileAction.CONNECT,
                QuickSettingsTileStatus.DISCONNECTED,
            ),
            quickSettingsTilePresentation(RealVpnUiState(state = "idle"), entryInProgress = false),
        )
    }

    @Test
    fun establishedAndRecoverableStatesRemainDisconnectable() {
        assertEquals(
            QuickSettingsTileAction.DISCONNECT,
            quickSettingsTilePresentation(RealVpnUiState(state = "active"), entryInProgress = true).action,
        )
        assertEquals(
            QuickSettingsTileAction.DISCONNECT,
            quickSettingsTilePresentation(
                RealVpnUiState(state = "recovering"),
                entryInProgress = true,
            ).action,
        )
        assertEquals(
            QuickSettingsTileAction.DISCONNECT,
            quickSettingsTilePresentation(
                RealVpnUiState(state = "waitingForNetwork"),
                entryInProgress = true,
            ).action,
        )
    }

    @Test
    fun foregroundLoginAndAlwaysOnHaveTheirOwnSafeActions() {
        assertEquals(
            QuickSettingsTilePresentation(
                QuickSettingsTileVisualState.INACTIVE,
                QuickSettingsTileAction.OPEN_APP,
                QuickSettingsTileStatus.OPEN_APP_FOR_LOGIN,
            ),
            quickSettingsTilePresentation(
                RealVpnUiState(state = "waitingForAuthentication"),
                entryInProgress = false,
            ),
        )
        assertEquals(
            QuickSettingsTilePresentation(
                QuickSettingsTileVisualState.ACTIVE,
                QuickSettingsTileAction.DISCONNECT,
                QuickSettingsTileStatus.ALWAYS_ON_MANAGED,
            ),
            quickSettingsTilePresentation(
                RealVpnUiState(state = "alwaysOnDisconnectBlocked"),
                entryInProgress = false,
            ),
        )
    }

    @Test
    fun entryArbiterAcceptsOnlyOneStartUntilReleased() {
        ConnectionEntryArbiter.finish(ConnectionEntryOwner.TILE_SERVICE)
        ConnectionEntryArbiter.finish(ConnectionEntryOwner.ACTIVITY)
        try {
            assertTrue(ConnectionEntryArbiter.tryBegin(ConnectionEntryOwner.TILE_SERVICE))
            assertTrue(ConnectionEntryArbiter.isInProgress())
            assertFalse(ConnectionEntryArbiter.tryBegin(ConnectionEntryOwner.ACTIVITY))
            ConnectionEntryArbiter.finish(ConnectionEntryOwner.ACTIVITY)
            assertTrue(ConnectionEntryArbiter.isInProgress())
        } finally {
            ConnectionEntryArbiter.finish(ConnectionEntryOwner.TILE_SERVICE)
        }
        assertFalse(ConnectionEntryArbiter.isInProgress())
    }
}
