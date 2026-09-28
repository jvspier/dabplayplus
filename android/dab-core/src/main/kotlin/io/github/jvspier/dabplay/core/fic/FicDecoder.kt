package io.github.jvspier.dabplay.core.fic

import io.github.jvspier.dabplay.core.AnnouncementSupport
import io.github.jvspier.dabplay.core.AnnouncementSwitch
import io.github.jvspier.dabplay.core.Crc
import io.github.jvspier.dabplay.core.DabText
import io.github.jvspier.dabplay.core.u8

data class Subchannel(val id: Int, val startCu: Int, val sizeCu: Int?, val bitrateKbps: Int?, val protection: String)

/** An MSC stream audio component of a service (TMid 0). ASCTy 63 = DAB+, 0 = MP2. */
data class AudioComponent(val ascty: Int, val subchannel: Int, val primary: Boolean)

data class LinkId(val type: String, val id: Long)

class LinkageSet(val international: Boolean, val lsn: Int, var hard: Boolean, var active: Boolean, val otherEnsemble: Boolean) {
    val ids = LinkedHashSet<LinkId>()
}

/** FIG 0/21 key: other-ensemble flag, R&M (0 = DAB ensemble, 8/9 = FM with/without RDS), Id (EId or PI). */
data class FiKey(val otherEnsemble: Boolean, val rm: Int, val id: Int)

/** A frequency in kHz, or undecoded raw bytes for other R&M types. */
sealed interface FiFrequency {
    data class Khz(val khz: Int) : FiFrequency
    data class Raw(val hex: String) : FiFrequency
}

/**
 * Collects ensemble information from FIBs (ETSI EN 300 401). Port of tools/fic.py:
 * FIG 0/0 (EId), 0/1 (sub-channels), 0/2 (services), 0/6 (linking), 0/9 (ECC), 0/13 (user apps),
 * 0/21 (frequency information), 0/24 (other-ensemble services), 1/0 and 1/1 (labels).
 */
class FicDecoder {
    var eid: Int? = null; private set
    var ecc: Int? = null; private set
    var ensembleLabel: String? = null; private set
    val services = LinkedHashMap<Long, String>()
    val components = LinkedHashMap<Long, List<AudioComponent>>()
    val subchannels = LinkedHashMap<Int, Subchannel>()
    val userApps = LinkedHashMap<Long, MutableSet<String>>()
    val links = LinkedHashMap<Pair<Boolean, Int>, LinkageSet>()
    val frequencyInfo = LinkedHashMap<FiKey, MutableSet<FiFrequency>>()
    val otherEnsembleServices = LinkedHashMap<Long, MutableSet<Int>>()
    /** FIG 0/18: per service, the announcement types it takes part in (ASu flags) and its announcement clusters. */
    val announcementSupport = LinkedHashMap<Long, AnnouncementSupport>()
    /** FIG 0/19 (this ensemble): per cluster, the announcement on now (ASw flags, 0 = none) and its sub-channel. */
    val announcementSwitching = LinkedHashMap<Int, AnnouncementSwitch>()
    private var switchSeq = 0L
    var fibsOk = 0; private set
    var fibsBad = 0; private set

    /** Feed FIC data: any number of whole 32-byte FIBs (a 384-byte block from the dongle = 12 FIBs). */
    fun feed(data: ByteArray, from: Int = 0, to: Int = data.size) {
        var o = from
        while (o + 32 <= to) { feedFib(data, o); o += 32 }
    }

    /** Returns false if the FIB CRC fails (the FIB is then ignored). */
    fun feedFib(fib: ByteArray, off: Int): Boolean {
        if (!Crc.fibOk(fib, off)) { fibsBad++; return false }
        fibsOk++
        var i = 0
        while (i < 30) {
            val hdr = fib.u8(off + i)
            if (hdr == 0xFF) break
            val type = hdr shr 5
            val len = hdr and 0x1F
            val start = off + i + 1
            val end = minOf(start + len, off + 32)
            i += 1 + len
            if (end <= start) continue
            val body = fib.copyOfRange(start, end)
            when (type) {
                0 -> fig0(body)
                1 -> fig1(body)
            }
        }
        return true
    }

    private fun fig0(b: ByteArray) {
        when (b.u8(0) and 0x1F) {
            0 -> if (b.size >= 3) eid = (b.u8(1) shl 8) or b.u8(2)
            1 -> fig0_1(b)
            2 -> fig0_2(b)
            6 -> fig0_6(b)
            9 -> if (b.size >= 3) ecc = b.u8(2)
            13 -> fig0_13(b)
            21 -> fig0_21(b)
            18 -> fig0_18(b)
            19 -> if ((b.u8(0) shr 6) and 1 == 0) fig0_19(b)     // OE = 0: this ensemble's announcements only
            24 -> fig0_24(b)
        }
    }

