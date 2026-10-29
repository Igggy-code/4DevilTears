package com.fourdeviltears.app;

import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.widget.Toast;
import android.system.Os;
import android.util.Log;
import android.view.ViewGroup;

import java.io.File;
import java.util.ArrayList;

import org.libsdl.app.SDLActivity;

/**
 * 4DevilTears — Android entry point.
 *
 * Game data is NOT shipped with the app. It is extracted from the user's own
 * disc image into app-private storage:
 *   files/game/default.xex, files/game/nativeXenon/...
 * (for now filled by install_android.sh over adb; an in-app image importer
 *  will replace that).
 */
public class DevilActivity extends SDLActivity {
    private static final String TAG = "4DevilTears";

    /** Runtime flags (settings menu, later). Implemented in android_touch_gamepad.cpp. */
    static native boolean nativeSetFlag(String name, String value);
    static native String nativeGetFlag(String name);
    static native void nativeSurfaceState(boolean alive);
    /** SDL's game surface (for PixelCopy in the flash watcher of the automated tests). */
    static android.view.SurfaceView gameSurface() { return mSurface; }

    /** Read by TouchGamepadView: draw the FPS counter under the overlay toggle. */
    static volatile boolean sShowFps = true;
    /** Read by TouchGamepadView: hide the controller during cutscenes and movies. */
    static volatile boolean sCinemaMode = true;
    /** Controller look (settings): opacity 0.2..1, fade when idle, essential buttons only. */
    static volatile float sPadOpacity = 0.58f;
    static volatile boolean sFadeIdle = true;
    static volatile boolean sEssentialOnly = true;
    /** Developer mode: the REC / UI / GFX capture buttons on the overlay. Off for players;
     *  tapping the "Settings" title 8 times turns it on, 3 more taps turn it off. */
    static volatile boolean sDebugRec = false;
    static final String KEY_DEV_MODE = "developer_mode";
    private int devTaps = 0;
    private long devTapAt = 0;
    /** Skip the intro movie at launch (on by default when the edition's intro carries
     *  third-party captions, e.g. the Russian fan translation). */
    static volatile boolean sSkipIntro = false;
    /** The Russian fan translation (recognised by its re-encoded, captioned intro movie):
     *  the app's own on-screen hints are written in Russian. */
    static volatile boolean sRussianEdition = false;
    /** fit | fill | stretch - TouchGamepadView maps touches to game coordinates with it. */
    static volatile String sScale = "wide";

    // ---- settings (Back button / back gesture) ----
    static final String PREFS = "settings";
    static final String KEY_SCALE = "scale";            // wide | fit | fill | stretch
    static final String DEFAULT_SCALE = "wide";
    static final String KEY_SHOW_FPS = "show_fps";
    static final String KEY_SHOW_PAD = "show_pad";
    static final String KEY_CINEMA = "cinema_mode";
    static final String KEY_FPS_CAP = "fps_cap";        // 0 = unlocked, 30, 40
    static final String KEY_OPACITY = "pad_opacity";     // percent
    static final String KEY_FADE_IDLE = "pad_fade_idle";
    static final String KEY_ESSENTIAL = "pad_essential";
    static final String KEY_SKIP_INTRO = "skip_intro";

    /** The intro movie of this edition was replaced by a re-encoded one with captions. */
    private boolean editedIntro() {
        File intro = new File(gameDir(), "nativeXenon/movie/rom_for/adv_for.wmv");
        long len = intro.length();
        return len > 0 && len != 169062776L;  // 169062776 = the original disc's intro
    }
    private TouchGamepadView gamepadView;

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    /** Percentage of the 16:9 image height kept when filling a wider screen. */
    private int fillSafeArea() {
        DisplayMetrics m = getResources().getDisplayMetrics();
        int w = Math.max(m.widthPixels, m.heightPixels), h = Math.min(m.widthPixels, m.heightPixels);
        if (w <= 0 || h <= 0) return 100;
        // Output height of a 16:9 image filling the width is w * 9 / 16; keep h of it.
        int safe = (int) Math.round(100.0 * h * 16.0 / (w * 9.0));
        return Math.max(50, Math.min(100, safe));
    }

