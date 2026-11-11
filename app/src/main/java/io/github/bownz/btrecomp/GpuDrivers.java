package io.github.bownz.btrecomp;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Custom Vulkan drivers the player installs (e.g. Mesa Turnip packages for Adreno, in the
 * AdrenoTools .zip format: a meta.json naming the driver library, plus the library).
 *
 * Drivers are unpacked into the app's private files (dlopen refuses shared storage). The
 * native side loads the selected one with libadrenotools when the renderer starts; see
 * android/jni/gpu_driver.cpp. If a launch with a custom driver doesn't get going, the next
 * launch goes back to the system driver.
 */
final class GpuDrivers {
    private static final String TAG = "BTRecomp";
    private static final String PREFS = "gpu_driver";
    private static final String KEY_SELECTED = "selected";   // folder name, or "" = system
    private static final String KEY_TRIAL = "trial";         // selected driver not yet proven
    static final long TRIAL_MS = 20_000;

    /** An installed driver. */
    static final class Driver {
        final String id;          // folder name
        final String name;
        final String version;
        final String library;

        Driver(String id, String name, String version, String library) {
            this.id = id;
            this.name = name;
            this.version = version;
            this.library = library;
        }

        String label() {
            return version.isEmpty() ? name : name + " (" + version + ")";
        }
    }

    private GpuDrivers() {}

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static File root(Context c) {
        return new File(c.getFilesDir(), "gpu_drivers");
    }

    static List<Driver> installed(Context c) {
        List<Driver> drivers = new ArrayList<>();
        File[] dirs = root(c).listFiles(File::isDirectory);
        if (dirs == null) {
            return drivers;
        }
        for (File dir : dirs) {
            Driver d = read(dir);
            if (d != null) {
                drivers.add(d);
            }
        }
        drivers.sort((a, b) -> a.label().compareToIgnoreCase(b.label()));
        return drivers;
    }

    private static Driver read(File dir) {
        try {
            JSONObject meta = new JSONObject(new String(Files.readAllBytes(new File(dir, "meta.json").toPath()), StandardCharsets.UTF_8));
            String library = meta.optString("libraryName", "");
            if (library.isEmpty() || !new File(dir, library).isFile()) {
                return null;
            }
            return new Driver(dir.getName(), meta.optString("name", dir.getName()), meta.optString("driverVersion", ""), library);
        } catch (Exception e) {
            return null;
        }
    }

    /** The selected driver, or null for the system driver. */
    static Driver selected(Context c) {
        String id = prefs(c).getString(KEY_SELECTED, "");
        if (id.isEmpty()) {
            return null;
        }
        return read(new File(root(c), id));
    }

    static void select(Context c, Driver d) {
        prefs(c).edit().putString(KEY_SELECTED, d == null ? "" : d.id).remove(KEY_TRIAL).commit();
    }

    /**
     * Called (on the game's thread) when the renderer starts. Returns the native side's four
     * lines, or null for the system driver. Also handles the crash guard.
     */
    static String forRenderer(Context c) {
        SharedPreferences p = prefs(c);
        Driver d = selected(c);
        if (d == null) {
            return null;
        }
        if (d.id.equals(p.getString(KEY_TRIAL, ""))) {
            // The last launch with this driver didn't make it through start-up.
            Log.w(TAG, "GPU driver: " + d.label() + " didn't start last time; back to the system driver");
            p.edit().putString(KEY_SELECTED, "").remove(KEY_TRIAL).putBoolean("reverted", true).commit();
            return null;
        }
        p.edit().putString(KEY_TRIAL, d.id).commit();
        File tmp = new File(c.getCacheDir(), "gpu_driver_tmp");
        tmp.mkdirs();
        return new File(root(c), d.id).getAbsolutePath() + "\n" + d.library + "\n"
            + c.getApplicationInfo().nativeLibraryDir + "\n" + tmp.getAbsolutePath() + "/" + "\n" + d.label();
    }

    /** The game has been running a while with the selected driver: it works. */
    static void confirmWorking(Context c) {
        prefs(c).edit().remove(KEY_TRIAL).apply();
    }

    /** True once after the crash guard switched back to the system driver. */
    static boolean takeRevertedNotice(Context c) {
        SharedPreferences p = prefs(c);
        boolean reverted = p.getBoolean("reverted", false);
        if (reverted) {
            p.edit().remove("reverted").apply();
        }
        return reverted;
    }

    /** Installs a driver package (.zip with meta.json). Returns it, or throws with a reason. */
    static Driver install(Context c, Uri zip) throws IOException {
        File staging = new File(root(c), ".installing");
        deleteTree(staging);
        if (!staging.mkdirs()) {
            throw new IOException("Couldn't create " + staging);
        }
        try (InputStream in = c.getContentResolver().openInputStream(zip);
             ZipInputStream zin = new ZipInputStream(in)) {
            ZipEntry entry;
            byte[] buffer = new byte[64 * 1024];
            while ((entry = zin.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                // Flatten: packages keep everything at the top level.
                String name = new File(entry.getName()).getName();
                if (name.isEmpty() || name.startsWith(".")) {
                    continue;
                }
                try (OutputStream out = new FileOutputStream(new File(staging, name))) {
                    int n;
                    while ((n = zin.read(buffer)) > 0) {
                        out.write(buffer, 0, n);
                    }
                }
            }
        }
        Driver d = read(staging);
        if (d == null) {
            deleteTree(staging);
            throw new IOException("That file isn't a driver package (needs meta.json and the driver .so inside a .zip).");
        }
        String id = (d.name + "-" + d.version).replaceAll("[^A-Za-z0-9._-]", "_");
        File target = new File(root(c), id);
        deleteTree(target);
        if (!staging.renameTo(target)) {
            deleteTree(staging);
            throw new IOException("Couldn't move the driver into place.");
        }
        return read(target);
    }

    static void remove(Context c, Driver d) {
        if (selected(c) != null && selected(c).id.equals(d.id)) {
            select(c, null);
        }
        deleteTree(new File(root(c), d.id));
    }

    private static void deleteTree(File f) {
        File[] children = f.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteTree(child);
            }
        }
        f.delete();
    }
}
