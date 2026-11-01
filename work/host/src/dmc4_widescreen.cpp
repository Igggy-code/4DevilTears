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
#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <set>
#include <string>
#include <fmt/format.h>

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
void Dmc4RenderScaleTick();
void Dmc4WideAspect(PPCRegister& f0, PPCRegister& r30, PPCRegister& r31) {
  Dmc4RenderScaleTick();
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

// ---- Internal render resolution (performance option) ----------------------------------------
// The render device (sub_8246DBF8, MT Framework) picks its screen size from fixed modes
// (1920x1080 / 1280x720 / 640x480) and keeps it at +80 / +84; the scene, depth and
// post-processing buffers are made from it afterwards. A lower size there makes the game
// itself draw fewer pixels; the presenter stretches the frame to the screen.
REXCVAR_DEFINE_INT32(dmc4_render_scale, 100, "4DevilTears",
                     "Internal render resolution in percent of 1280x720 (100 = native; 80 / 75 / "
                     "70 for weaker phones). Takes effect at the next start.");

// The game itself only renders correctly in 720p everywhere it has the HUD on (gameplay):
// cut-scenes (4x MSAA, tiling), movies, menus and their depth-of-field backgrounds mix the
// device size with fixed 1280x720 buffers. So the lower size is applied only while the
// gameplay HUD is drawn (for 0.5 s at least) and dropped as soon as it disappears.
extern std::atomic<int64_t> g_cock_update_ms;  // dmc4_menu_hooks.cpp: last gameplay HUD update
namespace {
uint8_t* g_rs_base = nullptr;
uint32_t g_rs_dev = 0, g_rs_w = 0, g_rs_h = 0;
bool g_rs_reduced = false;
int64_t g_rs_hud_since = -1;
void RsStore(uint8_t* p, uint32_t v) {
  p[0] = uint8_t(v >> 24); p[1] = uint8_t(v >> 16); p[2] = uint8_t(v >> 8); p[3] = uint8_t(v);
}
}  // namespace

// ---- engine graphics options (the PC version's config items, also in this build) -----------
// sShader singleton: global 0x82B85380 (lis -32072; lwz 21376). Fields (sShader ctor sub_82458E18,
// config items registered there): +26840 MotionBlurQuality (HIGH 0 / MEDIUM 1 / LOW 2 / NONE 3,
// default 1), +26860 TextureDetail (HIGH 0 / MEDIUM 1 / LOW 2, default 0), +26868/+26872
// Lighting (setter sub_8244E820: HIGH 4 / MEDIUM 2 / LOW 1, default 4). -1 = leave the game's.
REXCVAR_DEFINE_INT32(dmc4_motion_blur, -1, "4DevilTears",
                     "Motion blur quality: -1 game default, 0 high, 1 medium, 2 low, 3 none");
REXCVAR_DEFINE_INT32(dmc4_texture_detail, -1, "4DevilTears",
                     "Texture detail: -1 game default, 0 high, 1 medium, 2 low");
REXCVAR_DEFINE_INT32(dmc4_lighting, -1, "4DevilTears",
                     "Lighting quality: -1 game default, 0 high, 1 medium, 2 low");
// Bloom / ghost (lens flare) / star glare of the tone-map filter uToneMapFilter (vtable 0x820CC56C,
// ctor sub_82431388). Its draw sub_8243AAB8 (r3 = filter) runs those passes when bits 0x10 (bloom),
// 0x20 (ghost) and 0x40 (star) of +32 are set - the same bits the game clears for glare type None.
// Off: the bits are cleared for that one call only and restored after, so the filter's own state
// is never changed.
REXCVAR_DEFINE_BOOL(dmc4_glare, true, "4DevilTears", "Bloom / glare passes of the tone-map filter");
extern "C" void __imp__sub_8243AAB8(PPCContext& __restrict ctx, uint8_t* base);
extern "C" void sub_8243AAB8(PPCContext& __restrict ctx, uint8_t* base) {
  const uint32_t self = ctx.r3.u32;
  if (REXCVAR_GET(dmc4_glare) || !self || self >= 0xF0000000u) {
    __imp__sub_8243AAB8(ctx, base);
    return;
  }
  uint8_t* f = base + self + 32;
  const uint32_t flags = uint32_t(LoadBe32(f));
  const uint32_t off = flags & ~0x70u;
  f[0] = uint8_t(off >> 24); f[1] = uint8_t(off >> 16); f[2] = uint8_t(off >> 8); f[3] = uint8_t(off);
  __imp__sub_8243AAB8(ctx, base);
  f[0] = uint8_t(flags >> 24); f[1] = uint8_t(flags >> 16); f[2] = uint8_t(flags >> 8);
  f[3] = uint8_t(flags);
}

// Scene anti-aliasing of the engine: sRender (global 0x82B84F50, ctor sub_8246DBF8) +0x11AC8,
// property "AntiAliasing" (getter sub_82464938, setter sub_82464990: 0 none, 1..8 D3D multisample
// types, 10 special). The scene renderer sub_8245BC78 reads it every frame. Our renderer already
// draws the 2x/4x MSAA surfaces at 1x, so the game's MSAA only costs the predicated tiling bands
// (a 2x 1280x720 colour+depth frame doesn't fit the 10 MB EDRAM). Test: -1 game, 0 force none.
REXCVAR_DEFINE_INT32(dmc4_scene_aa, -1, "4DevilTears",
                     "Test: engine scene anti-aliasing: -1 game default, 0 none (one band)");

// D3DDevice_BeginTiling (sub_82167B08: r3 device, r4 flags, r5 tile count, r6 D3DRECT array):
// log each new band layout once, to see whether the scene still renders in bands.
extern "C" void __imp__sub_82167B08(PPCContext& __restrict ctx, uint8_t* base);
extern "C" void sub_82167B08(PPCContext& __restrict ctx, uint8_t* base) {
  const uint32_t count = ctx.r5.u32;
  const uint32_t rects = ctx.r6.u32;
  if (count && count <= 16 && rects && rects < 0xF0000000u) {
    static std::mutex m;
    static std::set<std::string> seen;
    std::string key = fmt::format("{}", count);
    for (uint32_t i = 0; i < count; ++i) {
      const uint8_t* r = base + rects + i * 16;
      key += fmt::format(" [{},{}-{},{}]", LoadBe32(r), LoadBe32(r + 4), LoadBe32(r + 8),
                         LoadBe32(r + 12));
    }
    std::lock_guard<std::mutex> lock(m);
    if (seen.size() < 64 && seen.insert(key).second) {
      REXKRNL_INFO("TILING: bands {} flags {:X}", key, ctx.r4.u32);
    }
  }
  __imp__sub_82167B08(ctx, base);
}

namespace {
void Dmc4SceneAaTick(uint8_t* base) {
  const uint32_t render = uint32_t(LoadBe32(base + 0x82B84F50u));
  if (!render || render >= 0xF0000000u) return;
  uint8_t* aa = base + render + 0x11AC8u;
  const int32_t game = LoadBe32(aa);
  static int32_t last_game = -2;
  const int32_t want = REXCVAR_GET(dmc4_scene_aa);
  if (game != last_game && !(want >= 0 && game == want)) {
    REXKRNL_INFO("SCENE AA: game value {} (forced {})", game, want);
    last_game = game;
  }
  if (want >= 0 && want <= 10 && game != want) {
    aa[0] = uint8_t(uint32_t(want) >> 24); aa[1] = uint8_t(uint32_t(want) >> 16);
    aa[2] = uint8_t(uint32_t(want) >> 8); aa[3] = uint8_t(want);
  }
}

void Dmc4QualityTick(uint8_t* base) {
  if (!base) return;
  Dmc4SceneAaTick(base);
  const uint32_t shader = uint32_t(LoadBe32(base + 0x82B85380u));
  if (!shader || shader >= 0xF0000000u) return;
  uint8_t* sh = base + shader;
  auto put = [](uint8_t* p, uint32_t v) {
    p[0] = uint8_t(v >> 24); p[1] = uint8_t(v >> 16); p[2] = uint8_t(v >> 8); p[3] = uint8_t(v);
  };
  const int32_t mb = REXCVAR_GET(dmc4_motion_blur);
  if (mb >= 0 && mb <= 3 && LoadBe32(sh + 26840) != mb) put(sh + 26840, uint32_t(mb));
  const int32_t td = REXCVAR_GET(dmc4_texture_detail);
  if (td >= 0 && td <= 2 && LoadBe32(sh + 26860) != td) put(sh + 26860, uint32_t(td));
  const int32_t li = REXCVAR_GET(dmc4_lighting);
  if (li >= 0 && li <= 2) {
    static const uint32_t kLight[3] = {4, 2, 1};
    if (uint32_t(LoadBe32(sh + 26868)) != kLight[li]) {
      put(sh + 26868, kLight[li]);
      put(sh + 26872, kLight[li]);
    }
  }
  // Log every change of the settings (and the first state), so a toggle shows in the log.
  static int32_t last_mb = -2, last_td = -2, last_li = -2, last_gl = -2;
  const int32_t gl = REXCVAR_GET(dmc4_glare) ? 1 : 0;
  if (mb != last_mb || td != last_td || li != last_li || gl != last_gl) {
    last_mb = mb; last_td = td; last_li = li; last_gl = gl;
    REXKRNL_INFO("QUALITY: motion blur {} texture detail {} lighting {} glare {} (sShader {:08X})", mb,
                 td, li, gl, shader);
  }
}
}  // namespace

// Game thread, several times per frame (viewport updates, 2D sprites).
void Dmc4RenderScaleTick() {
  Dmc4QualityTick(g_rs_base ? g_rs_base : g_base);
  if (!g_rs_dev || !g_rs_base) return;
  static std::atomic<bool> busy{false};  // called from several game threads
  if (busy.exchange(true, std::memory_order_acquire)) return;
  struct Release { std::atomic<bool>& b; ~Release() { b.store(false, std::memory_order_release); } } rel{busy};
  const int64_t now = std::chrono::duration_cast<std::chrono::milliseconds>(
                          std::chrono::steady_clock::now().time_since_epoch())
                          .count();
  const bool hud = now - g_cock_update_ms.load() < 300;
  if (!hud) g_rs_hud_since = -1;
  else if (g_rs_hud_since < 0) g_rs_hud_since = now;
  const bool want = hud && now - g_rs_hud_since >= 500;
  if (want == g_rs_reduced) return;
  g_rs_reduced = want;
  uint8_t* dev = g_rs_base + g_rs_dev;
  RsStore(dev + 80, want ? g_rs_w : 1280);
  RsStore(dev + 84, want ? g_rs_h : 720);
  // The reduced frame lands in the top-left corner of the 1280x720 front buffer.
  rex::cvar::SetFlagByName("present_source_width", want ? std::to_string(g_rs_w) : "0");
  rex::cvar::SetFlagByName("present_source_height", want ? std::to_string(g_rs_h) : "0");
  REXKRNL_INFO("RENDER SCALE: {}", want ? "gameplay - reduced" : "full 1280x720");
}

extern "C" void __imp__sub_8246DBF8(PPCContext& __restrict ctx, uint8_t* base);
extern "C" void sub_8246DBF8(PPCContext& __restrict ctx, uint8_t* base) {
  const uint32_t self = ctx.r3.u32;
  __imp__sub_8246DBF8(ctx, base);
  const int32_t pct = REXCVAR_GET(dmc4_render_scale);
  if (pct >= 100 || pct < 50 || !self) return;
  uint8_t* dev = base + self;
  const int32_t w = LoadBe32(dev + 80), h = LoadBe32(dev + 84);
  if (w != 1280 || h != 720) {
    REXKRNL_INFO("RENDER SCALE: device screen {}x{} - not 720p, left as is", w, h);
    return;
  }
  // Width a multiple of 32, height a multiple of 16 (the game rounds its viewport up to 16
  // rows anyway): 1024x576, 960x544, 896x512.
  g_rs_w = (uint32_t(1280 * pct / 100) + 16) & ~31u;
  g_rs_h = (g_rs_w * 9 / 16 + 15) & ~15u;
  g_rs_base = base;
  g_rs_dev = self;
  REXKRNL_INFO("RENDER SCALE: {}x{} ({}%) in gameplay", g_rs_w, g_rs_h, pct);
}

// ---------------------------------------------------------------------------------------------
// Frame pacing (see project doc FRAMERATE.md). sMain timer update sub_82473980 (r3 = sMain):
// byte +72 != 0 = fixed step (the 360 game's choice): every frame is exactly 1/60 s of game time,
// so below 60 FPS the game runs in slow motion. +72 == 0 = the engine's variable step (as on PC):
// game time follows the real clock (delta = elapsed * 60, capped at 16 frames), frames are
// limited to mMaxFps (+68, 120). On by default (false = the console's fixed step).
REXCVAR_DEFINE_BOOL(dmc4_variable_timestep, true, "4DevilTears",
                    "Game speed follows real time (engine variable step) instead of 1/60 s per frame");
REXCVAR_DEFINE_INT32(dmc4_timestep_smooth, 4, "4DevilTears",
                     "Variable step: average the game step over N frames (1 = raw)");
REXCVAR_DEFINE_DOUBLE(dmc4_timestep_max, 2.0, "4DevilTears",
                      "Variable step: largest step in 60 Hz frames (2 = slow motion only below 30 FPS)");
REXCVAR_DEFINE_INT32(dmc4_max_fps, 0, "4DevilTears",
                     "Frame cap of the engine variable step (0 = game default 120)");

extern "C" void __imp__sub_82473980(PPCContext& __restrict ctx, uint8_t* base);
extern "C" void sub_82473980(PPCContext& __restrict ctx, uint8_t* base) {
  const uint32_t self = ctx.r3.u32;
  static int saved_fixed = -1;  // the game's own flag, given back when the switch goes off
  if (self && !REXCVAR_GET(dmc4_variable_timestep) && saved_fixed >= 0) {
    base[self + 72] = uint8_t(saved_fixed);
    saved_fixed = -1;
  }
  if (self && REXCVAR_GET(dmc4_variable_timestep)) {
    uint8_t* m = base + self;
    if (m[72] != 0) saved_fixed = m[72];
    static bool logged = false;
    if (!logged) {
      logged = true;
      REXKRNL_INFO("TIMESTEP: variable (fixed flag was {}), mFps {} mMaxFps {}", int(m[72]),
                   LoadBeFloat(m + 64), LoadBeFloat(m + 68));
    }
    m[72] = 0;
    const int32_t cap = REXCVAR_GET(dmc4_max_fps);
    if (cap >= 30 && cap <= 240) StoreBeFloat(m + 68, float(cap));
  }
  __imp__sub_82473980(ctx, base);
  // Uneven frame times on a phone (25 ms, 40 ms, 22 ms...) feed the cloth / chain physics
  // (coats, hair) a jumpy step and they shake. Average the step over the last frames and keep
  // it within 2 frames of 60 Hz; the average still follows real time.
  if (self && REXCVAR_GET(dmc4_variable_timestep) && base[self + 72] == 0) {
    const int32_t n = std::clamp(REXCVAR_GET(dmc4_timestep_smooth), 1, 16);
    const float limit = std::max(1.0f, float(REXCVAR_GET(dmc4_timestep_max)));
    static float hist[16];
    static int pos = 0, filled = 0;
    uint8_t* m = base + self;
    float d = LoadBeFloat(m + 112);
    if (d > 0.0f && d < 16.0f) {
      hist[pos] = d;
      pos = (pos + 1) % n;
      filled = std::min(filled + 1, n);
      float sum = 0.0f;
      for (int i = 0; i < filled; ++i) sum += hist[i];
      d = std::min(sum / float(filled), limit);
      StoreBeFloat(m + 112, d);
    }
  }
}
