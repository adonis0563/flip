package com.example.service

import android.content.Context
import android.net.*
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import com.example.service.control.ControlChannelConstants
import kotlinx.coroutines.*
import java.net.Inet4Address
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Result of attempting to join a sender's hotspot.
 */
sealed class JoinResult {
    data class Joined(
        val network: Network,
        val gatewayAddress: String
    ) : JoinResult()

    data class Failed(
        val reason: FailReason
    ) : JoinResult()

    enum class FailReason {
        Unavailable,
        Timeout,
        NoIp,
        Unsupported,
        Cancelled
    }
}

/**
 * Receiver helper used by connection services to join a peer's LocalOnlyHotspot.
 *
 * Rules:
 * - Uses WifiNetworkSpecifier (Android 10+). Returns Failed(Unsupported) on Android 9 or lower.
 * - On onAvailable: immediately calls connectivityManager.bindProcessToNetwork(network)
 *   so sockets route over the no-internet hotspot rather than cellular data.
 * - Waits up to 5s for LinkProperties to contain an IPv4 address and a gateway (default route or DHCP server).
 * - Retains a strong reference to NetworkCallback; Wi-Fi stays up while request is held.
 * - leaveHotspot() unbinds process and unregisters callback safely.
 * - Never stores or logs passwords or gateway IP addresses. Logs only step names and elapsed ms with "FlipLink".
 */
