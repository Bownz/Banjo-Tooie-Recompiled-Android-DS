package io.github.bownz.btrecomp;

/**
 * Banjo-Tooie's game state for the bottom screen, read from the game's memory by
 * android/jni/game_state.cpp. Read-only: nothing here changes the game.
 *
 * Only addresses found in the game's memory are read (position, area, pause so far). Values the
 * bottom screen will show but whose memory location hasn't been found yet are {@link #UNKNOWN}; the bottom screen shows a dash for
 * them. They're being found with the temporary probe ({@link #probeMark()}).
 */
final class GameState {
    static final int UNKNOWN = -1;

    private GameState() {}

    private static native int nativeFrameCount();
    private static native int nativePresentedFrames();
    private static native int nativeProbeMark(String snapshotDir);
    private static native int nativeBottomSettings();
    private static native int nativePlayer(float[] out);
    private static native boolean nativePaused();
    private static native boolean nativeCutscene();
    private static native boolean nativeOnMenu();
    private static native boolean nativeEntryPoint(float[] out);
    private static native int[] nativeBuildMap(int maxSize, float capY, int key, float[] info);
    private static native int[] nativeBuildDetail(int key, float x0, float z0, float x1, float z1, int maxSize, float[] info);
    private static native byte[] nativeWaterMask(int key);
    private static native int[] nativeBackdrop(int key, float[] info);
    private static native boolean nativeMapWantsSeed(int key, float x, float y, float z);
    private static native int nativeAreaObjects(float[] out);
    private static native boolean nativeMapShowsLevelAbove(int key, float x, float y, float z);
    private static native boolean nativeFloorAbove(float x, float y, float z);
    private static native String nativeAreaName();
    private static native int nativeN64Buttons();
    private static native int nativeMapGeometryId();

    /** The game's frame counter, or -1 before the game runs. */
    static int frameCount() {
        try {
            return nativeFrameCount();
        } catch (UnsatisfiedLinkError e) {
            return -1;
        }
    }

    /** Frames shown on screen so far (RT64's count, including in-between frames), or -1. */
    static int presentedFrames() {
        try {
            return nativePresentedFrames();
        } catch (UnsatisfiedLinkError e) {
            return -1;
        }
    }

    /**
     * TEMPORARY: logs (tag BTProbe) the memory that changed a little since the last mark, and
     * saves a snapshot of the game's memory to Android/data/<app>/files/probe (pull-probe.bat).
     * Returns the mark's number, or -1 if the game isn't running.
     */
    static int probeMark(android.content.Context context) {
        java.io.File base = context.getExternalFilesDir(null);
        String dir = base != null ? new java.io.File(base, "probe").getAbsolutePath() : null;
        try {
            return nativeProbeMark(dir);
        } catch (UnsatisfiedLinkError e) {
            return -1;
        }
    }

    /** Bit 1 Bottom Screen Buttons, bit 2 Dim During Cutscenes, bit 4 Video > Show FPS. */
    static int bottomSettings() {
        try {
            return nativeBottomSettings();
        } catch (UnsatisfiedLinkError e) {
            return 3;
        }
    }

    // ---- To be read from memory once verified -------------------------------------------

    /** The hub and the worlds, for the caption above the bottom screen's puzzle. */
    static final String[] WORLDS = {
        "Isle o' Hags", "Mayahem Temple", "Glitter Gulch Mine", "Witchyworld", "Jolly Roger's Lagoon",
        "Terrydactyland", "Grunty Industries", "Hailfire Peaks", "Cloud Cuckooland", "Cauldron Keep",
    };

    /** Index into {@link #WORLDS}, or UNKNOWN. */
    static int world() { return UNKNOWN; }

    /** Whether the game is paused (instant; stays off in dialogue and cutscenes). */
    static boolean paused() {
        try {
            return nativePaused();
        } catch (UnsatisfiedLinkError e) {
            return false;
        }
    }

    private static final float[] playerBuffer = new float[4];
    private static int playerArea = UNKNOWN;

    /** Reads Banjo's position and the area together; call before position() and area(). */
    static synchronized void refreshPlayer() {
        try {
            playerArea = nativePlayer(playerBuffer);
        } catch (UnsatisfiedLinkError e) {
            playerArea = UNKNOWN;
        }
    }

    /** Banjo's position {x, y, z} in the current area (as of refreshPlayer()), or null. */
    static synchronized float[] position() {
        return playerArea >= 0 ? new float[] { playerBuffer[0], playerBuffer[1], playerBuffer[2] } : null;
    }

