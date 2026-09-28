package de.blinkt.openvpn.fragments

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import de.blinkt.openvpn.LaunchVPN
import de.blinkt.openvpn.R
import de.blinkt.openvpn.activities.DisconnectVPN
import de.blinkt.openvpn.core.ConnectionStatus
import de.blinkt.openvpn.core.OpenVPNService
import de.blinkt.openvpn.core.ProfileManager
import de.blinkt.openvpn.core.VpnStatus
import de.blinkt.openvpn.vpngate.VpnGateProfileConnector
import de.blinkt.openvpn.vpngate.VpnGateRepository
import de.blinkt.openvpn.vpngate.VpnGateServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import java.util.Locale

class VpnGateFragment : Fragment(), VpnStatus.StateListener {
    private data class CountryOption(val label: String, val code: String) {
        override fun toString() = label
    }

    private var servers = emptyList<VpnGateServer>()
    private var displayed = emptyList<VpnGateServer>()
    private var refreshGeneration = 0
    private lateinit var countrySpinner: Spinner
    private lateinit var listAdapter: ArrayAdapter<VpnGateServer>
    private lateinit var updatedLabel: TextView
    private lateinit var statusLabel: TextView
    private lateinit var disconnectButton: Button

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View =
        inflater.inflate(R.layout.fragment_vpngate, container, false)

    override fun onViewCreated(view: View, state: Bundle?) {
        super.onViewCreated(view, state)
        countrySpinner = view.findViewById(R.id.vpngate_country)
        updatedLabel = view.findViewById(R.id.vpngate_updated)
        statusLabel = view.findViewById(R.id.vpngate_status)
        disconnectButton = view.findViewById(R.id.vpngate_disconnect)
        val list = view.findViewById<ListView>(R.id.vpngate_list)
        listAdapter = object : ArrayAdapter<VpnGateServer>(requireContext(), android.R.layout.simple_list_item_2) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row = convertView ?: layoutInflater.inflate(android.R.layout.simple_list_item_2, parent, false)
                val server = getItem(position)!!
                row.findViewById<TextView>(android.R.id.text1).text =
                    "${server.countryName.ifBlank { server.countryCode }} · ${server.hostName}"
                row.findViewById<TextView>(android.R.id.text2).text = getString(
                    R.string.vpngate_server_detail,
                    server.ipAddress,
                    server.pingMs,
                    server.speedBps / 1_000_000.0,
                    server.sessions,
                    server.loggingPolicy,
                )
                return row
            }
        }
        list.adapter = listAdapter
        list.setOnItemClickListener { _, _, position, _ -> offerConnection(displayed[position]) }
        countrySpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = applyFilter()
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        view.findViewById<Button>(R.id.vpngate_refresh).setOnClickListener { refresh() }
        disconnectButton.setOnClickListener {
            startActivity(Intent(requireContext(), DisconnectVPN::class.java))
        }
        updateCountries()
        refresh()
    }

    override fun onResume() {
        super.onResume()
        VpnStatus.addStateListener(this)
        updateConnectionStatus()
    }

    override fun onPause() {
        VpnStatus.removeStateListener(this)
        super.onPause()
    }

    private fun refresh() {
        val generation = ++refreshGeneration
        updatedLabel.setText(R.string.vpngate_loading)
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = VpnGateRepository(requireContext()).refresh()
                if (generation != refreshGeneration) return@launch
                servers = result.servers.sortedWith(compareBy<VpnGateServer> { it.pingMs }.thenByDescending { it.speedBps })
                updateCountries()
                val time = DateFormat.getDateTimeInstance().format(Date(result.updatedAt))
                updatedLabel.text = getString(
                    if (result.fromCache) R.string.vpngate_cached else R.string.vpngate_updated,
                    time,
                    servers.size,
                )
            } catch (error: Exception) {
                if (generation == refreshGeneration) {
                    updatedLabel.text = getString(R.string.vpngate_load_error, error.localizedMessage ?: "unknown error")
                }
            }
        }
    }

    private fun updateCountries() {
        val oldSelection = (countrySpinner.selectedItem as? CountryOption)?.code ?: "!KR"
        val countries = servers.map { CountryOption("${it.countryName} (${it.countryCode})", it.countryCode) }
            .distinctBy { it.code }
            .sortedBy { it.label.lowercase(Locale.ROOT) }
        val options = listOf(
            CountryOption(getString(R.string.vpngate_outside_korea), "!KR"),
            CountryOption(getString(R.string.vpngate_all_countries), "*"),
        ) + countries
        countrySpinner.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, options)
        countrySpinner.setSelection(options.indexOfFirst { it.code == oldSelection }.coerceAtLeast(0))
        applyFilter()
    }

    private fun applyFilter() {
        val selected = (countrySpinner.selectedItem as? CountryOption)?.code ?: "!KR"
        displayed = when (selected) {
            "!KR" -> servers.filter { it.countryCode != "KR" }
            "*" -> servers
            else -> servers.filter { it.countryCode == selected }
        }
        listAdapter.clear()
        listAdapter.addAll(displayed)
        listAdapter.notifyDataSetChanged()
    }

    private fun offerConnection(server: VpnGateServer) {
        val preferences = requireContext().getSharedPreferences("vpngate", Activity.MODE_PRIVATE)
        if (preferences.getBoolean("privacy_acknowledged", false)) {
            connect(server)
            return
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.vpngate_warning_title)
            .setMessage(R.string.vpngate_warning)
            .setPositiveButton(R.string.vpngate_connect) { _, _ ->
                preferences.edit().putBoolean("privacy_acknowledged", true).apply()
                connect(server)
            }
            .setNeutralButton(R.string.vpngate_policy) { _, _ ->
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.vpngate.net/en/about_abuse.aspx")))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun connect(server: VpnGateServer) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val profile = withContext(Dispatchers.Default) { VpnGateProfileConnector.toProfile(server) }
                val error = profile.checkProfile(requireContext())
                if (error != R.string.no_error_found) {
                    throw IllegalArgumentException(getString(error))
                }
                ProfileManager.setTemporaryProfile(requireContext(), profile)
                val intent = Intent(requireContext(), LaunchVPN::class.java).apply {
                    action = Intent.ACTION_MAIN
                    putExtra(LaunchVPN.EXTRA_KEY, profile.uuid.toString())
                    putExtra(OpenVPNService.EXTRA_START_REASON, "VPN Gate server list")
                }
                startActivity(intent)
            } catch (error: Exception) {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.vpngate_profile_error, error.localizedMessage ?: "unknown error"),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun updateConnectionStatus() {
        if (!isAdded || view == null) return
        val connected = VpnStatus.isVPNActive()
        statusLabel.setText(if (connected) R.string.vpngate_connected else R.string.vpngate_disconnected)
        disconnectButton.isEnabled = connected
    }

    override fun updateState(
        state: String?,
        logmessage: String?,
        localizedResId: Int,
        level: ConnectionStatus?,
        intent: Intent?,
    ) {
        activity?.runOnUiThread { updateConnectionStatus() }
    }

    override fun setConnectedVPN(uuid: String?) {
        activity?.runOnUiThread { updateConnectionStatus() }
    }
}
