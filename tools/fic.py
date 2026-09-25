"""DAB FIC parsing: FIB CRC check; FIG 0/0 (EId), 0/1 (sub-channels), 0/2 (services), 0/6 (service
linking), 0/9 (ECC), 0/13 (user applications), 0/21 (frequency information), 0/24 (other-ensemble
services), 1/0 (ensemble label), 1/1 (service labels).

Reference: ETSI EN 300 401 (FIB = 30 bytes of FIGs + CRC-16 CCITT, stored inverted).
"""


def crc16_ccitt(data, crc=0xFFFF):
    for b in data:
        crc ^= b << 8
        for _ in range(8):
            crc = ((crc << 1) ^ 0x1021) if crc & 0x8000 else (crc << 1)
            crc &= 0xFFFF
    return crc


def fib_ok(fib):
    return (crc16_ccitt(fib[:30]) ^ 0xFFFF) == (fib[30] << 8 | fib[31])


def _label(b):
    # EBU Latin charset: close enough to latin-1 for station names
    return bytes(b).decode("latin-1").rstrip()


EEP_A = (12, 8, 6, 4)    # size (CU) per 8 kbit/s, protection 1-A..4-A
EEP_B = (27, 21, 18, 15)  # size (CU) per 32 kbit/s, protection 1-B..4-B


def _fig0_1(body, out):
    """Sub-channel organisation: subch id -> {start, size_cu, bitrate}. Long form (EEP) only;
    short form (UEP, DAB/MP2) is recorded without a bitrate."""
    i = 1
    while i + 3 <= len(body):
        subch = body[i] >> 2
        start = (body[i] & 3) << 8 | body[i + 1]
        if body[i + 2] & 0x80:  # long form
            if i + 4 > len(body):
                break
            opt, lvl = body[i + 2] >> 4 & 7, body[i + 2] >> 2 & 3
            size = (body[i + 2] & 3) << 8 | body[i + 3]
            if opt == 0:
                br = size // EEP_A[lvl] * 8
            elif opt == 1:
                br = size // EEP_B[lvl] * 32
            else:
                br = None
            out["subch"][subch] = {"start": start, "size_cu": size, "bitrate": br,
                                   "prot": f"EEP {lvl + 1}-{'AB'[opt] if opt < 2 else '?'}"}
            i += 4
        else:
            out["subch"].setdefault(subch, {"start": start, "size_cu": None, "bitrate": None,
                                            "prot": f"UEP #{body[i + 2] & 0x3F}"})
            i += 3


def _fig0_2(body, out):
    """Basic service organisation: SId -> list of (ASCTy, subch, primary) for stream audio."""
    pd = body[0] >> 5 & 1
    i = 1
    while i < len(body):
        sid_len = 4 if pd else 2
        if i + sid_len + 1 > len(body):
            break
        sid = int.from_bytes(body[i:i + sid_len], "big")
        ncomp = body[i + sid_len] & 0x0F
        i += sid_len + 1
        comps = []
        for _ in range(ncomp):
            if i + 2 > len(body):
                break
            tmid = body[i] >> 6
            if tmid == 0:  # MSC stream audio
                comps.append({"ascty": body[i] & 0x3F, "subch": body[i + 1] >> 2,
                              "primary": bool(body[i + 1] & 2)})
            i += 2
        if comps:
            out["comps"][sid] = comps


USER_APPS = {0x002: "SLS", 0x003: "BWS", 0x004: "TPEG", 0x005: "DGPS", 0x006: "TMC", 0x007: "SPI",
             0x008: "DAB-Java", 0x009: "DMB", 0x00A: "IPDC", 0x00B: "Voice", 0x00C: "Middleware",
             0x00D: "Filecasting", 0x44A: "Journaline"}


def _sid(body, i, pd):
    n = 4 if pd else 2
    return int.from_bytes(body[i:i + n], "big"), i + n


def _fig0_0(body, out):
    if len(body) >= 3:
        out["eid"] = body[1] << 8 | body[2]


def _fig0_6(body, out):
    """Service linking: linkage sets {(ILS, LSN): {"hard", "active", "ids": [(IdLQ, id)]}}."""
    cn, oe, pd = body[0] >> 7, body[0] >> 6 & 1, body[0] >> 5 & 1
    i = 1
    while i + 2 <= len(body):
        idlf, la, sh, ils = body[i] >> 7, body[i] >> 6 & 1, body[i] >> 5 & 1, body[i] >> 4 & 1
        lsn = (body[i] & 0x0F) << 8 | body[i + 1]
        i += 2
        ls = out["links"].setdefault((ils, lsn), {"hard": bool(sh), "active": bool(la), "ids": set(),
                                                    "oe": bool(oe)})
        ls["active"], ls["hard"] = bool(la), bool(sh)
        if not idlf:
            continue
        if i >= len(body):
            break
        idlq, nid = body[i] >> 5 & 3, body[i] & 0x0F
        i += 1
        for _ in range(nid):
            if pd:
                w = 4
            elif ils:
                w = 3                                   # ECC + 16-bit Id
            else:
                w = 2
            if i + w > len(body):
                break
            ls["ids"].add(({0: "DAB", 1: "RDS-PI", 3: "DRM/AMSS"}.get(idlq, f"IdLQ{idlq}"),
                           int.from_bytes(body[i:i + w], "big")))
            i += w


