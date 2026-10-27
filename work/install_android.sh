#!/bin/bash
# 4DevilTears — установка на телефон по USB (adb), загрузка файлов игры, логи.
#   bash "/Volumes/T7/DMC-box/work/install_android.sh"              APK + файлы игры (если их ещё нет) + запуск
#   SKIP_DATA=1  bash ".../install_android.sh"                      только APK + запуск
#   FORCE_DATA=1 bash ".../install_android.sh"                      перезалить файлы игры
#   FORCE_DATA=1 GAME_SRC="/Volumes/T7/DMC-box/2008 Devil May Cry 4" bash ...  другое издание
#                (образ .iso, папка Games on Demand или распакованная папка; распаковка - сама)
#   LOGS=1       bash ".../install_android.sh"                      логи + скриншот + сводка FPS -> work/logs
#   PROFILE=1    bash ".../install_android.sh"                      профиль CPU за 10 с + где потоки ждут (игра запущена)
#   GPUCOUNTERS=1 bash ".../install_android.sh"                     счётчики видеочипа Mali за 8 с (игра запущена)
#   CLEAR_CACHE=1 SKIP_DATA=1 bash ".../install_android.sh"         очистить кэш шейдеров/конвейеров перед запуском
#   TRACE=1      bash ".../install_android.sh"                      записать один кадр команд видеочипа (игра запущена)
#   BENCH=1      bash ".../install_android.sh"                      автостенд: игра перезапускается с разными флагами,
#                                                                   в меню снимается FPS/плавность + скриншот, в конце таблица
#                                                                   (~1,5 мин на вариант; телефон не трогать, экран не гасить)
#   BENCH_SET=имя — свой набор вариантов из work/bench/<имя>.txt (по умолчанию work/bench/default.txt)
#   ARGS="--a=1 --b" bash ".../install_android.sh"                  флаги без пересборки (ARGS=none — убрать) + перезапуск
#
# Файлы игры (default.xex + nativeXenon/) кладутся во внутреннюю память приложения
# (/data/data/com.fourdeviltears.app/files/game) через run-as — так у них правильный владелец.
# Позже это заменит импорт образа прямо в приложении.
WORK="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(dirname "$WORK")"
APK="$ROOT/DMC4.apk"
PKG=com.fourdeviltears.app
ACT="$PKG/.DevilActivity"
mkdir -p "$WORK/logs"
ANDROID_HOME=""
[ -f "$ROOT/build/android-app/local.properties" ] && ANDROID_HOME="$(sed -n 's/^sdk.dir=//p' "$ROOT/build/android-app/local.properties")"
ADB="$ANDROID_HOME/platform-tools/adb"
[ -x "$ADB" ] || ADB="$(command -v adb)"
[ -x "$ADB" ] || { echo "Нет adb (Android Studio -> SDK Tools -> Platform-Tools)"; exit 1; }
if ! "$ADB" get-state >/dev/null 2>&1; then
  echo "телефон не виден — перезапускаю adb..."
  "$ADB" kill-server >/dev/null 2>&1; "$ADB" start-server >/dev/null 2>&1
  for i in $(seq 1 10); do "$ADB" get-state >/dev/null 2>&1 && break; sleep 2; done
  "$ADB" get-state >/dev/null 2>&1 || { echo "Телефон не подключён или отладка по USB не разрешена"; exit 1; }
fi
echo "Телефон: $("$ADB" shell getprop ro.product.model) / Android $("$ADB" shell getprop ro.build.version.release)"

