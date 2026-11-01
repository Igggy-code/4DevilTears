// 4DevilTears: corrected Russian subtitles for the Russian fan edition.
//
// The Russian edition's cutscene subtitles were written for the English voice track (the
// timing tables were never changed), are all in capitals and lose З/Й/Щ/Ъ/Ё, because the
// subtitle font only had 17 Latin cells redrawn as Cyrillic. The port ships a new
// translation that follows the Russian voice line by line, plus a full Cyrillic alphabet.
//
// Nothing of the game is shipped: the APK carries only our own data (dmc4_ru_subs_data.inc):
// texts in the game's MSG2 encoding, cue times, our glyph cells (DXT5 blocks: Philosopher Bold
// for subtitles, Regular for menus and hints) and checksums. On the first start after the game
// was imported (or after this data changed) the player's own unpacked copy is updated in place:
//   - rom/demo/*.arc: language\America\<scene>_e (texts) and <scene>_super_e (timing table:
//     only the key arrays are replaced). The game streams these by entry, so the new entry is
//     appended to the end of the .arc and its table row updated (no 100 MB rewrite);
//   - every other .arc holding the font (language\moji00_u16_NOMIP_BM_HQ) or a menu/hint text
//     (localize\USA\..., language\America\...): the game loads those archives in one piece and
//     doesn't accept data past the original end, so each is rebuilt in table order into a
//     temporary file (replaced entries as LZX "uncompressed" blocks, like the game's own packer)
//     which then replaces the original.
//
// Only the Russian edition is touched: the game's part of the font in rom/system/dmc4_cmn.arc
// must be the Russian edition's, and every entry must match the Russian original (or already be
// ours) before it is replaced. Anything else is left alone.
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <algorithm>
#include <filesystem>
#include <map>
#include <utility>
#include <string>
#include <vector>
#if !defined(_WIN32)
#include <unistd.h>
#endif

#include "dmc4_lzx.h"

#if defined(DMC4_RU_SUBS_TEST)
#define RUSUBS_SINK(buf) std::printf("%s\n", buf)
#else
#include <rex/logging.h>
// The port's logger takes {}-style formats: format printf-style first.
#define RUSUBS_SINK(buf) REXKRNL_INFO("{}", buf)
#endif
// Also kept in <game>/.4dt_ru_subs.log (logcat rolls over long before anyone reads it).
static std::FILE* g_rusubs_log = nullptr;
#define RUSUBS_LOG(...) do { char rusubs_buf_[256]; std::snprintf(rusubs_buf_, sizeof rusubs_buf_, __VA_ARGS__); \
    RUSUBS_SINK(rusubs_buf_); if (g_rusubs_log) { std::fprintf(g_rusubs_log, "%s\n", rusubs_buf_); std::fflush(g_rusubs_log); } } while (0)

