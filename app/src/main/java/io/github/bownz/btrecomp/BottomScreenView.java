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

import java.util.ArrayList;
import java.util.List;

/**
 * The bottom screen for Banjo-Tooie on a second display (e.g. the AYN Thor's lower screen).
 *
 * <ul>
 *   <li>The current character (Banjo &amp; Kazooie, Banjo or Kazooie on their own, Mumbo, or a
 *       Humba Wumba transformation).</li>
 *   <li>Collectibles per world: Jiggies, notes, Glowbos, Jinjos, Cheato pages and honeycombs,
 *       plus the eggs and feathers carried. A tab per world, and the totals.</li>
 *   <li>A column of one-tap buttons for moves that need button combinations (Bottom Screen
 *       Buttons setting).</li>
 *   <li>It dims during cutscenes, and shows the totals while the game is paused.</li>
 * </ul>
 *
 * The game state comes from {@link GameState}, which reads the game's memory and only
 * reports values whose addresses have been verified in-game. Until then a value shows as a
 * dash. A temporary Mark button logs what changed in memory, to find them.
 *
 * All drawing is vector shapes made here; no game art is used.
 */
public final class BottomScreenView extends View {

    // ---- Colours (honey and wood) ----
    private static final int BG_TOP = Color.rgb(34, 24, 46);
    private static final int BG_BOTTOM = Color.rgb(16, 12, 22);
    private static final int HONEY = Color.rgb(255, 186, 40);
    private static final int HONEY_DARK = Color.rgb(196, 122, 18);
    private static final int WOOD = Color.argb(225, 74, 46, 22);
    private static final int WOOD_LIGHT = Color.argb(240, 104, 66, 30);
    private static final int WOOD_EDGE = Color.argb(160, 186, 140, 76);
    private static final int CREAM = Color.rgb(255, 244, 214);
    private static final int TAN = Color.rgb(232, 213, 168);

    /** A move done by pressing N64 buttons in a short sequence. */
    private static final class Move {
        final String name, keys;
        final int[][] steps;      // {buttons, milliseconds}, played in order on a tap
        final int holdButtons;    // non-zero: held while the finger stays down instead
        final boolean toggle;     // drawn lit after use until tapped again
        RectF rect = new RectF();
        boolean down, lit;

        Move(String name, String keys, int[][] steps) {
            this(name, keys, steps, 0, false);
        }

        Move(String name, String keys, int[][] steps, int holdButtons, boolean toggle) {
            this.name = name; this.keys = keys; this.steps = steps;
            this.holdButtons = holdButtons; this.toggle = toggle;
        }
    }

    private static final int A = TouchInput.N64_A, B = TouchInput.N64_B, Z = TouchInput.N64_Z;
    private static final int CU = TouchInput.N64_C_UP, CD = TouchInput.N64_C_DOWN;
    private static final int CL = TouchInput.N64_C_LEFT, CR = TouchInput.N64_C_RIGHT;

    private final List<Move> moves = new ArrayList<>();
    {
        // Crouch first (Z), then the combination, then let go.
        moves.add(new Move("Shoot Egg", "Z + C▲", new int[][] { { Z, 90 }, { Z | CU, 130 }, { 0, 0 } }));
        moves.add(new Move("Lay Egg", "Z + C▼", new int[][] { { Z, 90 }, { Z | CD, 130 }, { 0, 0 } }));
        moves.add(new Move("Talon Trot", "Z + C◀", new int[][] { { Z, 90 }, { Z | CL, 130 }, { 0, 0 } }, 0, true));
        moves.add(new Move("Wonderwing", "hold: Z + C▶", null, Z | CR, false));
        moves.add(new Move("Flap Flip", "Z, then A", new int[][] { { Z, 120 }, { Z | A, 160 }, { 0, 0 } }));
        moves.add(new Move("Beak Barge", "Z, then B", new int[][] { { Z, 110 }, { Z | B, 140 }, { 0, 0 } }));
        moves.add(new Move("Beak Bust", "A, then Z", new int[][] { { A, 140 }, { 0, 200 }, { Z, 160 }, { 0, 0 } }));
        moves.add(new Move("First Person", "C▲", new int[][] { { CU, 130 }, { 0, 0 } }));
    }

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean active;

