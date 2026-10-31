package com.example.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.domain.model.ConnectionHistoryItem
import com.example.domain.model.LatencyMeasureType
import com.example.domain.model.OutboundTag
import com.example.domain.model.RoutingRuleItem
import com.example.domain.model.RoutingRuleType
import com.example.domain.model.SecurityType
import com.example.domain.model.ServerProfile
import com.example.domain.model.SubscriptionItem
import com.example.domain.model.TransportType
import com.example.domain.model.VpnProtocol
import com.example.security.CredentialCipher

@Entity(
    tableName = "profiles",
    indices = [
        Index(value = ["groupName"]),
        Index(value = ["protocol"]),
        Index(value = ["subscriptionId"]),
        Index(value = ["isPinned", "sortOrder"])
    ]
)
data class ProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val remarks: String,
    val groupName: String = "Default",
    val subscriptionId: Long? = null,
    val protocol: String = VpnProtocol.VLESS.name,
    val address: String,
    val port: Int,
    val encryptedCredential: String,
    val encryption: String = "none",
    val alterId: Int = 0,
    val flow: String = "",
    val network: String = TransportType.TCP.name,
    val headerType: String = "none",
    val host: String = "",
    val path: String = "",
    val serviceName: String = "",
    val security: String = SecurityType.NONE.name,
    val sni: String = "",
    val alpn: String = "",
    val fingerprint: String = "chrome",
    val encryptedRealityPublicKey: String = "",
    val realityShortId: String = "",
    val realitySpiderX: String = "",
    val encryptedWireGuardPublicKey: String = "",
    val wireGuardReserved: String = "",
    val wireGuardLocalAddress: String = "172.16.0.2/32",
    val allowInsecure: Boolean = false,
    val isFavorite: Boolean = false,
    val isPinned: Boolean = false,
    val sortOrder: Int = 0,
    val lastLatencyMs: Long? = null,
    val latencyType: String = LatencyMeasureType.UNTESTED.name,
    val createdAt: Long = System.currentTimeMillis()
) {
    fun toDomain(): ServerProfile {
        return ServerProfile(
            id = id,
            remarks = remarks,
            groupName = groupName,
            subscriptionId = subscriptionId,
            protocol = runCatching { VpnProtocol.valueOf(protocol) }.getOrDefault(VpnProtocol.VLESS),
            address = address,
            port = port,
            credential = CredentialCipher.decrypt(encryptedCredential),
            encryption = encryption,
            alterId = alterId,
            flow = flow,
            network = runCatching { TransportType.valueOf(network) }.getOrDefault(TransportType.TCP),
            headerType = headerType,
            host = host,
            path = path,
            serviceName = serviceName,
            security = runCatching { SecurityType.valueOf(security) }.getOrDefault(SecurityType.NONE),
            sni = sni,
            alpn = alpn,
            fingerprint = fingerprint,
            realityPublicKey = CredentialCipher.decrypt(encryptedRealityPublicKey),
            realityShortId = realityShortId,
            realitySpiderX = realitySpiderX,
            wireGuardPublicKey = CredentialCipher.decrypt(encryptedWireGuardPublicKey),
            wireGuardReserved = wireGuardReserved,
            wireGuardLocalAddress = wireGuardLocalAddress,
            allowInsecure = allowInsecure,
            isFavorite = isFavorite,
            isPinned = isPinned,
            sortOrder = sortOrder,
            lastLatencyMs = lastLatencyMs,
            latencyType = runCatching { LatencyMeasureType.valueOf(latencyType) }.getOrDefault(LatencyMeasureType.UNTESTED),
            createdAt = createdAt
        )
    }

    companion object {
        fun fromDomain(profile: ServerProfile): ProfileEntity {
            return ProfileEntity(
                id = profile.id,
                remarks = profile.remarks,
                groupName = profile.groupName,
                subscriptionId = profile.subscriptionId,
                protocol = profile.protocol.name,
                address = profile.address,
                port = profile.port,
                encryptedCredential = CredentialCipher.encrypt(profile.credential),
                encryption = profile.encryption,
                alterId = profile.alterId,
                flow = profile.flow,
                network = profile.network.name,
                headerType = profile.headerType,
                host = profile.host,
                path = profile.path,
                serviceName = profile.serviceName,
                security = profile.security.name,
                sni = profile.sni,
                alpn = profile.alpn,
                fingerprint = profile.fingerprint,
                encryptedRealityPublicKey = CredentialCipher.encrypt(profile.realityPublicKey),
                realityShortId = profile.realityShortId,
                realitySpiderX = profile.realitySpiderX,
                encryptedWireGuardPublicKey = CredentialCipher.encrypt(profile.wireGuardPublicKey),
                wireGuardReserved = profile.wireGuardReserved,
                wireGuardLocalAddress = profile.wireGuardLocalAddress,
                allowInsecure = profile.allowInsecure,
                isFavorite = profile.isFavorite,
                isPinned = profile.isPinned,
                sortOrder = profile.sortOrder,
                lastLatencyMs = profile.lastLatencyMs,
                latencyType = profile.latencyType.name,
                createdAt = profile.createdAt
            )
        }
    }
}

