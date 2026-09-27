package io.github.jvspier.dabplay.data

/** A DAB service is identified by ECC + SId (e.g. E3.83C7 = 538), stable across ensembles. */
data class ServiceKey(val ecc: Int, val sid: Long) {
    override fun toString() = "%02X.%04X".format(ecc, sid)
}
