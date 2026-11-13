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

        float[] here = GameState.position();
        GameState.MapImage m = mapImage;
        int saved = c.save();
        c.clipPath(mapClip);
        c.drawColor(Color.BLACK);

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
            if (old == null) {
                drawLoading(c, r, here == null ? "Waiting for the game" : "Drawing the map", now);
                animating = true;
            }
        } else {
            if (m != fadingInFor) {   // a new picture: fade it in (an area's first one from black)
                fadingInFor = m;
                fadeInAt = now;
                fadeFrom = shownBefore ? 1f : 0f;
                shownBefore = true;
            }
            float fadeIn = Math.min(1f, fadeFrom + (now - fadeInAt) / 400f);
            if (fadeIn < 1f) animating = true;
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
            if (fadeIn >= 1f) {
                drawShimmer(c, r, m, cx, cz, viewScale);
            }

            // Where you came in (each entrance used in this area this session; the latest brighter).
            float iconScale = 0.75f + 0.25f * (1 - bl);
            java.util.List<float[]> entries = entrances.get(GameState.area());
            if (entries != null) {
                for (int i = 0; i < entries.size(); i++) {
                    float[] e = entries.get(i);
                    drawEntrance(c, r.centerX() + (e[0] - cx) * viewScale, r.centerY() + (e[2] - cz) * viewScale,
                        2.2f * iconScale * u, i == entries.size() - 1);
                }
            }
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
            if (mapCap < GameState.WHOLE) {
                text.setTextAlign(Paint.Align.LEFT);
                text.setTextSize(2.6f * u);
                text.setColor(Color.argb(220, 255, 236, 170));
                c.drawText("Lower floor", r.left + 11.5f * u, r.bottom - 3.2f * u, text);
                text.setTextAlign(Paint.Align.CENTER);
            }
        }
        if (drawBanner(c, frame, now)) animating = true;
        if (animating) {
            postInvalidateOnAnimation();
        }
    }

    // ---- The look: frame, banner, loading, player marker --------------------------------

    private long lastMapDraw;
    private float shownSpan = Float.NaN, wholeBlend = 0f;   // the view as shown, easing towards followSpan / mapWhole
    private boolean recentering;
    private GameState.MapImage leavingMap, fadingInFor;
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
    private void hexButton(Canvas c, float x, float y, float r, boolean plus) {
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
        Thread t = new Thread(r, "BT map detail");
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    private void updateDetail(GameState.MapImage m, RectF r, float cx, float cz, float viewScale) {
        if (m.key < 0 || viewScale < m.scale * 1.25f) {
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
            detail = null;
            panX = panZ = 0f;
            panned = false;
            recentering = false;
            wantedCap = GameState.WHOLE;
            mapCap = GameState.WHOLE;
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
                boolean under = GameState.floorAbove(p[0], p[1], p[2]);
                // Round the cap so small height changes on one storey don't redraw the map.
                wantedCap = under ? (float) (Math.ceil((p[1] + 250f) / 500f) * 500f) : GameState.WHOLE;
            }
        }
        if (mapBuilding) {
            return;
        }
        if (wantedCap != mapCap) {
            if (area >= 0) {
                GameState.MapImage saved = mapMemory.get(mapKey(area, wantedCap));
                if (saved != null) {   // a storey seen before: switch at once, refresh behind it
                    mapImage = saved;
                    mapCap = wantedCap;
                    invalidate();
                }
            }
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
            int areaBefore = GameState.area();
            GameState.MapImage built = GameState.buildMap(2048, cap);
            int areaAfter = GameState.area();
            if (built != null && areaBefore >= 0 && areaBefore == areaAfter) {
                saveMap(areaBefore, cap, built);
            }
            post(() -> {
                mapBuilding = false;
                if (id == mapGeometry && built != null) {
                    mapImage = built;
                    mapCap = cap;
                    if (areaBefore >= 0 && areaBefore == areaAfter) {
                        mapMemory.put(mapKey(areaBefore, cap), built);
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
    private static final String MAP_CACHE_VERSION = "v8";
    private int cacheTried = Integer.MIN_VALUE;
    private final java.util.LinkedHashMap<String, GameState.MapImage> mapMemory =
        new java.util.LinkedHashMap<String, GameState.MapImage>(8, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(java.util.Map.Entry<String, GameState.MapImage> eldest) {
                return size() > 4;
            }
        };

    private static String mapKey(int area, float cap) {
        return MAP_CACHE_VERSION + "-" + Integer.toHexString(area) + "-" + (cap >= GameState.WHOLE ? "all" : Integer.toString(Math.round(cap)));
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
        mapWorker.execute(() -> {
            GameState.MapImage loaded = loadMap(key);
            if (loaded == null) {
                return;
            }
            post(() -> {
                if (id == mapGeometry && mapImage == null) {
                    mapImage = loaded;
                    mapMemory.put(key, loaded);
                    invalidate();
                }
            });
        });
    }

    private GameState.MapImage loadMap(String key) {
        java.io.File png = mapFile(key, ".png"), meta = mapFile(key, ".txt");
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
            java.io.File bdPng = mapFile(key, "-bd.png");
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
        java.io.File png = mapFile(key, ".png"), meta = mapFile(key, ".txt");
        java.io.File tmp = mapFile(key, ".png.tmp");
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(tmp)) {
            if (!m.bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)) {
                return;
            }
        } catch (java.io.IOException e) {
            return;
        }
        GameState.MapImage bd = m.backdrop;
        boolean bdSaved = false;
        if (bd != null) {
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(mapFile(key, "-bd.png"))) {
                bdSaved = bd.bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
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
            return;
        }
        //noinspection ResultOfMethodCallIgnored
        tmp.renameTo(png);
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
            fitText(m.name, bw * 0.88f, Math.min(3.6f * u, area.height() * 0.3f));
            text.setColor(on ? HONEY : CREAM);
            drawCentered(c, m.name, m.rect.centerX(), m.rect.centerY() - area.height() * 0.12f);
            fitText(m.keys, bw * 0.88f, Math.min(2.6f * u, area.height() * 0.22f));
            text.setColor(TAN);
            drawCentered(c, m.keys, m.rect.centerX(), m.rect.centerY() + area.height() * 0.22f);
        }
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
            fitText(m.name, bw * 0.86f, Math.min(3.6f * u, bh * 0.32f));
            text.setColor(on ? HONEY : CREAM);
            drawCentered(c, m.name, m.rect.centerX(), m.rect.centerY() - bh * 0.12f);
            fitText(m.keys, bw * 0.86f, Math.min(2.6f * u, bh * 0.22f));
            text.setColor(TAN);
            drawCentered(c, m.keys, m.rect.centerX(), m.rect.centerY() + bh * 0.24f);
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
