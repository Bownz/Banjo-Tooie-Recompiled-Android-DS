package io.github.bownz.btrecomp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

/**
 * The bottom screen for Banjo-Tooie on a second display (e.g. the AYN Thor's lower screen).
 *
 * The screen is a map of the area you're in, drawn from above:
 * <ul>
 *   <li>A line on top: the world, its Jiggies and notes.</li>
 *   <li>The map: the area's floors and walls (from its collision data in the game's memory),
 *       where you've walked, and where you are.</li>
 *   <li>Four buttons for the moves done with Z and a C button (Bottom Screen Buttons).</li>
 * </ul>
 * The progress book (like the game's own pause screen): it opens whenever the game is paused
 * and closes when it's unpaused, and pages with ◀ ▶ or a swipe through every world in a table, then
 * each world on its own. The screen dims during cutscenes.
 *
 * The game state comes from {@link GameState}, which only reports values whose memory
 * addresses have been verified in-game; until then a value shows as a dash. A temporary Mark
 * button logs what changed in memory, to find them.
 *
 * All drawing is vector shapes made here; no game art is used.
 */
public final class BottomScreenView extends View {

    // ---- Colours (honey, wood and the night sky of the Isle) ----
    private static final int BG_TOP = Color.rgb(30, 22, 44);
    private static final int BG_BOTTOM = Color.rgb(14, 10, 20);
    private static final int HONEY = Color.rgb(255, 186, 40);
    private static final int HONEY_DARK = Color.rgb(196, 122, 18);
    private static final int SLOT = Color.argb(70, 255, 186, 40);
    private static final int WOOD = Color.argb(225, 74, 46, 22);
    private static final int WOOD_EDGE = Color.argb(160, 186, 140, 76);
    private static final int CREAM = Color.rgb(255, 244, 214);
    private static final int TAN = Color.rgb(232, 213, 168);

    /** Jiggies in each world, and in the whole game. */
    private static final int JIGGIES_PER_WORLD = 10;
    private static final int JIGGIES_TOTAL = 90;

    /** A move done with Z held and a C button. */
    private static final class Move {
        final String name, keys;
        final int buttons;        // pressed together after Z
        // Toggles (Talon Trot, Wonderwing) stay on until tapped again, holding Z the whole time
        // like the moves need: Talon Trot taps its C button once, Wonderwing holds it too.
        final boolean toggle;
        final boolean holdC;
        final RectF rect = new RectF();
        boolean down, lit;

        Move(String name, String keys, int buttons, boolean toggle, boolean holdC) {
            this.name = name; this.keys = keys; this.buttons = buttons; this.toggle = toggle; this.holdC = holdC;
        }
    }

    private static final int Z = TouchInput.N64_Z;
    private final Move[] moves = {
        new Move("Fire Egg", "Z + C▲", Z | TouchInput.N64_C_UP, false, false),
        new Move("Lay Egg", "Z + C▼", Z | TouchInput.N64_C_DOWN, false, false),
        new Move("Talon Trot", "on / off · holds Z", Z | TouchInput.N64_C_LEFT, true, false),
        new Move("Wonderwing", "on / off · holds Z", Z | TouchInput.N64_C_RIGHT, true, true),
    };

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean active;

    // State, refreshed by poll().
    private int frame = -1;
    private int world = GameState.UNKNOWN;
    private boolean paused, cutscene;
    private boolean showButtons = true, dimCutscenes = true;
    private float dim = 0f;   // 0 .. 1, eased toward the target
    private long dimAt;

    private final RectF boardRect = new RectF();

    // The progress book: open, the page (-1 every world, else one world), and the slide
    // between pages.
    private boolean bookOpen, bookByPause;
    private int bookPage = -1;
    private int slideFrom = Integer.MIN_VALUE, slideDir;
    private long slideAt;
    private static final long SLIDE_MS = 320;
    private final RectF prevRect = new RectF(), nextRect = new RectF(), closeRect = new RectF();
    private float swipeX = Float.NaN;
    private final RectF tmp = new RectF();

    // TEMPORARY: the probe's Mark button.
    private final RectF markRect = new RectF();
    private int marks;
    private long markFlashAt;

    private Move tapping;     // a tap move in progress
    private int heldButtons;  // buttons held by the toggle that's on

    private float u = 1f;     // layout unit: 1% of the shorter side

    public BottomScreenView(Context context) {
        super(context);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        text.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        text.setTextAlign(Paint.Align.CENTER);
        setContentDescription("Banjo-Tooie bottom screen");
    }

    /** Starts or stops reading the game state (while the screen is shown). */
    void setActive(boolean active) {
        if (this.active == active) {
            return;
        }
        this.active = active;
        handler.removeCallbacks(pollTask);
        if (active) {
            handler.post(pollTask);
        } else {
            cancel();
        }
    }

    /** Lets go of everything (the app is pausing). */
    void cancel() {
        handler.removeCallbacks(releaseTap);
        handler.removeCallbacks(pressTap);
        tapping = null;
        heldButtons = 0;
        for (Move m : moves) {
            m.down = false;
            m.lit = false;   // toggles go off: nothing stays held while the app is away
        }
        TouchInput.setN64Buttons(0);
        TouchInput.setHeldN64Buttons(0);
        invalidate();
    }

    // ------------------------------------------------------------------------------
    // State
    // ------------------------------------------------------------------------------

    private final Runnable pollTask = new Runnable() {
        @Override
        public void run() {
            poll();
            handler.postDelayed(this, 33);   // ~30 Hz, so the arrow moves smoothly
        }
    };

    private void poll() {
        frame = GameState.frameCount();
        refreshAreaName();
        world = areaWorld >= 0 ? areaWorld : GameState.world();
        boolean wasPaused = paused;
        paused = GameState.paused();
        if (paused && !wasPaused && !bookOpen) {
            openBook(true);              // pausing opens the book on where you are
        } else if (!paused && wasPaused && bookByPause) {
            bookOpen = false;            // and unpausing closes it again
        }
        cutscene = GameState.cutscene();
        GameState.refreshPlayer();
        refreshMap();
        recordEntry();
        updateHeading();
        int settings = GameState.bottomSettings();
        showButtons = (settings & 1) != 0;
        dimCutscenes = (settings & 2) != 0;
        invalidate();
    }

    // ------------------------------------------------------------------------------
    // Drawing
    // ------------------------------------------------------------------------------

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        u = Math.min(w, h) / 100f;