    /** Width / height of the screen in landscape. */
    private double screenAspect() {
        int w, h;
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            // The whole window (the game surface fills it, including under the cutout).
            android.graphics.Rect r = getWindowManager().getCurrentWindowMetrics().getBounds();
            w = Math.max(r.width(), r.height()); h = Math.min(r.width(), r.height());
        } else {
            DisplayMetrics m = new DisplayMetrics();
            getWindowManager().getDefaultDisplay().getRealMetrics(m);
            w = Math.max(m.widthPixels, m.heightPixels); h = Math.min(m.widthPixels, m.heightPixels);
        }
        return (w > 0 && h > 0) ? (double) w / h : 16.0 / 9.0;
    }

    /** "wide": the 16:9 image is stretched to the screen and the game draws narrower to match. */
    private String wideAspectFlag(String scale) {
        return "wide".equals(scale) ? String.format(java.util.Locale.ROOT, "%.4f", screenAspect()) : "0";
    }

    private void applyScaleLive(String scale) {
        try {
            boolean stretched = "stretch".equals(scale) || "wide".equals(scale);
            nativeSetFlag("present_letterbox", stretched ? "false" : "true");
            nativeSetFlag("dmc4_wide_aspect", wideAspectFlag(scale));
            nativeSetFlag("present_allow_overscan_cutoff", "fill".equals(scale) ? "true" : "false");
            nativeSetFlag("present_safe_area_y",
                    String.valueOf("fill".equals(scale) ? fillSafeArea() : 90));
        } catch (UnsatisfiedLinkError e) {
            Log.w(TAG, "nativeSetFlag", e);
        }
    }

    // ---- startup logo (replaces the SDL logo while the runtime boots) ------------------

    private android.view.View splashView;
    private final android.os.Handler splashHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private long splashShownAt;

    private void showSplash() {
        if (mLayout == null) return;
        android.widget.FrameLayout box = new android.widget.FrameLayout(this);
        box.setBackgroundColor(0xFF000000);
        android.widget.ImageView logo = new android.widget.ImageView(this);
        logo.setImageResource(R.drawable.splash_logo);
        logo.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        logo.setAdjustViewBounds(true);
        DisplayMetrics m = getResources().getDisplayMetrics();
        int longSide = Math.max(m.widthPixels, m.heightPixels);
        int shortSide = Math.min(m.widthPixels, m.heightPixels);
        // About a third of the screen width, never taller than 30% of the height.
        int lw = Math.min((int) (longSide * 0.34f), (int) (shortSide * 0.30f * 1400f / 788f));
        box.addView(logo, new android.widget.FrameLayout.LayoutParams(lw,
                ViewGroup.LayoutParams.WRAP_CONTENT, android.view.Gravity.CENTER));
        splashView = box;
        mLayout.addView(box, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        splashShownAt = System.currentTimeMillis();
        splashHandler.postDelayed(splashPoll, 500);
    }

    private final Runnable splashPoll = new Runnable() {
        @Override public void run() {
            if (splashView == null) return;
            float fps = 0f;
            try { fps = TouchGamepadView.nativeFps(); } catch (Throwable t) { fps = 0f; }
            long shown = System.currentTimeMillis() - splashShownAt;
            // Keep the logo for at least 2 s, until the game presents frames (max 20 s).
            if ((shown >= 2000 && fps > 1f) || shown >= 20000) {
                final android.view.View v = splashView;
                splashView = null;
                v.animate().alpha(0f).setDuration(400).withEndAction(() -> {
                    if (v.getParent() != null) ((ViewGroup) v.getParent()).removeView(v);
                }).start();
            } else {
                splashHandler.postDelayed(this, 250);
            }
        }
    };

    // ---- side settings panel -------------------------------------------------------------

    private android.widget.FrameLayout settingsOverlay;
    private android.widget.LinearLayout settingsPanel;

    private int dpx(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private android.widget.TextView label(String text, float sizeSp, int color, boolean bold) {
        android.widget.TextView t = new android.widget.TextView(this);
        t.setText(text);
        t.setTextSize(sizeSp);
        t.setTextColor(color);
        if (bold) t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        return t;
    }

    /** The display has a mode at (a multiple of) this refresh rate. */
    private boolean displaySupportsRefresh(float hz) {
        try {
            android.view.Display d = getWindowManager().getDefaultDisplay();
            for (android.view.Display.Mode m : d.getSupportedModes()) {
                float r = m.getRefreshRate();
                if (r >= hz - 0.5f && Math.abs(r / hz - Math.round(r / hz)) < 0.01f) return true;
            }
        } catch (Exception e) { Log.w(TAG, "display modes", e); }
        return false;
    }

    /** Tell Android the game's frame rate, so it runs the screen in a mode that shows every
     *  frame for the same time (40 -> 120 Hz). 0 = no cap: the default (highest) mode. */
    void applyFrameRateHint(int cap) {
        if (android.os.Build.VERSION.SDK_INT < 30) return;
        try {
            android.view.SurfaceView sv = gameSurface();
            if (sv == null || !sv.getHolder().getSurface().isValid()) return;
            sv.getHolder().getSurface().setFrameRate(cap > 0 ? cap : 0f,
                    android.view.Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE);
        } catch (Exception e) { Log.w(TAG, "setFrameRate", e); }
    }

    private android.widget.Switch switchRow(String text, boolean checked,
            android.widget.CompoundButton.OnCheckedChangeListener listener) {
        android.widget.Switch sw = new android.widget.Switch(this);
        sw.setText(text);
        sw.setTextSize(15);
        sw.setTextColor(0xFFEDEDED);
        sw.setShowText(false);
        sw.setSwitchPadding(dpx(16));
        int[][] states = { { android.R.attr.state_checked }, {} };
        sw.setThumbTintList(new android.content.res.ColorStateList(states,
                new int[] { 0xFFE0453A, 0xFFB8BEC8 }));
        sw.setTrackTintList(new android.content.res.ColorStateList(states,
                new int[] { 0x99E0453A, 0x55B8BEC8 }));
        sw.setChecked(checked);
        sw.setPadding(0, dpx(10), 0, dpx(10));
        sw.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        sw.setOnCheckedChangeListener(listener);
        return sw;
    }

    private void buildSettingsPanel() {
        final SharedPreferences p = prefs();
        settingsOverlay = new android.widget.FrameLayout(this);
        settingsOverlay.setBackgroundColor(0x66000000);
        settingsOverlay.setClickable(true);
        settingsOverlay.setOnClickListener(v -> hideSettings());

        settingsPanel = new android.widget.LinearLayout(this);
        settingsPanel.setOrientation(android.widget.LinearLayout.VERTICAL);
        settingsPanel.setPadding(dpx(24), dpx(20), dpx(24), dpx(20));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(0xF01A1C22);
        bg.setCornerRadii(new float[] { dpx(18), dpx(18), 0, 0, 0, 0, dpx(18), dpx(18) });
        settingsPanel.setBackground(bg);
        settingsPanel.setClickable(true);  // don't close when tapping the panel itself

        android.widget.TextView settingsTitle = label("Settings", 22, 0xFFFFFFFF, true);
        settingsTitle.setOnClickListener(v -> {
            long now = System.currentTimeMillis();
            devTaps = now - devTapAt < 1500 ? devTaps + 1 : 1;  // taps in a row
            devTapAt = now;
            int needed = sDebugRec ? 3 : 8;
            if (devTaps < needed) return;
            devTaps = 0;
            sDebugRec = !sDebugRec;
            p.edit().putBoolean(KEY_DEV_MODE, sDebugRec).apply();
            Toast.makeText(this, sDebugRec ? "Developer mode on: REC / UI / GFX buttons"
                    : "Developer mode off", Toast.LENGTH_SHORT).show();
            if (gamepadView != null) gamepadView.invalidate();
        });
        settingsPanel.addView(settingsTitle);
        android.widget.TextView sub = label("4DevilTears", 12, 0xFF9AA0AA, false);
        sub.setPadding(0, 0, 0, dpx(14));
        settingsPanel.addView(sub);

        android.widget.TextView displayTitle = label("Display", 13, 0xFFE0453A, true);
        displayTitle.setPadding(0, dpx(6), 0, dpx(4));
        settingsPanel.addView(displayTitle);
        android.widget.RadioGroup scaleGroup = new android.widget.RadioGroup(this);
        String[][] scales = {
            { "wide", "Full screen (wider view, no distortion)" },
            { "fit", "Original (16:9, black bars)" },
            { "fill", "Fill screen (crop top and bottom)" },
            { "stretch", "Stretch to screen" },
        };
        String scale = p.getString(KEY_SCALE, DEFAULT_SCALE);
        for (int i = 0; i < scales.length; i++) {
            android.widget.RadioButton rb = new android.widget.RadioButton(this);
            rb.setId(1000 + i);
            rb.setText(scales[i][1]);
            rb.setTextSize(15);
            rb.setTextColor(0xFFEDEDED);
            rb.setPadding(dpx(4), dpx(6), 0, dpx(6));
            rb.setChecked(scales[i][0].equals(scale));
            scaleGroup.addView(rb);
        }
        scaleGroup.setOnCheckedChangeListener((g, id) -> {
            String next = scales[id - 1000][0];
            p.edit().putString(KEY_SCALE, next).apply();
            sScale = next;
            applyScaleLive(next);
            Toast.makeText(this, "Takes effect after restarting the game if it didn't change now",
                    Toast.LENGTH_SHORT).show();
        });
        settingsPanel.addView(scaleGroup);
        // Frame rate caps: 30 or 40 (40 divides a 120 Hz display evenly - smoother than 30,
        // lighter than unlocked). One excludes the other.
        // 40 is offered only where it divides the screen's refresh evenly (120 Hz modes).
        final boolean has120 = displaySupportsRefresh(120f);
        final android.widget.Switch[] caps = new android.widget.Switch[2];
        final int[] capValues = { 30, 40 };
        final String[] capTexts = { "Lock to 30 FPS (steadier, cooler)",
                "Lock to 40 FPS (smoother than 30, cooler than unlocked)" };
        for (int i = 0; i < (has120 ? 2 : 1); i++) {
            final int idx = i;
            caps[i] = switchRow(capTexts[i], p.getInt(KEY_FPS_CAP, 0) == capValues[i], (b, on) -> {
                if (on && caps[1 - idx] != null && caps[1 - idx].isChecked()) {
                    caps[1 - idx].setChecked(false);  // its listener writes 0 - written over below
                }
                int cap = on ? capValues[idx] : (caps[1 - idx] != null && caps[1 - idx].isChecked()
                        ? capValues[1 - idx] : 0);
                p.edit().putInt(KEY_FPS_CAP, cap).apply();
                try { nativeSetFlag("frame_rate_limit", String.valueOf(cap)); }
                catch (UnsatisfiedLinkError e) { Log.w(TAG, "nativeSetFlag", e); }
                applyFrameRateHint(cap);
            });
            settingsPanel.addView(caps[i]);
        }

        android.widget.TextView overlayTitle = label("Overlay", 13, 0xFFE0453A, true);
        overlayTitle.setPadding(0, dpx(14), 0, dpx(0));
        settingsPanel.addView(overlayTitle);
        settingsPanel.addView(switchRow("FPS counter", p.getBoolean(KEY_SHOW_FPS, true), (b, on) -> {
            p.edit().putBoolean(KEY_SHOW_FPS, on).apply();
            sShowFps = on;
            if (gamepadView != null) gamepadView.invalidate();
        }));
        settingsPanel.addView(switchRow("On-screen controller", p.getBoolean(KEY_SHOW_PAD, true),
                (b, on) -> {
                    p.edit().putBoolean(KEY_SHOW_PAD, on).apply();
                    if (gamepadView != null) gamepadView.setUserHidden(!on);
                }));

        settingsPanel.addView(switchRow("Cinema mode (clean screen in cutscenes, movies and menus)",
                p.getBoolean(KEY_CINEMA, true), (b, on) -> {
                    p.edit().putBoolean(KEY_CINEMA, on).apply();
                    sCinemaMode = on;
                    if (gamepadView != null) gamepadView.invalidate();
                }));
        settingsPanel.addView(label("Cutscenes and movies play without any controls on screen: tap once, then tap again "
                + "to skip. On the title screen: tap to confirm, swipe to move, two fingers to go back.", 12, 0xFFB8BEC8, false));

        settingsPanel.addView(switchRow("Skip intro movie", p.getBoolean(KEY_SKIP_INTRO, false),
                (b, on) -> {
                    p.edit().putBoolean(KEY_SKIP_INTRO, on).apply();
                    sSkipIntro = on;
                }));
        settingsPanel.addView(switchRow("Fade when idle", p.getBoolean(KEY_FADE_IDLE, true),
                (b, on) -> {
                    p.edit().putBoolean(KEY_FADE_IDLE, on).apply();
                    sFadeIdle = on;
                    if (gamepadView != null) gamepadView.refreshFromSettings();
                }));
        settingsPanel.addView(switchRow("Essential buttons only (no BACK, LB, D-pad)",
                p.getBoolean(KEY_ESSENTIAL, true), (b, on) -> {
                    p.edit().putBoolean(KEY_ESSENTIAL, on).apply();
                    sEssentialOnly = on;
                    if (gamepadView != null) gamepadView.refreshFromSettings();
                }));
        final android.widget.TextView opacityLabel = label(
                "Controller opacity: " + p.getInt(KEY_OPACITY, 58) + "%", 15, 0xFFEDEDED, false);
        opacityLabel.setPadding(0, dpx(10), 0, dpx(2));
        settingsPanel.addView(opacityLabel);
        android.widget.SeekBar opacity = new android.widget.SeekBar(this);
        opacity.setMax(80);  // 20..100 %
        opacity.setProgress(p.getInt(KEY_OPACITY, 58) - 20);
        opacity.setProgressTintList(android.content.res.ColorStateList.valueOf(0xFFE0453A));
        opacity.setThumbTintList(android.content.res.ColorStateList.valueOf(0xFFE0453A));
        opacity.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(android.widget.SeekBar sb, int v, boolean user) {
                int pct = v + 20;
                opacityLabel.setText("Controller opacity: " + pct + "%");
                p.edit().putInt(KEY_OPACITY, pct).apply();
                sPadOpacity = pct / 100f;
                if (gamepadView != null) gamepadView.refreshFromSettings();
            }
            @Override public void onStartTrackingTouch(android.widget.SeekBar sb) {}
            @Override public void onStopTrackingTouch(android.widget.SeekBar sb) {}
        });
        settingsPanel.addView(opacity);

        android.widget.TextView gameTitle = label("Game files", 13, 0xFFE0453A, true);
        gameTitle.setPadding(0, dpx(14), 0, dpx(4));
        settingsPanel.addView(gameTitle);
        android.widget.TextView reimport = label("Change game files (.iso / folder)...", 15, 0xFFEDEDED, false);
        reimport.setPadding(0, dpx(8), 0, dpx(8));
        reimport.setOnClickListener(v -> {
            startActivity(new android.content.Intent(this, ImportActivity.class).putExtra("reimport", true));
        });
        settingsPanel.addView(reimport);

        android.widget.TextView padTitle = label("Controllers", 13, 0xFFE0453A, true);
        padTitle.setPadding(0, dpx(14), 0, dpx(4));
        settingsPanel.addView(padTitle);
        settingsPanel.addView(label("Bluetooth and USB gamepads (Xbox, PlayStation and others) work "
                + "automatically. The on-screen controller hides while one is connected.",
                13, 0xFFB8BEC8, false));

        android.widget.TextView close = label("Close", 15, 0xFFFFFFFF, true);
        close.setPadding(0, dpx(22), 0, dpx(4));
        close.setOnClickListener(v -> hideSettings());
        settingsPanel.addView(close);

        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(settingsPanel);
        android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(
                dpx(360), ViewGroup.LayoutParams.MATCH_PARENT, android.view.Gravity.END);
        settingsOverlay.addView(scroll, lp);
        scroll.setBackground(bg);
        settingsPanel.setBackground(null);
        scroll.setClickable(true);
        settingsOverlay.setTag(scroll);
    }

    void showSettings() {
        if (mLayout == null) return;
        if (settingsOverlay != null && settingsOverlay.getParent() != null) return;
        buildSettingsPanel();
        mLayout.addView(settingsOverlay, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        android.view.View scroll = (android.view.View) settingsOverlay.getTag();
        scroll.setTranslationX(dpx(380));
        scroll.animate().translationX(0).setDuration(220)
                .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
        settingsOverlay.setAlpha(0f);
        settingsOverlay.animate().alpha(1f).setDuration(220).start();
    }

    void hideSettings() {
        if (settingsOverlay == null || settingsOverlay.getParent() == null) return;
        final android.widget.FrameLayout overlay = settingsOverlay;
        android.view.View scroll = (android.view.View) overlay.getTag();
        scroll.animate().translationX(dpx(380)).setDuration(180)
                .setInterpolator(new android.view.animation.AccelerateInterpolator()).start();
        overlay.animate().alpha(0f).setDuration(180).withEndAction(() -> {
            if (overlay.getParent() != null) ((ViewGroup) overlay.getParent()).removeView(overlay);
        }).start();
    }

    boolean settingsVisible() {
        return settingsOverlay != null && settingsOverlay.getParent() != null;
    }

    @Override
    public void onBackPressed() {
        if (settingsVisible()) hideSettings(); else showSettings();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // The system Back key / gesture (not a controller's BACK button) opens the settings.
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            int src = event.getSource();
            boolean fromPad = (src & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                    || (src & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
            if (!fromPad) {
                if (event.getAction() == KeyEvent.ACTION_UP) {
                    if (settingsVisible()) hideSettings(); else showSettings();
                }
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private File gameDir() {
        return ImportActivity.gameDir(this);   // internal storage or the app's folder on a memory card
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // The runtime resolves user-data folders from HOME / XDG_DATA_HOME,
        // which Android does not set; point them at app-private storage.
        try {
            String files = getFilesDir().getAbsolutePath();
            Os.setenv("HOME", files, true);
            Os.setenv("XDG_DATA_HOME", files, true);
        } catch (Exception e) {
            Log.w(TAG, "setenv failed", e);
        }
        File game = gameDir();
        if (!game.exists()) {
            game.mkdirs();
        }
        if (!new File(game, "default.xex").canRead()) {
            Log.e(TAG, "default.xex not found in " + game.getAbsolutePath());
        }
        Log.i(TAG, "game data dir: " + game.getAbsolutePath());
        sGameReady = GameBuild.ready(this);
        if (!sGameReady) {
            // Opened directly (e.g. by a USB controller) before the game is added / built.
            startActivity(new android.content.Intent(this, ImportActivity.class));
            super.onCreate(savedInstanceState);
            finish();
            return;
        }
        super.onCreate(savedInstanceState);
        // The renderer stops presenting when the app leaves the screen and starts again on the
        // surface Android creates when it comes back - tell it when the surface exists.
        if (mSurface != null) {
            mSurface.getHolder().addCallback(new android.view.SurfaceHolder.Callback() {
                @Override public void surfaceCreated(android.view.SurfaceHolder holder) {
                    try { nativeSurfaceState(true); } catch (Throwable t) { }
                    applyFrameRateHint(prefs().getInt(KEY_FPS_CAP, 0));
                }
                @Override public void surfaceChanged(android.view.SurfaceHolder holder, int format,
                                                     int width, int height) { }
                @Override public void surfaceDestroyed(android.view.SurfaceHolder holder) {
                    try { nativeSurfaceState(false); } catch (Throwable t) { }
                }
            });
        }
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            // Use the whole screen, also beside the camera hole (the picture is full-screen).
            android.view.WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode =
                    android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(lp);
        }
        // On-screen Xbox 360 controller over the game surface (hides itself while a
        // physical controller is connected).
        sShowFps = prefs().getBoolean(KEY_SHOW_FPS, true);
        sDebugRec = prefs().getBoolean(KEY_DEV_MODE, false);
        // Cinema mode becomes the main mode: switched on once for everyone (also where it had
        // been turned off before); the switch in the settings still works afterwards.
        // The intro movie plays (tap twice to skip) - also the Russian edition's shorter one.
        if (!prefs().getBoolean("intro_default_v1", false)) {
            prefs().edit().putBoolean(KEY_SKIP_INTRO, false).putBoolean("intro_default_v1", true).apply();
        }
        if (!prefs().getBoolean("cinema_default_v2", false)) {
            prefs().edit().putBoolean(KEY_CINEMA, true).putBoolean("cinema_default_v2", true).apply();
        }
        sCinemaMode = prefs().getBoolean(KEY_CINEMA, true);
        sPadOpacity = prefs().getInt(KEY_OPACITY, 58) / 100f;
        sFadeIdle = prefs().getBoolean(KEY_FADE_IDLE, true);
        sEssentialOnly = prefs().getBoolean(KEY_ESSENTIAL, true);
        sSkipIntro = prefs().getBoolean(KEY_SKIP_INTRO, false);
        try { sRussianEdition = editedIntro(); } catch (Exception e) { sRussianEdition = false; }
        sScale = prefs().getString(KEY_SCALE, DEFAULT_SCALE);
        if (mLayout != null) {
            gamepadView = new TouchGamepadView(this);
            gamepadView.setUserHidden(!prefs().getBoolean(KEY_SHOW_PAD, true));
            mLayout.addView(gamepadView, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        showSplash();
    }

    /**
     * SDL picks the orientation from the game window (with a resizable window it
     * follows the rotation lock, i.e. usually portrait). The game is landscape only.
     */
    @Override
    public void setOrientationBis(int w, int h, boolean resizable, String hint) {
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
    }

    private static boolean sGameReady = false;

    @Override
    protected String[] getLibraries() {
        // SDL3 is linked into librexruntime; the last entry is the library
        // whose SDL_main is called (the game code module).
        return new String[] { "c++_shared", "rexruntime", "dmc4" };
    }

    /**
     * The game code module is not part of the APK: it is built on the phone from the
     * player's own default.xex (GameBuild) and loaded from app storage. Developer builds
     * may still bundle it in the APK.
     */
    @Override
    public void loadLibraries() {
        System.loadLibrary("c++_shared");
        System.loadLibrary("rexruntime");
        if (sGameReady) System.load(GameBuild.libraryPath(this));
    }

    @Override
    protected String getMainSharedObject() {
        return GameBuild.libraryPath(this);
    }

    @Override
    protected void main() {
        if (sGameReady) super.main();
    }

    @Override
    protected String[] getArguments() {
        ArrayList<String> args = new ArrayList<>();
        String files = getFilesDir().getAbsolutePath();
        args.add("--game_data_root");
        args.add(gameDir().getAbsolutePath());
        // Everything the runtime writes must live in app-private storage: the
        // default locations are next to the .so files (/data/app/..., read-only).
        args.add("--log_file");
        args.add(files + "/dmc4.log");
        args.add("--user_data_root");
        args.add(files + "/user");
        args.add("--cache_root");
        args.add(files + "/cache");
        // No emulated Xbox system dialogs (e.g. "sign in to Xbox LIVE"): they cannot be
        // operated from a phone; the default button is chosen automatically.
        args.add("--headless");
        // One-frame GPU trace: install_android.sh TRACE=1 creates files/trace_now.
        // Flags of the GPU plugin register after the command line is parsed; the
        // runtime only defers them in the "--name=value" form.
        args.add("--frame_trace_dir=" + files);
        // Unclipped full-screen draws without a scissor (blur/clear passes): find their
        // real extent on the CPU instead of assuming the whole EDRAM — cuts render
        // target transfers from 2048 to ~48 tiles in the menu.
        args.add("--execute_unclipped_draw_vs_on_cpu=true");
        // Mobile defaults - each only removes work the GPU / CPU would otherwise do:
        // no forced 8x anisotropic filtering (the game's own filtering is kept),
        // no bounds checks in every shader buffer access, no waiting for occlusion
        // query results and memexport readbacks (CPU<->GPU sync points).
        args.add("--anisotropic_override=-1");
        args.add("--vulkan_robust_buffer_access=false");
        args.add("--occlusion_query_enable=false");
        args.add("--readback_memexport=false");
        // D3D clear HLE: off until the red band on the logo screens is fixed.
        // XDK clears as host clears (mobile render target model).
        // Temporary: upstream Xenos GPU emulation, only to reach the main loop.
        // Will be replaced by the native MT Framework renderer.
        args.add("--gpu_plugin");
        args.add("xenos");
        // Picture scaling chosen in the settings.
        String scale = prefs().getString(KEY_SCALE, DEFAULT_SCALE);
        if ("stretch".equals(scale) || "wide".equals(scale)) {
            args.add("--present_letterbox=false");
            args.add("--dmc4_wide_aspect=" + wideAspectFlag(scale));
        } else if ("fill".equals(scale)) {
            args.add("--present_allow_overscan_cutoff=true");
            args.add("--present_safe_area_y=" + fillSafeArea());
        }
        // The intro movie is skipped by the app: draw nothing of it meanwhile.
        if (prefs().getBoolean(KEY_SKIP_INTRO, false)) args.add("--dmc4_hide_first_movie=true");
        if (prefs().getInt(KEY_FPS_CAP, 0) > 0) args.add("--frame_rate_limit=" + prefs().getInt(KEY_FPS_CAP, 0));
        // Extra flags for experiments without rebuilding: files/args.txt
        File extra = new File(getFilesDir(), "args.txt");
        if (extra.canRead()) {
            try {
                for (String line : java.nio.file.Files.readAllLines(extra.toPath())) {
                    line = line.trim();
                    if (!line.isEmpty()) args.add(line);
                }
            } catch (Exception e) {
                Log.w(TAG, "args.txt", e);
            }
        }
        Log.i(TAG, "args: " + args);
        return args.toArray(new String[0]);
    }
}
