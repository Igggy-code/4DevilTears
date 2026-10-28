package com.fourdeviltears.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.graphics.Paint;
import android.graphics.RectF;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.SparseArray;
import android.view.HapticFeedbackConstants;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;

/**
 * On-screen Xbox 360 controller. Presses go to native code, which feeds an SDL
 * virtual gamepad, so the game sees an ordinary controller.
 *
 * Layout (landscape), mirroring the 360 pad:
 *   LT LB          BACK [pad] START          RB RT
 *   (left stick)                     Y
 *        [D-pad]                   X   B
 *                     (right stick)  A
 * The left and right halves of the screen are "floating" stick zones: touch
 * anywhere there (outside the buttons) and the stick centres under the finger.
 * Double-tap a stick zone and hold to click the stick (L3 / R3).
 * The small pad icon at the top hides / shows the overlay; it hides itself
 * automatically while a physical controller is connected.
 */
public class TouchGamepadView extends View implements InputManager.InputDeviceListener {
    private static final String TAG = "4DevilTearsTouch";

    // SDL_GamepadButton
    static final int BTN_A = 0, BTN_B = 1, BTN_X = 2, BTN_Y = 3, BTN_BACK = 4, BTN_START = 6,
            BTN_LS = 7, BTN_RS = 8, BTN_LB = 9, BTN_RB = 10,
            BTN_UP = 11, BTN_DOWN = 12, BTN_LEFT = 13, BTN_RIGHT = 14;
    // SDL_GamepadAxis
    static final int AX_LX = 0, AX_LY = 1, AX_RX = 2, AX_RY = 3, AX_LT = 4, AX_RT = 5;

    static native boolean nativeAttach();
    static native void nativeButton(int button, boolean down);
    static native void nativeAxis(int axis, float value);
    static native float nativeFps();
    static native int nativeSceneMode();
    static native int nativeMenuState();
    static native int nativeListMenu();
    static native int nativeListCursor();
    static native int nativeTitleCursor();
    static native boolean nativeHudActive();
    static native int nativeUiSprites(int[] out);
    static native boolean nativeSetListCursor(int value);
    static native void nativeUiCaptureFrame(String path);
    static native boolean nativeSetTitleCursor(int value);

    // List menus (uListSelect subclasses) by vtable: touch rows in 1280x720 game space.
    // { vtable, x0, x1, half row height, row centre y... } - rows in cursor order.
    private static final int[][] MENU_ROWS = {
        { 0x820A8C70, 220, 640, 24, 502, 551, 600 },                 // mission start
        { 0x8208E508, 200, 640, 19, 128, 168, 408, 448, 528, 608 },  // mission menu
        { 0x820AA1F8, 250, 540, 26, 287, 342, 398, 453, 509 },       // pause menu
    };
    // Title main menu (New Game / Load Game / Options), title state 7.
    private static final int[] TITLE_ROWS = { 0, 500, 780, 21, 517, 563, 608 };
    // Menus where the on-screen controller is hidden (touch / gestures instead).
    private static final int[] KNOWN_MENUS = { 0x820A8C70, 0x8208E508, 0x820A7FA8, 0x820AA1F8 };
    private int listVt = 0;
    // Menus recognised from the sprites the game draws (see UiTouch).
    private final UiTouch ui = new UiTouch();
    private boolean uiMenu = false;
    // Direct cursor placement: trusted per menu once the game's cursor value and the
    // highlighted row (screen order) have agreed on two different rows and never disagreed.
    private long cursorKey = 0;
    private final java.util.HashSet<Integer> cursorAgree = new java.util.HashSet<>();
    private int cursorAgreeTicks = 0, cursorDisagreeTicks = 0;
    // The pause menu was the last menu: closing it returns to the game (its HUD may come late).
    private boolean lastMenuWasPause = false;
    private long pauseSeenAt = 0;
    // A tap on a clean picture with no menu and no cutscene brings the controller back.
    private boolean tapReveal = false;
    private int sceneSeen = -1;
    private long sceneSince = 0;

    private static boolean knownMenu(int vt) {
        for (int k : KNOWN_MENUS) if (k == vt) return true;
        return false;
    }

    private static int[] rowsFor(int vt) {
        for (int[] r : MENU_ROWS) if (r[0] == vt) return r;
        return null;
    }

    /** Screen -> 1280x720 game coordinates for the current picture scaling. */
    private float[] toGame(float x, float y) {
        float w = getWidth(), h = getHeight();
        float sx, sy;
        if ("stretch".equals(DevilActivity.sScale) || "wide".equals(DevilActivity.sScale)) { sx = w / 1280f; sy = h / 720f; }
        else if ("fill".equals(DevilActivity.sScale)) { sx = sy = Math.max(w / 1280f, h / 720f); }
        else { sx = sy = Math.min(w / 1280f, h / 720f); }
        float ox = (w - 1280f * sx) / 2f, oy = (h - 720f * sy) / 2f;
        return new float[] { (x - ox) / sx, (y - oy) / sy };
    }

    private static boolean sNativeOk = true;

    private static void button(int b, boolean down) {
        if (!sNativeOk) return;
        try { nativeButton(b, down); } catch (UnsatisfiedLinkError e) { sNativeOk = false; Log.e(TAG, "native", e); }
    }

    private static void axis(int a, float v) {
        if (!sNativeOk) return;
        try { nativeAxis(a, v); } catch (UnsatisfiedLinkError e) { sNativeOk = false; Log.e(TAG, "native", e); }
    }

    // ---- controls -------------------------------------------------------------------------

    private static final int K_BUTTON = 0, K_TRIGGER = 1, K_DPAD = 2, K_LSTICK = 3, K_RSTICK = 4, K_TOGGLE = 5;

    private static class Ctl {
        final int kind, id;
        final String label;
        final int color;
        float cx, cy, r;           // circle (buttons, d-pad, stick base)
        RectF rect;                // rounded rect (shoulders, triggers, back/start)
        boolean pressed;
        Ctl(int kind, int id, String label, int color) { this.kind = kind; this.id = id; this.label = label; this.color = color; }
        boolean hit(float x, float y, float slop) {
            if (rect != null) {
                return x >= rect.left - slop && x <= rect.right + slop && y >= rect.top - slop && y <= rect.bottom + slop;
            }
            float dx = x - cx, dy = y - cy, rr = r + slop;
            return dx * dx + dy * dy <= rr * rr;
        }
    }

