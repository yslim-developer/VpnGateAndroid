package de.blinkt.openvpn.vpngate

import android.util.Base64
import java.nio.charset.StandardCharsets

class VpnGateCsvParser {
    fun parse(csv: String): List<VpnGateServer> {
        if (csv.length > MAX_CSV_CHARS) return emptyList()
        val records = readRecords(csv)
        val headerIndex = records.indexOfFirst { row ->
            row.firstOrNull()?.startsWith('#') == true && row.any { it == "OpenVPN_ConfigData_Base64" }
        }
        if (headerIndex < 0) return emptyList()
        val columns = records[headerIndex].map { it.removePrefix("#").trim() }
            .withIndex().associate { it.value to it.index }
        val required = listOf("HostName", "IP", "CountryShort", "Ping", "Speed", "NumVpnSessions", "LogType", "OpenVPN_ConfigData_Base64")
        if (!required.all(columns::containsKey)) return emptyList()

        return records.drop(headerIndex + 1).mapNotNull { row ->
            if (row.firstOrNull() == "*") return@mapNotNull null
            fun field(name: String): String = columns[name]?.let(row::getOrNull)?.trim().orEmpty()
            val ip = field("IP")
            val ping = field("Ping").toIntOrNull()
            val speed = field("Speed").toLongOrNull()
            val sessions = field("NumVpnSessions").toIntOrNull()
            val encoded = field("OpenVPN_ConfigData_Base64")
            if (field("HostName").isBlank() || !validIpv4(ip) ||
                ping == null || ping < 0 || speed == null || speed <= 0 ||
                sessions == null || sessions < 0 || encoded.length > MAX_PROFILE_BASE64_CHARS ||
                encoded.isEmpty() || !encoded.matches(BASE64_CHARS)) return@mapNotNull null
            val profile = try {
                val bytes = Base64.decode(encoded, Base64.DEFAULT)
                if (bytes.size > MAX_PROFILE_BYTES) return@mapNotNull null
                String(bytes, StandardCharsets.UTF_8)
            } catch (_: IllegalArgumentException) {
                return@mapNotNull null
            }
            if (profile.isBlank()) return@mapNotNull null
            VpnGateServer(
                hostName = field("HostName"),
                ipAddress = ip,
                countryName = field("CountryLong"),
                countryCode = field("CountryShort"),
                pingMs = ping,
                speedBps = speed,
                sessions = sessions,
                loggingPolicy = field("LogType"),
                openVpnConfig = profile,
            )
        }
    }

    private fun validIpv4(ip: String): Boolean {
        val parts = ip.split('.')
        return parts.size == 4 && parts.all { part ->
            part.isNotEmpty() && part.all(Char::isDigit) && part.toIntOrNull() in 0..255
        }
    }

    private fun readRecords(csv: String): List<List<String>> {
        val records = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var index = 0
        while (index < csv.length) {
            when (val char = csv[index]) {
                '"' -> {
                    if (quoted && index + 1 < csv.length && csv[index + 1] == '"') {
                        field.append('"')
                        index++
                    } else quoted = !quoted
                }
                ',' -> if (quoted) field.append(char) else {
                    row.add(field.toString())
                    field.setLength(0)
                }
                '\n' -> if (quoted) field.append(char) else {
                    row.add(field.toString().trimEnd('\r'))
                    records.add(row)
                    row = mutableListOf()
                    field.setLength(0)
                }
                else -> field.append(char)
            }
            index++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) {
            row.add(field.toString())
            records.add(row)
        }
        return records
    }

    companion object {
        const val MAX_CSV_CHARS = 8_000_000
        private const val MAX_PROFILE_BYTES = 128 * 1024
        private const val MAX_PROFILE_BASE64_CHARS = (MAX_PROFILE_BYTES * 4 / 3) + 8
        private val BASE64_CHARS = Regex("[A-Za-z0-9+/]+={0,2}")
    }
}