    /** EN 300 401 §8.1.6.1: SId (16), ASu flags (16), Rfa (5), number of clusters (3), cluster ids (8 each). */
    private fun fig0_18(b: ByteArray) {
        var i = 1
        while (i + 5 <= b.size) {
            val sid = ((b.u8(i) shl 8) or b.u8(i + 1)).toLong()
            val asu = (b.u8(i + 2) shl 8) or b.u8(i + 3)
            val n = b.u8(i + 4) and 0x1F
            i += 5
            if (i + n > b.size) return
            announcementSupport[sid] = AnnouncementSupport(asu, (0 until n).map { b.u8(i + it) }.toSet())
            i += n
        }
    }

    /** §8.1.6.2: cluster id (8), ASw flags (16), new flag (1), region flag (1), SubChId (6), [Rfa (2), region (6)]. */
    private fun fig0_19(b: ByteArray) {
        var i = 1
        while (i + 4 <= b.size) {
            val cluster = b.u8(i)
            val asw = (b.u8(i + 1) shl 8) or b.u8(i + 2)
            val newFlag = b.u8(i + 3) and 0x80 != 0
            val regionFlag = b.u8(i + 3) and 0x40 != 0
            val subch = b.u8(i + 3) and 0x3F
            i += 4
            val region = if (regionFlag) { if (i >= b.size) return; (b.u8(i) and 0x3F).also { i += 1 } } else null
            announcementSwitching[cluster] = AnnouncementSwitch(asw, subch, newFlag, region, ++switchSeq)
        }
    }

    private fun fig1(b: ByteArray) {
        val ext = b.u8(0) and 0x07
        val charset = b.u8(0) shr 4
        if (b.size < 1 + 2 + 16) return
        val label = DabText.decode(b, 3, 19, charset).trimEnd()
        when (ext) {
            0 -> { eid = (b.u8(1) shl 8) or b.u8(2); ensembleLabel = label }
            1 -> services[((b.u8(1) shl 8) or b.u8(2)).toLong()] = label
        }
    }

    private fun sid(b: ByteArray, i: Int, pd: Boolean): Long {
        var v = 0L
        for (k in 0 until if (pd) 4 else 2) v = (v shl 8) or b.u8(i + k).toLong()
        return v
    }

    private fun fig0_1(b: ByteArray) {
        var i = 1
        while (i + 3 <= b.size) {
            val id = b.u8(i) shr 2
            val start = ((b.u8(i) and 3) shl 8) or b.u8(i + 1)
            if (b.u8(i + 2) and 0x80 != 0) {                      // long form (EEP)
                if (i + 4 > b.size) break
                val opt = (b.u8(i + 2) shr 4) and 7
                val lvl = (b.u8(i + 2) shr 2) and 3
                val size = ((b.u8(i + 2) and 3) shl 8) or b.u8(i + 3)
                val br = when (opt) { 0 -> size / EEP_A[lvl] * 8; 1 -> size / EEP_B[lvl] * 32; else -> null }
                val prot = "EEP ${lvl + 1}-${if (opt < 2) "AB"[opt] else '?'}"
                subchannels[id] = Subchannel(id, start, size, br, prot)
                i += 4
            } else {                                               // short form (UEP): size and bitrate from Table 8
                val t = b.u8(i + 2) and 0x3F
                subchannels[id] = Subchannel(id, start, Uep.SIZE_CU[t], Uep.BITRATE[t], "UEP #$t (PL${Uep.LEVEL[t]})")
                i += 3
            }
        }
    }

    private fun fig0_2(b: ByteArray) {
        val pd = (b.u8(0) shr 5) and 1 == 1
        var i = 1
        while (i < b.size) {
            val sidLen = if (pd) 4 else 2
            if (i + sidLen + 1 > b.size) break
            val sid = sid(b, i, pd)
            val n = b.u8(i + sidLen) and 0x0F
            i += sidLen + 1
            val comps = ArrayList<AudioComponent>()
            repeat(n) {
                if (i + 2 > b.size) return@repeat
                if (b.u8(i) shr 6 == 0) comps += AudioComponent(b.u8(i) and 0x3F, b.u8(i + 1) shr 2, b.u8(i + 1) and 2 != 0)
                i += 2
            }
            if (comps.isNotEmpty()) components[sid] = comps
        }
    }

