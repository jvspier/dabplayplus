#!/usr/bin/env python3
"""DTR-100 USB DAB+ dongle: MCU bridge primitives (see docs/protocol.md).

Only replays transfers found in the vendor libdab.so. Every primitive names its source
function. Replies are read with a full-packet buffer (64 bytes) so an unexpectedly long reply
shows up in the log instead of causing an overflow error.

usage: .venv/bin/python tools/dtr100.py check      # power on + interface check
       .venv/bin/python tools/dtr100.py scan [--channels 11C,12C] [--log captures/x.log]
       .venv/bin/python tools/dtr100.py play --channel 11C --service 538 [--seconds 10 --out x.wav]
       .venv/bin/python tools/dtr100.py fic [--channels 5B,12C] [--seconds 60]   # raw FIC to captures/raw
"""
import argparse
import time
import sys

import usb.core
import usb.util

VID, PID, MFR = 0x16C0, 0x05DC, "dabusb@126.com"
EP_OUT, EP_IN = 0x02, 0x82


class DTR100:
    def __init__(self, verbose=True, log=None):
        self.verbose = verbose
        self.log = log
        self.dev = None
        # driver state, mirrors libdab.so globals
        self.plan = None      # DAT_00657c04: active clock plan
        self.band_mode = 0    # DAT_00657c00: 1 = DAB band III lists loaded, 2 = L-band
        self.irq_mask = 0     # DAT_00657c51: shadow of page 7 reg 0x62 (bss, starts 0)
        self.fic_open = False # DAT_00657c10
        self.msc_seq = 0      # DAT_002025e0: rolling counter in cmd 0x42
        self.msc_size = 0     # DAT_002025e4 (0x41 threshold) / DAT_00657c38 (fw <= 1 read size)
        self.fw = None        # DAT_002025ec: MCU firmware level, selects the MSC code path
        self.subch = None     # active audio sub-channel
        self.t = None
        for dev in usb.core.find(find_all=True, idVendor=VID, idProduct=PID):
            # 16c0:05dc is a shared ID (USBasp etc.): insist on our manufacturer string
            if usb.util.get_string(dev, dev.iManufacturer) == MFR:
                self.dev = dev
                break
        if self.dev is None:
            sys.exit(f"no {VID:04x}:{PID:04x} device with manufacturer {MFR!r} found")
        usb.util.claim_interface(self.dev, 0)

    def close(self):
        usb.util.release_interface(self.dev, 0)
        usb.util.dispose_resources(self.dev)

    def _xfer(self, req, reply_len, timeout_out=1000, timeout_in=1000):
        req = bytes(req)
        self.dev.write(EP_OUT, req, timeout_out)
        rep = bytes(self.dev.read(EP_IN, max(reply_len, 64), timeout_in))
        note = "" if len(rep) == reply_len else f"   (expected {reply_len} bytes, got {len(rep)})"
        if self.log:
            r = rep.hex(" ") if len(rep) <= 64 else rep[:16].hex(" ") + f" ... ({len(rep)} bytes)"
            self.log.write(f"-> {req.hex(' ')}\n<- {r}{note}\n")
        if self.verbose:
            print(f"  -> {req.hex(' ')}")
            print(f"  <- {rep.hex(' ')}{note}")
        return rep

    @staticmethod
    def _expect(rep, code, what):
        if not rep or rep[0] != code:
            raise RuntimeError(f"{what}: expected reply code {code:#04x}, got {rep.hex(' ')}")

    # --- MCU commands -----------------------------------------------------------------

    def echo(self):  # FUN_00075200
        rep = self._xfer([0x00, 0, 0, 0], 4, 2000, 2000)
        return rep[1:4] == b"DAB"

    def version(self):  # FUN_00075360; byte 3 = firmware level (DAT_002025ec)
        rep = self._xfer([0x01, 0, 0, 0], 4)
        self._expect(rep, 0x81, "version")
        self.fw = rep[3]
        return rep[3]

    def power_on(self):  # FUN_00076840 (used by dab_api_init)
        rep = self._xfer([0x10, 0, 0, 1], 4, 2000, 4000)
        self._expect(rep, 0x90, "power on")

    # --- chip register access over SPI ---------------------------------------------------

    def wreg(self, reg, val):  # FUN_00076550: 21 00 00 02 reg val -> A1
        rep = self._xfer([0x21, 0, 0, 2, reg, val], 1, 1000, 4000)
        self._expect(rep, 0xA1, f"write reg {reg:#04x}")

    def rreg(self, reg, n=1):  # FUN_000764d0 / FUN_00076510 -> FUN_00075d00
        rep = self._xfer([0x22, 0, n & 0xFF, n >> 8, reg], n + 4, 1000, 4000)
        self._expect(rep, 0xA2, f"read reg {reg:#04x}")
        got = rep[2] | rep[3] << 8
        if got != n:
            raise RuntimeError(f"read reg {reg:#04x}: size mismatch, asked {n}, got {got}")
        return rep[4:4 + n]

    def page(self, p):
        self.wreg(0x03, p)

    def check_interface(self):  # FUN_000788a0 / FUN_00077b70
        self.page(7)
        self.wreg(0x7D, 0x06)
        return self.rreg(0x7D)[0] == 0x06

    def wlist(self, pairs):
        for reg, val in pairs:
            self.wreg(reg, val)

    # --- DAB chip driver (ported from libdab.so, DAB band III path only) ---------------

    def load_tables(self, path):
        import json
        self.t = json.load(open(path))

    PLAN_REGS = {  # FUN_00078b20: page 4 reg 0x6a, then page 6 regs, then 0x42
        0: (0x01, [(0x3C, 0x4B), (0x3D, 0x37), (0x3E, 0x89), (0x3F, 0x41)], (0x8F, 0xC2), 0xF5),
        1: (0x01, [(0x3C, 0x00), (0x3D, 0x00), (0x3E, 0x00), (0x3F, 0x40)], (0x00, 0x00), 0xF0),
        2: (0x21, [(0x3C, 0xB5), (0x3D, 0x14), (0x3E, 0x41), (0x3F, 0x3A)], (0x0D, 0x74), 0xDA),
        3: (0x31, [(0x3C, 0x69), (0x3D, 0x03), (0x3E, 0x9D), (0x3F, 0x36)], (0xCC, 0xCC), 0xCC),
    }

    def _clock_plan_regs(self, plan):  # common part of FUN_00078b20 / FUN_000789c0
        p = self.t["clock_plans"][plan]
        if p[0] == 0xFF:
            raise RuntimeError(f"clock plan {plan} invalid")
        self.page(0x0F)
        self.wlist([(0xE8, p[0] | 0x40), (0xE9, p[1] | 0x50), (0xEA, p[2]), (0xEB, p[3]),
                    (0xEC, p[4]), (0xED, p[5]), (0xEE, p[6])])

    def set_clock_plan(self, plan):  # FUN_00078b20(band 1, plan)
        if plan == self.plan:
            return
        self._clock_plan_regs(plan)
        for _ in range(10):
            if self.rreg(0x15)[0] & 1:
                break
            time.sleep(0.010)
        else:
            raise RuntimeError("clock plan not applied (reg 0x15 bit 0)")
        r6a, p6, (r40, r41), r42 = self.PLAN_REGS[plan]
        self.page(4)
        self.wreg(0x6A, r6a)
        self.page(6)
        self.wlist(p6 + [(0x54, 0x58), (0x40, r40), (0x41, r41), (0x42, r42), (0x43, 0x00)])
        self.plan = plan

    def system_init(self):  # FUN_00073220
        # FUN_000788a0(1, 0): interface check, FUN_00078920(1), FUN_000789c0(1, 0)
        if not self.check_interface():
            raise RuntimeError("check interface failed")
        self.page(0x0F)
        self.wlist([(0x54, 0x1C), (0x52, 0x07), (0x30, 0xF4), (0x2F, 0x71)])
        self._clock_plan_regs(0)
        for _ in range(10):
            if self.rreg(0x15)[0] & 1:
                break
            time.sleep(0.001)
        else:
            raise RuntimeError("clock plan 0 not applied")
        self.plan = 0

        self.page(6)
        self.wlist([(0x07, 0x08), (0x05, 0x17), (0x06, 0x10), (0x0A, 0x00)])
        self.page(4)
        self.wlist([(0x10, 0x91), (0xE1, 0x00), (0x35, 0x7B), (0x3B, 0x3C), (0x36, 0x68),
                    (0x3A, 0x44), (0x3C, 0x20), (0x3D, 0x0B), (0x3D, 0x09), (0xA6, 0x30),
                    (0xAA, 0x01), (0xAF, 0x07)])
        self.page(7)
        self.wlist([(0x10, 0x00), (0x13, 0x16), (0x14, 0x00), (0x19, 0x0A),
                    (0xF0, 0), (0xF1, 0), (0xF2, 0), (0xF3, 0), (0xF4, 0), (0xF5, 0), (0xF6, 0),
                    (0xF7, 0), (0xF8, 0), (0xFB, 0xFF)])
        self.page(6)
        self.wlist([(0x12, 0x04), (0x13, 0x72), (0x14, 0x63), (0x15, 0x64), (0x16, 0x6C),
                    (0x38, 0x01), (0x1A, 0xB4), (0x20, 0x5B), (0x25, 0x09), (0x44, 0x09),
                    (0x46, 0xA0), (0x47, 0x0F), (0x48, 0xB8), (0x49, 0x0B), (0x54, 0x58),
                    (0x55, 0x06), (0x56, 0x10), (0x59, 0x51), (0x5A, 0x1C), (0x6D, 0x00),
                    (0x8B, 0x34), (0x6A, 0x1C), (0x6B, 0x2D), (0x85, 0x32), (0x8D, 0x0C),
                    (0x8E, 0x01), (0x33, 0x02), (0x53, 0x06), (0x6F, 0x03), (0xBA, 0x08)])
        # clock plan 0 branch
        self.page(4)
        self.wreg(0x6A, 0x01)
        self.page(6)
        self.wlist([(0x3C, 0x4B), (0x3D, 0x37), (0x3E, 0x89), (0x3F, 0x41), (0x40, 0x8F),
                    (0x41, 0xC2), (0x42, 0xF5), (0x43, 0x00)])
        self.wlist([(0x94, 0x08), (0x98, 0x05), (0x99, 0x03), (0x9B, 0xCF), (0x9C, 0x10),
                    (0x9D, 0x1C), (0x9F, 0x32), (0xA0, 0x90), (0xA2, 0xA0), (0xA3, 0x08),
                    (0xA4, 0x01), (0xA8, 0xF6), (0xA9, 0x89), (0xAA, 0x0C), (0xAB, 0x32),
                    (0xAC, 0x14), (0xAD, 0x09), (0xAE, 0xFF), (0xEB, 0x6B), (0x93, 0x10),
                    (0x94, 0x29), (0xA2, 0x50), (0xA4, 0x02), (0xAF, 0x01)])
        self.page(9)
        self.wlist([(0x80, 0x80), (0x81, 0xFF), (0x87, 0x07), (0x45, 0xA1), (0xDD, 0xD0),
                    (0x39, 0x07), (0xE6, 0x10), (0xA5, 0xA0)])
        self.page(9)                                            # FUN_00078540
        self.wreg(0x46, 0x00)
        self.wlist([(0x09, 0x00), (0x0B, 0x00)])                # FUN_00077d90 (still page 9)
        self.page(7)
        self.wlist([(0x28, 0x01), (0x2A, 0x13)])
        self.page(7)
        self.wreg(0x29, 0x08)
        self.page(9)
        v = self.rreg(0x31)[0]
        self.wreg(0x31, v & 0xFC)
        self.page(7)                                            # FUN_00078830
        self.wlist([(0x77, 0x14), (0x04, 0x28), (0x0C, 0xF5)])
        self.rf_init_dab()                                      # FUN_00079600(1)
        time.sleep(0.100)
        self.page(0x0F)
        self.wreg(0x6B, 0xC5)

    def rf_init_dab(self):  # FUN_00079600(1)
        self.band_mode = 1
        self.page(0x0F)
        self.wlist(self.t["rf_init_dab_pairs"])
        self.wlist(self.t["rf_init_dab_tail"])
        hi, lo = self.rreg(0x10)[0], self.rreg(0x11)[0]
        if (hi << 8 | lo) == 0xFFFF:
            self.wlist([(0x2C, 200), (0x47, 0xB0), (0x35, 0x04), (0x2A, 0x07), (0x2D, 0xEC), (0x61, 0x2A)])
        else:
            self.wlist([(0x2C, 0x48), (0x47, 0xE0), (0x35, 0x04), (0x2A, 0x05), (0x2D, 0x8C), (0x61, 0x25)])

    @staticmethod
    def band3_index(f):  # FUN_00079080, band 1
        u = (f - 174928) // 1712
        if f < 224096:
            return u + 1 - (f < 210096) if f < 217008 else u + 2
        return u + 3

    def demod_restart(self):  # page 6 reg 0x10 0x48 -> 0xc9
        self.page(6)
        self.wreg(0x10, 0x48)
        time.sleep(0.001)
        self.wreg(0x10, 0xC9)

    def pll_band3(self, f):  # FUN_00079080(1, 0, f); returns 0 or -13
        if self.band_mode != 1:
            self.page(0x0F)
            self.wlist(self.t["rf_band3_pairs"])
            self.band_mode = 1
        i = self.band3_index(f)
        w, plan = self.t["band3_pll"][i], self.t["band3_plan"][i]
        # sanity check against our own index math before anything goes out (ratio ~341.39)
        if not 341.0 < w / f < 341.8:
            raise RuntimeError(f"PLL word {w:#x} implausible for {f} kHz (index {i})")
        self.set_clock_plan(plan)
        self.page(0x0F)
        self.wlist([(0x23, w >> 22 & 0xFF), (0x24, w >> 14 & 0xFF), (0x25, w >> 6 & 0xFF)])
        for r in (0x23, 0x24, 0x25):
            self.rreg(r)
        self.wreg(0x26, (w * 4 | 1) & 0xFF)
        time.sleep(0.020)
        self.wreg(0x20, 0x00)
        time.sleep(0.020)
        locked = False
        for _ in range(10):
            r15, r12, r13, r14 = (self.rreg(r)[0] for r in (0x15, 0x12, 0x13, 0x14))
            rb = r12 * 0x400000 + r13 * 0x4000 + r14 * 0x40 + (r15 >> 2 & 0x3F)
            if ((w ^ rb) & ~1) == 0 or w >> 1 == rb:
                if r15 & 2:
                    locked = True
                    break
                self.wreg(0x20, 0x00)
                time.sleep(0.020)
            else:
                self.wreg(0x20, 0x00)
                time.sleep(0.010)
        self.demod_restart()
        if not locked:
            return -13
        time.sleep(0.001)
        return 0

    MODE_1A = {0: 0xB4, 1: 0x44, 2: 0x04, 3: 0x84}  # page 6 reg 0x1a by reg 0x27 bits 4..5

    @staticmethod
    def _tune_thresholds(plan, mode, f):
        """Thresholds of FUN_00072400 by clock plan, transmission mode bits (page 6 reg 0x27) and
        frequency. Returns (u15, u17, lo, hi, u20, u22) or None (vendor returns -4)."""
        low = f < 0x3D091
        if plan in (0, 1):
            u22, u20 = 0x9B5 + (0xF5 if low else 0), 0x163 + (0x23 if low else 0)
            special = {2: (0x50, 0x140, 0x08, 0xF8), 3: (1000, 4000, 0x1A, 0xE6)}
        elif plan in (2, 3):
            u22, u20 = 0x9B5 + (0xAF if low else 0), 0x163 + (0x19 if low else 0)
            special = {2: (100, 400, 0x08, 0xF8), 3: (0x4E2, 5000, 0x1A, 0xE6)}
        else:
            return None
        if mode in special:
            u15, u17, lo, hi = special[mode]
        elif mode == 1:
            u15, u17, lo, hi = 0xED, 0x3B6, 0x0E, 0xF2
        else:
            u15, u17, lo, hi = 0x659, 0x1964, 0x37, 0xCE
        return u15, u17, lo, hi, u20, u22

    def tune(self, f, sync_timeout=1.5):
        """FUN_00072400, literal port (band III). Returns (rc, info); info['why'] names the
        check that rejected the channel. Only deviation: the sync poll is capped by time instead
        of the vendor's 9999 iterations."""
        info = {}

        def fail(why):
            info["why"] = why
            return -4, info

        rc = self.pll_band3(f)
        if rc:
            info["why"] = "pll"
            return rc, info
        self.page(4)
        self.wreg(0x55, 0x0D)
        self.page(6)
        self.wreg(0x54, {0: 0x7B, 1: 0x78, 3: 0x7A}.get(self.plan, 0x78))
        self.wreg(0x5A, 0x1B)
        self.page(0x0F)
        self.wreg(0x4B, 0x8F)
        self.demod_restart()

        t_sync = time.monotonic()
        deadline = t_sync + sync_timeout
        polls = 0
        hist = []
        while True:                                            # LAB_000725bb: sync poll
            polls += 1
            self.page(6)
            sync = self.rreg(0xCF)[0]
            if sync == 0xFF:
                return fail("sync reg 0xff")
            self.page(4)
            r38, r39 = self.rreg(0x38)[0], self.rreg(0x39)[0]
            self.page(6)
            v = self.rreg(0x53)[0]
            self.wreg(0x53, v | 0x80)
            r66, r67 = self.rreg(0x66)[0], self.rreg(0x67)[0]
            mode = (self.rreg(0x27)[0] & 0x30) >> 4
            self.wreg(0x1A, self.MODE_1A[mode])
            th = self._tune_thresholds(self.plan, mode, f)
            if th is None:
                return fail("clock plan")
            info["sync"], info["mode_bits"] = sync, mode
            info["polls"], info["t_sync"] = polls, round(time.monotonic() - t_sync, 3)
            if not hist or hist[-1][0] != sync:
                hist.append((sync, polls))
            info["sync_hist"] = hist
            if sync == 3:
                break
            if sync == 1 or time.monotonic() > deadline:
                self.page(6)
                self.wreg(0x5A, 0x1C)
                return fail("no sync" if sync == 1 else "sync timeout")
        u15, u17, lo, hi, u20, u22 = th
        u1 = r39 << 8 | r38                                    # page 4 regs 0x38/0x39
        u21 = r67 * 4 | (r66 & 3)                              # page 6 regs 0x66/0x67
        info["u1"], info["u21"] = u1, u21
        self.page(6)
        self.wreg(0x5A, 0x1C)
        self.rreg(0x18)
        self.page(0x0F)
        if self.rreg(0x00)[0] >> 6 == 2:
            u20 = u22
        if u21 * 10 < u20 and u21 != 0:
            return fail(f"u21 {u21} * 10 < {u20}")
        b23 = u1 != 0

        for i in range(16):                                    # loop A: signal level gate
            self.page(0x0F)
            rf0 = self.rreg(0x00)[0]
            u16 = u15 if (rf0 >> 6 == 2 and i > 10) else u17
            if u1 < u16 and b23:
                return fail(f"A: u1 {u1} < {u16}")
            weak = u1 < u17
            self.page(0x0F)
            self.rreg(0x00)
            self.page(6)
            self.rreg(0xB1); self.rreg(0xB5); self.rreg(0xB6)
            self.page(0x0F)
            rf0 = self.rreg(0x00)[0]
            if rf0 >> 6 != 2 and weak and i > 0x0D and b23:
                return fail("A: weak")
            if i == 15:
                break
            time.sleep(0.010)

        l1d = cnt37 = cnt30 = l1f = 0                          # loop B: settle + latch bits
        i = 0
        while True:
            time.sleep(0.010)
            self.page(6)
            r18 = self.rreg(0x18)[0]
            self.page(0x0F)
            rf0 = self.rreg(0x00)[0]
            if u1 < u17 and b23 and rf0 >> 6 != 2 and i > 0x19 and (r18 < lo or hi < r18):
                return fail(f"B: reg18 {r18} outside {lo}..{hi}")
            if u1 < u17 * 2 and b23 and rf0 >> 6 == 2 and i > 0x19 and (r18 < lo or hi < r18):
                return fail(f"B: reg18 {r18} outside {lo}..{hi} (rf0 mode 2)")
            self.page(6)
            r30 = self.rreg(0x30)[0] & 0x0F
            if (r30 > 9 and cnt30 > 4) or (r30 > 8 and cnt30 > 6):
                return fail(f"B: reg30 {r30}")
            if r30 > 7:
                if cnt30 > 8 and i > 0x0F:
                    return fail(f"B: reg30 {r30} persistent")
                cnt30 += 1
            self.page(6)
            r37 = self.rreg(0x37)[0] & 7
            check = True
            if u1 < 3000 and u21 < 0x226:
                check = r37 == 1
                if check:
                    cnt37 += 1
            if check and r37 == 1 and cnt37 > 0x14 and l1f < 2 and i > 0x1E:
                return fail("B: reg37 == 1 persistent")
            l1f = self.rreg(0x17)[0] & 0x1F
            r18b = self.rreg(0x18)[0]
            v = self.rreg(0x82)[0]
            self.wreg(0x82, v | 1)
            r7e, r7f = self.rreg(0x7E)[0], self.rreg(0x7F)[0]
            v = self.rreg(0x1C)[0]
            self.wreg(0x1C, v | 0x10)
            r26, r27 = self.rreg(0x26)[0], self.rreg(0x27)[0]
            v = self.rreg(0x44)[0]
            self.wreg(0x44, v | 0x80)
            r4c, r4d = self.rreg(0x4C)[0], self.rreg(0x4D)[0]
            if l1f == 1 and r37 == 6:
                if i > 0x0F and l1d > 1:
                    return fail("B: l1d")
                if ((r4c - 0x41) + (r4d & 1) * 0x100) & 0xFFFF > 0x69 or ((r27 & 0x0F) << 8 | r26) == 0:
                    l1d += 1
            elif l1f > 1 and (r18b < lo or hi < r18b) and ((r7f & 0x1F) << 8 | r7e) <= 0x8B:
                break                                          # cVar2 = 40 -> final loop
            i += 1
            if i == 0x4B:
                return fail("B: no settle in 75 rounds")
        info["loopB_rounds"] = i + 1

        for left in range(40, 0, -1):                          # LAB_00072ead
            time.sleep(0.010)
            self.page(6)
            r12 = self.rreg(0x12)[0]
            v = self.rreg(0x82)[0]
            self.wreg(0x82, v | 1)
            u15b = self.rreg(0x7E)[0] + (self.rreg(0x7F)[0] & 0x1F) * 0x100
            if u15b and r12 & 0x80:
                self.page(6)
                self.wreg(0x54, 0x58)
                self.page(0x0F)
                self.wreg(0x4B, 0x80)
                self.page(4)
                self.wreg(0x55, 0x15)
                break
            if left == 1:
                if not r12 & 0x80:
                    self.page(6)
                    self.rreg(0x18)
                    return fail("final: reg12 bit7 never set")
                break
        info["u15"] = u15b

        self.page(9)                                           # LAB_00072fad: quality verdict
        fb = self.rreg(0xFB)[0]
        r83, r84, r85, r86 = (self.rreg(r)[0] for r in (0x83, 0x84, 0x85, 0x86))
        if fb & 3 != 3:
            self.page(6)
            self.rreg(0x18)
            self.page(9)
            r83, r84, r85, r86 = (self.rreg(r)[0] for r in (0x83, 0x84, 0x85, 0x86))
        den = r83 << 8 | r84
        q = (r85 << 8 | r86) * 1000 // den if den else None
        info["q"] = q
        if q is None:
            return fail("quality: zero denominator")
        if fb & 3 == 3:
            if q < 0xC4 or u15b > 0xB3:
                return 0, info
        elif q < 0xC3 and u15b < 0xB4:
            return 0, info
        return fail(f"quality q={q} u15={u15b}")

    def fic_enable(self):  # FUN_000786d0, once after the first successful tune
        if self.fic_open:
            return
        self.fic_open = True
        self.page(9)
        self.wreg(0x35, 0x01)
        self.page(7)
        self.irq_mask &= 0xFE
        self.wreg(0x62, self.irq_mask)
        self.page(9)
        self.wreg(0x46, 0x14)
        self.wreg(0x46, 0x16)

    def start_audio(self, subch, n):
        """dab_api_set_subid(subch) + dab_api_set_msc_size(n), n = bytes per CIF = bitrate * 3.
        FUN_00069560 -> FUN_00072160 -> FUN_000712e0(subch, type 2, n) -> FUN_00077ea0,
        then FUN_000770e0(n) = MCU cmd 0x41 (firmware level >= 2). Assumes the channel is tuned
        and no sub-channel is active yet."""
        if not (0 <= subch <= 0x3F and 0 < n <= 0xD38):
            raise ValueError(f"bad subch {subch} / size {n}")
        self.demod_restart_nowait()                      # FUN_00072160, no active sub-channels
        self.page(9)                                     # FUN_000781e0: buffer threshold
        self.wlist([(0x56, n >> 8 & 0x0F), (0x57, n & 0xFF)])
        self.page(9)                                     # FUN_00078240: buffer reset
        self.wlist([(0x48, 0x00), (0x48, 0x05)])
        self.page(9)
        self.wreg(0x35, 0x70)
        self.page(7)
        self.irq_mask &= 0x8F
        self.wreg(0x62, self.irq_mask)
        self.page(9)                                     # FUN_000782a0(subch, 1, type == 1)
        self.wreg(0x3A, 0x80 | subch)
        self.page(9)                                     # FUN_00077ea0
        self.wreg(0x35, 0x7E)
        self.page(7)
        self.wreg(0x62, self.irq_mask)
        self.msc_size = n
        self.subch = subch
        if self.fw is None:
            raise RuntimeError("read the firmware level (version) before starting audio")
        if self.fw > 1:                                  # FUN_000770e0: MCU buffers MSC
            rep = self._xfer([0x41, 0, n & 0xFF, n >> 8], 4)
            self._expect(rep, 0xC1, "set msc size")
        if self.fw > 4:
            # dab_api_set_flag(1) -> FUN_00077240(1) -> cmd 0x43 (fw >= 5 only).
            # [hypothesis] the packed Java calls set_flag(1) after set_msc_size to start streaming.
            rep = self._xfer([0x43, 0, 0, 1], 4)
            self._expect(rep, 0xC3, "msc flag on")

    def stop_audio(self):
        """dab_api_set_flag(0) -> FUN_00069800(0): FUN_000775b0 (stop the sub-channel in the chip and
        flush the MCU buffer), then FUN_00077240(0)."""
        if self.subch is not None:                       # FUN_00071ca0 -> FUN_00071110 (audio slot)
            self.page(9)
            self.wreg(0x3A, self.subch)                  # FUN_000782a0(subch, 0, 0): disable
            self.page(7)
            self.irq_mask |= 0x70                        # no audio buffer active any more
            self.wreg(0x62, self.irq_mask)
            self.page(7)
            self.irq_mask |= 0x0E                        # no data buffers active either
            self.wreg(0x62, self.irq_mask)
            self.subch = None
        if self.fw is not None and self.fw > 1:          # FUN_000775b0: flush what the MCU still holds
            for _ in range(64):
                seq = self.msc_seq
                self.msc_seq = (seq + 1) & 0xFF
                st = self._xfer([0x42, 0, 0, seq], 6)
                avail = st[2] | st[3] << 8
                if avail == 0:
                    break
                m = min(avail, 0x480)
                self._expect(self._xfer([0x41, 0, m & 0xFF, m >> 8], 4), 0xC1, "flush size")
                self._xfer([0x40, 0, m & 0xFF, m >> 8], m + 4)
        self.msc_size = 0
        if self.fw is not None and self.fw > 4:          # FUN_00077240(0)
            self._expect(self._xfer([0x43, 0, 0, 0], 4), 0xC3, "msc flag off")
        elif self.fw is not None and self.fw > 1:
            self._expect(self._xfer([0x41, 0, 0, 0], 4), 0xC1, "stop msc")

    def demod_restart_nowait(self):
        self.page(6)
        self.wreg(0x10, 0x48)
        self.wreg(0x10, 0xC9)

    def msc_read(self, maxlen=4096):
        """Returns (data, status). fw >= 2: FUN_00077990 (cmd 0x42 status, then cmd 0x40 read).
        fw <= 1: FUN_00079e20 (poll page 9 reg 0x33, burst-read page 0x0C reg 0x10)."""
        if self.fw is not None and self.fw <= 1:
            self.page(9)
            st = self.rreg(0x33, 3)
            if not st[0] & 0x70:
                return b"", st
            if st[0] & 0x60:                             # overflow: reset the buffer (FUN_000781a0)
                self.wlist([(0x48, 0x00), (0x48, 0x05)])
                self.page(9)
                self.wreg(0x35, 0x04)
                return b"", st
            self.page(0x0C)
            data = self.rreg(0x10, self.msc_size)
            self.page(9)
            self.wreg(0x35, 0x04)
            return data, st
        seq = self.msc_seq
        self.msc_seq = (seq + 1) & 0xFF
        st = self._xfer([0x42, 0, 0, seq], 6)
        avail = st[2] | st[3] << 8
        if avail == 0:
            return b"", st
        m = min(avail, maxlen)
        if m < self.msc_size:
            return b"", st
        rep = self._xfer([0x40, 0, m & 0xFF, m >> 8], m + 4)
        self._expect(rep, 0xC0, "msc read")
        got = rep[2] | rep[3] << 8
        return rep[4:4 + got], st

    def quality(self):
        """FUN_000719d0 (dab_api_get_signal on fw <= 1): page 9 error/total bit counters.
        Returns the vendor's value (lower = better; 8000 = no measurement)."""
        self.page(9)
        if not self.rreg(0x37)[0] & 1:
            return 8000
        tot = int.from_bytes(self.rreg(0x88, 1) + self.rreg(0x89, 1) + self.rreg(0x8A, 1) + self.rreg(0x8B, 1), "big")
        err = int.from_bytes(self.rreg(0x8C, 1) + self.rreg(0x8D, 1) + self.rreg(0x8E, 1) + self.rreg(0x8F, 1), "big")
        return err * 1000 // tot * 10 if tot else 8000

    def fic_read(self):  # FUN_00079c20: 384 bytes or None
        self.page(9)
        st = self.rreg(0x33, 3)
        if not st[0] & 1:
            return None
        self.page(0x0A)
        data = self.rreg(0x10, 0x180)
        self.page(9)
        self.wreg(0x35, 0x01)
        return data


