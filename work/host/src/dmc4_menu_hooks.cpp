// 4DevilTears: hooks into the recompiled game code for touch-friendly menus.
//
// Recompiled functions are weak symbols with the original body in __imp__sub_XXXXXXXX, so a
// hook here runs first and then calls the game's own code unchanged.
//
// uTitle (MT Framework unit of the title screen, DTI "uTitle", 0x214 bytes):
//   ctor sub_8249D300, vtable 0x820BF218, vtable[19] = sub_8249D7F8 draws it every frame.
//   byte +23 = screen state (0..10), byte +22 = sub-state.
// sub_82784BD8 draws a 2D sprite: r5 -> {x, y}, r6 -> {w, h} (1280x720 space).
#include <atomic>
#include <dlfcn.h>
#include <chrono>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <string>
#include <unordered_set>
#include <vector>

#include <rex/cvar.h>

#include <rex/logging.h>
#include <rex/ppc/context.h>
#include <rex/ppc/func.h>

#if defined(__ANDROID__)
#include <jni.h>
#endif

REXCVAR_DEFINE_BOOL(dmc4_ui_capture, false, "4DevilTears",
                    "Log the 2D sprites of every distinct UI screen once (touch layout research)");

namespace {
std::atomic<int64_t> g_title_seen_ms{0};
std::atomic<uint32_t> g_title_state{0};
std::atomic<int> g_sprite_log_budget{0};
std::atomic<bool> g_in_title_draw{false};

// Field discovery: bytes of the uTitle object that change rarely (cursor, selections) are
// logged; offsets that keep changing (timers, animation) go quiet after a few changes.
constexpr uint32_t kTitleSize = 0x214;
uint8_t g_title_prev[kTitleSize];
uint16_t g_title_changes[kTitleSize];
uint32_t g_title_prev_self = 0;

int64_t NowMs() {
  return std::chrono::duration_cast<std::chrono::milliseconds>(
             std::chrono::steady_clock::now().time_since_epoch())
      .count();
}

uint32_t Be32(const uint8_t* p) {
  return (uint32_t(p[0]) << 24) | (uint32_t(p[1]) << 16) | (uint32_t(p[2]) << 8) | p[3];
}
}  // namespace

void Dmc4NoteBase(uint8_t* base);
extern "C" void __imp__sub_8249D7F8(PPCContext& __restrict ctx, uint8_t* base);
extern "C" void sub_8249D7F8(PPCContext& __restrict ctx, uint8_t* base) {
  const uint32_t self = ctx.r3.u32;
  const uint32_t state = (uint32_t(base[self + 23]) << 8) | base[self + 22];
  if (g_title_state.exchange(state) != state) {
    REXKRNL_INFO("TITLE state {} sub {} (this {:08X})", state >> 8, state & 0xFF, self);
    g_sprite_log_budget.store(0);  // full-screen fades only - not useful
  }
  g_title_seen_ms.store(NowMs());
  Dmc4NoteBase(base);
  if (self != g_title_prev_self) {
    g_title_prev_self = self;
    std::memcpy(g_title_prev, base + self, kTitleSize);
    std::memset(g_title_changes, 0, sizeof(g_title_changes));
  } else {
    const uint8_t* cur = base + self;
    for (uint32_t o = 24; o < kTitleSize; ++o) {
      if (cur[o] == g_title_prev[o]) continue;
      if (++g_title_changes[o] <= 6) {
        REXKRNL_INFO("TITLE field +{} {} -> {} (state {} sub {})", o, g_title_prev[o], cur[o],
                     state >> 8, state & 0xFF);
      }
      g_title_prev[o] = cur[o];
    }
  }
  g_in_title_draw.store(true);
  __imp__sub_8249D7F8(ctx, base);
  g_in_title_draw.store(false);
}

// ---- UI capture (touch layout research) ------------------------------------------------------
// With dmc4_ui_capture on, every 2D sprite the game draws is collected; for each distinct UI
// screen (title state, list menu class, cursor) the sprites of ~0.5 s are logged once as
// "UISPR" lines: position / size in 1280x720 space, colour, and the call's other arguments.
// That is the game's own layout - touch areas are built from it, independent of the phone's
// screen size.
namespace {
struct UiSprite {
  int32_t x, y, w, h;
  uint32_t color, r3, r4, r8, r9;
};
std::mutex g_ui_mutex;
std::vector<UiSprite> g_ui_sprites;
uint64_t g_ui_sig = ~0ull;
int64_t g_ui_sig_since = 0;
bool g_ui_dumped = true;
std::unordered_set<uint64_t> g_ui_done;
}  // namespace

