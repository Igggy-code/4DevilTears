// 4DevilTears: D3D call tracer (research for direct rendering).
//
// MT Framework's render command executor (sub_8246A5A0) drives the game's built-in Xbox 360
// Direct3D library through these functions (all called from it, all in the D3D code range
// 0x8215xxxx-0x8216xxxx). Known so far:
//   sub_821690F8  D3DDevice_Resolve (flags, src rect, dest texture, dest point, level, slice,
//                 clear color, clear Z (f1), clear stencil, params)
//   sub_82167FA0  end of predicated tiling: one Resolve per tile (tile count at +12740)
//   sub_82161DA8 / sub_821663F0  GPU wait loop (dmc4_perf.cpp)
// With dmc4_d3d_trace = N > 0 the next N calls are logged (D3DT lines: function, r4..r8, f1,
// result) and the counter is decremented to 0; set it at runtime (files/flags_now.txt).
#include <atomic>
#include <cstdint>

#include <rex/cvar.h>
#include <rex/logging.h>
#include <rex/ppc/context.h>
#include <rex/ppc/func.h>

REXCVAR_DEFINE_BOOL(dmc4_surface_map, true, "4DevilTears",
                    "Log each distinct D3D surface the game describes (GetDesc), once (research)");
REXCVAR_DEFINE_INT32(dmc4_d3d_trace, 0, "4DevilTears",
                     "Log the next N calls of the game's D3D functions (research)");

namespace {
std::atomic<uint64_t> g_seq{0};
inline bool Want() {
  int32_t& n = FLAGS_dmc4_d3d_trace_storage_();
  if (n <= 0) return false;
  --n;
  return true;
}
}  // namespace

#define DMC4_D3D_TRACE(ADDR)                                                                  \
  extern "C" void __imp__sub_##ADDR(PPCContext& __restrict ctx, uint8_t* base);               \
  extern "C" void sub_##ADDR(PPCContext& __restrict ctx, uint8_t* base) {                     \
    if (!Want()) {                                                                            \
      __imp__sub_##ADDR(ctx, base);                                                           \
      return;                                                                                 \
    }                                                                                         \
    uint32_t r4 = ctx.r4.u32, r5 = ctx.r5.u32, r6 = ctx.r6.u32, r7 = ctx.r7.u32,             \
             r8 = ctx.r8.u32;                                                                 \
    double f1 = ctx.f1.f64;                                                                   \
    uint32_t lr = uint32_t(ctx.lr);                                                           \
    __imp__sub_##ADDR(ctx, base);                                                             \
    REXKRNL_INFO("D3DT {} {:08X} r4={:08X} r5={:08X} r6={:08X} r7={:08X} r8={:08X} f1={} "   \
                 "lr={:08X} -> {:08X}",                                                       \
                 g_seq.fetch_add(1), 0x##ADDR, r4, r5, r6, r7, r8, f1, lr, ctx.r3.u32);       \
  }

