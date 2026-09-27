package io.github.jvspier.dabplay.logos

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import io.github.jvspier.dabplay.data.LogoStore
import io.github.jvspier.dabplay.data.ServiceKey
import io.github.jvspier.dabplay.data.SettingsStore
import io.github.jvspier.dabplay.data.StationStore
import java.net.InetAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** For Settings: is it online, and is a download pass running right now. */
data class LogoStatus(val online: Boolean = false, val running: Boolean = false)

/**
 * Downloads missing station logos whenever the device is online and Settings → "Download station logos" is on
 * (offline first: nothing depends on it). Order: the station playing, then favourites, then the rest. Each pass
 * restarts when connectivity, the setting or the station list changes; stations already done are skipped.
 */
class LogoFetcher(
    context: Context,
    private val stations: StationStore,
    private val logos: LogoStore,
    private val settings: SettingsStore,
    private val current: () -> ServiceKey?,
) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val online = MutableStateFlow(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _status = MutableStateFlow(LogoStatus())
    val status: StateFlow<LogoStatus> = _status

    fun start() {
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(n: Network, caps: NetworkCapabilities) {
                online.value = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                _status.update { it.copy(online = online.value) }
            }
            override fun onLost(n: Network) { online.value = false; _status.update { it.copy(online = false) } }
        })
        scope.launch {
            combine(online, settings.state.map { it.downloadLogos }.distinctUntilChanged(),
                stations.state.map { s -> s.stations.map { it.key } }.distinctUntilChanged()) { on, enabled, _ -> on && enabled }
                .collectLatest { go ->
                    if (!go) return@collectLatest
                    _status.update { it.copy(running = true) }
                    try { pass() } finally { _status.update { it.copy(running = false) } }
                }
        }
    }

    private suspend fun pass() = coroutineScope {
        val snap = stations.state.value
        val cur = current()
        val todo = snap.stations.filter { logos.needsLookup(it.key) }
            .sortedWith(compareBy({ it.key != cur }, { it.key !in snap.favourites }, { it.label.lowercase() }))
        if (todo.isEmpty()) return@coroutineScope
        Log.i(TAG, "looking up ${todo.size} logos")
        val finder = LogoFinder(OkHttpGet(), DnsJavaLookup(::dnsServers))       // one per pass: shares SI.xml files
        val gate = Semaphore(3)
        for (st in todo) launch {
            gate.withPermit {
                val q = LogoQuery(st.key.ecc, st.ensemble.eid, st.key.sid.toInt(), st.label)
                val found = runCatching { finder.find(q) }.getOrNull()
                when {
                    found != null -> {
                        logos.save(st.key, found.bytes, found.source.name, found.url)
                        Log.i(TAG, "${st.label}: ${found.source} ${found.info.width}x${found.info.height}")
                    }
                    online.value -> { logos.miss(st.key); Log.i(TAG, "${st.label}: no usable logo") }   // offline mid-pass: not a miss
                }
            }
        }
    }

    private fun dnsServers(): List<InetAddress> = cm.getLinkProperties(cm.activeNetwork)?.dnsServers.orEmpty()

    private companion object { const val TAG = "Logos" }
}
