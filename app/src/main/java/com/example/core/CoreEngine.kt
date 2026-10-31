package com.example.core

import android.content.Context
import com.example.domain.model.AppSettings
import com.example.domain.model.EngineStatistics
import com.example.domain.model.EngineType
import com.example.domain.model.RoutingRuleItem
import com.example.domain.model.ServerProfile
import com.example.domain.model.ValidationResult
import com.example.domain.model.VpnConnectionState
import kotlinx.coroutines.flow.StateFlow

/**
 * Common Engine Abstraction for Dual-Engine VPN Architecture (Xray-core & sing-box).
 * The UI and VpnService interact strictly with this interface and never depend on
 * engine-specific internal structures.
 */
interface CoreEngine {
    val engineType: EngineType

    suspend fun initialize(context: Context? = null): Result<Unit>

    fun validateConfig(
        profile: ServerProfile,
        settings: AppSettings,
        customRules: List<RoutingRuleItem> = emptyList(),
        localSocksPort: Int = 10808
    ): ValidationResult

    suspend fun start(
        profile: ServerProfile,
        settings: AppSettings,
        customRules: List<RoutingRuleItem> = emptyList(),
        protectSocket: (Int) -> Boolean = { true },
        onLog: (String, String, String) -> Unit = { _, _, _ -> }
    ): Result<Int>

    suspend fun verifyActiveConnection(
        testUrl: String? = null,
        timeoutMs: Int = 5000
    ): Result<Long>

    fun markConnected()

    suspend fun stop()

    fun getStatus(): StateFlow<VpnConnectionState>

    fun getStatistics(): StateFlow<EngineStatistics>

    suspend fun updateConfiguration(
        profile: ServerProfile,
        settings: AppSettings,
        customRules: List<RoutingRuleItem> = emptyList()
    ): Result<Unit>

    suspend fun healthCheck(): Result<Long>

    fun isNativeBinaryLoaded(): Boolean

    fun isEngineRunning(): Boolean

    fun getEngineVersion(): String
}