def service_list_complete(lab):
    """True once the ensemble label, all service labels and all sub-channels of the audio
    components are known."""
    comps, svcs, subch = lab.get("comps", {}), lab.get("services", {}), lab.get("subch", {})
    return ("ensemble" in lab and comps and all(sid in svcs for sid in comps)
            and all(c["subch"] in subch for cl in comps.values() for c in cl))


def read_service_list(d, max_seconds=3.0, quiet=0.3):
    """Read FIC until the service list is complete and nothing new arrived for `quiet` s."""
    import fic
    lab, ok, bad = {}, 0, 0
    t0 = time.monotonic()
    last_change, last_key = t0, None
    while time.monotonic() - t0 < max_seconds:
        data = d.fic_read()
        if data is None:
            time.sleep(0.005)
            continue
        for i in range(0, len(data), 32):
            fib = data[i:i + 32]
            if fic.fib_ok(fib):
                ok += 1
                fic.parse_fib(fib, lab)
            else:
                bad += 1
        key = (lab.get("ensemble"), len(lab.get("services", {})), len(lab.get("comps", {})),
               len(lab.get("subch", {})))
        if key != last_key:
            last_key, last_change = key, time.monotonic()
        if service_list_complete(lab) and time.monotonic() - last_change >= quiet:
            break
    lab.setdefault("services", {})
    return lab, ok, bad, time.monotonic() - t0