uint64_t Dmc4UiSignature();

void Dmc4UiCaptureSprite(PPCContext& ctx, uint8_t* base) {
  if (!REXCVAR_GET(dmc4_ui_capture)) return;
  const uint8_t* pos = base + ctx.r5.u32;
  const uint8_t* size = base + ctx.r6.u32;
  UiSprite s{int32_t(Be32(pos)), int32_t(Be32(pos + 4)), int32_t(Be32(size)), int32_t(Be32(size + 4)),
             ctx.r7.u32 ? Be32(base + ctx.r7.u32) : 0, ctx.r3.u32, ctx.r4.u32, ctx.r8.u32,
             ctx.r9.u32};
  const uint64_t sig = Dmc4UiSignature();
  const int64_t now = NowMs();
  std::lock_guard<std::mutex> lock(g_ui_mutex);
  if (sig != g_ui_sig) {
    g_ui_sig = sig;
    g_ui_sig_since = now;
    g_ui_sprites.clear();
    g_ui_dumped = g_ui_done.count(sig) != 0 || g_ui_done.size() > 300;
  }
  if (g_ui_dumped) return;
  if (now - g_ui_sig_since < 500) {
    if (g_ui_sprites.size() < 600) g_ui_sprites.push_back(s);
    return;
  }
  g_ui_dumped = true;
  g_ui_done.insert(sig);
  REXKRNL_INFO("UISCREEN sig {:016X} ({} sprites in 0.5 s)", sig, g_ui_sprites.size());
  std::unordered_set<std::string> seen;
  for (const UiSprite& u : g_ui_sprites) {
    std::string line = fmt::format("{},{} {}x{} c{:08X} r3 {:08X} r4 {:08X} r8 {} r9 {}", u.x, u.y,
                                   u.w, u.h, u.color, u.r3, u.r4, u.r8, u.r9);
    if (seen.insert(line).second) REXKRNL_INFO("UISPR {:016X} {}", sig, line);
  }
}

extern "C" void __imp__sub_82784BD8(PPCContext& __restrict ctx, uint8_t* base);
extern "C" void sub_82784BD8(PPCContext& __restrict ctx, uint8_t* base) {
  Dmc4UiCaptureSprite(ctx, base);
  if (g_in_title_draw.load() && g_sprite_log_budget.load() > 0) {
    g_sprite_log_budget.fetch_sub(1);
    const uint8_t* pos = base + ctx.r5.u32;
    const uint8_t* size = base + ctx.r6.u32;
    REXKRNL_INFO("TITLE sprite at {},{} size {}x{} color {:08X} prio {} r9 {}", int32_t(Be32(pos)),
                 int32_t(Be32(pos + 4)), int32_t(Be32(size)), int32_t(Be32(size + 4)),
                 Be32(base + ctx.r7.u32), ctx.r8.u32, ctx.r9.u32);
  }
  __imp__sub_82784BD8(ctx, base);
}

#if defined(__ANDROID__)
extern "C" {
// 0 - not in a known menu; otherwise 0x10000 | (title state << 8) | sub-state.
JNIEXPORT jint JNICALL Java_com_fourdeviltears_app_TouchGamepadView_nativeMenuState(JNIEnv*,
                                                                                   jclass) {
  if (NowMs() - g_title_seen_ms.load() > 300) return 0;
  return jint(0x10000 | g_title_state.load());
}
}
#endif

// ---- HUD diagnostics -----------------------------------------------------------------------
// uCockpit (the HUD: vitality, devil trigger, orbs), DTI 0x82B95E98-ish, vtable 0x8208FC08:
// vtable[6] = sub_827B0208 (update), vtable[19] = sub_827B0830 (draw). Logs every 2 s how
// often each runs and the unit's header, to see whether the HUD exists / is updated / drawn.
namespace {
std::atomic<uint32_t> g_cock_move{0}, g_cock_draw{0}, g_cock_self{0};
std::atomic<int64_t> g_cock_log_ms{0};
void CockpitReport(uint8_t* base) {
  int64_t now = NowMs();
  int64_t last = g_cock_log_ms.load();
  if (now - last < 2000 || !g_cock_log_ms.compare_exchange_strong(last, now)) return;
  uint32_t self = g_cock_self.load();
  if (!self) return;
  const uint8_t* p = base + self;
  REXKRNL_INFO("COCKPIT this {:08X} update {} draw {} hdr {:08X} {:08X} {:08X} {:08X} {:08X} {:08X}",
               self, g_cock_move.exchange(0), g_cock_draw.exchange(0), Be32(p + 4), Be32(p + 8),
               Be32(p + 12), Be32(p + 16), Be32(p + 20), Be32(p + 24));
}
}  // namespace

