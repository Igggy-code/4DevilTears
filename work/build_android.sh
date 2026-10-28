#!/bin/bash
# 4DevilTears — сборка Android APK (arm64-v8a):
#   ReXGlue SDK v0.10.0 (чистый) + Android-патч -> код игры (libdmc4.so) -> APK.
# Запуск:  bash "/Volumes/T7/DMC-box/work/build_android.sh"      Лог: work/logs/android_build.log
#          SKIP_NATIVE=1 bash ".../build_android.sh"   — только APK из уже собранных .so
set -uo pipefail
WORK="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(dirname "$WORK")"
BUILD="$ROOT/build"
SDK="$BUILD/rexglue-sdk"
PROJ="$BUILD/dmc4"
OBJ="$BUILD/dmc4-android"
APP="$BUILD/android-app"
SDK_TAG="v0.10.0"
NDK_VERSION="28.2.13676358"
mkdir -p "$WORK/logs" "$BUILD"
exec > >(tee "$WORK/logs/android_build.log") 2>&1
step() { echo; echo "=== [$(date +%H:%M:%S)] $* ==="; }
fail() { echo; echo "!!! ОШИБКА: $*"; echo "!!! Лог: work/logs/android_build.log"; exit 1; }

FREE_KB="$(df -k "$ROOT" | tail -1 | awk '{print $4}')"
if [ "${FREE_KB:-0}" -lt 3000000 ]; then
  echo "!!! на диске $((FREE_KB / 1024)) МБ свободно - сборке нужно ~3 ГБ."
  echo "!!! Старые логи чистит install_android.sh (оставляет LOGS_KEEP последних прогонов)."