def scan(d, channels):
    results = []
    t_scan = time.monotonic()
    for name, f in channels:
        t_ch = time.monotonic()
        rc, info = d.tune(f)
        tries = 1
        # The signal metric u1 is ~65-350 on empty channels and >= ~4000 near a real multiplex, but
        # a weak multiplex next to a strong one (9C beside 9D here) sometimes fails acquisition
        # outright. So: channels that reached sync 3 get a second try; promising ones (u1 >= 1000)
        # up to three. 'No sync' channels (sync 1) are not retried.
        while rc and tries < (3 if (info.get("u1") or 0) >= 1000 else 2 if info.get("sync") == 3 else 1):
            rc, info = d.tune(f)
            tries += 1
        line = f"{name:>4} {f / 1000:8.3f} MHz  " + (f"[try {tries}] " if tries > 1 else "")
        if rc:
            print(line + f"no lock ({time.monotonic() - t_ch:.2f}s; sync {info.get('sync')}, "
                  f"{info.get('why')}, u1 {info.get('u1')})", flush=True)
            continue
        d.fic_enable()
        labels, ok, bad, t_fic = read_service_list(d)
        if ok == 0 and tries < 3:
            # locked but no valid FIC at all (seen on the first tune after a replug): retune once more
            rc, info = d.tune(f)
            tries += 1
            if rc == 0:
                labels, ok, bad, t_fic = read_service_list(d)
                line += f"[FIC retry] "
        pct = 100 * ok // (ok + bad) if ok + bad else 0
        ens = labels.get("ensemble", "?")
        print(line + f"LOCK ({time.monotonic() - t_ch:.2f}s, FIC {t_fic:.2f}s) q={info.get('q')} "
              f"u1={info.get('u1')}  FIBs ok {ok}/{ok + bad} ({pct}%)  ensemble: {ens!r}", flush=True)
        for sid, lab in sorted(labels["services"].items()):
            extra = ""
            for c in labels.get("comps", {}).get(sid, []):
                sc = labels.get("subch", {}).get(c["subch"], {})
                kind = {63: "DAB+", 0: "MP2"}.get(c["ascty"], f"ASCTy {c['ascty']}")
                extra += (f"  subch {c['subch']:2} {kind:4} {sc.get('bitrate') or '?':>3} kbit/s"
                          f" {sc.get('prot', '')}{'' if c['primary'] else ' (secondary)'}")
            print(f"{'':24}{sid:04X}  {lab:<16}{extra}")
        results.append((name, f, pct, labels))
    print(f"scan: {len(channels)} channels, {len(results)} ensembles, "
          f"{sum(len(r[3]['services']) for r in results)} services in {time.monotonic() - t_scan:.1f}s")
    return results


