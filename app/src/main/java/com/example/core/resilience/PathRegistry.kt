package com.example.core.resilience

import com.example.core.autovpn.AutoVpnCacheStore
import com.example.core.autovpn.VpnAppMode
import com.example.domain.model.SecurityType
import com.example.domain.model.ServerProfile
import com.example.domain.model.TransportType

/**
 * Enumerates and manages legitimate [ConnectivityPath] candidates from:
 * 1. User-configured profiles (`TRUSTED_USER`)
 * 2. Explicit `AuthorizedRelay` paths (`Mode A` full profile or `Mode A2` runtime address/port
 *    override to a user/operator relay while preserving gateway TLS SNI / WS Host)
 * 3. Optional safe runtime variants (only when `config.allowRuntimeVariants == true`, never
 *    persisting mutations back to Room)
 * 4. Free VPN auto-discovery candidates (`UNTRUSTED` trust level, isolated unless in `FREE_VPN` mode)
 */
class PathRegistry {

    fun buildCandidatePaths(
        primaryProfile: ServerProfile?,
        allProfiles: List<ServerProfile>,
        authorizedRelays: List<AuthorizedRelay>,
        config: ConnectivityConfig,
        vpnMode: VpnAppMode = VpnAppMode.MY_VPN,
        networkHandleId: Long? = null
    ): List<ConnectivityPath> {
        val paths = mutableListOf<ConnectivityPath>()

        val eligibleProfiles = allProfiles.filter { profile ->
            if (vpnMode == VpnAppMode.FREE_VPN) {
                true
            } else {
                // Rule S-5: Never promote UNTRUSTED free discovery profiles into standard user paths
                !isAutoDiscoveredUntrustedProfile(profile)
            }
        }

        val orderedProfiles = buildList {
            if (primaryProfile != null) add(primaryProfile)
            for (p in eligibleProfiles) {
                if (primaryProfile == null || p.uniqueFingerprintKey() != primaryProfile.uniqueFingerprintKey()) {
                    add(p)
                }
            }
        }

        // 1. Direct paths for ordered profiles
        for (profile in orderedProfiles) {
            val trustLevel = if (isAutoDiscoveredUntrustedProfile(profile)) {
                PathTrustLevel.UNTRUSTED
            } else {
                PathTrustLevel.TRUSTED_USER
            }
            paths.add(
                ConnectivityPath(
                    pathId = "direct_${profile.id}_${profile.address}_${profile.port}",
                    profile = profile,
                    relay = null,
                    isRuntimeVariant = false,
                    trustLevel = trustLevel,
                    networkHandleId = networkHandleId
                )
            )
        }

        // 2. Opt-in runtime variants (Section 9.3 — OFF by default; never mutates stored profile)
        if (config.allowRuntimeVariants) {
            for (profile in orderedProfiles) {
                if (isAutoDiscoveredUntrustedProfile(profile)) continue
                val altFingerprints = listOf("chrome", "firefox", "safari")
                    .filter { !it.equals(profile.fingerprint, ignoreCase = true) }
                val altFp = altFingerprints.firstOrNull()
                if (altFp != null && (profile.security == SecurityType.TLS || profile.security == SecurityType.REALITY)) {
                    val variantProfile = profile.copy(fingerprint = altFp)
                    paths.add(
                        ConnectivityPath(
                            pathId = "variant_fp_${profile.id}_$altFp",
                            profile = variantProfile,
                            relay = null,
                            isRuntimeVariant = true,
                            variantLabel = "uTLS:$altFp",
                            trustLevel = PathTrustLevel.TRUSTED_USER,
                            networkHandleId = networkHandleId
                        )
                    )
                }
            }
        }

        // 3. Authorized relay paths (Section 12 — Mode A2 endpoint override)
        if (config.relayFallbackEnabled && authorizedRelays.isNotEmpty()) {
            val activeRelays = authorizedRelays.filter { it.enabled && isValidRelay(it) }
            for (relay in activeRelays) {
                for (profile in orderedProfiles) {
                    if (isAutoDiscoveredUntrustedProfile(profile)) continue
                    if (!isProfileCompatibleWithRelay(profile, relay)) continue
                    val relayedProfile = applyRelayOverride(profile, relay)
                    paths.add(
                        ConnectivityPath(
                            pathId = "relay_${relay.id}_${profile.id}",
                            profile = relayedProfile,
                            relay = relay,
                            isRuntimeVariant = false,
                            variantLabel = "Relay:${relay.name}",
                            trustLevel = PathTrustLevel.TRUSTED_USER,
                            networkHandleId = networkHandleId
                        )
                    )
                }
            }
        }

        return paths.distinctBy { it.pathId }
    }