        fill.setShader(new LinearGradient(0, 0, 0, h, BG_TOP, BG_BOTTOM, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, fill);
        fill.setShader(null);
        drawStars(c, w, h);

        float pad = 4 * u;
        float markH = 7 * u;
        if (bookOpen) {
            drawBook(c, new RectF(pad, pad, w - pad, h - pad - markH - 2 * u));
            drawMark(c, new RectF(pad, h - pad - markH, w - pad, h - pad));
            drawDim(c, w, h);
            return;
        }
        float movesH = showButtons ? 15 * u : 0f;
        float bottom = h - pad - markH - 2 * u - (showButtons ? movesH + 3 * u : 0f);

        // One line on top: where you are, this world's Jiggies and notes.
        float stripH = 6 * u;
        drawStrip(c, new RectF(pad, pad, w - pad, pad + stripH));

        // The map of the current area.
        RectF map = new RectF(pad, pad + stripH + 2.5f * u, w - pad, bottom);
        drawMap(c, map);

        if (showButtons) {
            drawMoves(c, new RectF(pad, bottom + 3 * u, w - pad, bottom + 3 * u + movesH));
        }
        drawMark(c, new RectF(pad, h - pad - markH, w - pad, h - pad));
        drawDim(c, w, h);
    }

    private void drawStrip(Canvas c, RectF r) {
        text.setTextSize(3.4f * u);
        text.setTextAlign(Paint.Align.LEFT);
        text.setColor(TAN);
        String where = world >= 0 ? GameState.WORLDS[world] : (areaLabel != null ? areaLabel : "Banjo-Tooie");
        c.drawText(where, r.left, r.centerY() - (text.descent() + text.ascent()) / 2f, text);
        text.setTextAlign(Paint.Align.RIGHT);
        text.setColor(HONEY);
        int jiggies = GameState.count(GameState.JIGGIES, world);
        int notes = GameState.count(GameState.NOTES, world);
        String right = (jiggies >= 0 ? jiggies + "/" + JIGGIES_PER_WORLD : "–") + " Jiggies  ·  " + value(notes) + " ♪";
        c.drawText(right, r.right, r.centerY() - (text.descent() + text.ascent()) / 2f, text);
        text.setTextAlign(Paint.Align.CENTER);
    }

    // ------------------------------------------------------------------------------
    // The map
    // ------------------------------------------------------------------------------

    /**
     * The current area from above, north up, drawn from the level the game has loaded
     * (android/jni/map_render.cpp): its own textures, walkable ground lit by height with an
     * outline, everything else dim. Built once per area in the background; each frame only
     * places it and draws Banjo's arrow. Close-up following Banjo by default; a tap on the map
     * switches to the whole area and back.
     */
    private void drawMap(Canvas c, RectF r) {
        mapRect.set(r);
        // Slate grey behind the map (what's past the play area's edge), lighter at the top.
        if (mapBackground == null || mapBackgroundTop != r.top || mapBackgroundBottom != r.bottom) {
            mapBackground = new android.graphics.LinearGradient(0, r.top, 0, r.bottom,
                Color.rgb(104, 104, 108), Color.rgb(68, 68, 74), android.graphics.Shader.TileMode.CLAMP);
            mapBackgroundTop = r.top;
            mapBackgroundBottom = r.bottom;
        }
        mapBackgroundPaint.setShader(mapBackground);
        c.drawRoundRect(r, 3 * u, 3 * u, mapBackgroundPaint);

        float[] here = GameState.position();
        GameState.MapImage m = mapImage;
        if (m == null) {
            text.setColor(CREAM);
            text.setTextSize(3.6f * u);
            drawCentered(c, here == null ? "Waiting for the game" : "Drawing the map…", r.centerX(), r.centerY());
        } else {
            // View: world units -> screen. Close-up: about 4500 units across the panel's short side.
            float worldW = m.bitmap.getWidth() / m.scale, worldH = m.bitmap.getHeight() / m.scale;
            float viewScale, cx, cz;
            if (!mapWhole && here != null) {
                viewScale = Math.min(r.width(), r.height()) / followSpan;
                cx = here[0];
                cz = here[2];
            } else {
                viewScale = Math.min(r.width() / worldW, r.height() / worldH) * 0.96f;
                cx = m.minX + worldW / 2;
                cz = m.minZ + worldH / 2;
            }
            int saved = c.save();
            mapClip.reset();
            mapClip.addRoundRect(r, 3 * u, 3 * u, Path.Direction.CW);
            c.clipPath(mapClip);
            c.translate(r.centerX(), r.centerY());
            float k = viewScale / m.scale;
            c.scale(k, k);
            c.translate(-(cx - m.minX) * m.scale, -(cz - m.minZ) * m.scale);
            c.drawBitmap(m.bitmap, 0, 0, mapPaint);
            c.restoreToCount(saved);

            // Where you came in (each entrance used in this area this session; the latest brighter).
            java.util.List<float[]> entries = entrances.get(GameState.area());
            if (entries != null) {
                int saved2 = c.save();
                c.clipPath(mapClip);
                for (int i = 0; i < entries.size(); i++) {
                    float[] e = entries.get(i);
                    drawEntrance(c, r.centerX() + (e[0] - cx) * viewScale, r.centerY() + (e[2] - cz) * viewScale,
                        (!mapWhole ? 2.2f : 1.7f) * u, i == entries.size() - 1);
                }
                c.restoreToCount(saved2);
            }

            if (here != null) {
                float px = r.centerX() + (here[0] - cx) * viewScale;
                float pz = r.centerY() + (here[2] - cz) * viewScale;
                drawArrow(c, px, pz, arrowDegrees, (!mapWhole ? 2.6f : 2.0f) * u);
            }
            text.setTextSize(2.4f * u);
            text.setColor(Color.argb(170, 232, 213, 168));
            text.setTextAlign(Paint.Align.RIGHT);
            c.drawText("Tap or pinch to zoom", r.right - 2 * u, r.bottom - 1.6f * u, text);
            // The area's name, top left (once the game has shown it).
            if (areaLabel != null) {
                text.setTextAlign(Paint.Align.LEFT);
                text.setTextSize(3.0f * u);
                float tw = text.measureText(areaLabel);
                fill.setColor(Color.argb(170, 18, 13, 26));
                c.drawRoundRect(r.left + 1.5f * u, r.top + 1.5f * u, r.left + 4.5f * u + tw, r.top + 6.5f * u, 2 * u, 2 * u, fill);
                text.setColor(CREAM);
                c.drawText(areaLabel, r.left + 3 * u, r.top + 5.1f * u, text);
            }
            if (mapCap < GameState.WHOLE) {
                text.setTextAlign(Paint.Align.LEFT);
                text.setTextSize(2.4f * u);
                text.setColor(Color.argb(200, 232, 213, 168));
                c.drawText("Lower floor", r.left + 2 * u, r.bottom - 1.6f * u, text);
            }
            text.setTextAlign(Paint.Align.CENTER);
        }
        stroke.setColor(Color.argb(200, 206, 162, 70));
        stroke.setStrokeWidth(0.45f * u);
        c.drawRoundRect(r, 3 * u, 3 * u, stroke);
    }