def record_fic(d, channels, seconds, outdir):
    """Save raw FIC (384-byte blocks = 12 FIBs) per channel for offline FIG analysis."""
    import os
    os.makedirs(outdir, exist_ok=True)
    for name, f in channels:
        for _ in range(3):
            rc, info = d.tune(f)
            if rc == 0:
                break
        if rc:
            print(f"{name}: no lock ({info.get('why')})")
            continue
        d.fic_enable()
        fn = os.path.join(outdir, f"{time.strftime('%Y-%m-%d')}-{name}.fic")
        n = 0
        with open(fn, "wb") as fh:
            end = time.monotonic() + seconds
            while time.monotonic() < end:
                data = d.fic_read()
                if data is None:
                    time.sleep(0.005)
                    continue
                fh.write(data)
                n += 1
        print(f"{name}: {n} FIC blocks ({n * 12} FIBs) -> {fn}", flush=True)


def play(d, chan, service, seconds, out, raw=None):
    import subprocess
    import wave

    import dabplus
    import fic
    f = dict(fic.BAND3)[chan]
    for attempt in range(1, 4):  # the lock check is marginal at times (see findings.md)
        rc, info = d.tune(f)
        if rc == 0:
            break
        print(f"{chan}: no lock on attempt {attempt} (rc {rc}, sync {info.get('sync')})", flush=True)
    else:
        sys.exit(f"{chan}: no lock")
    d.fic_enable()
    labels, sid, comp = {}, None, None

    def pick(exact):
        for s_, lab in labels.get("services", {}).items():
            hit = lab.lower() == service.lower() if exact else service.lower() in lab.lower()
            if hit and s_ in labels.get("comps", {}):
                c = next((c for c in labels["comps"][s_] if c["primary"]), labels["comps"][s_][0])
                if labels["subch"].get(c["subch"], {}).get("bitrate"):
                    return s_, c
        return None, None

    end = time.monotonic() + 8
    while time.monotonic() < end:  # collect FIC until the service and its sub-channel are known
        data = d.fic_read()
        if data is None:
            time.sleep(0.010)
            continue
        for i in range(0, len(data), 32):
            if fic.fib_ok(data[i:i + 32]):
                fic.parse_fib(data[i:i + 32], labels)
        sid, comp = pick(exact=True)  # exact label match wins ("538" vs "538 NONSTOP")
        if comp:
            break
    if not comp:
        sid, comp = pick(exact=False)
    if not comp:
        sys.exit(f"service {service!r} not found on {chan}; services: "
                 f"{sorted(labels.get('services', {}).values())}")
    sc = labels["subch"][comp["subch"]]
    kind = {63: "DAB+", 0: "DAB (MP2)"}.get(comp["ascty"], f"ASCTy {comp['ascty']}")
    print(f"{chan}: {labels['services'][sid]!r} SId {sid:04X}, subch {comp['subch']}, {kind}, "
          f"{sc['bitrate']} kbit/s, {sc['prot']}, start CU {sc['start']}, size {sc['size_cu']} CU")
    if comp["ascty"] != 63:
        sys.exit("only DAB+ is implemented so far")
    n = sc["bitrate"] * 3
    dec, faad = dabplus.SuperframeDecoder(sc["bitrate"]), dabplus.Faad()
    import pad
    t0_ = [None]

    def ts():
        return f"[{time.monotonic() - t0_[0]:6.1f}s]" if t0_[0] else "[   0.0s]"

    padd = pad.PadDecoder(
        on_dls=lambda lab: lab and print(f"{ts()} DLS: {lab}", flush=True),
        on_dlplus=lambda tags, running, tog: print(f"{ts()} DL+: {tags}", flush=True),
        on_slide=lambda tid, ct, cs, prm, body: print(
            f"{ts()} slide: {prm.get('ContentName', tid)} ({len(body)} bytes)", flush=True))
    rawf = None
    if raw:  # raw sub-channel bytes + a JSON sidecar, for offline decoding (tools/dabplus.py)
        import json
        rawf = open(raw, "wb")
        json.dump({"channel": chan, "service": labels["services"][sid], "sid": sid, "subch": comp["subch"],
                   "ascty": comp["ascty"], "bitrate": sc["bitrate"], "prot": sc["prot"]},
                  open(raw + ".json", "w"), indent=1)
    d.start_audio(comp["subch"], n)
    sink = wav = None
    t0 = last = time.monotonic()
    t0_[0] = t0
    got = pcm_bytes = 0
    try:
        while time.monotonic() - t0 < seconds:
            data, st = d.msc_read()
            if not data:
                time.sleep(0.010)
            got += len(data)
            if rawf:
                rawf.write(data)
            for asc, au in dec.feed(data):
                padd.feed_au(au)
                pcm = faad.decode(asc, au)
                if not pcm:
                    continue
                if sink is None and wav is None:
                    print(f"audio: {faad.rate} Hz, {faad.channels} ch, format {dec.fmt}", flush=True)
                    if out:
                        wav = wave.open(out, "wb")
                        wav.setnchannels(faad.channels); wav.setsampwidth(2); wav.setframerate(faad.rate)
                    else:
                        sink = subprocess.Popen(["pw-play", "--format", "s16", "--rate", str(faad.rate),
                                                 "--channels", str(faad.channels), "-"],
                                                stdin=subprocess.PIPE)
                pcm_bytes += len(pcm)
                (wav.writeframes if wav else sink.stdin.write)(pcm)
            if time.monotonic() - last > 30:
                last = time.monotonic()
                print(f"  {last - t0:5.1f}s  msc {got} B  {dec.stats}  faad errors {faad.errors}"
                      f"  status {st.hex(' ')}", flush=True)
    except KeyboardInterrupt:
        pass
    finally:
        d.stop_audio()
        if rawf:
            rawf.close()
        if wav:
            wav.close()
        if sink:
            sink.stdin.close()
            sink.wait()
    print(f"done: {got} MSC bytes, {pcm_bytes} PCM bytes, {dec.stats}, faad errors {faad.errors}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("step", choices=["check", "scan", "play", "fic"])
    ap.add_argument("--channel", default="11C", help="play: band III channel")
    ap.add_argument("--service", default="538", help="play: service label substring")
    ap.add_argument("--seconds", type=float, default=1e9, help="play: stop after this long")
    ap.add_argument("--out", help="play: write a WAV file instead of playing")
    ap.add_argument("--raw", help="play: also save the raw sub-channel bytes to this file")
    ap.add_argument("--channels", help="comma-separated, e.g. 11C,12C (default: all band III)")
    ap.add_argument("--tables", default="decompiled/3.26/tables.json")
    ap.add_argument("--log", help="write every transfer to this file")
    a = ap.parse_args()
    log = open(a.log, "w") if a.log else None
    d = DTR100(verbose=(a.step == "check" and not log), log=log)
    try:
        if a.step == "fic":
            import fic
            d.load_tables(a.tables)
            d.version()
            d.power_on()
            d.system_init()
            want = (a.channels or "5B,9C,9D,11C,12C").upper().split(",")
            record_fic(d, [c for c in fic.BAND3 if c[0] in want],
                       a.seconds if a.seconds < 1e8 else 60, "captures/raw")
        elif a.step == "play":
            d.load_tables(a.tables)
            print(f"firmware level {d.version()}")
            d.power_on()
            d.system_init()
            play(d, a.channel.upper(), a.service, a.seconds, a.out, a.raw)
        elif a.step == "scan":
            import fic
            chans = fic.BAND3
            if a.channels:
                want = a.channels.upper().split(",")
                chans = [c for c in fic.BAND3 if c[0] in want]
            d.load_tables(a.tables)
            print(f"firmware level {d.version()}")
            d.power_on()
            print("system init...", flush=True)
            d.system_init()
            print("scanning", flush=True)
            scan(d, chans)
        elif a.step == "check":
            print("echo:");      print("  ok" if d.echo() else "  MISMATCH")
            print("version:");   print(f"  firmware level {d.version()}")
            print("power on:");  d.power_on(); print("  ok")
            print("check interface (page 7, reg 0x7d = 6, read back):")
            print("  ok" if d.check_interface() else "  MISMATCH")
    finally:
        d.close()


if __name__ == "__main__":
    main()