collect_logs() {
  "$ADB" exec-out screencap -p > "$WORK/logs/android_screen.png" 2>/dev/null
  "$ADB" logcat -d > "$WORK/logs/android_logcat.txt"
  # лог рантайма: ищем самый свежий .log в файлах приложения
  "$ADB" exec-out run-as $PKG cat files/dmc4.log > "$WORK/logs/android_dmc4.log" 2>/dev/null
  "$ADB" exec-out run-as $PKG cat files/perf.csv > "$WORK/logs/android_perf.csv" 2>/dev/null
  # Дамп шейдеров (если игра запускалась с ARGS="--dump_shaders=...files/shaders").
  if "$ADB" shell run-as $PKG ls files/shaders >/dev/null 2>&1; then
    "$ADB" exec-out run-as $PKG tar -cf - files/shaders > "$WORK/logs/shaders.tar" 2>/dev/null
    echo "шейдеров в дампе: $(tar -tf "$WORK/logs/shaders.tar" 2>/dev/null | grep -c .)"
  fi
  "$ADB" shell dumpsys thermalservice > "$WORK/logs/android_thermal.txt" 2>/dev/null
  # Сводка по последним ~600 кадрам: FPS, время кадра, отрисовки, простои.
  if [ -s "$WORK/logs/android_perf.csv" ]; then
    tail -n 600 "$WORK/logs/android_perf.csv" | awk -F, '
      NR==1 && $1 ~ /frame_time_us/ { next }
      $1 > 0 { n++; t += $1; d += $3; st += $4; if ($1 > mx) mx = $1; if ($1 > 50000) slow++ }
      END { if (n) printf "кадров %d: в среднем %.1f FPS (%.1f мс), худший %.1f мс, кадров >50 мс: %d, отрисовок/кадр %.0f, простоев/кадр %.2f\n",
            n, 1e6 / (t / n), t / n / 1000, mx / 1000, slow, d / n, st / n }' | tee "$WORK/logs/android_perf_summary.txt"
  fi
  grep -m1 "Thermal Status" "$WORK/logs/android_thermal.txt" 2>/dev/null
  grep -E "4DevilTears|rex|DEBUG|FATAL|crash|SIGSEGV|SIGBUS|SIGABRT|backtrace|#[0-9]+ pc" "$WORK/logs/android_logcat.txt" | tail -60 > "$WORK/logs/android_logcat_short.txt"
  ls -la "$WORK/logs"/android_*
}

if [ "${LOGS:-0}" = "1" ]; then collect_logs; echo "LOGS_DONE"; exit 0; fi

if [ "${PROFILE:-0}" = "1" ]; then
  PID="$("$ADB" shell pidof $PKG | tr -d '\r')"
  [ -n "$PID" ] || { echo "Игра не запущена"; exit 1; }
  OUT="$WORK/logs/android_profile.txt"
  "$ADB" shell setprop security.perf_harden 0
  "$ADB" shell rm -f /data/local/tmp/perf.data
  echo "== simpleperf 10 с (pid $PID) — не трогай телефон"
  R="simpleperf report -i /data/local/tmp/perf.data"
  {
    echo "===== record"
    "$ADB" shell "simpleperf record --app $PKG -e cpu-clock -g -f 1000 --duration 10 -o /data/local/tmp/perf.data" 2>&1
    echo "===== threads"
    "$ADB" shell "$R --sort tid,comm -n --percent-limit 1" 2>&1
    echo "===== symbols"
    "$ADB" shell "$R --sort comm,dso,symbol -n --percent-limit 0.3" 2>&1
    echo "===== callers of top symbols (per thread)"
    "$ADB" shell "$R --sort comm,symbol -g caller --percent-limit 2" 2>&1 | head -1500
  } > "$OUT"
  # Второй проход: время вне CPU (где потоки ждут — блокировки, ожидание видеочипа).
  "$ADB" shell rm -f /data/local/tmp/perf_off.data
  echo "== simpleperf off-CPU 6 с"
  R2="simpleperf report -i /data/local/tmp/perf_off.data"
  {
    echo "===== offcpu record"
    "$ADB" shell "simpleperf record --app $PKG --trace-offcpu -e cpu-clock -g -f 1000 --duration 6 -o /data/local/tmp/perf_off.data" 2>&1
    echo "===== offcpu threads"
    "$ADB" shell "$R2 --sort tid,comm -n --percent-limit 1" 2>&1
    echo "===== offcpu symbols"
    "$ADB" shell "$R2 --sort comm,symbol -n --percent-limit 0.5" 2>&1
    echo "===== offcpu callers"
    "$ADB" shell "$R2 --sort comm,symbol -g caller --percent-limit 3" 2>&1 | head -2500
  } >> "$OUT"
  "$ADB" shell dumpsys thermalservice 2>/dev/null | grep -m1 "Thermal Status" >> "$OUT"
  wc -l "$OUT"; echo "PROFILE_DONE"; exit 0
fi