extern "C" void __imp__sub_827B0208(PPCContext& __restrict ctx, uint8_t* base);
extern "C" void sub_827B0208(PPCContext& __restrict ctx, uint8_t* base) {
  g_cock_self.store(ctx.r3.u32);
  g_cock_move.fetch_add(1);
  CockpitReport(base);
  __imp__sub_827B0208(ctx, base);
}

extern "C" void __imp__sub_827B0830(PPCContext& __restrict ctx, uint8_t* base);
std::atomic<int64_t> g_cock_draw_ms{0};
extern "C" void sub_827B0830(PPCContext& __restrict ctx, uint8_t* base) {
  g_cock_draw_ms.store(NowMs());
  g_cock_self.store(ctx.r3.u32);
  g_cock_draw.fetch_add(1);
  CockpitReport(base);
  __imp__sub_827B0830(ctx, base);
}

// ---- cutscene / movie state from the game's own units -----------------------------------
// uDemoCutCtrl (in-engine cutscene controller) vtable 0x8209062C, [6] = sub_82793370 (move).
// uDevil4Movie (pre-rendered movie player) vtable 0x820913E4, [6] = sub_828F95C0 (move).
// "Active" = its move ran in the last 400 ms. Replaces the file heuristic (a cutscene's
// soundtrack can stay open after the cutscene ends).
namespace {
std::atomic<int64_t> g_demo_ms{0}, g_movie_ms{0};
std::atomic<int> g_scene_logged{-1};
}  // namespace

int Dmc4SceneMode() {
  int64_t now = NowMs();
  int mode = (now - g_movie_ms.load() < 400) ? 2 : (now - g_demo_ms.load() < 400) ? 1 : 0;
  if (g_scene_logged.exchange(mode) != mode) REXKRNL_INFO("SCENE2 mode {}", mode);
  return mode;
}

extern "C" void __imp__sub_82793370(PPCContext& __restrict ctx, uint8_t* base);
extern "C" void sub_82793370(PPCContext& __restrict ctx, uint8_t* base) {
  g_demo_ms.store(NowMs());
  __imp__sub_82793370(ctx, base);
}

extern "C" void __imp__sub_828F95C0(PPCContext& __restrict ctx, uint8_t* base);
extern "C" void sub_828F95C0(PPCContext& __restrict ctx, uint8_t* base) {
  g_movie_ms.store(NowMs());
  __imp__sub_828F95C0(ctx, base);
}

// ---- generic menu discovery ---------------------------------------------------------------
// uListSelect::move (sub_825872D0) runs for every list-style menu derived from uListSelect
// (mission start, mission / library / save menus, pause, ...). For each active menu object:
// log when it appears / disappears (with its vtable = class) and the bytes of the object that
// change rarely - the cursor shows up as a small value going +1 / -1 on D-pad presses.
namespace {
struct MenuWatch {
  uint32_t self = 0, vtable = 0;
  int64_t seen_ms = 0;
  uint8_t prev[0x300];
  uint8_t changes[0x300];
};
std::mutex g_menu_mutex;
MenuWatch g_menus[8];
std::atomic<int64_t> g_menu_seen_ms{0};
std::atomic<uint32_t> g_menu_vtable{0};

void MenuSweep(int64_t now) {
  for (MenuWatch& m : g_menus) {
    if (m.self && now - m.seen_ms > 600) {
      REXKRNL_INFO("MENU off vt {:08X} this {:08X}", m.vtable, m.self);
      m.self = 0;
    }
  }
}
}  // namespace

