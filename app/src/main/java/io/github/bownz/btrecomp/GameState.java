package io.github.bownz.btrecomp;

/**
 * Banjo-Tooie's game state for the bottom screen, read from the game's memory by
 * android/jni/game_state.cpp. Read-only: nothing here changes the game.
 *
 * Only verified addresses are read. Values the bottom screen will show but whose memory
 * location hasn't been verified yet are {@link #UNKNOWN}; the bottom screen shows a dash for
 * them. They're being found with the temporary probe ({@link #probeMark()}).
 */
final class GameState {
    static final int UNKNOWN = -1;

    private GameState() {}

    private static native int nativeFrameCount();
    private static native int nativeProbeMark();
    private static native int nativeBottomSettings();

    /** The game's frame counter, or -1 before the game runs. */
    static int frameCount() {
        try {
            return nativeFrameCount();
        } catch (UnsatisfiedLinkError e) {
            return -1;
        }
    }

    /**
     * TEMPORARY: logs (tag BTProbe) the memory that changed a little since the last mark.
     * Returns the mark's number, or -1 if the game isn't running.
     */
    static int probeMark() {
        try {
            return nativeProbeMark();
        } catch (UnsatisfiedLinkError e) {
            return -1;
        }
    }

    /** Input > Dual Screen: bit 1 Bottom Screen Buttons, bit 2 Dim During Cutscenes. */
    static int bottomSettings() {
        try {
            return nativeBottomSettings();
        } catch (UnsatisfiedLinkError e) {
            return 3;
        }
    }

    // ---- To be read from memory once verified -------------------------------------------

    /** The worlds, in the order the game unlocks them, then the hub. */
    static final String[] WORLDS = {
        "Isle o' Hags", "Mayahem Temple", "Glitter Gulch Mine", "Witchyworld", "Jolly Roger's Lagoon",
        "Terrydactyland", "Grunty Industries", "Hailfire Peaks", "Cloud Cuckooland", "Cauldron Keep",
    };

    /** The playable characters and forms. */
    static final String[] CHARACTERS = {
        "Banjo & Kazooie", "Banjo", "Kazooie", "Mumbo", "Stony", "Detonator", "Van", "Submarine",
        "T-Rex", "Washing Machine", "Snowball", "Bee", "Dragon Kazooie",
    };

    /** Index into {@link #CHARACTERS}, or UNKNOWN. */
    static int character() { return UNKNOWN; }

    /** Index into {@link #WORLDS}, or UNKNOWN. */
    static int world() { return UNKNOWN; }

    static boolean paused() { return false; }

    static boolean cutscene() { return false; }

    /** A collectible's count in a world (or the total for world -1), or UNKNOWN. */
    static int count(int item, int world) { return UNKNOWN; }

    // Collectibles, as used by count().
    static final int JIGGIES = 0, NOTES = 1, GLOWBOS = 2, JINJOS = 3, CHEATO_PAGES = 4, HONEYCOMBS = 5;
    static final int EGGS = 6, FEATHERS = 7;
}