    private fun fig0_6(b: ByteArray) {
        val oe = (b.u8(0) shr 6) and 1 == 1
        val pd = (b.u8(0) shr 5) and 1 == 1
        var i = 1
        while (i + 2 <= b.size) {
            val idlf = b.u8(i) shr 7 == 1
            val la = (b.u8(i) shr 6) and 1 == 1
            val sh = (b.u8(i) shr 5) and 1 == 1
            val ils = (b.u8(i) shr 4) and 1 == 1
            val lsn = ((b.u8(i) and 0x0F) shl 8) or b.u8(i + 1)
            i += 2
            val ls = links.getOrPut(ils to lsn) { LinkageSet(ils, lsn, sh, la, oe) }
            ls.active = la; ls.hard = sh
            if (!idlf) continue
            if (i >= b.size) break
            val idlq = (b.u8(i) shr 5) and 3
            val n = b.u8(i) and 0x0F
            i += 1
            val type = when (idlq) { 0 -> "DAB"; 1 -> "RDS-PI"; 3 -> "DRM/AMSS"; else -> "IdLQ$idlq" }
            for (k in 0 until n) {
                val w = if (pd) 4 else if (ils) 3 else 2
                if (i + w > b.size) break
                var v = 0L
                for (x in 0 until w) v = (v shl 8) or b.u8(i + x).toLong()
                ls.ids += LinkId(type, v)
                i += w
            }
        }
    }

    private fun fig0_13(b: ByteArray) {
        val pd = (b.u8(0) shr 5) and 1 == 1
        var i = 1
        while (i < b.size) {
            val sid = sid(b, i, pd); i += if (pd) 4 else 2
            if (i >= b.size) break
            val n = b.u8(i) and 0x0F
            i += 1
            for (k in 0 until n) {
                if (i + 2 > b.size) return
                val type = (b.u8(i) shl 3) or (b.u8(i + 1) shr 5)
                val len = b.u8(i + 1) and 0x1F
                userApps.getOrPut(sid) { LinkedHashSet() } += USER_APPS[type] ?: "UA0x%x".format(type)
                i += 2 + len
            }
        }
    }

    private fun fig0_21(b: ByteArray) {
        val oe = (b.u8(0) shr 6) and 1 == 1
        var i = 1
        while (i + 2 <= b.size) {
            val n = b.u8(i + 1) and 0x1F
            val lstStart = i + 2
            val lstEnd = minOf(lstStart + n, b.size)
            i += 2 + n
            var j = lstStart
            while (j + 3 <= lstEnd) {
                val id = (b.u8(j) shl 8) or b.u8(j + 1)
                val rm = b.u8(j + 2) shr 4
                val flen = b.u8(j + 2) and 0x07
                val fStart = j + 3
                val fEnd = minOf(fStart + flen, lstEnd)
                j += 3 + flen
                val fs = frequencyInfo.getOrPut(FiKey(oe, rm, id)) { LinkedHashSet() }
                when (rm) {
                    0 -> { var k = fStart; while (k + 3 <= fEnd) {
                        fs += FiFrequency.Khz((((b.u8(k) and 7) shl 16) or (b.u8(k + 1) shl 8) or b.u8(k + 2)) * 16); k += 3 } }
                    8, 9 -> for (k in fStart until fEnd) { val c = b.u8(k); if (c in 1..204) fs += FiFrequency.Khz(87500 + c * 100) }
                    else -> fs += FiFrequency.Raw(b.copyOfRange(fStart, fEnd).joinToString("") { "%02x".format(it) })
                }
            }
        }
    }

    private fun fig0_24(b: ByteArray) {
        val pd = (b.u8(0) shr 5) and 1 == 1
        var i = 1
        while (i < b.size) {
            val sid = sid(b, i, pd); i += if (pd) 4 else 2
            if (i >= b.size) break
            val n = b.u8(i) and 0x0F
            i += 1
            for (k in 0 until n) {
                if (i + 2 > b.size) return
                otherEnsembleServices.getOrPut(sid) { LinkedHashSet() } += (b.u8(i) shl 8) or b.u8(i + 1)
                i += 2
            }
        }
    }

    companion object {
        private val EEP_A = intArrayOf(12, 8, 6, 4)     // CU per 8 kbit/s, protection 1-A..4-A
        private val EEP_B = intArrayOf(27, 21, 18, 15)  // CU per 32 kbit/s, protection 1-B..4-B
        val USER_APPS = mapOf(
            0x002 to "SLS", 0x003 to "BWS", 0x004 to "TPEG", 0x005 to "DGPS", 0x006 to "TMC", 0x007 to "SPI",
            0x008 to "DAB-Java", 0x009 to "DMB", 0x00A to "IPDC", 0x00B to "Voice", 0x00C to "Middleware",
            0x00D to "Filecasting", 0x44A to "Journaline",
        )
    }
}