void Dmc4NoteMenu(uint32_t self, uint8_t* base);
extern "C" void __imp__sub_825872D0(PPCContext& __restrict ctx, uint8_t* base);
extern "C" void sub_825872D0(PPCContext& __restrict ctx, uint8_t* base) {
  const uint32_t self = ctx.r3.u32;
  const int64_t now = NowMs();
  const uint8_t* cur = base + self;
  const uint32_t vt = Be32(cur);
  g_menu_seen_ms.store(now);
  g_menu_vtable.store(vt);
  Dmc4NoteMenu(self, base);
  {
    // Never block a game thread here (several job threads run menus concurrently): if
    // another thread is inside, skip the bookkeeping for this update.
    std::unique_lock<std::mutex> lock(g_menu_mutex, std::try_to_lock);
    if (!lock.owns_lock()) {
      __imp__sub_825872D0(ctx, base);
      return;
    }
    MenuSweep(now);
    MenuWatch* w = nullptr;
    for (MenuWatch& m : g_menus)
      if (m.self == self) w = &m;
    if (!w) {
      for (MenuWatch& m : g_menus)
        if (!m.self) { w = &m; break; }
      if (w) {
        w->self = self;
        w->vtable = vt;
        std::memcpy(w->prev, cur, sizeof(w->prev));
        std::memset(w->changes, 0, sizeof(w->changes));
        REXKRNL_INFO("MENU on vt {:08X} this {:08X} state {} sub {}", vt, self, cur[23], cur[22]);
      }
    } else {
      for (uint32_t o = 8; o < sizeof(w->prev); ++o) {
        if (cur[o] == w->prev[o]) continue;
        if (w->changes[o] < 255) ++w->changes[o];
        if (w->changes[o] <= 8) {
          REXKRNL_INFO("MENU vt {:08X} field +{} {} -> {} (state {} sub {})", vt, o, w->prev[o],
                       cur[o], cur[23], cur[22]);
        }
        w->prev[o] = cur[o];
      }
    }
    if (w) w->seen_ms = now;
  }
  __imp__sub_825872D0(ctx, base);
}

#if defined(__ANDROID__)
extern "C" JNIEXPORT jint JNICALL
Java_com_fourdeviltears_app_TouchGamepadView_nativeListMenu(JNIEnv*, jclass) {
  // vtable of the list menu updated in the last 300 ms, 0 if none.
  if (NowMs() - g_menu_seen_ms.load() > 300) return 0;
  return jint(g_menu_vtable.load());
}
#endif

// Cursor of the list menu last updated: u32 (big-endian) at +64 of the uListSelect base
// (seen going 0 -> 1 -> 2 on D-pad presses in the mission start and mission menus).
namespace {
std::atomic<uint32_t> g_menu_self{0};
std::atomic<uint8_t*> g_guest_base{nullptr};
}  // namespace

void Dmc4NoteBase(uint8_t* base) { g_guest_base.store(base); }

void Dmc4NoteMenu(uint32_t self, uint8_t* base) {
  g_menu_self.store(self);
  g_guest_base.store(base);
}

#if defined(__ANDROID__)
extern "C" JNIEXPORT jint JNICALL
Java_com_fourdeviltears_app_TouchGamepadView_nativeListCursor(JNIEnv*, jclass) {
  uint8_t* base = g_guest_base.load();
  uint32_t self = g_menu_self.load();
  if (!base || !self || NowMs() - g_menu_seen_ms.load() > 300) return -1;
  return jint(Be32(base + self + 64));
}
#endif

#if defined(__ANDROID__)
// Title main menu (state 7: New Game / Load Game / Options): cursor byte at +291.
extern "C" JNIEXPORT jint JNICALL
Java_com_fourdeviltears_app_TouchGamepadView_nativeTitleCursor(JNIEnv*, jclass) {
  uint8_t* base = g_guest_base.load();
  uint32_t self = g_title_prev_self;
  if (!base || !self || NowMs() - g_title_seen_ms.load() > 300) return -1;
  return jint(base[self + 291]);
}
#endif