namespace {

struct RuSubScene {
  const char* msg_arc; const char* msg_name; uint64_t msg_orig, msg_new;
  const uint8_t* msg; uint32_t msg_size;
  const char* sdl_arc; const char* sdl_name; uint64_t sdl_orig, sdl_new;
  const uint32_t* keys_t; const int32_t* keys_v; uint32_t nkeys;
};
// A menu/hint text (MSG2): one of our texts and the Russian edition's original it replaces.
struct RuMenuMsg { uint64_t orig, neu; const uint8_t* data; uint32_t size; };
struct RuArcEntry { const char* name; uint16_t msg; };
// One archive rebuilt in place: the font copy (if it has one) and its menu texts.
// A texture of ours (lettering we draw): pixel data after the 0x2C header; the header is the player's.
// rects == nullptr: data replaces all pixel data. Otherwise data holds the listed rectangles
// (x, y, w, h quadruples) of an 8_8_8_8 or DXT5 texture, pixel by pixel (or 4x4 block by block)
// in row order, as stored.
struct RuTex { const char* name; uint64_t orig, neu; const uint8_t* data; uint32_t size;
               const uint16_t* rects; uint32_t nrects; uint32_t tex_size; };
// A fix made inside the player's own DXT5 texture (no pixels shipped): clear a rectangle, then copy
// another rectangle of the same texture onto it (e.g. one letter of a neighbouring word). Applied only
// to the Russian edition's original texture (orig = FNV of the whole decompressed texture).
struct RuTexFix { const char* arc; const char* name; uint64_t orig; uint32_t tex_size;
                  uint16_t cx, cy, cw, ch;  uint16_t sx, sy, sw, sh, dx, dy; };
struct RuArcJob { const char* arc; bool font; const RuArcEntry* entries; uint32_t nentries;
                  const RuTex* tex; uint32_t ntex; };

#include "dmc4_ru_subs_data.inc"

using Bytes = std::vector<uint8_t>;

uint64_t Fnv(const uint8_t* p, size_t n) {
  uint64_t h = 0xcbf29ce484222325ull;
  for (size_t i = 0; i < n; i++) h = (h ^ p[i]) * 0x100000001b3ull;
  return h;
}
uint64_t Fnv(const Bytes& b) { return Fnv(b.data(), b.size()); }
uint32_t Be32(const uint8_t* p) { return uint32_t(p[0]) << 24 | p[1] << 16 | p[2] << 8 | p[3]; }
uint16_t Be16(const uint8_t* p) { return uint16_t(p[0] << 8 | p[1]); }
void PutBe32(uint8_t* p, uint32_t v) { p[0] = v >> 24; p[1] = v >> 16; p[2] = v >> 8; p[3] = v; }
void PutBe16(uint8_t* p, uint16_t v) { p[0] = v >> 8; p[1] = v; }

// ---------------- XMemCompress native stream ----------------
struct MemFile { const uint8_t* rb; uint8_t* wb; size_t n, o; };
int MfRead(mspack_file* f, void* buf, int c) {
  auto* m = reinterpret_cast<MemFile*>(f);
  size_t r = std::min<size_t>(c, m->n - m->o);
  std::memcpy(buf, m->rb + m->o, r); m->o += r; return int(r);
}
int MfWrite(mspack_file* f, void* buf, int c) {
  auto* m = reinterpret_cast<MemFile*>(f);
  size_t r = std::min<size_t>(c, m->n - m->o);
  std::memcpy(m->wb + m->o, buf, r); m->o += r; return int(r);
}
void* MfAlloc(mspack_system*, size_t n) { return std::calloc(n, 1); }
void MfFree(void* p) { std::free(p); }
void MfCopy(void* s, void* d, size_t n) { std::memcpy(d, s, n); }
void MfMsg(mspack_file*, const char*, ...) {}

// Frames: [u16 csize] (32 KB of output) or [0xFF u16 usize u16 csize]; LZX, 128 KB window.
bool XmemDecompress(const uint8_t* in, size_t n, Bytes& out, size_t outn) {
  Bytes raw; raw.reserve(n);
  size_t p = 0;
  while (p + 2 <= n) {
    size_t cs;
    if (in[p] == 0xFF) { if (p + 5 > n) break; cs = Be16(in + p + 3); p += 5; }
    else { cs = Be16(in + p); p += 2; }
    if (!cs || p + cs > n) break;
    raw.insert(raw.end(), in + p, in + p + cs); p += cs;
  }
  out.assign(outn, 0);
  mspack_system sys{};
  sys.read = MfRead; sys.write = MfWrite; sys.alloc = MfAlloc; sys.free = MfFree;
  sys.copy = MfCopy; sys.message = MfMsg;
  MemFile src{raw.data(), nullptr, raw.size(), 0}, dst{nullptr, out.data(), outn, 0};
  lzxd_stream* z = lzxd_init(&sys, reinterpret_cast<mspack_file*>(&src),
                             reinterpret_cast<mspack_file*>(&dst), 17, 0, 0x8000, off_t(outn), 0);
  if (!z) return false;
  int rc = lzxd_decompress(z, off_t(outn));
  lzxd_free(z);
  return rc == MSPACK_ERR_OK;
}

// The same stream made of LZX uncompressed blocks, one per 32 KB frame.
Bytes XmemStore(const Bytes& data) {
  Bytes out;
  size_t pos = 0;
  bool first = true;
  while (pos < data.size()) {
    const size_t n = std::min<size_t>(0x8000, data.size() - pos);
    Bytes blk;
    uint32_t cur = 0; int cnt = 0;
    auto put = [&](uint32_t v, int bits) {
      for (int i = bits - 1; i >= 0; i--) {
        cur = (cur << 1) | ((v >> i) & 1);
        if (++cnt == 16) { blk.push_back(uint8_t(cur)); blk.push_back(uint8_t(cur >> 8)); cur = 0; cnt = 0; }
      }
    };
    if (first) put(0, 1);              // no E8 translation
    put(3, 3);                         // block type: uncompressed
    put(uint32_t(n) >> 8, 16); put(uint32_t(n) & 0xFF, 8);
    while (cnt) put(0, 1);             // align (never already aligned here: 28 or 27 bits)
    // Every uncompressed block carries R0..R2 (12 bytes, little-endian).
    for (int r = 0; r < 3; r++) { blk.push_back(1); blk.push_back(0); blk.push_back(0); blk.push_back(0); }
    blk.insert(blk.end(), data.begin() + pos, data.begin() + pos + n);
    // No pad byte after an odd-sized block: the game's own packer writes none, and the game's
    // decoder rejects the entry if one is there (libmspack accepts both).
    uint8_t h[5];
    if (n == 0x8000) { PutBe16(h, uint16_t(blk.size())); out.insert(out.end(), h, h + 2); }
    else { h[0] = 0xFF; PutBe16(h + 1, uint16_t(n)); PutBe16(h + 3, uint16_t(blk.size())); out.insert(out.end(), h, h + 5); }
    out.insert(out.end(), blk.begin(), blk.end());
    pos += n; first = false;
  }
  return out;
}

// ---------------- MT Framework .arc (Xbox 360, v17) ----------------
struct Arc {
  FILE* f = nullptr;
  struct Entry { std::string name; uint32_t type, csize, dsf, off; long row; };
  std::vector<Entry> e;
  ~Arc() { if (f) std::fclose(f); }
  bool Open(const std::filesystem::path& p) {
    f = std::fopen(p.string().c_str(), "r+b");
    if (!f) return false;
    uint8_t h[8];
    if (std::fread(h, 1, 8, f) != 8 || std::memcmp(h, "\0CRA", 4) || Be16(h + 4) != 17) return false;
    const int n = Be16(h + 6);
    Bytes t(size_t(n) * 80);
    if (std::fread(t.data(), 1, t.size(), f) != t.size()) return false;
    for (int i = 0; i < n; i++) {
      const uint8_t* r = &t[size_t(i) * 80];
      Entry x;
      x.name.assign(reinterpret_cast<const char*>(r), strnlen(reinterpret_cast<const char*>(r), 64));
      x.type = Be32(r + 64); x.csize = Be32(r + 68); x.dsf = Be32(r + 72); x.off = Be32(r + 76);
      x.row = 8 + long(i) * 80;
      e.push_back(x);
    }
    return true;
  }
  Entry* Find(const char* name) {
    for (auto& x : e) if (x.name == name) return &x;
    return nullptr;
  }
  bool Read(const Entry& x, Bytes& out) {
    Bytes c(x.csize);
    if (std::fseek(f, long(x.off), SEEK_SET) || std::fread(c.data(), 1, c.size(), f) != c.size()) return false;
    const size_t ds = x.dsf >> 3;
    if (x.csize == ds) { out = c; return true; }
    return XmemDecompress(c.data(), c.size(), out, ds);
  }
  // Appends the new data and then points the table row at it.
  bool Replace(Entry& x, const Bytes& data) {
    const Bytes z = XmemStore(data);
    if (std::fseek(f, 0, SEEK_END)) return false;
    const long off = std::ftell(f);
    if (off < 0 || std::fwrite(z.data(), 1, z.size(), f) != z.size() || std::fflush(f)) return false;
#if !defined(_WIN32)
    fsync(fileno(f));
#endif
    uint8_t row[12];
    const uint32_t dsf = uint32_t(data.size()) << 3 | (x.dsf & 7);
    PutBe32(row, uint32_t(z.size())); PutBe32(row + 4, dsf); PutBe32(row + 8, uint32_t(off));
    if (std::fseek(f, x.row + 68, SEEK_SET) || std::fwrite(row, 1, 12, f) != 12 || std::fflush(f)) return false;
#if !defined(_WIN32)
    fsync(fileno(f));
#endif
    x.csize = uint32_t(z.size()); x.dsf = dsf; x.off = uint32_t(off);
    return true;
  }
};

// Rewrites the whole archive in table/offset order with the given entries replaced (LZX
// uncompressed blocks). Archives the game reads in one piece (menus, items, rooms) don't see
// data appended past their original end, so those are always rebuilt this way.
using Repl = std::vector<std::pair<std::pair<std::string, uint32_t>, Bytes>>;
bool RepackArc(const std::filesystem::path& p, const Repl& repl, int& replaced) {
  Bytes d;
  {
    std::FILE* f = std::fopen(p.string().c_str(), "rb");
    if (!f) return false;
    std::fseek(f, 0, SEEK_END); const long n = std::ftell(f); std::fseek(f, 0, SEEK_SET);
    d.resize(size_t(n));
    const bool ok = std::fread(d.data(), 1, d.size(), f) == d.size();
    std::fclose(f);
    if (!ok || d.size() < 8 || std::memcmp(d.data(), "\0CRA", 4) || Be16(&d[4]) != 17) return false;
  }
  const size_t n = Be16(&d[6]);
  if (8 + n * 80 > d.size()) return false;
  std::vector<size_t> order(n);
  size_t start = d.size();
  for (size_t i = 0; i < n; i++) { order[i] = i; start = std::min<size_t>(start, Be32(&d[8 + i * 80 + 76])); }
  std::sort(order.begin(), order.end(), [&](size_t x, size_t y) { return Be32(&d[8 + x * 80 + 76]) < Be32(&d[8 + y * 80 + 76]); });
  Bytes out(d.begin(), d.begin() + start);
  for (size_t i : order) {
    const size_t row = 8 + i * 80;   // index, not a pointer: out grows below
    const std::string name(reinterpret_cast<const char*>(&out[row]), strnlen(reinterpret_cast<const char*>(&out[row]), 64));
    const uint32_t type = Be32(&out[row + 64]), cs = Be32(&out[row + 68]), dsf = Be32(&out[row + 72]), off = Be32(&out[row + 76]);
    const Bytes* nd = nullptr;
    for (const auto& r : repl) if (r.first.first == name && r.first.second == type) nd = &r.second;
    const size_t at = out.size();
    if (nd) {
      const Bytes z = XmemStore(*nd);
      out.insert(out.end(), z.begin(), z.end());
      PutBe32(&out[row + 68], uint32_t(z.size())); PutBe32(&out[row + 72], uint32_t(nd->size()) << 3 | (dsf & 7));
      replaced++;
    } else {
      if (size_t(off) + cs > d.size()) return false;
      out.insert(out.end(), d.begin() + off, d.begin() + off + cs);
    }
    PutBe32(&out[row + 76], uint32_t(at));
  }
  const std::filesystem::path tmp = p.string() + ".4dt_tmp";
  std::FILE* f = std::fopen(tmp.string().c_str(), "wb");
  if (!f) return false;
  bool ok = std::fwrite(out.data(), 1, out.size(), f) == out.size() && std::fflush(f) == 0;
#if !defined(_WIN32)
  ok = ok && fsync(fileno(f)) == 0;
#endif
  std::fclose(f);
  std::error_code ec;
  if (!ok) { std::filesystem::remove(tmp, ec); return false; }
  std::filesystem::rename(tmp, p, ec);
  return !ec;
}

// ---------------- timing table (<scene>_super_e, "\0LDS") ----------------
// 0x44: three tracks of 24 bytes (subtitles, then two service tracks): u16 key count at +2,
// time / value array offsets at +16 / +20. The value array is 16-byte aligned; everything after
// the subtitle arrays is addressed by absolute offsets (0x10, 0x6C, 0x70, 0x84, 0x88).
bool SdlWrite(const Bytes& d, const uint32_t* T, const int32_t* V, uint32_t n, Bytes& out) {
  auto a16 = [](size_t x) { return (x + 15) & ~size_t(15); };
  if (d.size() < 0x8C || std::memcmp(d.data(), "\0LDS", 4)) return false;
  const uint32_t n0 = Be16(&d[0x46]);
  if (Be32(&d[0x54]) != 0x8C || Be32(&d[0x58]) != a16(0x8C + 4 * n0)) return false;
  // The tail starts where the next track's arrays start (an earlier version of ours left
  // zero padding before it, so it is not always right after the subtitle values).
  const size_t tail0 = Be32(&d[0x6C]);
  if (tail0 < Be32(&d[0x58]) + 4 * n0 || tail0 > d.size()) return false;
  const size_t vo = a16(0x8C + 4 * n);
  size_t tail = vo + 4 * n;
  if ((tail - tail0) % 16) tail += 16 - (tail - tail0) % 16;
  const int64_t delta = int64_t(tail) - int64_t(tail0);
  out.assign(d.begin(), d.begin() + 0x8C);
  PutBe16(&out[0x46], uint16_t(n));
  PutBe32(&out[0x54], 0x8C); PutBe32(&out[0x58], uint32_t(vo));
  for (size_t o : {0x10, 0x6C, 0x70, 0x84, 0x88}) {
    const uint32_t v = Be32(&out[o]);
    if (v < tail0) return false;
    PutBe32(&out[o], uint32_t(int64_t(v) + delta));
  }
  out.resize(tail, 0);
  for (uint32_t k = 0; k < n; k++) { PutBe32(&out[0x8C + 4 * k], T[k]); PutBe32(&out[vo + 4 * k], uint32_t(V[k])); }
  out.insert(out.end(), d.begin() + tail0, d.end());
  return true;
}

// ---------------- subtitle font (512x512 DXT5, Xbox 360 tiled) ----------------
uint32_t TiledOffset(uint32_t x, uint32_t y, uint32_t width_blocks, uint32_t log_bpb) {
  const uint32_t aligned_w = (width_blocks + 31) & ~31u;
  const uint32_t macro = ((x >> 5) + (y >> 5) * (aligned_w >> 5)) << (log_bpb + 7);
  const uint32_t micro = ((x & 7) + ((y & 0xE) << 2)) << log_bpb;
  const uint32_t offset = macro + ((micro & ~0xFu) << 1) + (micro & 0xF) + ((y & 1) << 4);
  return ((offset & ~0x1FFu) << 3) + ((y & 16) << 7) + ((offset & 0x1C0) << 2) +
         (((((y & 8) >> 2) + (x >> 3)) & 3) << 6) + (offset & 0x3F);
}

// ---------------- DXT5 (Xbox 360: 16-bit words byte-swapped, tiled) ----------------
struct Rgba { uint8_t r, g, b, a; };
void Dxt5Decode(const uint8_t* q, Rgba px[16]) {
  uint8_t b[16];
  for (int i = 0; i < 16; i += 2) { b[i] = q[i + 1]; b[i + 1] = q[i]; }
  uint8_t al[8] = {b[0], b[1]};
  if (b[0] > b[1]) for (int i = 1; i < 7; i++) al[i + 1] = uint8_t(((7 - i) * b[0] + i * b[1]) / 7);
  else { for (int i = 1; i < 5; i++) al[i + 1] = uint8_t(((5 - i) * b[0] + i * b[1]) / 5); al[6] = 0; al[7] = 255; }
  uint64_t ai = 0; for (int i = 0; i < 6; i++) ai |= uint64_t(b[2 + i]) << (8 * i);
  const uint16_t c0 = uint16_t(b[8] | b[9] << 8), c1 = uint16_t(b[10] | b[11] << 8);
  auto ex = [](uint16_t c, int sh, int bits) { const int v = (c >> sh) & ((1 << bits) - 1); return bits == 5 ? (v * 255 + 15) / 31 : (v * 255 + 31) / 63; };
  int cr[4], cg[4], cb[4];
  cr[0] = ex(c0, 11, 5); cg[0] = ex(c0, 5, 6); cb[0] = ex(c0, 0, 5);
  cr[1] = ex(c1, 11, 5); cg[1] = ex(c1, 5, 6); cb[1] = ex(c1, 0, 5);
  cr[2] = (2 * cr[0] + cr[1]) / 3; cg[2] = (2 * cg[0] + cg[1]) / 3; cb[2] = (2 * cb[0] + cb[1]) / 3;
  cr[3] = (cr[0] + 2 * cr[1]) / 3; cg[3] = (cg[0] + 2 * cg[1]) / 3; cb[3] = (cb[0] + 2 * cb[1]) / 3;
  const uint32_t ci = uint32_t(b[12] | b[13] << 8 | b[14] << 16 | uint32_t(b[15]) << 24);
  for (int i = 0; i < 16; i++) {
    const int k = (ci >> (2 * i)) & 3;
    px[i] = {uint8_t(cr[k]), uint8_t(cg[k]), uint8_t(cb[k]), al[(ai >> (3 * i)) & 7]};
  }
}
void Dxt5Encode(const Rgba px[16], uint8_t* q) {
  uint8_t b[16] = {};
  int amin = 255, amax = 0;
  for (int i = 0; i < 16; i++) { amin = std::min<int>(amin, px[i].a); amax = std::max<int>(amax, px[i].a); }
  b[0] = uint8_t(amax); b[1] = uint8_t(amin);
  if (amax > amin) {
    int al[8] = {amax, amin}; for (int i = 1; i < 7; i++) al[i + 1] = ((7 - i) * amax + i * amin) / 7;
    uint64_t ai = 0;
    for (int i = 0; i < 16; i++) { int best = 0; for (int k = 1; k < 8; k++) if (std::abs(al[k] - px[i].a) < std::abs(al[best] - px[i].a)) best = k; ai |= uint64_t(best) << (3 * i); }
    for (int i = 0; i < 6; i++) b[2 + i] = uint8_t(ai >> (8 * i));
  }
  // colour: endpoints = brightest / darkest visible pixel
  int lo = -1, hi = -1, lmin = 1 << 30, lmax = -1;
  for (int i = 0; i < 16; i++) {
    if (px[i].a < 8) continue;
    const int l = px[i].r * 2 + px[i].g * 4 + px[i].b;
    if (l < lmin) { lmin = l; lo = i; }
    if (l > lmax) { lmax = l; hi = i; }
  }
  if (hi < 0) { lo = hi = 0; }
  auto pk = [](const Rgba& c) { return uint16_t((c.r >> 3) << 11 | (c.g >> 2) << 5 | (c.b >> 3)); };
  uint16_t c0 = pk(px[hi]), c1 = pk(px[lo]);
  if (c0 < c1) std::swap(c0, c1);
  b[8] = uint8_t(c0); b[9] = uint8_t(c0 >> 8); b[10] = uint8_t(c1); b[11] = uint8_t(c1 >> 8);
  if (c0 != c1) {
    auto ex = [](uint16_t c, int sh, int bits) { const int v = (c >> sh) & ((1 << bits) - 1); return bits == 5 ? (v * 255 + 15) / 31 : (v * 255 + 31) / 63; };
    int cr[4], cg[4], cb[4];
    cr[0] = ex(c0, 11, 5); cg[0] = ex(c0, 5, 6); cb[0] = ex(c0, 0, 5); cr[1] = ex(c1, 11, 5); cg[1] = ex(c1, 5, 6); cb[1] = ex(c1, 0, 5);
    cr[2] = (2 * cr[0] + cr[1]) / 3; cg[2] = (2 * cg[0] + cg[1]) / 3; cb[2] = (2 * cb[0] + cb[1]) / 3;
    cr[3] = (cr[0] + 2 * cr[1]) / 3; cg[3] = (cg[0] + 2 * cg[1]) / 3; cb[3] = (cb[0] + 2 * cb[1]) / 3;
    uint32_t ci = 0;
    for (int i = 0; i < 16; i++) {
      int best = 0, bd = 1 << 30;
      for (int k = 0; k < 4; k++) { const int d = (cr[k] - px[i].r) * (cr[k] - px[i].r) + (cg[k] - px[i].g) * (cg[k] - px[i].g) + (cb[k] - px[i].b) * (cb[k] - px[i].b); if (d < bd) { bd = d; best = k; } }
      ci |= uint32_t(best) << (2 * i);
    }
    b[12] = uint8_t(ci); b[13] = uint8_t(ci >> 8); b[14] = uint8_t(ci >> 16); b[15] = uint8_t(ci >> 24);
  }
  for (int i = 0; i < 16; i += 2) { q[i] = b[i + 1]; q[i + 1] = b[i]; }
}

// Clear one rectangle and copy another onto it, inside a DXT5 texture (only the touched blocks change).
void ApplyTexFix(Bytes& tex, const RuTexFix& f) {
  const uint32_t w = Be16(&tex[0x0C]), wb = w / 4;
  auto blk = [&](uint32_t bx, uint32_t by) { return &tex[0x2C + TiledOffset(bx, by, wb, 4)]; };
  auto get = [&](uint32_t x, uint32_t y) { Rgba p[16]; Dxt5Decode(blk(x / 4, y / 4), p); return p[(y % 4) * 4 + x % 4]; };
  // the copied pixels, read before anything is written
  std::vector<Rgba> src(size_t(f.sw) * f.sh);
  for (uint32_t y = 0; y < f.sh; y++) for (uint32_t x = 0; x < f.sw; x++) src[y * f.sw + x] = get(f.sx + x, f.sy + y);
  const uint32_t x0 = std::min<uint32_t>(f.cx, f.dx), y0 = std::min<uint32_t>(f.cy, f.dy);
  const uint32_t x1 = std::max<uint32_t>(f.cx + f.cw, f.dx + f.sw), y1 = std::max<uint32_t>(f.cy + f.ch, f.dy + f.sh);
  for (uint32_t by = y0 / 4; by <= (y1 - 1) / 4; by++)
    for (uint32_t bx = x0 / 4; bx <= (x1 - 1) / 4; bx++) {
      Rgba p[16]; Dxt5Decode(blk(bx, by), p);
      for (int i = 0; i < 16; i++) {
        const uint32_t x = bx * 4 + i % 4, y = by * 4 + i / 4;
        if (x >= f.cx && x < uint32_t(f.cx) + f.cw && y >= f.cy && y < uint32_t(f.cy) + f.ch) p[i].a = 0;
        if (x >= f.dx && x < uint32_t(f.dx) + f.sw && y >= f.dy && y < uint32_t(f.dy) + f.sh) {
          const Rgba& c = src[(y - f.dy) * f.sw + (x - f.dx)];
          if (c.a > p[i].a) p[i] = c;
        }
      }
      Dxt5Encode(p, blk(bx, by));
    }
}

// Our glyph cells (32x32 px = 8x8 DXT5 blocks each) written over the player's texture.
void PatchFont(Bytes& tex) {
  for (size_t k = 0; k < sizeof(kFontCells) / sizeof(kFontCells[0]); k++) {
    const uint32_t cx = kFontCells[k] % 16 * 8, cy = kFontCells[k] / 16 * 8;
    for (uint32_t by = 0; by < 8; by++)
      for (uint32_t bx = 0; bx < 8; bx++)
        std::memcpy(&tex[0x2C + TiledOffset(cx + bx, cy + by, 128, 4)], &kFontCellBlocks[((k * 8 + by) * 8 + bx) * 16], 16);
  }
}

// The game's own part of the font (cells 0..79: digits, Latin, the edition's Cyrillic), as block bytes in row order.
uint64_t FontBaseHash(const Bytes& tex) {
  uint64_t h = 0xcbf29ce484222325ull;
  for (uint32_t by = 0; by < kFontBaseRows; by++)
    for (uint32_t bx = 0; bx < 128; bx++) {
      const uint8_t* b = &tex[0x2C + TiledOffset(bx, by, 128, 4)];
      for (int i = 0; i < 16; i++) h = (h ^ b[i]) * 0x100000001b3ull;
    }
  return h;
}

// Our texts use only the glyph cells we add (80+) and the space (14); the game's cutscene texts never do.
bool IsOurMsg(const Bytes& d) {
  if (d.size() < 0x44 || std::memcmp(d.data(), "MSG2", 4)) return false;
  bool glyph = false;
  for (size_t i = 0x40; i + 4 <= d.size(); i += 4) {
    if (d[i + 3] == 4) continue;
    const uint32_t g = Be16(&d[i]) >> 1;
    if (g != 14 && g < 80) return false;
    glyph = true;
  }
  return glyph;
}

// A menu text written by an earlier version of ours: uses one of our glyph cells (the Russian
// edition's menu texts use only cells 0..79 and 84, 110, 132, 143).
bool IsOurMenuMsg(const Bytes& d) {
  if (d.size() < 0x44 || std::memcmp(d.data(), "MSG2", 4)) return false;
  for (size_t i = 0x40; i + 4 <= d.size(); i += 4) {
    if (d[i + 3] == 4) continue;
    const uint32_t g = Be16(&d[i]) >> 1;
    if (g >= 80 && g != 84 && g != 110 && g != 132 && g != 143) return true;
  }
  return false;
}

std::filesystem::path Marker(const std::filesystem::path& game) { return game / ".4dt_ru_subs"; }

}  // namespace

