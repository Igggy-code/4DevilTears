// 4DevilTears: on-screen Xbox 360 gamepad bridge + small runtime hooks for the Android UI.
//
// TouchGamepadView (Java) draws the controls and reports presses here. They are
// exposed to the game as an SDL virtual gamepad, so the runtime's normal SDL input
// driver picks them up exactly like a physical controller.
#if defined(__ANDROID__)

#include <jni.h>

#include <mutex>
#include <string>

#include <android/log.h>
#include <SDL3/SDL.h>

#include <rex/cvar.h>
#include <rex/kernel/scene_state.h>
#include <rex/perf/counter.h>

#define LOG_TAG "4DevilTears"

// Surface lifecycle for the presenter (SDK window_sdl.cpp).
#include <atomic>
extern std::atomic<bool> g_rex_android_surface_alive;
extern std::atomic<uint32_t> g_rex_android_surface_generation;

int Dmc4SceneMode();  // dmc4_menu_hooks.cpp

namespace {

std::mutex g_mutex;
SDL_JoystickID g_id = 0;
SDL_Joystick* g_joystick = nullptr;

// Must be called with g_mutex held.
bool EnsureAttached() {
  if (g_joystick) {
    return true;
  }
  // The runtime initializes the joystick subsystem once its window exists.
  if (!SDL_WasInit(SDL_INIT_JOYSTICK)) {
    return false;
  }
  if (!g_id) {
    SDL_VirtualJoystickDesc desc;
    SDL_INIT_INTERFACE(&desc);
    desc.type = SDL_JOYSTICK_TYPE_GAMEPAD;
    desc.naxes = SDL_GAMEPAD_AXIS_COUNT;                // LX LY RX RY LT RT
    desc.nbuttons = SDL_GAMEPAD_BUTTON_DPAD_RIGHT + 1;  // A..DPAD_RIGHT
    desc.vendor_id = 0x045E;                            // Microsoft
    desc.product_id = 0x028E;                           // Xbox 360 Controller
    desc.name = "4DevilTears Touch Gamepad";
    g_id = SDL_AttachVirtualJoystick(&desc);
    if (!g_id) {
      __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "SDL_AttachVirtualJoystick: %s", SDL_GetError());
      return false;
    }
  }
  g_joystick = SDL_OpenJoystick(g_id);
  if (!g_joystick) {
    __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "SDL_OpenJoystick(virtual): %s", SDL_GetError());
    return false;
  }
  __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "touch gamepad attached (id %u)",
                      static_cast<unsigned>(g_id));
  return true;
}

}  // namespace

extern "C" {

JNIEXPORT jboolean JNICALL Java_com_fourdeviltears_app_TouchGamepadView_nativeAttach(JNIEnv*, jclass) {
  std::lock_guard<std::mutex> lock(g_mutex);
  return EnsureAttached() ? JNI_TRUE : JNI_FALSE;
}

// button: SDL_GamepadButton (0 = A/South ... 14 = DPad Right).
JNIEXPORT void JNICALL Java_com_fourdeviltears_app_TouchGamepadView_nativeButton(JNIEnv*, jclass,
                                                                                 jint button,
                                                                                 jboolean down) {
  std::lock_guard<std::mutex> lock(g_mutex);
  if (!EnsureAttached()) {
    return;
  }
  SDL_SetJoystickVirtualButton(g_joystick, button, down == JNI_TRUE);
}

// axis: SDL_GamepadAxis. Sticks take -1..1; triggers take 0..1.
JNIEXPORT void JNICALL Java_com_fourdeviltears_app_TouchGamepadView_nativeAxis(JNIEnv*, jclass,
                                                                               jint axis,
                                                                               jfloat value) {
  std::lock_guard<std::mutex> lock(g_mutex);
  if (!EnsureAttached()) {
    return;
  }
  float v = value;
  Sint16 raw;
  if (axis == SDL_GAMEPAD_AXIS_LEFT_TRIGGER || axis == SDL_GAMEPAD_AXIS_RIGHT_TRIGGER) {
    v = v < 0.f ? 0.f : (v > 1.f ? 1.f : v);
    raw = static_cast<Sint16>(-32768.f + v * 65535.f);
  } else {
    v = v < -1.f ? -1.f : (v > 1.f ? 1.f : v);
    raw = static_cast<Sint16>(v * 32767.f);
  }
  SDL_SetJoystickVirtualAxis(g_joystick, axis, raw);
}

// Guest frame rate from the runtime's per-frame counters (last completed frame), or -1.
JNIEXPORT jfloat JNICALL Java_com_fourdeviltears_app_TouchGamepadView_nativeFps(JNIEnv*, jclass) {
  const int64_t frame_us = rex::perf::GetSnapshotCounter(rex::perf::CounterId::kFrameTimeUs);
  if (frame_us <= 0) {
    return -1.0f;
  }
  return 1000000.0f / static_cast<float>(frame_us);
}

// What the game shows: 0 gameplay/menus, 1 in-engine cutscene, 2 pre-rendered movie.
JNIEXPORT jint JNICALL Java_com_fourdeviltears_app_TouchGamepadView_nativeSceneMode(JNIEnv*, jclass) {
  // The game's own cutscene / movie units first; the open-file heuristic only as a fallback
  // for a movie (its file is open exactly while it plays).
  int mode = Dmc4SceneMode();
  if (mode == 0 && rex::kernel::scene::CurrentMode() == 2) mode = 2;
  return mode;
}

JNIEXPORT void JNICALL Java_com_fourdeviltears_app_DevilActivity_nativeSurfaceState(JNIEnv*, jclass,
                                                                                   jboolean alive) {
  if (alive) {
    g_rex_android_surface_generation.fetch_add(1);
  }
  g_rex_android_surface_alive.store(alive != JNI_FALSE);
  __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "surface %s", alive ? "created" : "destroyed");
}

// Runtime flags from the Android UI (settings menu, later).
JNIEXPORT jboolean JNICALL Java_com_fourdeviltears_app_DevilActivity_nativeSetFlag(JNIEnv* env, jclass,
                                                                                   jstring name,
                                                                                   jstring value) {
  const char* n = env->GetStringUTFChars(name, nullptr);
  const char* v = env->GetStringUTFChars(value, nullptr);
  bool ok = rex::cvar::SetFlagByName(n, v);
  __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "setting %s=%s -> %s", n, v, ok ? "ok" : "rejected");
  env->ReleaseStringUTFChars(name, n);
  env->ReleaseStringUTFChars(value, v);
  return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL Java_com_fourdeviltears_app_DevilActivity_nativeGetFlag(JNIEnv* env, jclass,
                                                                                  jstring name) {
  const char* n = env->GetStringUTFChars(name, nullptr);
  std::string value = rex::cvar::GetFlagByName(n);
  env->ReleaseStringUTFChars(name, n);
  return env->NewStringUTF(value.c_str());
}

}  // extern "C"

#endif  // __ANDROID__