def _fig0_9(body, out):
    if len(body) >= 3:
        out["ecc"] = body[2]


def _fig0_13(body, out):
    """User applications: SId -> set of app names (SLS, SPI, ...)."""
    pd = body[0] >> 5 & 1
    i = 1
    while i < len(body):
        sid, i = _sid(body, i, pd)
        if i >= len(body):
            break
        napps = body[i] & 0x0F
        i += 1
        for _ in range(napps):
            if i + 2 > len(body):
                return
            uatype, ln = body[i] << 3 | body[i + 1] >> 5, body[i + 1] & 0x1F
            out["apps"].setdefault(sid, set()).add(USER_APPS.get(uatype, f"UA{uatype:#x}"))
            i += 2 + ln


def _fig0_21(body, out):
    """Frequency information: {(oe, R&M, Id): set of freqs}. DAB freqs in kHz, FM in kHz too."""
    oe = body[0] >> 6 & 1
    i = 1
    while i + 2 <= len(body):
        n = body[i + 1] & 0x1F                          # length of this FI list
        lst, i = body[i + 2:i + 2 + n], i + 2 + n
        j = 0
        while j + 3 <= len(lst):
            fid = lst[j] << 8 | lst[j + 1]
            rm, flen = lst[j + 2] >> 4, lst[j + 2] & 0x07
            freqs = lst[j + 3:j + 3 + flen]
            j += 3 + flen
            key = (oe, rm, fid)
            fs = out["fi"].setdefault(key, set())
            if rm == 0:                                  # DAB ensemble: 5-bit control + 19-bit freq
                for k in range(0, len(freqs) - 2, 3):
                    fs.add(((freqs[k] & 0x07) << 16 | freqs[k + 1] << 8 | freqs[k + 2]) * 16)
            elif rm in (8, 9):                           # FM with (8) / without (9) RDS: 87.5 + n*0.1 MHz
                for code in freqs:
                    if 1 <= code <= 204:
                        fs.add(87500 + code * 100)
            else:
                fs.add(("raw", bytes(freqs).hex()))


def _fig0_24(body, out):
    """Other-ensemble services: SId -> set of EIds carrying it."""
    pd = body[0] >> 5 & 1
    i = 1
    while i < len(body):
        sid, i = _sid(body, i, pd)
        if i >= len(body):
            break
        n = body[i] & 0x0F
        i += 1
        for _ in range(n):
            if i + 2 > len(body):
                return
            out["oe_services"].setdefault(sid, set()).add(body[i] << 8 | body[i + 1])
            i += 2


FIG0_HANDLERS = {0: _fig0_0, 6: _fig0_6, 9: _fig0_9, 13: _fig0_13, 21: _fig0_21, 24: _fig0_24}


def parse_fib(fib, out):
    """Collect info from one CRC-valid FIB into
    out = {'ensemble': .., 'services': {sid: label}, 'subch': {}, 'comps': {}}."""
    out.setdefault("services", {})
    out.setdefault("subch", {})
    out.setdefault("comps", {})
    for k in ("links", "apps", "fi", "oe_services"):
        out.setdefault(k, {})
    i = 0
    while i < 30:
        hdr = fib[i]
        if hdr == 0xFF:  # end marker / padding
            break
        ftype, flen = hdr >> 5, hdr & 0x1F
        body = fib[i + 1:i + 1 + flen]
        i += 1 + flen
        if ftype == 0 and len(body) >= 1:
            ext = body[0] & 0x1F
            if ext == 1:
                _fig0_1(body, out)
            elif ext == 2:
                _fig0_2(body, out)
            elif ext in FIG0_HANDLERS:
                FIG0_HANDLERS[ext](body, out)
            continue
        if ftype != 1 or len(body) < 1:
            continue
        ext = body[0] & 0x07
        if ext == 0 and len(body) >= 1 + 2 + 16:
            out["eid"] = body[1] << 8 | body[2]
            out["ensemble"] = _label(body[3:19])
        elif ext == 1 and len(body) >= 1 + 2 + 16:
            out["services"][body[1] << 8 | body[2]] = _label(body[3:19])


# Band III channel raster (ETSI EN 300 401 table), kHz
BAND3 = [
    ("5A", 174928), ("5B", 176640), ("5C", 178352), ("5D", 180064),
    ("6A", 181936), ("6B", 183648), ("6C", 185360), ("6D", 187072),
    ("7A", 188928), ("7B", 190640), ("7C", 192352), ("7D", 194064),
    ("8A", 195936), ("8B", 197648), ("8C", 199360), ("8D", 201072),
    ("9A", 202928), ("9B", 204640), ("9C", 206352), ("9D", 208064),
    ("10A", 209936), ("10N", 210096), ("10B", 211648), ("10C", 213360), ("10D", 215072),
    ("11A", 216928), ("11N", 217088), ("11B", 218640), ("11C", 220352), ("11D", 222064),
    ("12A", 223936), ("12N", 224096), ("12B", 225648), ("12C", 227360), ("12D", 229072),
    ("13A", 230784), ("13B", 232496), ("13C", 234208), ("13D", 235776), ("13E", 237488),
    ("13F", 239200),
]
