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
    private static native float[] nativeMapTriangles();
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

    /** Whether the game is paused. Provisional: verified against pausing, not yet cutscenes. */
    static boolean paused() {
        try {
            return nativePaused();
        } catch (UnsatisfiedLinkError e) {
            return false;
        }
    }

    private static final float[] playerBuffer = new float[3];
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
        return playerArea >= 0 ? playerBuffer.clone() : null;
    }

    /** The current area (the game's map id, e.g. 0xAF Spiral Mountain), or UNKNOWN. */
    static synchronized int area() { return playerArea; }

    /**
     * The current area's floors and walls from above, from its collision data: 8 floats per
     * triangle (kind 0 floor / 1 wall, mean height, then x, z of each corner); or null.
     */
    static float[] mapTriangles() {
        try {
            return nativeMapTriangles();
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

    static boolean cutscene() { return false; }

    /** A collectible's count in a world (or the total for world -1), or UNKNOWN. */
    static int count(int item, int world) { return UNKNOWN; }

    // Collectibles, as used by count().
    static final int JIGGIES = 0, NOTES = 1, GLOWBOS = 2, JINJOS = 3, CHEATO_PAGES = 4, HONEYCOMBS = 5;
    static final int EGGS = 6, FEATHERS = 7;
}
