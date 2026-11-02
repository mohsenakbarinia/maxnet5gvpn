package com.example.core.resilience

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import java.net.Inet4Address
import java.net.Inet6Address

/**
 * Abstraction over Android network monitoring so multi-network transition logic can be
 * deterministically unit-tested without requiring a live system ConnectivityManager.
 */
interface NetworkMonitor {
    val activeNetworkSnapshot: StateFlow<NetworkSnapshot>
    fun observeNetworkSnapshots(): Flow<NetworkSnapshot>
    fun getActivePhysicalNetwork(): Network?
    fun refreshSnapshot(): NetworkSnapshot
}

/**
 * Monitors underlying non-VPN networks (`NET_CAPABILITY_INTERNET` + `NET_CAPABILITY_NOT_VPN`)
 * using [ConnectivityManager.registerNetworkCallback], debounces rapid Wi-Fi <-> Cellular handoff
 * oscillations (800 ms window), and filters out no-op RSSI/signal-strength ticks by comparing
 * meaningful transport, handle, validation, and DNS server attributes (Section 13.1).
 */
class AndroidNetworkMonitor(
    context: Context
) : NetworkMonitor {

    private val appContext = context.applicationContext
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    @Volatile
    private var latestPhysicalNetwork: Network? = null

    private val _activeNetworkSnapshot = MutableStateFlow(queryCurrentSnapshot())
    override val activeNetworkSnapshot: StateFlow<NetworkSnapshot> = _activeNetworkSnapshot.asStateFlow()

    override fun getActivePhysicalNetwork(): Network? {
        val current = latestPhysicalNetwork
        if (current != null) {
            val caps = runCatching { connectivityManager.getNetworkCapabilities(current) }.getOrNull()
            if (caps != null &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            ) {
                return current
            }
        }
        return findBestNonVpnNetwork()
    }

    override fun refreshSnapshot(): NetworkSnapshot {
        val snap = queryCurrentSnapshot()
        _activeNetworkSnapshot.value = snap
        return snap
    }

    @OptIn(FlowPreview::class)
    val networkSnapshots: Flow<NetworkSnapshot>
        get() = observeNetworkSnapshots()

    @OptIn(FlowPreview::class)
    override fun observeNetworkSnapshots(): Flow<NetworkSnapshot> = callbackFlow {
        val initial = refreshSnapshot()
        trySend(initial)

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val snap = buildSnapshotForNetwork(network)
                if (snap.isNotVpn) {
                    latestPhysicalNetwork = network
                    _activeNetworkSnapshot.value = snap
                    trySend(snap)
                }
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                if (!networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) {
                    return
                }
                latestPhysicalNetwork = network
                val linkProps = runCatching { connectivityManager.getLinkProperties(network) }.getOrNull()
                val snap = buildSnapshot(network, networkCapabilities, linkProps)
                _activeNetworkSnapshot.value = snap
                trySend(snap)
            }

            override fun onLinkPropertiesChanged(
                network: Network,
                linkProperties: LinkProperties
            ) {
                val caps = runCatching { connectivityManager.getNetworkCapabilities(network) }.getOrNull()
                    ?: return
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) {
                    return
                }
                latestPhysicalNetwork = network
                val snap = buildSnapshot(network, caps, linkProperties)
                _activeNetworkSnapshot.value = snap
                trySend(snap)
            }

            override fun onLost(network: Network) {
                val fallback = findBestNonVpnNetwork()
                latestPhysicalNetwork = fallback
                val snap = if (fallback != null) {
                    buildSnapshotForNetwork(fallback)
                } else {
                    NetworkSnapshot.NONE
                }
                _activeNetworkSnapshot.value = snap
                trySend(snap)
            }
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()

        var registered = false
        runCatching {
            connectivityManager.registerNetworkCallback(request, callback)
            registered = true
        }

        awaitClose {
            if (registered) {
                runCatching { connectivityManager.unregisterNetworkCallback(callback) }
            }
        }
    }
        .debounce(DEBOUNCE_WINDOW_MS)
        .distinctUntilChanged { old, new ->
            isMeaningfullyEquivalent(old, new)
        }

    private fun queryCurrentSnapshot(): NetworkSnapshot {
        val net = findBestNonVpnNetwork() ?: return NetworkSnapshot.NONE
        latestPhysicalNetwork = net
        return buildSnapshotForNetwork(net)
    }

    private fun findBestNonVpnNetwork(): Network? {
        return runCatching {
            val active = connectivityManager.activeNetwork
            if (active != null) {
                val activeCaps = connectivityManager.getNetworkCapabilities(active)
                if (activeCaps != null &&
                    activeCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    activeCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                ) {
                    return@runCatching active
                }
            }
            @Suppress("DEPRECATION")
            val all = connectivityManager.allNetworks
            all.firstOrNull { candidate ->
                val caps = connectivityManager.getNetworkCapabilities(candidate)
                caps != null &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            }
        }.getOrNull()
    }

    private fun buildSnapshotForNetwork(network: Network): NetworkSnapshot {
        val caps = runCatching { connectivityManager.getNetworkCapabilities(network) }.getOrNull()
        val linkProps = runCatching { connectivityManager.getLinkProperties(network) }.getOrNull()
        return buildSnapshot(network, caps, linkProps)
    }

    private fun buildSnapshot(
        network: Network?,
        caps: NetworkCapabilities?,
        linkProps: LinkProperties?
    ): NetworkSnapshot {
        if (network == null || caps == null) return NetworkSnapshot.NONE
        val transportKind = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> TransportKind.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> TransportKind.CELLULAR
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> TransportKind.ETHERNET
            else -> TransportKind.OTHER
        }
        val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val isValidated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val isNotVpn = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        val isMetered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)

        val dnsServers = linkProps?.dnsServers
            ?.mapNotNull { it.hostAddress }
            .orEmpty()
        val ifaceName = linkProps?.interfaceName
        val linkAddresses = linkProps?.linkAddresses.orEmpty()
        val hasV4 = linkAddresses.isEmpty() || linkAddresses.any { it.address is Inet4Address }
        val hasV6 = linkAddresses.any { it.address is Inet6Address }

        return NetworkSnapshot(
            networkHandleId = runCatching { network.networkHandle }.getOrNull(),
            transportKind = transportKind,
            hasInternetCapability = hasInternet,
            isValidated = isValidated,
            isNotVpn = isNotVpn,
            isMetered = isMetered,
            dnsServers = dnsServers,
            interfaceName = ifaceName,
            hasIpv4 = hasV4,
            hasIpv6 = hasV6,
            underlyingNetwork = network
        )
    }

    companion object {
        const val DEBOUNCE_WINDOW_MS = 800L

        /**
         * Determines whether two [NetworkSnapshot]s represent the same underlying connectivity
         * state (ignoring timestamps or minor signal strength fluctuations).
         */
        fun isMeaningfullyEquivalent(a: NetworkSnapshot, b: NetworkSnapshot): Boolean {
            return a.networkHandleId == b.networkHandleId &&
                a.transportKind == b.transportKind &&
                a.hasInternetCapability == b.hasInternetCapability &&
                a.isValidated == b.isValidated &&
                a.isMetered == b.isMetered &&
                a.interfaceName == b.interfaceName &&
                a.dnsServers == b.dnsServers &&
                a.hasIpv4 == b.hasIpv4 &&
                a.hasIpv6 == b.hasIpv6
        }
    }
}
