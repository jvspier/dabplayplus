"""DAB+ (ETSI TS 102 563) audio superframe decoding, and AAC decoding via faad2 (ctypes).

Input: the raw sub-channel byte stream (what the dongle returns for MSC reads).
Output: 16-bit PCM. AUs are 960-sample AAC frames; ffmpeg's native decoder can't do SBR with
960-sample frames, so faad2 is used (as the vendor lib and welle.io do).
"""
import ctypes

from reedsolo import ReedSolomonError, RSCodec

from fic import crc16_ccitt

RS = RSCodec(10, nsize=255, fcr=0, prim=0x11D, generator=2, c_exp=8)  # RS(120,110) shortened


def firecode(data):
    """Fire code CRC over 9 bytes, g(x) = x^16+x^14+x^13+x^12+x^11+x^5+x^3+x^2+x+1."""
    crc = 0
    for b in data:
        crc ^= b << 8
        for _ in range(8):
            crc = ((crc << 1) ^ 0x782F) if crc & 0x8000 else (crc << 1)
            crc &= 0xFFFF
    return crc


class BitWriter:
    def __init__(self):
        self.bits = []

    def put(self, val, n):
        self.bits += [(val >> (n - 1 - i)) & 1 for i in range(n)]

    def bytes(self):
        b = self.bits + [0] * (-len(self.bits) % 8)
        return bytes(int("".join(map(str, b[i:i + 8])), 2) for i in range(0, len(b), 8))


SR_INDEX = {48000: 3, 32000: 5, 24000: 6, 16000: 8}


class SuperframeDecoder:
    def __init__(self, bitrate):
        self.s = bitrate // 8          # RS columns
        self.sf_len = 120 * self.s     # superframe = 5 logical frames of 24*s bytes
        self.buf = bytearray()
        self.synced = False
        self.stats = {"superframes": 0, "rs_fixed": 0, "rs_fail": 0, "au_ok": 0, "au_bad": 0,
                      "resync": 0}
        self.fmt = None

    def feed(self, data):
        """Feed raw sub-channel bytes; yields (asc, au) for every CRC-valid access unit."""
        self.buf += data
        while True:
            if not self.synced:
                # search byte-wise for a superframe start (firecode must match)
                for off in range(0, max(0, len(self.buf) - 11)):
                    if firecode(self.buf[off + 2:off + 11]) == (self.buf[off] << 8 | self.buf[off + 1]):
                        if off:
                            self.stats["resync"] += 1
                        del self.buf[:off]
                        self.synced = True
                        break
                else:
                    del self.buf[:max(0, len(self.buf) - 11)]
                    return
            if len(self.buf) < self.sf_len:
                return
            sf = bytearray(self.buf[:self.sf_len])
            if firecode(sf[2:11]) != (sf[0] << 8 | sf[1]):
                # try RS first: the firecode itself may be hit by errors
                self._rs(sf)
                if firecode(sf[2:11]) != (sf[0] << 8 | sf[1]):
                    self.synced = False
                    del self.buf[:1]
                    continue
            else:
                self._rs(sf)
            del self.buf[:self.sf_len]
            self.stats["superframes"] += 1
            yield from self._aus(sf)

    def _rs(self, sf):
        s = self.s
        for col in range(s):
            cw = bytearray(sf[col::s])  # 120 bytes, stride s
            if RS.check(cw)[0]:
                continue
            try:
                _, fixed, _ = RS.decode(cw)
                sf[col::s] = fixed
                self.stats["rs_fixed"] += 1
            except ReedSolomonError:
                self.stats["rs_fail"] += 1

    def _aus(self, sf):
        f = sf[2]
        dac, sbr, stereo, ps = f >> 6 & 1, f >> 5 & 1, f >> 4 & 1, f >> 3 & 1
        n_au = {(0, 0): 4, (0, 1): 2, (1, 0): 6, (1, 1): 3}[(dac, sbr)]
        # AU start addresses: (n_au - 1) x 12 bit from byte 3
        bits = int.from_bytes(sf[3:3 + 8], "big")
        total = 8 * 8
        starts = [{2: 5, 3: 6, 4: 8, 6: 11}[n_au]]
        for k in range(n_au - 1):
            starts.append(bits >> (total - 12 * (k + 1)) & 0xFFF)
        starts.append(110 * self.s)
        out_rate = 48000 if dac else 32000
        core = out_rate // 2 if sbr else out_rate
        self.fmt = {"rate": out_rate, "core": core, "sbr": bool(sbr), "ps": bool(ps),
                    "stereo": bool(stereo), "aus": n_au}
        asc = self._asc(core, out_rate, sbr, ps, stereo)
        for k in range(n_au):
            a, b = starts[k], starts[k + 1]
            if not a < b <= 110 * self.s or b - a < 3:
                self.stats["au_bad"] += 1
                continue
            au = bytes(sf[a:b - 2])
            if (crc16_ccitt(au) ^ 0xFFFF) != (sf[b - 2] << 8 | sf[b - 1]):
                self.stats["au_bad"] += 1
                continue
            self.stats["au_ok"] += 1
            yield asc, au

    @staticmethod
    def _asc(core, out_rate, sbr, ps, stereo):
        """AudioSpecificConfig as welle.io/dablin build it for faad2: AAC-LC core, 960-sample
        frames, backward-compatible SBR/PS sync extensions."""
        w = BitWriter()
        w.put(2, 5)                       # AOT AAC LC
        w.put(SR_INDEX[core], 4)
        w.put(2 if stereo and not ps else 1, 4)
        w.put(1, 1)                       # frameLengthFlag: 960 samples
        w.put(0, 1)                       # dependsOnCoreCoder
        w.put(0, 1)                       # extensionFlag
        if sbr:
            w.put(0x2B7, 11)              # syncExtensionType
            w.put(5, 5)                   # extension AOT SBR
            w.put(1, 1)                   # sbrPresentFlag
            w.put(SR_INDEX[out_rate], 4)
            if ps:
                w.put(0x548, 11)
                w.put(1, 1)               # psPresentFlag
        return w.bytes()


