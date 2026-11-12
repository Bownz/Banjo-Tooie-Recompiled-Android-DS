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
 * The progress book (like the game's own pause screen): it opens whenever the game is paused,
 * or by tapping the puzzle, and pages with ◀ ▶ or a swipe through every world in a table, then
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
        if (paused && !wasPaused && !bookOpen) {
            openBook(true);              // pausing opens the book on where you are
        } else if (!paused && wasPaused && bookByPause) {
            bookOpen = false;            // and unpausing closes it again
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
        if (bookOpen) {
            drawBook(c, new RectF(pad, pad, w - pad, h - pad - markH - 2 * u));
            drawMark(c, new RectF(pad, h - pad - markH, w - pad, h - pad));
            drawDim(c, w, h);
            return;
        }
        float movesH = showButtons ? 15 * u : 0f;
        float bottom = h - pad - markH - 2 * u - (showButtons ? movesH + 3 * u : 0f);

        // Caption: the world the board is for.
        text.setTextSize(3.6f * u);
        text.setColor(TAN);
        String caption = world >= 0 ? GameState.WORLDS[world] : "This world";
        c.drawText(caption, w / 2f, pad + 3.4f * u, text);

        float top = pad + 6.5f * u;
        float boardH = (bottom - top) * 0.46f;
        boardRect.set(pad, top, w - pad, top + boardH);
        drawPuzzle(c, boardRect, GameState.count(GameState.JIGGIES, world));

        float notesTop = boardRect.bottom + 3 * u;
        RectF notes = new RectF(pad, notesTop, w - pad, notesTop + (bottom - top) * 0.17f);
        drawNotes(c, notes, GameState.count(GameState.NOTES, world));

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

    /** Jinjos, Glowbos, Cheato pages, honeycombs; then eggs and feathers. */
    private void drawCounters(Canvas c, RectF r) {
        int at = world;
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

    // ------------------------------------------------------------------------------
    // The progress book
    // ------------------------------------------------------------------------------

    private static final int[] BOOK_ITEMS = { GameState.JIGGIES, GameState.NOTES, GameState.JINJOS,
        GameState.GLOWBOS, GameState.CHEATO_PAGES, GameState.HONEYCOMBS };
    private static final String[] BOOK_NAMES = { "Jiggies", "Notes", "Jinjos", "Glowbos", "Cheato Pages", "Honeycombs" };

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
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            if (markRect.contains(x, y)) {
                marks = Math.max(marks, GameState.probeMark());
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
            if (boardRect.contains(x, y)) {
                openBook(false);
                Haptics.tap(this);
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
