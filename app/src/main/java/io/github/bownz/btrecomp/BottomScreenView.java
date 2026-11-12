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
 * Banjo-Tooie is about gathering things, world by world, so the screen is a progress board
 * for the world you're in, which follows the game on its own:
 * <ul>
 *   <li>Jiggywiggy's puzzle: the world's ten Jiggies as jigsaw pieces, filled in as found.</li>
 *   <li>The notes collected there, on a stave.</li>
 *   <li>Jinjos, Glowbos, Cheato pages and empty honeycombs, as small counters.</li>
 *   <li>Eggs and feathers carried.</li>
 *   <li>Four buttons for the moves that need Z held with a C button (Bottom Screen Buttons).</li>
 * </ul>
 * Tap the puzzle to flip to the whole game's totals and back; pausing shows the totals too.
 * The screen dims during cutscenes.
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
        final boolean hold;       // held while the finger stays down (Wonderwing)
        final boolean toggle;     // on until tapped again (Talon Trot)
        final RectF rect = new RectF();
        boolean down, lit;

        Move(String name, String keys, int buttons, boolean hold, boolean toggle) {
            this.name = name; this.keys = keys; this.buttons = buttons; this.hold = hold; this.toggle = toggle;
        }
    }

    private static final int Z = TouchInput.N64_Z;
    private final Move[] moves = {
        new Move("Fire Egg", "Z + C▲", Z | TouchInput.N64_C_UP, false, false),
        new Move("Lay Egg", "Z + C▼", Z | TouchInput.N64_C_DOWN, false, false),
        new Move("Talon Trot", "Z + C◀", Z | TouchInput.N64_C_LEFT, false, true),
        new Move("Wonderwing", "hold · Z + C▶", Z | TouchInput.N64_C_RIGHT, true, false),
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

    /** Showing the whole game's totals instead of the current world. */
    private boolean totals;
    private final RectF boardRect = new RectF();

    // TEMPORARY: the probe's Mark button.
    private final RectF markRect = new RectF();
    private int marks;
    private long markFlashAt;

    private Move tapping;     // a tap move in progress
    private int heldButtons;  // buttons held by hold moves

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
        world = GameState.world();
        boolean wasPaused = paused;
        paused = GameState.paused();
        if (paused != wasPaused) {
            totals = paused;   // the pause screen shows the totals; unpausing goes back
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
        drawStars(c, w, h);

        float pad = 4 * u;
        float markH = 7 * u;
        float movesH = showButtons ? 15 * u : 0f;
        float bottom = h - pad - markH - 2 * u - (showButtons ? movesH + 3 * u : 0f);

        // Caption: where the board is from.
        text.setTextSize(3.6f * u);
        text.setColor(TAN);
        String caption = totals ? "Whole game" : world >= 0 ? GameState.WORLDS[world] : "This world";
        c.drawText(caption, w / 2f, pad + 3.4f * u, text);

        float top = pad + 6.5f * u;
        float boardH = (bottom - top) * 0.46f;
        boardRect.set(pad, top, w - pad, top + boardH);
        drawPuzzle(c, boardRect);

        float notesTop = boardRect.bottom + 3 * u;
        RectF notes = new RectF(pad, notesTop, w - pad, notesTop + (bottom - top) * 0.17f);
        drawNotes(c, notes);

        RectF counters = new RectF(pad, notes.bottom + 3 * u, w - pad, bottom);
        drawCounters(c, counters);

        if (showButtons) {
            drawMoves(c, new RectF(pad, bottom + 3 * u, w - pad, bottom + 3 * u + movesH));
        }
        drawMark(c, new RectF(pad, h - pad - markH, w - pad, h - pad));
        drawDim(c, w, h);
    }

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

    /**
     * Jiggywiggy's puzzle: ten jigsaw pieces (two rows of five), filled in honey as the
     * world's Jiggies are found. For the whole game, a bar of all 90 instead.
     */
    private void drawPuzzle(Canvas c, RectF r) {
        int count = GameState.count(GameState.JIGGIES, totals ? -1 : world);
        if (totals) {
            float barH = r.height() * 0.22f;
            RectF bar = new RectF(r.left, r.centerY() - barH / 2, r.right, r.centerY() + barH / 2);
            fill.setColor(SLOT);
            c.drawRoundRect(bar, barH / 2, barH / 2, fill);
            if (count > 0) {
                RectF done = new RectF(bar.left, bar.top, bar.left + bar.width() * Math.min(1f, count / (float) JIGGIES_TOTAL), bar.bottom);
                fill.setColor(HONEY);
                c.drawRoundRect(done, barH / 2, barH / 2, fill);
            }
            jiggyIcon(c, r.left + barH * 0.9f, bar.top - barH * 0.9f, barH * 0.7f, true);
            text.setTextAlign(Paint.Align.LEFT);
            text.setColor(CREAM);
            text.setTextSize(barH * 0.9f);
            c.drawText(value(count) + " / " + JIGGIES_TOTAL + " Jiggies", r.left + barH * 2f, bar.top - barH * 0.55f, text);
            text.setTextAlign(Paint.Align.CENTER);
            text.setColor(TAN);
            text.setTextSize(3.2f * u);
            c.drawText("Tap to go back to this world", r.centerX(), bar.bottom + barH * 1.3f, text);
            return;
        }

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
    private void drawNotes(Canvas c, RectF r) {
        int notes = GameState.count(GameState.NOTES, totals ? -1 : world);
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

    /** Jinjos, Glowbos, Cheato pages, honeycombs; then eggs and feathers. */
    private void drawCounters(Canvas c, RectF r) {
        int at = totals ? -1 : world;
        int[] items = { GameState.JINJOS, GameState.GLOWBOS, GameState.CHEATO_PAGES, GameState.HONEYCOMBS,
            GameState.EGGS, GameState.FEATHERS };
        String[] names = { "Jinjos", "Glowbos", "Cheato", "Honeycombs", "Eggs", "Feathers" };
        int cols = 3;
        float cw = r.width() / cols, ch = r.height() / 2;
        for (int i = 0; i < items.length; i++) {
            float cx = r.left + cw * (i % cols + 0.5f), cy = r.top + ch * (i / cols + 0.5f);
            boolean carried = items[i] == GameState.EGGS || items[i] == GameState.FEATHERS;
            int v = GameState.count(items[i], carried ? -1 : at);
            float s = Math.min(cw, ch);
            icon(c, items[i], cx - s * 0.22f, cy - s * 0.08f, s * 0.17f);
            text.setTextAlign(Paint.Align.LEFT);
            text.setColor(CREAM);
            text.setTextSize(s * 0.22f);
            c.drawText(value(v), cx + s * 0.02f, cy, text);
            text.setTextAlign(Paint.Align.CENTER);
            text.setColor(TAN);
            text.setTextSize(Math.min(2.8f * u, s * 0.12f));
            c.drawText(names[i], cx, cy + s * 0.3f, text);
        }
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
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            if (markRect.contains(x, y)) {
                marks = Math.max(marks, GameState.probeMark());
                markFlashAt = SystemClock.uptimeMillis();
                Haptics.tap(this);
                invalidate();
                return true;
            }
            if (boardRect.contains(x, y)) {
                totals = !totals;
                Haptics.tap(this);
                invalidate();
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
        if (m.hold) {
            // Z first, then the C button a moment later, held until the finger lifts.
            heldButtons |= Z;
            TouchInput.setHeldN64Buttons(heldButtons);
            final Move held = m;
            handler.postDelayed(() -> {
                if (held.down) {
                    heldButtons |= held.buttons;
                    TouchInput.setHeldN64Buttons(heldButtons);
                }
            }, 90);
        } else if (tapping == null) {
            if (m.toggle) {
                m.lit = !m.lit;
            }
            tapping = m;
            TouchInput.setN64Buttons(Z);            // crouch first...
            handler.postDelayed(pressTap, 90);      // ...then the C button
        }
        invalidate();
    }

    private void releaseMove(Move m) {
        m.down = false;
        if (m.hold) {
            heldButtons &= ~m.buttons;
            TouchInput.setHeldN64Buttons(heldButtons);
        }
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
