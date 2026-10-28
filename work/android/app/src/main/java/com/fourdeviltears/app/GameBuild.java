package com.fourdeviltears.app;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.StatFs;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Builds the game code module (libdmc4.so) on the phone from the player's own default.xex.
 *
 * The APK carries no game code. It carries a compiler (clang + lld, built for Android), the
 * code generator (rexglue: PowerPC -> C++) and a "kit" (assets/kit.zip): headers, the
 * runtime's link inputs and the app's own native code. After the game is imported:
 *
 *   1. codegen   default.xex -> generated C++ (files/build/generated)
 *   2. compile   every generated file -> .o on all big cores (files/build/obj)
 *   3. link      .o + app code -> files/native/libdmc4.so
 *
 * Runs in a foreground service (notification with progress, keeps going with the screen
 * off). Every finished object file is kept, so an interrupted build resumes where it
 * stopped. When the app is updated, only the parts that changed are redone: a new kit with
 * the same code generator / headers only relinks.
 */
public class GameBuild extends Service {
    private static final String TAG = "4DevilTearsBuild";
    private static final String CHANNEL = "build";
    private static final int NOTE_ID = 7;

    // ---- shared state, polled by BuildActivity ----------------------------------------------
    static volatile boolean running = false;
    static volatile boolean finished = false;
    static volatile String stage = "";
    static volatile String detail = "";
    static volatile String error = null;
    static volatile int permille = 0;   // < 0: indeterminate

    // ---- paths ---------------------------------------------------------------------------------
    static File kitDir(Context c) { return new File(c.getFilesDir(), "kit"); }
    static File buildDir(Context c) { return new File(c.getFilesDir(), "build"); }
    static File nativeDir(Context c) { return new File(c.getFilesDir(), "native"); }
    static File gameLib(Context c) { return new File(nativeDir(c), "libdmc4.so"); }

    /** A libdmc4.so shipped inside the APK (developer builds): used as is. */
    static File bundledLib(Context c) {
        return new File(c.getApplicationInfo().nativeLibraryDir, "libdmc4.so");
    }

    static boolean hasKit(Context c) {
        try (InputStream in = c.getAssets().open("kit.id")) { return true; } catch (IOException e) { return false; }
    }