if [ -n "${ARGS:-}" ]; then
  if [ "$ARGS" = "none" ]; then
    "$ADB" shell run-as $PKG rm -f files/args.txt; echo "== флаги убраны"
  else
    printf '%s\n' $ARGS | "$ADB" exec-in run-as $PKG sh -c 'cat > files/args.txt'
    echo "== флаги:"; "$ADB" shell run-as $PKG cat files/args.txt
  fi
  "$ADB" shell am force-stop $PKG; "$ADB" logcat -c
  "$ADB" shell am start -n "$ACT" >/dev/null
  echo "ARGS_DONE (игра перезапущена; через минуту: LOGS=1 bash \"$0\")"; exit 0
fi

skin_temp() {
  # Температура корпуса (°C, целое) из thermalservice; пусто, если не удалось прочитать.
  "$ADB" shell dumpsys thermalservice </dev/null 2>/dev/null \
    | grep -m1 -iE "mType=3.*mName=.*(skin|virtual)|mName=.*(SKIN|VIRTUAL-SKIN).*mType=3|Temperature\{mValue=[0-9.]+, mType=3" \
    | grep -o "mValue=[0-9.]*" | head -1 | cut -d= -f2 | cut -d. -f1
}

if [ "${BENCH:-0}" = "1" ]; then
  # Ctrl+C / any exit: stop the background gameplay input loop.
  trap 'rm -f "$WORK"/logs/bench_*/.inputs 2>/dev/null; kill $(jobs -p) 2>/dev/null' EXIT INT TERM
  SET="$WORK/bench/${BENCH_SET:-default}.txt"
  [ -f "$SET" ] || { echo "нет $SET"; exit 1; }
  echo "== установка APK"; "$ADB" install -r "$APK" || exit 1
  WARM="${BENCH_WARM:-55}"; MEASURE="${BENCH_MEASURE:-15}"
  OUTD="$WORK/logs/bench_$(date +%m%d_%H%M)"; mkdir -p "$OUTD"
  "$ADB" shell settings put system screen_off_timeout 1800000 >/dev/null 2>&1
  COOL="${BENCH_COOL_TEMP:-37}"; COOL_MAX="${BENCH_COOL_MAX:-60}"
  printf "%-18s %6s %7s %6s %6s %7s %6s %9s %s\n" "вариант" "FPS" "медиана" "≤17мс" "≤34мс" "p95,мс" ">50мс" "t°C→t°C" "флаги" | tee "$OUTD/summary.txt"
  n=0
  # Кнопка START экранного геймпада (TouchGamepadView): центр по ширине + 99 dp, сверху 31 dp.
  DENS="$("$ADB" shell wm density </dev/null | grep -o '[0-9]*' | tail -1)"; DENS="${DENS:-420}"
  SIZE="$("$ADB" shell wm size </dev/null | grep -o '[0-9]*x[0-9]*' | tail -1)"
  SW="${SIZE%x*}"; SH="${SIZE#*x}"; [ "$SW" -lt "$SH" ] && { T="$SW"; SW="$SH"; SH="$T"; }
  START_X=$(( SW / 2 + 99 * DENS / 160 )); START_Y=$(( 31 * DENS / 160 ))
  BOOT_MAX="${BENCH_BOOT_MAX:-180}"; WARM3D="${BENCH_WARM3D:-15}"
  echo "START: ${START_X},${START_Y} (экран ${SW}x${SH}, ${DENS} dpi); замер через ${WARM3D} с после появления 3D"
  while IFS='|' read -r NAME FLAGS <&3; do
    NAME="$(echo "$NAME" | xargs)"; FLAGS="$(echo "$FLAGS" | xargs)"
    [ -z "$NAME" ] && continue; case "$NAME" in \#*) continue;; esac
    n=$((n+1))
    "$ADB" shell am force-stop $PKG
    # Остудить телефон до порога, чтобы перегрев не искажал сравнение вариантов.
    waited=0
    while T="$(skin_temp)"; [ -n "$T" ] && [ "$T" -gt "$COOL" ] && [ "$waited" -lt "$COOL_MAX" ]; do
      [ "$waited" -eq 0 ] && echo "  остывание: ${T}°C > ${COOL}°C..."
      sleep 10; waited=$((waited + 10))
    done
    "$ADB" logcat -c
    # Токен @clear во флагах: очистить кэш шейдеров/конвейеров (замер компиляции с нуля).
    GAME=0; GAMEPLAY=0
    case " $FLAGS " in *" @game "*)
      GAME=1; FLAGS="$(echo " $FLAGS " | sed 's/ @game / /' | xargs)";;
    esac
    # Токен @gameplay: как @game, но ролик пропускается (START, START, A), затем
    # два A после загрузки миссии, и во время замера идёт ввод (X + левый стик).
    case " $FLAGS " in *" @gameplay "*)
      GAME=1; GAMEPLAY=1; FLAGS="$(echo " $FLAGS " | sed 's/ @gameplay / /' | xargs)";;
    esac
    case " $FLAGS " in *" @clear "*)
      "$ADB" shell run-as $PKG rm -rf files/cache
      FLAGS="$(echo " $FLAGS " | sed 's/ @clear / /' | xargs)";;
    esac
    LOG0="$("$ADB" shell run-as $PKG sh -c "'cat files/dmc4.log 2>/dev/null | wc -l'" | tr -d '\r ')"; LOG0="${LOG0:-0}"
    if [ -n "$FLAGS" ]; then printf '%s\n' $FLAGS | "$ADB" exec-in run-as $PKG sh -c 'cat > files/args.txt'
    else "$ADB" shell run-as $PKG rm -f files/args.txt; fi
    "$ADB" shell run-as $PKG rm -f files/perf.csv files/state.txt
    "$ADB" shell am start -n "$ACT" >/dev/null
    # Пропустить заставки и вступительный ролик: пока игра рисует мало (логотипы, видео —
    # единицы draw-вызовов на кадр), нажимаем START на экранном геймпаде каждые 3 с.
    # Как только пошла 3D-сцена титульного экрана (250+ draw-вызовов), перестаём
    # нажимать и после прогрева BENCH_WARM3D секунд начинаем замер.
    t=0; BOOT=""
    while [ "$t" -lt "$BOOT_MAX" ]; do
      sleep 3; t=$((t + 3))
      # First variant: screenshots of the boot (logos, video, transitions) for artifacts.
      "$ADB" exec-out screencap -p > "$OUTD/$n-boot_$(printf %03d $t)s.png" 2>/dev/null
      D="$("$ADB" exec-out run-as $PKG tail -n 20 files/perf.csv 2>/dev/null \
        | awk -F, '$3 ~ /^[0-9]+$/ { s += $3; n++ } END { print n ? int(s / n) : 0 }')"
      if [ "${D:-0}" -ge 150 ]; then BOOT="$t"; break; fi
      [ "$t" -ge 12 ] && "$ADB" shell input swipe "$START_X" "$START_Y" "$START_X" "$START_Y" 150
    done
    [ -z "$BOOT" ] && echo "  $NAME: 3D-сцена не появилась за ${BOOT_MAX} с"
    sleep "$WARM3D"
    if [ "$GAME" = "1" ]; then
      A_X=$(( SW - 125 * DENS / 160 )); A_Y=$(( SH - 123 * DENS / 160 ))
      # The app writes files/state.txt: scene (0 game/menus, 1 cutscene, 2 movie), title
      # (title screen state, -1 = not on the title), cutscenes / movies started so far.
      st() { "$ADB" exec-out run-as $PKG cat files/state.txt 2>/dev/null | tr -d '\r'; }
      stv() { st | tr ' ' '\n' | sed -n "s/^$1=//p"; }
      tap() { "$ADB" shell input swipe "$1" "$2" "$1" "$2" 150; }
      wait_for() {  # wait_for <seconds> <shell condition using $S>
        local lim="$1" w=0; shift
        while [ "$w" -lt "$lim" ]; do S="$(st)"; eval "$@" && return 0; sleep 1; w=$((w + 1)); done
        return 1
      }
      # Title -> New Game: START on "press start" (title state 3), A on the other title
      # screens, until the title is gone.
      i=0; seen_title=0; SWIPED=""
      while [ "$i" -lt 40 ]; do
        TS="$(stv title)"; TS="${TS:--1}"
        if [ "$TS" = "-1" ]; then [ "$seen_title" = "1" ] && break
        else
          seen_title=1
          if [ "$TS" = "3" ]; then tap "$START_X" "$START_Y"
          else
            # Menu screens (7 main menu, 11 new game settings): once per screen, move the
            # cursor down, down, up, up with swipes so the log shows which field is the cursor.
            case " $SWIPED " in *" $TS "*) ;; *)
              SWIPED="$SWIPED $TS"; CX=$((SW / 2)); CY=$((SH / 2)); DY=$((150 * DENS / 160))
              for dir in 1 1 -1 -1; do
                "$ADB" shell input swipe "$CX" "$CY" "$CX" "$((CY + dir * DY))" 200; sleep 1.2
              done;;
            esac
            tap "$A_X" "$A_Y"
          fi
        fi
        sleep 2; i=$((i + 1))
        [ "$i" -le 8 ] && "$ADB" exec-out screencap -p > "$OUTD/$n-menu_$i.png" 2>/dev/null
      done
      echo "  $NAME: титул пройден ($(st))"
      REC_PID=""
      if [ "${BENCH_RECORD:-0}" = "1" ] && [ "$n" = "1" ]; then
        "$ADB" shell rm -f /sdcard/4dt_game.mp4
        "$ADB" shell screenrecord --time-limit "${BENCH_GAME_WAIT:-45}" --bit-rate 12000000 /sdcard/4dt_game.mp4 &
        REC_PID=$!
      fi
      if [ "$GAMEPLAY" = "1" ]; then
        # 1st cutscene (Kyrie): skip it a few seconds after it starts.
        wait_for 90 '[ "$(echo "$S" | sed -n "s/.*cutscenes=\([0-9]*\).*/\1/p")" -ge 1 ]'
        sleep 4
        "$ADB" exec-out screencap -p > "$OUTD/$n-skip_0.png" 2>/dev/null
        tap "$START_X" "$START_Y"
        # 2nd cutscene (dialogue): let it play BENCH_CUTSCENE2_WAIT s (voice in the log), skip.
        wait_for 40 '[ "$(echo "$S" | sed -n "s/.*cutscenes=\([0-9]*\).*/\1/p")" -ge 2 ]'
        sleep "${BENCH_CUTSCENE2_WAIT:-18}"
        "$ADB" exec-out screencap -p > "$OUTD/$n-skip_0b.png" 2>/dev/null
        for k in 1 2 3; do
          [ "$(stv scene)" = "0" ] && break
          tap "$START_X" "$START_Y"; sleep 3
        done
        # Mission start screen -> A; then the mission loads; A, A for the tutorial hints.
        sleep 2
        "$ADB" exec-out screencap -p > "$OUTD/$n-skip_1.png" 2>/dev/null
        tap "$A_X" "$A_Y"
        sleep "${BENCH_MISSION_LOAD:-25}"
        # A cutscene on the way in: skip it first.
        for k in 1 2 3; do
          [ "$(stv scene)" = "0" ] && break
          tap "$START_X" "$START_Y"; sleep 4
        done
        "$ADB" exec-out screencap -p > "$OUTD/$n-skip_2.png" 2>/dev/null
        tap "$A_X" "$A_Y"; sleep 3
        tap "$A_X" "$A_Y"; sleep 3
        "$ADB" exec-out screencap -p > "$OUTD/$n-skip_3.png" 2>/dev/null
        echo "  $NAME: геймплей ($(st))"
        # Gameplay input until the measurement ends: shoot (X) and walk left / right -
        # only while no cutscene plays (taps there would mean "skip").
        X_X=$(( SW - 177 * DENS / 160 )); X_Y=$(( SH - 175 * DENS / 160 ))
        LS_X=$(( 220 * DENS / 160 )); LS_Y=$(( SH - 175 * DENS / 160 )); LS_D=$(( 90 * DENS / 160 ))
        touch "$OUTD/.inputs"
        BENCH_PID=$$
        (
          # Stops with the measurement - and also if the bench itself is gone (Ctrl+C).
          while [ -f "$OUTD/.inputs" ] && kill -0 "$BENCH_PID" 2>/dev/null; do
            if [ "$(stv scene)" != "0" ]; then sleep 2; continue; fi
            for k in 1 2 3; do tap "$X_X" "$X_Y"; sleep 0.3; done
            "$ADB" shell input swipe "$LS_X" "$LS_Y" $((LS_X + LS_D)) "$LS_Y" 1200
            for k in 1 2 3; do tap "$X_X" "$X_Y"; sleep 0.3; done
            "$ADB" shell input swipe "$((LS_X + LS_D))" "$LS_Y" "$LS_X" "$LS_Y" 1200
          done
        ) &
      fi
      t=0
      while [ "$t" -lt "${BENCH_GAME_WAIT:-45}" ]; do
        sleep 5; t=$((t + 5))
        "$ADB" exec-out screencap -p > "$OUTD/$n-game_$(printf %03d $t)s.png" 2>/dev/null
      done
      if [ -n "$REC_PID" ]; then
        wait "$REC_PID" 2>/dev/null; sleep 1
        "$ADB" pull /sdcard/4dt_game.mp4 "$OUTD/$n-game.mp4" >/dev/null 2>&1
      fi
    fi
    "$ADB" exec-out run-as $PKG cat files/perf.csv > "$OUTD/$n-$NAME.before.csv" 2>/dev/null
    T0="$(skin_temp)"
    sleep "$MEASURE"
    T1="$(skin_temp)"
    rm -f "$OUTD/.inputs"
    "$ADB" exec-out run-as $PKG cat files/perf.csv > "$OUTD/$n-$NAME.csv" 2>/dev/null
    "$ADB" exec-out screencap -p > "$OUTD/$n-$NAME.png" 2>/dev/null
    if [ "${BENCH_TRACE:-1}" = "1" ] && [ "$n" = "1" ]; then
      # Подробный снимок одного кадра титульного экрана: команды GPU + текстуры из памяти.
      "$ADB" shell run-as $PKG rm -rf files/frame_trace.txt files/tex
      "$ADB" shell run-as $PKG touch files/trace_now
      for i in 1 2 3 4 5 6 7 8 9 10; do
        sleep 2
        "$ADB" shell run-as $PKG sh -c "'grep -q ^SWAP files/frame_trace.txt 2>/dev/null'" && break
      done
      "$ADB" exec-out run-as $PKG cat files/frame_trace.txt > "$OUTD/frame_trace.txt" 2>/dev/null
      "$ADB" exec-out run-as $PKG tar -cf - files/tex > "$OUTD/textures.tar" 2>/dev/null
      "$ADB" exec-out screencap -p > "$OUTD/frame_trace_screen.png" 2>/dev/null
      echo "  снимок кадра: $(grep -c '^D ' "$OUTD/frame_trace.txt" 2>/dev/null) draw, текстур $(tar -tf "$OUTD/textures.tar" 2>/dev/null | grep -c bin)"
      "$ADB" exec-out run-as $PKG tar -cf - files/shaders > "$OUTD/shaders.tar" 2>/dev/null
      [ -s "$OUTD/shaders.tar" ] || rm -f "$OUTD/shaders.tar"
    fi
    ALIVE="$("$ADB" shell pidof $PKG | tr -d '\r')"
    SKIP=$(wc -l < "$OUTD/$n-$NAME.before.csv" 2>/dev/null || echo 0)
    if [ -z "$ALIVE" ]; then
      printf "%-18s %s\n" "$NAME" "ВЫЛЕТ" | tee -a "$OUTD/summary.txt"
      "$ADB" logcat -d > "$OUTD/$n-$NAME.logcat.txt"
      continue
    fi
    tail -n +$((SKIP + 1)) "$OUTD/$n-$NAME.csv" | awk -F, '$1 ~ /^[0-9]+$/ && $1 > 0 { print $1 / 1000 }' \
      | sort -n > "$OUTD/$n-$NAME.ms.txt"
    awk -v name="$NAME" -v flags="${BOOT:+[3D через ${BOOT} с] }$FLAGS" -v temps="${T0:-?}→${T1:-?}" '
      { a[++n] = $1; t += $1; if ($1 <= 17.5) f60++; if ($1 <= 34.5) f30++; if ($1 > 50) slow++ }
      END {
        if (!n) { printf "%-18s нет данных\n", name; exit }
        med = a[int((n + 1) / 2)]; i95 = int(n * 0.95); if (i95 < 1) i95 = 1
        printf "%-18s %6.1f %7.1f %5.0f%% %5.0f%% %7.1f %6d %9s %s\n", name, n * 1000 / t, med, 100 * f60 / n, 100 * f30 / n, a[i95], slow, temps, flags
      }' "$OUTD/$n-$NAME.ms.txt" | tee -a "$OUTD/summary.txt"
    # Стоимость компиляции конвейеров драйвером (строки PSOTIME) и оптимизатора SPIR-V (SPVOPT).
    "$ADB" exec-out run-as $PKG cat files/dmc4.log 2>/dev/null > "$OUTD/$n-$NAME.full.log"
    tail -n +$((LOG0 + 1)) "$OUTD/$n-$NAME.full.log" > "$OUTD/$n-$NAME.log"
    # The log may have been restarted by the new launch - then take it whole.
    [ -s "$OUTD/$n-$NAME.log" ] || cp "$OUTD/$n-$NAME.full.log" "$OUTD/$n-$NAME.log"
    rm -f "$OUTD/$n-$NAME.full.log"
    awk -v name="$NAME" '
      / PSOTIME / { for (i = 1; i <= NF; i++) if ($i == "PSOTIME") us = $(i + 1);
                    ph = ($0 ~ /placeholder=1/); if (ph) { pn++; pt += us } else { rn++; rt += us; if (us > rmax) rmax = us } }
      / SPVOPT / { for (i = 1; i <= NF; i++) if ($i == "SPVOPT") { a += $(i + 1); b += $(i + 3); ot += $(i + 6); on++ } }
      END {
        printf "%-18s конвейеры: %d шт, всего %.1f с, средн %.0f мс, макс %.0f мс; заглушки: %d шт, %.1f с",
          name, rn, rt / 1e6, rn ? rt / rn / 1000 : 0, rmax / 1000, pn, pt / 1e6
        if (on) printf "; SPIR-V опт: %d шт, %d→%d слов, %.1f с", on, a, b, ot / 1e6
        printf "\n"
      }' "$OUTD/$n-$NAME.log" | tee -a "$OUTD/compile.txt"
    grep -h "GPUSTAT" "$OUTD/$n-$NAME.log" | tail -1 | sed "s/.*GPUSTAT/$NAME GPUSTAT/" | tee -a "$OUTD/compile.txt"
    grep -h "Fast resolve:" "$OUTD/$n-$NAME.log" | sed 's/.*Fast resolve:/  fast resolve:/' | sort | uniq | head -20 >> "$OUTD/compile.txt"
  done 3< "$SET"
  cat "$OUTD/compile.txt" >> "$OUTD/summary.txt" 2>/dev/null
  "$ADB" shell run-as $PKG rm -f files/args.txt
  "$ADB" shell am force-stop $PKG
  echo "результаты: $OUTD/summary.txt"; echo "BENCH_DONE"; exit 0