    // State, refreshed by poll().
    private int frame = -1;
    private int character = GameState.UNKNOWN;
    private int world = GameState.UNKNOWN;
    private boolean paused, cutscene;
    private boolean showButtons = true, dimCutscenes = true;
    private float dim = 0f;               // 0 .. 1, eased toward the target
    private long dimAt;

    /** The tab being shown: -1 totals, otherwise a world. Follows the current world unless picked. */
    private int tab = -1;
    private boolean tabPicked;
    private final RectF[] tabRects = new RectF[GameState.WORLDS.length + 1];

    // TEMPORARY: the probe's Mark button.
    private final RectF markRect = new RectF();
    private int marks;
    private long markFlashAt;

    // A move being played: its steps, which one, and when the current one ends.
    private Move playing;
    private int playingStep;
    private int heldFromMove;   // buttons held by a hold move

    private float u = 1f;       // layout unit: 1% of the shorter side

    public BottomScreenView(Context context) {
        super(context);
        for (int i = 0; i < tabRects.length; i++) {
            tabRects[i] = new RectF();
        }
        stroke.setStyle(Paint.Style.STROKE);
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
        handler.removeCallbacks(stepTask);
        playing = null;
        heldFromMove = 0;
        for (Move m : moves) {
            m.down = false;
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
            handler.postDelayed(this, 100);
        }
    };

    private void poll() {
        frame = GameState.frameCount();
        character = GameState.character();
        int newWorld = GameState.world();
        if (newWorld != world) {
            world = newWorld;
            if (!tabPicked) {
                tab = world;
            }
        }
        boolean wasPaused = paused;
        paused = GameState.paused();
        if (paused && !wasPaused) {
            tab = -1;   // the pause screen: totals
            tabPicked = false;
        }
        cutscene = GameState.cutscene();
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
        drawHoneycombBackdrop(c, w, h);

        float buttonsW = showButtons ? Math.max(w * 0.30f, 22 * u) : 0f;
        float left = 3 * u, right = w - buttonsW - 3 * u;
        float top = 3 * u;

        float headerH = 16 * u;
        drawHeader(c, new RectF(left, top, right, top + headerH));
        float tabsTop = top + headerH + 2 * u;
        float tabsBottom = drawTabs(c, left, tabsTop, right);
        float markH = 9 * u;
        RectF board = new RectF(left, tabsBottom + 2 * u, right, h - markH - 5 * u);
        drawBoard(c, board);
        drawMark(c, new RectF(left, h - markH - 2 * u, right, h - 2 * u));
        if (showButtons) {
            drawMoves(c, new RectF(w - buttonsW, top, w - 3 * u, h - 3 * u));
        }
        drawDim(c, w, h);
    }

    private void drawHoneycombBackdrop(Canvas c, int w, int h) {
        float r = 7 * u;
        float dx = r * 1.732f, dy = r * 1.5f;
        stroke.setColor(Color.argb(18, 255, 186, 40));
        stroke.setStrokeWidth(Math.max(1f, 0.35f * u));
        int row = 0;
        for (float y = -r; y < h + r; y += dy, row++) {
            for (float x = (row % 2 == 0 ? 0 : dx / 2) - dx; x < w + dx; x += dx) {
                hexagon(path, x, y, r);
                c.drawPath(path, stroke);
            }
        }
    }