#if defined(__ANDROID__)
// Touch menus: put the cursor straight on the tapped row (the touch layer only does this
// after it has seen the cursor and the highlighted row agree on this menu).
extern "C" JNIEXPORT jboolean JNICALL
Java_com_fourdeviltears_app_TouchGamepadView_nativeSetListCursor(JNIEnv*, jclass, jint value) {
  uint8_t* base = g_guest_base.load();
  uint32_t self = g_menu_self.load();
  if (!base || !self || value < 0 || value > 255 || NowMs() - g_menu_seen_ms.load() > 300) return 0;
  const uint32_t v = uint32_t(value);
  uint8_t* p = base + self + 64;
  p[0] = uint8_t(v >> 24); p[1] = uint8_t(v >> 16); p[2] = uint8_t(v >> 8); p[3] = uint8_t(v);
  return 1;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_fourdeviltears_app_TouchGamepadView_nativeSetTitleCursor(JNIEnv*, jclass, jint value) {
  uint8_t* base = g_guest_base.load();
  uint32_t self = g_title_prev_self;
  if (!base || !self || value < 0 || value > 255 || NowMs() - g_title_seen_ms.load() > 300) return 0;
  base[self + 291] = uint8_t(value);
  return 1;
}
#endif

// Distinct UI screen: title state, list menu class and its cursor, scene mode.
uint64_t Dmc4UiSignature() {
  const int64_t now = NowMs();
  uint64_t title = (now - g_title_seen_ms.load() < 300) ? (0x10000 | g_title_state.load()) : 0;
  uint64_t menu = (now - g_menu_seen_ms.load() < 300) ? g_menu_vtable.load() : 0;
  uint64_t cursor = 0xFF;
  uint8_t* base = g_guest_base.load();
  if (base && menu && g_menu_self.load()) cursor = Be32(base + g_menu_self.load() + 64) & 0xFF;
  else if (base && title && g_title_prev_self) cursor = base[g_title_prev_self + 291];
  return (title << 40) | (menu << 8) | cursor;
}

// For the GPU-side UI layout capture (renderer, ui_capture_draws): the renderer lives in
// another shared library, so the signature function is handed over at run time.
extern "C" uint64_t Dmc4UiSignatureC() { return Dmc4UiSignature(); }
// A function of the renderer library (librexgpu-xenos.so, loaded by the app with
// RTLD_LOCAL - so not reachable through RTLD_DEFAULT).
static void* Dmc4GpuSym(const char* name) {
  static void* lib = nullptr;
  if (!lib) lib = dlopen("librexgpu-xenos.so", RTLD_NOW | RTLD_NOLOAD);
  void* sym = lib ? dlsym(lib, name) : nullptr;
  return sym ? sym : dlsym(RTLD_DEFAULT, name);
}
static void Dmc4RegisterUiSignature() {
  static std::atomic<bool> done{false};
  if (done.load()) return;
  using SetFn = void (*)(uint64_t (*)());
  if (auto set = reinterpret_cast<SetFn>(Dmc4GpuSym("Rex4dtSetUiSignatureFn"))) {
    set(&Dmc4UiSignatureC);
    done.store(true);
  }
}

#if defined(__ANDROID__)
// The gameplay HUD (uCockpit) was drawn in the last 300 ms - the player controls the character.
extern "C" JNIEXPORT jboolean JNICALL
Java_com_fourdeviltears_app_TouchGamepadView_nativeHudActive(JNIEnv*, jclass) {
  Dmc4RegisterUiSignature();
  return NowMs() - g_cock_draw_ms.load() < 300 ? 1 : 0;
}

// Write every draw of the next frame to a file (the touch layer asks for it when a tap
// finds nothing it recognises - to learn that screen).
extern "C" JNIEXPORT void JNICALL
Java_com_fourdeviltears_app_TouchGamepadView_nativeUiCaptureFrame(JNIEnv* env, jclass, jstring path) {
  using Fn = void (*)(const char*);
  static Fn fn = nullptr;
  if (!fn) fn = reinterpret_cast<Fn>(Dmc4GpuSym("Rex4dtUiCaptureFrame"));
  if (!fn || !path) return;
  const char* p = env->GetStringUTFChars(path, nullptr);
  if (p) {
    fn(p);
    env->ReleaseStringUTFChars(path, p);
  }
}

// The UI sprites of the last frame (11 ints each: x0 y0 x1 y1 in 1280x720 game pixels,
// u0 v0 u1 v1 texture coordinates, texture page, colour, draw), for touch menus.
extern "C" JNIEXPORT jint JNICALL
Java_com_fourdeviltears_app_TouchGamepadView_nativeUiSprites(JNIEnv* env, jclass, jintArray out) {
  using Fn = int (*)(int32_t*, int, uint32_t*);
  static Fn fn = nullptr;
  if (!fn) fn = reinterpret_cast<Fn>(Dmc4GpuSym("Rex4dtUiSprites"));
  if (!fn || !out) return -1;
  const jsize len = env->GetArrayLength(out);
  jint* data = env->GetIntArrayElements(out, nullptr);
  if (!data) return -1;
  uint32_t frame = 0;
  const int count = fn(reinterpret_cast<int32_t*>(data), int(len / 11), &frame);
  env->ReleaseIntArrayElements(out, data, 0);
  return count;
}
#endif
