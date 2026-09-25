"""DAB PAD decoding: X-PAD data subfields -> DLS (dynamic label), DL Plus tags, MOT slideshow.

References: ETSI EN 300 401 (PAD/X-PAD, DLS), ETSI TS 102 980 (DL Plus), ETSI EN 301 234 (MOT),
ETSI TS 101 499 (slideshow). For DAB+ the PAD sits in a data_stream_element at the start of each
AU; the X-PAD bytes are stored in reverse order, followed by 2 F-PAD bytes.
"""
from fic import crc16_ccitt

XPAD_LEN = (4, 6, 8, 12, 16, 24, 32, 48)

DLP_TYPES = {
    1: "ITEM.TITLE", 2: "ITEM.ALBUM", 3: "ITEM.TRACKNUMBER", 4: "ITEM.ARTIST", 5: "ITEM.COMPOSITION",
    6: "ITEM.MOVEMENT", 7: "ITEM.CONDUCTOR", 8: "ITEM.COMPOSER", 9: "ITEM.BAND", 10: "ITEM.COMMENT",
    11: "ITEM.GENRE", 12: "INFO.NEWS", 13: "INFO.NEWS.LOCAL", 14: "INFO.STOCKMARKET", 15: "INFO.SPORT",
    16: "INFO.LOTTERY", 17: "INFO.HOROSCOPE", 18: "INFO.DAILY_DIVERSION", 19: "INFO.HEALTH",
    20: "INFO.EVENT", 21: "INFO.SCENE", 22: "INFO.CINEMA", 23: "INFO.STUPIDITY.MACHINE",
    24: "INFO.DATE_TIME", 25: "INFO.WEATHER", 26: "INFO.TRAFFIC", 27: "INFO.ALARM",
    28: "INFO.ADVERTISEMENT", 29: "INFO.URL", 30: "INFO.OTHER", 31: "STATIONNAME.SHORT",
    32: "STATIONNAME.LONG", 33: "PROGRAMME.NOW", 34: "PROGRAMME.NEXT", 35: "PROGRAMME.PART",
    36: "PROGRAMME.HOST", 37: "PROGRAMME.EDITORIAL_STAFF", 38: "PROGRAMME.FREQUENCY",
    39: "PROGRAMME.HOMEPAGE", 40: "PROGRAMME.SUBCHANNEL", 41: "PHONE.HOTLINE", 42: "PHONE.STUDIO",
    43: "PHONE.OTHER", 44: "SMS.STUDIO", 45: "SMS.OTHER", 46: "EMAIL.HOTLINE", 47: "EMAIL.STUDIO",
    48: "EMAIL.OTHER", 49: "MMS.OTHER", 50: "CHAT", 51: "CHAT.CENTER", 52: "VOTE.QUESTION",
    53: "VOTE.CENTRE", 59: "PRIVATE.1", 60: "PRIVATE.2", 61: "PRIVATE.3", 62: "DESCRIPTOR.PLACE",
    63: "DESCRIPTOR.APPOINTMENT", 64: "DESCRIPTOR.IDENTIFIER", 65: "DESCRIPTOR.PURCHASE",
    66: "DESCRIPTOR.GET_DATA",
}