    private void drawHeader(Canvas c, RectF r) {
        panel(c, r, false);
        float badgeR = r.height() * 0.38f;
        float bx = r.left + r.height() * 0.5f, by = r.centerY();
        // The character's badge: a honeycomb with the initials.
        hexagon(path, bx, by, badgeR);
        fill.setColor(HONEY);
        c.drawPath(path, fill);
        stroke.setColor(HONEY_DARK);
        stroke.setStrokeWidth(0.6f * u);
        c.drawPath(path, stroke);
        String name = character >= 0 && character < GameState.CHARACTERS.length ? GameState.CHARACTERS[character] : null;
        text.setColor(Color.rgb(70, 40, 10));
        text.setTextSize(badgeR * 0.7f);
        drawCentered(c, initials(name), bx, by);

        text.setTextAlign(Paint.Align.LEFT);
        text.setColor(CREAM);
        float tx = bx + badgeR + 3 * u;
        text.setTextSize(5.2f * u);
        fitText(name != null ? name : "Banjo-Tooie", r.right - tx - 2 * u, 5.2f * u);
        c.drawText(name != null ? name : "Banjo-Tooie", tx, r.centerY() - 0.6f * u, text);
        text.setTextSize(3.4f * u);
        text.setColor(TAN);
        String status = frame < 0 ? "Waiting for the game to start"
            : paused ? "Paused"
            : world >= 0 ? GameState.WORLDS[world]
            : "Playing";
        c.drawText(status, tx, r.centerY() + 4.4f * u, text);
        text.setTextAlign(Paint.Align.CENTER);
    }

    private static String initials(String name) {
        if (name == null) {
            return "BT";
        }
        StringBuilder b = new StringBuilder();
        for (String part : name.split("[ &-]+")) {
            if (!part.isEmpty() && b.length() < 2) {
                b.append(Character.toUpperCase(part.charAt(0)));
            }
        }
        return b.toString();
    }

    /** The world tabs, wrapped onto as many rows as needed. Returns the bottom edge. */
    private float drawTabs(Canvas c, float left, float top, float right) {
        float chipH = 6.6f * u, gap = 1.4f * u;
        float x = left, y = top;
        text.setTextSize(3.0f * u);
        for (int i = -1; i < GameState.WORLDS.length; i++) {
            String label = i < 0 ? "Totals" : shortWorld(i);
            float chipW = text.measureText(label) + 5 * u;
            if (x + chipW > right && x > left) {
                x = left;
                y += chipH + gap;
            }
            RectF rect = tabRects[i + 1];
            rect.set(x, y, x + chipW, y + chipH);
            boolean selected = i == tab;
            boolean here = i >= 0 && i == world;
            fill.setColor(selected ? WOOD_LIGHT : WOOD);
            c.drawRoundRect(rect, chipH / 2, chipH / 2, fill);
            stroke.setColor(selected ? HONEY : here ? TAN : WOOD_EDGE);
            stroke.setStrokeWidth((selected ? 0.5f : 0.25f) * u);
            c.drawRoundRect(rect, chipH / 2, chipH / 2, stroke);
            text.setColor(selected ? HONEY : CREAM);
            drawCentered(c, label, rect.centerX(), rect.centerY());
            x += chipW + gap;
        }
        return y + chipH;
    }

    private static String shortWorld(int i) {
        switch (i) {
            case 0: return "Isle o' Hags";
            case 1: return "Mayahem";
            case 2: return "Glitter Gulch";
            case 3: return "Witchyworld";
            case 4: return "Jolly Roger's";
            case 5: return "Terrydactyland";
            case 6: return "Grunty Ind.";
            case 7: return "Hailfire";
            case 8: return "Cloud Cuckoo";
            default: return "Cauldron Keep";
        }
    }

