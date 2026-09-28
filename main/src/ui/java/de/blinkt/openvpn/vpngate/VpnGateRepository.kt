package de.blinkt.openvpn.vpngate

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URL
import javax.net.ssl.HttpsURLConnection

data class VpnGateResult(
    val servers: List<VpnGateServer>,
    val updatedAt: Long,
    val fromCache: Boolean,
)

class VpnGateRepository(context: Context) {
    private val cacheFile = context.cacheDir.resolve("vpngate-servers.csv")
    private val parser = VpnGateCsvParser()

    suspend fun refresh(): VpnGateResult = withContext(Dispatchers.IO) {
        try {
            val connection = URL(FEED_URL).openConnection() as HttpsURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            try {
                if (connection.responseCode != HttpsURLConnection.HTTP_OK) {
                    throw IOException("VPN Gate returned HTTP ${connection.responseCode}")
                }
                val csv = connection.inputStream.use { readBounded(it, MAX_CSV_BYTES) }
                val servers = parser.parse(csv)
                if (servers.isEmpty()) throw IOException("VPN Gate server list is empty")
                val tempFile = cacheFile.resolveSibling("${cacheFile.name}.tmp")
                tempFile.writeText(csv, Charsets.UTF_8)
                if (!tempFile.renameTo(cacheFile)) {
                    cacheFile.writeText(csv, Charsets.UTF_8)
                    tempFile.delete()
                }
                VpnGateResult(servers, System.currentTimeMillis(), false)
            } finally {
                connection.disconnect()
            }
        } catch (networkError: IOException) {
            if (!cacheFile.isFile) throw networkError
            val cached = cacheFile.inputStream().use { readBounded(it, MAX_CSV_BYTES) }
            val servers = parser.parse(cached)
            if (servers.isEmpty()) throw networkError
            VpnGateResult(servers, cacheFile.lastModified(), true)
        }
    }

    companion object {
        private const val FEED_URL = "https://www.vpngate.net/api/iphone/"
        private const val MAX_CSV_BYTES = 8_000_000

        @Throws(IOException::class)
        internal fun readBounded(input: InputStream, maxBytes: Int): String {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (output.size() + count > maxBytes) throw IOException("VPN Gate list is too large")
                output.write(buffer, 0, count)
            }
            return output.toString(Charsets.UTF_8.name())
        }
    }
}