    /**
     * Produces an in-memory [ServerProfile] with the relay address/port override applied (Mode A2)
     * while preserving the destination gateway's `sni`, `host` header, and user credentials.
     * Never persisted to the database.
     */
    fun materializeProfileForPath(path: ConnectivityPath): ServerProfile {
        val relay = path.relay ?: return path.profile
        return applyRelayOverride(path.profile, relay)
    }

    companion object {
        fun buildDirectPath(
            profile: ServerProfile,
            trustLevel: PathTrustLevel = if (isAutoDiscoveredUntrustedProfile(profile)) {
                PathTrustLevel.UNTRUSTED
            } else {
                PathTrustLevel.TRUSTED_USER
            },
            networkHandleId: Long? = null
        ): ConnectivityPath {
            return ConnectivityPath(
                pathId = "direct_${profile.id}_${profile.address}_${profile.port}",
                profile = profile,
                relay = null,
                isRuntimeVariant = false,
                trustLevel = trustLevel,
                networkHandleId = networkHandleId
            )
        }

        fun applyPathOverrides(
            base: ServerProfile,
            path: ConnectivityPath,
            relays: List<AuthorizedRelay> = emptyList()
        ): ServerProfile {
            val relay = path.relay ?: relays.firstOrNull { it.id == path.viaRelayId }
            return if (relay != null) {
                applyRelayOverride(base, relay)
            } else {
                path.profile
            }
        }

        fun applyRelayOverride(base: ServerProfile, relay: AuthorizedRelay): ServerProfile {
            val preservedSni = relay.sniOverride?.takeIf { it.isNotBlank() }
                ?: base.sni.ifBlank { base.address }
            val preservedHost = base.host.ifBlank { base.address }
            return base.copy(
                address = relay.host,
                port = relay.port,
                sni = preservedSni,
                host = preservedHost
            )
        }

        fun isAutoDiscoveredUntrustedProfile(profile: ServerProfile): Boolean {
            val group = profile.groupName.lowercase()
            return profile.id < 0L ||
                profile.groupName == AutoVpnCacheStore.AUTO_CACHE_GROUP ||
                group.contains("autovpn") ||
                group.contains("free_discovery")
        }

        fun isValidRelay(relay: AuthorizedRelay): Boolean {
            if (relay.host.isBlank() || relay.port !in 1..65535) return false
            val clean = relay.host.trim().lowercase()
            if (clean == "localhost" || clean == "127.0.0.1" || clean == "0.0.0.0") return false
            return true
        }

        fun isProfileCompatibleWithRelay(profile: ServerProfile, relay: AuthorizedRelay): Boolean {
            if (relay.associatedProfileIds.isNotEmpty() && profile.id !in relay.associatedProfileIds) {
                return false
            }
            if (profile.network !in relay.supportedTransports) {
                return false
            }
            if (relay.requireTlsOrReality) {
                if (profile.security != SecurityType.TLS && profile.security != SecurityType.REALITY) {
                    return false
                }
            }
            return true
        }

        /**
         * Transport compatibility ranking under national internet / severe DPI restrictions
         * (Section 9.1): REALITY / XHTTP / HTTPUPGRADE / WS(TLS:443) / GRPC(TLS:443) outrank raw TCP on non-443.
         */
        fun transportResilienceBonus(
            path: ConnectivityPath,
            nationalState: NationalInternetState
        ): Double {
            val profile = path.profile
            val isRestricted = nationalState == NationalInternetState.RESTRICTED_INTERNATIONAL ||
                nationalState == NationalInternetState.DOMESTIC_ONLY_SUSPECTED ||
                nationalState == NationalInternetState.PARTIAL_INTERNATIONAL

            var bonus = 0.0
            if (path.isViaRelay && nationalState == NationalInternetState.DOMESTIC_ONLY_SUSPECTED) {
                bonus += 28.0
            } else if (path.isViaRelay && isRestricted) {
                bonus += 15.0
            }

            if (profile.security == SecurityType.REALITY) {
                bonus += if (isRestricted) 20.0 else 10.0
            } else if (profile.security == SecurityType.TLS) {
                bonus += 8.0
            }

            bonus += when (profile.network) {
                TransportType.XHTTP, TransportType.HTTPUPGRADE -> if (isRestricted) 16.0 else 8.0
                TransportType.WS, TransportType.GRPC, TransportType.H2 -> if (isRestricted) 14.0 else 7.0
                TransportType.MKCP, TransportType.QUIC -> if (isRestricted) -8.0 else 2.0
                TransportType.TCP -> if (path.effectivePort == 443) 5.0 else -5.0
            }

            if (path.effectivePort == 443) {
                bonus += 5.0
            }
            return bonus
        }
    }
}