    /** The collectibles: a 3 x 2 grid for the tab, then what's carried. */
    private void drawBoard(Canvas c, RectF r) {
        panel(c, r, true);
        float pad = 2.4f * u;
        text.setTextSize(3.6f * u);
        text.setColor(HONEY);
        String title = tab < 0 ? (paused ? "Paused · Totals" : "Totals") : GameState.WORLDS[tab];
        c.drawText(title, r.centerX(), r.top + pad + 3.6f * u, text);

        float gridTop = r.top + pad + 6.5f * u;
        float carryH = r.height() * 0.22f;
        float gridBottom = r.bottom - carryH - pad;
        int cols = 3, rows = 2;
        float cellW = (r.width() - pad * 2) / cols, cellH = (gridBottom - gridTop) / rows;
        int[] items = { GameState.JIGGIES, GameState.NOTES, GameState.GLOWBOS, GameState.JINJOS,
            GameState.CHEATO_PAGES, GameState.HONEYCOMBS };
        String[] names = { "Jiggies", "Notes", "Glowbos", "Jinjos", "Cheato Pages", "Honeycombs" };
        for (int i = 0; i < items.length; i++) {
            float cx = r.left + pad + cellW * (i % cols + 0.5f);
            float cy = gridTop + cellH * (i / cols + 0.5f);
            tile(c, cx, cy, Math.min(cellW, cellH), items[i], names[i], GameState.count(items[i], tab));
        }

        // Carried: eggs and feathers.
        float cy = r.bottom - pad - carryH / 2;
        float half = (r.width() - pad * 2) / 2;
        tile(c, r.left + pad + half * 0.5f, cy, Math.min(half, carryH * 1.6f), GameState.EGGS, "Eggs",
            GameState.count(GameState.EGGS, -1));
        tile(c, r.left + pad + half * 1.5f, cy, Math.min(half, carryH * 1.6f), GameState.FEATHERS, "Feathers",
            GameState.count(GameState.FEATHERS, -1));
    }

    private void tile(Canvas c, float cx, float cy, float size, int item, String name, int value) {
        float iconR = size * 0.2f;
        float iconY = cy - size * 0.14f;
        icon(c, item, cx, iconY, iconR);
        text.setColor(CREAM);
        text.setTextSize(Math.min(6.5f * u, size * 0.2f));
        drawCentered(c, value >= 0 ? Integer.toString(value) : "–", cx, cy + size * 0.18f);
        text.setColor(TAN);
        text.setTextSize(Math.min(3.0f * u, size * 0.1f));
        drawCentered(c, name, cx, cy + size * 0.36f);
    }