    // The arrow's direction. While Banjo moves it's the way he's moving; standing still it's the
    // game's facing value, flipped when that was last seen pointing backwards (it does during
    // Talon Trot, where Kazooie carries Banjo the other way round).
    private float arrowDegrees = Float.NaN;
    private float lastX = Float.NaN, lastZ = Float.NaN;
    private boolean facingFlipped;

    private void updateHeading() {
        float[] p = GameState.position();
        float facing = GameState.facing();
        if (p == null) {
            lastX = Float.NaN;
            return;
        }
        if (!Float.isNaN(lastX)) {
            float dx = p[0] - lastX, dz = p[2] - lastZ;
            if (dx * dx + dz * dz > 6f * 6f && dx * dx + dz * dz < 400f * 400f) {   // moving (not a warp)
                float moving = (float) Math.toDegrees(Math.atan2(dx, dz));
                arrowDegrees = moving;
                if (!Float.isNaN(facing)) {
                    float diff = Math.abs(((facing - moving) % 360f + 540f) % 360f - 180f);   // 0 = opposite
                    if (diff < 50f) {
                        facingFlipped = true;
                    } else if (diff > 130f) {
                        facingFlipped = false;
                    }
                }
                lastX = p[0];
                lastZ = p[2];
                return;
            }
        }
        lastX = p[0];
        lastZ = p[2];
        if (!Float.isNaN(facing)) {
            arrowDegrees = facingFlipped ? facing + 180f : facing;
        }
    }

    /** An entrance: a small door (rounded top) in a ring. */
    private void drawEntrance(Canvas c, float x, float z, float size, boolean latest) {
        fill.setColor(latest ? Color.argb(235, 40, 26, 18) : Color.argb(170, 40, 26, 18));
        c.drawCircle(x, z, size, fill);
        stroke.setColor(latest ? HONEY : Color.argb(170, 255, 186, 40));
        stroke.setStrokeWidth(0.35f * u);
        c.drawCircle(x, z, size, stroke);
        float dw = size * 0.55f, dh = size * 1.05f;
        RectF door = new RectF(x - dw / 2, z - dh / 2, x + dw / 2, z + dh / 2);
        fill.setColor(latest ? CREAM : Color.argb(170, 255, 244, 214));
        c.drawRoundRect(door, dw / 2, dw / 2, fill);
        c.drawRect(door.left, door.centerY(), door.right, door.bottom, fill);
    }

    // Entrances used this session, per area (where Banjo appeared on entering it).
    private final java.util.HashMap<Integer, java.util.ArrayList<float[]>> entrances = new java.util.HashMap<>();
    private int entryArea = GameState.UNKNOWN;
    private long entryAreaSince;

    /** Once an area has been current for a second (so the entry point is the new area's), notes it. */
    private void recordEntry() {
        int area = GameState.area();
        long now = SystemClock.uptimeMillis();
        if (area != entryArea) {
            entryArea = area;
            entryAreaSince = now;
            return;
        }
        if (area < 0 || now - entryAreaSince < 1000) {
            return;
        }
        float[] e = GameState.entryPoint();
        if (e == null) {
            return;
        }
        java.util.ArrayList<float[]> list = entrances.get(area);
        if (list == null) {
            list = new java.util.ArrayList<>();
            entrances.put(area, list);
        }
        for (int i = 0; i < list.size(); i++) {
            float[] o = list.get(i);
            float dx = o[0] - e[0], dz = o[2] - e[2];
            if (dx * dx + dz * dz < 300f * 300f) {
                if (i != list.size() - 1) {   // used again: make it the latest
                    list.remove(i);
                    list.add(o);
                }
                return;
            }
        }
        list.add(e);
    }

    /** Banjo: a red arrow pointing the way he faces (0 degrees = +z = down the map), or a dot. */
    private void drawArrow(Canvas c, float x, float z, float degrees, float size) {
        if (Float.isNaN(degrees)) {
            fill.setColor(MAP_DOT);
            c.drawCircle(x, z, size * 0.7f, fill);
            stroke.setColor(CREAM);
            stroke.setStrokeWidth(0.35f * u);
            c.drawCircle(x, z, size * 0.7f, stroke);
            return;
        }
        double a = Math.toRadians(degrees);
        float dx = (float) Math.sin(a), dz = (float) Math.cos(a);   // screen x right, y down = +z
        float lx = -dz, lz = dx;
        path.reset();
        path.moveTo(x + dx * size * 1.6f, z + dz * size * 1.6f);
        path.lineTo(x - dx * size * 0.9f + lx * size * 0.9f, z - dz * size * 0.9f + lz * size * 0.9f);
        path.lineTo(x - dx * size * 0.4f, z - dz * size * 0.4f);
        path.lineTo(x - dx * size * 0.9f - lx * size * 0.9f, z - dz * size * 0.9f - lz * size * 0.9f);
        path.close();
        fill.setColor(MAP_DOT);
        c.drawPath(path, fill);
        stroke.setColor(CREAM);
        stroke.setStrokeWidth(0.35f * u);
        c.drawPath(path, stroke);
    }

