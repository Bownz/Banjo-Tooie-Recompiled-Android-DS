package io.github.bownz.btrecomp;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.SystemClock;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Every level's map, drawn from the ROM (map_atlas.cpp) in both styles (Classic and Atlas) and
 * with its storeys (heights splitting floors over floors), kept in files/atlas-v2.
 *
 * Started with the app: as soon as a ROM has been imported, the maps are drawn on up to three
 * threads on the fast cores while the launcher is up. Once the game starts, one thread carries
 * on, on a slow core at the lowest priority, one map every 8 s (back to back while paused or on
 * the title screen). An area is matched to its model by the fingerprint of its collision list,
 * which is the same in the ROM and in RAM. Bump DIR when the look changes.
 */
final class MapAtlas {
    private static final String TAG = "BTMapAtlas";
    private static final String DIR = "atlas-v2";

    static final class Entry {
        final int asset;
        final float[] storeys;   // ascending

        Entry(int asset, float[] storeys) {
            this.asset = asset;
            this.storeys = storeys;
        }
    }

    private static final ConcurrentHashMap<Integer, Entry> index = new ConcurrentHashMap<>();
    private static final AtomicInteger done = new AtomicInteger();
    private static final Object indexLock = new Object();
    private static volatile int total = -1;
    private static volatile File dir;
    private static boolean started;
    private static int threads = 1;
    private static android.os.PowerManager power;