// Returns true when the Russian edition was found and is up to date (patched now or before).
bool Dmc4ApplyRuSubtitles(const std::filesystem::path& game_root) {
  namespace fs = std::filesystem;
  const fs::path nx = game_root / "nativeXenon";
  const std::string want = std::to_string(kRuSubsVersion);
  {
    std::FILE* m = std::fopen(Marker(game_root).string().c_str(), "rb");
    if (m) {
      char buf[32] = {};
      const size_t got = std::fread(buf, 1, sizeof(buf) - 1, m); std::fclose(m); buf[got] = 0;
      if (want == buf) return true;
    }
  }
  struct LogFile { ~LogFile() { if (g_rusubs_log) std::fclose(g_rusubs_log); g_rusubs_log = nullptr; } } log_file;
  g_rusubs_log = std::fopen((game_root / ".4dt_ru_subs.log").string().c_str(), "w");
  // Edition check: the game's part of the font (cells 0..79) is the Russian edition's.
  {
    Arc a; Bytes tex;
    if (!a.Open(nx / "rom/system/dmc4_cmn.arc")) { RUSUBS_LOG("RUSUBS: dmc4_cmn.arc not readable"); return false; }
    auto* x = a.Find("language\\moji00_u16_NOMIP_BM_HQ");
    if (!x || !a.Read(*x, tex)) { RUSUBS_LOG("RUSUBS: subtitle font not found"); return false; }
    const uint64_t h = tex.size() == 0x2C + 512 * 512 ? FontBaseHash(tex) : 0;
    if (h != kFontRuBase) {
      RUSUBS_LOG("RUSUBS: not the Russian edition (font %016llx) - nothing to do", (unsigned long long)h);
      return false;
    }
  }
  RUSUBS_LOG("RUSUBS: Russian edition - installing translation v%u", kRuSubsVersion);
  int done = 0, skipped = 0, failed = 0, arcs = 0;
  // Font copies and menu texts: each archive rebuilt once, in order.
  for (const auto& j : kArcJobs) {
    const fs::path p = nx / j.arc;
    Repl repl;
    {
      Arc a;
      if (!a.Open(p)) { failed++; RUSUBS_LOG("RUSUBS: can't open %s", j.arc); continue; }
      if (j.font) {
        Bytes tex;
        auto* x = a.Find("language\\moji00_u16_NOMIP_BM_HQ");
        if (x && a.Read(*x, tex) && tex.size() == 0x2C + 512 * 512 && FontBaseHash(tex) == kFontRuBase) {
          PatchFont(tex);
          repl.push_back({{x->name, x->type}, std::move(tex)});
        } else { skipped++; RUSUBS_LOG("RUSUBS: font in %s is not the expected one, left alone", j.arc); }
      }
      for (uint32_t k = 0; k < j.nentries; k++) {
        const RuMenuMsg& m = kMenuMsgs[j.entries[k].msg];
        auto* x = a.Find(j.entries[k].name);
        Bytes cur;
        if (!x || !a.Read(*x, cur)) { skipped++; continue; }
        const uint64_t h = Fnv(cur);
        if (h != m.orig && h != m.neu && !IsOurMenuMsg(cur)) { skipped++; RUSUBS_LOG("RUSUBS: %s in %s differs, left alone", j.entries[k].name, j.arc); continue; }
        repl.push_back({{x->name, x->type}, Bytes(m.data, m.data + m.size)});
      }
      for (uint32_t k = 0; k < j.ntex; k++) {
        const RuTex& t = j.tex[k];
        auto* x = a.Find(t.name);
        Bytes cur;
        if (!x || !a.Read(*x, cur) || cur.size() != size_t(t.tex_size)) { skipped++; continue; }
        // Our pixels go in first; everything else must then be exactly the Russian edition's
        // (so an older drawing of ours is replaced too, and a different texture is left alone).
        if (!t.rects) {
          std::memcpy(cur.data() + 0x2C, t.data, t.size);
        } else {
          // 8_8_8_8: one pixel = 4 bytes; DXT5: one 4x4 block = 16 bytes (rects are block-aligned).
          const uint32_t w = Be16(&cur[0x0C]), fmt = Be32(&cur[0x14]) & 0x3F;
          const uint32_t cell = fmt == 0x14 ? 4 : 1, bpb = fmt == 0x14 ? 16 : 4, lg = fmt == 0x14 ? 4 : 2;
          const uint8_t* src = t.data;
          for (uint32_t r = 0; r < t.nrects; r++) {
            const uint16_t* q = &t.rects[r * 4];
            for (uint32_t yy = q[1] / cell; yy < (uint32_t(q[1]) + q[3]) / cell; yy++)
              for (uint32_t xx = q[0] / cell; xx < (uint32_t(q[0]) + q[2]) / cell; xx++, src += bpb)
                std::memcpy(&cur[0x2C + TiledOffset(xx, yy, w / cell, lg)], src, bpb);
          }
        }
        if (Fnv(cur) != t.neu) { skipped++; RUSUBS_LOG("RUSUBS: %s in %s differs, left alone", t.name, j.arc); continue; }
        repl.push_back({{x->name, x->type}, std::move(cur)});
      }
      for (const auto& f : kTexFixes) {
        if (std::strcmp(f.arc, j.arc)) continue;
        auto* x = a.Find(f.name);
        Bytes cur;
        if (!x || !a.Read(*x, cur) || cur.size() != f.tex_size) { skipped++; continue; }
        if (Fnv(cur) != f.orig) continue;          // already fixed by an earlier run (or not the expected texture)
        bool merged = false;
        for (auto& r : repl) if (r.first.first == x->name) { ApplyTexFix(r.second, f); merged = true; }
        if (!merged) { ApplyTexFix(cur, f); repl.push_back({{x->name, x->type}, std::move(cur)}); }
      }
    }
    if (repl.empty()) continue;
    int r = 0;
    if (RepackArc(p, repl, r)) { done += r; arcs++; }
    else { failed++; RUSUBS_LOG("RUSUBS: rebuild failed: %s", j.arc); }
  }
  // Cutscenes (rom/demo, streamed by entry): text + timing table, appended in place.
  for (const auto& s : kScenes) {
    Arc a;
    if (!a.Open(nx / s.msg_arc)) { failed++; RUSUBS_LOG("RUSUBS: can't open %s", s.msg_arc); continue; }
    Bytes cur;
    auto* m = a.Find(s.msg_name);
    if (!m || !a.Read(*m, cur)) { failed++; continue; }
    const uint64_t hm = Fnv(cur);
    // Entries already ours are written again: data version changed (or an older stream format).
    if (hm == s.msg_new || hm == s.msg_orig || IsOurMsg(cur)) {
      Bytes msg(s.msg, s.msg + s.msg_size);
      if (a.Replace(*m, msg)) done++; else failed++;
    } else { skipped++; RUSUBS_LOG("RUSUBS: %s differs from the Russian edition, left alone", s.msg_name); }

    Arc b; Arc* ab = &a;
    if (std::strcmp(s.sdl_arc, s.msg_arc)) { if (!b.Open(nx / s.sdl_arc)) { failed++; continue; } ab = &b; }
    auto* t = ab->Find(s.sdl_name);
    if (!t || !ab->Read(*t, cur)) { failed++; continue; }
    // Rebuilt from the player's table (the original, or ours from an earlier version): only the
    // key arrays change, so the result must match exactly.
    Bytes nt;
    if (!SdlWrite(cur, s.keys_t, s.keys_v, s.nkeys, nt) || Fnv(nt) != s.sdl_new) {
      skipped++; RUSUBS_LOG("RUSUBS: %s differs from the original, left alone", s.sdl_name); continue;
    }
    if (ab->Replace(*t, nt)) done++; else failed++;
  }
  RUSUBS_LOG("RUSUBS: %d replaced (%d archives rebuilt), %d left alone, %d failed", done, arcs, skipped, failed);
  if (failed == 0) {
    if (std::FILE* m = std::fopen(Marker(game_root).string().c_str(), "wb")) {
      std::fwrite(want.data(), 1, want.size(), m); std::fclose(m);
    }
  }
  return failed == 0;
}

#if defined(DMC4_RU_SUBS_TEST)
int main(int argc, char** argv) {
  if (argc < 2) { std::printf("usage: %s <game root with nativeXenon/>\n", argv[0]); return 2; }
  return Dmc4ApplyRuSubtitles(argv[1]) ? 0 : 1;
}
#endif
