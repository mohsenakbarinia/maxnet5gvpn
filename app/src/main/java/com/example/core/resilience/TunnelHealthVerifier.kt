package com.example.core.resilience

/**
 * Verifies that an established VPN tunnel actually carries international traffic end-to-end
 * over [Plane.OVERLAY] before the UI reports «اتصال امن برقرار شد» (Section 8.2 / Rule H-2).
 *
 * A tunnel is verified ONLY when:
 * 1. At least 2 overlay probes (`L-HTTP`) across >= 2 independent provider groups succeed
 *    within timeout, OR at least 1 overlay probe succeeds and bidirectional tunnel byte counters
 *    (`rxBytesDelta > 0 && txBytesDelta > 0`) confirm active payload flow across >= 2 probe targets.
 */
class TunnelHealthVerifier(
    private val endpointRegistry: ProbeEndpointRegistry,
    private val probeEngine: ProbeEngine,
    private val clockMsProvider: () -> Long = { System.currentTimeMillis() }
) {
    suspend fun verifyTunnel(
        overlaySocksPort: Int,
        config: ConnectivityConfig = ConnectivityConfig(),
        rxBytesDelta: Long = 0L,
        txBytesDelta: Long = 0L
    ): TunnelVerificationReport {
        val checkEndpoints = endpointRegistry.getTunnelCheckEndpoints().take(3)
        if (checkEndpoints.isEmpty()) {
            return TunnelVerificationReport(
                verified = false,
                probesAttempted = 0,
                probesSucceeded = 0,
                providerGroupsSucceeded = 0,
                dominantFailure = FailureCategory.CONFIGURATION_ERROR,
                timestampMs = clockMsProvider()
            )
        }

        val results = probeEngine.runProbeCycle(
            endpoints = checkEndpoints,
            plane = Plane.OVERLAY,
            maxLayer = ProbeLayer.HTTP,
            config = config,
            overlaySocksPort = overlaySocksPort,
            forceRefresh = true
        )

        val successes = results.filter { it.success }
        val failures = results.filter { !it.success }
        val providerGroupsSucceeded = successes.map { it.providerGroup }.distinct().size
        val latencies = successes.map { it.totalLatencyMs }.sorted()
        val medianLatency = if (latencies.isNotEmpty()) latencies[latencies.size / 2] else null

        val verified = (successes.size >= 2 && providerGroupsSucceeded >= 2) ||
            (successes.size >= 2 && rxBytesDelta > 0L && txBytesDelta > 0L)

        val dominantFailure = if (verified) {
            null
        } else {
            failures.mapNotNull { it.failureCategory }
                .groupingBy { it }
                .eachCount()
                .maxByOrNull { it.value }
                ?.key ?: FailureCategory.TUNNEL_NO_TRAFFIC
        }

        return TunnelVerificationReport(
            verified = verified,
            probesAttempted = results.size,
            probesSucceeded = successes.size,
            providerGroupsSucceeded = providerGroupsSucceeded,
            medianOverlayLatencyMs = medianLatency,
            rxBytesDelta = rxBytesDelta,
            txBytesDelta = txBytesDelta,
            dominantFailure = dominantFailure,
            timestampMs = clockMsProvider()
        )
    }
}
