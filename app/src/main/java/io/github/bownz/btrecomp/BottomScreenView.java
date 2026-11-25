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
    private boolean mapOff;   // Bottom Screen Map off: nothing built or drawn
    // Bottom Screen Buttons: Hold Z. The buttons are hidden until Z is held, then slide in
    // from the right over the bottom of the map, and slide out when it's let go.
    private boolean slideButtons, zHeld;
    private float buttonSlide = 1f;   // 0 hidden .. 1 in place
    private long buttonSlideAt;
    private static final float BUTTON_SLIDE_MS = 230f + 60f * 3;   // BUTTON_IN_MS + stagger × 3
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

    // Fredoka (SIL Open Font License; assets/fonts/Fredoka-OFL.txt): the bottom screen's lettering.
    private Typeface titleFont;

    private static Typeface loadFont(Context context, String asset, Typeface fallback) {
        try {
            return Typeface.createFromAsset(context.getAssets(), asset);
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    public BottomScreenView(Context context) {
        super(context);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        text.setTypeface(loadFont(context, "fonts/Fredoka-SemiBold.ttf", Typeface.create(Typeface.DEFAULT, Typeface.BOLD)));
        titleFont = loadFont(context, "fonts/Fredoka-Bold.ttf", Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
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
        mapOff = (GameState.bottomSettings() & 16) != 0;   // Input > Dual Screen > Bottom Screen Map
        refreshMap();
        recordEntry();
        updateHeading();
        easeArrow();
        int settings = GameState.bottomSettings();
        showButtons = (settings & 1) != 0;
        dimCutscenes = (settings & 2) != 0;
        slideButtons = showButtons && (settings & 8) != 0;
        boolean held = (GameState.n64Buttons() & GameState.BUTTON_Z) != 0;
        for (Move m : moves) held |= m.down;
        zHeld = held;
        // Redraw only when something shown has changed (or once a second for the rest: counts,
        // collected icons). The bottom screen is drawn by the same GPU as the game, and a full
        // redraw 30 times a second while Banjo stands still was GPU time the game could use.
        long sig = drawSignature();
        long now = SystemClock.uptimeMillis();
        if (sig != lastSignature || now - lastPollDraw >= 1000) {
            lastSignature = sig;
            lastPollDraw = now;
            invalidate();
        }
    }

    private long lastSignature, lastPollDraw;

    /** A hash of what poll() reads that changes what's drawn. */
    private long drawSignature() {
        long h = 1125899906842597L;
        float[] p = GameState.position();
        if (p != null) {
            h = 31 * h + Math.round(p[0] * 2f);
            h = 31 * h + Math.round(p[1] * 2f);
            h = 31 * h + Math.round(p[2] * 2f);
        } else {
            h = 31 * h + 7;
        }
        h = 31 * h + (Float.isNaN(shownDegrees) ? -1 : Math.round(shownDegrees * 4f));
        h = 31 * h + System.identityHashCode(mapImage);
        h = 31 * h + world;
        h = 31 * h + MapAtlas.done();
        h = 31 * h + (areaLabel == null ? 0 : areaLabel.hashCode());
        h = 31 * h + GameState.bottomSettings();
        h = 31 * h + ((paused ? 1 : 0) | (cutscene ? 2 : 0) | (bookOpen ? 4 : 0) | (zHeld ? 8 : 0) | (mapOff ? 16 : 0) | (onMenu ? 32 : 0));
        return h;
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
        boolean reserve = showButtons && !slideButtons;   // Hold Z: the map keeps the room
        float bottom = h - pad - markH - 2 * u - (reserve ? movesH + 3 * u : 0f);
        stepSlide();

        // One line on top: where you are, this world's Jiggies and notes.
        float stripH = 6 * u;
        drawStrip(c, new RectF(pad, pad, w - pad, pad + stripH));

        // The map of the current area.
        RectF map = new RectF(pad, pad + stripH + 2.5f * u, w - pad, bottom);
        drawMap(c, map);

        if (showButtons && !slideButtons) {
            drawMoves(c, new RectF(pad, bottom + 3 * u, w - pad, bottom + 3 * u + movesH));
        } else if (slideButtons && buttonSlide > 0f) {
            // Hold Z: a column down the right side of the map, sliding in from the right edge.
            RectF inner = mapRect;
            float colW = Math.min(30 * u, inner.width() * 0.34f);
            RectF col = new RectF(inner.right - colW - 1.2f * u, inner.top + 1.2f * u, inner.right - 1.2f * u, inner.bottom - 1.2f * u);
            // A dark band behind them, so they read over the map; fades with the slide.
            float fade = Math.min(1f, buttonSlide * 2.5f);
            fill.setColor(Color.argb((int) (185 * fade), 14, 12, 26));
            float band = 1.5f * u;
            c.drawRoundRect(new RectF(col.left - band, col.top - band, col.right + band, col.bottom + band), 3 * u, 3 * u, fill);
            drawMovesColumn(c, col, colW + pad + 2 * u);
        }
        drawMark(c, new RectF(pad, h - pad - markH, w - pad, h - pad));
        drawDim(c, w, h);
    }

    private void drawStrip(Canvas c, RectF r) {
        // This world's Jiggies and notes, top right (the area's name is on the map's banner).
        text.setTextSize(3.4f * u);
        text.setTextAlign(Paint.Align.RIGHT);
        text.setColor(HONEY);
        int jiggies = GameState.count(GameState.JIGGIES, world);
        int notes = GameState.count(GameState.NOTES, world);
        float baseline = r.centerY() - (text.descent() + text.ascent()) / 2f;
        float noteW = 2.6f * u;
        String right = (jiggies >= 0 ? jiggies + "/" + JIGGIES_PER_WORLD : "–") + " Jiggies  ·  " + value(notes) + " ";
        c.drawText(right, r.right - noteW, baseline, text);
        drawNote(c, r.right - noteW * 0.55f, r.centerY(), noteW, HONEY);
        text.setTextAlign(Paint.Align.CENTER);
    }

    /** A music note (the font has none), centred at (x, y), about size tall. */
    private void drawNote(Canvas c, float x, float y, float size, int colour) {
        fill.setColor(colour);
        float head = size * 0.26f;
        float hx = x - size * 0.12f, hy = y + size * 0.28f;
        c.save();
        c.rotate(-20f, hx, hy);
        c.drawOval(hx - head * 1.25f, hy - head * 0.9f, hx + head * 1.25f, hy + head * 0.9f, fill);
        c.restore();
        float sx = hx + head * 1.05f;
        c.drawRect(sx - size * 0.06f, y - size * 0.5f, sx + size * 0.06f, hy, fill);
        path.reset();
        path.moveTo(sx, y - size * 0.5f);
        path.cubicTo(sx + size * 0.35f, y - size * 0.35f, sx + size * 0.4f, y - size * 0.15f, sx + size * 0.22f, y + size * 0.02f);
        path.cubicTo(sx + size * 0.28f, y - size * 0.18f, sx + size * 0.18f, y - size * 0.28f, sx, y - size * 0.3f);
        path.close();
        c.drawPath(path, fill);
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
    private void drawMap(Canvas c, RectF frame) {
        long now = SystemClock.uptimeMillis();
        float dt = lastMapDraw == 0 ? 0f : Math.min(100f, now - lastMapDraw);
        lastMapDraw = now;
        boolean animating = false;

        // The wood frame; the map sits inside it.
        float bw = 2.2f * u;
        RectF r = new RectF(frame.left + bw, frame.top + bw, frame.right - bw, frame.bottom - bw);
        mapRect.set(r);
        float inner = 2.4f * u;
        drawFrame(c, frame, r, inner);
        mapClip.reset();
        mapClip.addRoundRect(r, inner, inner, Path.Direction.CW);
        if (mapOff) {
            fill.setColor(Color.argb(200, 14, 12, 26));
            c.drawRoundRect(r, inner, inner, fill);
            text.setTextAlign(Paint.Align.CENTER);
            text.setTextSize(3.4f * u);
            text.setColor(Color.argb(200, 255, 236, 170));
            c.drawText("Map off", r.centerX(), r.centerY(), text);
            text.setTextSize(2.4f * u);
            text.setColor(Color.argb(150, 255, 236, 170));
            c.drawText("Settings > Input > Bottom Screen Map", r.centerX(), r.centerY() + 4.5f * u, text);
            return;
        }

        float[] here = GameState.position();
        GameState.MapImage m = mapImage;
        int saved = c.save();
        c.clipPath(mapClip);
        c.drawColor(Color.BLACK);
        if (parchment && (m != null || leavingMap != null)) {
            drawParchment(c, r);
        }

        // The area we just left, fading out where it was.
        GameState.MapImage old = leavingMap;
        if (old != null) {
            float t = (now - leavingAt) / 300f;
            if (t >= 1f) {
                leavingMap = null;
            } else {
                mapPaint.setAlpha(Math.round(255 * (1f - t)));
                drawBackdrop(c, r, old, leavingCx, leavingCz, leavingS, mapPaint);
                drawWorldBitmap(c, r, old.bitmap, old.minX, old.minZ, old.scale, leavingCx, leavingCz, leavingS, mapPaint);
                mapPaint.setAlpha(255);
                animating = true;
            }
        }

        if (m == null) {
            if (old == null && !onMenu) {   // nothing at all on the title screen and file select
                drawLoading(c, r, here == null ? "Waiting for the game" : "Drawing the map", now);
                animating = true;
            }
        } else {
            if (m != fadingInFor) {   // a new picture: fade it in (an area's first one from black)
                // Within an area (another floor, a fresh build) the new picture crossfades over
                // the one shown before.
                crossFrom = shownBefore && old == null ? fadingInFor : null;
                fadingInFor = m;
                fadeInAt = now;
                fadeFrom = shownBefore && crossFrom == null ? 1f : 0f;
                shownBefore = true;
            }
            float fadeIn = Math.min(1f, fadeFrom + (now - fadeInAt) / (crossFrom != null ? 350f : 400f));
            if (fadeIn < 1f) animating = true;
            else crossFrom = null;
            boolean viewMoving = false;

            // The view eases towards what was asked (zoom steps, close-up <-> whole, Recenter).
            float ease = 1f - (float) Math.exp(-dt / 90f);
            float wholeTarget = mapWhole || here == null ? 1f : 0f;
            if (Float.isNaN(shownSpan)) shownSpan = followSpan;
            if (Math.abs(shownSpan - followSpan) > followSpan * 0.002f) {
                shownSpan = (float) Math.exp(Math.log(shownSpan) + (Math.log(followSpan) - Math.log(shownSpan)) * ease);
                animating = viewMoving = true;
            } else {
                shownSpan = followSpan;
            }
            if (Math.abs(wholeBlend - wholeTarget) > 0.002f) {
                wholeBlend += (wholeTarget - wholeBlend) * ease;
                animating = viewMoving = true;
            } else {
                wholeBlend = wholeTarget;
            }
            if (recentering) {
                panX -= panX * ease;
                panZ -= panZ * ease;
                if (Math.abs(panX) + Math.abs(panZ) < 2f) {
                    panX = panZ = 0f;
                    recentering = false;
                    panned = false;
                } else {
                    animating = viewMoving = true;
                }
            }

            float worldW = m.worldW(), worldH = m.worldH();
            float wholeScale = Math.min(r.width() / worldW, r.height() / worldH) * 0.96f;
            float wholeCx = m.minX + worldW / 2, wholeCz = m.minZ + worldH / 2;
            float followScale = Math.min(r.width(), r.height()) / shownSpan;
            float fcx = here != null ? here[0] : wholeCx, fcz = here != null ? here[2] : wholeCz;
            float bl = wholeBlend * wholeBlend * (3 - 2 * wholeBlend);   // smoothstep
            float viewScale = (float) Math.exp(Math.log(followScale) * (1 - bl) + Math.log(wholeScale) * bl);
            float cx = fcx * (1 - bl) + wholeCx * bl + panX;
            float cz = fcz * (1 - bl) + wholeCz * bl + panZ;
            viewCx = cx;
            viewCz = cz;
            viewS = viewScale;

            GameState.MapImage from = crossFrom;
            if (from != null) {
                mapPaint.setAlpha(255);
                drawBackdrop(c, r, from, cx, cz, viewScale, mapPaint);
                drawWorldBitmap(c, r, from.bitmap, from.minX, from.minZ, from.scale, cx, cz, viewScale, mapPaint);
            }
            mapPaint.setAlpha(Math.round(255 * fadeIn));
            drawBackdrop(c, r, m, cx, cz, viewScale, mapPaint);
            drawWorldBitmap(c, r, m.bitmap, m.minX, m.minZ, m.scale, cx, cz, viewScale, mapPaint);
            // The sharper close-up tile over it, when there is one for this view.
            GameState.MapImage d = detail;
            if (d != null && d.key == m.key) {
                drawWorldBitmap(c, r, d.bitmap, d.minX, d.minZ, d.scale, cx, cz, viewScale, mapPaint);
            }
            mapPaint.setAlpha(255);
            if (!viewMoving) {
                updateDetail(m, r, cx, cz, viewScale);   // not while zooming: the tile would be out of date at once
            }
            // (The water shimmer is off since 0.9.36: it only showed on some pictures, so it
            // gave away every swap between them. drawShimmer is kept for now.)

            // Where you came in (each entrance used in this area this session; the latest brighter).
            float iconScale = 0.75f + 0.25f * (1 - bl);
            // Collectibles and landmarks placed in the area (the game's own list).
            drawObjects(c, r, cx, cz, viewScale, iconScale, bl);
            // Doors used (learned as you play, kept between sessions); the latest brighter.
            int doorsHere = GameState.area();
            MapDoors.Door latestDoor = MapDoors.latest(doorsHere);
            java.util.List<MapDoors.Door> learned = MapDoors.doors(doorsHere);
            float[] objs = currentObjects();
            for (MapDoors.Door door : learned) {
                if (nearWarp(objs, door.x, door.z)) continue;   // warp pads and silos aren't doors (user, 24 Nov)
                drawEntrance(c, r.centerX() + (door.x - cx) * viewScale, r.centerY() + (door.z - cz) * viewScale,
                    2.2f * iconScale * u, door == latestDoor);
            }
            // Doorways from the level's setup (0.9.43): shown before they've been used; a learned
            // door there (which knows where it leads) takes its place.
            for (int i = 0; i + 3 < objs.length; i += 4) {
                if ((int) objs[i + 3] != GameState.OBJ_DOOR || nearLearned(learned, objs[i], objs[i + 2])) continue;
                drawEntrance(c, r.centerX() + (objs[i] - cx) * viewScale, r.centerY() + (objs[i + 2] - cz) * viewScale,
                    2.2f * iconScale * u, false);
            }
            drawDoorTag(c, r, cx, cz, viewScale, now);
            // Your own markers.
            for (float[] pin : userMarks(GameState.area())) {
                drawPin(c, r.centerX() + (pin[0] - cx) * viewScale, r.centerY() + (pin[1] - cz) * viewScale);
            }
            if (here != null) {
                float px = r.centerX() + (here[0] - cx) * viewScale;
                float pz = r.centerY() + (here[2] - cz) * viewScale;
                drawPlayer(c, px, pz, shownDegrees, 2.6f * iconScale * u, now);   // its pulse updates with the 30 Hz poll
            }
        }
        // A soft shadow inside the frame's edge.
        drawInnerShadow(c, r, inner);
        c.restoreToCount(saved);

        if (m != null) {
            drawMapControls(c, r);
            if (mapCap < GameState.WHOLE && m != wholeImage) {
                text.setTextAlign(Paint.Align.LEFT);
                text.setTextSize(2.6f * u);
                text.setColor(parchment ? Color.argb(230, 78, 50, 28) : Color.argb(220, 255, 236, 170));
                c.drawText("Lower floor", r.left + 11.5f * u, r.bottom - 3.2f * u, text);
                text.setTextAlign(Paint.Align.CENTER);
            }
        }
        drawAtlasProgress(c, r);
        if (drawBanner(c, frame, now)) animating = true;
        if (animating) {
            postInvalidateOnAnimation();
        }
    }

    // ---- The look: frame, banner, loading, player marker --------------------------------

    private long lastMapDraw;
    private float shownSpan = Float.NaN, wholeBlend = 0f;   // the view as shown, easing towards followSpan / mapWhole
    private boolean recentering;
    private GameState.MapImage leavingMap, fadingInFor, crossFrom;
    private float leavingCx, leavingCz, leavingS;
    private long leavingAt, fadeInAt;
    private float fadeFrom;
    private boolean shownBefore;
    private final Paint woodPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private android.graphics.Shader woodShader;
    private float woodTop = Float.NaN, woodBottom = Float.NaN;
    private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final int FRAME_DARK = Color.rgb(46, 24, 8);
    private static final int FRAME_LIGHT = Color.rgb(196, 140, 74);
    private static final int TRIM = Color.rgb(236, 186, 80);
    private static final int HONEY_DEEP = Color.rgb(242, 170, 40);
    private static final int HONEY_LIGHT = Color.rgb(255, 224, 130);
    private static final int INK = Color.rgb(74, 38, 8);

    private static Path hexagon(float cx, float cy, float r, float rotDeg) {
        Path p = new Path();
        for (int i = 0; i < 6; i++) {
            double a = Math.toRadians(60 * i + rotDeg);
            float x = cx + r * (float) Math.cos(a), y = cy + r * (float) Math.sin(a);
            if (i == 0) p.moveTo(x, y); else p.lineTo(x, y);
        }
        p.close();
        return p;
    }

    /** Carved wood around the map: grain, a dark outer edge, a light bevel, a gold trim, honey studs. */
    private void drawFrame(Canvas c, RectF frame, RectF r, float inner) {
        float outer = inner + 2.2f * u;
        if (woodShader == null || woodTop != frame.top || woodBottom != frame.bottom) {
            woodShader = new LinearGradient(0, frame.top, 0, frame.bottom, Color.rgb(132, 82, 38), Color.rgb(84, 48, 20), Shader.TileMode.CLAMP);
            woodTop = frame.top;
            woodBottom = frame.bottom;
        }
        woodPaint.setShader(woodShader);
        c.drawRoundRect(frame, outer, outer, woodPaint);
        // Grain: faint horizontal streaks.
        stroke.setStrokeWidth(0.18f * u);
        stroke.setColor(Color.argb(40, 40, 18, 4));
        for (float y = frame.top + 0.9f * u; y < frame.bottom; y += 1.1f * u) {
            c.drawLine(frame.left + outer * 0.3f, y, frame.right - outer * 0.3f, y + 0.25f * u, stroke);
        }
        stroke.setStrokeWidth(0.4f * u);
        stroke.setColor(FRAME_DARK);
        c.drawRoundRect(frame, outer, outer, stroke);
        RectF bevel = new RectF(frame.left + 0.5f * u, frame.top + 0.5f * u, frame.right - 0.5f * u, frame.bottom - 0.5f * u);
        stroke.setStrokeWidth(0.25f * u);
        stroke.setColor(FRAME_LIGHT);
        c.drawRoundRect(bevel, outer - 0.5f * u, outer - 0.5f * u, stroke);
        RectF trim = new RectF(r.left - 0.3f * u, r.top - 0.3f * u, r.right + 0.3f * u, r.bottom + 0.3f * u);
        stroke.setStrokeWidth(0.4f * u);
        stroke.setColor(TRIM);
        c.drawRoundRect(trim, inner + 0.3f * u, inner + 0.3f * u, stroke);
        float sx = (frame.left + r.left) / 2f + 0.15f * u, sy = (frame.top + r.top) / 2f + 0.15f * u;
        float ex = (frame.right + r.right) / 2f - 0.15f * u, ey = (frame.bottom + r.bottom) / 2f - 0.15f * u;
        float[][] corners = { { sx, sy }, { ex, sy }, { sx, ey }, { ex, ey } };
        for (float[] k : corners) {
            fill.setColor(HONEY_DEEP);
            c.drawPath(hexagon(k[0], k[1], 1.5f * u, 30), fill);
            stroke.setStrokeWidth(0.22f * u);
            stroke.setColor(Color.rgb(70, 36, 6));
            c.drawPath(hexagon(k[0], k[1], 1.5f * u, 30), stroke);
            fill.setColor(HONEY_LIGHT);
            c.drawPath(hexagon(k[0] - 0.2f * u, k[1] - 0.2f * u, 0.7f * u, 30), fill);
        }
    }

    /** A dark band just inside the frame, fading inwards. */
    private void drawInnerShadow(Canvas c, RectF r, float inner) {
        float depth = 2.6f * u;
        int dark = Color.argb(150, 10, 6, 2), clear = Color.argb(0, 10, 6, 2);
        shadowPaint.setShader(new LinearGradient(0, r.top, 0, r.top + depth, dark, clear, Shader.TileMode.CLAMP));
        c.drawRect(r.left, r.top, r.right, r.top + depth, shadowPaint);
        shadowPaint.setShader(new LinearGradient(0, r.bottom, 0, r.bottom - depth, dark, clear, Shader.TileMode.CLAMP));
        c.drawRect(r.left, r.bottom - depth, r.right, r.bottom, shadowPaint);
        shadowPaint.setShader(new LinearGradient(r.left, 0, r.left + depth, 0, dark, clear, Shader.TileMode.CLAMP));
        c.drawRect(r.left, r.top, r.left + depth, r.bottom, shadowPaint);
        shadowPaint.setShader(new LinearGradient(r.right, 0, r.right - depth, 0, dark, clear, Shader.TileMode.CLAMP));
        c.drawRect(r.right - depth, r.top, r.right, r.bottom, shadowPaint);
        shadowPaint.setShader(null);
    }

    // The area's name on a ribbon across the top of the frame; it drops in when the name changes.
    private String bannerText, bannerWorld;
    private long bannerAt;

    private boolean drawBanner(Canvas c, RectF frame, long now) {
        String title = areaLabel;
        String worldName = areaWorld >= 0 ? GameState.WORLDS[areaWorld] : null;
        if (title == null) {
            title = worldName;
        }
        if (title == null) {
            bannerText = null;
            return false;
        }
        if (!title.equals(bannerText)) {
            bannerText = title;
            bannerWorld = worldName != null && !worldName.equalsIgnoreCase(title) ? worldName : null;
            bannerAt = now;
        }
        float t = Math.min(1f, (now - bannerAt) / 450f);
        float k = 1.6f, q = t - 1f;
        float drop = 1f + (k + 1f) * q * q * q + k * q * q;   // ease-out with a small bounce

        text.setTypeface(titleFont);
        text.setTextSize(4.2f * u);
        float maxW = frame.width() * 0.5f;
        float tw = Math.min(maxW, text.measureText(title));
        if (text.measureText(title) > maxW) {
            text.setTextSize(4.2f * u * maxW / text.measureText(title));
        }
        float bh = 6.6f * u, bw = tw + 9 * u;
        float cx = frame.centerX();
        float by0 = frame.top - 2.8f * u - (1f - drop) * 9f * u, by1 = by0 + bh;
        float bx0 = cx - bw / 2, bx1 = cx + bw / 2;
        int alpha = Math.round(255 * Math.min(1f, t * 3f));
        int layer = c.saveLayerAlpha(frame.left - 6 * u, by0 - 4 * u, frame.right + 6 * u, by1 + 3 * u, alpha);
        // Folded tails.
        for (int side = -1; side <= 1; side += 2) {
            float ex = side < 0 ? bx0 : bx1;
            path.reset();
            path.moveTo(ex, by0 + 1.5f * u);
            path.lineTo(ex + side * 4.6f * u, by0 + 1.5f * u);
            path.lineTo(ex + side * 3.2f * u, by0 + 1.5f * u + (bh - 1.5f * u) / 2f + 0.6f * u);
            path.lineTo(ex + side * 4.6f * u, by1 + 1.2f * u);
            path.lineTo(ex, by1 + 1.2f * u);
            path.close();
            fill.setColor(Color.rgb(176, 104, 18));
            c.drawPath(path, fill);
            stroke.setStrokeWidth(0.22f * u);
            stroke.setColor(Color.rgb(70, 36, 6));
            c.drawPath(path, stroke);
        }
        RectF band = new RectF(bx0, by0, bx1, by1);
        fill.setShader(new LinearGradient(0, by0, 0, by1, Color.rgb(255, 214, 110), Color.rgb(226, 150, 30), Shader.TileMode.CLAMP));
        c.drawRoundRect(band, 1.2f * u, 1.2f * u, fill);
        fill.setShader(null);
        stroke.setStrokeWidth(0.32f * u);
        stroke.setColor(Color.rgb(70, 36, 6));
        c.drawRoundRect(band, 1.2f * u, 1.2f * u, stroke);
        stroke.setStrokeWidth(0.2f * u);
        stroke.setColor(Color.rgb(255, 236, 170));
        c.drawRoundRect(new RectF(bx0 + 0.5f * u, by0 + 0.5f * u, bx1 - 0.5f * u, by1 - 0.5f * u), 0.8f * u, 0.8f * u, stroke);
        float base = band.centerY() - (text.descent() + text.ascent()) / 2f;
        if (bannerWorld != null) {
            base += 0.9f * u;
        }
        text.setTextAlign(Paint.Align.CENTER);
        text.setColor(Color.rgb(255, 236, 170));
        c.drawText(title, cx + 0.2f * u, base + 0.25f * u, text);
        text.setColor(INK);
        c.drawText(title, cx, base, text);
        if (bannerWorld != null) {
            text.setTextSize(2.1f * u);
            text.setColor(Color.rgb(120, 66, 12));
            c.drawText(bannerWorld, cx, by0 + 2.2f * u, text);
        }
        text.setTypeface(textFont());
        c.restoreToCount(layer);
        return t < 1f;
    }

    private Typeface regularFont;

    private Typeface textFont() {
        if (regularFont == null) {
            regularFont = loadFont(getContext(), "fonts/Fredoka-SemiBold.ttf", Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        }
        return regularFont;
    }

    /** Six honeycomb cells lighting up in turn, and what's happening. */
    private void drawLoading(Canvas c, RectF r, String what, long now) {
        float cx = r.centerX(), cy = r.centerY() - 2 * u, ring = 3.6f * u, cell = 1.9f * u;
        int lit = (int) ((now / 110) % 6);
        for (int i = 0; i < 6; i++) {
            double a = Math.toRadians(60 * i - 90);
            float x = cx + ring * (float) Math.cos(a), y = cy + ring * (float) Math.sin(a);
            int age = (lit - i + 6) % 6;
            fill.setColor(Color.argb(255 - age * 36, 242, 170, 40));
            c.drawPath(hexagon(x, y, cell, 30), fill);
        }
        text.setColor(CREAM);
        text.setTextSize(3.2f * u);
        text.setTextAlign(Paint.Align.CENTER);
        c.drawText(what, cx, cy + ring + cell + 4.4f * u, text);
    }

    /**
     * Banjo's marker: a honey hexagon with a red pointer the way he's going, a white outline
     * and a soft shadow; a faint cone ahead; a slow pulse ring while he stands still. (An
     * original design: nothing from the game's art.)
     */
    private void drawPlayer(Canvas c, float x, float y, float degrees, float size, long now) {
        float hex = size * 1.15f;
        boolean still = now - lastMovedAt > 600;
        if (!Float.isNaN(degrees)) {
            double a = Math.toRadians(degrees);
            float dx = (float) Math.sin(a), dy = (float) Math.cos(a);
            // View cone.
            path.reset();
            path.moveTo(x, y);
            for (int i = -26; i <= 26; i += 4) {
                double b = a + Math.toRadians(i);
                path.lineTo(x + (float) Math.sin(b) * size * 7.5f, y + (float) Math.cos(b) * size * 7.5f);
            }
            path.close();
            fill.setShader(new android.graphics.RadialGradient(x, y, size * 7.5f, Color.argb(90, 255, 244, 200), Color.argb(0, 255, 244, 200), Shader.TileMode.CLAMP));
            c.drawPath(path, fill);
            fill.setShader(null);
            // Shadow, outline, pointer.
            fill.setColor(Color.argb(110, 0, 0, 0));
            c.drawPath(hexagon(x + 0.25f * size, y + 0.35f * size, hex * 1.08f, 30), fill);
            float lx = -dy, ly = dx;
            path.reset();
            path.moveTo(x + dx * size * 2.3f, y + dy * size * 2.3f);
            path.lineTo(x + dx * hex * 0.75f + lx * hex * 0.95f, y + dy * hex * 0.75f + ly * hex * 0.95f);
            path.lineTo(x + dx * hex * 0.75f - lx * hex * 0.95f, y + dy * hex * 0.75f - ly * hex * 0.95f);
            path.close();
            fill.setColor(Color.WHITE);
            c.drawPath(path, fill);
            c.drawPath(hexagon(x, y, hex * 1.17f, 30), fill);
            path.reset();
            path.moveTo(x + dx * size * 1.95f, y + dy * size * 1.95f);
            path.lineTo(x + dx * hex * 0.7f + lx * hex * 0.72f, y + dy * hex * 0.7f + ly * hex * 0.72f);
            path.lineTo(x + dx * hex * 0.7f - lx * hex * 0.72f, y + dy * hex * 0.7f - ly * hex * 0.72f);
            path.close();
            fill.setColor(Color.rgb(214, 52, 40));
            c.drawPath(path, fill);
        } else {
            fill.setColor(Color.WHITE);
            c.drawPath(hexagon(x, y, hex * 1.17f, 30), fill);
        }
        fill.setColor(HONEY_DEEP);
        c.drawPath(hexagon(x, y, hex, 30), fill);
        stroke.setStrokeWidth(0.18f * u);
        stroke.setColor(Color.rgb(160, 96, 12));
        c.drawPath(hexagon(x, y, hex, 30), stroke);
        fill.setColor(HONEY_LIGHT);
        c.drawPath(hexagon(x - 0.1f * size, y - 0.13f * size, hex * 0.43f, 30), fill);
        if (still) {
            float p = ((now - lastMovedAt) % 1600L) / 1600f;
            stroke.setStrokeWidth(0.35f * u);
            stroke.setColor(Color.argb(Math.round(170 * (1 - p)), 255, 214, 102));
            c.drawCircle(x, y, hex * (1.3f + 1.6f * p), stroke);
        }
    }

    // The arrow's direction. While Banjo moves it's the way he's moving; standing still it's the
    // game's facing value, flipped when that was last seen pointing backwards (it does during
    // Talon Trot, where Kazooie carries Banjo the other way round).
    private float arrowDegrees = Float.NaN;
    private float lastX = Float.NaN, lastZ = Float.NaN;
    private boolean facingFlipped;
    private float stoppedFacing = Float.NaN;   // the game's facing when he stopped moving
    private long lastMovedAt;
    private boolean turningInPlace;

    // What the arrow shows: eases towards arrowDegrees the short way round (~30 times a second).
    private float shownDegrees = Float.NaN;

    private void easeArrow() {
        if (Float.isNaN(arrowDegrees)) {
            return;
        }
        if (Float.isNaN(shownDegrees)) {
            shownDegrees = arrowDegrees;
            return;
        }
        float diff = ((arrowDegrees - shownDegrees) % 360f + 540f) % 360f - 180f;
        shownDegrees = Math.abs(diff) < 0.5f ? arrowDegrees : shownDegrees + diff * 0.3f;
        shownDegrees = (shownDegrees % 360f + 360f) % 360f;
    }

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
                lastMovedAt = SystemClock.uptimeMillis();
                if (!Float.isNaN(facing)) {
                    float diff = angleBetween(facing, moving);   // 0 = same way, 180 = opposite
                    if (diff < 50f) {
                        facingFlipped = false;
                    } else if (diff > 130f) {
                        facingFlipped = true;   // Talon Trot: the game's facing points backwards
                    }
                }
                stoppedFacing = Float.NaN;
                turningInPlace = false;
                lastX = p[0];
                lastZ = p[2];
                return;
            }
        }
        lastX = p[0];
        lastZ = p[2];
        // Standing still: keep the way he was moving, unless he turns on the spot.
        if (!Float.isNaN(facing)) {
            if (Float.isNaN(stoppedFacing)) {
                stoppedFacing = facing;
            } else if (turningInPlace || angleBetween(facing, stoppedFacing) > 20f) {
                turningInPlace = true;   // follow the facing until he moves again
                arrowDegrees = facingFlipped ? facing + 180f : facing;
            }
        }
    }

    /** The angle between two headings in degrees, 0 to 180. */
    private static float angleBetween(float a, float b) {
        return Math.abs(((a - b) % 360f + 540f) % 360f - 180f);
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

    // Doors learned by playing (MapDoors): where Banjo left each area, and where he came in.
    private int doorArea = GameState.UNKNOWN, arrivedFrom = GameState.UNKNOWN;
    private long doorAreaSince;
    private float[] doorLastPos, exitPos;
    private String doorLastName, stayName;
    private boolean arrivalNoted = true;

    private static boolean realArea(int area) {
        return area >= 0 && area != 0x18B && area != 0x158;   // not the title screen or file select
    }

    /** Follows area changes: the exit in the old area, then (a second later) the way into the new one. */
    private void recordEntry() {
        int area = GameState.area();
        long now = SystemClock.uptimeMillis();
        if (area < 0) {
            return;   // loading: wait for the next area
        }
        float[] p = GameState.position();
        if (area != doorArea) {
            if (realArea(doorArea) && realArea(area) && doorLastPos != null) {
                exitPos = doorLastPos;
                MapDoors.note(doorArea, exitPos, area, true, null);
                arrivedFrom = doorArea;
            } else {
                arrivedFrom = GameState.UNKNOWN;
                exitPos = null;
            }
            doorArea = area;
            doorAreaSince = now;
            arrivalNoted = false;
            doorLastPos = null;
            return;
        }
        if (p != null && (p[0] != 0f || p[1] != 0f || p[2] != 0f)) {
            doorLastPos = p;
        }
        if (arrivalNoted || now - doorAreaSince < 1000) {
            if (!arrivalNoted || now - doorAreaSince > 2500) {
                String n = GameState.areaName();
                if (n != null && !n.isEmpty()) doorLastName = n;
            }
            return;
        }
        arrivalNoted = true;
        // The area's name, only when the game has just written a new one (it doesn't on every
        // doorway, so the string can still be the last area's).
        String fresh = GameState.areaName();
        if (fresh != null && !fresh.isEmpty() && !fresh.equals(stayName)) {
            MapDoors.nameArea(area, fresh);
        }
        if (fresh != null && !fresh.isEmpty()) stayName = fresh;
        if (!realArea(arrivedFrom)) {
            return;   // loaded a save or came from the title: no doorway
        }
        float[] e = GameState.entryPoint();
        String here = GameState.areaName();
        if (e != null) {
            MapDoors.note(area, e, arrivedFrom, false, doorLastName);
        }
        if (exitPos != null && here != null && !here.isEmpty()) {
            MapDoors.note(arrivedFrom, exitPos, area, true, here);   // the old area's door: where it leads
        }
        if (here != null && !here.isEmpty()) doorLastName = here;
    }

    /** Banjo: a red arrow pointing the way he faces (0 degrees = +z = down the map), or a dot. */
    // The map picture for the current area, built in the background.
    private final RectF mapRect = new RectF();
    private final Paint mapBackgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private android.graphics.Shader mapBackground;
    private float mapBackgroundTop = Float.NaN, mapBackgroundBottom = Float.NaN;
    private final Path mapClip = new Path();
    private final Paint mapPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private boolean mapWhole = false;
    private float followSpan = 4500f;   // world units across the panel's short side (close-up)
    // Touch on the map: pinch zooms; a two-finger drag moves the view off Banjo, after which
    // one finger moves it too until Recenter. Press and hold drops (or removes) a marker.
    private boolean mapTouching, mapPinched;
    private long mapTouchAt;
    private float panX, panZ;           // world units the view is moved from Banjo / the area's middle
    private boolean panned;
    private float dragX = Float.NaN, dragY = Float.NaN;
    private int dragPointers;
    private float downX, downY;
    private boolean downMoved;
    private float viewCx, viewCz, viewS = 1f;   // the last drawn view, for touches
    private final RectF zoomInRect = new RectF(), zoomOutRect = new RectF(), recenterRect = new RectF();
    private final android.view.ScaleGestureDetector pinch = new android.view.ScaleGestureDetector(getContext(),
        new android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(android.view.ScaleGestureDetector d) {
                if (mapWhole || wholeBlend > 0f) {
                    // From the whole area (or on the way there): carry on from the view as shown,
                    // moved so the middle stays put.
                    float[] here = GameState.position();
                    followSpan = Math.min(mapRect.width(), mapRect.height()) / Math.max(1e-6f, viewS);
                    mapWhole = false;
                    wholeBlend = 0f;
                    if (here != null) {
                        panX = viewCx - here[0];
                        panZ = viewCz - here[2];
                        panned = Math.abs(panX) + Math.abs(panZ) > 1f;
                    }
                    recentering = false;
                }
                followSpan = Math.max(1200f, Math.min(30000f, followSpan / d.getScaleFactor()));
                shownSpan = followSpan;   // pinching follows the fingers at once
                mapPinched = true;
                invalidate();
                return true;
            }
        });

    // Zoom steps for + and - (world units across the panel's short side); past the last, the
    // whole area.
    private static final float[] ZOOM_STEPS = { 1800f, 2600f, 3500f, 4500f, 6000f, 8000f, 11000f };

    private void zoom(int dir) {
        if (mapWhole) {
            if (dir > 0) {
                mapWhole = false;
                followSpan = ZOOM_STEPS[ZOOM_STEPS.length - 1];
            }
        } else if (dir > 0) {
            float next = followSpan;
            for (int i = ZOOM_STEPS.length - 1; i >= 0; i--) {
                if (ZOOM_STEPS[i] < followSpan * 0.95f) { next = ZOOM_STEPS[i]; break; }
            }
            followSpan = next;
        } else {
            float next = Float.NaN;
            for (float step : ZOOM_STEPS) {
                if (step > followSpan * 1.05f) { next = step; break; }
            }
            if (Float.isNaN(next)) {
                mapWhole = true;
            } else {
                followSpan = next;
            }
        }
        Haptics.tap(this);
        invalidate();
    }

    private void recenter() {
        recentering = true;   // glides back (drawMap)
        Haptics.tap(this);
        invalidate();
    }

    /** + and - (honeycomb buttons) at the top right, a compass bottom left, Recenter bottom right once moved. */
    private void drawMapControls(Canvas c, RectF r) {
        float hr = 3.4f * u;
        float bx = r.right - 1.8f * u - hr;
        float y1 = r.top + 1.8f * u + hr, y2 = y1 + 2 * hr + 0.6f * u;
        zoomInRect.set(bx - hr, y1 - hr, bx + hr, y1 + hr);
        zoomOutRect.set(bx - hr, y2 - hr, bx + hr, y2 + hr);
        hexButton(c, bx, y1, hr, true);
        hexButton(c, bx, y2, hr, false);
        // The Notes layer: on or off (hide the notes when hunting for something else).
        float y3 = y2 + 2 * hr + 0.6f * u;
        notesRect.set(bx - hr, y3 - hr, bx + hr, y3 + hr);
        hexButton(c, bx, y3, hr, null);
        android.graphics.Bitmap note = GameState.sprite(0x0BE9);
        if (note != null) {
            float h = hr * 0.62f;
            spriteRect.set(bx - h, y3 - h, bx + h, y3 + h);
            spritePaint.setAlpha(notesShown ? 255 : 90);
            c.drawBitmap(note, null, spriteRect, spritePaint);
            spritePaint.setAlpha(255);
        }
        if (!notesShown) {
            stroke.setStrokeWidth(0.6f * u);
            stroke.setColor(INK);
            c.drawLine(bx - hr * 0.45f, y3 + hr * 0.45f, bx + hr * 0.45f, y3 - hr * 0.45f, stroke);
        }

        // Compass (north is up on this map).
        float cr = 3.6f * u, ccx = r.left + 1.8f * u + cr, ccy = r.bottom - 1.8f * u - cr;
        fill.setColor(Color.rgb(92, 54, 22));
        c.drawCircle(ccx, ccy, cr, fill);
        stroke.setStrokeWidth(0.3f * u);
        stroke.setColor(FRAME_DARK);
        c.drawCircle(ccx, ccy, cr, stroke);
        fill.setColor(Color.rgb(250, 232, 186));
        c.drawCircle(ccx, ccy, cr * 0.82f, fill);
        stroke.setStrokeWidth(0.2f * u);
        stroke.setColor(TRIM);
        c.drawCircle(ccx, ccy, cr * 0.82f, stroke);
        path.reset();
        path.moveTo(ccx, ccy - cr * 0.66f);
        path.lineTo(ccx - cr * 0.18f, ccy);
        path.lineTo(ccx + cr * 0.18f, ccy);
        path.close();
        fill.setColor(Color.rgb(214, 52, 40));
        c.drawPath(path, fill);
        path.reset();
        path.moveTo(ccx, ccy + cr * 0.66f);
        path.lineTo(ccx - cr * 0.18f, ccy);
        path.lineTo(ccx + cr * 0.18f, ccy);
        path.close();
        fill.setColor(Color.rgb(90, 70, 50));
        c.drawPath(path, fill);
        fill.setColor(INK);
        c.drawCircle(ccx, ccy, cr * 0.1f, fill);
        text.setTypeface(titleFont);
        text.setTextSize(2.2f * u);
        text.setTextAlign(Paint.Align.CENTER);
        text.setColor(INK);
        c.drawText("N", ccx, ccy - cr * 0.7f + 2.2f * u * 0.0f - 0.2f * u, text);
        text.setTypeface(textFont());

        if (panned || recentering) {
            text.setTextSize(2.8f * u);
            float tw = text.measureText("Recenter");
            float h2 = 5.6f * u;
            recenterRect.set(r.right - 1.8f * u - tw - 7.5f * u, r.bottom - 1.8f * u - h2, r.right - 1.8f * u, r.bottom - 1.8f * u);
            fill.setColor(Color.rgb(92, 54, 22));
            c.drawRoundRect(recenterRect, h2 / 2, h2 / 2, fill);
            stroke.setStrokeWidth(0.3f * u);
            stroke.setColor(FRAME_DARK);
            c.drawRoundRect(recenterRect, h2 / 2, h2 / 2, stroke);
            RectF in = new RectF(recenterRect.left + 0.55f * u, recenterRect.top + 0.55f * u, recenterRect.right - 0.55f * u, recenterRect.bottom - 0.55f * u);
            fill.setColor(HONEY_DEEP);
            c.drawRoundRect(in, in.height() / 2, in.height() / 2, fill);
            stroke.setStrokeWidth(0.2f * u);
            stroke.setColor(HONEY_LIGHT);
            c.drawRoundRect(in, in.height() / 2, in.height() / 2, stroke);
            fill.setColor(INK);
            c.drawPath(hexagon(in.left + 2.6f * u, in.centerY(), 1.0f * u, 30), fill);
            text.setColor(INK);
            text.setTextAlign(Paint.Align.LEFT);
            c.drawText("Recenter", in.left + 4.4f * u, in.centerY() - (text.descent() + text.ascent()) / 2f, text);
            text.setTextAlign(Paint.Align.CENTER);
        } else {
            recenterRect.setEmpty();
        }
    }

    /** A honeycomb button with + or -. */
    private void hexButton(Canvas c, float x, float y, float r, Boolean plus) {
        fill.setColor(Color.argb(120, 0, 0, 0));
        c.drawPath(hexagon(x + 0.3f * u, y + 0.5f * u, r, 0), fill);
        fill.setColor(Color.rgb(92, 54, 22));
        c.drawPath(hexagon(x, y, r, 0), fill);
        stroke.setStrokeWidth(0.3f * u);
        stroke.setColor(FRAME_DARK);
        c.drawPath(hexagon(x, y, r, 0), stroke);
        fill.setColor(HONEY_DEEP);
        c.drawPath(hexagon(x, y, r * 0.82f, 0), fill);
        stroke.setStrokeWidth(0.2f * u);
        stroke.setColor(HONEY_LIGHT);
        c.drawPath(hexagon(x, y, r * 0.82f, 0), stroke);
        if (plus == null) return;   // a plain plate (the Notes button draws its own picture)
        stroke.setStrokeWidth(0.6f * u);
        stroke.setColor(INK);
        float arm = r * 0.34f;
        c.drawLine(x - arm, y, x + arm, y, stroke);
        if (plus) {
            c.drawLine(x, y - arm, x, y + arm, stroke);
        }
    }

    /** Draws a picture whose top-left is world (minX, minZ) at `scale` pixels per unit. */
    /** The soft glow of the area's surroundings under its map (instead of plain black), if it has one. */
    private void drawBackdrop(Canvas c, RectF r, GameState.MapImage m, float cx, float cz, float viewScale, Paint paint) {
        if (parchment) {
            // On parchment the scenery around the level is left out; an ink outline and a shadow
            // set the level off the paper instead.
            GameState.MapImage ink = m.ink;
            if (ink != null) {
                drawWorldBitmap(c, r, ink.bitmap, ink.minX, ink.minZ, ink.scale, cx, cz, viewScale, paint);
            } else {
                requestInk(m);
            }
            return;
        }
        GameState.MapImage bd = m.backdrop;
        if (bd != null) {
            drawWorldBitmap(c, r, bd.bitmap, bd.minX, bd.minZ, bd.scale, cx, cz, viewScale, paint);
        }
    }

    private void drawWorldBitmap(Canvas c, RectF r, android.graphics.Bitmap b, float minX, float minZ, float scale,
                                 float cx, float cz, float viewScale, Paint paint) {
        int saved = c.save();
        c.translate(r.centerX() + (minX - cx) * viewScale, r.centerY() + (minZ - cz) * viewScale);
        float k = viewScale / scale;
        c.scale(k, k);
        c.drawBitmap(b, 0, 0, paint);
        c.restoreToCount(saved);
    }

    // ---- Sharper close-ups -------------------------------------------------------------
    // When the view is zoomed in past the area map's own detail, a tile around the view is
    // drawn again at screen detail (GameState.buildDetail) and shown over it.
    private volatile GameState.MapImage detail;
    private boolean detailBuilding;
    private final java.util.concurrent.ExecutorService detailWorker = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(() -> { GameState.backgroundThread(); r.run(); }, "BT map detail");
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    private void updateDetail(GameState.MapImage m, RectF r, float cx, float cz, float viewScale) {
        if (m.key < 0 || atlasStyle || viewScale < m.scale * 1.25f) {
            return;   // the area map is detailed enough here
        }
        float halfW = r.width() / 2 / viewScale, halfH = r.height() / 2 / viewScale;
        GameState.MapImage d = detail;
        if (d != null && d.key == m.key && d.scale >= viewScale * 0.7f && d.scale <= viewScale * 1.6f
                && cx - halfW >= d.minX && cz - halfH >= d.minZ
                && cx + halfW <= d.minX + d.worldW() && cz + halfH <= d.minZ + d.worldH()) {
            return;   // the tile covers this view
        }
        if (detailBuilding) {
            return;
        }
        detailBuilding = true;
        // One and a half times the view each way, so moving around stays sharp for a while.
        final float half = Math.max(halfW, halfH) * 1.5f;
        final int size = Math.min(1600, Math.round(2 * half * viewScale));
        final float x0 = cx - half, z0 = cz - half, x1 = cx + half, z1 = cz + half;
        final int key = m.key;
        detailWorker.execute(() -> {
            GameState.MapImage built = GameState.buildDetail(key, x0, z0, x1, z1, size);
            post(() -> {
                detailBuilding = false;
                if (built != null && mapImage != null && mapImage.key == key) {
                    detail = built;
                    invalidate();
                }
            });
        });
    }

    // ---- Water shimmer -----------------------------------------------------------------
    // Two slow layers of soft light ripples, drawn only where the area map has water.
    private android.graphics.Bitmap shimmerTile;
    private final Paint shimmerPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint maskPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final android.graphics.Matrix shimmerMatrix = new android.graphics.Matrix();
    private static final float SHIMMER_WORLD = 700f;   // world units per pattern tile

    private android.graphics.Bitmap shimmerTile() {
        if (shimmerTile == null) {
            int n = 128;
            int[] px = new int[n * n];
            for (int y = 0; y < n; y++) {
                for (int x = 0; x < n; x++) {
                    double a = 2 * Math.PI * x / n, b = 2 * Math.PI * y / n;
                    double v = Math.sin(2 * a + Math.sin(b) * 1.3) + Math.sin(3 * b + Math.sin(2 * a) * 1.1) + 0.6 * Math.sin(a + 2 * b);
                    double ridge = Math.max(0, 1.0 - Math.abs(v) * 1.6);   // thin bright lines where the waves cross zero
                    int alpha = (int) Math.round(255 * Math.pow(ridge, 1.6));
                    px[y * n + x] = Color.argb(alpha, 235, 248, 255);
                }
            }
            shimmerTile = android.graphics.Bitmap.createBitmap(px, n, n, android.graphics.Bitmap.Config.ARGB_8888);
            maskPaint.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN));
        }
        return shimmerTile;
    }

    private void drawShimmer(Canvas c, RectF r, GameState.MapImage m, float cx, float cz, float viewScale) {
        if (m.water == null) {
            return;
        }
        android.graphics.Bitmap tile = shimmerTile();
        float t = (SystemClock.uptimeMillis() % 600000L) / 1000f;
        int layer = c.saveLayer(r, null);
        float k = SHIMMER_WORLD * viewScale / tile.getWidth();
        for (int i = 0; i < 2; i++) {
            // Anchored to the world, drifting slowly (two directions, two speeds).
            float driftX = i == 0 ? t * 35f : -t * 22f, driftZ = i == 0 ? t * 18f : t * 30f;
            shimmerMatrix.reset();
            shimmerMatrix.setScale(k * (i == 0 ? 1f : 1.37f), k * (i == 0 ? 1f : 1.37f));
            shimmerMatrix.postTranslate(r.centerX() + (driftX - cx) * viewScale, r.centerY() + (driftZ - cz) * viewScale);
            android.graphics.BitmapShader sh = new android.graphics.BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
            sh.setLocalMatrix(shimmerMatrix);
            shimmerPaint.setShader(sh);
            shimmerPaint.setAlpha(i == 0 ? 70 : 50);
            c.drawRect(r, shimmerPaint);
        }
        shimmerPaint.setShader(null);
        drawWorldBitmap(c, r, m.water, m.minX, m.minZ, m.scale, cx, cz, viewScale, maskPaint);
        c.restoreToCount(layer);
    }

    // ---- Your markers ------------------------------------------------------------------
    // Press and hold on the map: drop a marker there, or remove the one under your finger.
    // Kept per area in the app's preferences.
    private final java.util.HashMap<Integer, java.util.ArrayList<float[]>> marksByArea = new java.util.HashMap<>();
    private final Runnable longPress = this::onLongPress;

    private java.util.ArrayList<float[]> userMarks(int area) {
        java.util.ArrayList<float[]> list = marksByArea.get(area);
        if (list == null) {
            list = new java.util.ArrayList<>();
            if (area >= 0) {
                String v = getContext().getSharedPreferences("map_marks", android.content.Context.MODE_PRIVATE)
                    .getString(Integer.toHexString(area), "");
                for (String pair : v.split(";")) {
                    String[] xz = pair.split(",");
                    if (xz.length == 2) {
                        try {
                            list.add(new float[] { Float.parseFloat(xz[0]), Float.parseFloat(xz[1]) });
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
            }
            marksByArea.put(area, list);
        }
        return list;
    }

    private void onLongPress() {
        if (!mapTouching || downMoved || dragPointers > 1) {
            return;
        }
        int area = GameState.area();
        if (area < 0) {
            return;
        }
        float wx = viewCx + (downX - mapRect.centerX()) / viewS, wz = viewCz + (downY - mapRect.centerY()) / viewS;
        java.util.ArrayList<float[]> list = userMarks(area);
        float reach = 5 * u / viewS;   // world units within a finger's width
        int hit = -1;
        float best = reach * reach;
        for (int i = 0; i < list.size(); i++) {
            // The pin's head is drawn above its point: test against both.
            float dx = list.get(i)[0] - wx, dz = list.get(i)[1] - wz;
            float dz2 = list.get(i)[1] - 3 * u / viewS - wz;
            float d = Math.min(dx * dx + dz * dz, dx * dx + dz2 * dz2);
            if (d < best) { best = d; hit = i; }
        }
        if (hit >= 0) {
            list.remove(hit);
        } else {
            list.add(new float[] { wx, wz });
        }
        StringBuilder sb = new StringBuilder();
        for (float[] pin : list) {
            if (sb.length() > 0) sb.append(';');
            sb.append(pin[0]).append(',').append(pin[1]);
        }
        getContext().getSharedPreferences("map_marks", android.content.Context.MODE_PRIVATE).edit()
            .putString(Integer.toHexString(area), sb.toString()).apply();
        mapPinched = true;   // the touch is used up
        Haptics.tap(this);
        invalidate();
    }

    // ---- Collectibles on the map ------------------------------------------------------

    private float[] areaObjects = new float[0];
    private int objectsArea = Integer.MIN_VALUE;
    private long objectsAt;

    /** The area's object list, read again every 2 s (cheap) and at once on a new area. */
    /** Within 1500 units of Banjo across the ground. */
    private static boolean nearBanjo(float x, float z) {
        float[] p = GameState.position();
        if (p == null) return false;
        float dx = x - p[0], dz = z - p[2];
        return dx * dx + dz * dz < 1500f * 1500f;
    }

    private float[] currentObjects() {
        int area = GameState.area();
        long now = SystemClock.uptimeMillis();
        if (area != objectsArea || now - objectsAt > 2000) {
            objectsArea = area;
            objectsAt = now;
            areaObjects = area >= 0 ? GameState.areaObjects() : new float[0];
        }
        return areaObjects;
    }

    /**
     * Jiggies, notes, Jinjos, Glowbos, honeycombs, pads and Jamjars as small icons (original
     * shapes, nothing from the game's art). Refills only close up; on a lower storey only
     * what's below the floor above.
     */
    private void drawObjects(Canvas c, RectF r, float cx, float cz, float viewScale, float iconScale, float whole) {
        float[] o = currentObjects();
        if (o.length == 0) return;
        float s = (1.0f + 0.35f * (1 - whole)) * u;
        // Pads grow with the zoom at half its rate (size ~ zoom^0.5), within limits: bigger and
        // easier to see close up, never huge. Zoom 1 = about 6000 units across the map.
        float zoomRel = viewScale * 6000f / Math.max(1f, r.width());
        padGrow = Math.max(0.8f, Math.min(1.8f, (float) Math.sqrt(zoomRel)));
        float[] banjo = GameState.position();
        long now = SystemClock.uptimeMillis();
        for (int pass = 0; pass < 2; pass++) {   // small things first, collectibles on top
            for (int i = 0; i + 3 < o.length; i += 4) {
                int kind = (int) o[i + 3];
                boolean minor = kind == GameState.OBJ_EGGS || kind == GameState.OBJ_FEATHERS || kind == GameState.OBJ_GOLD_FEATHERS
                        || kind == GameState.OBJ_HONEYCOMB;
                if ((pass == 0) != minor) continue;
                if (minor && whole > 0.5f) continue;
                boolean note = kind == GameState.OBJ_NOTES || kind == GameState.OBJ_CLEF;
                if (note && !notesShown) continue;   // the Notes layer is off
                // A lower floor's map still shows the rest of the area (0.9.15), so things up
                // there stay; only those right over Banjo's floor are left out.
                if (mapCap < GameState.WHOLE && o[i + 1] > mapCap && nearBanjo(o[i], o[i + 2])) continue;
                float x = r.centerX() + (o[i] - cx) * viewScale, y = r.centerY() + (o[i + 2] - cz) * viewScale;
                if (x < r.left - 4 * u || x > r.right + 4 * u || y < r.top - 4 * u || y > r.bottom + 4 * u) continue;
                if (note) {
                    // Notes are the most numerous, so the quietest: 70% unless Banjo is near
                    // or the note was just tapped.
                    boolean near = banjo != null && Math.hypot(o[i] - banjo[0], o[i + 2] - banjo[2]) < 900;
                    boolean tapped = now - tagAt < TAG_MS && tagIndex == i;
                    spritePaint.setAlpha(near || tapped ? 255 : 178);
                    drawObject(c, kind, x, y, s);
                    spritePaint.setAlpha(255);
                } else {
                    drawObject(c, kind, x, y, s);
                }
            }
        }
        drawTag(c, r, o, cx, cz, viewScale, now);
    }

    // ---- Tapping an icon: its name for a moment --------------------------------------------
    private static final long TAG_MS = 2500;
    private int tagIndex = -1;
    private long tagAt;
    private float padGrow = 1f;
    private boolean notesShown = true;
    private final RectF notesRect = new RectF();

    private static String objectName(int kind) {
        switch (kind) {
            case GameState.OBJ_JIGGY: return "Jiggy";
            case GameState.OBJ_NOTES: return "Note nest";
            case GameState.OBJ_CLEF: return "Treble clef";
            case GameState.OBJ_JINJO: return "Jinjo";
            case GameState.OBJ_GLOWBO: return "Glowbo";
            case GameState.OBJ_HONEYCOMB: return "Honeycomb";
            case GameState.OBJ_EMPTY_HONEYCOMB: return "Empty honeycomb";
            case GameState.OBJ_EGGS: return "Blue eggs";
            case GameState.OBJ_FEATHERS: return "Red feathers";
            case GameState.OBJ_GOLD_FEATHERS: return "Gold feathers";
            case GameState.OBJ_MUMBO_PAD: return "Mumbo pad";
            case GameState.OBJ_FLIGHT_PAD: return "Flight pad";
            case GameState.OBJ_JUMP_PAD: return "Shock jump pad";
            case GameState.OBJ_WARP_PAD: return "Warp pad";
            case GameState.OBJ_SILO: return "Warp silo";
            case GameState.OBJ_JAMJARS: return "Jamjars (moves)";
            default: return null;
        }
    }

    /**
     * A tap on the map at (x, y): the icon under it, if any, shows its name. Eggs and feathers
     * answer from further away than their picture (they're small and often on busy ground).
     */
    private boolean tapIcon(float x, float y) {
        float[] o = currentObjects();
        RectF r = mapRect;
        float s = viewS, cx = viewCx, cz = viewCz;
        if (!(s > 0)) return false;
        int best = -1;
        float bestD = Float.MAX_VALUE;
        for (int i = 0; i + 3 < o.length; i += 4) {
            int kind = (int) o[i + 3];
            if ((kind == GameState.OBJ_NOTES || kind == GameState.OBJ_CLEF) && !notesShown) continue;
            if (objectName(kind) == null) continue;
            float ix = r.centerX() + (o[i] - cx) * s, iy = r.centerY() + (o[i + 2] - cz) * s;
            boolean refill = kind == GameState.OBJ_EGGS || kind == GameState.OBJ_FEATHERS || kind == GameState.OBJ_GOLD_FEATHERS;
            float reach = (refill ? 6f : 4f) * u;
            float d = (float) Math.hypot(ix - x, iy - y);
            if (d < reach && (d < bestD || (d <= bestD && kind == GameState.OBJ_JAMJARS))) { bestD = d; best = i; }   // Jamjars over his silo
        }
        // Doors: "To <where it leads>"
        java.util.List<MapDoors.Door> learnedHere = MapDoors.doors(GameState.area());
        for (MapDoors.Door d : learnedHere) {
            if (nearWarp(o, d.x, d.z)) continue;
            float ix = r.centerX() + (d.x - cx) * s, iy = r.centerY() + (d.z - cz) * s;
            float dd = (float) Math.hypot(ix - x, iy - y);
            if (dd < 4f * u && dd < bestD) {
                bestD = dd; best = -2; doorTagX = d.x; doorTagZ = d.z;
                doorTagName = doorLabel(d);
            }
        }
        for (int i = 0; i + 3 < o.length; i += 4) {
            if ((int) o[i + 3] != GameState.OBJ_DOOR || nearLearned(learnedHere, o[i], o[i + 2])) continue;
            float ix = r.centerX() + (o[i] - cx) * s, iy = r.centerY() + (o[i + 2] - cz) * s;
            float dd = (float) Math.hypot(ix - x, iy - y);
            if (dd < 4f * u && dd < bestD) {
                bestD = dd; best = -2; doorTagX = o[i]; doorTagZ = o[i + 2];
                doorTagName = "Door (not used yet)";
            }
        }
        if (best == -2) {
            tagIndex = -1;
            doorTagAt = SystemClock.uptimeMillis();
            Haptics.tap(this);
            invalidate();
            return true;
        }
        if (best < 0) return false;
        tagIndex = best;
        tagAt = SystemClock.uptimeMillis();
        Haptics.tap(this);
        invalidate();
        return true;
    }

    /** Within 600 units of a warp pad or silo: going through one changes area, but it isn't a door. */
    private static boolean nearWarp(float[] objs, float x, float z) {
        for (int i = 0; i + 3 < objs.length; i += 4) {
            int k = (int) objs[i + 3];
            if ((k == GameState.OBJ_WARP_PAD || k == GameState.OBJ_SILO) && Math.hypot(objs[i] - x, objs[i + 2] - z) < 600) return true;
        }
        return false;
    }

    private static boolean nearLearned(java.util.List<MapDoors.Door> learned, float x, float z) {
        for (MapDoors.Door d : learned) if (Math.hypot(d.x - x, d.z - z) < 400) return true;
        return false;
    }

    private static String doorLabel(MapDoors.Door d) {
        String n = d != null ? MapDoors.nameOf(d.to) : null;
        return n != null ? "To " + titleCase(n.replace("( ", "(").replace(" )", ")")) : "Door";
    }

    private float doorTagX, doorTagZ;
    private String doorTagName;
    private long doorTagAt = -100000;

    private void drawDoorTag(Canvas c, RectF r, float cx, float cz, float viewScale, long now) {
        if (doorTagName == null || now - doorTagAt >= TAG_MS) return;
        drawTagAt(c, r, r.centerX() + (doorTagX - cx) * viewScale, r.centerY() + (doorTagZ - cz) * viewScale, doorTagName, now - doorTagAt);
    }

    private void drawTagAt(Canvas c, RectF r, float x, float y, String name, long age) {
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(2.4f * u);
        float w = text.measureText(name) + 2.4f * u, h = 3.6f * u;
        float ty = y - 4.6f * u;
        float left = Math.max(r.left + 0.5f * u, Math.min(r.right - 0.5f * u - w, x - w / 2));
        RectF box = new RectF(left, ty - h / 2, left + w, ty + h / 2);
        int a = Math.min(255, (int) ((TAG_MS - age) / 2));
        fill.setColor(Color.argb(Math.min(230, a), 52, 30, 12));
        c.drawRoundRect(box, h / 2, h / 2, fill);
        text.setColor(Color.argb(a, 255, 240, 205));
        c.drawText(name, box.centerX(), box.centerY() - (text.descent() + text.ascent()) / 2, text);
        postInvalidateDelayed(50);
    }

    private void drawTag(Canvas c, RectF r, float[] o, float cx, float cz, float viewScale, long now) {
        if (tagIndex < 0 || now - tagAt >= TAG_MS || tagIndex + 3 >= o.length) return;
        String name = objectName((int) o[tagIndex + 3]);
        if (name == null) return;
        float x = r.centerX() + (o[tagIndex] - cx) * viewScale, y = r.centerY() + (o[tagIndex + 2] - cz) * viewScale;
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(2.4f * u);
        float w = text.measureText(name) + 2.4f * u, h = 3.6f * u;
        float ty = y - 4.6f * u;
        float left = Math.max(r.left + 0.5f * u, Math.min(r.right - 0.5f * u - w, x - w / 2));
        RectF box = new RectF(left, ty - h / 2, left + w, ty + h / 2);
        int a = Math.min(255, (int) ((TAG_MS - (now - tagAt)) / 2));   // fades out at the end
        fill.setColor(Color.argb(Math.min(230, a), 52, 30, 12));
        c.drawRoundRect(box, h / 2, h / 2, fill);
        text.setColor(Color.argb(a, 255, 240, 205));
        c.drawText(name, box.centerX(), box.centerY() - (text.descent() + text.ascent()) / 2, text);
        postInvalidateDelayed(50);
    }

    /**
     * Eggs and feathers: a soft white glow and a thin dark edge round the picture, so they stand
     * out on busy ground (contrast more than size). Each kind keeps one look everywhere.
     */
    private final java.util.HashMap<Integer, android.graphics.Bitmap> outlined = new java.util.HashMap<>();

    private android.graphics.Bitmap outlinedSprite(int index) {
        android.graphics.Bitmap o = outlined.get(index);
        if (o != null) return o;
        android.graphics.Bitmap b = GameState.sprite(index);
        if (b == null) return null;
        b = trimmed(b);
        int pad = 3, w = b.getWidth() + 2 * pad, h = b.getHeight() + 2 * pad;
        int[] src = new int[b.getWidth() * b.getHeight()];
        b.getPixels(src, 0, b.getWidth(), 0, 0, b.getWidth(), b.getHeight());
        boolean[] in = new boolean[w * h];
        for (int y = 0; y < b.getHeight(); y++)
            for (int x = 0; x < b.getWidth(); x++)
                in[(y + pad) * w + x + pad] = (src[y * b.getWidth() + x] >>> 24) > 100;
        boolean[] edge = MapLayers.growMask(in, w, h, 1);
        int[] out = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                int sx = x - pad, sy = y - pad;
                int s = sx >= 0 && sy >= 0 && sx < b.getWidth() && sy < b.getHeight() ? src[sy * b.getWidth() + sx] : 0;
                if ((s >>> 24) > 100) out[i] = s;
                else if (edge[i]) out[i] = 0xE6281A0C;   // dark edge
            }
        }
        o = android.graphics.Bitmap.createBitmap(out, w, h, android.graphics.Bitmap.Config.ARGB_8888);
        outlined.put(index, o);
        return o;
    }

    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private void drawRefill(Canvas c, int index, float x, float y, float size) {
        glowPaint.setShader(new RadialGradient(x, y, size * 0.75f, Color.argb(170, 255, 255, 255), Color.argb(0, 255, 255, 255), Shader.TileMode.CLAMP));
        c.drawCircle(x, y, size * 0.75f, glowPaint);
        glowPaint.setShader(null);
        android.graphics.Bitmap b = outlinedSprite(index);
        if (b == null) return;
        float k = size / Math.max(b.getWidth(), b.getHeight());
        spriteRect.set(x - b.getWidth() * k / 2, y - b.getHeight() * k / 2, x + b.getWidth() * k / 2, y + b.getHeight() * k / 2);
        c.drawBitmap(b, null, spriteRect, spritePaint);
    }

    private static final int OUTLINE = Color.rgb(40, 26, 12);

    private final Paint spritePaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final RectF spriteRect = new RectF();

    /**
     * The game's own picture for a kind of thing (asset index in the ROM's table), or -1.
     * Read from the player's ROM on the device (GameState.sprite); nothing is stored in the app.
     */
    private static int spriteFor(int kind) {
        switch (kind) {
            case GameState.OBJ_JIGGY: return 0x0BE6;
            case GameState.OBJ_NOTES: case GameState.OBJ_CLEF: return 0x0BE9;
            case GameState.OBJ_JINJO: return 0x0BD0;
            case GameState.OBJ_GLOWBO: return 0x0BBB;
            case GameState.OBJ_HONEYCOMB: return 0x0B4D;
            case GameState.OBJ_EMPTY_HONEYCOMB: return 0x0BEA;
            case GameState.OBJ_EGGS: return 0x0B81;
            case GameState.OBJ_FEATHERS: case GameState.OBJ_FLIGHT_PAD: return 0x0B4A;
            case GameState.OBJ_GOLD_FEATHERS: return 0x0B4B;
            case GameState.OBJ_MUMBO_PAD: return 0x0B9D;
            case GameState.OBJ_JAMJARS: return 0x0BF7;
            default: return -1;
        }
    }

    /** Draws the game's sprite for this kind, `size` across; false if there isn't one. */
    private boolean drawSprite(Canvas c, int kind, float x, float y, float size) {
        int index = spriteFor(kind);
        if (index < 0) return false;
        android.graphics.Bitmap b = GameState.sprite(index);
        if (b == null) return false;
        float h = size / 2;
        spriteRect.set(x - h, y - h, x + h, y + h);
        c.drawBitmap(b, null, spriteRect, spritePaint);
        return true;
    }

    /**
     * The pads' own models (from the player's ROM, drawn from above, each on its own): flight
     * pad 1590, Mumbo pad 2009 (2008-2016 are the same pad in other colours), shock jump pad
     * 1589 (1874 is it in a stone frame), warp pad 1601 (an empty octagon: the game paints its
     * swirl on as it runs, so drawWarpSwirl adds a still one).
     */
    private static int padModel(int kind) {
        switch (kind) {
            case GameState.OBJ_FLIGHT_PAD: return 1590;
            case GameState.OBJ_MUMBO_PAD: return 2009;
            case GameState.OBJ_JUMP_PAD: return 1589;
            case GameState.OBJ_WARP_PAD: return 1601;
            case GameState.OBJ_SILO: return 2327;   // Isle o' Hags' silver warp dome (found 23 Nov)
            default: return -1;
        }
    }

    private final Runnable redraw = this::postInvalidate;
    private final Path swirlPath = new Path();
    private final Paint swirlPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    /** The warp pad's swirl, still: purple at the rim to bright blue in the middle. */
    private void drawWarpSwirl(Canvas c, float x, float y, float rad) {
        swirlPath.reset();
        for (int i = 0; i < 8; i++) {   // the pad's octagon, just inside its rim
            double a = Math.PI / 8 + i * Math.PI / 4;
            float px = x + (float) (rad * Math.cos(a)), py = y + (float) (rad * Math.sin(a));
            if (i == 0) swirlPath.moveTo(px, py); else swirlPath.lineTo(px, py);
        }
        swirlPath.close();
        swirlPaint.setStyle(Paint.Style.FILL);
        swirlPaint.setShader(new RadialGradient(x, y, rad, new int[] { Color.rgb(90, 150, 255), Color.rgb(60, 70, 230), Color.rgb(110, 40, 200) },
            new float[] { 0f, 0.55f, 1f }, Shader.TileMode.CLAMP));
        c.drawPath(swirlPath, swirlPaint);
        swirlPaint.setShader(null);
        // Two spiral arms, lighter, winding in to the middle.
        swirlPaint.setStyle(Paint.Style.STROKE);
        swirlPaint.setStrokeCap(Paint.Cap.ROUND);
        swirlPaint.setStrokeWidth(Math.max(1f, rad * 0.12f));
        swirlPaint.setColor(Color.argb(140, 160, 205, 255));
        for (int arm = 0; arm < 2; arm++) {
            Path p = new Path();
            for (int k = 0; k <= 40; k++) {
                float t = k / 40f;
                double a = arm * Math.PI + t * Math.PI * 2.6;
                float rr = rad * 0.9f * (1f - t);
                float px = x + (float) (rr * Math.cos(a)), py = y + (float) (rr * Math.sin(a));
                if (k == 0) p.moveTo(px, py); else p.lineTo(px, py);
            }
            c.drawPath(p, swirlPaint);
        }
        swirlPaint.setStyle(Paint.Style.FILL);
    }

    private void drawObject(Canvas c, int kind, float x, float y, float s) {
        int pad = padModel(kind);
        if (pad >= 0) {
            android.graphics.Bitmap b = GameState.modelIcon(pad, redraw);
            if (b != null) {
                float half = (kind == GameState.OBJ_SILO ? 2.2f : 1.75f) * s * padGrow;   // the dome is twice a pad's size
                float k = half / Math.max(b.getWidth(), b.getHeight()) * 2f;
                spriteRect.set(x - b.getWidth() * k / 2, y - b.getHeight() * k / 2, x + b.getWidth() * k / 2, y + b.getHeight() * k / 2);
                if (kind == GameState.OBJ_MUMBO_PAD) {
                    c.save();
                    c.rotate(180f, x, y);   // Mumbo's face the right way up
                    c.drawBitmap(b, null, spriteRect, spritePaint);
                    c.restore();
                } else {
                    c.drawBitmap(b, null, spriteRect, spritePaint);
                    if (kind == GameState.OBJ_WARP_PAD) {
                        drawWarpSwirl(c, x, y, Math.min(spriteRect.width(), spriteRect.height()) * 0.30f);
                    }
                }
                return;
            }
        }
        if (kind == GameState.OBJ_FLIGHT_PAD) {   // the pad's red ring, the feather in it
            stroke.setColor(Color.rgb(200, 50, 40));
            stroke.setStrokeWidth(0.4f * s);
            c.drawCircle(x, y, 1.2f * s, stroke);
            if (drawSprite(c, kind, x, y, 2.2f * s)) return;
        } else if (kind == GameState.OBJ_MUMBO_PAD) {   // the pad's purple ring, Mumbo in it
            stroke.setColor(Color.rgb(150, 80, 200));
            stroke.setStrokeWidth(0.4f * s);
            c.drawCircle(x, y, 1.4f * s, stroke);
            if (drawSprite(c, kind, x, y, 2.6f * s)) return;
        } else {
            boolean refill = kind == GameState.OBJ_EGGS || kind == GameState.OBJ_FEATHERS || kind == GameState.OBJ_GOLD_FEATHERS;
            if (refill) {
                int index = spriteFor(kind);
                if (index >= 0 && GameState.sprite(index) != null) {
                    drawRefill(c, index, x, y, 3.0f * s);   // a bit bigger than before (2.4)
                    return;
                }
            }
            boolean minor = refill || kind == GameState.OBJ_HONEYCOMB;
            float size = kind == GameState.OBJ_CLEF ? 3.4f * s : kind == GameState.OBJ_NOTES ? 2.3f * s : minor ? 2.4f * s : 3.0f * s;
            if (drawSprite(c, kind, x, y, size)) return;
        }
        if (kind == GameState.OBJ_GOLD_FEATHERS) kind = GameState.OBJ_FEATHERS;
        if (kind == GameState.OBJ_EMPTY_HONEYCOMB) kind = GameState.OBJ_HONEYCOMB;
        stroke.setColor(OUTLINE);
        stroke.setStrokeWidth(0.28f * s);
        switch (kind) {
            case GameState.OBJ_JIGGY: {
                // A plain jigsaw piece: a square with a knob on top and one on the right.
                float h = 1.25f * s, k = 0.42f * s;
                Path p = new Path();
                p.addRect(x - h, y - h, x + h, y + h, Path.Direction.CW);
                p.addCircle(x, y - h - k * 0.55f, k, Path.Direction.CW);
                p.addCircle(x + h + k * 0.55f, y, k, Path.Direction.CW);
                p.setFillType(Path.FillType.WINDING);
                fill.setColor(Color.rgb(250, 196, 40));
                c.drawPath(p, fill);
                c.drawPath(simplify(p), stroke);
                fill.setColor(Color.argb(150, 255, 248, 200));
                c.drawCircle(x - 0.45f * h, y - 0.45f * h, 0.28f * h, fill);
                break;
            }
            case GameState.OBJ_NOTES:
            case GameState.OBJ_CLEF: {
                // A music note (a beamed pair for the big one).
                boolean big = kind == GameState.OBJ_CLEF;
                float hs = (big ? 1.25f : 0.95f) * s;
                fill.setColor(big ? Color.rgb(255, 214, 70) : Color.rgb(255, 180, 60));
                float[] heads = big ? new float[] { x - 0.9f * hs, x + 0.9f * hs } : new float[] { x };
                Path stems = new Path();
                for (float hx : heads) {
                    c.save();
                    c.rotate(-20, hx, y + 0.6f * hs);
                    RectF head = new RectF(hx - 0.75f * hs, y + 0.15f * hs, hx + 0.75f * hs, y + 1.05f * hs);
                    c.drawOval(head, fill);
                    c.drawOval(head, stroke);
                    c.restore();
                    stems.moveTo(hx + 0.62f * hs, y + 0.45f * hs);
                    stems.lineTo(hx + 0.62f * hs, y - 1.6f * hs);
                }
                if (big) {
                    stems.moveTo(heads[0] + 0.62f * hs, y - 1.6f * hs);
                    stems.lineTo(heads[1] + 0.62f * hs, y - 1.6f * hs);
                } else {
                    stems.moveTo(x + 0.62f * hs, y - 1.6f * hs);
                    stems.quadTo(x + 1.6f * hs, y - 1.0f * hs, x + 1.2f * hs, y - 0.3f * hs);
                }
                stroke.setStrokeWidth(0.62f * s);
                c.drawPath(stems, stroke);
                stroke.setColor(fill.getColor());
                stroke.setStrokeWidth(0.3f * s);
                c.drawPath(stems, stroke);
                break;
            }
            case GameState.OBJ_JINJO: {
                // A little round figure: head with two eyes.
                float rr = 1.0f * s;
                fill.setColor(Color.rgb(176, 96, 220));
                c.drawCircle(x, y, rr, fill);
                c.drawCircle(x, y, rr, stroke);
                fill.setColor(Color.WHITE);
                c.drawCircle(x - 0.38f * rr, y - 0.15f * rr, 0.26f * rr, fill);
                c.drawCircle(x + 0.38f * rr, y - 0.15f * rr, 0.26f * rr, fill);
                fill.setColor(OUTLINE);
                c.drawCircle(x - 0.38f * rr, y - 0.12f * rr, 0.12f * rr, fill);
                c.drawCircle(x + 0.38f * rr, y - 0.12f * rr, 0.12f * rr, fill);
                break;
            }
            case GameState.OBJ_GLOWBO: {
                // A glowing orb.
                float rr = 0.9f * s;
                fill.setColor(Color.argb(90, 220, 140, 255));
                c.drawCircle(x, y, rr * 1.8f, fill);
                fill.setColor(Color.rgb(200, 110, 255));
                c.drawCircle(x, y, rr, fill);
                c.drawCircle(x, y, rr, stroke);
                fill.setColor(Color.argb(200, 255, 255, 255));
                c.drawCircle(x - 0.3f * rr, y - 0.3f * rr, 0.3f * rr, fill);
                break;
            }
            case GameState.OBJ_HONEYCOMB: {
                fill.setColor(Color.rgb(242, 160, 40));
                Path h = hexagon(x, y, 0.75f * s, 0f);
                c.drawPath(h, fill);
                c.drawPath(h, stroke);
                break;
            }
            case GameState.OBJ_EGGS: {
                fill.setColor(Color.rgb(90, 150, 240));
                RectF e = new RectF(x - 0.5f * s, y - 0.7f * s, x + 0.5f * s, y + 0.7f * s);
                c.drawOval(e, fill);
                c.drawOval(e, stroke);
                break;
            }
            case GameState.OBJ_FEATHERS: {
                fill.setColor(Color.rgb(230, 70, 60));
                Path f = new Path();
                f.moveTo(x, y - 0.9f * s);
                f.quadTo(x + 0.7f * s, y, x, y + 0.9f * s);
                f.quadTo(x - 0.7f * s, y, x, y - 0.9f * s);
                c.drawPath(f, fill);
                c.drawPath(f, stroke);
                break;
            }
            case GameState.OBJ_MUMBO_PAD: {
                // A pad: purple ring with a star dot.
                stroke.setColor(Color.rgb(150, 80, 200));
                stroke.setStrokeWidth(0.45f * s);
                c.drawCircle(x, y, 1.0f * s, stroke);
                fill.setColor(Color.rgb(255, 230, 120));
                c.drawCircle(x, y, 0.35f * s, fill);
                break;
            }
            case GameState.OBJ_FLIGHT_PAD: {
                // A flight pad: red ring with a gold feather across it.
                stroke.setColor(Color.rgb(200, 50, 40));
                stroke.setStrokeWidth(0.4f * s);
                c.drawCircle(x, y, 1.0f * s, stroke);
                Path q = new Path();
                q.moveTo(x - 0.6f * s, y + 0.6f * s);
                q.quadTo(x - 0.5f * s, y - 0.4f * s, x + 0.6f * s, y - 0.6f * s);
                q.quadTo(x + 0.5f * s, y + 0.4f * s, x - 0.6f * s, y + 0.6f * s);
                q.close();
                fill.setColor(Color.rgb(250, 200, 60));
                c.drawPath(q, fill);
                stroke.setColor(OUTLINE);
                stroke.setStrokeWidth(0.18f * s);
                c.drawPath(q, stroke);
                break;
            }
            case GameState.OBJ_WARP_PAD: case GameState.OBJ_SILO: {
                // A warp pad: blue ring, pale blue centre.
                fill.setColor(Color.rgb(170, 220, 255));
                c.drawCircle(x, y, 0.9f * s, fill);
                stroke.setColor(Color.rgb(40, 110, 220));
                stroke.setStrokeWidth(0.45f * s);
                c.drawCircle(x, y, 1.1f * s, stroke);
                break;
            }
            case GameState.OBJ_JUMP_PAD: {
                // A shock jump pad: green disc with an up chevron.
                fill.setColor(Color.rgb(90, 170, 60));
                c.drawCircle(x, y, 1.0f * s, fill);
                c.drawCircle(x, y, 1.0f * s, stroke);
                Path v = new Path();
                v.moveTo(x - 0.5f * s, y + 0.3f * s);
                v.lineTo(x, y - 0.35f * s);
                v.lineTo(x + 0.5f * s, y + 0.3f * s);
                stroke.setColor(Color.rgb(255, 245, 200));
                stroke.setStrokeWidth(0.25f * s);
                c.drawPath(v, stroke);
                break;
            }
            case GameState.OBJ_JAMJARS: {
                // His silo: an olive-green diamond with a dot.
                Path d = new Path();
                d.moveTo(x, y - 1.1f * s);
                d.lineTo(x + 1.1f * s, y);
                d.lineTo(x, y + 1.1f * s);
                d.lineTo(x - 1.1f * s, y);
                d.close();
                fill.setColor(Color.rgb(120, 140, 60));
                c.drawPath(d, fill);
                c.drawPath(d, stroke);
                fill.setColor(Color.rgb(250, 230, 150));
                c.drawCircle(x, y, 0.3f * s, fill);
                break;
            }
            default:
                break;
        }
    }

    /** The outline of a shape made of several overlapping parts. */
    private static Path simplify(Path p) {
        Path out = new Path();
        out.op(p, new Path(), Path.Op.UNION);
        return out;
    }

    /** A marker pin with its point at (x, y). */
    private void drawPin(Canvas c, float x, float y) {
        float rad = 1.7f * u, headY = y - 3.2f * u;
        Path pin = new Path();
        pin.moveTo(x, y);
        pin.lineTo(x - rad * 0.8f, headY + rad * 0.55f);
        pin.lineTo(x + rad * 0.8f, headY + rad * 0.55f);
        pin.close();
        fill.setColor(HONEY);
        c.drawPath(pin, fill);
        c.drawCircle(x, headY, rad, fill);
        stroke.setColor(Color.rgb(40, 26, 12));
        stroke.setStrokeWidth(0.3f * u);
        c.drawCircle(x, headY, rad, stroke);
        fill.setColor(Color.rgb(40, 26, 12));
        c.drawCircle(x, headY, rad * 0.4f, fill);
    }

    private volatile GameState.MapImage mapImage;
    private int mapGeometry = 0;
    private long mapGeometryAt;
    private boolean mapRebuilt;
    private boolean mapBuilding;
    private final java.util.concurrent.ExecutorService mapWorker = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(() -> { GameState.backgroundThread(); r.run(); }, "BT map");
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    // The floor layer shown: GameState.WHOLE, or a height cap when Banjo is under another floor.
    private float mapCap = GameState.WHOLE;
    private float wantedCap = GameState.WHOLE;
    private long layerCheckedAt;
    private int underMisses;
    private float layerY, layerOver;   // Banjo's height and what's over him, at the last check

    /**
     * Builds a new area's map (and once more 1.5 s later, in case the area was still loading),
     * and a lower storey's when Banjo is under another floor (checked twice a second).
     */
    private long seedCheckedAt, seedBuiltAt;
    private float[] belowAt;   // where the map last switched to a lower level because it showed one above

    private boolean onMenu;

    private boolean layersLoaded;

    private void refreshMap() {
        if (!layersLoaded) {
            layersLoaded = true;
            try {
                notesShown = getContext().getSharedPreferences("map_layers", android.content.Context.MODE_PRIVATE).getBoolean("notes", true);
            } catch (RuntimeException ignored) {
                // default
            }
        }
        onMenu = GameState.onMenu();
        int bs = GameState.bottomSettings();                       // Input > Dual Screen > Map Style
        int style = (bs & 128) != 0 ? MapAtlas.STYLE_ILLUSTRATED : (bs & 32) != 0 ? MapAtlas.STYLE_CLASSIC : MapAtlas.STYLE_ATLAS;
        boolean atlas = style != MapAtlas.STYLE_CLASSIC;
        parchment = (bs & 64) == 0;                                // ... > Map Background
        if (style != mapStyle || !styleSet) {
            styleSet = true;
            atlasStyle = atlas;
            mapStyle = style;
            GameState.setMapStyle(atlas);
            mapMemory.clear();
            mapGeometry = 0;   // draw the area again in the new style
            mapImage = null;
            detail = null;
        }
        if (mapOff) {
            // Forget the area, so turning the map back on builds it afresh.
            mapGeometry = 0;
            mapImage = null;
            detail = null;
            leavingMap = null;
            return;
        }
        int id = onMenu ? 0 : GameState.mapGeometryId();   // title screen / file select: no map
        long now = SystemClock.uptimeMillis();
        // A map just drawn from the ROM may be the area on screen (built live so far): show it.
        int drawnNow = MapAtlas.done();
        if (drawnNow != atlasSeen) {
            if (atlasSeen >= 0 && atlasStyle && mapImage != null && mapImage.key >= 0 && mapCap >= GameState.WHOLE) {
                mapRebuilt = false;
                mapGeometryAt = 0;
            }
            atlasSeen = drawnNow;
        }
        if (mapCap >= GameState.WHOLE && mapImage != null) {
            wholeImage = mapImage;   // what a lower floor is cut into (composeLayer)
        }
        if (id != mapGeometry) {
            if (mapImage != null) {   // fade the area we're leaving out where it was
                leavingMap = mapImage;
                leavingCx = viewCx;
                leavingCz = viewCz;
                leavingS = viewS;
                leavingAt = now;
                shownBefore = false;  // the next area's map fades in from black
                invalidate();
            }
            mapGeometry = id;
            mapGeometryAt = now;
            mapRebuilt = false;
            mapImage = null;
            wholeImage = null;
            layerRaw = null;
            layerMask = null;
            fadingInFor = null;
            crossFrom = null;
            detail = null;
            panX = panZ = 0f;
            panned = false;
            recentering = false;
            wantedCap = GameState.WHOLE;
            mapCap = GameState.WHOLE;
            belowAt = null;
            cacheTried = Integer.MIN_VALUE;
            if (id != 0) {
                buildMap(id, wantedCap);
            }
            return;
        }
        if (id == 0) {
            return;
        }
        // An area seen before: show its saved map straight away (the fresh one replaces it).
        int area = GameState.area();
        if (mapImage == null && area >= 0 && cacheTried != area) {
            cacheTried = area;
            showSavedMap(id, area, GameState.WHOLE);
        }
        if (now - layerCheckedAt > 500) {
            layerCheckedAt = now;
            float[] p = GameState.position();
            if (p != null) {
                // 0.9.32: anything solid right over him (a roof, an arch, Gruntilda's hat), not
                // only a whole floor across a wide patch: the map cuts out just that structure
                // now. The two older checks still count (a floor above with nothing found right
                // over the middle of him, e.g. at a gap).
                float over = GameState.overhead(p[0], p[1], p[2]);
                boolean under = !Float.isNaN(over) || GameState.floorAbove(p[0], p[1], p[2]);
                if (Float.isNaN(over)) over = p[1] + 2000f;
                // Or the whole-area map shows a level well above him right where he is (checked
                // against the last build, which is the whole area while it's shown). Once that
                // switched to the lower level, stay there while he keeps near that spot and height.
                if (!under && mapCap == GameState.WHOLE && mapImage != null && mapImage.key >= 0 && !paused && !cutscene
                        && GameState.mapShowsLevelAbove(mapImage.key, p[0], p[1], p[2])) {
                    under = true;
                    belowAt = new float[] { p[0], p[1], p[2] };
                }
                if (!under && belowAt != null && mapCap != GameState.WHOLE) {
                    float dx = p[0] - belowAt[0], dz = p[2] - belowAt[2];
                    under = dx * dx + dz * dz < 1500f * 1500f && Math.abs(p[1] - belowAt[1]) < 300f;
                }
                if (!under) belowAt = null;
                // Out from under for a whole second before the map goes back (stepping along
                // the edge of a roof doesn't flick it).
                if (under) {
                    underMisses = 0;
                } else if (mapCap != GameState.WHOLE && ++underMisses < 2) {
                    under = true;
                    over = layerOver;
                }
                if (under) {
                    // The cut goes between his floor and what's over him, rounded so walking
                    // about on one floor doesn't redraw the map.
                    float mid = Math.max(p[1] + 200f, (p[1] + over) / 2f);
                    float cap = (float) (Math.floor(mid / 250f) * 250f);
                    if (cap < p[1] + 150f) cap += 250f;
                    layerY = p[1];
                    layerOver = over;
                    wantedCap = mapCap != GameState.WHOLE && Math.abs(mapCap - cap) <= 250f && mapCap > p[1] + 100f && mapCap < over
                        ? mapCap : cap;   // the cut shown still lies between them: keep it
                } else {
                    wantedCap = GameState.WHOLE;
                }
            }
        }
        if (mapBuilding) {
            return;
        }
        // Standing on ground the map left out (a high tier above the area's main level): build
        // again with it included. Checked once a second.
        // At most one such rebuild every 15 s: a build costs a second or two of a core, and
        // walking up a long slope the map had left out asked for one every second (0.9.9: a big
        // slowdown on Isle o' Hags' west side, 13 Nov).
        // 0.9.20: every 45 s at most (a build keeps a slow core busy for several seconds and its
        // memory traffic cost the renderer frames).
        if (mapImage != null && wantedCap == GameState.WHOLE && now - seedCheckedAt > 1000 && now - seedBuiltAt > 45000) {
            seedCheckedAt = now;
            float[] p = GameState.position();
            if (p != null && !paused && !cutscene && GameState.mapWantsSeed(mapImage.key, p[0], p[1], p[2])) {
                seedBuiltAt = now;
                buildMap(id, wantedCap);
                return;
            }
        }
        if (wantedCap != mapCap) {
            if (layerBusy) {
                return;
            }
            if (wantedCap >= GameState.WHOLE && wholeImage != null) {
                // Out from under: back to the whole area (crossfades).
                mapImage = wholeImage;
                mapCap = GameState.WHOLE;
                layerMask = null;
                layerRaw = null;
                invalidate();
                return;
            }
            if (wantedCap < GameState.WHOLE) {
                // A storey built earlier in this visit (or an earlier session) is cut in without
                // building it again (0.9.20: walking between storeys rebuilt the map every time).
                requestLayer(id, wantedCap, area >= 0 ? mapMemory.get(mapKey(area, wantedCap)) : null);
                return;
            }
            buildMap(id, wantedCap);
        } else if (mapCap < GameState.WHOLE && layerMask != null && layerRaw != null && !layerBusy
                && now - layerCheckedAt < 50 && !layerMask.covers(GameState.position())) {
            // Under another part of the floor above (not the one cut out): cut that out instead.
            requestLayer(id, mapCap, layerRaw);
        } else if ((mapImage == null || !mapRebuilt) && now - mapGeometryAt > (mapImage != null ? 8000 : 1500)) {
            // With a saved map on screen the fresh build waits 8 s: entering an area, the game is
            // loading and the renderer has the least time to spare (0.9.20).
            mapRebuilt = true;
            buildMap(id, wantedCap);
        }
    }

    private void buildMap(int id, float cap) {
        if (mapBuilding) {
            return;
        }
        mapBuilding = true;
        final int style = mapStyle;
        mapWorker.execute(() -> {
            int areaBefore = GameState.area();
            // The area's map drawn from the ROM (either style), when it's been drawn (found by the
            // fingerprint of the collision list loaded now). Otherwise a live build. Before 0.9.40
            // Classic was always built live, from the game's memory, where Mayahem Temple's
            // see-through model isn't its water: most of the moat was missing (24 Nov).
            GameState.MapImage built = cap >= GameState.WHOLE ? MapAtlas.load(GameState.currentFingerprint(), "all", style) : null;
            boolean fromAtlas = built != null;
            if (built == null) {
                built = GameState.buildMap(2048, cap);
            }
            int areaAfter = GameState.area();
            // (Illustrated only comes from the ROM: a live stand-in isn't kept as its map.)
            if (built != null && !fromAtlas && style != MapAtlas.STYLE_ILLUSTRATED && areaBefore >= 0 && areaBefore == areaAfter) {
                saveMap(areaBefore, cap, built);
            }
            final GameState.MapImage result = built;
            post(() -> {
                mapBuilding = false;
                if (id == mapGeometry && result != null && cap < GameState.WHOLE) {
                    if (areaBefore >= 0 && areaBefore == areaAfter) {
                        mapMemory.put(mapKey(areaBefore, cap), result);
                    }
                    requestLayer(id, cap, result);
                } else if (id == mapGeometry && result != null) {
                    mapImage = result;
                    mapCap = cap;
                    if (areaBefore >= 0 && areaBefore == areaAfter) {
                        mapMemory.put(mapKey(areaBefore, cap), result);
                    }
                    invalidate();
                } else if (id == mapGeometry && cap != GameState.WHOLE) {
                    mapCap = cap;   // nothing below: keep showing the whole area
                }
            });
        });
    }

    // ---- Explored areas ----------------------------------------------------------------
    // Each area's map (and storey) is kept: the last few in memory, all of them as PNG files in
    // the app's cache, by the game's map id. Bump MAP_CACHE_VERSION when the map's look changes.
    private static final String MAP_CACHE_VERSION = "v13";
    private int cacheTried = Integer.MIN_VALUE;
    private final java.util.LinkedHashMap<String, GameState.MapImage> mapMemory =
        new java.util.LinkedHashMap<String, GameState.MapImage>(8, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(java.util.Map.Entry<String, GameState.MapImage> eldest) {
                return size() > 4;
            }
        };

    private String mapKey(int area, float cap) {
        return MAP_CACHE_VERSION + (mapStyle == MapAtlas.STYLE_ILLUSTRATED ? "i" : atlasStyle ? "a" : "c") + "-" + Integer.toHexString(area) + "-" + (cap >= GameState.WHOLE ? "all" : Integer.toString(Math.round(cap)));
    }

    private java.io.File mapFile(String key, String ext) {
        java.io.File dir = new java.io.File(getContext().getCacheDir(), "maps");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return new java.io.File(dir, "map-" + key + ext);
    }

    /** Shows a saved map for the area right away: from memory, or read from the cache in the background. */
    private void showSavedMap(int id, int area, float cap) {
        String key = mapKey(area, cap);
        GameState.MapImage memory = mapMemory.get(key);
        if (memory != null) {
            mapImage = memory;
            invalidate();
            return;
        }
        final int style = mapStyle;
        layerWorker.execute(() -> {
            GameState.MapImage loaded = loadMap(key);
            if (loaded == null && cap >= GameState.WHOLE) {
                // Not seen before: the map drawn from the ROM, if it's done (Classic refines it
                // with a live build a few seconds later; Atlas keeps it).
                loaded = MapAtlas.load(GameState.currentFingerprint(), "all", style);
            }
            if (loaded == null) {
                return;
            }
            final GameState.MapImage got = loaded;
            post(() -> {
                if (id == mapGeometry && mapImage == null) {
                    mapImage = got;
                    mapMemory.put(key, got);
                    invalidate();
                }
            });
        });
    }

    private GameState.MapImage loadMap(String key) {
        return loadImage(mapFile(key, ".png"), mapFile(key, ".txt"), mapFile(key, "-bd.png"));
    }

    private static GameState.MapImage loadImage(java.io.File png, java.io.File meta, java.io.File bdPng) {
        if (!png.isFile() || !meta.isFile()) {
            return null;
        }
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(meta))) {
            String[] v = r.readLine().trim().split(" ");
            android.graphics.Bitmap b = android.graphics.BitmapFactory.decodeFile(png.getPath());
            if (b == null || v.length < 3) {
                return null;
            }
            GameState.MapImage m = new GameState.MapImage(b, Float.parseFloat(v[0]), Float.parseFloat(v[1]), Float.parseFloat(v[2]));
            String line = r.readLine();   // the backdrop's place, when saved
            if (line != null && bdPng.isFile()) {
                String[] bv = line.trim().split(" ");
                android.graphics.Bitmap bb = android.graphics.BitmapFactory.decodeFile(bdPng.getPath());
                if (bb != null && bv.length >= 3) {
                    m.backdrop = new GameState.MapImage(bb, Float.parseFloat(bv[0]), Float.parseFloat(bv[1]), Float.parseFloat(bv[2]));
                }
            }
            return m;
        } catch (Exception e) {
            return null;
        }
    }

    /** Saves a map to the cache (on the map worker thread). */
    private void saveMap(int area, float cap, GameState.MapImage m) {
        String key = mapKey(area, cap);
        saveImage(m, mapFile(key, ".png"), mapFile(key, ".txt"), mapFile(key, "-bd.png"), android.graphics.Bitmap.CompressFormat.PNG, 100);
    }

    private static boolean saveImage(GameState.MapImage m, java.io.File png, java.io.File meta, java.io.File bdPng,
                                     android.graphics.Bitmap.CompressFormat format, int quality) {
        java.io.File tmp = new java.io.File(png.getPath() + ".tmp");
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(tmp)) {
            if (!m.bitmap.compress(format, quality, out)) {
                return false;
            }
        } catch (java.io.IOException e) {
            return false;
        }
        GameState.MapImage bd = m.backdrop;
        boolean bdSaved = false;
        if (bd != null) {
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(bdPng)) {
                bdSaved = bd.bitmap.compress(format, quality, out);
            } catch (java.io.IOException e) {
                bdSaved = false;
            }
        }
        try (java.io.FileWriter w = new java.io.FileWriter(meta)) {
            w.write(m.minX + " " + m.minZ + " " + m.scale + "\n");
            if (bdSaved) {
                w.write(bd.minX + " " + bd.minZ + " " + bd.scale + "\n");
            }
        } catch (java.io.IOException e) {
            return false;
        }
        return tmp.renameTo(png);
    }

    // ---- Parchment background (Map Background) ---------------------------------------------
    // The level on an old paper sheet: a grain texture and a faint grid that move with the map,
    // darker edges, an ink outline and shadow around the level (MapLayers.ink), and a compass.
    // All drawn here from code (noise and lines), no art.
    private boolean parchment = true;
    private android.graphics.Bitmap paperTile;
    private final Paint paperPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edgePaint = new Paint();
    private final android.graphics.Matrix paperMatrix = new android.graphics.Matrix();

    /** A 256 x 256 tile of paper grain that repeats without seams. */
    private static android.graphics.Bitmap makePaperTile() {
        final int n = 256;
        java.util.Random rnd = new java.util.Random(7);
        float[][] layers = new float[3][];
        int[] cells = { 8, 32, 128 };
        for (int l = 0; l < 3; l++) {
            int g = cells[l];
            float[] grid = new float[g * g];
            for (int i = 0; i < grid.length; i++) grid[i] = rnd.nextFloat();
            float[] v = new float[n * n];
            for (int y = 0; y < n; y++) {
                float fy = (float) y * g / n;
                int y0 = (int) fy;
                float ty = fy - y0;
                ty = ty * ty * (3 - 2 * ty);
                for (int x = 0; x < n; x++) {
                    float fx = (float) x * g / n;
                    int x0 = (int) fx;
                    float tx = fx - x0;
                    tx = tx * tx * (3 - 2 * tx);
                    float a = grid[y0 % g * g + x0 % g], b = grid[y0 % g * g + (x0 + 1) % g];
                    float cc = grid[(y0 + 1) % g * g + x0 % g], d = grid[(y0 + 1) % g * g + (x0 + 1) % g];
                    v[y * n + x] = (a + (b - a) * tx) * (1 - ty) + (cc + (d - cc) * tx) * ty;
                }
            }
            layers[l] = v;
        }
        int[] px = new int[n * n];
        for (int i = 0; i < px.length; i++) {
            float t = 0.55f * layers[0][i] + 0.3f * layers[1][i] + 0.15f * layers[2][i];
            if (rnd.nextFloat() < 0.004f) t -= 0.5f;   // a fleck
            float k = 0.86f + 0.2f * t;
            int r = Math.max(0, Math.min(255, Math.round(236 * k)));
            int g = Math.max(0, Math.min(255, Math.round(214 * k)));
            int b = Math.max(0, Math.min(255, Math.round(168 * k)));
            px[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
        }
        return android.graphics.Bitmap.createBitmap(px, n, n, android.graphics.Bitmap.Config.ARGB_8888);
    }

    private void drawParchment(Canvas c, RectF r) {
        if (paperTile == null) {
            paperTile = makePaperTile();
            paperPaint.setShader(new android.graphics.BitmapShader(paperTile,
                android.graphics.Shader.TileMode.REPEAT, android.graphics.Shader.TileMode.REPEAT));
        }
        float s = viewS > 0 && !Float.isNaN(viewS) ? viewS : 0.05f;
        // The paper moves with the map (its grain stays one size on screen).
        float ox = r.centerX() - viewCx * s, oz = r.centerY() - viewCz * s;
        float tile = 1.6f * u;   // grain size
        paperMatrix.setScale(tile / 8f, tile / 8f);
        paperMatrix.postTranslate(ox, oz);
        paperPaint.getShader().setLocalMatrix(paperMatrix);
        c.drawRect(r, paperPaint);
        // A faint grid on round world distances, about 12 units of screen apart.
        float step = 250f;
        while (step * s < 9f * u && step < 1e6f) step *= 2f;
        gridPaint.setColor(Color.argb(38, 110, 80, 45));
        gridPaint.setStrokeWidth(Math.max(1f, 0.14f * u));
        float x0 = viewCx + (r.left - r.centerX()) / s, x1 = viewCx + (r.right - r.centerX()) / s;
        float z0 = viewCz + (r.top - r.centerY()) / s, z1 = viewCz + (r.bottom - r.centerY()) / s;
        for (float x = (float) Math.ceil(x0 / step) * step; x <= x1; x += step) {
            float sx = r.centerX() + (x - viewCx) * s;
            c.drawLine(sx, r.top, sx, r.bottom, gridPaint);
        }
        for (float z = (float) Math.ceil(z0 / step) * step; z <= z1; z += step) {
            float sz = r.centerY() + (z - viewCz) * s;
            c.drawLine(r.left, sz, r.right, sz, gridPaint);
        }
        // Darker, burnt-looking edges.
        float e = 7f * u;
        int dark = Color.argb(120, 105, 66, 28), clear = Color.argb(0, 105, 66, 28);
        edgePaint.setShader(new android.graphics.LinearGradient(r.left, 0, r.left + e, 0, dark, clear, android.graphics.Shader.TileMode.CLAMP));
        c.drawRect(r.left, r.top, r.left + e, r.bottom, edgePaint);
        edgePaint.setShader(new android.graphics.LinearGradient(r.right, 0, r.right - e, 0, dark, clear, android.graphics.Shader.TileMode.CLAMP));
        c.drawRect(r.right - e, r.top, r.right, r.bottom, edgePaint);
        edgePaint.setShader(new android.graphics.LinearGradient(0, r.top, 0, r.top + e, dark, clear, android.graphics.Shader.TileMode.CLAMP));
        c.drawRect(r.left, r.top, r.right, r.top + e, edgePaint);
        edgePaint.setShader(new android.graphics.LinearGradient(0, r.bottom, 0, r.bottom - e, dark, clear, android.graphics.Shader.TileMode.CLAMP));
        c.drawRect(r.left, r.bottom - e, r.right, r.bottom, edgePaint);
        edgePaint.setShader(null);
    }

    /** A small compass rose in the map's top-right corner. */
    private void drawCompass(Canvas c, RectF r) {
        float cx = r.right - 6.5f * u, cy = r.top + 8f * u, l = 3.6f * u, w = 0.9f * u;
        Path p = new Path();
        fill.setColor(Color.argb(210, 150, 110, 70));
        p.moveTo(cx - l, cy); p.lineTo(cx, cy - w); p.lineTo(cx + l, cy); p.lineTo(cx, cy + w); p.close();
        c.drawPath(p, fill);
        p.reset();
        fill.setColor(Color.argb(230, 90, 55, 30));
        p.moveTo(cx, cy - l); p.lineTo(cx + w, cy); p.lineTo(cx, cy + l); p.lineTo(cx - w, cy); p.close();
        c.drawPath(p, fill);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(2.2f * u);
        text.setColor(Color.argb(230, 90, 55, 30));
        c.drawText("N", cx, cy - l - 0.6f * u, text);
    }

    /** Works out a picture's ink outline and shadow in the background (MapLayers.ink). */
    private void requestInk(GameState.MapImage m) {
        if (m.inkRequested) {
            return;
        }
        m.inkRequested = true;
        layerWorker.execute(() -> {
            try {
                int w = m.bitmap.getWidth(), h = m.bitmap.getHeight();
                int f = Math.max(1, (Math.max(w, h) + 1023) / 1024);   // at most 1024 across
                int sw = Math.max(1, w / f), sh = Math.max(1, h / f);
                android.graphics.Bitmap small = f == 1 ? m.bitmap : android.graphics.Bitmap.createScaledBitmap(m.bitmap, sw, sh, true);
                int[] px = new int[sw * sh];
                small.getPixels(px, 0, sw, 0, 0, sw, sh);
                for (int i = 0; i < px.length; i++) px[i] = px[i] >>> 24;
                int pad = Math.max(6, Math.round(Math.max(sw, sh) / 90f));
                int[] ink = MapLayers.ink(px, sw, sh, pad);
                float scale = m.scale * sw / w;
                android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(ink, sw + 2 * pad, sh + 2 * pad, android.graphics.Bitmap.Config.ARGB_8888);
                m.ink = new GameState.MapImage(b, m.minX - pad / scale, m.minZ - pad / scale, scale);
                post(this::invalidate);
            } catch (Throwable t) {
                android.util.Log.w("BTMap", "ink outline failed", t);
            }
        });
    }

    // ---- Lower floors, cut in locally ----------------------------------------------------
    // Under another floor (Gruntilda's hat, a bridge, a roof) the map shows the whole area as
    // usual, with only the part over Banjo cut away to the floor below: a storey map (everything
    // above a height left out) is compared with the whole-area map, and the patch where they
    // differ that Banjo is under is taken from the storey map, with soft edges. The rest of the
    // area (other high ground) stays as it is. Done on its own thread, so it never waits behind
    // a map build.
    private GameState.MapImage wholeImage, layerRaw;
    private LayerMask layerMask;
    private boolean layerBusy;
    private final java.util.concurrent.ExecutorService layerWorker = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        // On the slowest cores at low priority, like the map builder (0.9.45): it can draw a
        // whole storey from the ROM, and on the big cores that cost frames after area changes.
        Thread t = new Thread(() -> { GameState.backgroundThread(); r.run(); }, "BT map layer");
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    /** Where a composed lower floor was cut out, in the whole map's pixels. */
    private static final class LayerMask {
        final boolean[] cut;
        final int w, h;
        final float minX, minZ, scale;

        LayerMask(boolean[] cut, int w, int h, float minX, float minZ, float scale) {
            this.cut = cut;
            this.w = w;
            this.h = h;
            this.minX = minX;
            this.minZ = minZ;
            this.scale = scale;
        }

        /** Whether Banjo (x, y, z) is within the cut (or a few pixels from it). */
        boolean covers(float[] p) {
            if (p == null) return true;
            int px = (int) ((p[0] - minX) * scale), pz = (int) ((p[2] - minZ) * scale);
            int rad = Math.max(2, Math.round(60f * scale));
            for (int z = Math.max(0, pz - rad); z <= Math.min(h - 1, pz + rad); z++)
                for (int x = Math.max(0, px - rad); x <= Math.min(w - 1, px + rad); x++)
                    if (cut[z * w + x]) return true;
            return false;
        }
    }

    /**
     * Shows the floor below at cap: storey is its map (null: from the atlas, else built). Cut into
     * the whole-area map around Banjo when there is one.
     */
    private void requestLayer(int id, float cap, GameState.MapImage storey) {
        layerBusy = true;
        final float[] p = GameState.position();
        final GameState.MapImage whole = wholeImage;
        final int style = mapStyle;
        final int area = GameState.area();
        final float y = layerY, over = layerOver;
        layerWorker.execute(() -> {
            GameState.MapImage raw = storey;
            Object[] composed = null;
            try {
                if (raw == null) {
                    // The storey drawn from the ROM between his floor and what's over him.
                    int fp = GameState.currentFingerprint();
                    MapAtlas.Entry e = MapAtlas.lookup(fp);
                    String layer = e != null ? MapAtlas.storeyFor(e, y, over) : null;
                    raw = layer != null ? MapAtlas.load(fp, layer, style) : null;
                    if (raw == null && e != null) {
                        raw = MapAtlas.storeyAt(fp, cap, style);
                    }
                }
                composed = raw != null && whole != null && p != null ? composeLayer(whole, raw, p) : null;
            } catch (Throwable t) {
                // Out of memory or a bad file: show the storey map as it is rather than nothing,
                // and never leave layerBusy set (it would stop floor switching for good).
                android.util.Log.w("BTMap", "lower floor cut failed", t);
                composed = null;
            }
            final Object[] done = composed;
            final GameState.MapImage got = raw;
            post(() -> {
                layerBusy = false;
                if (id != mapGeometry) {
                    return;
                }
                if (got == null) {
                    buildMap(id, cap);   // not drawn yet: build it (comes back here)
                    return;
                }
                if (area >= 0) {
                    mapMemory.put(mapKey(area, cap), got);
                }
                layerRaw = got;
                mapCap = cap;
                if (done != null) {
                    mapImage = (GameState.MapImage) done[0];
                    layerMask = (LayerMask) done[1];
                } else if (whole != null && p != null) {
                    // Nothing over him differs on the map: the whole area already shows his floor.
                    mapImage = whole;
                    layerMask = null;
                } else {
                    mapImage = got;
                    layerMask = null;
                }
                invalidate();
            });
        });
    }

    /**
     * The whole map with the patch over p taken from the storey map: {MapImage, LayerMask}, or
     * null when the two maps are the same around p.
     */
    private static Object[] composeLayer(GameState.MapImage whole, GameState.MapImage storey, float[] p) {
        final int w = whole.bitmap.getWidth(), h = whole.bitmap.getHeight();
        final int sw = storey.bitmap.getWidth(), sh = storey.bitmap.getHeight();
        int[] a = new int[w * h];
        whole.bitmap.getPixels(a, 0, w, 0, 0, w, h);
        int[] sp = new int[sw * sh];
        storey.bitmap.getPixels(sp, 0, sw, 0, 0, sw, sh);
        boolean[] cut = MapLayers.compose(a, w, h, whole.minX, whole.minZ, whole.scale, sp, sw, sh, storey.minX, storey.minZ, storey.scale, p[0], p[2]);
        if (cut == null) {
            return null;
        }
        android.graphics.Bitmap out = android.graphics.Bitmap.createBitmap(a, w, h, android.graphics.Bitmap.Config.ARGB_8888);
        GameState.MapImage img = new GameState.MapImage(out, whole.minX, whole.minZ, whole.scale);
        img.backdrop = whole.backdrop;
        return new Object[] { img, new LayerMask(cut, w, h, whole.minX, whole.minZ, whole.scale) };
    }

    // ---- Maps drawn from the ROM (MapAtlas) ----------------------------------------------
    private boolean atlasStyle = true, styleSet;
    private int mapStyle = MapAtlas.STYLE_ATLAS;
    private int atlasSeen = -1;

    /** "Drawing maps n/N" under the map while the atlas is being drawn. */
    private void drawAtlasProgress(Canvas c, RectF r) {
        if (!MapAtlas.drawing()) {
            return;
        }
        text.setTextAlign(Paint.Align.RIGHT);
        text.setTextSize(2.4f * u);
        text.setColor(parchment && mapImage != null ? Color.argb(220, 78, 50, 28) : Color.argb(200, 255, 236, 170));
        c.drawText("Drawing maps " + MapAtlas.done() + "/" + MapAtlas.total(), r.right - 3f * u, r.bottom - 3.2f * u, text);
        text.setTextAlign(Paint.Align.CENTER);
    }

    // ---- Area names --------------------------------------------------------------------
    // The game writes the area's name when you enter it (not after loading a save). Each one
    // seen is remembered (by the game's map id) for next time.
    private String areaLabel;
    private int nameArea = GameState.UNKNOWN;
    private long nameAreaSince;
    private String nameBefore;

    // The name is learnt when the game writes a new one within 8 s of entering an area. Guards
    // (0.9.1): Jinjo Village was labelled Spiral Mountain and Spiral Mountain had no label. The
    // game writes the name a moment before the area id changes: loading a save from the title
    // screen (whose demo runs in Jinjo Village) wrote "SPIRAL MOUNTAIN" while the id still said
    // Jinjo Village, so it was learnt for the wrong area, and Spiral Mountain then saw no change.
    // Now a new name waits 1.5 s: if the area changes meanwhile, it belongs to the new area. A
    // name already known for another area is never taken, the compared name is the last valid
    // one seen, and names learnt before 0.9.1 were dropped (new preferences file).
    private String lastValidName;
    private String pendingName;
    private long pendingAt;

    private static final String AREA_NAMES = "area_names2";

    /** Areas whose names are known from the snapshots, until the game shows its own. */
    private static String builtInName(int area) {
        switch (area) {
            case 0xAF: return "SPIRAL MOUNTAIN";
            case 0x142: return "ISLE O' HAGS ( JINJO VILLAGE )";
            default: return null;
        }
    }

    private void refreshAreaName() {
        int area = GameState.area();
        long now = SystemClock.uptimeMillis();
        android.content.SharedPreferences names = getContext().getSharedPreferences(AREA_NAMES, android.content.Context.MODE_PRIVATE);
        String current = GameState.areaName();
        if (area != nameArea) {
            nameArea = area;
            nameAreaSince = now;
            String known = area >= 0 ? names.getString(Integer.toString(area), builtInName(area)) : null;
            setAreaName(known);
            // A name written just before the change is this area's.
            String fresh = pendingName != null && now - pendingAt < 1500 ? pendingName : null;
            pendingName = null;
            if (current != null) lastValidName = current;
            nameBefore = lastValidName;
            if (fresh != null && area >= 0) learnName(names, area, fresh);
            return;
        }
        if (current != null && !current.equals(lastValidName)) {
            pendingName = current;   // a new name: whose it is is decided in 1.5 s
            pendingAt = now;
        }
        if (current != null) {
            lastValidName = current;
        }
        if (pendingName != null && now - pendingAt >= 1500) {
            String name = pendingName;
            pendingName = null;
            if (area >= 0 && now - nameAreaSince <= 9500 && !name.equals(nameBefore)) {
                learnName(names, area, name);
            }
        }
    }

    private void learnName(android.content.SharedPreferences names, int area, String name) {
        if (namedElsewhere(names, area, name)) {
            return;
        }
        names.edit().putString(Integer.toString(area), name).apply();
        setAreaName(name);
        nameBefore = name;
    }

    /** Whether this name already belongs to a different area (learnt, or built in). */
    private static boolean namedElsewhere(android.content.SharedPreferences names, int area, String name) {
        for (java.util.Map.Entry<String, ?> e : names.getAll().entrySet()) {
            if (name.equals(e.getValue()) && !e.getKey().equals(Integer.toString(area))) {
                return true;
            }
        }
        for (int other : new int[] { 0xAF, 0x142 }) {
            if (other != area && name.equals(builtInName(other))) {
                return true;
            }
        }
        return false;
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
            drawMoveLabel(c, i, m, on, bw, area.height());
        }
    }

    // ---- The move buttons' pictures (the game's own sprites, from the player's ROM) -------
    // Fire Egg: a blue egg shot out in front of Kazooie; Lay Egg: out behind her; Talon Trot:
    // Kazooie; Wonderwing: the gold feather. Null (text only) until the sprites can be read.
    private final android.graphics.Bitmap[] moveIcons = new android.graphics.Bitmap[4];
    private android.graphics.Bitmap talonFallback;

    private android.graphics.Bitmap moveIcon(int i) {
        if (i < 0 || i >= moveIcons.length) return null;
        if (moveIcons[i] != null) return moveIcons[i];
        android.graphics.Bitmap kazooie = GameState.sprite(0x0B8B), egg = GameState.sprite(0x0B81), gold = GameState.sprite(0x0B4B);
        android.graphics.Bitmap made = null;
        if (i == 2) {
            // The orange bird foot from the shock jump pad's picture (model 1589), cut out; until
            // that's drawn (a moment, in the background), Kazooie's head.
            android.graphics.Bitmap pad = GameState.modelIcon(1589, 384, () -> post(() -> {
                moveIcons[2] = null;
                invalidate();
            }));
            if (pad != null) {
                int w = pad.getWidth(), h = pad.getHeight();
                int[] px = new int[w * h];
                pad.getPixels(px, 0, w, 0, 0, w, h);
                int[] foot = MapLayers.footCutout(px, w, h);
                if (foot != null) {
                    made = android.graphics.Bitmap.createBitmap(foot, 2, foot[0], foot[0], foot[1], android.graphics.Bitmap.Config.ARGB_8888);
                }
            }
            if (made == null) {
                if (talonFallback == null && kazooie != null) talonFallback = trimmed(kazooie);
                if (pad != null) {
                    moveIcons[2] = talonFallback;   // the cut-out failed: don't redo it every frame
                }
                return talonFallback;   // until the pad is drawn, the foot is tried again
            }
        } else if (i == 3 && gold != null) {
            made = trimmed(gold);
        } else if ((i == 0 || i == 1) && egg != null) {
            // The blue egg flying, with a trail of the game's sparkle stars (170 x 100):
            // Fire Egg high on the right, tipped forward, at the end of gold and orange stars
            // climbing up and forward, a warm glow behind it; Lay Egg low on the left, tipped
            // back, blue and white stars arcing down to it from behind, a cool glow.
            boolean forward = i == 0;
            android.graphics.Bitmap goldStar = GameState.sprite(0x09E2), orange = GameState.sprite(0x09E3);
            android.graphics.Bitmap blue = GameState.sprite(0x09E5), white = GameState.sprite(0x09E0);
            made = android.graphics.Bitmap.createBitmap(170, 100, android.graphics.Bitmap.Config.ARGB_8888);
            Canvas k = new Canvas(made);
            Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
            float gx = forward ? 131 : 42, gy = forward ? 43 : 58;
            int glow = forward ? Color.argb(130, 255, 190, 60) : Color.argb(120, 120, 190, 255);
            p.setShader(new RadialGradient(gx, gy, 46, glow, glow & 0x00FFFFFF, Shader.TileMode.CLAMP));
            k.drawCircle(gx, gy, 46, p);
            p.setShader(null);
            float[][] trail = forward
                ? new float[][] { { 100, 54, 38, 1f, 0 }, { 72, 66, 30, 0.9f, 1 }, { 48, 76, 24, 0.75f, 0 }, { 28, 84, 18, 0.6f, 1 }, { 12, 90, 13, 0.45f, 0 } }
                : new float[][] { { 74, 42, 36, 1f, 0 }, { 100, 26, 30, 0.9f, 1 }, { 124, 16, 24, 0.75f, 0 }, { 146, 12, 18, 0.6f, 1 }, { 162, 14, 13, 0.45f, 0 } };
            for (float[] t : trail) {
                android.graphics.Bitmap star = forward ? (t[4] == 0 ? goldStar : orange) : (t[4] == 0 ? blue : white);
                if (star != null) putSprite(k, trimmed(star), t[0], t[1], t[2], 0f, t[3], p);
            }
            putSprite(k, trimmed(egg), gx, gy - (forward ? 3 : -2), forward ? 52 : 50, forward ? 40f : -40f, 1f, p);
        }
        moveIcons[i] = made;
        return made;
    }

    /** b drawn centred at (cx, cy), its longer side `size`, turned `degrees` (clockwise). */
    private static void putSprite(Canvas k, android.graphics.Bitmap b, float cx, float cy, float size, float degrees, float alpha, Paint p) {
        float s = size / Math.max(b.getWidth(), b.getHeight());
        float w = b.getWidth() * s, h = b.getHeight() * s;
        k.save();
        k.rotate(degrees, cx, cy);
        p.setAlpha(Math.round(255 * alpha));
        k.drawBitmap(b, null, new RectF(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2), p);
        p.setAlpha(255);
        k.restore();
    }

    /** The sprite cropped to what's drawn in it. */
    private static android.graphics.Bitmap trimmed(android.graphics.Bitmap b) {
        int w = b.getWidth(), h = b.getHeight();
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        int x0 = w, y0 = h, x1 = -1, y1 = -1;
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                if ((px[y * w + x] >>> 24) > 16) { x0 = Math.min(x0, x); x1 = Math.max(x1, x); y0 = Math.min(y0, y); y1 = Math.max(y1, y); }
        if (x1 < x0) return b;
        return android.graphics.Bitmap.createBitmap(b, x0, y0, x1 - x0 + 1, y1 - y0 + 1);
    }

    private final RectF moveIconRect = new RectF();

    /** A move button's picture (left) and name and keys; text only when there's no room. */
    private void drawMoveLabel(Canvas c, int i, Move m, boolean on, float bw, float bh) {
        android.graphics.Bitmap icon = moveIcon(i);
        float iconH = bh * 0.72f;
        float iconW = icon == null ? 0 : iconH * icon.getWidth() / icon.getHeight();
        if (icon != null && bw - iconW - 3 * u < bw * 0.45f) {
            icon = null;   // too narrow: text only
        }
        if (icon == null) {
            fitText(m.name, bw * 0.86f, Math.min(3.6f * u, bh * 0.32f));
            text.setColor(on ? HONEY : CREAM);
            drawCentered(c, m.name, m.rect.centerX(), m.rect.centerY() - bh * 0.12f);
            fitText(m.keys, bw * 0.86f, Math.min(2.6f * u, bh * 0.22f));
            text.setColor(TAN);
            drawCentered(c, m.keys, m.rect.centerX(), m.rect.centerY() + bh * 0.24f);
            return;
        }
        float left = m.rect.left + bh * 0.16f;
        moveIconRect.set(left, m.rect.centerY() - iconH / 2, left + iconW, m.rect.centerY() + iconH / 2);
        spritePaint.setAlpha(on ? 255 : 235);
        c.drawBitmap(icon, null, moveIconRect, spritePaint);
        spritePaint.setAlpha(255);
        float tx = moveIconRect.right + 1.6f * u, room = m.rect.right - tx - 1.6f * u;
        text.setTextAlign(Paint.Align.LEFT);
        fitText(m.name, room, Math.min(3.6f * u, bh * 0.32f));
        text.setColor(on ? HONEY : CREAM);
        c.drawText(m.name, tx, m.rect.centerY() - bh * 0.12f - (text.descent() + text.ascent()) / 2f, text);
        fitText(m.keys, room, Math.min(2.6f * u, bh * 0.22f));
        text.setColor(TAN);
        c.drawText(m.keys, tx, m.rect.centerY() + bh * 0.22f - (text.descent() + text.ascent()) / 2f, text);
        text.setTextAlign(Paint.Align.CENTER);
    }

    // Hold Z animation: each button takes BUTTON_IN_MS to come in, the next one starting
    // BUTTON_STAGGER_MS later (top first), with a slight overshoot; leaving runs it backwards.
    private static final float BUTTON_IN_MS = 230f, BUTTON_STAGGER_MS = 60f;

    /** How far button i is in (0 out .. 1 in place, overshooting a little on the way). */
    private float buttonIn(int i) {
        float total = BUTTON_IN_MS + BUTTON_STAGGER_MS * (moves.length - 1);
        float t = (buttonSlide * total - i * BUTTON_STAGGER_MS) / BUTTON_IN_MS;
        t = Math.max(0f, Math.min(1f, t));
        float k = 1.5f;   // overshoot (ease-out-back): about 7% past, then settles
        float q = t - 1f;
        return 1f + (k + 1f) * q * q * q + k * q * q;
    }

    /** The move buttons stacked top to bottom (Hold Z), each slid right by its own progress. */
    private void drawMovesColumn(Canvas c, RectF area, float travel) {
        float gap = 1.5f * u;
        float bh = (area.height() - gap * (moves.length - 1)) / moves.length;
        float bw = area.width();
        for (int i = 0; i < moves.length; i++) {
            Move m = moves[i];
            m.rect.set(area.left, area.top + i * (bh + gap), area.right, area.top + i * (bh + gap) + bh);
            float in = buttonIn(i);
            if (in <= 0f) {
                continue;
            }
            c.save();
            c.translate((1f - in) * travel, 0f);   // the touch area stays where it ends up
            boolean on = m.down || m.lit || m == tapping;
            float rad = Math.min(bh, bw) * 0.3f;
            fill.setColor(on ? Color.argb(240, 150, 96, 30) : WOOD);
            c.drawRoundRect(m.rect, rad, rad, fill);
            stroke.setColor(on ? HONEY : WOOD_EDGE);
            stroke.setStrokeWidth((on ? 0.6f : 0.3f) * u);
            c.drawRoundRect(m.rect, rad, rad, stroke);
            drawMoveLabel(c, i, m, on, bw, bh);
            c.restore();
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
        // Hold Z: while the buttons are out, they get the touches and the map ignores them.
        boolean buttonsOut = slideButtons && (zHeld || buttonSlide > 0.05f);
        if (buttonsOut && mapTouching) {
            mapTouching = false;   // Z came on mid-gesture: drop it
            handler.removeCallbacks(longPress);
        }
        // The map's own buttons: + and -, Recenter.
        if (!bookOpen && !buttonsOut && mapImage != null && action == MotionEvent.ACTION_DOWN) {
            if (zoomInRect.contains(x, y)) { zoom(1); return true; }
            if (zoomOutRect.contains(x, y)) { zoom(-1); return true; }
            if (notesRect.contains(x, y)) {
                notesShown = !notesShown;
                try {
                    getContext().getSharedPreferences("map_layers", android.content.Context.MODE_PRIVATE).edit().putBoolean("notes", notesShown).apply();
                } catch (RuntimeException ignored) {
                    // not kept
                }
                Haptics.tap(this);
                invalidate();
                return true;
            }
            if (!recenterRect.isEmpty() && recenterRect.contains(x, y)) { recenter(); return true; }
        }
        // The map: pinch zooms; two fingers drag the view (then one finger does, until Recenter);
        // press and hold drops or removes a marker.
        if (!bookOpen && !buttonsOut && mapImage != null && (mapTouching || (action == MotionEvent.ACTION_DOWN && mapRect.contains(x, y)))) {
            pinch.onTouchEvent(e);
            int pointers = e.getPointerCount();
            if (action == MotionEvent.ACTION_POINTER_UP) {
                pointers--;   // the lifted finger still counts in this event
            }
            float sx = 0, sy = 0;
            int counted = 0;
            for (int i = 0; i < e.getPointerCount(); i++) {
                if (action == MotionEvent.ACTION_POINTER_UP && i == index) continue;
                sx += e.getX(i);
                sy += e.getY(i);
                counted++;
            }
            if (counted > 0) { sx /= counted; sy /= counted; }
            switch (action) {
                case MotionEvent.ACTION_DOWN:
                    mapTouching = true;
                    mapPinched = false;
                    mapTouchAt = SystemClock.uptimeMillis();
                    downX = x;
                    downY = y;
                    downMoved = false;
                    dragX = sx;
                    dragY = sy;
                    dragPointers = pointers;
                    handler.postDelayed(longPress, 450);
                    break;
                case MotionEvent.ACTION_POINTER_DOWN:
                case MotionEvent.ACTION_POINTER_UP:
                    downMoved = true;   // more than one finger: not a press-and-hold
                    handler.removeCallbacks(longPress);
                    dragX = sx;         // the centre moved: start the drag again from here
                    dragY = sy;
                    dragPointers = pointers;
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (Math.hypot(x - downX, y - downY) > 2 * u) {
                        downMoved = true;
                        handler.removeCallbacks(longPress);
                    }
                    if (!Float.isNaN(dragX) && (pointers >= 2 || panned) && viewS > 0) {
                        float dx = sx - dragX, dy = sy - dragY;
                        if (pointers >= 2 || dx * dx + dy * dy > 0) {
                            recentering = false;
                            panX -= dx / viewS;
                            panZ -= dy / viewS;
                            if (Math.abs(panX) + Math.abs(panZ) > 1f) {
                                panned = true;
                            }
                            invalidate();
                        }
                    }
                    dragX = sx;
                    dragY = sy;
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    handler.removeCallbacks(longPress);
                    if (action == MotionEvent.ACTION_UP && !downMoved && !mapPinched
                            && SystemClock.uptimeMillis() - mapTouchAt < 400) {
                        tapIcon(x, y);   // a short tap on an icon: its name
                    }
                    mapTouching = false;
                    dragX = dragY = Float.NaN;
                    break;
                default:
                    break;
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
            if (showButtons && frame >= 0 && (!slideButtons || buttonsOut)) {
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

    /** Moves the Hold Z slide toward where it should be; keeps drawing until it's there. */
    private void stepSlide() {
        long now = android.os.SystemClock.uptimeMillis();
        float dt = buttonSlideAt == 0 ? 0f : Math.min(0.05f, (now - buttonSlideAt) / BUTTON_SLIDE_MS);   // at most about a frame's step
        buttonSlideAt = now;
        float target = !slideButtons || zHeld ? 1f : 0f;
        if (buttonSlide < target) {
            buttonSlide = Math.min(target, buttonSlide + dt);
        } else if (buttonSlide > target) {
            buttonSlide = Math.max(target, buttonSlide - dt);
        }
        if (buttonSlide != target) {
            postInvalidateOnAnimation();
        }
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