class _FrameInfo(ctypes.Structure):  # NeAACDecFrameInfo (faad2), leading fields + slack
    _fields_ = [("bytesconsumed", ctypes.c_ulong), ("samples", ctypes.c_ulong),
                ("channels", ctypes.c_ubyte), ("error", ctypes.c_ubyte),
                ("samplerate", ctypes.c_ulong), ("sbr", ctypes.c_ubyte),
                ("object_type", ctypes.c_ubyte), ("header_type", ctypes.c_ubyte),
                ("_rest", ctypes.c_ubyte * 256)]


class Faad:
    """Minimal faad2 wrapper: (re)initialise on ASC change, decode one raw AU to PCM s16le."""

    def __init__(self):
        self.lib = ctypes.CDLL("libfaad.so.2")
        self.lib.NeAACDecOpen.restype = ctypes.c_void_p
        self.lib.NeAACDecInit2.argtypes = [ctypes.c_void_p, ctypes.c_char_p, ctypes.c_ulong,
                                           ctypes.POINTER(ctypes.c_ulong), ctypes.POINTER(ctypes.c_ubyte)]
        self.lib.NeAACDecInit2.restype = ctypes.c_byte
        self.lib.NeAACDecDecode.argtypes = [ctypes.c_void_p, ctypes.POINTER(_FrameInfo),
                                            ctypes.c_char_p, ctypes.c_ulong]
        self.lib.NeAACDecDecode.restype = ctypes.c_void_p
        self.lib.NeAACDecClose.argtypes = [ctypes.c_void_p]
        self.lib.NeAACDecGetErrorMessage.argtypes = [ctypes.c_ubyte]
        self.lib.NeAACDecGetErrorMessage.restype = ctypes.c_char_p
        self.h, self.asc = None, None
        self.rate = self.channels = None
        self.errors = 0

    def decode(self, asc, au):
        if asc != self.asc:
            if self.h:
                self.lib.NeAACDecClose(self.h)
            self.h = self.lib.NeAACDecOpen()
            rate, ch = ctypes.c_ulong(), ctypes.c_ubyte()
            if self.lib.NeAACDecInit2(self.h, asc, len(asc), ctypes.byref(rate), ctypes.byref(ch)) < 0:
                raise RuntimeError(f"NeAACDecInit2 failed for ASC {asc.hex()}")
            self.asc = asc
        info = _FrameInfo()
        pcm = self.lib.NeAACDecDecode(self.h, ctypes.byref(info), au, len(au))
        if info.error:
            self.errors += 1
            self.last_error = self.lib.NeAACDecGetErrorMessage(info.error).decode()
            return b""
        self.rate, self.channels = info.samplerate, info.channels
        return ctypes.string_at(pcm, info.samples * 2) if pcm and info.samples else b""
