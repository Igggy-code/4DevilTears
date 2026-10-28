// 4DevilTears: full-screen picture on phone screens (wider than 16:9) without distortion.
//
// The game keeps rendering into its 1280x720 targets; the presenter stretches that image to
// the whole screen (present_letterbox=false), and everything drawn in it is made narrower by
// the same factor, so after the stretch the proportions are right again:
//
//  * 3D (gameplay and in-engine cutscenes). Every frame sub_8245B028 walks the scene
//    viewports and writes camera->mAspect = viewport width / height (stfs f0,32(r30) at
//    0x8245B254), then builds the projection through the camera's vtable
//    (sub_828AD1B8: m00 = 1/(aspect*tan(fovY/2)), m11 = 1/tan(fovY/2)). mFov is vertical, so a
//    wider aspect shows more to the sides (Hor+) and keeps the vertical view of the original;
//    the frustum culling planes are derived from the same matrix right after.
//    Hook: [[midasm_hook]] Dmc4WideAspect before that store (dmc4_manifest.toml).
//    Only screen-wide views (16:9 and wider, e.g. letterboxed cinema) are touched;
//    render-to-texture / square ones keep their aspect.
//
//  * Movies (.wmv). sub_82186810 fits the movie quad into the screen using
//    movie w/h (+132/+136) and a pixel-aspect factor (+140). The factor is multiplied by the
//    stretch while the quad is built, so the movie stays 16:9 with bars at the sides.
//    The quad is 6 vertices of 20 bytes from +144 (x, y first), rect ints at +96..+108.
//    With dmc4_hide_first_movie the first movie of the session (the intro, which the app
//    skips) gets an empty quad, so not even a frame of it shows.
//
//  * Cutscene bars. uCinemaScope (vtable 0x8208F7D8, "Start Flag" at +52) draws two black
//    1280-wide bands over the full-screen view during in-engine cutscenes (draw = vtable[9],
//    sub_827B29A8). With the wider camera the picture already has the cinema shape on a
//    phone, so the bands are not drawn.
//
// dmc4_wide_aspect = width/height of the phone screen (set by DevilActivity); 0 = off.
#include <cmath>
#include <cstdint>
#include <cstring>

#include <rex/cvar.h>
#include <rex/logging.h>
#include <rex/ppc/context.h>
#include <rex/ppc/func.h>

REXCVAR_DEFINE_DOUBLE(dmc4_wide_aspect, 0.0, "4DevilTears",
                      "Screen aspect (width/height) the 16:9 image is stretched to; 0 = off");
REXCVAR_DEFINE_BOOL(dmc4_hide_first_movie, false, "4DevilTears",
                    "Draw nothing of the first movie of the session (the skipped intro)");

namespace {
constexpr double kNative = 16.0 / 9.0;

// How much wider than 16:9 the screen is (1 = no change).
double WideFactor() {
  double a = REXCVAR_GET(dmc4_wide_aspect);
  if (!(a > kNative * 1.005) || a > 4.0) return 1.0;
  return a / kNative;
}

float LoadBeFloat(const uint8_t* p) {
  uint32_t u = (uint32_t(p[0]) << 24) | (uint32_t(p[1]) << 16) | (uint32_t(p[2]) << 8) | p[3];
  float f;
  std::memcpy(&f, &u, 4);
  return f;
}
void StoreBeFloat(uint8_t* p, float f) {
  uint32_t u;
  std::memcpy(&u, &f, 4);
  p[0] = uint8_t(u >> 24);
  p[1] = uint8_t(u >> 16);
  p[2] = uint8_t(u >> 8);
  p[3] = uint8_t(u);
}

uint8_t* g_base = nullptr;  // guest memory, noted by the viewport update below

int32_t LoadBe32(const uint8_t* p) {
  return int32_t((uint32_t(p[0]) << 24) | (uint32_t(p[1]) << 16) | (uint32_t(p[2]) << 8) | p[3]);
}

// Diagnostics: each distinct viewport rectangle once.
struct SeenRect { int32_t l, t, r, b; };
SeenRect g_seen[64];
int g_seen_count = 0;
}  // namespace

