package com.fourdeviltears.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/**
 * "Preparing the game": shows the on-device build of the game code (GameBuild) and starts
 * the game when it is done. The screen stays on while this is open; the build itself runs
 * in a foreground service and keeps going if the player leaves the app.
 */
public class BuildActivity extends Activity {
    private TextView status, detail, hint;
    private ProgressBar progress;
    private Button retry;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean launched = false;

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(0xFF000000);
        root.setPadding(dp(32), dp(24), dp(32), dp(24));

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.splash_logo);
        logo.setAdjustViewBounds(true);
        int lw = Math.min(getResources().getDisplayMetrics().widthPixels / 3, dp(320));
        root.addView(logo, new LinearLayout.LayoutParams(lw, LinearLayout.LayoutParams.WRAP_CONTENT));

        status = new TextView(this);
        status.setTextColor(0xFFEDEDED);
        status.setTextSize(18);
        status.setTypeface(Typeface.DEFAULT_BOLD);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, dp(18), 0, dp(6));
        status.setText("Preparing the game...");
        root.addView(status);

        detail = new TextView(this);
        detail.setTextColor(0xFFB8BEC8);
        detail.setTextSize(13);
        detail.setGravity(Gravity.CENTER);
        root.addView(detail);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1000);
        progress.setProgressTintList(android.content.res.ColorStateList.valueOf(0xFFE0453A));
        progress.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(0xFFE0453A));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(dp(420), dp(12));
        plp.topMargin = dp(18);
        root.addView(progress, plp);

        hint = new TextView(this);
        hint.setTextColor(0xFF8A909A);
        hint.setTextSize(12);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, dp(18), 0, 0);
        hint.setText("One-time step: the game's code is translated and compiled for this phone "
                + "(about 5–15 minutes). You can leave the app — it keeps going in the "
                + "background and resumes if interrupted.");
        root.addView(hint);

        retry = new Button(this);
        retry.setText("Try again");
        retry.setVisibility(View.GONE);
        retry.setOnClickListener(v -> { retry.setVisibility(View.GONE); begin(); });
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(16);
        root.addView(retry, rlp);

        setContentView(root);

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            // Only for the progress notification while the app is in the background.
            requestPermissions(new String[] { Manifest.permission.POST_NOTIFICATIONS }, 1);
        }
        begin();
    }

    private void begin() {
        if (GameBuild.ready(this)) { play(); return; }
        GameBuild.error = null;
        GameBuild.start(this);
        ui.post(poll);
    }

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (isFinishing() || launched) return;
            if (GameBuild.ready(BuildActivity.this) && !GameBuild.running) { play(); return; }
            String err = GameBuild.error;
            if (err != null && !GameBuild.running) {
                status.setText("Couldn't prepare the game");
                detail.setText(err);
                progress.setIndeterminate(false);
                progress.setVisibility(View.GONE);
                retry.setVisibility(View.VISIBLE);
                return;
            }
            progress.setVisibility(View.VISIBLE);
            if (!GameBuild.stage.isEmpty()) status.setText(GameBuild.stage);
            detail.setText(GameBuild.detail);
            int pm = GameBuild.permille;
            if (pm < 0) {
                progress.setIndeterminate(true);
            } else {
                progress.setIndeterminate(false);
                progress.setProgress(pm);
            }
            ui.postDelayed(this, 250);
        }
    };

    @Override protected void onResume() {
        super.onResume();
        ui.removeCallbacks(poll);
        ui.post(poll);
    }

    @Override protected void onPause() {
        super.onPause();
        ui.removeCallbacks(poll);
    }

    private void play() {
        if (launched) return;
        launched = true;
        startActivity(new Intent(this, DevilActivity.class));
        finish();
    }
}
