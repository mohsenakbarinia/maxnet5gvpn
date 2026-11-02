package com.example.core.resilience

import android.net.Network

/**
 * Assesses whether explicitly configured, legitimate domestic endpoints remain reachable
 * when international connectivity is restricted.
 *
 * Strict compliance (Section 6):
 * - Never tests third-party apps (Snapp, banks, government websites) unless explicitly configured
 *   by the operator/user.
 * - Default domestic endpoint list is empty, yielding [DomesticReachabilityState.NOT_TESTED].
 * - Never assumes a reachable domestic HTTP endpoint can act as a general-purpose VPN relay.
 */
class DomesticReachabilityDiscovery(
    private val endpointRegistry: ProbeEndpointRegistry,
    private val probeEngine: ProbeEngine,
    private val clockMsProvider: () -> Long = { System.currentTimeMillis() }
) {
    suspend fun assessDomesticReachability(
        config: ConnectivityConfig,
        isMetered: Boolean = false,
        underlayNetwork: Network? = null,
        forceRefresh: Boolean = false
    ): DomesticReachability {
        if (!config.domesticDiscoveryEnabled) {
            return DomesticReachability(
                state = DomesticReachabilityState.NOT_TESTED,
                timestampMs = clockMsProvider()
            )
        }
        val domesticEndpoints = endpointRegistry.getDomesticEndpoints()
        if (domesticEndpoints.isEmpty()) {
            return DomesticReachability(
                state = DomesticReachabilityState.NOT_TESTED,
                timestampMs = clockMsProvider()
            )
        }

        val results = probeEngine.runProbeCycle(
            endpoints = domesticEndpoints,
            plane = Plane.UNDERLAY,
            maxLayer = ProbeLayer.HTTP,
            config = config,
            isMeteredNetwork = isMetered,
            underlayNetwork = underlayNetwork,
            forceRefresh = forceRefresh
        )

        if (results.isEmpty()) {
            return DomesticReachability(
                state = DomesticReachabilityState.NOT_TESTED,
                timestampMs = clockMsProvider()
            )
        }

        val successes = results.filter { it.success }
        val latencies = successes.map { it.totalLatencyMs }.sorted()
        val medianLatency = if (latencies.isNotEmpty()) {
            latencies[latencies.size / 2]
        } else {
            null
        }

        val state = if (successes.isNotEmpty()) {
            DomesticReachabilityState.YES
        } else {
            DomesticReachabilityState.NO
        }

        return DomesticReachability(
            state = state,
            testedCount = results.size,
            successCount = successes.size,
            medianLatencyMs = medianLatency,
            timestampMs = clockMsProvider()
        )
    }
}
