package com.fourdeviltears.app;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.StatFs;
import android.provider.DocumentsContract;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * First launch: the player points the app at their own copy of the game and it is unpacked
 * into app-private storage (files/game: default.xex + nativeXenon/). Nothing from the game
 * ships with the app.
 *
 * Accepted sources (the layout is detected automatically):
 *  - a disc image (.iso): plain XISO or a full Redump XGD1/2/3 image;
 *  - a Games on Demand folder (e.g. the Russian fan translation): the outer folder,
 *    the 434307DF folder, the 00007000 folder or the <id>.data folder with Data0000...
 */
public class ImportActivity extends Activity {
    private static final String TAG = "4DevilTearsImport";
    private static final int REQ_ISO = 1, REQ_FOLDER = 2;
    // Devil May Cry 4 (Xbox 360), default.xex v3 (TitleID 434307DF, MediaID 6729D527).
    // The Russian fan translation keeps the same executable.
    static final String KNOWN_XEX_SHA256 =
            "73472b0348d4abf23a27c80ee6c5ec7f3894783c6b816d38f670af4fe74b0a38";
    private static final long NEEDED_BYTES = 7L * 1024 * 1024 * 1024;

    private TextView status, detail;
    private ProgressBar progress;
    private LinearLayout buttons;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean busy = false;

    static File gameDir(android.content.Context c) { return new File(c.getFilesDir(), "game"); }

    static boolean gameInstalled(android.content.Context c) {
        File g = gameDir(c);
        return new File(g, "default.xex").isFile() && new File(g, "nativeXenon").isDirectory();
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (gameInstalled(this) && !getIntent().getBooleanExtra("reimport", false)) {
            launchGame();
            return;
        }
        buildUi();
    }