    static String assetText(Context c, String name) {
        try (InputStream in = c.getAssets().open(name)) {
            return new String(readAll(in), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return "";
        }
    }

    /** True when the game can start: a bundled module, or a built one that matches this APK. */
    static boolean ready(Context c) {
        if (bundledLib(c).isFile()) return true;
        if (!hasKit(c)) return false;
        File lib = gameLib(c);
        return lib.isFile() && assetText(c, "kit.id").equals(readText(new File(nativeDir(c), "kit.id")));
    }

    /** Path of the module SDL should load. */
    static String libraryPath(Context c) {
        return bundledLib(c).isFile() ? bundledLib(c).getAbsolutePath() : gameLib(c).getAbsolutePath();
    }

    static void start(Context c) {
        if (running) return;
        Intent i = new Intent(c, GameBuild.class);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
    }

    // ---- service -------------------------------------------------------------------------------
    private PowerManager.WakeLock wake;
    private NotificationManager nm;
    private Notification.Builder note;
    private long lastNote = 0;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Preparing the game",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
            note = new Notification.Builder(this, CHANNEL);
        } else {
            note = new Notification.Builder(this);
        }
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, BuildActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        note.setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("DMC 4: preparing the game")
                .setContentText("Starting...")
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setProgress(1000, 0, true);
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTE_ID, note.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTE_ID, note.build());
        }
        if (running) return START_NOT_STICKY;
        running = true;
        finished = false;
        error = null;
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "4DevilTears:build");
        wake.acquire(6 * 3600 * 1000L);
        new Thread(this::run, "game-build").start();
        return START_NOT_STICKY;
    }

    private void run() {
        String err = null;
        long t0 = System.currentTimeMillis();
        try {
            build();
        } catch (Throwable t) {
            Log.e(TAG, "build", t);
            err = t.getMessage() != null ? t.getMessage() : t.toString();
        }
        error = err;
        finished = err == null;
        running = false;
        long min = (System.currentTimeMillis() - t0) / 60000;
        Log.i(TAG, "build " + (err == null ? "done" : "failed") + " in " + min + " min");
        if (wake != null && wake.isHeld()) wake.release();
        stopForeground(true);
        // Final notification (the player may be away): tap to play / to see the error.
        PendingIntent open = PendingIntent.getActivity(this, 1,
                new Intent(this, ImportActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(err == null ? "DMC 4 is ready" : "DMC 4: preparing failed")
                .setContentText(err == null ? "Tap to play" : err)
                .setContentIntent(open)
                .setAutoCancel(true);
        nm.notify(NOTE_ID + 1, b.build());
        stopSelf();
    }

    private void progress(String s, String d, int pm) {
        stage = s; detail = d; permille = pm;
        long now = System.currentTimeMillis();
        if (now - lastNote < 1000) return;
        lastNote = now;
        note.setContentText(s + (pm >= 0 ? String.format(Locale.US, "  %d%%", pm / 10) : ""))
                .setProgress(1000, Math.max(pm, 0), pm < 0);
        nm.notify(NOTE_ID, note.build());
    }

    // ---- the build -------------------------------------------------------------------------------
    private String bin(String name) { return getApplicationInfo().nativeLibraryDir + "/" + name; }

    private void build() throws Exception {
        File kit = kitDir(this), work = buildDir(this), out = nativeDir(this);
        String kitId = assetText(this, "kit.id");
        String recompId = assetText(this, "recomp.id");
        if (kitId.isEmpty()) throw new IOException("This build of the app has no compiler kit.");
        File xex = new File(ImportActivity.gameDir(this), "default.xex");
        if (!xex.isFile()) throw new IOException("The game isn't added yet.");

        // 1. Kit (headers, link inputs) - unpacked once per app version.
        if (!kitId.equals(readText(new File(kit, "kit.id")))) {
            progress("Unpacking the compiler...", "", -1);
            deleteTree(kit);
            unzipAsset("kit.zip", kit);
            writeText(new File(kit, "kit.id"), kitId);
        }
        Map<String, String> cfg = readConfig(new File(kit, "build.cfg"));

        // A new code generator / headers / compiler flags invalidate generated code and objects.
        String id = recompId + " " + xex.length();
        File gen = new File(work, "generated/default"), obj = new File(work, "obj");
        if (!id.equals(readText(new File(work, "recomp.id")))) {
            deleteTree(work);
        }
        work.mkdirs();
        obj.mkdirs();
        writeText(new File(work, "recomp.id"), id);

        long free = new StatFs(getFilesDir().getPath()).getAvailableBytes();
        if (free < 700L * 1024 * 1024) {
            throw new IOException(String.format(Locale.US,
                    "Not enough free space: about 0.7 GB needed, %.1f GB free.", free / 1e9));
        }

        // 2. Code generation: PowerPC -> C++.
        if (!new File(gen, "codegen.stamp").isFile()) {
            progress("Translating the game code...", "PowerPC → C++", -1);
            File manifest = new File(work, "dmc4_manifest.toml");
            // The game stays where the importer put it: point game_root at it (the code
            // generator refuses an executable outside game_root).
            String toml = new String(Files.readAllBytes(new File(kit, "dmc4_manifest.toml").toPath()),
                    StandardCharsets.UTF_8);
            toml = toml.replaceFirst("(?m)^game_root\\s*=.*$", java.util.regex.Matcher.quoteReplacement(
                    "game_root = \"" + xex.getParentFile().getAbsolutePath() + "\""));
            writeText(manifest, toml);
            File link = new File(work, "default.xex");
            link.delete();
            try {
                android.system.Os.symlink(xex.getAbsolutePath(), link.getAbsolutePath());
            } catch (Exception e) {
                Files.copy(xex.toPath(), link.toPath());
            }
            run(work, Arrays.asList(bin("librexglue.so"), "codegen", "dmc4_manifest.toml"), "codegen");
            if (!new File(gen, "codegen.stamp").isFile()) throw new IOException("Code generation failed.");
        }

        // 3. Compile.
        List<String> cxx = new ArrayList<>();
        cxx.add(bin("libclang.so"));
        cxx.add("--driver-mode=g++");
        cxx.add("--target=" + cfg.get("target"));
        cxx.add("--sysroot=" + new File(kit, "sysroot"));
        cxx.add("-resource-dir=" + new File(kit, "clang"));
        cxx.addAll(split(cfg.get("cxxflags")));
        cxx.add("-I" + gen);
        cxx.add("-isystem");
        cxx.add(new File(kit, "include").getAbsolutePath());

        File pchHeader = new File(work, "pch.hxx"), pch = new File(work, "pch.hxx.pch");
        if (!pch.isFile()) {
            progress("Preparing the compiler...", "", 0);
            writeText(pchHeader, "#include \"" + new File(gen, "dmc4_pch.h") + "\"\n");
            File tmp = new File(work, "pch.hxx.pch.tmp");
            List<String> a = new ArrayList<>(cxx);
            a.addAll(Arrays.asList("-x", "c++-header", pchHeader.getAbsolutePath(), "-o", tmp.getAbsolutePath()));
            run(work, a, "pch");
            if (!tmp.renameTo(pch)) throw new IOException("Can't write " + pch);
        }

        File[] srcs = gen.listFiles((d, n) -> n.endsWith(".cpp"));
        if (srcs == null || srcs.length == 0) throw new IOException("No generated code.");
        // Biggest first: the longest files must not end up last on a single core.
        Arrays.sort(srcs, (x, y) -> Long.compare(y.length(), x.length()));
        long total = 0;
        for (File f : srcs) total += weight(f);
        AtomicLong done = new AtomicLong();
        List<File> todo = new ArrayList<>();
        List<File> objects = new ArrayList<>();
        for (File f : srcs) {
            File o = new File(obj, f.getName() + ".o");
            objects.add(o);
            if (o.isFile()) done.addAndGet(weight(f)); else todo.add(f);
        }
        int jobs = jobs();
        Log.i(TAG, "compile: " + todo.size() + "/" + srcs.length + " files, " + jobs + " jobs");
        final long totalW = total;
        final long startW = done.get();
        final long t0 = System.currentTimeMillis();
        final int[] filesDone = { srcs.length - todo.size() };
        final int filesTotal = srcs.length;
        Runnable report = () -> {
            long d = done.get();
            int pm = (int) (20 + d * 960 / Math.max(1, totalW));
            String eta = "";
            long el = System.currentTimeMillis() - t0;
            if (d > startW && el > 20000) {
                long left = (long) ((totalW - d) * (double) el / (d - startW)) / 1000;
                eta = left >= 90 ? String.format(Locale.US, "  ·  about %d min left", (left + 59) / 60)
                        : "  ·  less than 2 min left";
            }
            progress("Building the game code...",
                    String.format(Locale.US, "%d / %d files%s", filesDone[0], filesTotal, eta), pm);
        };
        report.run();
        ExecutorService pool = Executors.newFixedThreadPool(jobs);
        List<Future<?>> fs = new ArrayList<>();
        for (File f : todo) {
            fs.add(pool.submit(() -> {
                File o = new File(obj, f.getName() + ".o"), tmp = new File(obj, f.getName() + ".o.tmp");
                List<String> a = new ArrayList<>(cxx);
                // The function table: one giant function run once at start-up.
                if (f.getName().endsWith("_register.cpp")) a.add("-O1");
                a.addAll(Arrays.asList("-include-pch", pch.getAbsolutePath(),
                        "-c", f.getAbsolutePath(), "-o", tmp.getAbsolutePath()));
                run(work, a, f.getName());
                if (!tmp.renameTo(o)) throw new IOException("Can't write " + o);
                done.addAndGet(weight(f));
                synchronized (filesDone) { filesDone[0]++; }
                report.run();
                return null;
            }));
        }
        pool.shutdown();
        try {
            for (Future<?> f : fs) {
                try {
                    f.get();
                } catch (java.util.concurrent.ExecutionException e) {
                    pool.shutdownNow();
                    Throwable c = e.getCause();
                    throw c instanceof Exception ? (Exception) c : new IOException(String.valueOf(c));
                }
            }
        } finally {
            pool.shutdownNow();
        }

        // 4. Link. The compiler driver works out the full linker command (crt objects,
        // runtime libraries) with -###; lld then runs directly from the app's native
        // library folder (it is the only place Android lets an app execute files from).
        progress("Linking...", "", 985);
        out.mkdirs();
        File lib = gameLib(this), tmp = new File(out, "libdmc4.so.tmp");
        File rsp = new File(work, "objects.rsp");
        StringBuilder sb = new StringBuilder();
        for (File o : objects) sb.append(quote(o.getAbsolutePath())).append('\n');
        for (String s : split(cfg.get("linkinputs"))) {
            sb.append(quote(s.replace("@KIT@", kit.getAbsolutePath())
                    .replace("@LIBDIR@", getApplicationInfo().nativeLibraryDir))).append('\n');
        }
        writeText(rsp, sb.toString());
        List<String> a = new ArrayList<>();
        a.add(bin("libclang.so"));
        a.add("--driver-mode=g++");
        a.add("--target=" + cfg.get("target"));
        a.add("--sysroot=" + new File(kit, "sysroot"));
        a.add("-resource-dir=" + new File(kit, "clang"));
        a.add("-fuse-ld=lld");
        a.add("--ld-path=" + bin("liblld.so"));   // only printed (-###), never run by the driver
        for (String s : split(cfg.get("linkflags"))) a.add(s.replace("@KIT@", kit.getAbsolutePath()));
        a.add("-o");
        a.add(tmp.getAbsolutePath());
        a.add("@" + rsp.getAbsolutePath());
        a.add("-###");
        List<String> ld = null;
        for (String line : runCapture(work, a, "link-plan")) {
            if (line.startsWith(" \"")) ld = parseQuoted(line);
        }
        if (ld == null || ld.size() < 2) throw new IOException("link: no linker command");
        StringBuilder lr = new StringBuilder();
        for (int i = 1; i < ld.size(); i++) lr.append(quote(ld.get(i))).append('\n');
        File lrsp = new File(work, "link.rsp");
        writeText(lrsp, lr.toString());
        run(work, Arrays.asList(bin("liblld.so"), "-flavor", "gnu", "@" + lrsp.getAbsolutePath()), "link");
        lib.delete();
        if (!tmp.renameTo(lib)) throw new IOException("Can't write " + lib);
        writeText(new File(out, "kit.id"), kitId);
        progress("Ready", "", 1000);
    }

    /** Rough compile cost: the function table takes ~20x longer per byte than code. */
    private static long weight(File f) {
        return 4096 + f.length() * (f.getName().endsWith("_register.cpp") ? 20 : 1);
    }

    private int jobs() {
        int cores = Runtime.getRuntime().availableProcessors();
        ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        // A compiler process needs 0.2-0.5 GB here; leave room for the system.
        long gb = mi.totalMem / (1024L * 1024 * 1024);
        int byMem = (int) Math.max(1, (gb - 2) * 2);
        return Math.max(1, Math.min(Math.min(cores - 1, byMem), 6));
    }

    private final Object logLock = new Object();

    private void run(File cwd, List<String> args, String what) throws Exception {
        runCapture(cwd, args, what);
    }

    /** Runs a tool from the native library folder; returns its output (stderr included). */
    private List<String> runCapture(File cwd, List<String> args, String what) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(args).directory(cwd).redirectErrorStream(true);
        pb.environment().put("LD_LIBRARY_PATH", getApplicationInfo().nativeLibraryDir);
        pb.environment().put("TMPDIR", getCacheDir().getAbsolutePath());
        Process p = pb.start();
        List<String> outLines = new ArrayList<>();
        ArrayDeque<String> tail = new ArrayDeque<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (outLines.size() < 200) outLines.add(line);
                if (tail.size() >= 30) tail.removeFirst();
                tail.addLast(line);
            }
        }
        int rc = p.waitFor();
        boolean plan = args.contains("-###");
        if (rc != 0 || (!tail.isEmpty() && !plan)) {
            synchronized (logLock) {
                try (FileOutputStream o = new FileOutputStream(new File(buildDir(this), "build.log"), true)) {
                    StringBuilder sb = new StringBuilder("== " + what + " rc=" + rc + "\n");
                    if (rc != 0) sb.append(String.join(" ", args)).append('\n');
                    for (String l : tail) sb.append(l).append('\n');
                    o.write(sb.toString().getBytes(StandardCharsets.UTF_8));
                } catch (IOException ignored) { }
            }
        }
        if (rc != 0) {
            Log.e(TAG, what + " failed rc=" + rc + ": " + String.join("\n", tail));
            String last = tail.isEmpty() ? "" : tail.peekLast();
            throw new IOException(what + " failed (" + rc + ")" + (last.isEmpty() ? "" : ":\n" + last));
        }
        return outLines;
    }

    /** Splits a line of -### output: "arg" "arg with \"quotes\"" ... */
    private static List<String> parseQuoted(String line) {
        List<String> r = new ArrayList<>();
        int i = 0, n = line.length();
        while (i < n) {
            while (i < n && line.charAt(i) == ' ') i++;
            if (i >= n) break;
            if (line.charAt(i) != '"') {
                int j = line.indexOf(' ', i);
                if (j < 0) j = n;
                r.add(line.substring(i, j));
                i = j;
                continue;
            }
            StringBuilder sb = new StringBuilder();
            i++;
            while (i < n && line.charAt(i) != '"') {
                char c = line.charAt(i++);
                if (c == '\\' && i < n) c = line.charAt(i++);
                sb.append(c);
            }
            i++;
            r.add(sb.toString());
        }
        return r;
    }

    // ---- helpers -------------------------------------------------------------------------------
    private void unzipAsset(String name, File dst) throws IOException {
        File tmp = new File(dst.getPath() + ".tmp");
        deleteTree(tmp);
        tmp.mkdirs();
        byte[] buf = new byte[1 << 16];
        String root = tmp.getCanonicalPath() + File.separator;
        try (ZipInputStream z = new ZipInputStream(getAssets().open(name))) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                File f = new File(tmp, e.getName());
                if (!f.getCanonicalPath().startsWith(root)) throw new IOException("bad kit entry " + e.getName());
                if (e.isDirectory()) { f.mkdirs(); continue; }
                f.getParentFile().mkdirs();
                try (FileOutputStream o = new FileOutputStream(f)) {
                    int n;
                    while ((n = z.read(buf)) > 0) o.write(buf, 0, n);
                }
            }
        }
        if (!tmp.renameTo(dst)) throw new IOException("Can't unpack the compiler kit");
    }

    private static Map<String, String> readConfig(File f) throws IOException {
        Map<String, String> m = new HashMap<>();
        for (String line : Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) {
            int eq = line.indexOf('=');
            if (eq > 0 && !line.startsWith("#")) m.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
        }
        for (String k : new String[] { "target", "cxxflags", "linkflags", "linkinputs" }) {
            if (!m.containsKey(k)) throw new IOException("kit: no " + k);
        }
        return m;
    }

    private static List<String> split(String s) {
        List<String> r = new ArrayList<>();
        if (s == null) return r;
        for (String t : s.trim().split("\\s+")) if (!t.isEmpty()) r.add(t);
        return r;
    }

    private static String quote(String s) { return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""; }

    static String readText(File f) {
        try { return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim(); }
        catch (IOException e) { return ""; }
    }

    static void writeText(File f, String s) throws IOException {
        try (FileOutputStream o = new FileOutputStream(f)) { o.write(s.getBytes(StandardCharsets.UTF_8)); }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        return b.toByteArray();
    }

    static void deleteTree(File f) {
        if (!f.exists() && !isSymlink(f)) return;
        if (f.isDirectory() && !isSymlink(f)) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteTree(k);
        }
        f.delete();
    }

    private static boolean isSymlink(File f) { return Files.isSymbolicLink(f.toPath()); }
}
