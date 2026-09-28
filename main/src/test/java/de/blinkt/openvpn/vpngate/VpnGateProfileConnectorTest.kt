package de.blinkt.openvpn.vpngate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27])
class VpnGateProfileConnectorTest {
    private fun server(config: String) = VpnGateServer(
        hostName = "vpn1",
        ipAddress = "203.0.113.1",
        countryName = "Japan",
        countryCode = "JP",
        pingMs = 10,
        speedBps = 1_000_000,
        sessions = 3,
        loggingPolicy = "2weeks",
        openVpnConfig = config,
    )

    @Test
    fun convertsInlineConfigToNamedProfile() {
        val profile = VpnGateProfileConnector.toProfile(server("client\nremote 203.0.113.1 1194\n"))
        assertEquals("VPN Gate JP vpn1", profile.name)
    }

    @Test
    fun rejectsConfigWithoutRemote() {
        assertThrows(IllegalArgumentException::class.java) {
            VpnGateProfileConnector.toProfile(server("client\n"))
        }
    }
}