    /** Where Banjo entered the current area {x, y, z}, or null. */
    static float[] entryPoint() {
        float[] p = new float[3];
        try {
            return nativeEntryPoint(p) ? p : null;
        } catch (UnsatisfiedLinkError e) {
            return null;
        }
    }

    /** Which way Banjo faces, in degrees (0 = +z, 90 = +x), or NaN. */
    static synchronized float facing() {
        return playerArea >= 0 ? playerBuffer[3] : Float.NaN;
    }

    /** The current area (the game's map id, e.g. 0xAF Spiral Mountain), or UNKNOWN. */
    static synchronized int area() { return playerArea; }

    /** The current area's map picture (map_render.cpp) and where it sits in the world. */
    static final class MapImage {
        final android.graphics.Bitmap bitmap;
        final float minX, minZ, scale;   // world x, z at the picture's top-left; pixels per world unit
        final int key;                   // the build it came from (for detail tiles); -1 when loaded from a file
        final android.graphics.Bitmap water;   // where the water is (alpha), or null
        /** The soft glow drawn under the map (the level's surroundings, blurred), or null. */
        volatile MapImage backdrop;

        MapImage(android.graphics.Bitmap bitmap, float minX, float minZ, float scale) {
            this(bitmap, minX, minZ, scale, -1, null);
        }

        MapImage(android.graphics.Bitmap bitmap, float minX, float minZ, float scale, int key, android.graphics.Bitmap water) {
            this.bitmap = bitmap;
            this.minX = minX;
            this.minZ = minZ;
            this.scale = scale;
            this.key = key;
            this.water = water;
        }

        /** World width and height covered. */
        float worldW() { return bitmap.getWidth() / scale; }
        float worldH() { return bitmap.getHeight() / scale; }
    }

    /** No floor layer: the whole area (for buildMap). */
    static final float WHOLE = 1e9f;

    private static final java.util.concurrent.atomic.AtomicInteger buildKeys = new java.util.concurrent.atomic.AtomicInteger(1);

    /**
     * Draws the current area's map, at most maxSize pixels across; slow (call off the UI thread).
     * capY: leave out everything entirely above it (the storey Banjo is on), or WHOLE.
     */
    static MapImage buildMap(int maxSize, float capY) {
        float[] info = new float[5];
        int key = buildKeys.getAndIncrement();
        int[] pixels;
        byte[] waterBytes;
        try {
            pixels = nativeBuildMap(maxSize, capY, key, info);
            waterBytes = pixels == null ? null : nativeWaterMask(key);
        } catch (UnsatisfiedLinkError e) {
            return null;
        }
        int w = (int) info[0], h = (int) info[1];
        if (pixels == null || w <= 0 || h <= 0 || pixels.length != w * h) {
            return null;
        }
        android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(pixels, w, h, android.graphics.Bitmap.Config.ARGB_8888);
        MapImage map = new MapImage(bitmap, info[2], info[3], info[4], key, waterMask(waterBytes, w, h));
        float[] bdInfo = new float[5];
        int[] bd = nativeBackdrop(key, bdInfo);
        int bw = (int) bdInfo[0], bh = (int) bdInfo[1];
        if (bd != null && bw > 0 && bh > 0 && bd.length == bw * bh) {
            map.backdrop = new MapImage(android.graphics.Bitmap.createBitmap(bd, bw, bh, android.graphics.Bitmap.Config.ARGB_8888),
                bdInfo[2], bdInfo[3], bdInfo[4]);
        }
        return map;
    }

    /** An alpha-only bitmap from one byte per pixel, or null. */
    private static android.graphics.Bitmap waterMask(byte[] bytes, int w, int h) {
        if (bytes == null || bytes.length != w * h) {
            return null;
        }
        boolean any = false;
        for (byte b : bytes) {
            if (b != 0) { any = true; break; }
        }
        if (!any) {
            return null;
        }
        try {
            android.graphics.Bitmap m = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ALPHA_8);
            if (m.getRowBytes() == w) {
                m.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(bytes));
            } else {
                java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(m.getRowBytes() * h);
                for (int y = 0; y < h; y++) {
                    buf.position(y * m.getRowBytes());
                    buf.put(bytes, y * w, w);
                }
                buf.rewind();
                m.copyPixelsFromBuffer(buf);
            }
            return m;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * A sharper close-up of part of a map built this session (key), covering world (x0, z0) to
     * (x1, z1) at up to maxSize pixels across; null if that build isn't the latest. Slow.
     */
    static MapImage buildDetail(int key, float x0, float z0, float x1, float z1, int maxSize) {
        if (key < 0) {
            return null;
        }
        float[] info = new float[5];
        int[] pixels;
        try {
            pixels = nativeBuildDetail(key, x0, z0, x1, z1, maxSize, info);
        } catch (UnsatisfiedLinkError e) {
            return null;
        }
        int w = (int) info[0], h = (int) info[1];
        if (pixels == null || w <= 0 || h <= 0 || pixels.length != w * h) {
            return null;
        }
        android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(pixels, w, h, android.graphics.Bitmap.Config.ARGB_8888);
        return new MapImage(bitmap, info[2], info[3], info[4], key, null);
    }