    /**
     * Whether the device is warm enough that drawing should wait: Android's thermal status
     * (light throttling or worse) or, where it's reported, the headroom forecast past 80%.
     */
    private static boolean tooWarm() {
        android.os.PowerManager pm = power;
        if (pm == null || android.os.Build.VERSION.SDK_INT < 29) return false;
        try {
            if (pm.getCurrentThermalStatus() >= android.os.PowerManager.THERMAL_STATUS_LIGHT) return true;
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                float headroom = pm.getThermalHeadroom(10);
                return !Float.isNaN(headroom) && headroom > 0.8f;
            }
        } catch (RuntimeException ignored) {
            // not reported on this device
        }
        return false;
    }

    private MapAtlas() {
    }

    /** Starts drawing (once per process). */
    static synchronized void start(Context context) {
        if (started) {
            return;
        }
        started = true;
        File d = new File(context.getApplicationContext().getFilesDir(), DIR);
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        dir = d;
        // A build holds about 400 MB while it runs (measured: three at once peaked at 1.26 GB).
        long ram = 0;
        android.app.ActivityManager am = (android.app.ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (am != null) {
            android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            ram = mi.totalMem;
        }
        // Two at most: three kept the Thor hot enough to throttle the game afterwards (0.9.38).
        int byRam = ram >= (6L << 30) ? 2 : 1;
        power = (android.os.PowerManager) context.getApplicationContext().getSystemService(Context.POWER_SERVICE);
        threads = Math.max(1, Math.min(byRam, Runtime.getRuntime().availableProcessors() - 2));
        Thread t = new Thread(MapAtlas::run, "BT atlas");
        t.setDaemon(true);
        t.start();
    }

    /** How many level models there are (-1 before the ROM is found) and how many are done. */
    static int total() {
        return total;
    }

    static int done() {
        return done.get();
    }

    static boolean drawing() {
        int t = total;
        return t > 0 && done.get() < t;
    }

    static Entry lookup(int fp) {
        return fp == 0 ? null : index.get(fp);
    }

    /** The storey layer to show for Banjo at y under something at over: "0", "1"... or null. */
    static String storeyFor(Entry e, float y, float over) {
        for (int k = 0; k < e.storeys.length; k++) {
            if (e.storeys[k] > y + 100f && e.storeys[k] < over - 50f) {
                return Integer.toString(k);
            }
        }
        return null;
    }

    /**
     * The floor below cap drawn from the ROM when no storey drawn at launch fits (saved as layer
     * "cap<n>", so it's drawn once). Null if this area isn't in the atlas. Before 0.9.40 such a
     * cut was built from the game's memory, whose see-through model isn't always the area's water:
     * under Mayahem Temple's cliffs the moat around Banjo disappeared from the map (24 Nov).
     */
    static GameState.MapImage storeyAt(int fp, float cap, boolean atlas) {
        Entry e = lookup(fp);
        if (e == null || dir == null) {
            return null;
        }
        String layer = "cap" + Math.round(cap);
        GameState.MapImage saved = load(fp, layer, atlas);
        if (saved != null) {
            return saved;
        }
        GameState.MapImage[] both = GameState.atlasBuildBoth(e.asset, cap, null);
        if (both == null) {
            return null;
        }
        save(fp, layer, both);
        both[atlas ? 0 : 1].bitmap.recycle();   // the backdrop is shared: kept
        return both[atlas ? 1 : 0];
    }

    /** A saved map: layer "all" or a storey; atlas style or classic. Null if not drawn. */
    static GameState.MapImage load(int fp, String layer, boolean atlas) {
        File d = dir;
        if (d == null || lookup(fp) == null) {
            return null;
        }
        String base = Integer.toHexString(fp) + "-" + layer;
        File img = new File(d, base + (atlas ? "-a.img" : "-c.img")), meta = new File(d, base + ".txt");
        if (!img.isFile() || !meta.isFile()) {
            return null;
        }
        try (BufferedReader r = new BufferedReader(new FileReader(meta))) {
            String[] v = r.readLine().trim().split(" ");
            Bitmap b = BitmapFactory.decodeFile(img.getPath());
            if (b == null || v.length < 3) {
                return null;
            }
            GameState.MapImage m = new GameState.MapImage(b, Float.parseFloat(v[0]), Float.parseFloat(v[1]), Float.parseFloat(v[2]));
            String line = r.readLine();
            File bd = new File(d, base + "-bd.img");
            if (line != null && bd.isFile()) {
                String[] bv = line.trim().split(" ");
                Bitmap bb = BitmapFactory.decodeFile(bd.getPath());
                if (bb != null && bv.length >= 3) {
                    m.backdrop = new GameState.MapImage(bb, Float.parseFloat(bv[0]), Float.parseFloat(bv[1]), Float.parseFloat(bv[2]));
                }
            }
            return m;
        } catch (Exception e) {
            return null;
        }
    }

    // ---- Drawing -----------------------------------------------------------------------

    private static void run() {
        // Crash guard: work in progress leaves busy-<name>.txt (the model, or "scan") until it
        // ends. Found at start, the app closed during it (a crash, or just swiped away): it's
        // counted in suspects.txt, and anything caught twice is skipped from now on (a model is
        // then built live in play; a scan stops drawing altogether).
        java.util.Map<String, Integer> suspects = new java.util.HashMap<>();
        File suspectFile = new File(dir, "suspects.txt");
        try (BufferedReader r = new BufferedReader(new FileReader(suspectFile))) {
            for (String line; (line = r.readLine()) != null; ) {
                String[] v = line.trim().split(" ");
                if (v.length == 2) suspects.put(v[0], Integer.parseInt(v[1]));
            }
        } catch (Exception ignored) {
            // none yet
        }
        File[] busy = dir.listFiles((d, name) -> name.startsWith("busy-") && name.endsWith(".txt"));
        if (busy != null && busy.length > 0) {
            for (File f : busy) {
                try (BufferedReader r = new BufferedReader(new FileReader(f))) {
                    String what = r.readLine();
                    if (what != null && !what.trim().isEmpty()) {
                        what = what.trim();
                        int n = suspects.getOrDefault(what, 0) + 1;
                        suspects.put(what, n);
                        Log.w(TAG, what + " was in progress when the app closed (" + n + ")");
                        if (n >= 2 && !what.equals("scan")) appendIndex(what + " 0");
                    }
                } catch (IOException | RuntimeException ignored) {
                    // unreadable: just remove it
                }
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
            try (FileWriter w = new FileWriter(suspectFile)) {
                for (java.util.Map.Entry<String, Integer> en : suspects.entrySet()) w.write(en.getKey() + " " + en.getValue() + "\n");
            } catch (IOException ignored) {
                // best effort
            }
        }
        if (suspects.getOrDefault("scan", 0) >= 2) {
            Log.w(TAG, "the ROM scan closed the app twice: not drawing");
            readIndex();
            return;
        }
        SystemClock.sleep(2000);   // let the game's own start-up go first
        int[] models;
        File scanBusy = new File(dir, "busy-scan.txt");
        while (true) {
            try (FileWriter w = new FileWriter(scanBusy)) {
                w.write("scan");
            } catch (IOException ignored) {
                // best effort
            }
            models = GameState.atlasModels();
            //noinspection ResultOfMethodCallIgnored
            scanBusy.delete();
            if (models != null) break;
            SystemClock.sleep(3000);   // no ROM imported yet
        }
        Set<Integer> tried = readIndex();
        ConcurrentLinkedQueue<Integer> queue = new ConcurrentLinkedQueue<>();
        int already = 0;
        for (int a : models) {
            if (tried.contains(a)) already++;
            else queue.add(a);
        }
        done.set(already);
        total = models.length;
        if (queue.isEmpty()) {
            return;
        }
        Log.i(TAG, "drawing " + queue.size() + " of " + models.length + " maps on " + threads + " threads");
        for (int k = 0; k < threads; k++) {
            final int id = k;
            Thread t = new Thread(() -> work(id, queue), "BT atlas " + k);
            t.setDaemon(true);
            t.start();
        }
    }

    private static void work(int id, ConcurrentLinkedQueue<Integer> queue) {
        GameState.fastThread();
        boolean slow = false;
        long lastEnd = 0;
        while (true) {
            if (GameState.gameRunning()) {
                if (id != 0) {
                    return;   // in play: one thread only
                }
                if (!slow) {
                    slow = true;
                    GameState.backgroundThread();   // a slow core, lowest priority
                }
                // In play nothing is drawn (0.9.38: it kept a warm device throttled, 10 fps);
                // only while the game is paused or on the title screen.
                if (!(GameState.paused() || GameState.onMenu())) {
                    SystemClock.sleep(1000);
                    continue;
                }
            }
            if (tooWarm()) {
                SystemClock.sleep(5000);   // let it cool first
                continue;
            }
            Integer asset = queue.poll();
            if (asset == null) {
                return;
            }
            File busy = new File(dir, "busy-" + id + ".txt");
            try (FileWriter w = new FileWriter(busy)) {
                w.write(Integer.toString(asset));
            } catch (IOException ignored) {
                // the guard is best effort
            }
            try {
                drawModel(asset);
            } catch (Throwable t) {
                Log.w(TAG, "map " + asset + " failed", t);
                appendIndex(asset + " 0");
            }
            //noinspection ResultOfMethodCallIgnored
            busy.delete();
            done.incrementAndGet();
            lastEnd = SystemClock.uptimeMillis();
        }
    }

    private static void drawModel(int asset) {
        float[] st = new float[3];
        GameState.MapImage[] whole = GameState.atlasBuildBoth(asset, GameState.WHOLE, st);
        int fp = GameState.atlasFingerprint(asset);
        if (whole == null || fp == 0 || !save(fp, "all", whole)) {
            appendIndex(asset + " 0");
            return;
        }
        recycle(whole);
        List<Float> storeys = new ArrayList<>();
        for (float s : st) if (!Float.isNaN(s)) storeys.add(s);
        java.util.Collections.sort(storeys);
        int kept = 0;
        for (float s : storeys) {
            GameState.MapImage[] m = GameState.atlasBuildBoth(asset, s, null);
            if (m == null || !save(fp, Integer.toString(kept), m)) {
                break;   // layer numbers go by order: stop at the first that couldn't be drawn
            }
            recycle(m);
            kept++;
        }
        float[] kept2 = new float[kept];
        StringBuilder line = new StringBuilder().append(asset).append(' ').append(Integer.toHexString(fp));
        for (int k = 0; k < kept; k++) {
            kept2[k] = storeys.get(k);
            line.append(' ').append(kept2[k]);
        }
        appendIndex(line.toString());
        index.put(fp, new Entry(asset, kept2));
    }

    private static void recycle(GameState.MapImage[] m) {
        for (GameState.MapImage i : m) i.bitmap.recycle();
        if (m[0].backdrop != null) m[0].backdrop.bitmap.recycle();
    }

    private static Bitmap.CompressFormat format() {
        // Lossy WebP: a fifth of a PNG; the whole atlas is a few hundred files.
        return android.os.Build.VERSION.SDK_INT >= 30 ? Bitmap.CompressFormat.WEBP_LOSSY : Bitmap.CompressFormat.WEBP;
    }

    /** Writes {classic, atlas} (one transform and backdrop) as <fp>-<layer>-c/-a/-bd.img + .txt. */
    private static boolean save(int fp, String layer, GameState.MapImage[] both) {
        String base = Integer.toHexString(fp) + "-" + layer;
        if (!write(both[0].bitmap, new File(dir, base + "-c.img")) || !write(both[1].bitmap, new File(dir, base + "-a.img"))) {
            return false;
        }
        GameState.MapImage bd = both[0].backdrop;
        boolean bdSaved = bd != null && write(bd.bitmap, new File(dir, base + "-bd.img"));
        try (FileWriter w = new FileWriter(new File(dir, base + ".txt"))) {
            w.write(both[0].minX + " " + both[0].minZ + " " + both[0].scale + "\n");
            if (bdSaved) w.write(bd.minX + " " + bd.minZ + " " + bd.scale + "\n");
        } catch (IOException e) {
            return false;
        }
        return true;
    }

    private static boolean write(Bitmap b, File f) {
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            if (!b.compress(format(), 88, out)) return false;
        } catch (IOException e) {
            return false;
        }
        return tmp.renameTo(f);
    }

    private static void appendIndex(String line) {
        synchronized (indexLock) {
            try (FileWriter w = new FileWriter(new File(dir, "index.txt"), true)) {
                w.write(line + "\n");
            } catch (IOException ignored) {
                // drawn again next time
            }
        }
    }

    /** Reads index.txt (lines: asset fp storeys...); returns every asset tried. */
    private static Set<Integer> readIndex() {
        Set<Integer> tried = new HashSet<>();
        File f = new File(dir, "index.txt");
        if (!f.isFile()) {
            return tried;
        }
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            for (String line; (line = r.readLine()) != null; ) {
                String[] v = line.trim().split(" ");
                if (v.length < 2) continue;
                try {
                    int asset = Integer.parseInt(v[0]);
                    int fp = (int) Long.parseLong(v[1], 16);
                    tried.add(asset);
                    if (fp == 0 || !new File(dir, Integer.toHexString(fp) + "-all.txt").isFile()) continue;
                    float[] st = new float[v.length - 2];
                    for (int k = 0; k < st.length; k++) st[k] = Float.parseFloat(v[k + 2]);
                    Arrays.sort(st);
                    index.put(fp, new Entry(asset, st));
                } catch (NumberFormatException ignored) {
                    // a torn line
                }
            }
        } catch (IOException ignored) {
            // start again
        }
        return tried;
    }
}
