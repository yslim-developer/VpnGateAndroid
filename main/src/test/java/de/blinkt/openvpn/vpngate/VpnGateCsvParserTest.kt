package de.blinkt.openvpn.vpngate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27])
class VpnGateCsvParserTest {
    private val header = "#HostName,IP,Score,Ping,Speed,CountryLong,CountryShort,NumVpnSessions,Uptime,TotalUsers,TotalTraffic,LogType,Operator,Message,OpenVPN_ConfigData_Base64"

    private fun encoded(profile: String) = Base64.getEncoder().encodeToString(profile.toByteArray())

    @Test
    fun parsesOfficialFeedAndQuotedCommas() {
        val config = "client\nremote 203.0.113.1 1194\n"
        val csv = """*vpn_servers
$header
vpn1,203.0.113.1,100,25,5000000,Japan,JP,4,1000,7,900,2weeks,"Volunteer, Inc.","Hello, world",${encoded(config)}
*
"""

        val server = VpnGateCsvParser().parse(csv).single()

        assertEquals("vpn1", server.hostName)
        assertEquals("JP", server.countryCode)
        assertEquals(25, server.pingMs)
        assertEquals(5_000_000L, server.speedBps)
        assertEquals(4, server.sessions)
        assertEquals("2weeks", server.loggingPolicy)
        assertEquals(config, server.openVpnConfig)
    }

    @Test
    fun usesHeaderNamesAndSkipsMalformedRows() {
        val config = encoded("client\nremote example.org 443\n")
        val csv = """*vpn_servers
#CountryShort,HostName,IP,OpenVPN_ConfigData_Base64,Ping,Speed,NumVpnSessions,LogType
US,vpn2,198.51.100.2,$config,50,1000000,2,none
US,vpn3,not-an-ip,not-base64,50,1000000,2,none
US,vpn4,198.51.100.4,$config,broken,1000000,2,none
*
"""

        val servers = VpnGateCsvParser().parse(csv)

        assertEquals(1, servers.size)
        assertEquals("vpn2", servers.single().hostName)
    }

    @Test
    fun rejectsMissingRequiredHeaderAndOversizedInput() {
        assertTrue(VpnGateCsvParser().parse("#HostName,IP\nvpn1,203.0.113.1\n").isEmpty())
        assertTrue(VpnGateCsvParser().parse("x".repeat(VpnGateCsvParser.MAX_CSV_CHARS + 1)).isEmpty())
    }
}
