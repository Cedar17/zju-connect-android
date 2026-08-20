package io.github.cedar17.zjuconnect

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.net.Inet4Address

/** Tracks only opaque, in-memory properties of non-VPN underlay networks. */
internal class UnderlayNetworkMonitor(
    context: Context,
    private val onSnapshotChanged: (UnderlayNetworkSnapshot) -> Unit,
) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val lock = Any()
    private val networks = mutableMapOf<Long, UnderlayNetworkFingerprint>()
    private var revision = 0L
    private var registered = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            update(network.networkHandle) { existing ->
                existing ?: UnderlayNetworkFingerprint(networkHandle = network.networkHandle)
            }
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            if (!capabilities.isEligibleUnderlay()) {
                remove(network.networkHandle)
                return
            }
            update(network.networkHandle) { existing ->
                (existing ?: UnderlayNetworkFingerprint(networkHandle = network.networkHandle)).copy(
                    transportMask = capabilities.transportMask(),
                    suspended = !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED),
                )
            }
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            update(network.networkHandle) { existing ->
                (existing ?: UnderlayNetworkFingerprint(networkHandle = network.networkHandle)).copy(
                    linkIdentityHash = linkProperties.opaqueIpv4IdentityHash(),
                )
            }
        }

        override fun onLost(network: Network) {
            remove(network.networkHandle)
        }
    }

    @Suppress("DEPRECATION")
    fun start(): UnderlayNetworkSnapshot {
        val initialNetworks = readCurrentNetworks()
        synchronized(lock) {
            if (registered) return snapshotLocked()
            networks.putAll(initialNetworks)
            registered = true
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        try {
            connectivity.registerNetworkCallback(request, callback)
        } catch (error: RuntimeException) {
            synchronized(lock) { registered = false }
            throw error
        }
        reconcileCurrentNetworks()
        return snapshot()
    }

    fun stop() {
        val shouldUnregister = synchronized(lock) {
            if (!registered) {
                false
            } else {
                registered = false
                true
            }
        }
        if (shouldUnregister) {
            runCatching { connectivity.unregisterNetworkCallback(callback) }
        }
    }

    fun snapshot(): UnderlayNetworkSnapshot = synchronized(lock) { snapshotLocked() }

    /**
     * Captures the physical system-default network immediately before VPN setup.
     *
     * This runs on the service start path rather than from a NetworkCallback, so
     * the synchronous ConnectivityManager reads are not used to interpret a
     * callback that may already be stale.
     */
    fun captureSessionStart(): UnderlaySessionStart {
        val activeUnderlay = readActiveUnderlay()
        return UnderlaySessionStart(
            snapshot = snapshot(),
            activeUnderlay = activeUnderlay,
        )
    }

    private fun update(
        networkHandle: Long,
        transform: (UnderlayNetworkFingerprint?) -> UnderlayNetworkFingerprint,
    ) {
        val changed = synchronized(lock) {
            if (!registered) return@synchronized null
            val previous = networks[networkHandle]
            val next = transform(previous)
            if (previous == next) {
                null
            } else {
                networks[networkHandle] = next
                revision += 1
                snapshotLocked()
            }
        }
        changed?.let(onSnapshotChanged)
    }

    private fun remove(networkHandle: Long) {
        val changed = synchronized(lock) {
            if (!registered) return@synchronized null
            if (networks.remove(networkHandle) == null) {
                null
            } else {
                revision += 1
                snapshotLocked()
            }
        }
        changed?.let(onSnapshotChanged)
    }

    @Suppress("DEPRECATION")
    private fun readCurrentNetworks(): Map<Long, UnderlayNetworkFingerprint> = buildMap {
        connectivity.allNetworks.forEach { network ->
            val capabilities = connectivity.getNetworkCapabilities(network) ?: return@forEach
            if (!capabilities.isEligibleUnderlay()) return@forEach
            val linkProperties = connectivity.getLinkProperties(network)
            put(
                network.networkHandle,
                UnderlayNetworkFingerprint(
                    networkHandle = network.networkHandle,
                    transportMask = capabilities.transportMask(),
                    suspended = !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED),
                    linkIdentityHash = linkProperties?.opaqueIpv4IdentityHash() ?: 0,
                ),
            )
        }
    }

    private fun readActiveUnderlay(): UnderlayNetworkFingerprint? {
        val network = connectivity.activeNetwork ?: return null
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return null
        if (!capabilities.isEligibleUnderlay()) return null
        val linkProperties = connectivity.getLinkProperties(network)
        return UnderlayNetworkFingerprint(
            networkHandle = network.networkHandle,
            transportMask = capabilities.transportMask(),
            suspended = !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED),
            linkIdentityHash = linkProperties?.opaqueIpv4IdentityHash() ?: 0,
        )
    }

    private fun reconcileCurrentNetworks() {
        val current = readCurrentNetworks()
        val changed = synchronized(lock) {
            if (!registered || current == networks) {
                null
            } else {
                networks.clear()
                networks.putAll(current)
                revision += 1
                snapshotLocked()
            }
        }
        changed?.let(onSnapshotChanged)
    }

    private fun snapshotLocked(): UnderlayNetworkSnapshot = UnderlayNetworkSnapshot(
        revision = revision,
        networks = networks.values.toSet(),
    )
}

private fun NetworkCapabilities.isEligibleUnderlay(): Boolean =
    hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)

private fun NetworkCapabilities.transportMask(): Int {
    var mask = 0
    listOf(
        NetworkCapabilities.TRANSPORT_CELLULAR,
        NetworkCapabilities.TRANSPORT_WIFI,
        NetworkCapabilities.TRANSPORT_BLUETOOTH,
        NetworkCapabilities.TRANSPORT_ETHERNET,
        NetworkCapabilities.TRANSPORT_WIFI_AWARE,
        NetworkCapabilities.TRANSPORT_LOWPAN,
    ).forEachIndexed { index, transport ->
        if (hasTransport(transport)) mask = mask or (1 shl index)
    }
    return mask
}

/** Hashes link details so addresses can affect recovery without becoming loggable state. */
private fun LinkProperties.opaqueIpv4IdentityHash(): Int {
    val addresses = linkAddresses
        .asSequence()
        .filter { it.address is Inet4Address }
        .map { "${it.address.hostAddress}/${it.prefixLength}" }
        .sorted()
        .toList()
    val defaultRoutes = routes
        .asSequence()
        .filter { it.isDefaultRoute && it.destination.address is Inet4Address }
        .map { route ->
            "${route.destination}/${route.gateway?.hostAddress.orEmpty()}/${route.`interface`.orEmpty()}"
        }
        .sorted()
        .toList()
    return listOf(interfaceName.orEmpty(), addresses, defaultRoutes).hashCode()
}