    private void launchGame() {
        if (getIntent().getBooleanExtra("reimport", false)) {
            // The game is already running on the old files: restart the app cleanly.
            Intent restart = new Intent(this, ImportActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            android.app.PendingIntent pi = android.app.PendingIntent.getActivity(this, 0, restart,
                    android.app.PendingIntent.FLAG_IMMUTABLE | android.app.PendingIntent.FLAG_CANCEL_CURRENT);
            android.app.AlarmManager am = (android.app.AlarmManager) getSystemService(ALARM_SERVICE);
            if (am != null) am.set(android.app.AlarmManager.RTC, System.currentTimeMillis() + 500, pi);
            finishAffinity();
            System.exit(0);
            return;
        }
        startActivity(new Intent(this, DevilActivity.class));
        finish();
    }

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private void buildUi() {
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
        status.setText("Add your copy of Devil May Cry 4 (Xbox 360)");
        root.addView(status);

        detail = new TextView(this);
        detail.setTextColor(0xFFB8BEC8);
        detail.setTextSize(13);
        detail.setGravity(Gravity.CENTER);
        detail.setText("Choose the disc image (.iso), or the Games on Demand folder "
                + "(for example \"2008 Devil May Cry 4\" or the 434307DF folder inside it). "
                + "The game is unpacked once into the app (about 6.5 GB).");
        root.addView(detail);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1000);
        progress.setVisibility(View.GONE);
        progress.setProgressTintList(android.content.res.ColorStateList.valueOf(0xFFE0453A));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(dp(420), dp(12));
        plp.topMargin = dp(18);
        root.addView(progress, plp);

        buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.CENTER);
        buttons.setPadding(0, dp(22), 0, 0);
        Button iso = new Button(this);
        iso.setText("Disc image (.iso)");
        iso.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            startActivityForResult(i, REQ_ISO);
        });
        Button folder = new Button(this);
        folder.setText("Game folder");
        folder.setOnClickListener(v ->
                startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_FOLDER));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.leftMargin = dp(8);
        blp.rightMargin = dp(8);
        buttons.addView(iso, blp);
        buttons.addView(folder, blp);
        root.addView(buttons);

        setContentView(root);
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null || busy) return;
        final Uri uri = data.getData();
        final boolean tree = req == REQ_FOLDER;
        busy = true;
        buttons.setVisibility(View.GONE);
        progress.setVisibility(View.VISIBLE);
        progress.setProgress(0);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        new Thread(() -> {
            String error = null;
            try {
                runImport(uri, tree);
            } catch (Exception e) {
                Log.e(TAG, "import", e);
                error = e.getMessage() != null ? e.getMessage() : e.toString();
            }
            final String err = error;
            ui.post(() -> {
                busy = false;
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                if (err == null) {
                    launchGame();
                } else {
                    status.setText("Couldn't add the game");
                    detail.setText(err);
                    progress.setVisibility(View.GONE);
                    buttons.setVisibility(View.VISIBLE);
                }
            });
        }, "import").start();
    }

    private void setStatus(String s, String d) { ui.post(() -> { status.setText(s); detail.setText(d); }); }

    // ---- sources -------------------------------------------------------------------------

    /** A seekable byte source: the whole disc image. */
    interface Source extends AutoCloseable {
        int read(long pos, byte[] dst, int off, int len) throws IOException;
        long length();
        @Override void close();
    }

    static final class FileSource implements Source {
        private final ParcelFileDescriptor pfd;
        private final FileChannel ch;
        FileSource(ParcelFileDescriptor pfd) {
            this.pfd = pfd;
            this.ch = new FileInputStream(pfd.getFileDescriptor()).getChannel();
        }
        @Override public int read(long pos, byte[] dst, int off, int len) throws IOException {
            return ch.read(ByteBuffer.wrap(dst, off, len), pos);
        }
        @Override public long length() {
            try { return ch.size(); } catch (IOException e) { return -1; }
        }
        @Override public void close() {
            try { ch.close(); pfd.close(); } catch (IOException ignored) { }
        }
    }

    /** Games on Demand (SVOD): Data0000.. files of 0xCB groups, each one hash block + 0xCC
     *  data blocks of 4 KiB, after a 4 KiB master hash block. */
    static final class GodSource implements Source {
        static final int BLK = 0x1000, PER = 0xCC, GROUPS = 0xCB;
        private final List<ParcelFileDescriptor> pfds = new ArrayList<>();
        private final List<FileChannel> chans = new ArrayList<>();
        GodSource(List<ParcelFileDescriptor> files) {
            for (ParcelFileDescriptor p : files) {
                pfds.add(p);
                chans.add(new FileInputStream(p.getFileDescriptor()).getChannel());
            }
        }
        @Override public int read(long pos, byte[] dst, int off, int len) throws IOException {
            int done = 0;
            while (len > 0) {
                long b = pos / BLK;
                long perFile = (long) PER * GROUPS;
                int fi = (int) (b / perFile);
                long w = b % perFile;
                long g = w / PER, k = w % PER;
                if (fi >= chans.size()) break;
                long fo = 0x1000 + g * (PER + 1) * (long) BLK + BLK + k * BLK + pos % BLK;
                int take = (int) Math.min(len, BLK - pos % BLK);
                int n = chans.get(fi).read(ByteBuffer.wrap(dst, off, take), fo);
                if (n <= 0) break;
                done += n; off += n; len -= n; pos += n;
            }
            return done;
        }
        @Override public long length() { return (long) chans.size() * PER * GROUPS * BLK; }
        @Override public void close() {
            for (FileChannel c : chans) try { c.close(); } catch (IOException ignored) { }
            for (ParcelFileDescriptor p : pfds) try { p.close(); } catch (IOException ignored) { }
        }
    }

    /** Finds the folder holding Data0000.. anywhere below the picked folder (5 levels). */
    private List<ParcelFileDescriptor> openGodData(Uri tree) throws IOException {
        String rootId = DocumentsContract.getTreeDocumentId(tree);
        List<String[]> found = new ArrayList<>();  // {name, docId}
        findData(tree, rootId, 0, found);
        if (found.isEmpty()) {
            throw new IOException("No game data found in this folder. Pick the Games on Demand "
                    + "folder (with Data0000, Data0001... inside), or use the .iso option.");
        }
        found.sort((a, b) -> a[0].compareTo(b[0]));
        List<ParcelFileDescriptor> out = new ArrayList<>();
        for (String[] f : found) {
            Uri u = DocumentsContract.buildDocumentUriUsingTree(tree, f[1]);
            out.add(getContentResolver().openFileDescriptor(u, "r"));
        }
        return out;
    }

    private boolean findData(Uri tree, String docId, int depth, List<String[]> out) {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId);
        List<String[]> dirs = new ArrayList<>();
        List<String[]> data = new ArrayList<>();
        try (Cursor c = getContentResolver().query(children, new String[] {
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_MIME_TYPE }, null, null, null)) {
            if (c == null) return false;
            while (c.moveToNext()) {
                String name = c.getString(0), id = c.getString(1), mime = c.getString(2);
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) dirs.add(new String[] { name, id });
                else if (name != null && name.matches("Data\\d{4}")) data.add(new String[] { name, id });
            }
        }
        if (!data.isEmpty()) { out.addAll(data); return true; }
        if (depth >= 5) return false;
        for (String[] d : dirs) if (findData(tree, d[1], depth + 1, out)) return true;
        return false;
    }

    // ---- XDVDFS ----------------------------------------------------------------------------

    private static final int SECTOR = 2048;
    private static final long[] PARTITIONS = { 0L, 0xFD90000L, 0x2080000L, 0x18300000L };

    private static final class Entry {
        final String path; final long sector, size; final boolean dir;
        Entry(String p, long s, long z, boolean d) { path = p; sector = s; size = z; dir = d; }
    }

    private static int u16(byte[] b, int o) { return (b[o] & 0xFF) | (b[o + 1] & 0xFF) << 8; }
    private static long u32(byte[] b, int o) {
        return (b[o] & 0xFFL) | (b[o + 1] & 0xFFL) << 8 | (b[o + 2] & 0xFFL) << 16 | (b[o + 3] & 0xFFL) << 24;
    }

    private static void readFully(Source s, long pos, byte[] b, int len) throws IOException {
        int got = 0;
        while (got < len) {
            int n = s.read(pos + got, b, got, len - got);
            if (n <= 0) throw new IOException("The image ends too early (incomplete copy?)");
            got += n;
        }
    }

    private static long findPartition(Source s) throws IOException {
        byte[] m = new byte[20];
        for (long base : PARTITIONS) {
            if (s.length() > 0 && base + 32L * SECTOR + 20 > s.length()) continue;
            try { readFully(s, base + 32L * SECTOR, m, 20); } catch (IOException e) { continue; }
            if (new String(m, java.nio.charset.StandardCharsets.US_ASCII).equals("MICROSOFT*XBOX*MEDIA")) {
                return base;
            }
        }
        throw new IOException("This doesn't look like an Xbox 360 game image.");
    }

    private static void walk(Source s, long base, long sector, long size, String rel,
                             List<Entry> out) throws IOException {
        if (size == 0) return;
        if (size > 64L * 1024 * 1024) throw new IOException("Damaged directory table");
        byte[] d = new byte[(int) size];
        readFully(s, base + sector * SECTOR, d, (int) size);
        java.util.ArrayDeque<Integer> stack = new java.util.ArrayDeque<>();
        java.util.HashSet<Integer> seen = new java.util.HashSet<>();
        stack.push(0);
        while (!stack.isEmpty()) {
            int off = stack.pop();
            if (!seen.add(off) || off + 14 > d.length) continue;
            int left = u16(d, off), right = u16(d, off + 2);
            if (left == 0xFFFF && right == 0xFFFF) continue;
            long start = u32(d, off + 4), fsize = u32(d, off + 8);
            int attr = d[off + 12] & 0xFF, nlen = d[off + 13] & 0xFF;
            if (off + 14 + nlen > d.length) continue;
            String name = new String(d, off + 14, nlen, java.nio.charset.StandardCharsets.ISO_8859_1);
            String path = rel.isEmpty() ? name : rel + "/" + name;
            boolean dir = (attr & 0x10) != 0;
            out.add(new Entry(path, start, fsize, dir));
            if (dir) walk(s, base, start, fsize, path, out);
            if (left != 0) stack.push(left * 4);
            if (right != 0) stack.push(right * 4);
        }
    }

    // ---- import -----------------------------------------------------------------------------

    private void runImport(Uri uri, boolean tree) throws Exception {
        setStatus("Opening...", "");
        Source src;
        if (tree) src = new GodSource(openGodData(uri));
        else src = new FileSource(getContentResolver().openFileDescriptor(uri, "r"));
        try (Source s = src) {
            long base = findPartition(s);
            byte[] vd = new byte[8];
            readFully(s, base + 32L * SECTOR + 20, vd, 8);
            List<Entry> all = new ArrayList<>();
            walk(s, base, u32(vd, 0), u32(vd, 4), "", all);

            List<Entry> files = new ArrayList<>();
            long total = 0;
            Entry xex = null;
            for (Entry e : all) {
                if (e.dir) continue;
                if (e.path.equalsIgnoreCase("default.xex")) { xex = e; files.add(e); total += e.size; }
                else if (e.path.startsWith("nativeXenon/")) { files.add(e); total += e.size; }
            }
            if (xex == null) throw new IOException("default.xex not found - is this Devil May Cry 4?");

            // The executable first: it decides whether this copy is supported at all.
            setStatus("Checking the game...", "");
            byte[] x = new byte[(int) xex.size];
            readFully(s, base + xex.sector * SECTOR, x, x.length);
            String sha = sha256(x);
            if (!KNOWN_XEX_SHA256.equals(sha)) {
                throw new IOException("This version of the game isn't supported yet.\n(default.xex "
                        + sha.substring(0, 16) + ")");
            }

            long free = new StatFs(getFilesDir().getPath()).getAvailableBytes();
            File game = gameDir(this), tmp = new File(getFilesDir(), "game.importing");
            if (free < total + 256L * 1024 * 1024) {
                throw new IOException(String.format(Locale.US,
                        "Not enough free space: %.1f GB needed, %.1f GB free.",
                        total / 1e9, free / 1e9));
            }
            deleteTree(tmp);
            if (!tmp.mkdirs()) throw new IOException("Can't create " + tmp);

            byte[] buf = new byte[4 << 20];
            long doneBytes = 0;
            long t0 = System.currentTimeMillis(), lastUi = 0;
            int n = 0;
            for (Entry e : files) {
                File dst = new File(tmp, e.path);
                File parent = dst.getParentFile();
                if (parent != null) parent.mkdirs();
                try (FileOutputStream o = new FileOutputStream(dst)) {
                    long left = e.size, pos = base + e.sector * SECTOR;
                    while (left > 0) {
                        int chunk = (int) Math.min(left, buf.length);
                        readFully(s, pos, buf, chunk);
                        o.write(buf, 0, chunk);
                        pos += chunk; left -= chunk; doneBytes += chunk;
                        long now = System.currentTimeMillis();
                        if (now - lastUi > 250) {
                            lastUi = now;
                            final int p = (int) (doneBytes * 1000 / Math.max(1, total));
                            double mbs = doneBytes / 1e6 / Math.max(0.001, (now - t0) / 1000.0);
                            final String d = String.format(Locale.US, "%.1f / %.1f GB  ·  %.0f MB/s\n%s",
                                    doneBytes / 1e9, total / 1e9, mbs, e.path);
                            ui.post(() -> { progress.setProgress(p); status.setText("Unpacking the game..."); detail.setText(d); });
                        }
                    }
                }
                n++;
            }
            deleteTree(game);
            if (!tmp.renameTo(game)) throw new IOException("Can't move the unpacked game into place");
            setStatus("Done", n + " files");
        }
    }

    private static String sha256(byte[] b) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] h = md.digest(b);
        StringBuilder sb = new StringBuilder();
        for (byte v : h) sb.append(String.format(Locale.US, "%02x", v));
        return sb.toString();
    }

    private static void deleteTree(File f) {
        if (!f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }
}