fi
step "Android SDK / NDK / JDK"
find_sdk() {
  for c in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/Library/Android/sdk" /Volumes/T7/AndroidSdk /Volumes/T7/Android/sdk /Volumes/T7/Android/Sdk; do
    [ -n "$c" ] && [ -d "$c/platforms" -o -d "$c/platform-tools" -o -d "$c/ndk" ] && { echo "$c"; return; }
  done
  for f in "/Volumes/T7/Fable droid/build/android-app/local.properties" /Volumes/T7/*/local.properties "$HOME"/AndroidStudioProjects/*/local.properties; do
    [ -f "$f" ] || continue
    d="$(sed -n 's/^sdk\.dir=//p' "$f" | sed 's/\\:/:/g' | head -1)"
    [ -n "$d" ] && [ -d "$d" ] && { echo "$d"; return; }
  done
}
export ANDROID_HOME="$(find_sdk)"
[ -n "$ANDROID_HOME" ] || fail "Android SDK не найден (Android Studio -> SDK Manager)"
java_major() { "$1/bin/java" -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -1; }
pick_jdk() {
  local c m
  for c in /Applications/Android\ Studio*.app/Contents/jbr/Contents/Home \
           "$HOME"/Applications/Android\ Studio*.app/Contents/jbr/Contents/Home \
           "$(/usr/libexec/java_home -v 21 2>/dev/null)" "$(/usr/libexec/java_home -v 17 2>/dev/null)" \
           /opt/homebrew/opt/openjdk@21 /opt/homebrew/opt/openjdk@17 "${JAVA_HOME:-}"; do
    [ -n "$c" ] && [ -x "$c/bin/java" ] || continue
    m="$(java_major "$c")"
    [ -n "$m" ] && [ "$m" -ge 17 ] && [ "$m" -le 23 ] && { echo "$c"; return; }
  done
}
JDK="$(pick_jdk)"; [ -n "$JDK" ] && export JAVA_HOME="$JDK" || echo "!!! нет JDK 17-23 (brew install openjdk@21)"
NDK="$ANDROID_HOME/ndk/$NDK_VERSION"
if [ ! -f "$NDK/build/cmake/android.toolchain.cmake" ]; then
  NDK=""
  for d in "$ANDROID_HOME"/ndk/*; do [ -f "$d/build/cmake/android.toolchain.cmake" ] && NDK="$d"; done
fi
[ -n "$NDK" ] || fail "нет NDK (SDK Manager -> SDK Tools -> NDK (Side by side))"
echo "ANDROID_HOME=$ANDROID_HOME"; echo "NDK=$NDK"; echo "JAVA_HOME=${JAVA_HOME:-}"
command -v cmake >/dev/null && command -v ninja >/dev/null || fail "нужны cmake и ninja (brew install cmake ninja)"
TOOLCHAIN="$NDK/build/cmake/android.toolchain.cmake"
# armv8.2-a: все arm64-телефоны с Vulkan 1.1 (LSE-атомики, fp16, dotprod).
ARCH_FLAGS="-march=armv8.2-a+fp16+dotprod"
ANDROID_ARGS=(-DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN" -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-29 -DANDROID_STL=c++_shared
              -DCMAKE_C_FLAGS="$ARCH_FLAGS" -DCMAKE_CXX_FLAGS="$ARCH_FLAGS")
SDK_OBJ="$SDK/out/build/android-arm64"; SDK_PREFIX="$SDK/out/install/android-arm64"

if [ "${SKIP_NATIVE:-0}" != "1" ]; then
step "ReXGlue SDK $SDK_TAG"
if [ ! -d "$SDK/.git" ]; then
  git clone --branch "$SDK_TAG" --depth 1 https://github.com/rexglue/rexglue-sdk.git "$SDK" || fail "git clone SDK"
fi
git -C "$SDK" submodule update --init --depth 1 --jobs 8 || fail "submodules"

step "Android-патч SDK"
P="$WORK/patches/4dt-sdk-android.patch"
if git -C "$SDK" apply --reverse --check "$P" 2>/dev/null; then
  echo "уже применён"
else
  # Наложена старая версия патча: вернуть SDK к чистому состоянию и наложить новую.
  # Файлы с неизменившимся содержимым сохраняют прежнюю дату — иначе изменение
  # заголовков SDK заставит перекомпилировать весь код игры.
  SAVE="$(mktemp -d)"
  (cd "$SDK" && { git diff --name-only; git ls-files --others --exclude-standard; } | sort -u) | while read -r f; do
    [ -f "$SDK/$f" ] && { mkdir -p "$SAVE/$(dirname "$f")"; cp -p "$SDK/$f" "$SAVE/$f"; }
  done
  git -C "$SDK" checkout -- . && git -C "$SDK" clean -fdq -e out
  git -C "$SDK" apply "$P" || fail "патч не применился"
  (cd "$SAVE" && find . -type f) | while read -r f; do
    cmp -s "$SAVE/$f" "$SDK/$f" && touch -r "$SAVE/$f" "$SDK/$f"
  done
  rm -rf "$SAVE"
  echo "применён"
fi

step "rexglue для Mac (генератор кода)"
HOST_REXGLUE="$(find "$SDK/out" -path "*mac-arm64*" -type f -name rexglue -perm -u+x 2>/dev/null | head -1)"
if [ -z "$HOST_REXGLUE" ]; then
  (cd "$SDK" && cmake --preset mac-arm64 -DCMAKE_C_COMPILER=/usr/bin/clang -DCMAKE_CXX_COMPILER=/usr/bin/clang++ \
        -DCMAKE_OSX_DEPLOYMENT_TARGET=13.3 -DREXGLUE_ENABLE_TRACY=OFF) || fail "configure rexglue (mac)"
  cmake --build "$SDK/out/build/mac-arm64" --config Release --target rexglue --parallel 6 || fail "сборка rexglue (mac)"
  HOST_REXGLUE="$(find "$SDK/out" -path "*mac-arm64*" -type f -name rexglue -perm -u+x | head -1)"
fi
[ -n "$HOST_REXGLUE" ] || fail "не найден rexglue (mac)"
"$HOST_REXGLUE" --version

step "SDK под Android (arm64)"
cmake -S "$SDK" -B "$SDK_OBJ" -G Ninja "${ANDROID_ARGS[@]}" -DCMAKE_BUILD_TYPE=Release \
      -DREXGLUE_ENABLE_TRACY=OFF -DREXGLUE_BUILD_TESTS=OFF -DCMAKE_INSTALL_PREFIX="$SDK_PREFIX" \
      || fail "configure SDK (android)"
cmake --build "$SDK_OBJ" --target install --parallel 6 || fail "сборка SDK (android)"

step "Проект игры"
XEX="$ROOT/extracted/default.xex"
[ -f "$XEX" ] || fail "нет $XEX (сначала извлеките образ: tools/xdvdfs_extract.py)"
mkdir -p "$PROJ/src"
# Копировать только изменившиеся файлы: новая дата манифеста = перегенерация и полная перекомпиляция.
copy_if_changed() { cmp -s "$1" "$2" || cp -f "$1" "$2"; }
copy_if_changed "$XEX" "$PROJ/default.xex"
copy_if_changed "$WORK/dmc4_manifest.toml" "$PROJ/dmc4_manifest.toml"
copy_if_changed "$WORK/host/CMakeLists.txt" "$PROJ/CMakeLists.txt"
for f in "$WORK"/host/src/*; do copy_if_changed "$f" "$PROJ/src/$(basename "$f")"; done
if [ ! -f "$PROJ/generated/rexglue.cmake" ] || [ "$PROJ/dmc4_manifest.toml" -nt "$PROJ/generated/default/codegen.stamp" ]; then
  (cd "$PROJ" && "$HOST_REXGLUE" codegen dmc4_manifest.toml) || fail "codegen"
fi

step "Код игры под Android (libdmc4.so)"
cmake -S "$PROJ" -B "$OBJ" -G Ninja "${ANDROID_ARGS[@]}" -DCMAKE_BUILD_TYPE=Release \
      -Drexglue_DIR="$SDK_PREFIX/lib/cmake/rexglue" -DCMAKE_FIND_ROOT_PATH="$SDK_PREFIX" \
      -DREX_HOST_REXGLUE="$HOST_REXGLUE" || fail "configure игры (android)"
START=$(date +%s)
cmake --build "$OBJ" --parallel 6 || fail "компиляция игры (android)"
echo "Компиляция: $(( ($(date +%s) - START) / 60 )) мин"
fi

step "Android-проект"
TPL="$SDK/thirdparty/sdl3/android-project"
[ -d "$TPL" ] || fail "нет шаблона $TPL"
rm -rf "$APP"; mkdir -p "$APP"
cp -R "$TPL/gradle" "$TPL/gradlew" "$TPL/build.gradle" "$TPL/settings.gradle" "$TPL/gradle.properties" "$APP/"
mkdir -p "$APP/app/src/main" "$APP/app/sdl-java" "$APP/app/libs/arm64-v8a"
cp -R "$TPL/app/src/main/res" "$APP/app/src/main/"
cp -R "$TPL/app/src/main/java/org" "$APP/app/sdl-java/"
cp -R "$WORK/android/app/." "$APP/app/"
echo "sdk.dir=$ANDROID_HOME" > "$APP/local.properties"
LIBS="$APP/app/libs/arm64-v8a"
# Код игры (libdmc4.so) в APK не кладётся: телефон собирает его сам из default.xex игрока
# (make_kit.sh + GameBuild.java). BUNDLE_GAME_LIB=1 — положить готовый, для быстрых тестов.
if [ "${BUNDLE_GAME_LIB:-0}" = "1" ]; then
  cp -f "$OBJ/libdmc4.so" "$LIBS/" || fail "нет libdmc4.so"
fi
cp -f "$(find "$OBJ" "$SDK_PREFIX" -name librexgpu-xenos.so | head -1)" "$LIBS/" || fail "нет librexgpu-xenos.so"
cp -f "$(find "$SDK_PREFIX" "$SDK_OBJ" -name librexruntime.so | head -1)" "$LIBS/" || fail "нет librexruntime.so"
cp -f "$(find "$NDK/toolchains/llvm/prebuilt" -path "*aarch64-linux-android/libc++_shared.so" | head -1)" "$LIBS/" || fail "нет libc++_shared.so"
step "Набор для сборки кода игры на телефоне"
NDK="$NDK" bash "$WORK/make_kit.sh" "$APP" || fail "make_kit.sh"
ls -la "$LIBS"

step "APK (Gradle)"
[ -n "${JAVA_HOME:-}" ] && echo "org.gradle.java.home=$JAVA_HOME" >> "$APP/gradle.properties"
cd "$APP" && chmod +x gradlew && ./gradlew --no-daemon assembleDebug || fail "gradle"
APK="$APP/app/build/outputs/apk/debug/app-debug.apk"
[ -f "$APK" ] || fail "APK не найден"
cp -f "$APK" "$ROOT/DMC4.apk"
ls -la "$ROOT/DMC4.apk"
echo "ANDROID_BUILD_OK"
