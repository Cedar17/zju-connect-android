package io.github.cedar17.zjuconnect

/**
 * Generates listener identities so callbacks from a replaced Go session cannot
 * update the current Android lifecycle.
 *
 * Its caller serializes access with the service state lock.
 */
internal class RealVpnBridgeSessionTracker {
    private var currentGeneration = 0L

    fun beginSession(): Long {
        currentGeneration += 1
        return currentGeneration
    }

    fun accepts(generation: Long): Boolean = generation == currentGeneration
}
