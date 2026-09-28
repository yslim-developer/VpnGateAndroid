package de.blinkt.openvpn.vpngate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException

class VpnGateRepositoryTest {
    @Test
    fun boundedReadAcceptsSmallUtf8Response() {
        val input = ByteArrayInputStream("한국 VPN".toByteArray(Charsets.UTF_8))
        assertEquals("한국 VPN", VpnGateRepository.readBounded(input, 100))
    }

    @Test
    fun boundedReadRejectsOversizedResponse() {
        val input = ByteArrayInputStream(ByteArray(101))
        assertThrows(IOException::class.java) { VpnGateRepository.readBounded(input, 100) }
    }
}
