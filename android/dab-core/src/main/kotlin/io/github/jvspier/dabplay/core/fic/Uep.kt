package io.github.jvspier.dabplay.core.fic

/**
 * EN 300 401 Table 8: Unequal Error Protection profiles, by the 6-bit table index of FIG 0/1's short form. UEP is what
 * DAB (MP2) sub-channels mostly use; the sub-channel's bitrate is only known through this table. Verified entry by
 * entry against the copy in the vendor's libdab.so (records of size, level, bitrate at 0xd7180).
 */
object Uep {
    /** Sub-channel size in capacity units. */
    val SIZE_CU = intArrayOf(
        16, 21, 24, 29, 35, 24, 29, 35, 42, 52, 29, 35, 42, 52, 32, 42, 48, 58, 70, 40, 52, 58, 70, 84, 48, 58, 70, 84, 104, 58, 70, 84,
        104, 64, 84, 96, 116, 140, 80, 104, 116, 140, 168, 96, 116, 140, 168, 208, 116, 140, 168, 208, 232, 128, 168, 192, 232, 280, 160, 208, 280, 192, 280, 416)
    /** Protection level (1 = strongest). */
    val LEVEL = intArrayOf(
        5, 4, 3, 2, 1, 5, 4, 3, 2, 1, 5, 4, 3, 2, 5, 4, 3, 2, 1, 5, 4, 3, 2, 1, 5, 4, 3, 2, 1, 5, 4, 3,
        2, 5, 4, 3, 2, 1, 5, 4, 3, 2, 1, 5, 4, 3, 2, 1, 5, 4, 3, 2, 1, 5, 4, 3, 2, 1, 5, 4, 2, 5, 3, 1)
    /** Bitrate in kbit/s. */
    val BITRATE = intArrayOf(
        32, 32, 32, 32, 32, 48, 48, 48, 48, 48, 56, 56, 56, 56, 64, 64, 64, 64, 64, 80, 80, 80, 80, 80, 96, 96, 96, 96, 96, 112, 112, 112,
        112, 128, 128, 128, 128, 128, 160, 160, 160, 160, 160, 192, 192, 192, 192, 192, 224, 224, 224, 224, 224, 256, 256, 256, 256, 256, 320, 320, 320, 384, 384, 384)
}
