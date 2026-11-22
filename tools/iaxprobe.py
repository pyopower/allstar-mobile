#!/usr/bin/env python3
"""Minimal IAX2 client probe for AllStarLink (iaxRpt-style "radio-less" login).

Receive-only by default: connects, logs every frame, ACKs, answers PING/LAGRQ.
Usage: iaxprobe.py host port user secret node [seconds]
"""
import hashlib, socket, struct, sys, time

IE = {1: 'CALLED_NUMBER', 2: 'CALLING_NUMBER', 4: 'CALLING_NAME', 6: 'USERNAME',
      8: 'CAPABILITY', 9: 'FORMAT', 11: 'VERSION', 14: 'AUTHMETHODS', 15: 'CHALLENGE',
      16: 'MD5_RESULT', 22: 'CAUSE', 42: 'CAUSECODE', 54: 'CALLTOKEN', 55: 'CAPABILITY2', 56: 'FORMAT2'}
FT = {1: 'DTMF', 2: 'VOICE', 4: 'CONTROL', 5: 'NULL', 6: 'IAX', 7: 'TEXT', 12: 'DTMF_BEGIN'}
IAXS = {1: 'NEW', 2: 'PING', 3: 'PONG', 4: 'ACK', 5: 'HANGUP', 6: 'REJECT', 7: 'ACCEPT',
        8: 'AUTHREQ', 9: 'AUTHREP', 10: 'INVAL', 11: 'LAGRQ', 12: 'LAGRP', 18: 'VNAK', 40: 'CALLTOKEN'}
ULAW = 4


def ie(t, v):
    if isinstance(v, int):
        v = struct.pack('>H', v) if t == 11 else struct.pack('>I', v)
    elif isinstance(v, str):
        v = v.encode()
    return bytes([t, len(v)]) + v


def parse_ies(b):
    out, i = {}, 0
    while i + 2 <= len(b):
        t, l = b[i], b[i + 1]
        out[t] = b[i + 2:i + 2 + l]
        i += 2 + l
    return out


class Call:
    def __init__(self, host, port, user, secret, node):
        self.addr = (socket.gethostbyname(host), port)
        self.user, self.secret, self.node = user, secret, node
        self.s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.s.settimeout(0.2)
        self.scall = 0x1234 & 0x7fff
        self.dcall = 0
        self.oseq = self.iseq = 0
        self.t0 = time.monotonic()
        self.stats = {'voice_bytes': 0, 'mini': 0}

    def ts(self):
        return int((time.monotonic() - self.t0) * 1000) & 0xffffffff

    def send_full(self, ftype, sub, data=b'', ts=None, ack=False):
        ts = self.ts() if ts is None else ts
        hdr = struct.pack('>HHIBBBB', 0x8000 | self.scall, self.dcall, ts,
                          self.oseq, self.iseq, ftype, sub)
        self.s.sendto(hdr + data, self.addr)
        if not ack:
            self.oseq = (self.oseq + 1) & 0xff
        print(f'>> {FT.get(ftype, ftype)}/{IAXS.get(sub, sub) if ftype == 6 else sub} oseq={self.oseq} iseq={self.iseq}')

    def ack(self, ts):
        self.send_full(6, 4, ts=ts, ack=True)

    def new(self, token=b''):
        data = (ie(11, 2) + ie(1, self.node) + ie(2, '0') + ie(4, 'AllStarMobile') +
                ie(6, self.user) + ie(8, ULAW) + ie(9, ULAW) + bytes([54, len(token)]) + token)
        self.send_full(6, 1, data)

    def run(self, secs):
        self.new()
        end = time.monotonic() + secs
        while time.monotonic() < end:
            try:
                pkt, _ = self.s.recvfrom(4096)
            except socket.timeout:
                continue
            if not pkt[0] & 0x80:  # mini frame
                self.stats['mini'] += 1
                self.stats['voice_bytes'] += len(pkt) - 4
                if self.stats['mini'] % 50 == 1:
                    print(f'<< mini voice #{self.stats["mini"]} len={len(pkt) - 4}')
                continue
            src, dst, ts, oseq, iseq, ftype, sub = struct.unpack('>HHIBBBB', pkt[:12])
            body = pkt[12:]
            src &= 0x7fff
            name = IAXS.get(sub, sub) if ftype == 6 else sub
            print(f'<< {FT.get(ftype, ftype)}/{name} src={src} oseq={oseq} iseq={iseq} ts={ts} len={len(body)}')
            if ftype == 6 and sub == 4:
                continue  # ACK
            if ftype == 6 and sub == 40:  # CALLTOKEN -> resend NEW with token
                tok = parse_ies(body).get(54, b'')
                self.oseq = self.iseq = 0
                self.new(tok)
                continue
            if self.dcall == 0:
                self.dcall = src
            if oseq == self.iseq:
                self.iseq = (self.iseq + 1) & 0xff
            if ftype == 6:
                ies = parse_ies(body)
                printable = {IE.get(k, k): (v if k not in (8, 9) else int.from_bytes(v, 'big')) for k, v in ies.items()}
                print('   IEs', printable)
                if sub == 8:  # AUTHREQ
                    self.ack(ts)
                    md5 = hashlib.md5(ies[15] + self.secret.encode()).hexdigest()
                    self.send_full(6, 9, ie(16, md5))
                elif sub == 2:  # PING
                    self.send_full(6, 3, ts=ts)
                elif sub == 11:  # LAGRQ
                    self.send_full(6, 12, ts=ts)
                elif sub in (5, 6):
                    print('   call ended:', ies.get(22))
                    return
                else:
                    self.ack(ts)
            else:
                if ftype == 4 and sub == 4 and DTMF:
                    self.ack(ts)
                    for i, ch in enumerate(DTMF):
                        if os.environ.get('MODE') == 'text':
                            self.send_full(7, 0, f'D {self.node} 0 {i + 1} {ch}'.encode() + b'\0')
                        else:
                            self.send_full(12, ord(ch)); time.sleep(0.08); self.send_full(1, ord(ch))
                        time.sleep(0.12)
                    continue
                if ftype == 7:
                    print('   TEXT:', body.decode(errors='replace'))
                elif ftype == 2:
                    self.stats['voice_bytes'] += len(body)
                self.ack(ts)
        self.send_full(6, 5, ie(22, 'bye'))
        print('stats', self.stats)


import os
DTMF = os.environ.get('DTMF', '')
if __name__ == '__main__':
    a = sys.argv[1:]
    Call(a[0], int(a[1]), a[2], a[3], a[4]).run(float(a[5]) if len(a) > 5 else 15)
