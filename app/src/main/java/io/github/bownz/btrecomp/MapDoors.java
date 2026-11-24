package io.github.bownz.btrecomp;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Doors learned by playing (0.9.41). Each time the area changes, where Banjo last stood in the
 * old area is that area's exit, and where the game puts him in the new one (its entry point) is
 * that area's way in from the old one. The two ends of one doorway merge into one door. Kept in
 * files/doors.txt across sessions, so the map fills in with every door used.
 *
 * Lines: area x y z to flags name (flags: 1 = exit seen, 2 = way in seen; name = where it
 * leads, as the game names it, spaces as underscores, or "-").
 */
final class MapDoors {
    private static final String TAG = "BTDoor";
    private static final float MERGE = 400f;   // world units: the two ends of a doorway

    static final class Door {
        final int area;
        float x, y, z;
        int to;
        int flags;
        String name;
        long used;

        Door(int area, float x, float y, float z, int to, int flags, String name) {
            this.area = area; this.x = x; this.y = y; this.z = z; this.to = to; this.flags = flags; this.name = name;
        }
    }

    private static final HashMap<Integer, ArrayList<Door>> byArea = new HashMap<>();
    private static File file;
    private static long lastUsed = 1;

    private MapDoors() {}

    static synchronized void start(Context context) {
        if (file != null) return;
        file = new File(context.getApplicationContext().getFilesDir(), "doors.txt");
        try (BufferedReader r = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] v = line.trim().split(" ");
                if (v.length < 6) continue;
                try {
                    Door d = new Door(Integer.parseInt(v[0]), Float.parseFloat(v[1]), Float.parseFloat(v[2]),
                        Float.parseFloat(v[3]), Integer.parseInt(v[4]), Integer.parseInt(v[5]),
                        v.length > 6 && !v[6].equals("-") ? v[6].replace('_', ' ') : null);
                    list(d.area).add(d);
                } catch (NumberFormatException ignored) {
                    // a damaged line: skipped
                }
            }
        } catch (IOException ignored) {
            // none yet
        }
    }

    private static ArrayList<Door> list(int area) {
        ArrayList<Door> l = byArea.get(area);
        if (l == null) {
            l = new ArrayList<>();
            byArea.put(area, l);
        }
        return l;
    }

    /** The doors known in an area (a copy). */
    static synchronized List<Door> doors(int area) {
        ArrayList<Door> l = byArea.get(area);
        return l == null ? new ArrayList<>() : new ArrayList<>(l);
    }

    /** The door used most recently in an area, or null. */
    static synchronized Door latest(int area) {
        Door best = null;
        for (Door d : doors(area)) if (best == null || d.used > best.used) best = d;
        return best;
    }

    /**
     * Notes one end of a doorway: exit = Banjo left the area here; otherwise he arrived here.
     * to = the area on the other side (or -1); name = its name, or null.
     */
    static synchronized void note(int area, float[] p, int to, boolean exit, String name) {
        if (file == null || p == null || area < 0) return;
        Door hit = null;
        for (Door d : list(area)) {
            float dx = d.x - p[0], dz = d.z - p[2];
            if (dx * dx + dz * dz < MERGE * MERGE && Math.abs(d.y - p[1]) < 600f && (d.to == to || d.to < 0 || to < 0)) {
                hit = d;
                break;
            }
        }
        if (hit == null) {
            hit = new Door(area, p[0], p[1], p[2], to, 0, null);
            list(area).add(hit);
        } else if (exit && (hit.flags & 1) == 0) {
            hit.x = p[0]; hit.y = p[1]; hit.z = p[2];   // the exit is where the doorway is; the way in is in front of it
        }
        hit.flags |= exit ? 1 : 2;
        if (to >= 0) hit.to = to;
        if (name != null && !name.isEmpty()) hit.name = name;
        hit.used = ++lastUsed;
        Log.i(TAG, String.format(java.util.Locale.US, "%s area 0x%X at (%.0f, %.0f, %.0f) %s 0x%X%s",
            exit ? "exit" : "way in", area, p[0], p[1], p[2], exit ? "->" : "<-", to, name != null ? " " + name : ""));
        save();
    }

    private static void save() {
        File tmp = new File(file.getPath() + ".tmp");
        try (FileWriter w = new FileWriter(tmp)) {
            for (ArrayList<Door> l : byArea.values()) {
                for (Door d : l) {
                    w.write(String.format(java.util.Locale.US, "%d %.1f %.1f %.1f %d %d %s\n", d.area, d.x, d.y, d.z, d.to, d.flags,
                        d.name == null ? "-" : d.name.trim().replace(' ', '_')));
                }
            }
        } catch (IOException e) {
            return;
        }
        tmp.renameTo(file);
    }
}
