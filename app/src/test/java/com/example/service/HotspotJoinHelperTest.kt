package com.example.service

import android.content.Context
import android.net.LinkAddress
import android.net.LinkProperties
import android.net.RouteInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.Inet4Address
import java.net.InetAddress

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HotspotJoinHelperTest {

    private fun createLinkAddress(addressString: String): LinkAddress {
        try {
            val ctor = LinkAddress::class.java.getDeclaredConstructor(String::class.java)
            ctor.isAccessible = true
            return ctor.newInstance(addressString)
        } catch (_: Exception) {
            val ipPart = addressString.substringBefore("/")
            val prefixPart = addressString.substringAfter("/").toInt()
            val address = InetAddress.getByName(ipPart)

            val ctor = LinkAddress::class.java.declaredConstructors.first {
                it.parameterTypes.size >= 2 &&
                it.parameterTypes[0].isAssignableFrom(address.javaClass) &&
                it.parameterTypes[1] == Int::class.javaPrimitiveType
            }
            ctor.isAccessible = true
            val args = arrayOfNulls<Any>(ctor.parameterTypes.size)
            args[0] = address
            args[1] = prefixPart
            for (i in 2 until ctor.parameterTypes.size) {
                val pt = ctor.parameterTypes[i]
                if (pt == Int::class.javaPrimitiveType) args[i] = 0
                else if (pt == Long::class.javaPrimitiveType) args[i] = 0L
                else if (pt == Boolean::class.javaPrimitiveType) args[i] = false
            }
            return ctor.newInstance(*args) as LinkAddress
        }
    }

    private fun createDefaultRoute(gateway: InetAddress): RouteInfo {
        for (ctor in RouteInfo::class.java.declaredConstructors) {
            if (!ctor.parameterTypes.contains(InetAddress::class.java)) continue
            try {
                ctor.isAccessible = true
                val args = arrayOfNulls<Any>(ctor.parameterTypes.size)
                for (i in ctor.parameterTypes.indices) {
                    val pt = ctor.parameterTypes[i]
                    if (pt == InetAddress::class.java) {
                        args[i] = gateway
                    } else if (pt == Int::class.javaPrimitiveType) {
                        args[i] = 1
                    } else if (pt == Long::class.javaPrimitiveType) {
                        args[i] = 0L
                    } else if (pt == Boolean::class.javaPrimitiveType) {
                        args[i] = false
                    } else if (pt == String::class.java) {
                        args[i] = "wlan0"
                    }
                }
                return ctor.newInstance(*args) as RouteInfo
            } catch (_: Exception) {}
        }
        error("Unable to construct RouteInfo for gateway $gateway")
    }

    @Test
    fun testFailReasonEnumValues() {
        val reasons = JoinResult.FailReason.values().map { it.name }
        assertTrue(reasons.contains("Unavailable"))
        assertTrue(reasons.contains("Timeout"))
        assertTrue(reasons.contains("NoIp"))
        assertTrue(reasons.contains("Unsupported"))
        assertTrue(reasons.contains("Cancelled"))
    }

    @Test
    fun testExtractIpv4Gateway_noIpv4AddressReturnsNull() {
        val lp = LinkProperties()
        val gateway = HotspotJoinHelper.extractIpv4Gateway(lp)
        assertNull("Without an assigned IPv4 address on the link, gateway must be null", gateway)
    }

    @Test
    fun testExtractIpv4Gateway_withDefaultRoute() {
        val lp = LinkProperties()
        val gatewayIp = InetAddress.getByName("192.168.43.1") as Inet4Address

        val linkAddress = createLinkAddress("192.168.43.50/24")
        lp.setInterfaceName("wlan0")
        lp.setLinkAddresses(listOf(linkAddress))
        val route = createDefaultRoute(gatewayIp)
        lp.addRoute(route)

        val resolved = HotspotJoinHelper.extractIpv4Gateway(lp)
        assertNotNull("Must resolve gateway from route", resolved)
        assertEquals("192.168.43.1", resolved)
    }

    @Test
    fun testExtractIpv4Gateway_withDhcpServerAddressFallback() {
        val lp = LinkProperties()
        val gatewayIp = InetAddress.getByName("192.168.43.1") as Inet4Address

        val linkAddress = createLinkAddress("192.168.43.50/24")
        lp.setLinkAddresses(listOf(linkAddress))
        lp.setDhcpServerAddress(gatewayIp)

        val resolved = HotspotJoinHelper.extractIpv4Gateway(lp)
        assertNotNull("Must resolve gateway from dhcpServerAddress on API 30+", resolved)
        assertEquals("192.168.43.1", resolved)
    }

    @Test
    fun testLeaveHotspot_safeToCallMultipleTimes() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val helper = HotspotJoinHelper(context)

        helper.leaveHotspot()
        helper.leaveHotspot()
        helper.leaveHotspot()
    }
}