# EBU Latin (ETSI TS 101 756 table 1), the part that differs from ASCII/latin-1 and matters for
# West-European text. [partial, verify against the spec if odd characters show up]
EBU_LATIN = {
    0x80: "á", 0x81: "à", 0x82: "é", 0x83: "è", 0x84: "í", 0x85: "ì", 0x86: "ó", 0x87: "ò",
    0x88: "ú", 0x89: "ù", 0x8A: "Ñ", 0x8B: "Ç", 0x8C: "Ş", 0x8D: "ß", 0x8E: "¡", 0x8F: "Ĳ",
    0x90: "â", 0x91: "ä", 0x92: "ê", 0x93: "ë", 0x94: "î", 0x95: "ï", 0x96: "ô", 0x97: "ö",
    0x98: "û", 0x99: "ü", 0x9A: "ñ", 0x9B: "ç", 0x9C: "ş", 0x9D: "ğ", 0x9E: "ı", 0x9F: "ĳ",
    0xC0: "Á", 0xC1: "À", 0xC2: "É", 0xC3: "È", 0xC4: "Í", 0xC5: "Ì", 0xC6: "Ó", 0xC7: "Ò",
    0xC8: "Ú", 0xC9: "Ù", 0xCA: "Ř", 0xCB: "Č", 0xCC: "Š", 0xCD: "Ž", 0xCE: "Đ", 0xCF: "Ŀ",
    0xD0: "Â", 0xD1: "Ä", 0xD2: "Ê", 0xD3: "Ë", 0xD4: "Î", 0xD5: "Ï", 0xD6: "Ô", 0xD7: "Ö",
    0xD8: "Û", 0xD9: "Ü", 0xDA: "ř", 0xDB: "č", 0xDC: "š", 0xDD: "ž", 0xDE: "đ", 0xDF: "ŀ",
}


def decode_text(b, charset):
    if charset == 15:
        return b.decode("utf-8", "replace")
    if charset == 6:
        return b.decode("utf-16-be", "replace")
    return "".join(EBU_LATIN.get(c, chr(c)) for c in b)


def crc_ok(dg):
    return len(dg) >= 2 and (crc16_ccitt(dg[:-2]) ^ 0xFFFF) == (dg[-2] << 8 | dg[-1])


class DataGroupAssembler:
    """Reassembles one data group from X-PAD 'start' and 'continuation' subfields."""

    def __init__(self):
        self.buf = None
        self.need = None

    def start(self, data, need):
        self.buf, self.need = bytearray(data), need
        return self._done()

    def cont(self, data):
        if self.buf is None:
            return None
        self.buf += data
        return self._done()

    def _done(self):
        if self.need is not None and len(self.buf) >= self.need:
            dg, self.buf = bytes(self.buf[:self.need]), None
            return dg
        return None


