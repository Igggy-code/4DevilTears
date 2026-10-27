#!/usr/bin/env python3
"""Find code addresses referenced from data or built in code with lis/addi (vtables, callbacks) that ReXGlue
analysis merged into a neighbouring function, and emit manifest boundaries.
Usage: find_vtable_funcs.py default.pe generated/default > extra_functions.toml"""
import bisect, glob, os, re, struct, sys

pe = open(sys.argv[1], 'rb').read(); gen = sys.argv[2]; B = 0x82000000
e = struct.unpack_from('<I', pe, 0x3C)[0]; ns = struct.unpack_from('<H', pe, e + 6)[0]
oh = struct.unpack_from('<H', pe, e + 20)[0]; s = e + 24 + oh
secs = []
for i in range(ns):
    n = pe[s:s+8].rstrip(b'\0').decode(); vs, va = struct.unpack_from('<II', pe, s + 8)
    secs.append((n, B + va, vs)); s += 40
text = [x for x in secs if x[0] == '.text'][0]; T0, T1 = text[1], text[1] + text[2]
data_secs = [x for x in secs if x[0] in ('.rdata', '.data')]

starts = sorted(int(a, 16) for a in re.findall(r'\{ 0x([0-9A-F]{8}), sub_',
                open(os.path.join(gen, 'dmc4_init.cpp')).read()))
start_set = set(starts)
labels = set()
for f in glob.glob(os.path.join(gen, '*.cpp')):
    labels.update(int(a, 16) for a in re.findall(r'\bloc_([0-9A-F]{8}):', open(f).read()))

def ins(a): return struct.unpack_from('>I', pe, a - B)[0]
def is_terminator(w):
    op = w >> 26
    if w == 0x4E800020: return True               # blr
    if w == 0x4E800420: return True               # bctr
    if op == 18 and not (w & 1): return True      # b (not bl)
    if w in (0, 0x60000000): return True          # padding / nop
    return False

# Import thunks (filled by the loader): [?, ?, mtctr r11, bctr] — handled by ReXGlue itself.
# Unpatched XEX import thunk: [0x01nnnnnn, 0x02nnnnnn, mtctr r11, bctr] (import records, not code).
def is_thunk(a):
    if a + 16 > T1: return False
    w0, w1 = ins(a), ins(a + 4)
    return (w0 >> 24) == 0x01 and (w1 >> 24) == 0x02 and (w0 & 0xFFFFFF) == (w1 & 0xFFFFFF) \
        and ins(a + 8) == 0x7D6903A6 and ins(a + 12) == 0x4E800420
thunks = [a for a in range(T0, T1 - 12, 4) if is_thunk(a)]
def first_thunk_after(a):
    i = bisect.bisect_right(thunks, a)
    return thunks[i] if i < len(thunks) else T1

refs = set()
for name, va, vs in data_secs:
    off = va - B
    for o in range(off, off + vs - 3, 4):
        v = struct.unpack_from('>I', pe, o)[0]
        if T0 <= v < T1 and not (v & 3):
            refs.add(v)

def used_as_func_ptr(a, ry):
    """Look at how the materialised address in ry is used next: stored to memory or
    passed as a call argument -> function pointer; used as a load base or jumped to
    locally (mtctr+bctr) -> table/label inside the current function."""
    for k in range(1, 16):
        x = ins(a + 4 * k); op = x >> 26
        if op in (32, 34, 40, 42, 48, 50) and ((x >> 16) & 31) == ry:   # lwz/lbz/lhz/lha/lfs/lfd base
            return False
        if x == (0x7C0903A6 | (ry << 21)):                               # mtctr ry
            return ins(a + 4 * (k + 1)) == 0x4E800421                    # bctrl = call (func ptr); bctr = local jump
        if op in (36, 37, 38, 44, 62) and ((x >> 21) & 31) == ry:       # stw/stwu/stb/sth/std ry, ...
            return True
        if op == 18 and (x & 1):                                         # bl: passed as argument?
            return 3 <= ry <= 10
        if op == 31 and ((x >> 1) & 0x3FF) == 444 and ((x >> 21) & 31) == ry and ((x >> 11) & 31) == ry:
            continue                                                     # mr rA, ry (copy) — keep looking
        if ((x >> 21) & 31) == ry and op not in (36, 37, 38, 44, 45, 47, 62, 16, 18, 19):
            return False                                                 # ry overwritten
        if x in (0x4E800020, 0x4E800420) or (op == 18 and not (x & 1)):
            return 3 <= ry <= 10                                         # tail call / return with ry as arg
    return False

# Function pointers materialised in code: lis rX,hi ; ... ; addi rY,rX,lo  -> address in .text
code_refs = set()
n_ins = (T1 - T0) // 4
for i in range(n_ins):
    w = ins(T0 + i * 4)
    if (w >> 26) != 15 or ((w >> 16) & 31) != 0: continue      # lis rD, imm
    rd = (w >> 21) & 31; hi = w & 0xFFFF
    for j in range(i + 1, min(i + 12, n_ins)):
        x = ins(T0 + j * 4)
        if (x >> 26) == 14 and ((x >> 16) & 31) == rd:          # addi rY, rD, lo
            lo = x & 0xFFFF
            v = ((hi << 16) + (lo - 0x10000 if lo & 0x8000 else lo)) & 0xFFFFFFFF
            if T0 <= v < T1 and not (v & 3) and used_as_func_ptr(T0 + j * 4, (x >> 21) & 31):
                code_refs.add(v)
            break
        if ((x >> 21) & 31) == rd and (x >> 26) not in (36, 37, 38, 44, 45, 47): break  # rD overwritten
refs |= code_refs

new = {}
for t in sorted(refs):
    if t in start_set or t in labels or is_thunk(t): continue
    i = bisect.bisect_right(starts, t) - 1
    if i < 0: continue
    if not is_terminator(ins(t - 4)): continue
    if ins(t) == 0: continue
    new.setdefault(starts[i], []).append(t)

def branch_target(a, w):
    op = w >> 26
    if op == 18 and not (w & 1):                       # b / ba
        d = w & 0x03FFFFFC
        if d & 0x02000000: d -= 0x04000000
        return d if w & 2 else a + d
    if op == 16 and not (w & 1):                       # bc
        d = w & 0xFFFC
        if d & 0x8000: d -= 0x10000
        return d if w & 2 else a + d
    return None

def crosses(f, end, t):
    """True if any non-linking branch in [f,end) jumps across the cut t."""
    for a in range(f, end, 4):
        bt = branch_target(a, ins(a))
        if bt is None or not (f <= bt < end): continue
        if (a < t) != (bt < t): return True
    return False

out = []; skipped = 0
for f, ts in sorted(new.items()):
    j = bisect.bisect_right(starts, f)
    end = min(starts[j] if j < len(starts) else T1, first_thunk_after(f))
    ts = [t for t in ts if t < end]
    ok = [t for t in sorted(ts) if not crosses(f, end, t)]
    skipped += len(ts) - len(ok)
    if not ok: continue
    cuts = [f] + ok + [end]
    for a, b in zip(cuts, cuts[1:]):
        out.append((a, b - a))
print(f"# auto: {len(out)} boundaries; skipped {skipped} cuts crossed by branches", file=sys.stderr)
print("# --- Auto-generated by tools/find_vtable_funcs.py: data-referenced (vtable/callback) entry points ---")
for a, sz in out:
    print(f'[entrypoint.functions."0x{a:08X}"]\nsize = 0x{sz:X}')
