#!/usr/bin/env python3
"""Xbox / Xbox 360 XDVDFS (XISO) extractor. Supports plain XISO, full Redump XGD1/2/3 images
and Games on Demand (GOD / SVOD) containers - pass the outer folder (e.g. "2008 Devil May Cry 4"),
the <TitleID> folder, the header file or its <header>.data folder: the layout is found
automatically.
Usage: xdvdfs_extract.py <image.iso | GOD folder> <out_dir> [--list]"""
import os, struct, sys, time

SECTOR = 2048
MAGIC = b"MICROSOFT*XBOX*MEDIA"
OFFSETS = {"XISO": 0, "XGD2": 0xFD90000, "XGD3": 0x2080000, "XGD1": 0x18300000}

class GodImage:
    """Games on Demand (SVOD) container as one flat image: Data0000.. files of 0xCB groups,
    each group = one hash block + 0xCC data blocks of 4 KiB (after a 4 KiB master hash)."""
    BLK, PER, GROUPS = 0x1000, 0xCC, 0xCB

    def __init__(self, data_dir):
        self.dir = data_dir
        self.files = sorted(n for n in os.listdir(data_dir) if n.startswith("Data"))
        if not self.files:
            raise SystemExit(f"no Data#### files in {data_dir}")
        self.pos = 0
        self.handles = {}

    def __enter__(self): return self
    def __exit__(self, *a):
        for h in self.handles.values(): h.close()

    def seek(self, pos, whence=0):
        self.pos = pos if whence == 0 else self.pos + pos

    def read(self, n):
        out = bytearray()
        while n > 0:
            b = self.pos // self.BLK
            fi, w = divmod(b, self.PER * self.GROUPS)
            g, k = divmod(w, self.PER)
            if fi >= len(self.files): break
            fo = 0x1000 + g * (self.PER + 1) * self.BLK + self.BLK + k * self.BLK + self.pos % self.BLK
            take = min(n, self.BLK - self.pos % self.BLK)
            h = self.handles.get(fi)
            if h is None:
                h = self.handles[fi] = open(os.path.join(self.dir, self.files[fi]), "rb")
            h.seek(fo)
            chunk = h.read(take)
            if not chunk: break
            out += chunk; self.pos += len(chunk); n -= len(chunk)
        return bytes(out)

def find_god_data_dir(path):
    """Any of: outer folder, TitleID folder, 00007000 folder, header file, header.data folder."""
    if os.path.isdir(path) and any(n.startswith("Data0000") for n in os.listdir(path)):
        return path
    if os.path.isfile(path) and os.path.isdir(path + ".data"):
        return path + ".data"
    if os.path.isdir(path):
        for root, dirs, files in os.walk(path):
            for d in dirs:
                if d.endswith(".data") and os.path.isfile(os.path.join(root, d[:-5])):
                    return os.path.join(root, d)
    return None

def open_image(path):
    god = find_god_data_dir(path)
    if god:
        print(f"[GOD] {god}", flush=True)
        return GodImage(god)
    return open(path, "rb")

def find_partition(f):
    for name, off in OFFSETS.items():
        f.seek(off + 32 * SECTOR)
        if f.read(20) == MAGIC:
            return name, off
    raise SystemExit("XDVDFS volume descriptor not found")

def read_dir(f, base, sector, size):
    if size == 0:
        return []
    f.seek(base + sector * SECTOR)
    data = f.read(size)
    out, stack, seen = [], [0], set()
    while stack:
        off = stack.pop()
        if off in seen or off + 14 > len(data):
            continue
        seen.add(off)
        left, right, start, fsize, attr, nlen = struct.unpack_from("<HHIIBB", data, off)
        if left == 0xFFFF and right == 0xFFFF:
            continue
        name = data[off + 14: off + 14 + nlen].decode("latin-1")
        out.append((name, start, fsize, attr))
        if left: stack.append(left * 4)
        if right: stack.append(right * 4)
    return out

def walk(f, base, sector, size, rel=""):
    for name, start, fsize, attr in sorted(read_dir(f, base, sector, size)):
        path = os.path.join(rel, name)
        if attr & 0x10:
            yield path, None, None
            yield from walk(f, base, start, fsize, path)
        else:
            yield path, start, fsize

def main():
    iso, out = sys.argv[1], sys.argv[2]
    listing = "--list" in sys.argv
    with open_image(iso) as f:
        kind, base = find_partition(f)
        f.seek(base + 32 * SECTOR + 20)
        root_sec, root_size = struct.unpack("<II", f.read(8))
        print(f"[{kind}] partition @ {base:#x}, root sector {root_sec}, size {root_size}", flush=True)
        total_files = total_bytes = 0
        t0 = time.time()
        for path, start, fsize in walk(f, base, root_sec, root_size):
            dst = os.path.join(out, path)
            if start is None:
                if not listing: os.makedirs(dst, exist_ok=True)
                continue
            total_files += 1; total_bytes += fsize
            if listing:
                print(f"{fsize:>12}  {path}"); continue
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            if os.path.exists(dst) and os.path.getsize(dst) == fsize:
                continue  # resume support
            f.seek(base + start * SECTOR)
            left = fsize
            with open(dst + ".part", "wb") as o:
                while left:
                    chunk = f.read(min(left, 8 << 20))
                    if not chunk: raise SystemExit(f"short read in {path}")
                    o.write(chunk); left -= len(chunk)
            os.replace(dst + ".part", dst)
            print(f"{fsize:>12}  {path}  ({total_bytes/2**20/(time.time()-t0+1e-9):.0f} MB/s)", flush=True)
        print(f"DONE: {total_files} files, {total_bytes/2**30:.2f} GiB", flush=True)

if __name__ == "__main__":
    main()
