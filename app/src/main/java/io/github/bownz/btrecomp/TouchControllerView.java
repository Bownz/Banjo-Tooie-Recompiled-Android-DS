package io.github.bownz.btrecomp;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.util.SparseArray;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * On-screen N64 controller.
 *
 * The buttons go straight into the game as N64 buttons (TouchInput.setOverlayN64Button),
 * so they always do what their label says, whatever the controller bindings are. The sticks
 * drive the virtual SDL gamepad's sticks: the left one is the N64 stick, and the optional
 * right one is the controller's right stick (the C buttons with the default bindings).
 *
 * The default layout is in units of the view's shorter side, anchored to its edges. The
 * player can move and resize each group of controls, and add or remove groups
 * ({@link #startEditing()}); the changes are kept per screen shape (and per camera mode, once
 * the game reports one) in SharedPreferences, and layouts can be saved and loaded by name.
 *
 * Drag to Move Camera: a finger away from the buttons turns the camera by tapping C-Left or
 * C-Right as it moves, so it works the way the game's own camera controls do.
 *
 * The controller hides itself while a menu (the launcher, the menu bar, settings) takes
 * input, so touches reach the menu, and comes back when it closes.
 */
public final class TouchControllerView extends View {

    private enum AnchorX { LEFT, CENTER, RIGHT }
    private enum AnchorY { TOP, BOTTOM }
    private enum Kind { BUTTON, STICK }

    /** A default position: offsets from the anchored edges, in units of the shorter side. */
    private static final class Place {
        final AnchorX ax; final float ox; final AnchorY ay; final float oy; final float radius;
        Place(AnchorX ax, float ox, AnchorY ay, float oy, float radius) {
            this.ax = ax; this.ox = ox; this.ay = ay; this.oy = oy; this.radius = radius;
        }
    }

    private static final class Control {
        final Kind kind;
        final String group;
        final String label;
        final int color;
        final Place normal;   // null: not shown with the normal camera
        final Place analog;   // null: not shown with the Analog Camera
        final Runnable onPress, onRelease;
        // Sticks only.
        final int axisX, axisY;
        final float captureSlack;
        int pointerId = -1;
        float sx, sy;

        // Resolved on layout, in pixels.
        float cx, cy, r;
        boolean shown;
        int n64Bit;   // N64 buttons only: the button it presses

        Control(Kind kind, String group, String label, int color, Place normal, Place analog,
                Runnable onPress, Runnable onRelease, int axisX, int axisY, float captureSlack) {
            this.kind = kind; this.group = group; this.label = label; this.color = color;
            this.normal = normal; this.analog = analog;
            this.onPress = onPress; this.onRelease = onRelease;
            this.axisX = axisX; this.axisY = axisY; this.captureSlack = captureSlack;
        }

        boolean hit(float x, float y, float slack) {
            float dx = x - cx, dy = y - cy, reach = r * slack;
            return dx * dx + dy * dy <= reach * reach;
        }
    }

    /** How the player moved and resized a group: offset as a fraction of the view, and scale. */
    private static final class Adjust {
        float dx, dy, scale = 1f;
    }

    /**
     * Groups the player can add or remove in the layout editor, with their names. The menu
     * button can't be removed (it's the way back to the editor). On by default: the ones in
     * {@link #DEFAULT_ON}.
     */
    private static final Map<String, String> GROUP_NAMES = new LinkedHashMap<>();
    static {
        GROUP_NAMES.put("stick", "Control stick");
        GROUP_NAMES.put("dpad", "D-pad");
        GROUP_NAMES.put("a", "A");
        GROUP_NAMES.put("b", "B");
        GROUP_NAMES.put("z", "Z");
        GROUP_NAMES.put("r", "R");
        GROUP_NAMES.put("start", "Start");
        GROUP_NAMES.put("c", "C buttons (all four)");
        GROUP_NAMES.put("c_up", "C-Up");
        GROUP_NAMES.put("c_down", "C-Down");
        GROUP_NAMES.put("c_left", "C-Left");
        GROUP_NAMES.put("c_right", "C-Right");
        GROUP_NAMES.put("camera", "Right stick (the C buttons, or as bound)");
        GROUP_NAMES.put("l", "L");
    }
    private static final Set<String> DEFAULT_ON = new HashSet<>(java.util.Arrays.asList(
        "stick", "dpad", "a", "b", "z", "r", "start", "c", "menu"));

    private static final float BUTTON_SLACK = 1.25f;
    private static final float MIN_LAYOUT_ASPECT = 1.4f;
    private static final float MIN_SCALE = 0.5f, MAX_SCALE = 2.5f;
    private static final String PREFS = "touch_layout";

    private static final int COLOR_A = Color.rgb(59, 111, 216);
    private static final int COLOR_B = Color.rgb(46, 158, 79);
    private static final int COLOR_C = Color.rgb(232, 194, 27);
    private static final int COLOR_START = Color.rgb(200, 55, 55);
    private static final int COLOR_NEUTRAL = Color.rgb(120, 120, 128);
    private static final int COLOR_SELECT = Color.rgb(255, 214, 64);

    private final List<Control> controls = new ArrayList<>();
    private final List<Control> sticks = new ArrayList<>();

    private final SparseArray<float[]> buttonPointers = new SparseArray<>();
    private final Set<Control> pressed = new HashSet<>();

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);

    private float opacity = 0.55f;
    private boolean active = false;   // Touch controls wanted (setting / no controller)
    private boolean menuOpen = false; // A menu is taking input: hide and let touches through
    private boolean gameStarted = false; // Until the game runs (startup, launcher) there's nothing to control
    // A camera mode with its own layout (uiState bit 2); none reported by the game yet.
    private boolean analogMode = false;
    // Drag to Move Camera (Input > Touch): a finger that lands away from the buttons turns the
    // camera, by tapping C-Left / C-Right each time it has moved far enough sideways.
    private boolean dragCamera = true;
    private int cameraPointer = -1;
    private float cameraLastX, cameraLastY;
    private float cameraDragDp;          // sideways movement not yet turned into a tap
    private int cameraTapsPending;       // + right, - left
    private boolean cameraTapDown;
    private static final long CAMERA_TAP_MS = 50;
    // Floating stick (a toggle in the layout editor): touching anywhere on the left half brings
    // a smaller control stick under the thumb. It works whether or not the stick itself is on
    // screen; when it is, it goes back to its place when let go.
    private static final String KEY_FLOATING = "floating_stick";
    private static final float FLOATING_RADIUS = 0.11f;   // in units of the shorter side
    private boolean floatingStick = false;
    private boolean stickFloating = false;
    // Double-tap the control stick for Z (a toggle in the layout editor): a quick tap, then a
    // second touch soon after holds Z for as long as that touch lasts (the stick still works).
    private static final String KEY_DOUBLE_TAP_Z = "double_tap_z";
    private static final long TAP_MAX_MS = 250, DOUBLE_TAP_GAP_MS = 300;
    private boolean doubleTapZ = false;
    private boolean zFromStick = false;
    // A button pressed while that Z is held: Z is let go when it's released (so A gives a
    // Flap Flip and Z is gone by the landing). B and the C buttons keep Z, for the moves done
    // with Z held (see keepsZ).
    private Control zEndsWith;
    private long stickDownAt, stickUpAt;
    private boolean lastStickTouchWasTap;
    private float stickHomeX, stickHomeY, stickHomeR;
    private float unit = 1f;

    // Layout adjustments for the current camera mode, by group.
    private final Map<String, Adjust> adjust = new HashMap<>();
    // Groups shown in the current camera mode (added or removed in the editor).
    private final Set<String> on = new HashSet<>();

    // ---- Editing ----
    private boolean editing = false;
    private String selected;
    private int dragPointer = -1;
    private float dragLastX, dragLastY;
    private int pinchPointer = -1;
    private float pinchStartDist, pinchStartScale;
    private final RectF[] toolbar = { new RectF(), new RectF(), new RectF(), new RectF(), new RectF(), new RectF(), new RectF(), new RectF(), new RectF(), new RectF() };
    private static final String[] TOOLBAR_LABELS = { "−", "+", "Remove", "Add", "Reset", "Done", "Floating stick", "Double-tap stick: Z", "Save", "Load" };
    private static final int TB_SMALLER = 0, TB_BIGGER = 1, TB_REMOVE = 2, TB_ADD = 3, TB_RESET = 4, TB_DONE = 5, TB_FLOATING = 6, TB_DOUBLE_TAP = 7,
        TB_SAVE = 8, TB_LOAD = 9;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            pollUiState();
            if (active || editing) {
                handler.postDelayed(this, 150);
            }
        }
    };

    public TouchControllerView(Context context) {
        super(context);

        stroke.setStyle(Paint.Style.STROKE);
        stroke.setColor(Color.WHITE);
        text.setColor(Color.WHITE);
        text.setTextAlign(Paint.Align.CENTER);
        text.setFakeBoldText(true);

        // Three columns. Left: Z, D-pad, stick. Right: R, C-buttons, B, A. Centre: menu, Start.
        // In the second camera mode (once the game reports one) the C-buttons get smaller and
        // move up, the right stick takes their place, and Start moves beside the menu button.

        // ---- Left column ----
        Place z = new Place(AnchorX.LEFT, 0.13f, AnchorY.TOP, 0.13f, 0.085f);
        controls.add(button("z", "Z", COLOR_NEUTRAL, z, z, TouchInput.N64_Z));
        float dpx = 0.30f, dpy = 0.70f, dpOff = 0.075f, dpR = 0.042f;
        controls.add(button("dpad", "▲", COLOR_NEUTRAL, both(AnchorX.LEFT, dpx, AnchorY.BOTTOM, dpy + dpOff, dpR), TouchInput.N64_DPAD_UP));
        controls.add(button("dpad", "▼", COLOR_NEUTRAL, both(AnchorX.LEFT, dpx, AnchorY.BOTTOM, dpy - dpOff, dpR), TouchInput.N64_DPAD_DOWN));
        controls.add(button("dpad", "◀", COLOR_NEUTRAL, both(AnchorX.LEFT, dpx - dpOff, AnchorY.BOTTOM, dpy, dpR), TouchInput.N64_DPAD_LEFT));
        controls.add(button("dpad", "▶", COLOR_NEUTRAL, both(AnchorX.LEFT, dpx + dpOff, AnchorY.BOTTOM, dpy, dpR), TouchInput.N64_DPAD_RIGHT));
        Place ls = new Place(AnchorX.LEFT, 0.27f, AnchorY.BOTTOM, 0.27f, 0.16f);
        addStick(new Control(Kind.STICK, "stick", "", COLOR_NEUTRAL, ls, ls, null, null,
            TouchInput.AXIS_LEFT_X, TouchInput.AXIS_LEFT_Y, 1.5f));

        // ---- Right column ----
        Place r = new Place(AnchorX.RIGHT, 0.13f, AnchorY.TOP, 0.13f, 0.085f);
        controls.add(button("r", "R", COLOR_NEUTRAL, r, r, TouchInput.N64_R));

        float cx = 0.30f, cy = 0.70f, cOff = 0.105f, cR = 0.056f;          // normal camera
        float acx = 0.37f, acy = 0.31f, acOff = 0.072f, acR = 0.040f;      // Analog Camera (from the top)
        controls.add(button("c", "C▲", COLOR_C,
            new Place(AnchorX.RIGHT, cx, AnchorY.BOTTOM, cy + cOff, cR),
            new Place(AnchorX.RIGHT, acx, AnchorY.TOP, acy - acOff, acR), TouchInput.N64_C_UP));
        controls.add(button("c", "C▼", COLOR_C,
            new Place(AnchorX.RIGHT, cx, AnchorY.BOTTOM, cy - cOff, cR),
            new Place(AnchorX.RIGHT, acx, AnchorY.TOP, acy + acOff, acR), TouchInput.N64_C_DOWN));
        controls.add(button("c", "C◀", COLOR_C,
            new Place(AnchorX.RIGHT, cx + cOff, AnchorY.BOTTOM, cy, cR),
            new Place(AnchorX.RIGHT, acx + acOff, AnchorY.TOP, acy, acR), TouchInput.N64_C_LEFT));
        controls.add(button("c", "C▶", COLOR_C,
            new Place(AnchorX.RIGHT, cx - cOff, AnchorY.BOTTOM, cy, cR),
            new Place(AnchorX.RIGHT, acx - acOff, AnchorY.TOP, acy, acR), TouchInput.N64_C_RIGHT));
        controls.add(button("b", "B", COLOR_B, both(AnchorX.RIGHT, 0.40f, AnchorY.BOTTOM, 0.32f, 0.078f), TouchInput.N64_B));
        controls.add(button("a", "A", COLOR_A, both(AnchorX.RIGHT, 0.20f, AnchorY.BOTTOM, 0.20f, 0.09f), TouchInput.N64_A));
        Place rs = new Place(AnchorX.RIGHT, 0.72f, AnchorY.BOTTOM, 0.30f, 0.13f);
        addStick(new Control(Kind.STICK, "camera", "C", COLOR_NEUTRAL, rs, rs, null, null,
            TouchInput.AXIS_RIGHT_X, TouchInput.AXIS_RIGHT_Y, 1.2f));

        // ---- Centre ----
        controls.add(sdlButton("menu", "☰", COLOR_NEUTRAL,
            new Place(AnchorX.CENTER, 0f, AnchorY.TOP, 0.08f, 0.045f),
            new Place(AnchorX.CENTER, -0.09f, AnchorY.TOP, 0.08f, 0.045f), TouchInput.BUTTON_BACK));
        controls.add(button("start", "START", COLOR_START,
            new Place(AnchorX.CENTER, 0f, AnchorY.BOTTOM, 0.10f, 0.06f),
            new Place(AnchorX.CENTER, 0.09f, AnchorY.TOP, 0.08f, 0.05f), TouchInput.N64_START));

        // ---- Added in the layout editor (off by default) ----
        // Single C buttons, in a row across the middle.
        float rowY = 0.40f, rowR = 0.05f;
        controls.add(button("c_up", "C▲", COLOR_C, both(AnchorX.CENTER, -0.27f, AnchorY.TOP, rowY, rowR), TouchInput.N64_C_UP));
        controls.add(button("c_down", "C▼", COLOR_C, both(AnchorX.CENTER, -0.09f, AnchorY.TOP, rowY, rowR), TouchInput.N64_C_DOWN));
        controls.add(button("c_left", "C◀", COLOR_C, both(AnchorX.CENTER, 0.09f, AnchorY.TOP, rowY, rowR), TouchInput.N64_C_LEFT));
        controls.add(button("c_right", "C▶", COLOR_C, both(AnchorX.CENTER, 0.27f, AnchorY.TOP, rowY, rowR), TouchInput.N64_C_RIGHT));
        // L (unused by the game; mods may use it), beside Z.
        Place l = new Place(AnchorX.LEFT, 0.30f, AnchorY.TOP, 0.13f, 0.07f);
        controls.add(button("l", "L", COLOR_NEUTRAL, l, l, TouchInput.N64_L));

        loadLayout();
        floatingStick = getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_FLOATING, false);
        doubleTapZ = getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_DOUBLE_TAP_Z, false);
        setContentDescription("On-screen N64 controller");
        setHapticFeedbackEnabled(true);
        setVisibility(GONE);
    }

    private static Place both(AnchorX ax, float ox, AnchorY ay, float oy, float radius) {
        return new Place(ax, ox, ay, oy, radius);
    }

    private Control button(String group, String label, int color, Place place, int n64Bit) {
        return button(group, label, color, place, place, n64Bit);
    }

    private Control button(String group, String label, int color, Place normal, Place analog, int n64Bit) {
        Control c = new Control(Kind.BUTTON, group, label, color, normal, analog,
            () -> TouchInput.setOverlayN64Button(n64Bit, true),
            () -> TouchInput.setOverlayN64Button(n64Bit, false), 0, 0, 1f);
        c.n64Bit = n64Bit;
        return c;
    }

    /** A button on the virtual SDL gamepad (the menu button: opens the game's menu). */
    private Control sdlButton(String group, String label, int color, Place normal, Place analog, int sdlButton) {
        return new Control(Kind.BUTTON, group, label, color, normal, analog,
            () -> TouchInput.setButton(sdlButton, true),
            () -> TouchInput.setButton(sdlButton, false), 0, 0, 1f);
    }

    private void addStick(Control c) {
        controls.add(c);
        sticks.add(c);
    }

    // ------------------------------------------------------------------------------
    // Public control
    // ------------------------------------------------------------------------------

    /** Turns the touch controls on (creating the virtual gamepad) or off. */
    public void setActive(boolean active) {
        if (this.active == active) {
            return;
        }
        this.active = active;
        if (!active) {
            releaseAll();
            TouchInput.release();
        } else {
            TouchInput.acquire();
            startPolling();
        }
        refreshVisibility();
    }

    public boolean isActive() {
        return active;
    }

    public void setOpacity(float opacity) {
        this.opacity = Math.max(0.1f, Math.min(1f, opacity));
        invalidate();
    }

    /** Lifts every touch: call when the activity pauses so nothing stays held. */
    public void releaseAll() {
        for (Control c : new ArrayList<>(pressed)) {
            c.onRelease.run();
        }
        pressed.clear();
        buttonPointers.clear();
        cameraPointer = -1;
        cameraDragDp = 0f;
        cameraTapsPending = 0;
        if (cameraTapDown) {
            cameraTapDown = false;
            TouchInput.setOverlayN64Button(TouchInput.N64_C_LEFT | TouchInput.N64_C_RIGHT, false);
        }
        for (Control s : sticks) {
            if (s.pointerId != -1 || s.sx != 0f || s.sy != 0f) {
                s.pointerId = -1;
                setStick(s, 0f, 0f);
            }
            stickReleased(s);
        }
        releaseStickZ();
        invalidate();
    }

    /** Opens the layout editor (Input > Touch > Edit Touch Layout), over everything else. */
    public void startEditing() {
        releaseAll();
        pollUiState();
        editing = true;
        selected = null;
        dragPointer = pinchPointer = -1;
        startPolling();
        refreshVisibility();
        invalidate();
    }

    private void stopEditing() {
        editing = false;
        selected = null;
        dragPointer = pinchPointer = -1;
        saveLayout();
        layoutControls();
        refreshVisibility();
        invalidate();
    }

    /** A group is shown. */
    private boolean groupOn(String group) {
        return on.contains(group);
    }

    private void startPolling() {
        handler.removeCallbacks(poll);
        handler.post(poll);
    }

    private void pollUiState() {
        int state = TouchInput.uiState();
        boolean menu = (state & 1) != 0;
        boolean started = (state & 16) != 0;
        if (started != gameStarted) {
            gameStarted = started;
            refreshVisibility();
        }
        boolean analog = (state & 2) != 0;
        dragCamera = (state & 4) != 0;
        if (!dragCamera) {
            cameraPointer = -1;
            cameraDragDp = 0f;
        }
        if (analog != analogMode && !editing) {
            releaseAll();
            analogMode = analog;
            loadLayout();
            layoutControls();
            invalidate();
        }
        if (menu != menuOpen) {
            menuOpen = menu;
            if (menu) {
                releaseAll();
            }
            refreshVisibility();
        }
    }

    private void refreshVisibility() {
        boolean show = editing || (active && !menuOpen && gameStarted);
        if (!show) {
            releaseAll();
        }
        setVisibility(show ? VISIBLE : GONE);
    }

    // ------------------------------------------------------------------------------
    // Layout
    // ------------------------------------------------------------------------------

    private String prefix() {
        return analogMode ? "analog." : "normal.";
    }

    // ---- Layouts per screen shape, and saved layouts ----
    //
    // Each screen shape (landscape, portrait, square - e.g. a foldable opened) keeps its own
    // layout, per camera mode, as one string "group=dx,dy,scale,on;..." under
    // "layout.<shape>.<mode>". A shape with none yet starts from the older per-group keys
    // ("<mode>.<group>", "<mode>.on.<group>"), i.e. the layout from before shapes existed.
    // Saved layouts ("saved.<name>") use the same string and load onto any shape.

    private String loadedShape = "";

    /** The screen's shape, from its aspect ratio. */
    private String shape() {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) {
            android.util.DisplayMetrics m = getResources().getDisplayMetrics();
            w = m.widthPixels;
            h = m.heightPixels;
        }
        float ratio = h > 0 ? (float) w / h : 1.6f;
        if (ratio >= 1.45f) {
            return "landscape";
        }
        return ratio < 0.9f ? "portrait" : "square";
    }

    private static String shapeName(String shape) {
        switch (shape) {
            case "portrait": return "Portrait";
            case "square": return "Square screen";
            default: return "Landscape";
        }
    }

    private String layoutKey() {
        return "layout." + loadedShape + "." + (analogMode ? "analog" : "normal");
    }

    private void loadLayout() {
        loadedShape = shape();
        SharedPreferences prefs = getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String layout = prefs.getString(layoutKey(), null);
        if (layout != null) {
            applyLayoutString(layout);
            return;
        }
        // Not set up on this shape yet: the layout from before shapes were kept apart.
        adjust.clear();
        on.clear();
        for (Control c : controls) {
            String shown = prefs.getString(prefix() + "on." + c.group, null);
            if (shown != null ? shown.equals("1") : DEFAULT_ON.contains(c.group)) {
                on.add(c.group);
            }
            if (adjust.containsKey(c.group)) {
                continue;
            }
            Adjust a = new Adjust();
            String value = prefs.getString(prefix() + c.group, null);
            if (value != null) {
                String[] parts = value.split(",");
                try {
                    a.dx = Float.parseFloat(parts[0]);
                    a.dy = Float.parseFloat(parts[1]);
                    a.scale = clampScale(Float.parseFloat(parts[2]));
                } catch (RuntimeException ignored) {
                    a = new Adjust();
                }
            }
            adjust.put(c.group, a);
        }
    }

    private void saveLayout() {
        getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(layoutKey(), layoutString()).apply();
    }

    /** The current layout as "group=dx,dy,scale,on;...". */
    private String layoutString() {
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, Adjust> e : adjust.entrySet()) {
            Adjust a = e.getValue();
            b.append(e.getKey()).append('=').append(a.dx).append(',').append(a.dy).append(',').append(a.scale)
                .append(',').append(on.contains(e.getKey()) ? 1 : 0).append(';');
        }
        return b.toString();
    }

    /** Applies a layout string; groups it doesn't mention get their defaults. */
    private void applyLayoutString(String layout) {
        adjust.clear();
        on.clear();
        Set<String> mentioned = new HashSet<>();
        for (String entry : layout.split(";")) {
            int eq = entry.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String group = entry.substring(0, eq);
            String[] parts = entry.substring(eq + 1).split(",");
            Adjust a = new Adjust();
            try {
                a.dx = Float.parseFloat(parts[0]);
                a.dy = Float.parseFloat(parts[1]);
                a.scale = clampScale(Float.parseFloat(parts[2]));
                if (parts.length > 3 && parts[3].equals("1")) {
                    on.add(group);
                }
                mentioned.add(group);
            } catch (RuntimeException ignored) {
                a = new Adjust();
            }
            adjust.put(group, a);
        }
        for (Control c : controls) {
            if (!adjust.containsKey(c.group)) {
                adjust.put(c.group, new Adjust());
            }
            if (!mentioned.contains(c.group) && DEFAULT_ON.contains(c.group)) {
                on.add(c.group);
            }
        }
        on.add("menu");   // always there: it's the way back to this editor
    }

    /** Names of the saved layouts, sorted. */
    private List<String> savedLayoutNames() {
        List<String> names = new ArrayList<>();
        for (String key : getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE).getAll().keySet()) {
            if (key.startsWith("saved.")) {
                names.add(key.substring("saved.".length()));
            }
        }
        java.util.Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    /** Asks for a name, then saves the current layout under it (replacing one of the same name). */
    private void showSaveDialog() {
        final android.widget.EditText input = new android.widget.EditText(getContext());
        input.setSingleLine(true);
        input.setHint("Layout name");
        int pad = Math.round(20 * getResources().getDisplayMetrics().density);
        android.widget.FrameLayout box = new android.widget.FrameLayout(getContext());
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(input);
        new android.app.AlertDialog.Builder(getContext())
            .setTitle("Save this layout")
            .setView(box)
            .setPositiveButton("Save", (d, w) -> {
                String name = input.getText().toString().trim().replace(";", "").replace("=", "");
                if (name.isEmpty()) {
                    return;
                }
                getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString("saved." + name, layoutString()).apply();
                android.widget.Toast.makeText(getContext(), "Saved \"" + name + "\"", android.widget.Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    /** Lists the saved layouts: tap one to use it here, or delete them. */
    private void showLoadDialog() {
        final List<String> names = savedLayoutNames();
        if (names.isEmpty()) {
            android.widget.Toast.makeText(getContext(), "No saved layouts yet: use Save first.", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        new android.app.AlertDialog.Builder(getContext())
            .setTitle("Load a layout")
            .setItems(names.toArray(new String[0]), (d, which) -> {
                String layout = getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString("saved." + names.get(which), null);
                if (layout != null) {
                    applyLayoutString(layout);
                    selected = null;
                    layoutControls();
                    invalidate();
                }
            })
            .setNeutralButton("Delete…", (d, w) -> showDeleteDialog())
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void showDeleteDialog() {
        final List<String> names = savedLayoutNames();
        final boolean[] checked = new boolean[names.size()];
        new android.app.AlertDialog.Builder(getContext())
            .setTitle("Delete saved layouts")
            .setMultiChoiceItems(names.toArray(new String[0]), checked, (d, which, isChecked) -> checked[which] = isChecked)
            .setPositiveButton("Delete", (d, w) -> {
                SharedPreferences.Editor editor = getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
                for (int i = 0; i < names.size(); i++) {
                    if (checked[i]) {
                        editor.remove("saved." + names.get(i));
                    }
                }
                editor.apply();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    /** The toolbar's four rows, centred on the screen. */
    private static float toolbarTop(int h, float bh, float gap) {
        return h * 0.5f - (4 * bh + 3 * gap) / 2f;
    }

    private static float clampScale(float s) {
        return Math.max(MIN_SCALE, Math.min(MAX_SCALE, s));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (!shape().equals(loadedShape)) {
            // Folded, unfolded or rotated: this shape's own layout.
            if (editing) {
                saveLayout();   // keep the edits made on the shape before
            }
            releaseAll();
            selected = null;
            loadLayout();
        }
        layoutControls();
        if (active || editing) {
            startPolling();
        }
    }

    private void layoutControls() {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) {
            return;
        }
        // Size controls from the shorter side, but never wider than a 1.4:1 working area,
        // so the columns stay apart on square or portrait screens.
        unit = Math.min(Math.min(w, h), w / MIN_LAYOUT_ASPECT);

        // Default positions first.
        Map<String, float[]> centres = new HashMap<>(); // group -> sum x, sum y, count
        for (Control c : controls) {
            Place p = analogMode ? c.analog : c.normal;
            c.shown = p != null && groupOn(c.group);
            if (p == null) {
                continue;
            }
            c.r = p.radius * unit;
            switch (p.ax) {
                case LEFT: c.cx = p.ox * unit; break;
                case RIGHT: c.cx = w - p.ox * unit; break;
                default: c.cx = w / 2f + p.ox * unit; break;
            }
            c.cy = (p.ay == AnchorY.TOP) ? p.oy * unit : h - p.oy * unit;
            float[] sum = centres.get(c.group);
            if (sum == null) {
                sum = new float[3];
                centres.put(c.group, sum);
            }
            sum[0] += c.cx; sum[1] += c.cy; sum[2] += 1;
        }
        // Then the player's moves and sizes, per group, around the group's centre.
        for (Control c : controls) {
            if (!c.shown) {
                continue;
            }
            Adjust a = adjust.get(c.group);
            if (a == null) {
                continue;
            }
            float[] sum = centres.get(c.group);
            float gx = sum[0] / sum[2], gy = sum[1] / sum[2];
            c.cx = gx + (c.cx - gx) * a.scale + a.dx * w;
            c.cy = gy + (c.cy - gy) * a.scale + a.dy * h;
            c.r *= a.scale;
        }
        stroke.setStrokeWidth(Math.max(2f, unit * 0.006f));

        // Editor toolbar, in the middle of the screen: − + Remove / Add Reset Done.
        float bh = unit * 0.10f, gap = unit * 0.03f;
        float[] widths = { unit * 0.14f, unit * 0.14f, unit * 0.26f, unit * 0.22f, unit * 0.22f, unit * 0.22f };
        for (int row = 0; row < 2; row++) {
            float total = gap * 2;
            for (int i = row * 3; i < row * 3 + 3; i++) total += widths[i];
            float x = (w - total) / 2f, top = toolbarTop(h, bh, gap) + row * (bh + gap);
            for (int i = row * 3; i < row * 3 + 3; i++) {
                toolbar[i].set(x, top, x + widths[i], top + bh);
                x += widths[i] + gap;
            }
        }
        // Third row: the stick toggles (floating stick, double-tap for Z), side by side.
        float half = unit * 0.40f;
        float top3 = toolbarTop(h, bh, gap) + 2 * (bh + gap);
        float left3 = (w - (half * 2 + gap)) / 2f;
        toolbar[TB_FLOATING].set(left3, top3, left3 + half, top3 + bh);
        toolbar[TB_DOUBLE_TAP].set(left3 + half + gap, top3, left3 + half * 2 + gap, top3 + bh);
        // Fourth row: save and load layouts.
        float top4 = top3 + bh + gap;
        toolbar[TB_SAVE].set(left3, top4, left3 + half, top4 + bh);
        toolbar[TB_LOAD].set(left3 + half + gap, top4, left3 + half * 2 + gap, top4 + bh);
    }

    // ------------------------------------------------------------------------------
    // Drawing
    // ------------------------------------------------------------------------------

    @Override
    protected void onDraw(Canvas canvas) {
        int baseAlpha = Math.round(255 * (editing ? Math.max(opacity, 0.8f) : opacity));
        if (editing) {
            canvas.drawColor(Color.argb(150, 0, 0, 0));
        }
        for (Control c : controls) {
            boolean floatingNow = stickFloating && c.group.equals("stick");
            if (!c.shown && !floatingNow) {
                continue;
            }
            if (c.kind == Kind.STICK) {
                drawStick(canvas, c, baseAlpha);
            } else {
                boolean down = pressed.contains(c);
                fill.setColor(c.color);
                fill.setAlpha(down ? Math.min(255, baseAlpha + 90) : baseAlpha / 2);
                canvas.drawCircle(c.cx, c.cy, c.r, fill);
                stroke.setAlpha(baseAlpha);
                canvas.drawCircle(c.cx, c.cy, c.r, stroke);

                text.setAlpha(Math.min(255, baseAlpha + 60));
                text.setTextSize(c.r * (c.label.length() > 2 ? 0.55f : 0.8f));
                float baseline = c.cy - (text.descent() + text.ascent()) / 2f;
                canvas.drawText(c.label, c.cx, baseline, text);
            }
            if (editing && c.group.equals(selected)) {
                stroke.setColor(COLOR_SELECT);
                stroke.setAlpha(255);
                float old = stroke.getStrokeWidth();
                stroke.setStrokeWidth(old * 2f);
                canvas.drawCircle(c.cx, c.cy, c.r + old * 2f, stroke);
                stroke.setStrokeWidth(old);
                stroke.setColor(Color.WHITE);
            }
        }
        if (editing) {
            drawEditor(canvas);
        }
    }

    private void drawStick(Canvas canvas, Control c, int baseAlpha) {
        fill.setColor(Color.BLACK);
        fill.setAlpha(baseAlpha / 3);
        canvas.drawCircle(c.cx, c.cy, c.r, fill);
        stroke.setAlpha(baseAlpha);
        canvas.drawCircle(c.cx, c.cy, c.r, stroke);

        float knobX = c.cx + c.sx * c.r, knobY = c.cy + c.sy * c.r;
        fill.setColor(COLOR_NEUTRAL);
        fill.setAlpha(c.pointerId != -1 ? Math.min(255, baseAlpha + 90) : baseAlpha);
        canvas.drawCircle(knobX, knobY, c.r * 0.45f, fill);
        canvas.drawCircle(knobX, knobY, c.r * 0.45f, stroke);
        if (zFromStick && c.group.equals("stick")) {
            text.setAlpha(255);
            text.setTextSize(c.r * 0.4f);
            float baseline = knobY - (text.descent() + text.ascent()) / 2f;
            canvas.drawText("Z", knobX, baseline, text);
        } else if (!c.label.isEmpty()) {
            text.setAlpha(Math.min(255, baseAlpha + 30));
            text.setTextSize(c.r * 0.22f);
            float baseline = knobY - (text.descent() + text.ascent()) / 2f;
            canvas.drawText(c.label, knobX, baseline, text);
        }
    }

    private void drawEditor(Canvas canvas) {
        text.setAlpha(255);
        text.setTextSize(unit * 0.045f);
        float y = toolbar[0].top - unit * 0.10f;
        canvas.drawText("Touch layout · " + shapeName(loadedShape) + (analogMode ? " · second camera mode" : ""), getWidth() / 2f, y, text);
        text.setTextSize(unit * 0.032f);
        canvas.drawText(selected == null ? "Tap a control to select it, drag to move, pinch to resize · Add for more"
                : (GROUP_NAMES.containsKey(selected) ? GROUP_NAMES.get(selected) + ": " : "")
                    + "drag to move · pinch or − / + to resize · Remove to hide", getWidth() / 2f, y + unit * 0.05f, text);

        float radius = unit * 0.02f;
        for (int i = 0; i < toolbar.length; i++) {
            RectF rect = toolbar[i];
            boolean primary = i == TB_DONE;
            boolean disabled = i == TB_REMOVE && (selected == null || !GROUP_NAMES.containsKey(selected));
            fill.setColor(primary ? Color.rgb(46, 158, 79) : i == TB_REMOVE && !disabled ? Color.rgb(170, 60, 50)
                : (i == TB_FLOATING && floatingStick) || (i == TB_DOUBLE_TAP && doubleTapZ) ? Color.rgb(59, 111, 216) : Color.rgb(60, 60, 68));
            fill.setAlpha(disabled ? 110 : 235);
            canvas.drawRoundRect(rect, radius, radius, fill);
            stroke.setAlpha(255);
            canvas.drawRoundRect(rect, radius, radius, stroke);
            text.setTextSize(rect.height() * 0.45f);
            float baseline = rect.centerY() - (text.descent() + text.ascent()) / 2f;
            String label = i == TB_FLOATING ? "Floating stick: " + (floatingStick ? "On" : "Off")
                : i == TB_DOUBLE_TAP ? "Double-tap stick for Z: " + (doubleTapZ ? "On" : "Off") : TOOLBAR_LABELS[i];
            if (i == TB_FLOATING || i == TB_DOUBLE_TAP) {
                text.setTextSize(rect.height() * 0.38f);
                while (text.getTextSize() > 8f && text.measureText(label) > rect.width() * 0.92f) {
                    text.setTextSize(text.getTextSize() * 0.92f);
                }
                baseline = rect.centerY() - (text.descent() + text.ascent()) / 2f;
            }
            canvas.drawText(label, rect.centerX(), baseline, text);
        }
    }

    // ------------------------------------------------------------------------------
    // Touch handling
    // ------------------------------------------------------------------------------

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (editing) {
            onEditTouch(event);
            return true;
        }
        if (!active || menuOpen) {
            return false;
        }

        final int action = event.getActionMasked();
        final int index = event.getActionIndex();
        final int pointerId = event.getPointerId(index);

        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                float x = event.getX(index), y = event.getY(index);
                Control stick = null;
                for (Control s : sticks) {
                    if (s.shown && s.pointerId == -1 && !(stickFloating && s.group.equals("stick")) && s.hit(x, y, s.captureSlack)) {
                        stick = s;
                        break;
                    }
                }
                Control left = floatingStick ? leftStick() : null;
                if (stick != null) {
                    stick.pointerId = pointerId;
                    stickDown(stick);
                    updateStick(stick, x, y);
                } else if (hitButton(x, y) != null) {
                    buttonPointers.put(pointerId, new float[] { x, y });
                } else if (left != null && left.pointerId == -1 && inFloatingZone(x, y)) {
                    // Floating stick: a smaller stick comes to the thumb, centred under it.
                    stickHomeX = left.cx;
                    stickHomeY = left.cy;
                    stickHomeR = left.r;
                    stickFloating = true;
                    left.cx = x;
                    left.cy = y;
                    left.r = FLOATING_RADIUS * unit;
                    left.pointerId = pointerId;
                    stickDown(left);
                    updateStick(left, x, y);
                } else if (dragCamera && cameraPointer == -1) {
                    // Away from the buttons: this finger turns the camera (Drag to Move Camera).
                    cameraPointer = pointerId;
                    cameraLastX = x;
                    cameraLastY = y;
                } else if (action == MotionEvent.ACTION_DOWN) {
                    return false;
                }
                TouchInput.ensure();
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                for (int i = 0; i < event.getPointerCount(); i++) {
                    int id = event.getPointerId(i);
                    Control stick = stickFor(id);
                    if (stick != null) {
                        updateStick(stick, event.getX(i), event.getY(i));
                    } else if (id == cameraPointer) {
                        float x = event.getX(i), y = event.getY(i);
                        float density = getResources().getDisplayMetrics().density;
                        addCameraDrag((x - cameraLastX) / density);
                        cameraLastX = x;
                        cameraLastY = y;
                    } else {
                        float[] pos = buttonPointers.get(id);
                        if (pos != null) {
                            pos[0] = event.getX(i);
                            pos[1] = event.getY(i);
                        }
                    }
                }
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_UP) {
                    buttonPointers.clear();
                    cameraPointer = -1;
                    cameraDragDp = 0f;
                    for (Control s : sticks) {
                        if (s.pointerId != -1) {
                            s.pointerId = -1;
                            setStick(s, 0f, 0f);
                            stickUp(s);
                            stickReleased(s);
                        }
                    }
                } else {
                    Control stick = stickFor(pointerId);
                    if (stick != null) {
                        stick.pointerId = -1;
                        setStick(stick, 0f, 0f);
                        stickUp(stick);
                        stickReleased(stick);
                    } else if (pointerId == cameraPointer) {
                        cameraPointer = -1;
                        cameraDragDp = 0f;
                    } else {
                        buttonPointers.remove(pointerId);
                    }
                }
                break;
            }
            default:
                return true;
        }

        updatePressedButtons();
        invalidate();
        return true;
    }

    /**
     * Where the floating stick can appear: the lower-left, like Roblox's dynamic thumbstick
     * (the left half, below the top third). The rest of the screen turns the camera.
     */
    private boolean inFloatingZone(float x, float y) {
        return x < getWidth() / 2f && y > getHeight() / 3f;
    }

    /** The left (control) stick. */
    private Control leftStick() {
        for (Control s : sticks) {
            if (s.group.equals("stick")) {
                return s;
            }
        }
        return null;
    }

    /** The control stick was touched: a second touch right after a quick tap holds Z. */
    private void stickDown(Control s) {
        if (!s.group.equals("stick")) {
            return;
        }
        long now = android.os.SystemClock.uptimeMillis();
        if (doubleTapZ && lastStickTouchWasTap && now - stickUpAt <= DOUBLE_TAP_GAP_MS && !zFromStick) {
            zFromStick = true;
            zEndsWith = null;
            TouchInput.setOverlayN64Button(TouchInput.N64_Z, true);
            Haptics.tap(this);
            invalidate();
        }
        stickDownAt = now;
    }

    private void releaseStickZ() {
        if (zFromStick) {
            zFromStick = false;
            zEndsWith = null;
            TouchInput.setOverlayN64Button(TouchInput.N64_Z, false);
            invalidate();
        }
    }

    /**
     * Buttons used with Z held, which don't end the double-tap Z: B and the C buttons (eggs,
     * Talon Trot, Wonderwing and the other moves done while crouched).
     */
    private static boolean keepsZ(Control c) {
        return (c.n64Bit & (TouchInput.N64_B | TouchInput.N64_C_UP | TouchInput.N64_C_DOWN
            | TouchInput.N64_C_LEFT | TouchInput.N64_C_RIGHT)) != 0;
    }

    // ---- Drag to Move Camera --------------------------------------------------------------

    /** Sideways finger movement (dp): every step's worth taps C-Right (right) or C-Left (left). */
    private void addCameraDrag(float dxDp) {
        // Camera Drag Speed 1..5: 120 dp per step at the slowest, 40 at the fastest.
        int speed = Math.max(1, Math.min(5, TouchInput.dragSpeed()));
        float step = 140f - 20f * speed;
        cameraDragDp += dxDp;
        while (Math.abs(cameraDragDp) >= step) {
            int dir = cameraDragDp > 0 ? 1 : -1;
            cameraDragDp -= dir * step;
            // Don't let a fast swipe queue up turns that play out long after the finger stops.
            if (Math.abs(cameraTapsPending) < 2) {
                cameraTapsPending += dir;
            }
        }
        if (cameraTapsPending != 0 && !cameraTapDown) {
            handler.post(cameraTap);
        }
    }

    /** Presses C-Left/C-Right for a moment, then lets go, once per queued step. */
    private final Runnable cameraTap = new Runnable() {
        @Override
        public void run() {
            if (cameraTapDown) {
                TouchInput.setOverlayN64Button(TouchInput.N64_C_LEFT | TouchInput.N64_C_RIGHT, false);
                cameraTapDown = false;
                if (cameraTapsPending != 0) {
                    handler.postDelayed(this, CAMERA_TAP_MS);
                }
                return;
            }
            if (cameraTapsPending == 0) {
                return;
            }
            int bit = cameraTapsPending > 0 ? TouchInput.N64_C_RIGHT : TouchInput.N64_C_LEFT;
            cameraTapsPending -= cameraTapsPending > 0 ? 1 : -1;
            TouchInput.setOverlayN64Button(bit, true);
            cameraTapDown = true;
            handler.postDelayed(this, CAMERA_TAP_MS);
        }
    };

    /** The thumb left the control stick: let go of Z if the double tap was holding it. */
    private void stickUp(Control s) {
        if (!s.group.equals("stick")) {
            return;
        }
        long now = android.os.SystemClock.uptimeMillis();
        if (zFromStick) {
            releaseStickZ();
            lastStickTouchWasTap = false;   // a third tap starts over
        } else {
            lastStickTouchWasTap = now - stickDownAt <= TAP_MAX_MS;
        }
        stickUpAt = now;
    }

    /** Puts a floating stick back in its place once the thumb lifts. */
    private void stickReleased(Control s) {
        if (stickFloating && s.group.equals("stick")) {
            stickFloating = false;
            s.cx = stickHomeX;
            s.cy = stickHomeY;
            s.r = stickHomeR;
        }
    }

    private Control stickFor(int pointerId) {
        for (Control s : sticks) {
            if (s.pointerId == pointerId) {
                return s;
            }
        }
        return null;
    }

    private Control hitButton(float x, float y) {
        for (Control c : controls) {
            if (c.shown && c.kind == Kind.BUTTON && c.hit(x, y, BUTTON_SLACK)) {
                return c;
            }
        }
        return null;
    }

    private void updatePressedButtons() {
        Set<Control> now = new HashSet<>();
        for (int i = 0; i < buttonPointers.size(); i++) {
            float[] pos = buttonPointers.valueAt(i);
            Control c = hitButton(pos[0], pos[1]);
            if (c != null) {
                now.add(c);
            }
        }

        boolean newPress = false;
        for (Control c : new ArrayList<>(pressed)) {
            if (!now.contains(c)) {
                c.onRelease.run();
                pressed.remove(c);
                if (c == zEndsWith) {
                    releaseStickZ();   // e.g. after the crouch jump
                }
            }
        }
        for (Control c : now) {
            if (pressed.add(c)) {
                if (zFromStick && zEndsWith == null && !keepsZ(c)) {
                    zEndsWith = c;
                }
                c.onPress.run();
                newPress = true;
            }
        }
        if (newPress) {
            Haptics.tap(this);
        }
    }

    private void updateStick(Control s, float x, float y) {
        float dx = (x - s.cx) / s.r, dy = (y - s.cy) / s.r;
        float length = (float) Math.sqrt(dx * dx + dy * dy);
        if (length > 1f) {
            dx /= length;
            dy /= length;
        }
        setStick(s, dx, dy);
    }

    private void setStick(Control s, float x, float y) {
        s.sx = x;
        s.sy = y;
        // Screen Y grows downward, as does SDL's stick Y; no flip needed.
        TouchInput.setAxis(s.axisX, x);
        TouchInput.setAxis(s.axisY, y);
    }

    // ------------------------------------------------------------------------------
    // Layout editor
    // ------------------------------------------------------------------------------

    private void onEditTouch(MotionEvent event) {
        final int action = event.getActionMasked();
        final int index = event.getActionIndex();
        final int pointerId = event.getPointerId(index);
        switch (action) {
            case MotionEvent.ACTION_DOWN: {
                float x = event.getX(index), y = event.getY(index);
                for (int i = 0; i < toolbar.length; i++) {
                    if (toolbar[i].contains(x, y)) {
                        onToolbar(i);
                        return;
                    }
                }
                Control c = controlAt(x, y);
                selected = c != null ? c.group : null;
                if (c != null) {
                    dragPointer = pointerId;
                    dragLastX = x;
                    dragLastY = y;
                    Haptics.tap(this);
                }
                break;
            }
            case MotionEvent.ACTION_POINTER_DOWN: {
                if (selected != null && pinchPointer == -1) {
                    pinchPointer = pointerId;
                    pinchStartDist = pinchDistance(event);
                    pinchStartScale = adjust.get(selected).scale;
                }
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                if (selected == null) {
                    break;
                }
                Adjust a = adjust.get(selected);
                if (pinchPointer != -1 && dragPointer != -1) {
                    float d = pinchDistance(event);
                    if (pinchStartDist > 1f && d > 1f) {
                        a.scale = clampScale(pinchStartScale * d / pinchStartDist);
                        layoutControls();
                    }
                } else if (dragPointer != -1) {
                    int i = event.findPointerIndex(dragPointer);
                    if (i >= 0) {
                        float x = event.getX(i), y = event.getY(i);
                        a.dx += (x - dragLastX) / getWidth();
                        a.dy += (y - dragLastY) / getHeight();
                        a.dx = Math.max(-1f, Math.min(1f, a.dx));
                        a.dy = Math.max(-1f, Math.min(1f, a.dy));
                        dragLastX = x;
                        dragLastY = y;
                        layoutControls();
                        keepGroupOnScreen(selected);
                    }
                }
                break;
            }
            case MotionEvent.ACTION_POINTER_UP: {
                if (pointerId == pinchPointer) {
                    pinchPointer = -1;
                } else if (pointerId == dragPointer) {
                    // The pinching finger carries on dragging.
                    dragPointer = pinchPointer;
                    pinchPointer = -1;
                    int i = dragPointer != -1 ? event.findPointerIndex(dragPointer) : -1;
                    if (i >= 0) {
                        dragLastX = event.getX(i);
                        dragLastY = event.getY(i);
                    }
                }
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragPointer = pinchPointer = -1;
                break;
            default:
                break;
        }
        invalidate();
    }

    private float pinchDistance(MotionEvent event) {
        int a = event.findPointerIndex(dragPointer), b = event.findPointerIndex(pinchPointer);
        if (a < 0 || b < 0) {
            return 0f;
        }
        float dx = event.getX(a) - event.getX(b), dy = event.getY(a) - event.getY(b);
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    private Control controlAt(float x, float y) {
        Control best = null;
        float bestDist = Float.MAX_VALUE;
        for (Control c : controls) {
            if (!c.shown) {
                continue;
            }
            float dx = x - c.cx, dy = y - c.cy;
            float d = (float) Math.sqrt(dx * dx + dy * dy) - c.r;
            if (d < unit * 0.03f && d < bestDist) {
                best = c;
                bestDist = d;
            }
        }
        return best;
    }

    /** Pulls a group back so its controls' centres stay on screen. */
    private void keepGroupOnScreen(String group) {
        float shiftX = 0f, shiftY = 0f;
        int w = getWidth(), h = getHeight();
        for (Control c : controls) {
            if (!c.shown || !c.group.equals(group)) {
                continue;
            }
            if (c.cx < 0) shiftX = Math.max(shiftX, -c.cx);
            if (c.cx > w) shiftX = Math.min(shiftX, w - c.cx);
            if (c.cy < 0) shiftY = Math.max(shiftY, -c.cy);
            if (c.cy > h) shiftY = Math.min(shiftY, h - c.cy);
        }
        if (shiftX != 0f || shiftY != 0f) {
            Adjust a = adjust.get(group);
            a.dx += shiftX / w;
            a.dy += shiftY / h;
            layoutControls();
        }
    }

    private void onToolbar(int i) {
        Haptics.tap(this);
        switch (i) {
            case TB_REMOVE:
                if (selected != null && GROUP_NAMES.containsKey(selected)) {
                    on.remove(selected);
                    selected = null;
                }
                break;
            case TB_ADD:
                showAddMenu();
                return;
            case TB_SAVE:
                showSaveDialog();
                return;
            case TB_LOAD:
                showLoadDialog();
                return;
            case TB_DOUBLE_TAP:
                doubleTapZ = !doubleTapZ;
                getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_DOUBLE_TAP_Z, doubleTapZ).apply();
                if (doubleTapZ) {
                    android.widget.Toast.makeText(getContext(),
                        "Tap the control stick, then touch it again and hold for Z. You can still move while holding.",
                        android.widget.Toast.LENGTH_LONG).show();
                }
                invalidate();
                return;
            case TB_FLOATING:
                floatingStick = !floatingStick;
                getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_FLOATING, floatingStick).apply();
                if (floatingStick) {
                    android.widget.Toast.makeText(getContext(),
                        "Touch anywhere in the lower-left to bring up the stick; drag elsewhere to turn the camera. Remove the control stick if you only want the floating one.",
                        android.widget.Toast.LENGTH_LONG).show();
                }
                invalidate();
                return;
            case TB_SMALLER:
            case TB_BIGGER: {
                float step = i == TB_SMALLER ? -0.1f : 0.1f;
                if (selected != null) {
                    Adjust a = adjust.get(selected);
                    a.scale = clampScale(a.scale + step);
                } else {
                    for (Adjust a : adjust.values()) {
                        a.scale = clampScale(a.scale + step);
                    }
                }
                break;
            }
            case TB_RESET:
                for (Adjust a : adjust.values()) {
                    a.dx = a.dy = 0f;
                    a.scale = 1f;
                }
                on.clear();
                on.addAll(DEFAULT_ON);
                selected = null;
                break;
            default:
                stopEditing();
                return;
        }
        layoutControls();
        invalidate();
    }

    /** Lists the groups not on screen; picking one adds it (selected, ready to move). */
    private void showAddMenu() {
        final List<String> groups = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (Map.Entry<String, String> e : GROUP_NAMES.entrySet()) {
            String group = e.getKey();
            if (on.contains(group)) {
                continue;
            }
            groups.add(group);
            names.add(e.getValue());
        }
        if (groups.isEmpty()) {
            android.widget.Toast.makeText(getContext(), "Everything is already on screen.", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        new android.app.AlertDialog.Builder(getContext())
            .setTitle("Add to the touch controls")
            .setItems(names.toArray(new String[0]), (dialog, which) -> {
                String group = groups.get(which);
                on.add(group);
                selected = group;
                layoutControls();
                keepGroupOnScreen(group);
                invalidate();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    // Folding or unfolding a foldable can take the view off its window and put it back:
    // start watching the menus again.
    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (active || editing) {
            startPolling();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        handler.removeCallbacks(poll);
        handler.removeCallbacks(cameraTap);
        super.onDetachedFromWindow();
    }
}