    // The map picture for the current area, built in the background.
    private final RectF mapRect = new RectF();
    private final Paint mapBackgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private android.graphics.Shader mapBackground;
    private float mapBackgroundTop = Float.NaN, mapBackgroundBottom = Float.NaN;
    private final Path mapClip = new Path();
    private final Paint mapPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private boolean mapWhole = false;
    private float followSpan = 4500f;   // world units across the panel's short side (close-up)
    private boolean mapTouching, mapPinched;
    private long mapTouchAt;
    private final android.view.ScaleGestureDetector pinch = new android.view.ScaleGestureDetector(getContext(),
        new android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(android.view.ScaleGestureDetector d) {
                if (mapWhole) {
                    mapWhole = false;
                    followSpan = 12000f;
                }
                followSpan = Math.max(1200f, Math.min(30000f, followSpan / d.getScaleFactor()));
                mapPinched = true;
                invalidate();
                return true;
            }
        });

    /** Tap: close-up -> wider -> whole area -> close-up. */
    private void cycleZoom() {
        if (mapWhole) {
            mapWhole = false;
            followSpan = 3500f;
        } else if (followSpan < 5000f) {
            followSpan = 7500f;
        } else {
            mapWhole = true;
        }
        Haptics.tap(this);
        invalidate();
    }
    private volatile GameState.MapImage mapImage;
    private int mapGeometry = 0;
    private long mapGeometryAt;
    private boolean mapRebuilt;
    private boolean mapBuilding;
    private final java.util.concurrent.ExecutorService mapWorker = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "BT map");
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    // The floor layer shown: GameState.WHOLE, or a height cap when Banjo is under another floor.
    private float mapCap = GameState.WHOLE;
    private float wantedCap = GameState.WHOLE;
    private long layerCheckedAt;

    /**
     * Builds a new area's map (and once more 1.5 s later, in case the area was still loading),
     * and a lower storey's when Banjo is under another floor (checked twice a second).
     */
    private void refreshMap() {
        int id = GameState.mapGeometryId();
        long now = SystemClock.uptimeMillis();
        if (id != mapGeometry) {
            mapGeometry = id;
            mapGeometryAt = now;
            mapRebuilt = false;
            mapImage = null;
            wantedCap = GameState.WHOLE;
            mapCap = GameState.WHOLE;
            if (id != 0) {
                buildMap(id, wantedCap);
            }
            return;
        }
        if (id == 0) {
            return;
        }
        if (now - layerCheckedAt > 500) {
            layerCheckedAt = now;
            float[] p = GameState.position();
            if (p != null) {
                boolean under = GameState.floorAbove(p[0], p[1], p[2]);
                // Round the cap so small height changes on one storey don't redraw the map.
                wantedCap = under ? (float) (Math.ceil((p[1] + 250f) / 500f) * 500f) : GameState.WHOLE;
            }
        }
        if (mapBuilding) {
            return;
        }
        if (wantedCap != mapCap) {
            buildMap(id, wantedCap);
        } else if ((mapImage == null || !mapRebuilt) && now - mapGeometryAt > 1500) {
            mapRebuilt = true;
            buildMap(id, wantedCap);
        }
    }

    private void buildMap(int id, float cap) {
        if (mapBuilding) {
            return;
        }
        mapBuilding = true;
        mapWorker.execute(() -> {
            GameState.MapImage built = GameState.buildMap(2048, cap);
            post(() -> {
                mapBuilding = false;
                if (id == mapGeometry && built != null) {
                    mapImage = built;
                    mapCap = cap;
                    invalidate();
                } else if (id == mapGeometry && cap != GameState.WHOLE) {
                    mapCap = cap;   // nothing below: keep showing the whole area
                }
            });
        });
    }

    // ---- Area names --------------------------------------------------------------------
    // The game writes the area's name when you enter it (not after loading a save). Each one
    // seen is remembered (by the game's map id) for next time.
    private String areaLabel;
    private int nameArea = GameState.UNKNOWN;
    private long nameAreaSince;
    private String nameBefore;

    private void refreshAreaName() {
        int area = GameState.area();
        long now = SystemClock.uptimeMillis();
        android.content.SharedPreferences names = getContext().getSharedPreferences("area_names", android.content.Context.MODE_PRIVATE);
        if (area != nameArea) {
            nameArea = area;
            nameAreaSince = now;
            nameBefore = GameState.areaName();
            String known = area >= 0 ? names.getString(Integer.toString(area), null) : null;
            setAreaName(known);
            return;
        }
        if (area < 0 || now - nameAreaSince > 8000) {
            return;
        }
        String seen = GameState.areaName();
        if (seen != null && !seen.equals(nameBefore)) {
            names.edit().putString(Integer.toString(area), seen).apply();
            setAreaName(seen);
            nameBefore = seen;
        }
    }

    /** "ISLE O' HAGS ( JINJO VILLAGE )" -> label "Jinjo Village", world Isle o' Hags. */
    private void setAreaName(String raw) {
        if (raw == null) {
            areaLabel = null;
            areaWorld = GameState.UNKNOWN;
            return;
        }
        String world = raw.trim(), place = world;
        int open = raw.indexOf('('), close = raw.lastIndexOf(')');
        if (open > 0 && close > open) {
            world = raw.substring(0, open).trim();
            place = raw.substring(open + 1, close).trim();
        }
        areaLabel = titleCase(place);
        areaWorld = GameState.UNKNOWN;
        for (int i = 0; i < GameState.WORLDS.length; i++) {
            if (GameState.WORLDS[i].equalsIgnoreCase(world) || GameState.WORLDS[i].equalsIgnoreCase(place)) {
                areaWorld = i;
            }
        }
    }

    private int areaWorld = GameState.UNKNOWN;

    private static String titleCase(String s) {
        StringBuilder out = new StringBuilder();
        boolean start = true;
        for (char ch : s.toLowerCase(java.util.Locale.ROOT).toCharArray()) {
            out.append(start && Character.isLetter(ch) ? Character.toUpperCase(ch) : ch);
            start = ch == ' ' || ch == '(' || ch == '-';
        }
        return out.toString().replace(" O' ", " o' ").replace(" Of ", " of ").replace(" The ", " the ");
    }

    private static final int MAP_DOT = Color.rgb(230, 70, 60);

    /** A few fixed stars: the Isle at night. */
    private void drawStars(Canvas c, int w, int h) {
        fill.setColor(Color.argb(60, 255, 244, 214));
        long seed = 7;
        for (int i = 0; i < 40; i++) {
            seed = seed * 6364136223846793005L + 1442695040888963407L;
            float x = ((seed >>> 33) % 1000) / 1000f * w;
            seed = seed * 6364136223846793005L + 1442695040888963407L;
            float y = ((seed >>> 33) % 1000) / 1000f * h;
            c.drawCircle(x, y, (i % 3 == 0 ? 0.35f : 0.2f) * u, fill);
        }
    }

    /** Jiggywiggy's puzzle: ten jigsaw pieces (two rows of five), filled in as they're found. */
    private void drawPuzzle(Canvas c, RectF r, int count) {
        int cols = 5, rows = 2;
        float cell = Math.min(r.width() / cols, r.height() / rows);
        float ox = r.centerX() - cell * cols / 2f, oy = r.centerY() - cell * rows / 2f;
        for (int i = 0; i < cols * rows; i++) {
            int col = i % cols, row = i / cols;
            boolean found = count >= 0 && i < count;
            puzzlePiece(path, ox + col * cell, oy + row * cell, cell, col, row, cols, rows);
            fill.setColor(found ? HONEY : SLOT);
            c.drawPath(path, fill);
            stroke.setColor(found ? HONEY_DARK : Color.argb(110, 255, 186, 40));
            stroke.setStrokeWidth(0.45f * u);
            c.drawPath(path, stroke);
        }
        if (count < 0) {
            text.setColor(TAN);
            text.setTextSize(3.4f * u);
            drawCentered(c, "Jiggies: –", r.centerX(), r.centerY());
        }
    }

    /**
     * One piece of a rows x cols jigsaw: a square cell with a knob out on its right and bottom
     * edges and a matching notch in on its left and top edges (none on the outer border).
     */
    private static void puzzlePiece(Path p, float x, float y, float s, int col, int row, int cols, int rows) {
        float k = s * 0.16f;   // knob radius
        float inset = s * 0.06f;
        float l = x + inset, t = y + inset, rt = x + s - inset, b = y + s - inset;
        float mx = (l + rt) / 2, my = (t + b) / 2;
        p.reset();
        p.moveTo(l, t);
        // Top edge: a notch unless on the top row.
        if (row > 0) {
            p.lineTo(mx - k, t);
            p.arcTo(new RectF(mx - k, t - k, mx + k, t + k), 180, -180, false);
        }
        p.lineTo(rt, t);
        // Right edge: a knob unless on the last column.
        if (col < cols - 1) {
            p.lineTo(rt, my - k);
            p.arcTo(new RectF(rt - k + inset, my - k, rt + k + inset, my + k), 270, 180, false);
        }
        p.lineTo(rt, b);
        // Bottom edge: a knob unless on the bottom row.
        if (row < rows - 1) {
            p.lineTo(mx + k, b);
            p.arcTo(new RectF(mx - k, b - k + inset, mx + k, b + k + inset), 0, 180, false);
        }
        p.lineTo(l, b);
        // Left edge: a notch unless on the first column.
        if (col > 0) {
            p.lineTo(l, my + k);
            p.arcTo(new RectF(l - k, my - k, l + k, my + k), 90, -180, false);
        }
        p.close();
    }

    /** The notes, as a stave with a count. */
    private void drawNotes(Canvas c, RectF r, int notes) {
        stroke.setColor(Color.argb(90, 232, 213, 168));
        stroke.setStrokeWidth(0.25f * u);
        float gap = r.height() / 6f;
        for (int i = 1; i <= 5; i++) {
            float y = r.top + gap * i;
            c.drawLine(r.left, y, r.right, y, stroke);
        }
        // A treble clef stand-in and the count.
        text.setColor(HONEY);
        text.setTextSize(r.height() * 0.95f);
        text.setTextAlign(Paint.Align.LEFT);
        c.drawText("♫", r.left + 1 * u, r.bottom - r.height() * 0.12f, text);
        text.setTextAlign(Paint.Align.RIGHT);
        text.setColor(CREAM);
        text.setTextSize(r.height() * 0.55f);
        c.drawText(value(notes) + " notes", r.right - 1 * u, r.centerY() + r.height() * 0.2f, text);
        text.setTextAlign(Paint.Align.CENTER);
    }

    private static String value(int v) {
        return v >= 0 ? Integer.toString(v) : "–";
    }

    private void jiggyIcon(Canvas c, float x, float y, float r, boolean filled) {
        float s = r * 0.75f;
        fill.setColor(filled ? HONEY : SLOT);
        c.drawRect(x - s, y - s, x + s, y + s, fill);
        c.drawCircle(x, y - s, s * 0.38f, fill);
        c.drawCircle(x + s, y, s * 0.38f, fill);
    }

    /** Simple drawings for each collectible (not the game's art). */
    private void icon(Canvas c, int item, float x, float y, float r) {
        stroke.setStrokeWidth(Math.max(1.5f, r * 0.12f));
        switch (item) {
            case GameState.GLOWBOS:
                fill.setShader(new RadialGradient(x, y, r, Color.rgb(255, 250, 200), Color.argb(0, 255, 120, 220), Shader.TileMode.CLAMP));
                c.drawCircle(x, y, r, fill);
                fill.setShader(null);
                fill.setColor(Color.rgb(250, 120, 210));
                c.drawCircle(x, y, r * 0.45f, fill);
                break;
            case GameState.JINJOS:
                fill.setColor(Color.rgb(120, 200, 255));
                c.drawCircle(x, y - r * 0.45f, r * 0.42f, fill);
                c.drawOval(x - r * 0.4f, y - r * 0.1f, x + r * 0.4f, y + r * 0.85f, fill);
                fill.setColor(Color.BLACK);
                c.drawCircle(x - r * 0.14f, y - r * 0.5f, r * 0.07f, fill);
                c.drawCircle(x + r * 0.14f, y - r * 0.5f, r * 0.07f, fill);
                break;
            case GameState.CHEATO_PAGES: {
                float pw = r * 0.7f, ph = r * 0.9f, fold = r * 0.3f;
                path.reset();
                path.moveTo(x - pw, y - ph);
                path.lineTo(x + pw - fold, y - ph);
                path.lineTo(x + pw, y - ph + fold);
                path.lineTo(x + pw, y + ph);
                path.lineTo(x - pw, y + ph);
                path.close();
                fill.setColor(CREAM);
                c.drawPath(path, fill);
                stroke.setColor(Color.rgb(150, 110, 60));
                for (int i = 0; i < 3; i++) {
                    float ly = y - ph * 0.3f + i * ph * 0.4f;
                    c.drawLine(x - pw * 0.6f, ly, x + pw * 0.6f, ly, stroke);
                }
                break;
            }
            case GameState.HONEYCOMBS:
                hexagon(path, x, y, r * 0.9f);
                fill.setColor(HONEY);
                c.drawPath(path, fill);
                hexagon(path, x, y, r * 0.5f);
                fill.setColor(HONEY_DARK);
                c.drawPath(path, fill);
                break;
            case GameState.EGGS:
                fill.setColor(Color.rgb(110, 170, 255));
                c.drawOval(x - r * 0.62f, y - r * 0.85f, x + r * 0.62f, y + r * 0.75f, fill);
                fill.setColor(Color.argb(150, 255, 255, 255));
                c.drawOval(x - r * 0.35f, y - r * 0.6f, x - r * 0.05f, y - r * 0.1f, fill);
                break;
            default: {
                // A feather: a curved leaf shape with a quill.
                path.reset();
                path.moveTo(x - r * 0.7f, y + r * 0.8f);
                path.quadTo(x - r * 0.6f, y - r * 0.6f, x + r * 0.7f, y - r * 0.9f);
                path.quadTo(x + r * 0.3f, y + r * 0.4f, x - r * 0.7f, y + r * 0.8f);
                fill.setColor(Color.rgb(230, 70, 60));
                c.drawPath(path, fill);
                stroke.setColor(Color.rgb(255, 220, 180));
                c.drawLine(x - r * 0.8f, y + r * 0.95f, x + r * 0.5f, y - r * 0.6f, stroke);
                break;
            }
        }
    }

    // ------------------------------------------------------------------------------
    // The progress book
    // ------------------------------------------------------------------------------

    private static final int[] BOOK_ITEMS = { GameState.JIGGIES, GameState.NOTES, GameState.JINJOS,
        GameState.GLOWBOS, GameState.CHEATO_PAGES, GameState.HONEYCOMBS };
    private static final String[] BOOK_NAMES = { "Jiggies", "Notes", "Jinjos", "Glowbos", "Cheato Pages", "Honeycombs" };

    /** Opens the book (when the game pauses). */
    private void openBook(boolean byPause) {
        bookOpen = true;
        bookByPause = byPause;
        bookPage = world;      // open on where you are (every world if not known)
        slideFrom = Integer.MIN_VALUE;
        invalidate();
    }

    /** -1 (every world), then each world in turn, wrapping round. */
    private void turnPage(int dir) {
        int n = GameState.WORLDS.length + 1;
        slideFrom = bookPage;
        slideDir = dir;
        slideAt = SystemClock.uptimeMillis();
        bookPage = ((bookPage + 1 + dir) % n + n) % n - 1;
        Haptics.tap(this);
        invalidate();
    }

    private void drawBook(Canvas c, RectF r) {
        // Header: ◀ the page's title ▶, and × to close.
        float headH = 11 * u;
        prevRect.set(r.left, r.top, r.left + headH, r.top + headH);
        closeRect.set(r.right - headH * 0.8f, r.top + headH * 0.1f, r.right, r.top + headH * 0.9f);
        nextRect.set(closeRect.left - 2 * u - headH, r.top, closeRect.left - 2 * u, r.top + headH);
        tmp.set(prevRect.right + 2 * u, r.top, nextRect.left - 2 * u, r.top + headH);
        fill.setColor(WOOD);
        c.drawRoundRect(tmp, headH * 0.3f, headH * 0.3f, fill);
        stroke.setColor(WOOD_EDGE);
        stroke.setStrokeWidth(0.3f * u);
        c.drawRoundRect(tmp, headH * 0.3f, headH * 0.3f, stroke);
        String title = bookPage < 0 ? "All Worlds" : GameState.WORLDS[bookPage];
        fitText(title, tmp.width() * 0.9f, 4.6f * u);
        text.setColor(HONEY);
        drawCentered(c, title, tmp.centerX(), tmp.centerY());
        for (int i = 0; i < 2; i++) {
            RectF a = i == 0 ? prevRect : nextRect;
            fill.setColor(WOOD);
            c.drawOval(a, fill);
            c.drawOval(a, stroke);
            arrow(c, a.centerX(), a.centerY(), a.height() * 0.2f, i == 0 ? -1 : 1);
        }
        stroke.setColor(TAN);
        stroke.setStrokeWidth(0.6f * u);
        float x = closeRect.width() * 0.22f;
        c.drawLine(closeRect.centerX() - x, closeRect.centerY() - x, closeRect.centerX() + x, closeRect.centerY() + x, stroke);
        c.drawLine(closeRect.centerX() - x, closeRect.centerY() + x, closeRect.centerX() + x, closeRect.centerY() - x, stroke);

        // The page, on a wooden board; it slides sideways when the page turns.
        RectF board = new RectF(r.left, r.top + headH + 3 * u, r.right, r.bottom);
        fill.setShader(new LinearGradient(0, board.top, 0, board.bottom, Color.rgb(46, 30, 16), Color.rgb(66, 40, 18), Shader.TileMode.CLAMP));
        c.drawRoundRect(board, 3 * u, 3 * u, fill);
        fill.setShader(null);
        stroke.setColor(Color.argb(200, 206, 162, 70));
        stroke.setStrokeWidth(0.45f * u);
        c.drawRoundRect(board, 3 * u, 3 * u, stroke);

        float t = (SystemClock.uptimeMillis() - slideAt) / (float) SLIDE_MS;
        int saved = c.save();
        c.clipRect(board);
        if (slideFrom != Integer.MIN_VALUE && t < 1f) {
            float e = 1f - (1f - t) * (1f - t) * (1f - t);   // ease out
            float bw = board.width();
            c.translate(-slideDir * e * bw, 0);
            drawPage(c, board, slideFrom);
            c.translate(slideDir * bw, 0);
            drawPage(c, board, bookPage);
            postInvalidateOnAnimation();
        } else {
            drawPage(c, board, bookPage);
        }
        c.restoreToCount(saved);
    }

    private void drawPage(Canvas c, RectF board, int page) {
        if (page < 0) {
            drawAllWorlds(c, board);
        } else {
            drawWorldPage(c, board, page);
        }
    }

    /** Every world in a table: a row each, a column per collectible, the totals at the bottom. */
    private void drawAllWorlds(Canvas c, RectF b) {
        int rows = GameState.WORLDS.length + 1;   // + the totals row
        float pad = 2 * u;
        float nameW = b.width() * 0.3f;
        float headH = 7 * u;
        float colW = (b.width() - nameW - pad * 2) / BOOK_ITEMS.length;
        float rowH = (b.height() - headH - pad * 2) / rows;
        for (int col = 0; col < BOOK_ITEMS.length; col++) {
            icon(c, BOOK_ITEMS[col], b.left + pad + nameW + colW * (col + 0.5f), b.top + pad + headH / 2,
                Math.min(headH * 0.4f, colW * 0.3f));
        }
        for (int row = 0; row < rows; row++) {
            boolean total = row == rows - 1;
            int wld = total ? -1 : row;
            float top = b.top + pad + headH + row * rowH;
            tmp.set(b.left + pad, top + rowH * 0.06f, b.right - pad, top + rowH * 0.94f);
            fill.setColor(total ? Color.argb(255, 96, 62, 26) : row % 2 == 0 ? Color.argb(255, 58, 36, 16) : Color.argb(255, 50, 31, 14));
            c.drawRoundRect(tmp, tmp.height() * 0.3f, tmp.height() * 0.3f, fill);
            if (wld >= 0 && wld == world) {
                stroke.setColor(HONEY);
                stroke.setStrokeWidth(0.4f * u);
                c.drawRoundRect(tmp, tmp.height() * 0.3f, tmp.height() * 0.3f, stroke);
            }
            String name = total ? "Total" : shortWorld(row);
            text.setTextAlign(Paint.Align.LEFT);
            fitText(name, nameW - 2 * u, Math.min(3.2f * u, rowH * 0.5f));
            text.setColor(total ? HONEY : CREAM);
            c.drawText(name, b.left + pad + 1.5f * u, tmp.centerY() - (text.descent() + text.ascent()) / 2f, text);
            text.setTextAlign(Paint.Align.CENTER);
            for (int col = 0; col < BOOK_ITEMS.length; col++) {
                int v = GameState.count(BOOK_ITEMS[col], wld);
                String value = BOOK_ITEMS[col] == GameState.JIGGIES && v >= 0 ? v + "/" + (total ? JIGGIES_TOTAL : JIGGIES_PER_WORLD) : value(v);
                boolean complete = BOOK_ITEMS[col] == GameState.JIGGIES && v >= (total ? JIGGIES_TOTAL : JIGGIES_PER_WORLD);
                fitText(value, colW * 0.9f, Math.min(3.2f * u, rowH * 0.5f));
                text.setColor(v < 0 ? Color.rgb(150, 136, 116) : complete ? HONEY : CREAM);
                drawCentered(c, value, b.left + pad + nameW + colW * (col + 0.5f), tmp.centerY());
            }
        }
    }

    /** One world: its puzzle, then a line per collectible. */
    private void drawWorldPage(Canvas c, RectF b, int wld) {
        float pad = 3 * u;
        RectF puzzle = new RectF(b.left + pad, b.top + pad, b.right - pad, b.top + pad + b.height() * 0.36f);
        drawPuzzle(c, puzzle, GameState.count(GameState.JIGGIES, wld));
        int rows = BOOK_ITEMS.length - 1;   // all but the Jiggies (the puzzle)
        float top = puzzle.bottom + 2 * u;
        float rowH = (b.bottom - pad - top) / rows;
        for (int i = 1; i < BOOK_ITEMS.length; i++) {
            float y = top + (i - 1) * rowH + rowH / 2;
            icon(c, BOOK_ITEMS[i], b.left + pad + 4 * u, y, Math.min(rowH * 0.32f, 3.6f * u));
            text.setTextAlign(Paint.Align.LEFT);
            text.setColor(CREAM);
            text.setTextSize(Math.min(4f * u, rowH * 0.42f));
            c.drawText(BOOK_NAMES[i], b.left + pad + 10 * u, y - (text.descent() + text.ascent()) / 2f, text);
            text.setTextAlign(Paint.Align.RIGHT);
            text.setColor(HONEY);
            c.drawText(value(GameState.count(BOOK_ITEMS[i], wld)), b.right - pad - 2 * u, y - (text.descent() + text.ascent()) / 2f, text);
            text.setTextAlign(Paint.Align.CENTER);
            if (i < BOOK_ITEMS.length - 1) {
                stroke.setColor(Color.argb(60, 232, 213, 168));
                stroke.setStrokeWidth(0.2f * u);
                c.drawLine(b.left + pad, y + rowH / 2, b.right - pad, y + rowH / 2, stroke);
            }
        }
    }

    private static String shortWorld(int i) {
        switch (i) {
            case 0: return "Isle o' Hags";
            case 1: return "Mayahem Temple";
            case 2: return "Glitter Gulch";
            case 3: return "Witchyworld";
            case 4: return "Jolly Roger's";
            case 5: return "Terrydactyland";
            case 6: return "Grunty Ind.";
            case 7: return "Hailfire Peaks";
            case 8: return "Cloud Cuckooland";
            default: return "Cauldron Keep";
        }
    }

    private void arrow(Canvas c, float cx, float cy, float r, int dir) {
        path.reset();
        path.moveTo(cx + dir * r, cy);
        path.lineTo(cx - dir * r * 0.6f, cy - r);
        path.lineTo(cx - dir * r * 0.6f, cy + r);
        path.close();
        fill.setColor(HONEY);
        c.drawPath(path, fill);
    }

    /** The four Z + C moves, in a row. */
    private void drawMoves(Canvas c, RectF area) {
        float gap = 2 * u;
        float bw = (area.width() - gap * (moves.length - 1)) / moves.length;
        for (int i = 0; i < moves.length; i++) {
            Move m = moves[i];
            m.rect.set(area.left + i * (bw + gap), area.top, area.left + i * (bw + gap) + bw, area.bottom);
            boolean on = m.down || m.lit || m == tapping;
            float rad = area.height() * 0.3f;
            fill.setColor(on ? Color.argb(240, 150, 96, 30) : WOOD);
            c.drawRoundRect(m.rect, rad, rad, fill);
            stroke.setColor(on ? HONEY : WOOD_EDGE);
            stroke.setStrokeWidth((on ? 0.6f : 0.3f) * u);
            c.drawRoundRect(m.rect, rad, rad, stroke);
            fitText(m.name, bw * 0.88f, Math.min(3.6f * u, area.height() * 0.3f));
            text.setColor(on ? HONEY : CREAM);
            drawCentered(c, m.name, m.rect.centerX(), m.rect.centerY() - area.height() * 0.12f);
            fitText(m.keys, bw * 0.88f, Math.min(2.6f * u, area.height() * 0.22f));
            text.setColor(TAN);
            drawCentered(c, m.keys, m.rect.centerX(), m.rect.centerY() + area.height() * 0.22f);
        }
    }

    /** TEMPORARY: the probe's Mark button and what it's for. */
    private void drawMark(Canvas c, RectF r) {
        boolean flash = SystemClock.uptimeMillis() - markFlashAt < 300;
        float bw = Math.min(r.width() * 0.28f, 24 * u);
        markRect.set(r.left, r.top, r.left + bw, r.bottom);
        fill.setColor(flash ? HONEY : Color.argb(200, 60, 90, 150));
        c.drawRoundRect(markRect, r.height() / 2, r.height() / 2, fill);
        text.setColor(flash ? Color.rgb(60, 30, 0) : Color.WHITE);
        text.setTextSize(3.2f * u);
        drawCentered(c, "Mark" + (marks > 0 ? " " + marks : ""), markRect.centerX(), markRect.centerY());
        text.setTextAlign(Paint.Align.LEFT);
        text.setColor(Color.argb(170, 232, 213, 168));
        text.setTextSize(2.4f * u);
        c.drawText("Test build: tap Mark, collect one thing, Mark again.", markRect.right + 2 * u, r.centerY() + 0.9f * u, text);
        text.setTextAlign(Paint.Align.CENTER);
        if (flash) {
            postInvalidateDelayed(300);
        }
    }

    /** Cutscenes: the screen fades down, and back up when it ends. */
    private void drawDim(Canvas c, int w, int h) {
        float target = cutscene && dimCutscenes ? 1f : 0f;
        long now = SystemClock.uptimeMillis();
        if (dim != target) {
            float step = Math.max(0, now - dimAt) / 400f;
            dim = target > dim ? Math.min(target, dim + step) : Math.max(target, dim - step);
            postInvalidateOnAnimation();
        }
        dimAt = now;
        if (dim > 0f) {
            fill.setColor(Color.argb(Math.round(200 * dim), 0, 0, 0));
            c.drawRect(0, 0, w, h, fill);
        }
    }

    private static void hexagon(Path p, float x, float y, float r) {
        p.reset();
        for (int i = 0; i < 6; i++) {
            double a = Math.PI / 3 * i + Math.PI / 6;
            float px = x + (float) (r * Math.cos(a)), py = y + (float) (r * Math.sin(a));
            if (i == 0) p.moveTo(px, py); else p.lineTo(px, py);
        }
        p.close();
    }

    private void drawCentered(Canvas c, String s, float x, float y) {
        c.drawText(s, x, y - (text.descent() + text.ascent()) / 2f, text);
    }

    private void fitText(String s, float maxWidth, float size) {
        text.setTextSize(size);
        while (text.getTextSize() > 8f && text.measureText(s) > maxWidth) {
            text.setTextSize(text.getTextSize() * 0.92f);
        }
    }

    // ------------------------------------------------------------------------------
    // Touch
    // ------------------------------------------------------------------------------

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int action = e.getActionMasked();
        int index = e.getActionIndex();
        float x = e.getX(index), y = e.getY(index);
        // The map: a tap cycles the zoom, a pinch zooms freely.
        if (!bookOpen && mapImage != null && (mapTouching || (action == MotionEvent.ACTION_DOWN && mapRect.contains(x, y)))) {
            pinch.onTouchEvent(e);
            if (action == MotionEvent.ACTION_DOWN) {
                mapTouching = true;
                mapPinched = false;
                mapTouchAt = SystemClock.uptimeMillis();
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                if (action == MotionEvent.ACTION_UP && !mapPinched && SystemClock.uptimeMillis() - mapTouchAt < 400) {
                    cycleZoom();
                }
                mapTouching = false;
            }
            return true;
        }
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            if (markRect.contains(x, y)) {
                marks = Math.max(marks, GameState.probeMark(getContext()));
                markFlashAt = SystemClock.uptimeMillis();
                Haptics.tap(this);
                invalidate();
                return true;
            }
            if (bookOpen) {
                if (prevRect.contains(x, y)) {
                    turnPage(-1);
                } else if (nextRect.contains(x, y)) {
                    turnPage(1);
                } else if (closeRect.contains(x, y)) {
                    bookOpen = false;
                    Haptics.tap(this);
                    invalidate();
                } else if (action == MotionEvent.ACTION_DOWN) {
                    swipeX = x;   // a sideways swipe turns the page
                }
                return true;
            }
            if (showButtons && frame >= 0) {
                for (Move m : moves) {
                    if (m.rect.contains(x, y)) {
                        pressMove(m);
                        return true;
                    }
                }
            }
            return true;
        }
        if (action == MotionEvent.ACTION_UP && bookOpen && !Float.isNaN(swipeX)) {
            float dx = e.getX() - swipeX;
            swipeX = Float.NaN;
            if (Math.abs(dx) > 12 * u) {
                turnPage(dx < 0 ? 1 : -1);
            }
            return true;
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP || action == MotionEvent.ACTION_CANCEL) {
            for (Move m : moves) {
                if (m.down && (action != MotionEvent.ACTION_POINTER_UP || m.rect.contains(x, y))) {
                    releaseMove(m);
                }
            }
        }
        return true;
    }

    private void pressMove(Move m) {
        Haptics.tap(this);
        m.down = true;
        if (m.toggle) {
            boolean on = !m.lit;
            for (Move other : moves) {
                other.lit = false;   // one toggle at a time
            }
            heldButtons = 0;
            TouchInput.setHeldN64Buttons(0);
            if (on) {
                m.lit = true;
                // Hold Z, then a moment later the C button: held for Wonderwing, a tap to
                // start the Talon Trot. Z stays held until the toggle is tapped off.
                heldButtons = Z;
                TouchInput.setHeldN64Buttons(heldButtons);
                final Move toggled = m;
                handler.postDelayed(() -> {
                    if (!toggled.lit) {
                        return;
                    }
                    if (toggled.holdC) {
                        heldButtons = toggled.buttons;
                        TouchInput.setHeldN64Buttons(heldButtons);
                    } else if (tapping == null) {
                        tapping = toggled;
                        handler.post(pressTap);
                    }
                }, 90);
            }
        } else if (tapping == null) {
            tapping = m;
            TouchInput.setN64Buttons(Z);            // crouch first...
            handler.postDelayed(pressTap, 90);      // ...then the C button
        }
        invalidate();
    }

    private void releaseMove(Move m) {
        m.down = false;
        invalidate();
    }

    private final Runnable pressTap = new Runnable() {
        @Override
        public void run() {
            if (tapping != null) {
                TouchInput.setN64Buttons(tapping.buttons);
                handler.postDelayed(releaseTap, 130);
            }
        }
    };

    private final Runnable releaseTap = new Runnable() {
        @Override
        public void run() {
            TouchInput.setN64Buttons(0);
            tapping = null;
            invalidate();
        }
    };

    @Override
    protected void onDetachedFromWindow() {
        setActive(false);
        super.onDetachedFromWindow();
    }
}