@Entity(tableName = "subscriptions")
data class SubscriptionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val title: String,
    val encryptedUrl: String,
    val enabledAutoUpdate: Boolean = true,
    val updateIntervalHours: Int = 24,
    val lastUpdatedAt: Long? = null,
    val lastStatus: String = "Never updated",
    val uploadBytes: Long? = null,
    val downloadBytes: Long? = null,
    val totalBytes: Long? = null,
    val expireTimestampSec: Long? = null,
    val supportUrl: String = "",
    val webPageUrl: String = "",
    val announcement: String = "",
    val profileCount: Int = 0
) {
    fun toDomain(): SubscriptionItem {
        return SubscriptionItem(
            id = id,
            title = title,
            url = CredentialCipher.decrypt(encryptedUrl),
            enabledAutoUpdate = enabledAutoUpdate,
            updateIntervalHours = updateIntervalHours,
            lastUpdatedAt = lastUpdatedAt,
            lastStatus = lastStatus,
            uploadBytes = uploadBytes,
            downloadBytes = downloadBytes,
            totalBytes = totalBytes,
            expireTimestampSec = expireTimestampSec,
            supportUrl = supportUrl,
            webPageUrl = webPageUrl,
            announcement = announcement,
            profileCount = profileCount
        )
    }

    companion object {
        fun fromDomain(item: SubscriptionItem): SubscriptionEntity {
            return SubscriptionEntity(
                id = item.id,
                title = item.title,
                encryptedUrl = CredentialCipher.encrypt(item.url),
                enabledAutoUpdate = item.enabledAutoUpdate,
                updateIntervalHours = item.updateIntervalHours,
                lastUpdatedAt = item.lastUpdatedAt,
                lastStatus = item.lastStatus,
                uploadBytes = item.uploadBytes,
                downloadBytes = item.downloadBytes,
                totalBytes = item.totalBytes,
                expireTimestampSec = item.expireTimestampSec,
                supportUrl = item.supportUrl,
                webPageUrl = item.webPageUrl,
                announcement = item.announcement,
                profileCount = item.profileCount
            )
        }
    }
}

@Entity(tableName = "profile_groups")
data class ProfileGroupEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "application_settings")
data class ApplicationSettingsEntity(
    @PrimaryKey val id: Int = 1,
    val selectedProfileId: Long? = null,
    val activeEngine: String = "XRAY_CORE",
    val routingMode: String = "RULE_BASED",
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "routing_rules")
data class RoutingRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val type: String,
    val value: String,
    val outboundTag: String,
    val isEnabled: Boolean = true
) {
    fun toDomain(): RoutingRuleItem {
        return RoutingRuleItem(
            id = id,
            name = name,
            type = runCatching { RoutingRuleType.valueOf(type) }.getOrDefault(RoutingRuleType.DOMAIN),
            value = value,
            outboundTag = runCatching { OutboundTag.valueOf(outboundTag) }.getOrDefault(OutboundTag.DIRECT),
            isEnabled = isEnabled
        )
    }

    companion object {
        fun fromDomain(item: RoutingRuleItem): RoutingRuleEntity {
            return RoutingRuleEntity(
                id = item.id,
                name = item.name,
                type = item.type.name,
                value = item.value,
                outboundTag = item.outboundTag.name,
                isEnabled = item.isEnabled
            )
        }
    }
}

@Entity(tableName = "connection_history")
data class ConnectionHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val profileName: String,
    val protocol: String,
    val engine: String,
    val connectedAt: Long,
    val disconnectedAt: Long?,
    val uploadBytes: Long,
    val downloadBytes: Long,
    val statusNote: String
) {
    fun toDomain(): ConnectionHistoryItem {
        return ConnectionHistoryItem(
            id = id,
            profileName = profileName,
            protocol = protocol,
            engine = engine,
            connectedAt = connectedAt,
            disconnectedAt = disconnectedAt,
            uploadBytes = uploadBytes,
            downloadBytes = downloadBytes,
            statusNote = statusNote
        )
    }
}
