#!/usr/bin/env python3
"""FM through the dongle: literal port of the (unused) FM functions in the vendor libdab.so.

The vendor apps never call these (dead code, likely from the chip vendor's reference driver), so this is
the first time they run. Only register sequences from the decompile plus the MCU commands already used for
DAB (0x21/0x22 register access, 0x41/0x42/0x40 buffered reads) are sent.

  FUN_00074aa0 fm_init(plan, bufsize)   FUN_00074920 fm_tune(khz)    FUN_00074470 rssi
  FUN_00074510 status                   FUN_00077f10 audio start     FUN_00077e50 audio stop

usage: .venv/bin/python tools/fm_test.py [--freqs 87900,88000] [--seconds 5] [--log x.log] [--raw out.bin]
"""
import argparse
import os
import sys
import time

sys.path.insert(0, os.path.dirname(__file__))
import dtr100  # noqa: E402


class FM(dtr100.DTR100):
    BUF = 0x800  # fm_init param_2 (<= 0x800): audio buffer threshold; the vendor never picked one (dead code)

    def fm_rf_init(self):  # FUN_00079600(4)
        self.page(0x0F)
        self.wlist(self.t["rf_init_fm_pairs"])
        self.wlist(self.t["rf_init_fm_tail"])
        hi, lo = self.rreg(0x10)[0], self.rreg(0x11)[0]
        if (hi << 8 | lo) == 0xFFFF:
            self.wlist([(0x2C, 200), (0x47, 0xB1), (0x35, 0x14), (0x2A, 0x07), (0x2D, 0xEC), (0x61, 0x2A)])
        else:
            self.wlist([(0x2C, 0x48), (0x47, 0xE1), (0x35, 0x14), (0x2A, 0x05), (0x2D, 0x8C), (0x61, 0x25)])

    def fm_init(self, plan=0, bufsize=BUF):  # FUN_00074aa0
        assert bufsize <= 0x800 and plan in (0, 1)
        self.fm_buf = bufsize
        # FUN_000788a0(4, plan): interface check, FUN_00078920(4), FUN_000789c0(4, plan)
        if not self.check_interface():
            raise RuntimeError("check interface failed")
        self.page(0x0F)
        self.wlist([(0x54, 0x1C), (0x52, 0x07), (0x30, 0xF4), (0x2F, 0x71)])
        self._clock_plan_regs(plan)
        for _ in range(10):
            if self.rreg(0x15)[0] & 1:
                break
            time.sleep(0.001)
        else:
            raise RuntimeError(f"FM clock plan {plan} not applied")
        self.plan = plan
        self.page(6)
        self.wlist([(0x07, 0x08), (0x05, 0x17), (0x06, 0x10), (0x0A, 0x00)])
        self.page(7)
        self.wlist([(0x10, 0x00), (0x13, 0x16), (0x14, 0x00), (0x19, 0x0A),
                    (0xF0, 0), (0xF1, 0), (0xF2, 0), (0xF3, 0), (0xF4, 0), (0xF5, 0), (0xF6, 0),
                    (0xF7, 0), (0xF8, 0), (0xFB, 0xFF)])
        self.page(4)
        self.wlist([(0x69, 0xB0), (0x6A, 0x0D)])
        self.page(7)
        self.wlist([(0x2B, 0x03), (0x30, 0x80)])
        if plan == 0:
            self.wreg(0xC8, 0x03)
        self.wreg(0x06, 0x04)
        self.page(6)
        self.wlist([(0x33, 0x02), (0x3F, 0x10), (0x44, 0x09), (0x45, 0x14), (0x46, 0x50), (0x47, 0x7A),
                    (0x48, 0xD3), (0x49, 0xF3), (0x6D, 0x88), (0xD2, 0x70), (0xD3, 0x6A), (0xD4, 0xBC),
                    (0xD5, 0x74), (0xD6, 0x13), (0xF8, 0x91), (0x54, 0x88), (0x55, 0x06), (0x56, 0x06),
                    (0x59, 0x51), (0x5A, 0x1C), (0x38, 0x00)])
        if plan == 0:
            self.wlist([(0x42, 0x00), (0x43, 0x10)])
        else:
            self.wlist([(0x42, 0xA0), (0x43, 0x0F)])
        self.wlist([(0x53, 0x06), (0x85, 0x3A), (0xBA, 0x08), (0xD2, 0x80), (0xE5, 0x07), (0xE7, 0x2B),
                    (0xE8, 0x01), (0xE9, 0x01), (0xF5, 0x60), (0xF9, 0x20), (0xD7, 0x4F), (0xF0, 0x41),
                    (0xF1, 0xE4), (0xF3, 0x01), (0xF4, 0x20), (0xE5, 0x07), (0xF6, 0xA7), (0xED, 0x01)])
        self.page(9)
        self.wreg(0x7D, 0x05)
        self.wlist([(0x09, 0x00), (0x0B, 0x00)])                # FUN_00077d90 (page 9)
        self.page(7)
        self.wlist([(0x28, 0x01), (0x2A, 0x13)])
        self.page(7)
        self.wreg(0x29, 0x08)
        self.page(9)
        self.wlist([(0x45, 0xA0), (0x46, 0x00)])
        self.page(9)                                            # FUN_000781e0(0xbc)
        self.wlist([(0x56, 0x00), (0x57, 0xBC)])
        self.page(9)                                            # FUN_00078240(4)
        self.wlist([(0x48, 0x00), (0x48, 0x05)])
        self.page(7)                                            # FUN_00078830
        self.wlist([(0x77, 0x14), (0x04, 0x28), (0x0C, 0xF5)])
        self.page(6)
        self.wreg(0x10, 0x48)
        time.sleep(0.010)
        self.wreg(0x10, 0xC9)
        time.sleep(0.100)
        self.fm_rf_init()

    def fm_pll(self, khz):  # FUN_00079080(4, 0, khz); returns 0 or -13
        w = self.t["fm_pll"][(khz - 0x128AE) // 0x32]
        if not 684.0 < w / khz < 689.0:
            raise RuntimeError(f"FM PLL word {w:#x} implausible for {khz} kHz")
        # FUN_00078b20(4, plan): the plan is unchanged, so it's a no-op
        self.page(0x0F)
        self.wlist([(0x23, w >> 22 & 0xFF), (0x24, w >> 14 & 0xFF), (0x25, w >> 6 & 0xFF), (0x26, w * 4 & 0xFF)])
        time.sleep(0.001)
        self.wreg(0x20, 0x00)
        time.sleep(0.002)
        locked = False
        for _ in range(10):
            r15, r12, r13, r14 = (self.rreg(r)[0] for r in (0x15, 0x12, 0x13, 0x14))
            rb = r12 * 0x400000 + r13 * 0x4000 + r14 * 0x40 + (r15 >> 2 & 0x3F)
            if (((w ^ rb) & ~1) == 0 or w >> 1 == rb) and r15 & 2:
                locked = True
                break
            self.wreg(0x20, 0x00)
            time.sleep(0.002)
        self.demod_restart_nowait()                             # LAB_000794d0
        if not locked:
            return -13
        time.sleep(0.001)
        return 0

    def fm_tune(self, khz):  # FUN_00074920; returns (rc, stereo)
        assert 76000 <= khz <= 108000 and khz % 50 == 0
        self.page(6)
        self.wlist([(0xF1, 0xE4), (0xF4, 0x20), (0xED, 0x01)])
        rc = self.fm_pll(khz)
        time.sleep(0.200)
        self.page(6)
        stereo = self.rreg(0xEF)[0] & 1
        if not stereo:
            self.wlist([(0xF0, 0x43), (0xF1, 0xFC), (0xF4, 0x30)])
        else:
            self.wlist([(0xF0, 0x41), (0xF1, 0xEC), (0xF4, 0x20)])
        return rc, stereo

    def fm_rssi(self):  # FUN_00074470 (vendor units, higher = stronger)
        self.page(0x0F)
        r0, r4, r2 = self.rreg(0x00)[0], self.rreg(0x04)[0], self.rreg(0x02)[0]
        v = ((r0 & 0x30) >> 4) * -0x78 + (r0 & 0x0F) * -0x1E + ((r2 & 0x1E) >> 1) * -0x1E + (r4 & 0x7F) * -4
        return v + (300 if (r0 & 0xC0) == 0x40 else 200)

    def fm_status(self):  # FUN_00074510: (flags: bit0 = reg 0xEF bit 0, bit1 = reg 0xDB bit 0, quality 0xEE)
        self.page(6)
        flags = (self.rreg(0xEF)[0] & 1) | (2 if self.rreg(0xDB)[0] & 1 else 0)
        return flags, self.rreg(0xEE)[0]

    def fm_audio_start(self):  # FUN_00074900 -> FUN_00077f10, then the MCU read size like DAB (fw >= 2)
        self.page(9)
        self.wreg(0x35, 0x70)
        self.page(7)
        self.irq_mask = 0x8F
        self.wreg(0x62, 0x8F)
        self.page(9)
        self.wlist([(0x56, self.fm_buf >> 8 & 0x0F), (0x57, self.fm_buf & 0xFF)])
        self.page(9)
        self.wlist([(0x48, 0x00), (0x48, 0x05)])
        self.msc_size = self.fm_buf
        if self.fw > 1:
            self._expect(self._xfer([0x41, 0, self.fm_buf & 0xFF, self.fm_buf >> 8], 4), 0xC1, "set buffer size")

    def fm_audio_stop(self):  # FUN_000748d0 -> FUN_00077e50(4), plus the MCU stop used for DAB
        self.page(7)
        self.wreg(0x62, self.irq_mask | 0x7E)
        if self.fw > 1:
            self._xfer([0x41, 0, 0, 0], 4)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--freqs", default="87900,88000,92600,94400,96800,98900,104300,107100",
                    help="kHz; the strongest one is used")
    ap.add_argument("--seconds", type=float, default=5)
    ap.add_argument("--log")
    ap.add_argument("--raw", default="captures/2026-09-25-fm.bin")
    a = ap.parse_args()
    d = FM(verbose=False, log=open(a.log, "w") if a.log else None)
    d.load_tables("decompiled/3.26/tables.json")
    try:
        print(f"firmware level {d.version()}")
        d.power_on()
        t = time.monotonic()
        d.fm_init()
        print(f"FM init ok ({time.monotonic() - t:.2f}s)")
        res = []
        for f in (int(x) for x in a.freqs.split(",")):
            rc, st = d.fm_tune(f)
            rssi = d.fm_rssi()
            flags, q = d.fm_status()
            print(f"  {f / 1000:6.1f} MHz: pll rc {rc}, stereo-flag {st}, rssi {rssi}, status flags {flags:02b}, q {q}")
            res.append((rssi, f))
        best = max(res)[1]
        rc, st = d.fm_tune(best)
        print(f"tuned {best / 1000:.1f} MHz (rc {rc}, stereo-flag {st}); starting audio buffer")
        d.fm_audio_start()
        got = bytearray()
        statuses = set()
        end = time.monotonic() + a.seconds
        t0 = time.monotonic()
        while time.monotonic() < end:
            data, stt = d.msc_read()
            statuses.add(stt.hex(" "))
            if data:
                got += data
            else:
                time.sleep(0.005)
        dt = time.monotonic() - t0
        d.fm_audio_stop()
        open(a.raw, "wb").write(got)
        print(f"received {len(got)} bytes in {dt:.1f}s = {len(got) / dt:.0f} B/s -> {a.raw}")
        print(f"distinct 0x42 status replies: {sorted(statuses)[:6]}")
    finally:
        d.close()


if __name__ == "__main__":
    main()
