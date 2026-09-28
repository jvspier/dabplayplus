package io.github.jvspier.dabplay.core

import io.github.jvspier.dabplay.core.fic.FicDecoder

/** FIG 0/18: which announcement types a service takes part in ([asu], ASu flags) and in which clusters. */
data class AnnouncementSupport(val asu: Int, val clusters: Set<Int>)

/** FIG 0/19: the announcement on in a cluster ([asw], 0 = none) and the sub-channel carrying it; [seq] orders updates. */
data class AnnouncementSwitch(val asw: Int, val subchannel: Int, val newFlag: Boolean, val region: Int?, val seq: Long)

/** Announcement types (EN 300 401 §8.1.6.1, ASu/ASw flag bits). */
object Announcement {
    const val ALARM = 1 shl 0
    const val TRAFFIC = 1 shl 1          // road traffic flash
    const val TRANSPORT = 1 shl 2
    const val WARNING = 1 shl 3
    const val NEWS = 1 shl 4
}

/** An announcement to switch to: in [cluster], of type [flags], on [subchannel]. */
data class ActiveAnnouncement(val cluster: Int, val subchannel: Int, val flags: Int)

/**
 * Decides when to switch to an announcement and back (EN 300 401 §8.1.6). For the station being listened to, it looks
 * at the clusters the station belongs to (FIG 0/18) and what those clusters announce now (FIG 0/19). A switching
 * entry only counts while the broadcast keeps repeating it: when it stops arriving for [staleMs] the announcement is
 * over (we only catch part of the FIC while audio streams). Announcements on the station's own sub-channel need no
 * switch; a skipped one is ignored until its cluster goes quiet.
 */
class AnnouncementWatcher(private val staleMs: Long = 3000) {
    private val lastSeq = HashMap<Int, Long>()
    private val lastSeen = HashMap<Int, Long>()
    private val skipped = HashSet<Int>()

    /** [wanted]: ASw flags to follow (traffic if the setting is on; alarm always). */
    fun update(now: Long, fic: FicDecoder, sid: Long, ownSubchannel: Int, wanted: Int): ActiveAnnouncement? {
        for ((cluster, sw) in fic.announcementSwitching) {
            if (sw.seq != lastSeq[cluster]) { lastSeq[cluster] = sw.seq; lastSeen[cluster] = now }
        }
        val support = fic.announcementSupport[sid] ?: return null
        for (cluster in support.clusters.sorted()) {
            val sw = fic.announcementSwitching[cluster] ?: continue
            val fresh = now - (lastSeen[cluster] ?: Long.MIN_VALUE / 2) < staleMs
            val on = fresh && sw.asw and wanted != 0
            if (!on) { skipped -= cluster; continue }
            if (cluster in skipped || sw.subchannel == ownSubchannel) continue
            return ActiveAnnouncement(cluster, sw.subchannel, sw.asw and wanted)
        }
        return null
    }

    /** Whether the station takes part in any of [wanted] (for the TA badge). */
    fun supports(fic: FicDecoder, sid: Long, wanted: Int) = (fic.announcementSupport[sid]?.asu ?: 0) and wanted != 0

    /** The listener skipped this announcement: ignore the cluster until it goes quiet. */
    fun skip(cluster: Int) { skipped += cluster }
}
