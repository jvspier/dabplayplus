package io.github.jvspier.dabplay.core.fic

import io.github.jvspier.dabplay.core.Golden
import io.github.jvspier.dabplay.core.Golden.q
import org.junit.Assume.assumeTrue
import kotlin.test.Test

class FicGoldenTest {
    private fun lines(d: FicDecoder): List<String> {
        val head = listOf(
            "fibs ok ${d.fibsOk} bad ${d.fibsBad}",
            "ensemble %04X ecc %02X label %s".format(d.eid ?: 0, d.ecc ?: 0, q(d.ensembleLabel ?: "")),
        )
        val rest = ArrayList<String>()
        d.services.forEach { (sid, name) -> rest += "service %04X %s".format(sid, q(name)) }
        d.components.forEach { (sid, cs) -> cs.forEach { c ->
            rest += "comp %04X ascty %d subch %d primary %d".format(sid, c.ascty, c.subchannel, if (c.primary) 1 else 0) } }
        d.subchannels.values.forEach { s ->
            rest += "subch ${s.id} start ${s.startCu} size ${s.sizeCu ?: "None"} bitrate ${s.bitrateKbps ?: "None"} prot ${s.protection}" }
        d.userApps.forEach { (sid, apps) -> apps.forEach { rest += "app %04X %s".format(sid, it) } }
        d.links.values.forEach { ls ->
            val ids = ls.ids.sortedWith(compareBy({ it.type }, { it.id })).joinToString(" ") { "${it.type}:%X".format(it.id) }
            rest += "link ils %d lsn %03X hard %d active %d oe %d ids %s".format(
                if (ls.international) 1 else 0, ls.lsn, if (ls.hard) 1 else 0, if (ls.active) 1 else 0, if (ls.otherEnsemble) 1 else 0, ids)
        }
        d.frequencyInfo.forEach { (k, fs) -> fs.forEach { f ->
            val fv = when (f) { is FiFrequency.Khz -> f.khz.toString(); is FiFrequency.Raw -> "raw:${f.hex}" }
            rest += "fi oe %d rm %d id %04X %s".format(if (k.otherEnsemble) 1 else 0, k.rm, k.id, fv) } }
        d.otherEnsembleServices.forEach { (sid, eids) -> eids.forEach { rest += "oe_service %04X eid %04X".format(sid, it) } }
        return head + rest.sorted()
    }

    @Test fun allFicCapturesMatchThePythonReference() {
        val files = Golden.raw(".*\\.fic")
        assumeTrue("no FIC captures in ${Golden.root}", files.isNotEmpty())
        for (f in files) {
            val d = FicDecoder()
            d.feed(f.readBytes())
            Golden.compare(f.name, Golden.expected(f), lines(d))
            println("  ${f.name}: ${d.services.size} services, ${d.fibsOk} FIBs OK")
        }
    }
}