class HotspotJoinHelper(
    private val context: Context,
    var onNetworkLost: (() -> Unit)? = null
) {
    private val connectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    // Strong reference to the active NetworkCallback to keep the Wi-Fi connection alive
    private val activeCallbackRef = AtomicReference<ConnectivityManager.NetworkCallback?>(null)
    private val boundNetworkRef = AtomicReference<Network?>(null)
    private val isJoined = AtomicBoolean(false)

    /**
     * Attempts to join the specified Wi-Fi hotspot within the given timeout.
     */
    suspend fun joinHotspot(
        credentials: WifiCredentials,
        timeoutMs: Int = 30000
    ): JoinResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        ControlChannelConstants.logStep(
            step = "join_requested",
            elapsedMs = 0
        )

        // Android below 10 does not support WifiNetworkSpecifier
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            ControlChannelConstants.logStep(
                step = "join_unsupported",
                elapsedMs = System.currentTimeMillis() - startTime
            )
            return@withContext JoinResult.Failed(JoinResult.FailReason.Unsupported)
        }

        // Clean up any previously held network request before starting a new one
        leaveHotspot()

        val resultDeferred = CompletableDeferred<JoinResult>()
        var gatewayTimeoutJob: Job? = null

        val specifierBuilder = WifiNetworkSpecifier.Builder()
            .setSsid(credentials.ssid)

        val sec = credentials.security.trim().uppercase()
        when {
            sec == "WPA3" || sec == "SAE" || sec == "WPA3-SAE" -> {
                specifierBuilder.setWpa3Passphrase(credentials.password)
            }
            sec == "OPEN" || sec == "NOPASS" || sec == "NONE" || credentials.password.isEmpty() -> {
                // Open network: no passphrase set
            }
            else -> {
                // WPA2 / default
                specifierBuilder.setWpa2Passphrase(credentials.password)
            }
        }

        if (credentials.isHidden) {
            specifierBuilder.setIsHiddenSsid(true)
        }

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifierBuilder.build())
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                ControlChannelConstants.logStep(
                    step = "on_available",
                    elapsedMs = System.currentTimeMillis() - startTime
                )

                boundNetworkRef.set(network)

                try {
                    connectivityManager.bindProcessToNetwork(network)
                    ControlChannelConstants.logStep(
                        step = "process_bound",
                        elapsedMs = System.currentTimeMillis() - startTime
                    )
                } catch (e: Exception) {
                    ControlChannelConstants.logStep(
                        step = "process_bind_failed",
                        elapsedMs = System.currentTimeMillis() - startTime,
                        detail = e.javaClass.simpleName
                    )
                }

                // Check if LinkProperties are already available
                val lp = connectivityManager.getLinkProperties(network)
                if (lp != null) {
                    val gw = extractIpv4Gateway(lp)
                    if (gw != null) {
                        isJoined.set(true)
                        ControlChannelConstants.logStep(
                            step = "join_success",
                            elapsedMs = System.currentTimeMillis() - startTime
                        )
                        resultDeferred.complete(JoinResult.Joined(network, gw))
                        return
                    }
                }

                // Wait up to 5s for onLinkPropertiesChanged to provide IPv4 address & gateway
                gatewayTimeoutJob = CoroutineScope(Dispatchers.IO).launch {
                    delay(5000L)
                    if (!resultDeferred.isCompleted) {
                        ControlChannelConstants.logStep(
                            step = "no_ip_or_gateway",
                            elapsedMs = System.currentTimeMillis() - startTime
                        )
                        leaveHotspot()
                        resultDeferred.complete(JoinResult.Failed(JoinResult.FailReason.NoIp))
                    }
                }
            }

            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
                super.onLinkPropertiesChanged(network, lp)
                if (boundNetworkRef.get() == network && !resultDeferred.isCompleted) {
                    val gw = extractIpv4Gateway(lp)
                    if (gw != null) {
                        gatewayTimeoutJob?.cancel()
                        isJoined.set(true)
                        ControlChannelConstants.logStep(
                            step = "join_success",
                            elapsedMs = System.currentTimeMillis() - startTime
                        )
                        resultDeferred.complete(JoinResult.Joined(network, gw))
                    }
                }
            }

            override fun onUnavailable() {
                super.onUnavailable()
                gatewayTimeoutJob?.cancel()
                ControlChannelConstants.logStep(
                    step = "on_unavailable",
                    elapsedMs = System.currentTimeMillis() - startTime
                )
                leaveHotspot()
                resultDeferred.complete(JoinResult.Failed(JoinResult.FailReason.Unavailable))
            }

            override fun onLost(network: Network) {
                super.onLost(network)
                ControlChannelConstants.logStep(
                    step = "on_lost",
                    elapsedMs = System.currentTimeMillis() - startTime
                )
                if (isJoined.get()) {
                    onNetworkLost?.invoke()
                }
            }
        }

        activeCallbackRef.set(callback)

        try {
            connectivityManager.requestNetwork(request, callback, timeoutMs)
            ControlChannelConstants.logStep(
                step = "request_network_called",
                elapsedMs = System.currentTimeMillis() - startTime
            )
        } catch (e: Exception) {
            leaveHotspot()
            return@withContext JoinResult.Failed(JoinResult.FailReason.Unavailable)
        }

        val finalResult = try {
            withTimeout(timeoutMs.toLong()) {
                resultDeferred.await()
            }
        } catch (e: TimeoutCancellationException) {
            gatewayTimeoutJob?.cancel()
            ControlChannelConstants.logStep(
                step = "join_timeout",
                elapsedMs = System.currentTimeMillis() - startTime
            )
            leaveHotspot()
            JoinResult.Failed(JoinResult.FailReason.Timeout)
        } catch (e: CancellationException) {
            gatewayTimeoutJob?.cancel()
            leaveHotspot()
            JoinResult.Failed(JoinResult.FailReason.Cancelled)
        } catch (e: Exception) {
            gatewayTimeoutJob?.cancel()
            leaveHotspot()
            JoinResult.Failed(JoinResult.FailReason.Unavailable)
        }

        finalResult
    }

    /**
     * Overload for convenience taking individual connection parameters.
     */
    suspend fun joinHotspot(
        ssid: String,
        password: String = "",
        security: String = "WPA",
        isHidden: Boolean = false,
        timeoutMs: Int = 30000
    ): JoinResult {
        return joinHotspot(
            credentials = WifiCredentials(
                ssid = ssid,
                password = password,
                security = security,
                hidden = isHidden
            ),
            timeoutMs = timeoutMs
        )
    }

    /**
     * Unbinds process from network, unregisters network callback, and clears references.
     * Safe to call multiple times.
     */
    @Synchronized
    fun leaveHotspot() {
        isJoined.set(false)

        try {
            connectivityManager.bindProcessToNetwork(null)
        } catch (_: Exception) {}

        boundNetworkRef.set(null)

        val cb = activeCallbackRef.getAndSet(null)
        if (cb != null) {
            try {
                connectivityManager.unregisterNetworkCallback(cb)
            } catch (_: Exception) {}
        }

        ControlChannelConstants.logStep(step = "left_hotspot")
    }

    companion object {
        /**
         * Extracts an IPv4 gateway address from LinkProperties:
         * 1. Looks for the gateway of the default route (0.0.0.0 destination).
         * 2. If none, checks DHCP server address (API 30+).
         * Returns null if neither appears or if no IPv4 address exists on the link.
         */
        fun extractIpv4Gateway(linkProperties: LinkProperties): String? {
            // Must have an assigned IPv4 address on the interface
            val hasIpv4 = linkProperties.linkAddresses.any { it.address is Inet4Address }
            if (!hasIpv4) {
                return null
            }

            // 1. Gateway of default route
            for (route in linkProperties.routes) {
                if (route.isDefaultRoute && route.gateway is Inet4Address) {
                    val host = route.gateway?.hostAddress
                    if (!host.isNullOrBlank() && host != "0.0.0.0") {
                        return host
                    }
                }
            }

            // Fallback: check any IPv4 route gateway if default flag wasn't explicitly set
            for (route in linkProperties.routes) {
                val gw = route.gateway
                if (gw is Inet4Address && !gw.isAnyLocalAddress) {
                    val host = gw.hostAddress
                    if (!host.isNullOrBlank() && host != "0.0.0.0") {
                        return host
                    }
                }
            }

            // 2. DHCP server address (API 30+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val dhcp = linkProperties.dhcpServerAddress
                if (dhcp is Inet4Address) {
                    val host = dhcp.hostAddress
                    if (!host.isNullOrBlank() && host != "0.0.0.0") {
                        return host
                    }
                }
            }

            return null
        }
    }
}
