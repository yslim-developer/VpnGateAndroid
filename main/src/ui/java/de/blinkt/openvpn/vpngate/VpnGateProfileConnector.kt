package de.blinkt.openvpn.vpngate

import de.blinkt.openvpn.VpnProfile
import de.blinkt.openvpn.core.ConfigParser
import java.io.StringReader

object VpnGateProfileConnector {
    fun toProfile(server: VpnGateServer): VpnProfile {
        require(server.openVpnConfig.lineSequence().any { it.trimStart().startsWith("remote ") }) {
            "VPN Gate profile has no remote server"
        }
        val parser = ConfigParser()
        parser.parseConfig(StringReader(server.openVpnConfig))
        return parser.convertProfile().apply {
            mName = "VPN Gate ${server.countryCode} ${server.hostName}"
            mProfileCreator = "VPN Gate"
        }
    }
}
