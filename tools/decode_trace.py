#!/usr/bin/env python3
"""Decode a 4DevilTears frame trace into render passes.
Usage: decode_trace.py frame_trace.txt [--draws]"""
import json, os, re, struct, sys, collections
E = json.load(open(os.path.join(os.path.dirname(os.path.abspath(__file__)), 'xenos_enums.json')))
def en(name, v): return E[name].get(str(v), str(v))
def bits(v, lo, n): return (v >> lo) & ((1 << n) - 1)
def rt(c):
    base = bits(c, 0, 12); fmt = bits(c, 16, 4)
    return f"{base}:{en('ColorRenderTargetFormat', fmt)[2:]}"
def dep(d): return f"{bits(d,0,12)}:{en('DepthRenderTargetFormat', bits(d,16,1))[1:]}"
def surf(s): return f"pitch{bits(s,0,14)} {en('MsaaSamples', bits(s,16,2))[1:]}"
def tex(dw):
    d0, d1, d2, d3, d4, d5 = dw
    typ = bits(d0, 0, 2); pitch = bits(d0, 22, 9) * 32; tiled = bits(d0, 31, 1)
    fmt = en('TextureFormat', bits(d1, 0, 6))[2:]; endian = en('Endian', bits(d1, 6, 2))[1:]
    base = bits(d1, 12, 20) << 12
    dim = bits(d5, 9, 2)
    w = bits(d2, 0, 13) + 1; h = bits(d2, 13, 13) + 1
    mag = bits(d3, 19, 2); mn = bits(d3, 21, 2)
    return dict(base=base, fmt=fmt, w=w, h=h, tiled=tiled, pitch=pitch, endian=endian, dim=dim, typ=typ, filt=f"{mag}{mn}")
lines = open(sys.argv[1], errors='replace').read().splitlines()
show_draws = '--draws' in sys.argv
passes = []; cur = None; copies = []; transfers = []
written_tex = {}  # copy dest base -> (fmt, w)
for i, l in enumerate(lines):
    if l.startswith('D '):
        f = dict(kv.split('=', 1) for kv in l.split()[2:] if '=' in kv)
        g = lambda k: int(f[k], 16)
        surfv = g('surf'); mask = g('mask'); dc = g('dc'); mode = int(f['edram'])
        rts = [rt(g(f'c{k}')) for k in range(4) if (mask >> (4 * k)) & 0xF]
        z = dep(g('d')) if (dc & 0x6) else '-'
        key = (surf(surfv), tuple(rts), z, mode)
        if cur is None or cur['key'] != key:
            cur = dict(key=key, draws=0, prims=0, tex=collections.Counter(), first=int(l.split()[1]), a2c=0, blend=0, vp=f['vp'], ps=set())
            passes.append(cur)
        cur['draws'] += 1; cur['prims'] += int(f['n'])
        cc = g('cc'); cur['a2c'] += bits(cc, 4, 1)
        cur['ps'].add(f['ps'])
        if show_draws: print(l)
    elif l.lstrip().startswith(('PT ', 'VT ')) and cur is not None:
        parts = l.split(); dw = [int(x, 16) for x in parts[2:8]]
        t = tex(dw)
        src = ' <copy>' if t['base'] in written_tex else ''
        cur['tex'][f"{t['fmt']} {t['w']}x{t['h']} {'T' if t['tiled'] else 'L'} @{t['base']:08X}{src}"] += 1
    elif l.startswith('C '):
        f = dict(kv.split('=', 1) for kv in l.split()[2:] if '=' in kv)
        g = lambda k: int(f[k], 16)
        ctl = g('ctl'); dbase = g('dbase'); dpitch = g('dpitch'); dinfo = g('dinfo')
        sel = bits(ctl, 0, 3); src = dep(g('d')) if sel == 4 else rt(g(f'c{sel}'))
        fmt = en('ColorFormat', bits(dinfo, 7, 6)); w = bits(dpitch, 0, 14); h = bits(dpitch, 16, 14)
        clr = ('clearC ' if bits(ctl, 8, 1) else '') + ('clearZ' if bits(ctl, 9, 1) else '')
        written_tex[dbase] = (fmt, w)
        copies.append(f"after pass {len(passes)}: copy src {src} ({surf(g('surf'))}) -> @{dbase:08X} {fmt} {w}x{h} endian{bits(dinfo,0,3)} {clr}")
        cur = None
    elif l.startswith('T '):
        transfers.append(f"before pass {len(passes)+1}: {l[2:]}")
for n, p in enumerate(passes, 1):
    s, rts, z, mode = p['key']
    print(f"\n#{n} draws={p['draws']} (from {p['first']}) prims={p['prims']} vp={p['vp']} [{s}] RT={','.join(rts) or '-'} Z={z} edram_mode={mode} a2c={p['a2c']} ps={len(p['ps'])}")
    for t, c in p['tex'].most_common(8):
        print(f"     tex x{c}: {t}")
print("\n== copies (EDRAM -> texture)"); print("\n".join(copies))
print("\n== render target transfers"); print("\n".join(transfers))