fi

if [ "${PULL_TEX:-0}" = "1" ]; then
  # Текстуры последнего снимка кадра (TRACE=1) -> work/logs/textures_trace.tar
  "$ADB" exec-out run-as $PKG tar -cf - files/tex > "$WORK/logs/textures_trace.tar" 2>/dev/null
  echo "текстур: $(tar -tf "$WORK/logs/textures_trace.tar" 2>/dev/null | grep -c bin)"
  echo "PULL_TEX_DONE"; exit 0
fi

if [ "${TRACE:-0}" = "1" ]; then
  # Каждый снимок - в свою папку logs/trace_<время>[_<TRACE_NAME>]: ничего не перезаписывается.
  # TRACE_NAME=menu_pause - подпись (что на экране); TRACE_TEX=0 - без текстур (~200 МБ).
  "$ADB" shell pidof $PKG >/dev/null 2>&1 || { echo "Игра не запущена"; exit 1; }
  TD="$WORK/logs/trace_$(date +%m%d_%H%M%S)${TRACE_NAME:+_$TRACE_NAME}"; mkdir -p "$TD"
  "$ADB" exec-out screencap -p > "$TD/screen_before.png" 2>/dev/null
  "$ADB" shell run-as $PKG rm -rf files/frame_trace.txt files/tex
  "$ADB" shell run-as $PKG touch files/trace_now
  echo "== запись кадра (стой на месте)..."
  for i in $(seq 1 30); do
    sleep 1
    if "$ADB" shell run-as $PKG sh -c "'grep -q ^SWAP files/frame_trace.txt 2>/dev/null'"; then break; fi
  done
  "$ADB" exec-out run-as $PKG cat files/frame_trace.txt > "$TD/frame_trace.txt" 2>/dev/null
  "$ADB" exec-out screencap -p > "$TD/screen.png" 2>/dev/null
  "$ADB" exec-out run-as $PKG cat files/state.txt > "$TD/state.txt" 2>/dev/null
  if [ "${TRACE_TEX:-1}" = "1" ]; then
    sleep 3
    "$ADB" exec-out run-as $PKG tar -cf - files/tex > "$TD/textures.tar" 2>/dev/null
  fi
  echo "папка: $TD"
  echo "отрисовок: $(grep -c '^D ' "$TD/frame_trace.txt"), копий: $(grep -c '^C ' "$TD/frame_trace.txt"), переносов: $(grep -c '^T ' "$TD/frame_trace.txt")"
  echo "TRACE_DONE"; exit 0
