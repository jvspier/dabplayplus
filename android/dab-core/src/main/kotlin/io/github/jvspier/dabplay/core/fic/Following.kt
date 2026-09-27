package io.github.jvspier.dabplay.core.fic

/** Another ensemble that carries a service, and a frequency it's on. */
data class Alternative(val eid: Int, val khz: Int)

/**
 * DAB alternatives for a service, from the ensemble's own FIC: the other ensembles carrying it (FIG 0/24) with their
 * DAB frequencies (FIG 0/21, R&M 0 = DAB ensemble). FM entries (R&M 8/9) are ignored: this app doesn't do FM.
 * Seen on 5B (2026-09-24/27): RADIONL (SId 8421) is announced on 6 other regional ensembles, with frequencies.
 */
fun dabAlternatives(otherEnsembles: Map<Long, Set<Int>>, frequencies: Map<FiKey, Set<FiFrequency>>, sid: Long, ownEid: Int?) =
    otherEnsembles[sid].orEmpty().filter { it != ownEid }.flatMap { eid ->
        frequencies.filterKeys { it.rm == 0 && it.id == eid }.values.flatten()
            .filterIsInstance<FiFrequency.Khz>().map { Alternative(eid, it.khz) }
    }.distinct()
