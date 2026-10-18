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
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * For Settings: is it online, is a download pass running and how far it got ([checked] of [todo] stations), and which
 * stations have no usable logo anywhere ([missed]).
 */
data class LogoStatus(val online: Boolean = false, val running: Boolean = false, val checked: Int = 0, val todo: Int = 0,
                      val missed: Set<ServiceKey> = emptySet())

/**
 * Downloads missing station logos whenever the device is online and Settings → "Download station logos" is on
 * (offline first: nothing depends on it). Order: the station playing, then favourites, then the rest. Each pass
 * restarts when connectivity, the setting or the station list changes, or on [retryMissing] / [refreshAll]; stations
 * already done are skipped. A station whose lookup went unanswered (timeout, connection gone) is tried again next pass;
 * one with no usable logo anywhere after a few days ([LogoStore.RETRY_MISS_MS]).
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
    private val requests = MutableStateFlow(0)                  // Settings buttons: a new pass now
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _status = MutableStateFlow(LogoStatus())
    val status: StateFlow<LogoStatus> = combine(_status, logos.missed) { s, m -> s.copy(missed = m) }
        .stateIn(scope, SharingStarted.Eagerly, LogoStatus())

    /** Settings → "Retry missing": look up the stations without a logo again, now. Off the main thread (disk sync). */
    fun retryMissing() { scope.launch { logos.retryMissing(); requests.update { it + 1 } } }

    /** Settings → "Refresh all": look up every station again; each keeps its logo until a new one is found. */
    fun refreshAll() { scope.launch { logos.refreshAll(); requests.update { it + 1 } } }

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
                stations.state.map { s -> s.stations.map { it.key } }.distinctUntilChanged(), requests) { on, enabled, _, _ -> on && enabled }
                .collectLatest { go ->
                    if (!go) return@collectLatest
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
        val checked = AtomicInteger()
        _status.update { it.copy(running = true, checked = 0, todo = todo.size) }
        val finder = LogoFinder(OkHttpGet(), DnsJavaLookup(::dnsServers))       // one per pass: shares SI.xml files
        val gate = Semaphore(3)
        for (st in todo) launch {
            gate.withPermit {
                val q = LogoQuery(st.key.ecc, st.ensemble.eid, st.key.sid.toInt(), st.label)
                val r = try { finder.lookup(q) } catch (e: CancellationException) { throw e } catch (e: Exception) { LogoResult.Unreachable }
                ensureActive()                           // cancelled mid-lookup (scan, network change): that's not a miss
                try {
                    when (r) {
                        is LogoResult.Found -> {
                            logos.save(st.key, r.logo.bytes, r.logo.source.name, r.logo.url)
                            Log.i(TAG, "${st.label}: ${r.logo.source} ${r.logo.info.width}x${r.logo.info.height}")
                        }
                        LogoResult.NotFound -> { logos.miss(st.key); Log.i(TAG, "${st.label}: no usable logo") }
                        LogoResult.Unreachable -> Log.i(TAG, "${st.label}: no answer, next time")   // not a miss
                    }
                } catch (e: java.io.IOException) { Log.w(TAG, "logo cache: ${e.message}") }    // disk full: try again next time
                val n = checked.incrementAndGet()
                _status.update { it.copy(checked = n) }
            }
        }
    }

    private fun dnsServers(): List<InetAddress> = cm.getLinkProperties(cm.activeNetwork)?.dnsServers.orEmpty()

    private companion object { const val TAG = "Logos" }
}
