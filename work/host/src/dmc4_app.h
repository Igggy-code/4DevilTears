// 4DevilTears — application class (paths, platform setup).
#pragma once

#include <cstdlib>
#include <filesystem>

#include <rex/rex_app.h>
#include <rex/perf/counter.h>

#if defined(__ANDROID__)
#include <SDL3/SDL_system.h>
#endif

class Dmc4App : public rex::ReXApp {
 public:
  using rex::ReXApp::ReXApp;

  static std::unique_ptr<rex::ui::WindowedApp> Create(rex::ui::WindowedAppContext& ctx) {
    return std::unique_ptr<Dmc4App>(new Dmc4App(ctx, "dmc4", PPCImageConfig));
  }

  void OnConfigurePaths(rex::PathConfig& paths) override {
#if defined(__ANDROID__)
    // All data lives in app-private storage:
    //   files/game   — files extracted from the user's own disc image
    //   files/user   — saves / settings
    //   files/cache  — shader & pipeline caches
    if (const char* internal = SDL_GetAndroidInternalStoragePath()) {
      const std::filesystem::path root(internal);
      if (paths.game_data_root.empty()) paths.game_data_root = root / "game";
      paths.user_data_root = root / "user";
      paths.cache_root = root / "cache";
      paths.config_path = root / "dmc4.toml";  // default is next to the .so (read-only)
    }
#else
    (void)paths;
#endif
  }

  void OnPostSetup() override {
#if defined(__ANDROID__)
    // Per-guest-frame performance log (frame time, fps, draws, stalls, cache misses).
    // The SDK declares --perf_log_csv but never opens it, so do it here.
    if (const char* internal = SDL_GetAndroidInternalStoragePath()) {
      rex::perf::SetCsvLogPath((std::filesystem::path(internal) / "perf.csv").string());
    }
#endif
  }
};
