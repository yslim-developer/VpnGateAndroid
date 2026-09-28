package de.blinkt.openvpn.vpngate

data class VpnGateServer(
    val hostName: String,
    val ipAddress: String,
    val countryName: String,
    val countryCode: String,
    val pingMs: Int,
    val speedBps: Long,
    val sessions: Int,
    val loggingPolicy: String,
    val openVpnConfig: String,
)