fi

if [ "${GPUCOUNTERS:-0}" = "1" ]; then
  T="$ROOT/tools/malic"
  if [ ! -x "$T/malicounters" ]; then
    echo "== сборка malicounters (один раз)"
    bash "$T/build_mac.sh" || { echo "!!! сборка malicounters не удалась"; exit 1; }
  fi
  "$ADB" push "$T/malicounters" /data/local/tmp/malicounters >/dev/null
  "$ADB" shell chmod 755 /data/local/tmp/malicounters
  echo "== счётчики Mali 8 с (не трогай телефон)"
  "$ADB" shell /data/local/tmp/malicounters 8 > "$WORK/logs/gpu_counters.txt" 2>&1
  head -3 "$WORK/logs/gpu_counters.txt"; wc -l "$WORK/logs/gpu_counters.txt"
  echo "GPUCOUNTERS_DONE"; exit 0
fi

if [ "${CLEAR_CACHE:-0}" = "1" ]; then
  "$ADB" shell am force-stop $PKG
  "$ADB" shell run-as $PKG rm -rf files/cache && echo "== кэш шейдеров/конвейеров очищен"
fi

echo "== установка APK"
grep -q "ANDROID_BUILD_OK" "$WORK/logs/android_build.log" 2>/dev/null || { echo "!!! последняя сборка не завершилась (нет ANDROID_BUILD_OK)"; [ "${FORCE_OLD:-0}" = "1" ] || exit 1; }
"$ADB" install -r "$APK" || exit 1
"$ADB" shell am force-stop $PKG

