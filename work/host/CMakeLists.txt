# 4DevilTears — ReXGlue host: Android shared library (libdmc4.so = game code module).
cmake_minimum_required(VERSION 3.25)
project(dmc4 LANGUAGES CXX)
set(CMAKE_CXX_STANDARD 23)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(REXGLUE_RECOMP_DEBUG_INFO "none" CACHE STRING "" FORCE)
include(generated/rexglue.cmake)

set(DMC4_SOURCES src/main.cpp)
if(ANDROID)
    # SDLActivity loads libdmc4.so and calls its SDL_main.
    add_library(dmc4 SHARED ${DMC4_SOURCES} src/android_touch_gamepad.cpp src/dmc4_menu_hooks.cpp src/dmc4_widescreen.cpp src/dmc4_boot.cpp src/dmc4_perf.cpp src/dmc4_d3dtrace.cpp)
    target_link_libraries(dmc4 PRIVATE log android)
    target_link_options(dmc4 PRIVATE -Wl,-z,max-page-size=16384)
    # Codegen runs on the build machine: use the host (macOS) rexglue tool
    # instead of the Android binary exported by the cross-compiled SDK.
    if(REX_HOST_REXGLUE)
        set_target_properties(rex::rexglue PROPERTIES
            IMPORTED_LOCATION "${REX_HOST_REXGLUE}"
            IMPORTED_LOCATION_RELEASE "${REX_HOST_REXGLUE}"
            IMPORTED_LOCATION_RELWITHDEBINFO "${REX_HOST_REXGLUE}"
            IMPORTED_LOCATION_DEBUG "${REX_HOST_REXGLUE}")
    endif()
else()
    add_executable(dmc4 ${DMC4_SOURCES})
endif()
rexglue_setup_target(dmc4 GPU_PLUGINS xenos)
