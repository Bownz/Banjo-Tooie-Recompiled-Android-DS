package io.github.bownz.btrecomp;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.RandomAccessFile;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Support tools for testers: the app version, per-session log files, crash detection and a
 * one-tap "share log" report (no adb needed).
 *
 * The game's native output (stdout/stderr, already sent to logcat) is also written to
 * files/logs/session.txt by android_main.cpp; the previous session's file is kept as
 * previous.txt so a crash can still be reported after a restart.
 */
final class Diagnostics {
    private static final String TAG = "BTRecomp";
    static final String AUTHORITY_SUFFIX = ".logs";
    private static final String PREFS = "diagnostics";
    private static final String KEY_LAST_EXIT = "last_exit_seen";
    private static final String KEY_LEFT_AT = "left_at";
    private static final int MAX_LOG_BYTES = 3 * 1024 * 1024;

    private Diagnostics() {}

    /** e.g. "0.2.0-beta (76e2c32)". */
    static String version(Context c) {
        try {
            PackageInfo info = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return info.versionName;
        } catch (Exception e) {
            return "unknown";
        }
    }

    static File logDir(Context c) {
        File dir = new File(c.getFilesDir(), "logs");
        dir.mkdirs();
        return dir;
    }

    /** Called once per game start, before the native side opens session.txt. */
    static void rotateLogs(Context c) {
        File dir = logDir(c);
        File session = new File(dir, "session.txt");
        File previous = new File(dir, "previous.txt");
        if (session.isFile()) {
            previous.delete();
            session.renameTo(previous);
        }
        Log.i(TAG, "Banjo-Tooie Recompiled Android " + version(c) + " on " + Build.MANUFACTURER + " " + Build.MODEL
            + ", Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
    }

    // ---- Crash detection -----------------------------------------------------------------

    /**
     * The player is leaving the game (it went to the background, or is closing). Android can
     * report the game as crashed when it is killed or shuts down after that; those aren't
     * crashes the player saw, so they aren't offered as reports.
     */
    static void markLeaving(Context c) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(KEY_LEFT_AT, System.currentTimeMillis()).commit();
    }

