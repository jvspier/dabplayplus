#!/usr/bin/env python3
"""First contact with the DTR-100: MCU echo + version only. Does not touch the DAB chip.

Both commands are taken verbatim from libdab.so (see docs/protocol.md):
  echo    -> 00 00 00 00   reply bytes 1..3 must be 'D' 'A' 'B'  (FUN_00075200)
  version -> 01 00 00 00   reply 4 bytes, byte 3 = firmware level (FUN_00075360)

usage: .venv/bin/python tools/probe.py
"""
import sys

import usb.core
import usb.util

VID, PID, MFR = 0x16C0, 0x05DC, "dabusb@126.com"
EP_OUT, EP_IN = 0x02, 0x82


def find():
    for dev in usb.core.find(find_all=True, idVendor=VID, idProduct=PID):
        # 16c0:05dc is a shared ID (USBasp etc.): insist on our manufacturer string
        if usb.util.get_string(dev, dev.iManufacturer) == MFR:
            return dev
    sys.exit(f"no {VID:04x}:{PID:04x} device with manufacturer {MFR!r} found")


def xfer(dev, req, reply_len, timeout=1000):
    print(f"  -> {bytes(req).hex(' ')}")
    dev.write(EP_OUT, bytes(req), timeout)
    rep = bytes(dev.read(EP_IN, reply_len, timeout))
    print(f"  <- {rep.hex(' ')}")
    return rep


def main():
    dev = find()
    print(f"found bus {dev.bus} addr {dev.address}")
    usb.util.claim_interface(dev, 0)
    try:
        print("echo:")
        r = xfer(dev, [0x00, 0x00, 0x00, 0x00], 4, 2000)
        ok = r[1:4] == b"DAB"
        print(f"  echo {'OK' if ok else 'MISMATCH'} (bytes 1..3 = {r[1:4]!r})")

        print("version:")
        r = xfer(dev, [0x01, 0x00, 0x00, 0x00], 4)
        nib = lambda b: f"{b >> 4}.{b & 0xF}"
        print(f"  bytes 1..3 as nibbles: {nib(r[1])}  {nib(r[2])}  {nib(r[3])}"
              f"   firmware level (byte 3) = {r[3]}")
    finally:
        usb.util.release_interface(dev, 0)
        usb.util.dispose_resources(dev)


if __name__ == "__main__":
    main()