if [ "${SKIP_DATA:-0}" != "1" ]; then
  HAVE="$("$ADB" shell run-as $PKG ls files/game/default.xex 2>/dev/null | tr -d '\r')"
  if [ -n "$HAVE" ] && [ "${FORCE_DATA:-0}" != "1" ]; then
    echo "== файлы игры уже на телефоне (FORCE_DATA=1 — залить заново)"
  else
    # GAME_SRC: образ .iso, папка Games on Demand (внешняя "2008 Devil May Cry 4", папка
    # 434307DF или .data - всё равно) или уже распакованная папка с default.xex.
    DATA="$ROOT/extracted"
    if [ -n "${GAME_SRC:-}" ]; then
      if [ -f "$GAME_SRC/default.xex" ]; then DATA="$GAME_SRC"
      else
        DATA="$ROOT/extracted_$(basename "$GAME_SRC" | tr -c 'A-Za-z0-9._-' '_')"
        if [ ! -f "$DATA/.complete" ]; then
          echo "== распаковка $GAME_SRC -> $DATA"
          python3 "$ROOT/tools/xdvdfs_extract.py" "$GAME_SRC" "$DATA" | tail -n 2 || exit 1
          [ -f "$DATA/default.xex" ] && touch "$DATA/.complete"
        fi
      fi
    fi
    [ -f "$DATA/default.xex" ] || { echo "!!! нет $DATA/default.xex"; exit 1; }
    echo "== данные игры: $DATA"
    FREE="$("$ADB" shell df -k /data | awk 'NR==2{print int($4/1048576)}' | tr -d '\r')"
    echo "== копирование файлов игры ($(du -sh "$DATA/nativeXenon" | cut -f1)) на телефон, свободно ${FREE} ГБ"
    echo "   идёт 5–15 минут, прогресса не видно — просто ждите"
    "$ADB" shell run-as $PKG rm -rf files/game
    "$ADB" shell run-as $PKG mkdir -p files/game
    START=$(date +%s)
    COPYFILE_DISABLE=1 tar --no-xattrs --no-mac-metadata --exclude .DS_Store -cf - -C "$DATA" default.xex nativeXenon \
      | "$ADB" exec-in run-as $PKG tar -xf - -C files/game || { echo "!!! ошибка копирования"; exit 1; }
    echo "   готово за $(( $(date +%s) - START )) с"
    "$ADB" shell run-as $PKG du -sh files/game
  fi
fi
"$ADB" logcat -c
echo "== запуск"
"$ADB" shell am start -n "$ACT"
echo "INSTALL_DONE  (через 1–2 минуты:  LOGS=1 bash \"$0\")"