class PadDecoder:
    def __init__(self, on_dls=None, on_dlplus=None, on_slide=None):
        self.on_dls, self.on_dlplus, self.on_slide = on_dls, on_dlplus, on_slide
        self.last_app = None       # app type of the last subfield (for CI-less continuation)
        self.dl_asm = DataGroupAssembler()
        self.mot_asm = DataGroupAssembler()
        self.mot_len = None        # from the data group length indicator (app type 1)
        self.dl_toggle = None
        self.dl_segs = {}
        self.dl_charset = 0
        self.dl_label = None
        self.dl_label_toggle = None
        self.mot = {}              # transport id -> {"hdr": {seg: bytes}, "body": {...}, "last": {...}}
        self.stats = {"pad": 0, "dl_dg": 0, "dl_crc_bad": 0, "mot_dg": 0, "mot_crc_bad": 0,
                      "slides": 0}

    # --- PAD / X-PAD ---------------------------------------------------------------------

    def feed_au(self, au):
        """Extract PAD from a DAB+ AU (data_stream_element first) and process it."""
        if not au or au[0] >> 5 != 4:
            return
        n, st = au[1], 2
        if n == 255:
            n, st = 255 + au[2], 3
        pad = au[st:st + n]
        if len(pad) >= 2:
            self.feed_pad(pad[:-2], pad[-2:])

    def feed_pad(self, xpad_rev, fpad):
        self.stats["pad"] += 1
        xpad_ind = fpad[0] >> 4 & 3
        ci_flag = fpad[1] >> 1 & 1
        xpad = bytes(reversed(xpad_rev))
        if xpad_ind == 0 or not xpad:
            return
        subfields = []
        if ci_flag:
            i = 0
            cis = []
            if xpad_ind == 1:          # short X-PAD: 1 CI byte + 3 data bytes
                cis.append((xpad[0] & 0x1F, 3))
                i = 1
            else:                      # variable size: up to 4 CIs, app type 0 ends the list
                while i < len(xpad) and len(cis) < 4:
                    app, ln = xpad[i] & 0x1F, XPAD_LEN[xpad[i] >> 5]
                    i += 1
                    if app == 0:
                        break
                    cis.append((app, ln))
            for app, ln in cis:
                subfields.append((app, xpad[i:i + ln]))
                i += ln
        elif self.last_app is not None:
            subfields.append((self._cont_type(self.last_app), xpad))
        for app, data in subfields:
            self._subfield(app, data)
            self.last_app = app

    @staticmethod
    def _cont_type(app):
        # a CI-less X-PAD continues the previous application; 'start' types continue as 'cont'
        return {2: 3, 12: 13, 14: 15}.get(app, app)

    def _subfield(self, app, data):
        if app == 1 and len(data) >= 2:                     # data group length indicator
            self.mot_len = (data[0] & 0x3F) << 8 | data[1]
        elif app == 2 and len(data) >= 2:                   # DLS, start of data group
            b0 = data[0]
            if b0 & 0x10:                                    # command
                cmd = b0 & 0x0F
                need = 4 if cmd == 1 else 2 + (data[1] & 0x0F) + 1 + 2
            else:
                need = 2 + (b0 & 0x0F) + 1 + 2
            dg = self.dl_asm.start(data, need)
            if dg:
                self._dl_group(dg)
        elif app == 3:                                       # DLS continuation
            dg = self.dl_asm.cont(data)
            if dg:
                self._dl_group(dg)
        elif app == 12 and self.mot_len:                     # MOT, start of data group
            dg = self.mot_asm.start(data, self.mot_len)
            if dg:
                self._mot_group(dg)
        elif app == 13:                                      # MOT continuation
            dg = self.mot_asm.cont(data)
            if dg:
                self._mot_group(dg)

    # --- DLS / DL Plus ---------------------------------------------------------------------

    def _dl_group(self, dg):
        self.stats["dl_dg"] += 1
        if not crc_ok(dg):
            self.stats["dl_crc_bad"] += 1
            return
        b0, b1 = dg[0], dg[1]
        toggle, first, last, cmd = b0 >> 7, b0 >> 6 & 1, b0 >> 5 & 1, b0 >> 4 & 1
        if cmd:
            c = b0 & 0x0F
            if c == 1:                                        # remove label
                self.dl_label = None
                if self.on_dls:
                    self.on_dls("")
            elif c == 2:                                      # DL Plus command
                self._dlplus(toggle, dg[2:-2])
            return
        if toggle != self.dl_toggle:
            self.dl_toggle, self.dl_segs = toggle, {}
        seg = 0 if first else b1 >> 4 & 7
        if first:
            self.dl_charset = b1 >> 4
        self.dl_segs[seg] = (bytes(dg[2:-2]), last)
        lasts = [k for k, (_, l) in self.dl_segs.items() if l]
        if lasts and all(k in self.dl_segs for k in range(lasts[0] + 1)):
            raw = b"".join(self.dl_segs[k][0] for k in range(lasts[0] + 1))
            label = decode_text(raw, self.dl_charset)
            if label != self.dl_label:
                self.dl_label, self.dl_label_toggle = label, toggle
                if self.on_dls:
                    self.on_dls(label)
                self.dl_raw = raw

    def _dlplus(self, toggle, cmd):
        if not cmd or cmd[0] >> 4 != 0:                      # CId 0 = DL Plus tags
            return
        item_toggle, item_running, nt = cmd[0] >> 3 & 1, cmd[0] >> 2 & 1, (cmd[0] & 3) + 1
        if self.dl_label is None or toggle != self.dl_label_toggle:
            return                                            # tags refer to a label we don't have
        tags = {}
        text = self.dl_label
        for k in range(nt):
            t = cmd[1 + 3 * k:4 + 3 * k]
            if len(t) < 3:
                break
            ctype, start, ln = t[0] & 0x7F, t[1] & 0x7F, (t[2] & 0x7F) + 1
            if ctype == 0:                                    # DUMMY
                continue
            tags[DLP_TYPES.get(ctype, f"TYPE{ctype}")] = text[start:start + ln]
        if self.on_dlplus:
            self.on_dlplus(tags, bool(item_running), item_toggle)

    # --- MOT slideshow ---------------------------------------------------------------------

    def _mot_group(self, dg):
        self.stats["mot_dg"] += 1
        ext, crcf, segf, uaf, dgtype = dg[0] >> 7, dg[0] >> 6 & 1, dg[0] >> 5 & 1, dg[0] >> 4 & 1, dg[0] & 0x0F
        if crcf and not crc_ok(dg):
            self.stats["mot_crc_bad"] += 1
            return
        i = 2 + (2 if ext else 0)
        last = segno = 0
        if segf:
            last, segno = dg[i] >> 7, (dg[i] & 0x7F) << 8 | dg[i + 1]
            i += 2
        tid = None
        if uaf:
            li = dg[i] & 0x0F
            if dg[i] & 0x10 and li >= 2:
                tid = dg[i + 1] << 8 | dg[i + 2]
            i += 1 + li
        end = len(dg) - (2 if crcf else 0)
        if tid is None or dgtype not in (3, 4) or i + 2 > end:
            return
        seg = dg[i + 2:end]                                   # skip segmentation header
        o = self.mot.setdefault(tid, {3: {}, 4: {}, "last": {}})
        o[dgtype][segno] = bytes(seg)
        if last:
            o["last"][dgtype] = segno
        self._mot_try(tid, o)

    def _mot_try(self, tid, o):
        for t in (3, 4):
            if t not in o["last"] or any(k not in o[t] for k in range(o["last"][t] + 1)):
                return
        hdr = b"".join(o[3][k] for k in range(o["last"][3] + 1))
        body = b"".join(o[4][k] for k in range(o["last"][4] + 1))
        if len(hdr) < 7:
            return
        body_size = hdr[0] << 20 | hdr[1] << 12 | hdr[2] << 4 | hdr[3] >> 4
        ctype, csub = hdr[5] >> 1 & 0x3F, (hdr[5] & 1) << 8 | hdr[6]
        params = self._mot_params(hdr[7:])
        del self.mot[tid]
        if len(body) != body_size:
            return
        self.stats["slides"] += 1
        if self.on_slide:
            self.on_slide(tid, ctype, csub, params, body)

    @staticmethod
    def _mot_params(b):
        out, i = {}, 0
        while i < len(b):
            pli, pid = b[i] >> 6, b[i] & 0x3F
            i += 1
            if pli == 0:
                ln = 0
            elif pli == 1:
                ln = 1
            elif pli == 2:
                ln = 4
            else:
                if i >= len(b):
                    break
                if b[i] & 0x80:
                    ln = (b[i] & 0x7F) << 8 | b[i + 1]
                    i += 2
                else:
                    ln = b[i] & 0x7F
                    i += 1
            val = b[i:i + ln]
            i += ln
            if pid == 0x0C and val:                            # ContentName: charset byte + name
                out["ContentName"] = decode_text(val[1:], val[0] >> 4)
            elif pid == 0x05:
                out["TriggerTime"] = val.hex()
            elif pid == 0x25 and len(val) >= 2:
                out["CategoryID/SlideID"] = (val[0], val[1])
            elif pid == 0x27:
                out["ClickThroughURL"] = val.decode("latin-1", "replace")
            elif pid == 0x28:
                out["AlternativeLocationURL"] = val.decode("latin-1", "replace")
            else:
                out[f"param_{pid:#04x}"] = val.hex()
        return out
