#!/usr/bin/env python3
"""Extract the DAB chip register/PLL tables from the vendor libdab.so (x86 build).

Addresses are Ghidra addresses (image base 0x10000) as seen in decompiled/<ver>/libdab-x86.c.
Output is JSON on stdout. The output is vendor data: keep it under decompiled/ (gitignored).

usage: extract_tables.py path/to/lib/x86/libdab.so > decompiled/3.26/tables.json
"""
import json
import struct
import sys

GHIDRA_BASE = 0x10000

d = open(sys.argv[1], "rb").read()
shoff, = struct.unpack_from("<I", d, 0x20)
shentsize, shnum = struct.unpack_from("<HH", d, 0x2e)
secs = [struct.unpack_from("<IIIIIIIIII", d, shoff + i * shentsize) for i in range(shnum)]


def off(gaddr):
    va = gaddr - GHIDRA_BASE
    for s in secs:
        # sh_type 8 = NOBITS (.bss): no file contents
        if s[3] and s[3] <= va < s[3] + s[5] and s[1] != 8:
            return s[4] + va - s[3]
    raise ValueError(f"address {gaddr:#x} not in a file-backed section")


def u8(gaddr, n):
    o = off(gaddr)
    return list(d[o:o + n])


def u32(gaddr, n):
    return list(struct.unpack_from(f"<{n}I", d, off(gaddr)))


def pairs(gaddr, n):
    b = u8(gaddr, n * 2)
    return [[b[i], b[i + 1]] for i in range(0, len(b), 2)]


t = {
    # FUN_00079600(1): page 0x0F register list for DAB mode, then 2 more pairs
    "rf_init_dab_pairs": pairs(0x00100480, 99),
    "rf_init_dab_tail": pairs(0x000E7EA4, 2),
    # FUN_00079600(4): same for FM
    "rf_init_fm_pairs": pairs(0x00100580, 78),
    "rf_init_fm_tail": pairs(0x000E7D40, 2),
    # FUN_00079080: band-specific page 0x0F lists, written once when the band changes
    "rf_band3_pairs": pairs(0x00100440, (0x0010046C - 0x00100440) // 2),
    "rf_lband_pairs": pairs(0x00100400, (0x0010042C - 0x00100400) // 2),
    # FUN_00078b20 / FUN_000789c0: clock plans, 7 bytes each -> page 0x0F regs 0xE8..0xEE
    # (0xE8 |= 0x40, 0xE9 |= 0x50). 0xFF in byte 0 marks an invalid plan.
    "clock_plans": [u8(0x000E7EA8 + 7 * i, 7) for i in range(4)],
    # FUN_00079080 band 1 (Band III): per-channel PLL word and clock-plan index
    "band3_pll": u32(0x000E7F80, 48),
    "band3_plan": u32(0x000E7E00, 48),
    # band 2 (L-band): index (f_khz - 1452960) / 1712
    "lband_pll": u32(0x000E7F00, 32),
    "lband_plan": u32(0x000E7D80, 32),
}
json.dump(t, sys.stdout, indent=1)
