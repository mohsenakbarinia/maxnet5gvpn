package com.v2ray.ang.service

import android.content.Context
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Native JNI bridge for `libhev-socks5-tunnel.so` (HevSocks5Tunnel C/lwIP TUN-to-SOCKS5 engine).
 *
 * IMPORTANT: The package (`com.v2ray.ang.service`) and class name (`TProxyService`) MUST NOT
 * be renamed because `JNI_OnLoad` inside `libhev-socks5-tunnel.so` explicitly calls
 * `FindClass("com/v2ray/ang/service/TProxyService")` to register the four native methods below.
 */
class TProxyService {

    data class TunnelStats(
        val txPackets: Long,
        val txBytes: Long,
        val rxPackets: Long,
        val rxBytes: Long
    )

    companion object {
        private val nativeLoaded = AtomicBoolean(false)
        private val runningFallback = AtomicBoolean(false)

        init {
            try {
                System.loadLibrary("hev-socks5-tunnel")
                nativeLoaded.set(true)
            } catch (_: Throwable) {
                nativeLoaded.set(false)
            }
        }

        @JvmStatic
        @Suppress("FunctionName")
        private external fun TProxyStartService(configPath: String, fd: Int): Boolean

        @JvmStatic
        @Suppress("FunctionName")
        private external fun TProxyStopService(): Boolean

        @JvmStatic
        @Suppress("FunctionName")
        private external fun TProxyIsRunning(): Boolean

        @JvmStatic
        @Suppress("FunctionName")
        private external fun TProxyGetStats(): LongArray?

        fun isNativeLibraryLoaded(): Boolean = nativeLoaded.get()

        fun isTun2SocksRunning(): Boolean = isRunning()

        fun buildHevTunnelYaml(
            socksPort: Int,
            mtu: Int,
            enableIpv6: Boolean,
            ipv4Client: String = "10.26.26.1",
            ipv6Client: String = "fd00:1::1"
        ): String {
            val safeMtu = mtu.coerceIn(1280, 1500)
            return buildString {
                appendLine("tunnel:")
                appendLine("  mtu: $safeMtu")
                appendLine("  ipv4: $ipv4Client")
                if (enableIpv6) {
                    appendLine("  ipv6: '$ipv6Client'")
                }
                appendLine("socks5:")
                appendLine("  port: $socksPort")
                appendLine("  address: 127.0.0.1")
                appendLine("  udp: 'udp'")
                appendLine("misc:")
                appendLine("  tcp-read-write-timeout: 300000")
                appendLine("  udp-read-write-timeout: 60000")
                appendLine("  log-level: warn")
            }
        }

        @Synchronized
        fun startTun2Socks(
            context: Context,
            tunFd: Int,
            socksPort: Int,
            mtu: Int,
            enableIpv6: Boolean
        ): Result<File> {
            return try {
                val yaml = buildHevTunnelYaml(
                    socksPort = socksPort,
                    mtu = mtu,
                    enableIpv6 = enableIpv6
                )
                val configFile = File(context.filesDir, "hev-socks5-tunnel.yaml")
                configFile.writeText(yaml, Charsets.UTF_8)

                if (!nativeLoaded.get()) {
                    return Result.failure(
                        IllegalStateException("Native library libhev-socks5-tunnel.so is not loaded in this runtime")
                    )
                }

                if (isRunning()) {
                    runCatching { TProxyStopService() }
                }

                val started = TProxyStartService(configFile.absolutePath, tunFd)
                if (!started && !TProxyIsRunning()) {
                    Result.failure(IllegalStateException("TProxyStartService failed to attach to TUN fd=$tunFd"))
                } else {
                    runningFallback.set(true)
                    Result.success(configFile)
                }
            } catch (t: Throwable) {
                Result.failure(t)
            }
        }

        @Synchronized
        fun stopTun2Socks() {
            if (!nativeLoaded.get()) {
                runningFallback.set(false)
                return
            }
            try {
                if (TProxyIsRunning() || runningFallback.get()) {
                    TProxyStopService()
                }
            } catch (_: Throwable) {
            } finally {
                runningFallback.set(false)
            }
        }

        fun isRunning(): Boolean {
            if (!nativeLoaded.get()) return false
            return try {
                TProxyIsRunning()
            } catch (_: Throwable) {
                false
            }
        }

        fun queryTrafficStats(): TunnelStats? {
            if (!nativeLoaded.get()) return null
            return try {
                val raw = TProxyGetStats() ?: return null
                if (raw.size >= 4) {
                    TunnelStats(
                        txPackets = raw[0].coerceAtLeast(0L),
                        txBytes = raw[1].coerceAtLeast(0L),
                        rxPackets = raw[2].coerceAtLeast(0L),
                        rxBytes = raw[3].coerceAtLeast(0L)
                    )
                } else {
                    null
                }
            } catch (_: Throwable) {
                null
            }
        }
    }
}