    /** Simple drawings for each collectible (not the game's art). */
    private void icon(Canvas c, int item, float x, float y, float r) {
        stroke.setStrokeWidth(Math.max(1.5f, r * 0.1f));
        switch (item) {
            case GameState.JIGGIES: {
                // A puzzle piece: a square with a knob on top and on the right.
                float s = r * 0.75f;
                fill.setColor(HONEY);
                c.drawRect(x - s, y - s, x + s, y + s, fill);
                c.drawCircle(x, y - s, s * 0.38f, fill);
                c.drawCircle(x + s, y, s * 0.38f, fill);
                fill.setColor(BG_BOTTOM);
                c.drawCircle(x - s, y, s * 0.3f, fill);
                c.drawCircle(x, y + s, s * 0.3f, fill);
                break;
            }
            case GameState.NOTES:
                text.setColor(HONEY);
                text.setTextSize(r * 2.0f);
                drawCentered(c, "♪", x, y);
                break;
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

    private void drawMoves(Canvas c, RectF area) {
        int n = moves.size();
        float gap = 1.6f * u;
        float h = (area.height() - gap * (n - 1)) / n;
        for (int i = 0; i < n; i++) {
            Move m = moves.get(i);
            m.rect.set(area.left, area.top + i * (h + gap), area.right, area.top + i * (h + gap) + h);
            boolean on = m.down || m.lit || m == playing;
            fill.setColor(on ? Color.argb(240, 150, 96, 30) : WOOD);
            float rad = Math.min(h, m.rect.width()) * 0.3f;
            c.drawRoundRect(m.rect, rad, rad, fill);
            stroke.setColor(on ? HONEY : WOOD_EDGE);
            stroke.setStrokeWidth((on ? 0.6f : 0.3f) * u);
            c.drawRoundRect(m.rect, rad, rad, stroke);
            text.setColor(on ? HONEY : CREAM);
            text.setTextSize(Math.min(3.6f * u, h * 0.32f));
            fitText(m.name, m.rect.width() * 0.9f, Math.min(3.6f * u, h * 0.32f));
            drawCentered(c, m.name, m.rect.centerX(), m.rect.centerY() - h * 0.12f);
            text.setColor(TAN);
            text.setTextSize(Math.min(2.6f * u, h * 0.22f));
            drawCentered(c, m.keys, m.rect.centerX(), m.rect.centerY() + h * 0.22f);
        }
    }

    /** TEMPORARY: the probe's Mark button and what it's for. */
    private void drawMark(Canvas c, RectF r) {
        boolean flash = SystemClock.uptimeMillis() - markFlashAt < 300;
        float bw = Math.min(r.width() * 0.35f, 30 * u);
        markRect.set(r.left, r.top, r.left + bw, r.bottom);
        fill.setColor(flash ? HONEY : Color.argb(230, 60, 90, 150));
        c.drawRoundRect(markRect, r.height() / 2, r.height() / 2, fill);
        text.setColor(flash ? Color.rgb(60, 30, 0) : Color.WHITE);
        text.setTextSize(3.6f * u);
        drawCentered(c, "Mark" + (marks > 0 ? " " + marks : ""), markRect.centerX(), markRect.centerY());
        text.setTextAlign(Paint.Align.LEFT);
        text.setColor(TAN);
        text.setTextSize(2.5f * u);
        float tx = markRect.right + 2 * u;
        c.drawText("Finding the game's data (test builds only):", tx, r.centerY() - 0.6f * u, text);
        c.drawText("tap Mark, collect one thing, tap Mark again.", tx, r.centerY() + 2.8f * u, text);
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

    private void panel(Canvas c, RectF r, boolean strong) {
        fill.setColor(strong ? Color.argb(215, 48, 32, 20) : WOOD);
        float rad = 3 * u;
        c.drawRoundRect(r, rad, rad, fill);
        stroke.setColor(Color.argb(200, 206, 162, 70));
        stroke.setStrokeWidth(0.45f * u);
        c.drawRoundRect(r, rad, rad, stroke);
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
        float x = e.getX(e.getActionIndex()), y = e.getY(e.getActionIndex());
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            if (markRect.contains(x, y)) {
                marks = Math.max(marks, GameState.probeMark());
                markFlashAt = SystemClock.uptimeMillis();
                Haptics.tap(this);
                invalidate();
                return true;
            }
            for (int i = 0; i < tabRects.length; i++) {
                if (tabRects[i].contains(x, y)) {
                    int picked = i - 1;
                    // Tapping the current world's tab goes back to following the game.
                    tabPicked = !(picked == world && tabPicked);
                    tab = tabPicked ? picked : world;
                    Haptics.tap(this);
                    invalidate();
                    return true;
                }
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
        if (m.holdButtons != 0) {
            heldFromMove |= m.holdButtons;
            TouchInput.setHeldN64Buttons(heldFromMove);
        } else if (playing == null) {
            if (m.toggle) {
                m.lit = !m.lit;
            }
            playing = m;
            playingStep = 0;
            handler.post(stepTask);
        }
        invalidate();
    }

    private void releaseMove(Move m) {
        m.down = false;
        if (m.holdButtons != 0) {
            heldFromMove &= ~m.holdButtons;
            TouchInput.setHeldN64Buttons(heldFromMove);
        }
        invalidate();
    }

    /** Plays the current move's steps, one after another. */
    private final Runnable stepTask = new Runnable() {
        @Override
        public void run() {
            Move m = playing;
            if (m == null) {
                return;
            }
            if (playingStep >= m.steps.length) {
                TouchInput.setN64Buttons(0);
                playing = null;
                invalidate();
                return;
            }
            int[] step = m.steps[playingStep++];
            TouchInput.setN64Buttons(step[0]);
            handler.postDelayed(this, Math.max(16, step[1]));
        }
    };

    @Override
    protected void onDetachedFromWindow() {
        setActive(false);
        super.onDetachedFromWindow();
    }
}