DMC4_D3D_TRACE(8215C8E0)
DMC4_D3D_TRACE(8215C940)
DMC4_D3D_TRACE(8215CDA0)
DMC4_D3D_TRACE(8215CE08)
DMC4_D3D_TRACE(8215D030)
DMC4_D3D_TRACE(8215D070)
DMC4_D3D_TRACE(8215D0A0)
DMC4_D3D_TRACE(8215D3B0)
DMC4_D3D_TRACE(8215D578)
DMC4_D3D_TRACE(8215D5C8)
DMC4_D3D_TRACE(8215D6C8)
DMC4_D3D_TRACE(8215DB98)
DMC4_D3D_TRACE(8215EC18)
DMC4_D3D_TRACE(8215ED18)
DMC4_D3D_TRACE(8215EEC0)
DMC4_D3D_TRACE(8215F0E0)
DMC4_D3D_TRACE(8215F130)
DMC4_D3D_TRACE(8215F4E8)
DMC4_D3D_TRACE(8215F4F8)
DMC4_D3D_TRACE(8215F998)
DMC4_D3D_TRACE(8215FC70)
DMC4_D3D_TRACE(82160800)
DMC4_D3D_TRACE(821608E8)
DMC4_D3D_TRACE(821609D0)
DMC4_D3D_TRACE(82160A30)
DMC4_D3D_TRACE(82160A90)
DMC4_D3D_TRACE(82160AE8)
DMC4_D3D_TRACE(82160BC8)
DMC4_D3D_TRACE(82160E80)
DMC4_D3D_TRACE(82161050)
DMC4_D3D_TRACE(821611E0)
DMC4_D3D_TRACE(82161F28)
DMC4_D3D_TRACE(82163E18)
DMC4_D3D_TRACE(82164E10)
DMC4_D3D_TRACE(821651F8)
DMC4_D3D_TRACE(82167300)
DMC4_D3D_TRACE(821677F8)
DMC4_D3D_TRACE(82167998)
DMC4_D3D_TRACE(82167B08)
DMC4_D3D_TRACE(82167FA0)
DMC4_D3D_TRACE(82168460)
DMC4_D3D_TRACE(821687A8)
DMC4_D3D_TRACE(821690F8)
DMC4_D3D_TRACE(8216B088)
DMC4_D3D_TRACE(8216B1B0)

// sub_82163D38 = D3DSurface_GetDesc(surface r3, desc r4): Format (+0) <- surface+40,
// Type (+4) = 4, MultiSample (+16) <- low 2 bits of halfword +24, Width (+24) / Height (+28)
// <- size word +36 (bits 0..12 width-1, 13..25 height-1). Surfaces with bit 1 of word +0 set
// are texture levels (described through sub_82162C80).
// dmc4_surface_map logs each distinct surface once with its first 48 bytes - the game's own
// render target list (direct rendering stage 3).
namespace {
uint32_t LoadBe32(const uint8_t* p) {
  return (uint32_t(p[0]) << 24) | (uint32_t(p[1]) << 16) | (uint32_t(p[2]) << 8) | p[3];
}
struct SeenSurface { uint32_t ptr, words[12]; };
SeenSurface g_surfaces[96];
int g_surface_count = 0;
}  // namespace

extern "C" void __imp__sub_82163D38(PPCContext& __restrict ctx, uint8_t* base);
extern "C" void sub_82163D38(PPCContext& __restrict ctx, uint8_t* base) {
  const uint32_t surface = ctx.r3.u32, desc = ctx.r4.u32;
  const bool trace = Want();
  const uint32_t lr = uint32_t(ctx.lr);
  __imp__sub_82163D38(ctx, base);
  if (trace) {
    REXKRNL_INFO("D3DT {} 82163D38 surface={:08X} desc={:08X} lr={:08X}", g_seq.fetch_add(1),
                 surface, desc, lr);
  }
  if (!REXCVAR_GET(dmc4_surface_map) || !surface || !desc || g_surface_count >= 96) return;
  SeenSurface s{surface, {}};
  for (int i = 0; i < 12; i++) s.words[i] = LoadBe32(base + surface + i * 4);
  for (int i = 0; i < g_surface_count; i++) {
    if (g_surfaces[i].ptr != s.ptr) continue;
    bool same = true;
    for (int w = 0; w < 12; w++) same = same && g_surfaces[i].words[w] == s.words[w];
    if (same) return;
  }
  g_surfaces[g_surface_count++] = s;
  const uint8_t* d = base + desc;
  REXKRNL_INFO("SURF {:08X} {}x{} format {:08X} msaa {} lr {:08X} | {:08X} {:08X} {:08X} {:08X} "
               "{:08X} {:08X} {:08X} {:08X} {:08X} {:08X} {:08X} {:08X}",
               surface, LoadBe32(d + 24), LoadBe32(d + 28), LoadBe32(d), LoadBe32(d + 16), lr,
               s.words[0], s.words[1], s.words[2], s.words[3], s.words[4], s.words[5], s.words[6],
               s.words[7], s.words[8], s.words[9], s.words[10], s.words[11]);
}