    /** Back in the game: a crash from now on is one the player would see. */
    static void markPlaying(Context c) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(KEY_LEFT_AT, 0).apply();
    }

    /**
     * If the game's process ended in a crash or freeze since we last checked, a short
     * description; otherwise null. Android 11+ only (ApplicationExitInfo).
     */
    static String takeUnreportedCrash(Context c) {
        if (Build.VERSION.SDK_INT < 30) {
            return null;
        }
        try {
            ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
            List<ApplicationExitInfo> exits = am.getHistoricalProcessExitReasons(c.getPackageName(), 0, 5);
            SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            long lastSeen = p.getLong(KEY_LAST_EXIT, 0);
            long leftAt = p.getLong(KEY_LEFT_AT, 0);
            long newest = lastSeen;
            String found = null;
            for (ApplicationExitInfo exit : exits) {
                if (!c.getPackageName().equals(exit.getProcessName())) {
                    continue; // only the game's process, not the settings screen
                }
                newest = Math.max(newest, exit.getTimestamp());
                if (exit.getTimestamp() <= lastSeen || found != null) {
                    continue;
                }
                if (leftAt > 0 && exit.getTimestamp() >= leftAt) {
                    continue; // ended while closing or in the background, not mid-game
                }
                int reason = exit.getReason();
                if (reason == ApplicationExitInfo.REASON_CRASH || reason == ApplicationExitInfo.REASON_CRASH_NATIVE
                        || reason == ApplicationExitInfo.REASON_ANR) {
                    found = describe(exit);
                }
            }
            p.edit().putLong(KEY_LAST_EXIT, newest).apply();
            return found;
        } catch (Exception e) {
            return null;
        }
    }

    private static String describe(ApplicationExitInfo exit) {
        String kind;
        switch (exit.getReason()) {
            case ApplicationExitInfo.REASON_CRASH: kind = "crash (Java)"; break;
            case ApplicationExitInfo.REASON_CRASH_NATIVE: kind = "crash (native)"; break;
            case ApplicationExitInfo.REASON_ANR: kind = "stopped responding"; break;
            default: kind = "exit " + exit.getReason(); break;
        }
        String when = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date(exit.getTimestamp()));
        String text = kind + " at " + when;
        if (exit.getDescription() != null) {
            text += ": " + exit.getDescription();
        }
        return text;
    }

    // ---- Report --------------------------------------------------------------------------

    /** Writes the report to cache/share and returns it. {@code crash} may be null. */
    static File writeReport(Context c, String crash) throws IOException {
        File dir = new File(c.getCacheDir(), "share");
        dir.mkdirs();
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        File out = new File(dir, "bt-android-log-" + stamp + ".txt");
        File[] old = dir.listFiles();
        if (old != null) {
            for (File f : old) {
                f.delete();
            }
        }
        try (Writer w = new OutputStreamWriter(new FileOutputStream(out), StandardCharsets.UTF_8)) {
            w.write("Banjo-Tooie Recompiled Android - log report\n");
            w.write("App version: " + version(c) + "\n");
            w.write("Device: " + Build.MANUFACTURER + " " + Build.MODEL + " (" + Build.DEVICE + ")\n");
            w.write("Android: " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")\n");
            w.write("SoC: " + (Build.VERSION.SDK_INT >= 31 ? Build.SOC_MANUFACTURER + " " + Build.SOC_MODEL : Build.HARDWARE) + "\n");
            GpuDrivers.Driver driver = GpuDrivers.selected(c);
            w.write("GPU driver selected: " + (driver == null ? "System driver" : driver.label()) + "\n");
            if (crash != null) {
                w.write("Last exit: " + crash + "\n");
            }
            w.write("\n");
            File dirLogs = logDir(c);
            appendFile(w, "Previous session", new File(dirLogs, "previous.txt"));
            appendFile(w, "Current session", new File(dirLogs, "session.txt"));
            appendLogcat(w);
        }
        return out;
    }

    private static void appendFile(Writer w, String title, File f) throws IOException {
        w.write("===== " + title + " (" + f.getName() + ") =====\n");
        if (!f.isFile()) {
            w.write("(none)\n\n");
            return;
        }
        try (RandomAccessFile in = new RandomAccessFile(f, "r")) {
            long length = in.length();
            long start = Math.max(0, length - MAX_LOG_BYTES);
            if (start > 0) {
                w.write("(first " + start + " bytes left out)\n");
            }
            in.seek(start);
            byte[] data = new byte[(int) (length - start)];
            in.readFully(data);
            w.write(new String(data, StandardCharsets.UTF_8));
        }
        w.write("\n\n");
    }

    /** This app's own recent logcat (apps may only read their own). */
    private static void appendLogcat(Writer w) throws IOException {
        w.write("===== logcat (this app) =====\n");
        try {
            Process process = new ProcessBuilder("logcat", "-d", "-v", "time", "-t", "4000").redirectErrorStream(true).start();
            try (InputStream in = process.getInputStream();
                 BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    w.write(line);
                    w.write('\n');
                }
            }
        } catch (IOException e) {
            w.write("(couldn't read logcat: " + e.getMessage() + ")\n");
        }
    }

    /**
     * TEMPORARY (test builds): when Mark has saved memory snapshots (files/probe), packs them
     * with the report into one zip next to it, so Share Log sends everything. Null when there
     * are none.
     */
    private static File zipWithProbe(Context c, File report) throws IOException {
        File base = c.getExternalFilesDir(null);
        File probe = base != null ? new File(base, "probe") : null;
        File[] files = probe != null ? probe.listFiles() : null;
        if (files == null || files.length == 0) {
            return null;
        }
        java.util.Arrays.sort(files);
        File zip = new File(report.getParentFile(), report.getName().replace(".txt", ".zip"));
        byte[] buffer = new byte[1 << 16];
        try (java.util.zip.ZipOutputStream out = new java.util.zip.ZipOutputStream(new FileOutputStream(zip))) {
            for (File f : prepend(report, files)) {
                if (!f.isFile()) {
                    continue;
                }
                java.util.zip.ZipEntry entry = new java.util.zip.ZipEntry(f == report ? f.getName() : "probe/" + f.getName());
                // The snapshots are gzip already.
                out.setLevel(f.getName().endsWith(".gz") ? 0 : 6);
                out.putNextEntry(entry);
                try (InputStream in = new java.io.FileInputStream(f)) {
                    int n;
                    while ((n = in.read(buffer)) > 0) {
                        out.write(buffer, 0, n);
                    }
                }
                out.closeEntry();
            }
        }
        return zip;
    }

    private static File[] prepend(File first, File[] rest) {
        File[] all = new File[rest.length + 1];
        all[0] = first;
        System.arraycopy(rest, 0, all, 1, rest.length);
        return all;
    }

    /** Builds the report and opens the share sheet (Files, Drive, Discord, email...). */
    static void share(Activity activity, String crash) {
        try {
            File report = writeReport(activity, crash);
            File zip = zipWithProbe(activity, report);
            File shared = zip != null ? zip : report;
            Uri uri = Uri.parse("content://" + activity.getPackageName() + AUTHORITY_SUFFIX + "/" + shared.getName());
            Intent send = new Intent(Intent.ACTION_SEND)
                .setType(zip != null ? "application/zip" : "text/plain")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(Intent.EXTRA_SUBJECT, "Banjo-Tooie Recompiled Android log (" + version(activity) + ")")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.startActivity(Intent.createChooser(send, "Share Banjo-Tooie log").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
        } catch (Exception e) {
            Log.w(TAG, "Couldn't share the log", e);
            android.widget.Toast.makeText(activity, "Couldn't create the log: " + e.getMessage(), android.widget.Toast.LENGTH_LONG).show();
        }
    }
}