// Called with f0 = viewport width / height, just before it becomes the camera's mAspect.
// r30 = camera, r31 -> viewport rectangle (left at -4, top at 0, right at +4, bottom at +8).
void Dmc4WideAspect(PPCRegister& f0, PPCRegister& r30, PPCRegister& r31) {
  const double k = WideFactor();
  const double aspect = f0.f64;
  // Screen-wide 3D views: 16:9, and letterboxed cinema views (wider than 16:9).
  // Render-to-texture views (square, 4:3, tall) keep their aspect.
  const bool wide_view = aspect > kNative - 0.02 && aspect < 3.2;
  if (g_base && g_seen_count < 64) {
    const uint8_t* rc = g_base + r31.u32;
    SeenRect s{LoadBe32(rc - 4), LoadBe32(rc), LoadBe32(rc + 4), LoadBe32(rc + 8)};
    bool known = false;
    for (int i = 0; i < g_seen_count; i++) {
      const SeenRect& o = g_seen[i];
      if (o.l == s.l && o.t == s.t && o.r == s.r && o.b == s.b) { known = true; break; }
    }
    if (!known) {
      g_seen[g_seen_count++] = s;
      const float fov = LoadBeFloat(g_base + r30.u32 + 36);
      REXKRNL_INFO("WIDE viewport ({},{})-({},{}) aspect {:.4f} fov {:.2f} -> {}", s.l, s.t, s.r,
                   s.b, aspect, fov, (k != 1.0 && wide_view) ? aspect * k : aspect);
    }
  }
  if (k == 1.0 || !wide_view) return;
  f0.f64 = double(float(aspect * k));
}

// The scene viewport update itself: only notes where guest memory is for the hook above.
extern "C" void __imp__sub_8245B028(PPCContext& __restrict ctx, uint8_t* base);
extern "C" void sub_8245B028(PPCContext& __restrict ctx, uint8_t* base) {
  g_base = base;
  __imp__sub_8245B028(ctx, base);
}

// Movie quad layout (see the header comment).
extern "C" void __imp__sub_82186810(PPCContext& __restrict ctx, uint8_t* base);
extern "C" void sub_82186810(PPCContext& __restrict ctx, uint8_t* base) {
  const double k = WideFactor();
  static bool first_movie_done = false;
  const bool hide = REXCVAR_GET(dmc4_hide_first_movie) && !first_movie_done;
  first_movie_done = true;
  if (k == 1.0 && !hide) {
    __imp__sub_82186810(ctx, base);
    return;
  }
  // r3 = the movie object; read it now, the call returns its result in r3.
  uint8_t* const m = base + ctx.r3.u32;
  uint8_t* par = m + 140;
  const float saved = LoadBeFloat(par);
  StoreBeFloat(par, float(saved * k));
  __imp__sub_82186810(ctx, base);
  StoreBeFloat(par, saved);
  if (hide) {
    for (uint32_t v = 0; v < 6; v++) {
      StoreBeFloat(m + 144 + v * 20, 0.0f);
      StoreBeFloat(m + 148 + v * 20, 0.0f);
    }
    REXKRNL_INFO("WIDE first movie hidden (intro skip)");
  }
  static float logged = 0.0f;
  if (logged != saved) {
    logged = saved;
    REXKRNL_INFO("WIDE movie {}x{} on {}x{}: pixel aspect {:.4f} -> {:.4f}, quad ({},{})-({},{})",
                 LoadBeFloat(m + 132), LoadBeFloat(m + 136), LoadBe32(m + 124), LoadBe32(m + 128),
                 saved, saved * k, LoadBe32(m + 96), LoadBe32(m + 100), LoadBe32(m + 104),
                 LoadBe32(m + 108));
  }
}

// uCinemaScope draw: no cinema bands while the wide camera is on.
extern "C" void __imp__sub_827B29A8(PPCContext& __restrict ctx, uint8_t* base);
extern "C" void sub_827B29A8(PPCContext& __restrict ctx, uint8_t* base) {
  if (WideFactor() != 1.0) return;
  __imp__sub_827B29A8(ctx, base);
}