    private static class Stick {
        final int axX, axY, click;
        float homeX, homeY, baseX, baseY, knobX, knobY, radius;
        int pointer = -1;
        long lastDown;
        boolean clicked;
        Stick(int axX, int axY, int click) { this.axX = axX; this.axY = axY; this.click = click; }
    }

    private final Ctl[] ctls;
    private final Ctl dpad, toggle;
    private final Stick lstick = new Stick(AX_LX, AX_LY, BTN_LS);
    private final Stick rstick = new Stick(AX_RX, AX_RY, BTN_RS);
    private final SparseArray<Object> owners = new SparseArray<>();   // pointer id -> Ctl / Stick
    private int dpadMask;                                             // bits: up down left right

    private final float dp;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);

    private boolean overlayVisible = true;
    private boolean userHidden = false;
    // Cinema mode: hidden (nothing drawn at all) while the game plays a cutscene / movie.
    private boolean cinemaHidden = false;
    private int sceneMode = 0;
    private int menuState = 0;
    // files/state.txt for the automated tests: what the game shows right now.
    private int lastScene = -1, lastMenu = -1, cutscenes = 0, movies = 0;

    // Intro movie auto-skip: the first movie of the session, half a second after it starts.
    private long introSeenAt = 0;
    private boolean introDone = false;
    // Black cover over the intro movie while it is being skipped (no half-second of it).
    private long introMaskUntil = 0;
    // Boot (logos, disclaimer, intro): no controls until the title screen (touch still works).
    private boolean bootDone = false;
    private final long createdAt = System.currentTimeMillis();
    // Controls come back only after the game has been out of cutscenes / menus for a moment,
    // so they don't flash between two movies or during a fade.
    private long clearSince = 0;
    private static final long SHOW_DELAY_MS = 1200;

    private void maybeSkipIntro() {
        if (introDone || !DevilActivity.sSkipIntro) return;
        long now = System.currentTimeMillis();
        if (sceneMode != 2) {
            if (introSeenAt != 0) introDone = true;  // it ended on its own
            return;
        }
        introMaskUntil = now + 400;
        if (introSeenAt == 0) { introSeenAt = now; return; }
        if (now - introSeenAt >= 500) {
            introDone = true;
            introMaskUntil = now + 1500;  // until the movie has really gone
            button(BTN_START, true);
            handler.postDelayed(() -> button(BTN_START, false), 120);
        }
    }

    /** Automated tests: files/flags_now.txt (name=value per line) switches runtime flags
     *  without restarting the game - several variants are compared in one session. */
    private int liveFlagsTick = 0;
    private void applyLiveFlags() {
        if (++liveFlagsTick % 4 != 0) return;
        java.io.File f = new java.io.File(getContext().getFilesDir(), "flags_now.txt");
        if (!f.exists()) return;
        try {
            for (String line : java.nio.file.Files.readAllLines(f.toPath())) {
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                String name = line.substring(0, eq).trim(), value = line.substring(eq + 1).trim();
                boolean ok = DevilActivity.nativeSetFlag(name, value);
                Log.i(TAG, "live flag " + name + "=" + value + (ok ? "" : " (rejected)"));
            }
        } catch (Exception e) {
            Log.w(TAG, "flags_now", e);
        }
        f.renameTo(new java.io.File(getContext().getFilesDir(), "flags_applied.txt"));
    }

    // Automated tests (files/flash_watch exists): from leaving the title until a few seconds into
    // the first cutscene, copy the screen ~15 times a second and keep frames with rows of strongly
    // saturated green / red (short flashes a screenshot from adb can't catch) as flash_N.png.
    private boolean flashTitleSeen = false, flashActive = false, flashDone = false;
    private long flashStart = 0, flashCutsceneAt = 0;
    private int flashSaved = 0, flashFrames = 0;
    private android.os.HandlerThread flashThread;
    private android.os.Handler flashHandler;
    private android.graphics.Bitmap flashPrev;

    // UI capture (files/ui_capture exists): a screenshot of every distinct menu screen / cursor
    // position, next to the game's own sprite layout (UISPR lines in dmc4.log).
    private String uiLastKey = "", uiPendingKey = "";
    private long uiPendingSince = 0, uiLastTry = 0;
    private long noHudSince = 0;
    private java.util.HashSet<String> uiSaved = new java.util.HashSet<>();
    private Boolean uiCaptureOn = null;

    private void uiCaptureTick() {
        if (uiCaptureOn == null) {
            uiCaptureOn = new java.io.File(getContext().getFilesDir(), "ui_capture").exists();
        }
        if (!uiCaptureOn || android.os.Build.VERSION.SDK_INT < 24) return;
        int cur = -1;
        try {
            cur = (menuState & 0x10000) != 0 ? nativeTitleCursor() : (listVt != 0 ? nativeListCursor() : -1);
        } catch (UnsatisfiedLinkError e) { }
        String key = Integer.toHexString(menuState) + "_" + Integer.toHexString(listVt) + "_" + cur
                + "_s" + sceneMode;
        long now = System.currentTimeMillis();
        if (!key.equals(uiPendingKey)) { uiPendingKey = key; uiPendingSince = now; return; }
        if (now - uiPendingSince < 700 || uiSaved.contains(key) || uiSaved.size() > 300) return;
        if (key.equals(uiLastKey) && now - uiLastTry < 1500) return;
        uiLastKey = key;
        uiLastTry = now;
        android.view.SurfaceView sv = DevilActivity.gameSurface();
        if (sv == null || !sv.getHolder().getSurface().isValid()) return;
        final android.graphics.Bitmap bmp =
                android.graphics.Bitmap.createBitmap(1280, 576, android.graphics.Bitmap.Config.ARGB_8888);
        final String name = "ui_" + key + ".png";
        try {
            android.view.PixelCopy.request(sv, bmp, result -> {
                if (result == android.view.PixelCopy.SUCCESS) {
                    saveFlash(bmp, name);
                    uiSaved.add(name.substring(3, name.length() - 4));
                }
            }, handler);
        } catch (Exception ignored) { }
    }

    private void flashWatchTick() {
        if (flashDone) return;
        if (!flashActive) {
            if (!new java.io.File(getContext().getFilesDir(), "flash_watch").exists()) {
                flashDone = true;
                return;
            }
            if ((menuState & 0x10000) != 0) { flashTitleSeen = true; return; }
            if (!flashTitleSeen) return;
            flashActive = true;
            flashStart = System.currentTimeMillis();
            flashThread = new android.os.HandlerThread("flashwatch");
            flashThread.start();
            flashHandler = new android.os.Handler(flashThread.getLooper());
            flashHandler.post(flashGrab);
            Log.i(TAG, "flash watch: started");
            try (java.io.FileWriter lw = new java.io.FileWriter(
                    new java.io.File(getContext().getFilesDir(), "flash_log.txt"), true)) {
                lw.write("started\n");
            } catch (Exception ignored) {
            }
            return;
        }
        long now = System.currentTimeMillis();
        if (cutscenes >= 1 && flashCutsceneAt == 0) flashCutsceneAt = now;
        if ((flashCutsceneAt != 0 && now - flashCutsceneAt > 5000) || now - flashStart > 120000) {
            flashActive = false;
            flashDone = true;
            Log.i(TAG, "flash watch: done, " + flashFrames + " frames, " + flashSaved + " saved");
            final int frames = flashFrames, saved = flashSaved;
            if (flashHandler != null) {
                flashHandler.post(() -> {
                    flashSheetFlush();
                    try (java.io.FileWriter lw = new java.io.FileWriter(
                            new java.io.File(getContext().getFilesDir(), "flash_log.txt"), true)) {
                        lw.write("done: " + frames + " frames grabbed, " + saved + " flagged\n");
                    } catch (Exception ignored) {
                    }
                });
            }
            if (flashThread != null) flashThread.quitSafely();
        }
    }

    private final Runnable flashGrab = new Runnable() {
        @Override public void run() {
            if (!flashActive) return;
            android.view.SurfaceView sv = DevilActivity.gameSurface();
            if (sv == null || android.os.Build.VERSION.SDK_INT < 24 || !sv.getHolder().getSurface().isValid()) {
                flashHandler.postDelayed(this, 200);
                return;
            }
            final android.graphics.Bitmap bmp =
                    android.graphics.Bitmap.createBitmap(480, 216, android.graphics.Bitmap.Config.ARGB_8888);
            final long t0 = System.currentTimeMillis();
            try {
                android.view.PixelCopy.request(sv, bmp, result -> {
                    if (result == android.view.PixelCopy.SUCCESS) flashCheck(bmp, t0);
                    long wait = Math.max(0, 66 - (System.currentTimeMillis() - t0));
                    if (flashActive) flashHandler.postDelayed(flashGrab, wait);
                }, flashHandler);
            } catch (Exception e) {
                flashHandler.postDelayed(this, 200);
            }
        }
    };

    // Every grabbed frame also goes into contact sheets (10x10 thumbnails 96x43 per sheet) - the
    // whole sequence around the transition can be inspected afterwards.
    private android.graphics.Bitmap flashSheet;
    private int flashSheetCount = 0, flashSheetIndex = 0;

    private void flashSheetAdd(android.graphics.Bitmap bmp) {
        if (flashSheet == null) {
            flashSheet = android.graphics.Bitmap.createBitmap(960, 430, android.graphics.Bitmap.Config.ARGB_8888);
            flashSheetCount = 0;
        }
        android.graphics.Canvas c = new android.graphics.Canvas(flashSheet);
        int x = (flashSheetCount % 10) * 96, y = (flashSheetCount / 10) * 43;
        c.drawBitmap(bmp, null, new android.graphics.Rect(x, y, x + 96, y + 43), null);
        if (++flashSheetCount == 100) flashSheetFlush();
    }

    private void flashSheetFlush() {
        if (flashSheet == null || flashSheetCount == 0) return;
        saveFlash(flashSheet, "flash_sheet_" + (flashSheetIndex++) + ".png");
        flashSheet = null;
        flashSheetCount = 0;
    }

    private void flashCheck(android.graphics.Bitmap bmp, long t) {
        flashFrames++;
        if (flashSheetIndex < 12) flashSheetAdd(bmp);
        int w = bmp.getWidth(), h = bmp.getHeight();
        int[] row = new int[w];
        int green = 0, red = 0, first = -1, last = -1;
        for (int y = 0; y < h; y++) {
            bmp.getPixels(row, 0, w, 0, y, w, 1);
            int g = 0, r = 0;
            for (int x = 0; x < w; x++) {
                int c = row[x], R = (c >> 16) & 255, G = (c >> 8) & 255, B = c & 255;
                if (G > R + 40 && G > B + 40 && G > 80) g++;
                if (R > G + 60 && R > B + 60) r++;
            }
            boolean flagged = false;
            if (g > w * 0.3) { green++; flagged = true; }
            if (r > w * 0.3) { red++; flagged = true; }
            if (flagged) { if (first < 0) first = y; last = y; }
        }
        if ((green > 0 || red > 0) && flashSaved < 40) {
            String name = "flash_" + flashSaved + ".png";
            Log.i(TAG, "flash watch: " + name + " at +" + (t - flashStart) + " ms, green rows " + green
                    + ", red rows " + red + ", rows " + first + "-" + last + " of " + h
                    + ", cutscenes " + cutscenes + ", scene " + sceneMode);
            saveFlash(bmp, name);
            try (java.io.FileWriter lw = new java.io.FileWriter(
                    new java.io.File(getContext().getFilesDir(), "flash_log.txt"), true)) {
                lw.write(name + " +" + (t - flashStart) + "ms green_rows=" + green + " red_rows=" + red
                        + " rows=" + first + "-" + last + "/" + h + " cutscenes=" + cutscenes
                        + " scene=" + sceneMode + "\n");
            } catch (Exception ignored) {
            }
            if (flashPrev != null) saveFlash(flashPrev, "flash_" + flashSaved + "_prev.png");
            flashSaved++;
        }
        flashPrev = bmp;
    }

    private void saveFlash(android.graphics.Bitmap bmp, String name) {
        try (java.io.FileOutputStream f = new java.io.FileOutputStream(
                new java.io.File(getContext().getFilesDir(), name))) {
            bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, f);
        } catch (Exception ignored) {
        }
    }

    private void publishState() {
        if (sceneMode == lastScene && menuState == lastMenu) return;
        if (sceneMode == 1 && lastScene != 1) cutscenes++;
        if (sceneMode == 2 && lastScene != 2) movies++;
        lastScene = sceneMode;
        lastMenu = menuState;
        String line = "scene=" + sceneMode + " title=" + ((menuState & 0x10000) != 0 ? ((menuState >> 8) & 0xFF) : -1)
                + " menu=" + Integer.toHexString(listVt)
                + " cutscenes=" + cutscenes + " movies=" + movies + "\n";
        try (java.io.FileOutputStream f = new java.io.FileOutputStream(
                new java.io.File(getContext().getFilesDir(), "state.txt"))) {
            f.write(line.getBytes());
        } catch (Exception ignored) {
        }
    }
    // Menu gestures (title screen for now): tap = confirm, swipe = D-pad, two fingers = back.
    private float gestX, gestY;
    private int gestPid = -1;
    // Touch-to-skip: first tap shows the hint, a second tap within the window presses START.
    private long hintShownAt = 0, hintUntil = 0;
    private static final long HINT_MS = 3000;
    private final Paint hintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hintBand = new Paint();
    // Idle fade (settings: opacity / fade when idle).
    private long lastTouch = System.currentTimeMillis();
    private long toggleDownTime;

    /** From the settings: hide / show the on-screen controller. */
    public void setUserHidden(boolean hidden) {
        userHidden = hidden;
        updateVisibility();
    }
    private final Handler handler = new Handler(Looper.getMainLooper());
    private InputManager inputManager;

    public TouchGamepadView(Context context) {
        super(context);
        dp = context.getResources().getDisplayMetrics().density;
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(2f * dp);
        text.setTextAlign(Paint.Align.CENTER);
        text.setFakeBoldText(true);

        Ctl a = new Ctl(K_BUTTON, BTN_A, "A", 0xFF5DBB3F);
        Ctl b = new Ctl(K_BUTTON, BTN_B, "B", 0xFFE0453A);
        Ctl x = new Ctl(K_BUTTON, BTN_X, "X", 0xFF3A7BE0);
        Ctl y = new Ctl(K_BUTTON, BTN_Y, "Y", 0xFFF2C12E);
        Ctl lb = new Ctl(K_BUTTON, BTN_LB, "LB", Color.WHITE);
        Ctl rb = new Ctl(K_BUTTON, BTN_RB, "RB", Color.WHITE);
        Ctl lt = new Ctl(K_TRIGGER, AX_LT, "LT", Color.WHITE);
        Ctl rt = new Ctl(K_TRIGGER, AX_RT, "RT", Color.WHITE);
        Ctl back = new Ctl(K_BUTTON, BTN_BACK, "BACK", Color.WHITE);
        Ctl start = new Ctl(K_BUTTON, BTN_START, "START", Color.WHITE);
        dpad = new Ctl(K_DPAD, 0, "", Color.WHITE);
        toggle = new Ctl(K_TOGGLE, 0, "◐", Color.WHITE);
        ctls = new Ctl[] { a, b, x, y, lb, rb, lt, rt, back, start, dpad, toggle };

        setFocusable(false);
        handler.post(attachPoll);
        handler.postDelayed(fpsPoll, 1000);
    }

    // FPS counter (guest frames per second, published by the GPU backend).
    private float fps = -1f;
    private final Paint fpsPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Runnable fpsPoll = new Runnable() {
        @Override public void run() {
            if (sNativeOk) {
                try { fps = nativeFps(); } catch (UnsatisfiedLinkError e) { fps = -1f; }
                try { sceneMode = nativeSceneMode(); } catch (UnsatisfiedLinkError e) { sceneMode = 0; }
                try { menuState = nativeMenuState(); } catch (UnsatisfiedLinkError e) { menuState = 0; }
                try { listVt = nativeListMenu(); } catch (UnsatisfiedLinkError e) { listVt = 0; }
                if (menuState == 0 && knownMenu(listVt)) menuState = 0x20000;
                publishState();
                flashWatchTick();
                uiCaptureTick();
                maybeSkipIntro();
                applyLiveFlags();
                long now = System.currentTimeMillis();
                if (!bootDone && ((menuState & 0x10000) != 0 || sceneMode == 1
                        || now - createdAt > 150000)) bootDone = true;
                if (introMaskUntil > now && sceneMode == 2) introMaskUntil = Math.max(introMaskUntil, now + 400);
                // Touches made on the clean picture (menus, skip hotspots) never bring the
                // controller up - only a finger already on the visible controller keeps it.
                boolean touching = !cinemaHidden && (owners.size() != 0 || gestPid != -1);
                boolean hud = false;
                try { hud = nativeHudActive(); } catch (UnsatisfiedLinkError e) { hud = sceneMode == 0; }
                // Cinema mode: the controller only while the player controls the character - the
                // gameplay HUD is drawn, no cutscene / movie, no menu (any list menu, title).
                // Any menu screen the game draws (rows / button prompts) - also the ones not
                // known by their list object (options, settings pages...).
                uiMenu = sceneMode == 0 && !hud && ui.refresh() && ui.hasMenu();
                if (sceneMode != sceneSeen) { sceneSeen = sceneMode; sceneSince = now; }
                learnCursor();
                // A screenshot job asks for the draws of this screen too (files/ui_capture_now).
                if (++captureFlagTick % 10 == 0) {
                    java.io.File flag = new java.io.File(getContext().getFilesDir(), "ui_capture_now");
                    if (flag.exists() && flag.delete()) { lastCapture = 0; captureScreen(); }
                }
                if (listVt == PAUSE_VT) { lastMenuWasPause = true; pauseSeenAt = now; }
                else if (hud || sceneMode != 0 || menuState != 0 || listVt != 0
                        || (uiMenu && now - pauseSeenAt > 1000)) lastMenuWasPause = false;
                if (hud || sceneMode != 0 || menuState != 0 || listVt != 0 || uiMenu) tapReveal = false;
                boolean gameplay = hud && sceneMode == 0 && menuState == 0 && listVt == 0;
                if (!gameplay && bootDone && sceneMode == 0 && menuState == 0 && listVt == 0 && !uiMenu
                        && (lastMenuWasPause || tapReveal)) gameplay = true;
                // Safety net: no menu / cutscene for a while but no HUD either (a gameplay part
                // without the HUD?) - give the controller back rather than leave the player stuck.
                if (!hud && !uiMenu && bootDone && sceneMode == 0 && menuState == 0 && listVt == 0) {
                    if (noHudSince == 0) noHudSince = now;
                    if (now - noHudSince > 6000) gameplay = true;
                } else noHudSince = 0;
                boolean want = DevilActivity.sCinemaMode && !touching && !gameplay;
                boolean ch;
                if (want) { ch = true; clearSince = 0; }
                else if (cinemaHidden && !touching) {
                    if (clearSince == 0) clearSince = now;
                    ch = now - clearSince < SHOW_DELAY_MS;
                } else ch = false;
                if (ch != cinemaHidden) {
                    cinemaHidden = ch;
                    if (!ch) hintUntil = 0;
                    updateVisibility();
                }
                updateAlpha(false);
                invalidate();
            }
            handler.postDelayed(this, 100);  // the controller must not flash up on scene changes
        }
    };

    private void drawFps(Canvas canvas) {
        if (fps < 0 || !DevilActivity.sShowFps) return;
        fpsPaint.setTextAlign(Paint.Align.CENTER);
        fpsPaint.setFakeBoldText(true);
        fpsPaint.setTextSize(13 * dp);
        fpsPaint.setColor(fps >= 25 ? 0xCC7CFC00 : (fps >= 15 ? 0xCCFFD700 : 0xCCFF5050));
        canvas.drawText(String.format(java.util.Locale.US, "%.1f FPS", fps),
                toggle.cx, toggle.cy + toggle.r + 16 * dp, fpsPaint);
    }

    private final Runnable attachPoll = new Runnable() {
        @Override public void run() {
            boolean ok = false;
            try { ok = nativeAttach(); } catch (UnsatisfiedLinkError e) { sNativeOk = false; Log.e(TAG, "native", e); return; }
            if (!ok) handler.postDelayed(this, 500);
            else Log.i(TAG, "virtual gamepad ready");
        }
    };

    // ---- physical controller detection ----------------------------------------------------

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        inputManager = (InputManager) getContext().getSystemService(Context.INPUT_SERVICE);
        if (inputManager != null) inputManager.registerInputDeviceListener(this, handler);
        updateVisibility();
    }

    @Override protected void onDetachedFromWindow() {
        if (inputManager != null) inputManager.unregisterInputDeviceListener(this);
        handler.removeCallbacks(attachPoll);
        handler.removeCallbacks(fpsPoll);
        releaseAll();
        super.onDetachedFromWindow();
    }

    @Override public void onInputDeviceAdded(int id) { updateVisibility(); }
    @Override public void onInputDeviceRemoved(int id) { updateVisibility(); }
    @Override public void onInputDeviceChanged(int id) { updateVisibility(); }

    private static boolean physicalPadConnected() {
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice d = InputDevice.getDevice(id);
            if (d == null || d.isVirtual()) continue;
            int s = d.getSources();
            if ((s & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                    || (s & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) {
                return true;
            }
        }
        return false;
    }

    private void updateVisibility() {
        boolean v = !userHidden && !cinemaHidden && !physicalPadConnected();
        if (v != overlayVisible) {
            overlayVisible = v;
            if (!v) releaseAll();
            invalidate();
        }
    }

    // ---- layout -----------------------------------------------------------------------------

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        // Sizes in dp; scale down a bit on short screens.
        // Designed for ~840x400 dp landscape; shrink uniformly to fit anything smaller
        // (including portrait / split-screen) so controls never overlap.
        float s = Math.min(1f, Math.min((w / dp) / 840f, (h / dp) / 400f)) * dp;
        float face = 27 * s, faceOff = 52 * s;
        float fcx = w - 125 * s, fcy = h - 175 * s;
        place(ctls[0], fcx, fcy + faceOff, face);   // A
        place(ctls[1], fcx + faceOff, fcy, face);   // B
        place(ctls[2], fcx - faceOff, fcy, face);   // X
        place(ctls[3], fcx, fcy - faceOff, face);   // Y

        float top = 14 * s, bw = 92 * s, bh = 42 * s, gap = 10 * s, side = 18 * s;
        ctls[6].rect = new RectF(side, top, side + bw, top + bh);                         // LT
        ctls[4].rect = new RectF(side + bw + gap, top + 18 * s, side + 2 * bw + gap, top + 18 * s + bh); // LB
        ctls[7].rect = new RectF(w - side - bw, top, w - side, top + bh);                 // RT
        ctls[5].rect = new RectF(w - side - 2 * bw - gap, top + 18 * s, w - side - bw - gap, top + 18 * s + bh); // RB

        float sw = 78 * s, sh = 34 * s;
        ctls[8].rect = new RectF(w / 2f - 60 * s - sw, top, w / 2f - 60 * s, top + sh);   // BACK
        ctls[9].rect = new RectF(w / 2f + 60 * s, top, w / 2f + 60 * s + sw, top + sh);   // START
        place(toggle, w / 2f, top + sh / 2f, 18 * s);

        place(dpad, 275 * s, h - 80 * s, 62 * s);

        lstick.radius = 62 * s;
        lstick.homeX = 125 * s; lstick.homeY = h - 175 * s;
        rstick.radius = 55 * s;
        rstick.homeX = w - 280 * s; rstick.homeY = h - 80 * s;
        resetStick(lstick);
        resetStick(rstick);

        text.setTextSize(15 * s);
    }

    private static void place(Ctl c, float x, float y, float r) { c.cx = x; c.cy = y; c.r = r; c.rect = null; }

    private void resetStick(Stick st) {
        st.baseX = st.knobX = st.homeX;
        st.baseY = st.knobY = st.homeY;
    }

    // ---- input ------------------------------------------------------------------------------

    @Override public boolean onTouchEvent(MotionEvent e) {
        int action = e.getActionMasked();
        int idx = e.getActionIndex();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                float x = e.getX(idx), y = e.getY(idx);
                lastTouch = System.currentTimeMillis();
                if (cinemaHidden && !userHidden && !physicalPadConnected()) {
                    if (sceneMode == 0 && (menuState != 0 || uiMenu)) menuTouchDown(e.getPointerId(idx), x, y, action);
                    else cinemaTouch(e.getPointerId(idx), x, y);
                    return true;
                }
                updateAlpha(true);
                if (!overlayVisible) {
                    if (toggle.hit(x, y, 12 * dp)) { userHidden = false; updateVisibility(); return true; }
                    return false;  // let the game see it
                }
                down(e.getPointerId(idx), x, y);
                break;
            }
            case MotionEvent.ACTION_MOVE:
                for (int i = 0; i < e.getPointerCount(); i++) move(e.getPointerId(i), e.getX(i), e.getY(i));
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                if (e.getPointerId(idx) == gestPid) {
                    menuTouchUp(e.getX(idx), e.getY(idx));
                    break;
                }
                up(e.getPointerId(idx));
                break;
            case MotionEvent.ACTION_CANCEL:
                releaseAll();
                break;
        }
        invalidate();
        return overlayVisible;
    }

    // ---- menus without the controller: tap = confirm, swipe = D-pad, two fingers = back -----

    private void menuTouchDown(int pid, float x, float y, int action) {
        if (action == MotionEvent.ACTION_POINTER_DOWN && gestPid != -1) {
            gestPid = -1;  // second finger: back
            pulse(BTN_B);
            return;
        }
        // START and A hotspots answer at once (automated tests, and where thumbs rest) -
        // unless the finger is on something the menu draws there.
        Ctl start = ctls[9], a = ctls[0];
        Ctl hot = start.hit(x, y, 8 * dp) ? start : (a.hit(x, y, 8 * dp) ? a : null);
        if (hot != null && uiMenu) {
            float[] g = toGame(x, y);
            UiTouch.Action act = ui.tap(g[0], g[1]);
            if (act != null && act.button >= 0) hot = null;
        }
        if (hot != null) {
            owners.put(pid, hot);
            press(hot, true);
            return;
        }
        gestPid = pid;
        gestX = x;
        gestY = y;
    }

    private void menuTouchUp(float x, float y) {
        gestPid = -1;
        float dx = x - gestX, dy = y - gestY;
        float slop = 28 * dp;
        if (Math.abs(dx) < slop && Math.abs(dy) < slop && menuTapRow(x, y)) {
            return;
        }
        if (Math.abs(dx) < slop && Math.abs(dy) < slop) {
            // Tap: START and A together - "press start" screens want START, lists want A.
            button(BTN_START, true);
            button(BTN_A, true);
            haptic();
            handler.postDelayed(() -> { button(BTN_START, false); button(BTN_A, false); }, 110);
        } else if (Math.abs(dy) >= Math.abs(dx)) {
            pulse(dy < 0 ? BTN_UP : BTN_DOWN);
        } else {
            pulse(dx < 0 ? BTN_LEFT : BTN_RIGHT);
        }
    }

    /** Tap on a row of a known list menu: on the selected row - confirm (A); on another row -
     *  move the cursor there with D-pad presses (the next tap confirms). */
    private boolean menuTapRow(float x, float y) {
        boolean title = (menuState & 0x10000) != 0;
        if (UiTouch.sLogFile == null) UiTouch.sLogFile = new java.io.File(getContext().getFilesDir(), "ui_taps.txt");
        // The title's main menu (state 7) keeps its own rows; every other screen - options
        // (title state 10), list menus, pages - is read from what the game draws.
        boolean titleMain = title && ((menuState >> 8) & 0xFF) == 7;
        if (!titleMain && ui.refresh()) {
            float[] g = toGame(x, y);
            UiTouch.Action act = ui.tap(g[0], g[1]);
            if (act != null) {
                if (act.button < 0) captureScreen();
                if (act.target >= 0 && setCursorDirect(act)) return true;
                if (act.button >= 0) {
                    final int btn = act.button;
                    for (int i = 0; i < act.times; i++) {
                        handler.postDelayed(() -> button(btn, true), i * 200L);
                        handler.postDelayed(() -> button(btn, false), i * 200L + 100);
                    }
                    haptic();
                }
                return true;
            }
        }
        int[] rows = title ? (((menuState >> 8) & 0xFF) == 7 ? TITLE_ROWS : null) : rowsFor(listVt);
        if (rows == null) return false;
        float[] g = toGame(x, y);
        if (g[0] < rows[1] || g[0] > rows[2]) return true;  // outside the list: ignore
        int target = -1;
        for (int i = 4; i < rows.length; i++) {
            if (Math.abs(g[1] - rows[i]) <= rows[3]) { target = i - 4; break; }
        }
        if (target < 0) return true;
        int cur;
        try { cur = title ? nativeTitleCursor() : nativeListCursor(); }
        catch (UnsatisfiedLinkError e) { cur = -1; }
        if (cur < 0 || cur > 64) return false;
        if (cur == target) { pulse(BTN_A); return true; }
        final int btn = target > cur ? BTN_DOWN : BTN_UP;
        int steps = Math.abs(target - cur);
        for (int i = 0; i < steps; i++) {
            handler.postDelayed(() -> { button(btn, true); }, i * 160L);
            handler.postDelayed(() -> { button(btn, false); }, i * 160L + 90);
        }
        haptic();
        return true;
    }

    private static final int PAUSE_VT = 0x820AA1F8;
    private long lastCapture = 0;
    private int captureFlagTick = 0;

    /** A tap found nothing known: record the screen's draws (files/ui_frames.txt) to learn it. */
    private void captureScreen() {
        long now = System.currentTimeMillis();
        if (now - lastCapture < 1500) return;
        lastCapture = now;
        try {
            nativeUiCaptureFrame(new java.io.File(getContext().getFilesDir(), "ui_frames.txt").getPath());
            ui.note("frame captured");
        } catch (UnsatisfiedLinkError e) { }
    }

    /** The game's cursor of the current menu (list object, else title), -1 if none. */
    private int gameCursor() {
        try {
            if (listVt != 0) return nativeListCursor();
            if ((menuState & 0x10000) != 0) return nativeTitleCursor();
        } catch (UnsatisfiedLinkError e) { }
        return -1;
    }

    /** Compares the game's cursor with the highlighted row while the menu is shown. */
    private void learnCursor() {
        long key = listVt != 0 ? (listVt & 0xFFFFFFFFL) : ((menuState & 0x10000) != 0 ? menuState : 0);
        if (key != cursorKey) {
            cursorKey = key;
            cursorAgree.clear();
            cursorAgreeTicks = cursorDisagreeTicks = 0;
        }
        if (key == 0 || !(uiMenu || menuState != 0)) return;
        int vis = ui.selectedIndex(), cur = gameCursor();
        if (vis < 0 || cur < 0 || cur > 64) return;
        if (cur == vis) { cursorAgree.add(vis); cursorAgreeTicks++; }
        else cursorDisagreeTicks++;
    }

    private boolean cursorTrusted() {
        return cursorKey != 0 && cursorAgree.size() >= 2 && cursorDisagreeTicks * 10 < cursorAgreeTicks;
    }

    /** Puts the game's cursor straight on the tapped row (no D-pad scrolling). */
    private boolean setCursorDirect(UiTouch.Action act) {
        int cur = gameCursor();
        boolean ok = false;
        if (cursorTrusted() && cur == act.from) {
            try {
                ok = listVt != 0 ? nativeSetListCursor(act.target) : nativeSetTitleCursor(act.target);
            } catch (UnsatisfiedLinkError e) { ok = false; }
        }
        ui.note("direct " + act.from + "->" + act.target + " key " + Long.toHexString(cursorKey)
                + " cur " + cur + " agree " + cursorAgree + " " + cursorAgreeTicks + "/" + cursorDisagreeTicks
                + (ok ? " SET" : " (presses)"));
        if (ok) haptic();
        return ok;
    }

    private void pulse(int btn) {
        button(btn, true);
        haptic();
        handler.postDelayed(() -> button(btn, false), 110);
    }

    // ---- cinema mode: clean picture, touch to skip ------------------------------------------

    private void cinemaTouch(int pid, float x, float y) {
        long now = System.currentTimeMillis();
        // START and A keep working as invisible hotspots (the automated tests tap them, and
        // they are where a player's thumbs already are).
        Ctl start = ctls[9], a = ctls[0];
        Ctl hot = start.hit(x, y, 8 * dp) ? start : (a.hit(x, y, 8 * dp) ? a : null);
        if (hot != null) {
            owners.put(pid, hot);
            press(hot, true);
            hintUntil = 0;
        } else if (!bootDone && (sceneMode == 0 || now - sceneSince < 6000)) {
            // The first logos (Capcom, Dolby) cannot be skipped: no "tap again" hint.
        } else if (sceneMode == 0) {
            // Not a cutscene / movie (e.g. back from the pause menu before the HUD shows):
            // never START (it would pause again) - give the controller back instead.
            tapReveal = true;
        } else if (now < hintUntil) {
            hintUntil = 0;
            haptic();
            button(BTN_START, true);
            handler.postDelayed(() -> button(BTN_START, false), 120);
        } else {
            hintShownAt = now;
            hintUntil = now + HINT_MS;
        }
        invalidate();
    }

    private void drawSkipHint(Canvas canvas) {
        long now = System.currentTimeMillis();
        if (now >= hintUntil) return;
        float t = (now - hintShownAt) / 1000f, left = (hintUntil - now) / 1000f;
        float a = Math.min(1f, Math.min(t / 0.25f, left / 0.5f));
        int w = getWidth(), h = getHeight();
        float bandTop = h * 0.70f;
        hintBand.setShader(new LinearGradient(0, bandTop, 0, h, 0x00000000,
                ((int) (0xB0 * a) << 24), Shader.TileMode.CLAMP));
        canvas.drawRect(0, bandTop, w, h, hintBand);
        hintPaint.setTextAlign(Paint.Align.CENTER);
        hintPaint.setColor(((int) (0xF0 * a) << 24) | 0xFFFFFF);
        hintPaint.setTextSize(17 * dp);
        hintPaint.setFakeBoldText(true);
        hintPaint.setLetterSpacing(0.08f);
        String what = sceneMode == 2 ? "MOVIE" : "CUTSCENE";
        canvas.drawText("TAP AGAIN TO SKIP " + what, w / 2f, h - 40 * dp, hintPaint);
        // Thin progress line: how long the second tap is accepted.
        float frac = Math.max(0f, left / (HINT_MS / 1000f));
        hintPaint.setStrokeWidth(2 * dp);
        hintPaint.setColor(((int) (0x90 * a) << 24) | 0xE0453A);
        float lw = 140 * dp * frac;
        canvas.drawLine(w / 2f - lw / 2f, h - 28 * dp, w / 2f + lw / 2f, h - 28 * dp, hintPaint);
        postInvalidateOnAnimation();
    }

    // ---- opacity / idle fade ------------------------------------------------------------------

    private boolean isOptional(Ctl c) {
        // "Essential buttons only": BACK, LB and the D-pad are hidden.
        return DevilActivity.sEssentialOnly && (c == ctls[8] || c == ctls[4] || c == dpad);
    }

    private void updateAlpha(boolean touched) {
        float base = DevilActivity.sPadOpacity;
        boolean idle = DevilActivity.sFadeIdle && owners.size() == 0
                && System.currentTimeMillis() - lastTouch > 4000;
        float target = cinemaHidden ? 1f : base * (idle && !touched ? 0.35f : 1f);
        if (Math.abs(getAlpha() - target) < 0.01f) return;
        animate().cancel();
        if (touched) setAlpha(target);
        else animate().alpha(target).setDuration(target < getAlpha() ? 700 : 150).start();
    }

    /** Settings changed: re-apply opacity / layout. */
    public void refreshFromSettings() {
        updateAlpha(true);
        invalidate();
    }

    private void down(int pid, float x, float y) {
        float slop = 8 * dp;
        for (Ctl c : ctls) {
            if (c.kind == K_DPAD || isOptional(c) || !c.hit(x, y, slop)) continue;
            if (c.kind == K_TOGGLE) {
                // Short tap hides the overlay, holding it opens the settings.
                toggleDownTime = System.currentTimeMillis();
                owners.put(pid, c);
                return;
            }
            owners.put(pid, c);
            press(c, true);
            return;
        }
        if (!isOptional(dpad) && dpad.hit(x, y, slop)) {
            owners.put(pid, dpad);
            dpad.pressed = true;
            setDpad(x, y);
            return;
        }
        Stick st = x < getWidth() / 2f ? lstick : rstick;
        if (st.pointer != -1) return;
        st.pointer = pid;
        owners.put(pid, st);
        long now = System.currentTimeMillis();
        if (now - st.lastDown < 300) { st.clicked = true; button(st.click, true); }
        st.lastDown = now;
        // Floating stick: centre under the finger.
        st.baseX = x; st.baseY = y; st.knobX = x; st.knobY = y;
        haptic();
    }

    private void move(int pid, float x, float y) {
        Object o = owners.get(pid);
        if (o == dpad) {
            setDpad(x, y);
        } else if (o instanceof Stick) {
            Stick st = (Stick) o;
            float dx = x - st.baseX, dy = y - st.baseY;
            float len = (float) Math.hypot(dx, dy);
            if (len > st.radius) {
                // Drag the base along so the stick never "sticks" at the rim.
                float over = len - st.radius;
                st.baseX += dx / len * over;
                st.baseY += dy / len * over;
                dx = x - st.baseX; dy = y - st.baseY;
            }
            st.knobX = x; st.knobY = y;
            float nx = dx / st.radius, ny = dy / st.radius;
            float mag = (float) Math.hypot(nx, ny);
            if (mag < 0.08f) { nx = 0; ny = 0; }
            axis(st.axX, nx);
            axis(st.axY, ny);   // SDL: +Y is down, same as the screen
        }
    }

    private void up(int pid) {
        Object o = owners.get(pid);
        owners.remove(pid);
        if (o == dpad) {
            dpad.pressed = false;
            applyDpad(0);
        } else if (o == toggle) {
            if (System.currentTimeMillis() - toggleDownTime >= 500) {
                if (getContext() instanceof DevilActivity) ((DevilActivity) getContext()).showSettings();
            } else {
                userHidden = true;
                updateVisibility();
            }
        } else if (o instanceof Ctl) {
            press((Ctl) o, false);
        } else if (o instanceof Stick) {
            releaseStick((Stick) o);
        }
    }

    private void releaseStick(Stick st) {
        st.pointer = -1;
        if (st.clicked) { st.clicked = false; button(st.click, false); }
        axis(st.axX, 0);
        axis(st.axY, 0);
        resetStick(st);
    }

    private void press(Ctl c, boolean down) {
        if (c.pressed == down) return;
        c.pressed = down;
        if (c.kind == K_TRIGGER) axis(c.id, down ? 1f : 0f);
        else button(c.id, down);
        if (down) haptic();
    }

    private void setDpad(float x, float y) {
        float dx = x - dpad.cx, dy = y - dpad.cy;
        int mask = 0;
        float dead = dpad.r * 0.25f;
        if (Math.hypot(dx, dy) > dead) {
            double ang = Math.toDegrees(Math.atan2(dy, dx));  // 0 = right, 90 = down
            // 8-way with 45-degree sectors.
            if (ang > -67.5 && ang < 67.5) mask |= 8;          // right
            if (ang > 112.5 || ang < -112.5) mask |= 4;        // left
            if (ang > 22.5 && ang < 157.5) mask |= 2;          // down
            if (ang < -22.5 && ang > -157.5) mask |= 1;        // up
        }
        applyDpad(mask);
    }

    private void applyDpad(int mask) {
        if (mask == dpadMask) return;
        int changed = mask ^ dpadMask;
        int[] bits = { 1, 2, 4, 8 };
        int[] btns = { BTN_UP, BTN_DOWN, BTN_LEFT, BTN_RIGHT };
        for (int i = 0; i < 4; i++) {
            if ((changed & bits[i]) != 0) button(btns[i], (mask & bits[i]) != 0);
        }
        if ((mask & ~dpadMask) != 0) haptic();
        dpadMask = mask;
    }

    private void releaseAll() {
        for (Ctl c : ctls) if (c.pressed && c != dpad) press(c, false);
        dpad.pressed = false;
        applyDpad(0);
        if (lstick.pointer != -1) releaseStick(lstick);
        if (rstick.pointer != -1) releaseStick(rstick);
        owners.clear();
    }

    private void haptic() {
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
    }

    // ---- drawing ----------------------------------------------------------------------------

    @Override protected void onDraw(Canvas canvas) {
        if (introMaskUntil > System.currentTimeMillis()) {
            canvas.drawColor(Color.BLACK);
            return;
        }
        if (cinemaHidden) {  // clean picture: no controls, no toggle, no FPS
            drawSkipHint(canvas);
            return;
        }
        drawFps(canvas);
        if (!overlayVisible) {
            drawCircle(canvas, toggle, 0x55FFFFFF, false);
            return;
        }
        drawStick(canvas, lstick);
        drawStick(canvas, rstick);
        for (Ctl c : ctls) {
            if (isOptional(c)) continue;
            if (c == dpad) drawDpad(canvas);
            else if (c.rect != null) drawRect(canvas, c);
            else drawCircle(canvas, c, c.color, true);
        }
    }

    private void drawCircle(Canvas cv, Ctl c, int color, boolean labelColored) {
        fill.setColor(c.pressed ? withAlpha(color, 0xB0) : 0x30000000);
        cv.drawCircle(c.cx, c.cy, c.r, fill);
        stroke.setColor(withAlpha(color, c.pressed ? 0xFF : 0x99));
        cv.drawCircle(c.cx, c.cy, c.r, stroke);
        text.setColor(c.pressed ? Color.WHITE : withAlpha(labelColored ? color : Color.WHITE, 0xCC));
        cv.drawText(c.label, c.cx, c.cy - (text.descent() + text.ascent()) / 2f, text);
    }

    private void drawRect(Canvas cv, Ctl c) {
        float rad = 10 * dp;
        fill.setColor(c.pressed ? 0x99FFFFFF : 0x30000000);
        cv.drawRoundRect(c.rect, rad, rad, fill);
        stroke.setColor(c.pressed ? 0xFFFFFFFF : 0x99FFFFFF);
        cv.drawRoundRect(c.rect, rad, rad, stroke);
        text.setColor(c.pressed ? Color.BLACK : 0xCCFFFFFF);
        cv.drawText(c.label, c.rect.centerX(), c.rect.centerY() - (text.descent() + text.ascent()) / 2f, text);
    }

    private void drawDpad(Canvas cv) {
        float r = dpad.r, w = r * 0.38f;
        String[] arrows = { "▲", "▼", "◀", "▶" };
        float[][] off = { { 0, -1 }, { 0, 1 }, { -1, 0 }, { 1, 0 } };
        for (int i = 0; i < 4; i++) {
            boolean on = (dpadMask & (1 << i)) != 0;
            float cx = dpad.cx + off[i][0] * r * 0.62f, cy = dpad.cy + off[i][1] * r * 0.62f;
            RectF rc = new RectF(cx - w, cy - w, cx + w, cy + w);
            fill.setColor(on ? 0x99FFFFFF : 0x30000000);
            cv.drawRoundRect(rc, 6 * dp, 6 * dp, fill);
            stroke.setColor(on ? 0xFFFFFFFF : 0x99FFFFFF);
            cv.drawRoundRect(rc, 6 * dp, 6 * dp, stroke);
            text.setColor(on ? Color.BLACK : 0xCCFFFFFF);
            cv.drawText(arrows[i], cx, cy - (text.descent() + text.ascent()) / 2f, text);
        }
    }

    private void drawStick(Canvas cv, Stick st) {
        boolean active = st.pointer != -1;
        fill.setColor(active ? 0x40FFFFFF : 0x25000000);
        cv.drawCircle(st.baseX, st.baseY, st.radius, fill);
        stroke.setColor(active ? 0xCCFFFFFF : 0x77FFFFFF);
        cv.drawCircle(st.baseX, st.baseY, st.radius, stroke);
        float kx = st.knobX, ky = st.knobY;
        float dx = kx - st.baseX, dy = ky - st.baseY, len = (float) Math.hypot(dx, dy);
        if (len > st.radius) { kx = st.baseX + dx / len * st.radius; ky = st.baseY + dy / len * st.radius; }
        fill.setColor(st.clicked ? 0xDDFFFFFF : (active ? 0xAAFFFFFF : 0x66FFFFFF));
        cv.drawCircle(kx, ky, st.radius * 0.45f, fill);
    }

    private static int withAlpha(int color, int alpha) { return (color & 0x00FFFFFF) | (alpha << 24); }
}