    /** Z on the N64 controller. */
    static final int BUTTON_Z = 0x2000;

    /** Player 1's N64 buttons as the game last read them (every controller and touch combined). */
    static int n64Buttons() {
        try {
            return nativeN64Buttons();
        } catch (UnsatisfiedLinkError e) {
            return 0;
        }
    }

    /** Whether walkable floor lies directly above (x, z), more than 200 units over y. */
    static boolean floorAbove(float x, float y, float z) {
        try {
            return nativeFloorAbove(x, y, z);
        } catch (UnsatisfiedLinkError e) {
            return false;
        }
    }

    /** The area's name as the game writes it on entering ("ISLE O' HAGS ( JINJO VILLAGE )"), or null. */
    static String areaName() {
        try {
            return nativeAreaName();
        } catch (UnsatisfiedLinkError e) {
            return null;
        }
    }

    /** Changes when the area's collision data does (0 when there's none). */
    static int mapGeometryId() {
        try {
            return nativeMapGeometryId();
        } catch (UnsatisfiedLinkError e) {
            return 0;
        }
    }

    /**
     * Whether Banjo stands on ground the map built with this key left out (ground well above
     * the area's main level). The point is remembered for the area: build the map again and
     * that ground joins the play area.
     */
    static boolean mapWantsSeed(int key, float x, float y, float z) {
        try {
            return nativeMapWantsSeed(key, x, y, z);
        } catch (UnsatisfiedLinkError e) {
            return false;
        }
    }

    // Kinds of things on the map (areaObjects), as the native side numbers them.
    static final int OBJ_JIGGY = 1, OBJ_NOTES = 2, OBJ_CLEF = 3, OBJ_JINJO = 4, OBJ_GLOWBO = 5,
        OBJ_HONEYCOMB = 6, OBJ_EGGS = 7, OBJ_FEATHERS = 8, OBJ_MUMBO_PAD = 9, OBJ_JAMJARS = 10;

    private static final float[] objectBuffer = new float[4 * 512];

    /**
     * The current area's collectibles and landmarks from the game's list of what's placed in
     * it: x, y, z, kind (OBJ_*) per thing. Things already collected are still listed.
     */
    static float[] areaObjects() {
        try {
            synchronized (objectBuffer) {
                int n = nativeAreaObjects(objectBuffer);
                return java.util.Arrays.copyOf(objectBuffer, Math.max(0, n) * 4);
            }
        } catch (UnsatisfiedLinkError e) {
            return new float[0];
        }
    }

    /** Whether the game is on its title screen or file select (no map to show). */
    static boolean onMenu() {
        try {
            return nativeOnMenu();
        } catch (UnsatisfiedLinkError e) {
            return false;
        }
    }

    /** Whether the map built with this key shows, at Banjo's spot, a level well above him. */
    static boolean mapShowsLevelAbove(int key, float x, float y, float z) {
        try {
            return nativeMapShowsLevelAbove(key, x, y, z);
        } catch (UnsatisfiedLinkError e) {
            return false;
        }
    }

    /** Whether a cutscene is playing (the player has no control; see game_state.cpp). */
    static boolean cutscene() {
        try {
            return nativeCutscene();
        } catch (UnsatisfiedLinkError e) {
            return false;
        }
    }

    /** A collectible's count in a world (or the total for world -1), or UNKNOWN. */
    static int count(int item, int world) { return UNKNOWN; }

    // Collectibles, as used by count().
    static final int JIGGIES = 0, NOTES = 1, GLOWBOS = 2, JINJOS = 3, CHEATO_PAGES = 4, HONEYCOMBS = 5;
    static final int EGGS = 6, FEATHERS = 7;
}
