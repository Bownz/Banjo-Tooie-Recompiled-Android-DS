package io.github.bownz.btrecomp;

import android.app.Activity;
import android.app.Dialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * The first-launch "where should your files go?" popup, in the bottom screen's DK style: a
 * jungle-green board with a gold frame, the chunky title font, and two big wooden choices.
 * Picking the shared folder on Android 11+ shows a second page explaining the Settings
 * switch before going there. Built in code (no layouts) and used only by StorageAccess.
 */
final class StorageDialog {
    interface Listener {
        void onShared();
        void onApp();
    }

    private static final int BANANA = Color.rgb(249, 214, 72);
    private static final int CREAM = Color.rgb(255, 244, 214);
    private static final int TAN = Color.rgb(232, 213, 168);
    private static final int OUTLINE = Color.rgb(42, 20, 4);
    private static final int WOOD_TOP = Color.rgb(154, 101, 50), WOOD_BOTTOM = Color.rgb(106, 66, 32);
    private static final int WOOD_EDGE = Color.rgb(58, 30, 6);
    private static final int GOLD = Color.rgb(217, 165, 32);

    private final Activity activity;
    private final Listener listener;
    private final Typeface titleFace;
    private Dialog dialog;
    private FrameLayout content;

    StorageDialog(Activity activity, Listener listener) {
        this.activity = activity;
        this.listener = listener;
        Typeface face;
        try {
            face = Typeface.createFromAsset(activity.getAssets(), "fonts/LuckiestGuy-Regular.ttf");
        } catch (RuntimeException e) {
            face = Typeface.DEFAULT_BOLD;
        }
        titleFace = face;
    }

    void show() {
        dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCancelable(false);
        content = new FrameLayout(activity);
        showChoicePage();
        dialog.setContentView(content);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setDimAmount(0.75f);
            int screen = activity.getResources().getDisplayMetrics().widthPixels;
            w.setLayout(Math.min(screen - dp(32), dp(620)), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        dialog.show();
    }

    void dismiss() {
        if (dialog != null) {
            dialog.dismiss();
            dialog = null;
        }
    }

    // ------------------------------------------------------------------------------
    // Pages
    // ------------------------------------------------------------------------------

    private void showChoicePage() {
        LinearLayout page = board();
        page.addView(title("Where should Banjo-Tooie keep its files?"));
        page.addView(body("Your saves, settings, mods and ROM all go in one place. You only choose once.", 0, dp(16)));

        View shared = choice("Shared folder", "Recommended",
            new String[] {
                "A folder you pick, new or one you already use",
                "Easy to add mods from a file manager or PC",
                "Kept if you uninstall the app",
            },
            "Needs permission to access your files", v -> {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R
                        && !StorageAccess.hasSharedStorageAccess(activity)) {
                    showSettingsPage();
                } else {
                    dismiss();
                    listener.onShared();
                }
            });
        page.addView(shared);
        shared.post(shared::requestFocus);

        View app = choice("App storage", null,
            new String[] {
                "Kept inside the app, no permission needed",
                "Harder to reach for adding mods",
                "Removed if you uninstall the app",
            },
            null, v -> {
                dismiss();
                listener.onApp();
            });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        page.addView(app, lp);
        setPage(page);
    }

    /** Android 11+: all-files access is a Settings switch, so say what to do before going there. */
    private void showSettingsPage() {
        LinearLayout page = board();
        page.addView(title("One more step"));
        page.addView(body("Android will open a settings screen for Banjo-Tooie, then you'll pick the folder.", 0, dp(12)));

        LinearLayout steps = card(false);
        steps.addView(step("1", "Turn on \"Allow access to manage all files\""));
        steps.addView(step("2", "Press back, then pick a folder for your files"));
        page.addView(steps);

        LinearLayout buttons = new LinearLayout(activity);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(16);
        page.addView(buttons, blp);

        TextView back = button("Back", false, v -> showChoicePage());
        TextView go = button("Open settings", true, v -> {
            dismiss();
            listener.onShared();
        });
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, dp(56), 1f);
        buttons.addView(back, half);
        LinearLayout.LayoutParams half2 = new LinearLayout.LayoutParams(0, dp(56), 1.6f);
        half2.leftMargin = dp(12);
        buttons.addView(go, half2);
        go.post(go::requestFocus);
        setPage(page);
    }

    private void setPage(LinearLayout page) {
        content.removeAllViews();
        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        scroll.addView(page);
        content.addView(scroll);
        page.setAlpha(0f);
        page.setTranslationY(dp(12));
        page.animate().alpha(1f).translationY(0).setDuration(220).start();
    }

    // ------------------------------------------------------------------------------
    // Pieces
    // ------------------------------------------------------------------------------

    /** The jungle-green board with a gold frame that every page sits on. */
    private LinearLayout board() {
        LinearLayout board = new LinearLayout(activity);
        board.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            new int[] { Color.rgb(47, 90, 37), Color.rgb(22, 48, 15) });
        bg.setCornerRadius(dp(22));
        bg.setStroke(dp(4), GOLD);
        board.setBackground(bg);
        board.setPadding(dp(22), dp(20), dp(22), dp(22));
        return board;
    }

    private TextView title(String s) {
        TextView t = new TextView(activity);
        t.setText(s);
        t.setTypeface(titleFace);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        t.setTextColor(BANANA);
        t.setShadowLayer(dp(3), 0, dp(2), OUTLINE);
        t.setGravity(Gravity.CENTER);
        t.setLetterSpacing(0.02f);
        return t;
    }

    private TextView body(String s, int top, int bottom) {
        TextView t = new TextView(activity);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        t.setTextColor(TAN);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(8), top + dp(6), dp(8), bottom);
        return t;
    }

    /** A wooden panel; tappable ones get a lighter pressed state and a ripple. */
    private LinearLayout card(boolean tappable) {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(14), dp(18), dp(16));
        Drawable normal = wood(WOOD_TOP, WOOD_BOTTOM);
        if (tappable) {
            StateListDrawable states = new StateListDrawable();
            states.addState(new int[] { android.R.attr.state_pressed }, wood(Color.rgb(184, 128, 66), Color.rgb(132, 86, 44)));
            // Highlighted when picked with a controller's d-pad.
            GradientDrawable focused = wood(Color.rgb(184, 128, 66), Color.rgb(132, 86, 44));
            focused.setStroke(dp(4), BANANA);
            states.addState(new int[] { android.R.attr.state_focused }, focused);
            states.addState(new int[] {}, normal);
            GradientDrawable mask = new GradientDrawable();
            mask.setCornerRadius(dp(16));
            mask.setColor(Color.WHITE);
            card.setBackground(new RippleDrawable(ColorStateList.valueOf(Color.argb(60, 255, 230, 160)), states, mask));
            card.setClickable(true);
            card.setFocusable(true);
        } else {
            card.setBackground(normal);
        }
        return card;
    }

    private GradientDrawable wood(int top, int bottom) {
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] { top, bottom });
        d.setCornerRadius(dp(16));
        d.setStroke(dp(3), WOOD_EDGE);
        return d;
    }

    private View choice(String name, String tag, String[] points, String note, View.OnClickListener onClick) {
        LinearLayout card = card(true);
        card.setOnClickListener(onClick);

        LinearLayout head = new LinearLayout(activity);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView n = new TextView(activity);
        n.setText(name);
        n.setTypeface(titleFace);
        n.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        n.setTextColor(CREAM);
        n.setShadowLayer(dp(2), 0, dp(2), OUTLINE);
        head.addView(n, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (tag != null) {
            TextView chip = new TextView(activity);
            chip.setText(tag.toUpperCase());
            chip.setTypeface(titleFace);
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            chip.setTextColor(OUTLINE);
            chip.setPadding(dp(10), dp(4), dp(10), dp(3));
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(20));
            bg.setColor(BANANA);
            bg.setStroke(dp(2), OUTLINE);
            chip.setBackground(bg);
            head.addView(chip);
        }
        card.addView(head);

        for (String p : points) {
            TextView t = new TextView(activity);
            t.setText("•  " + p);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            t.setTextColor(CREAM);
            t.setPadding(dp(4), dp(5), 0, 0);
            card.addView(t);
        }
        if (note != null) {
            TextView t = new TextView(activity);
            t.setText(note);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            t.setTextColor(BANANA);
            t.setPadding(dp(4), dp(8), 0, 0);
            card.addView(t);
        }
        return card;
    }

    private View step(String number, String s) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(6), 0, dp(6));
        TextView num = new TextView(activity);
        num.setText(number);
        num.setTypeface(titleFace);
        num.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        num.setTextColor(OUTLINE);
        num.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(BANANA);
        bg.setStroke(dp(2), OUTLINE);
        num.setBackground(bg);
        row.addView(num, new LinearLayout.LayoutParams(dp(34), dp(34)));
        TextView t = new TextView(activity);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        t.setTextColor(CREAM);
        t.setPadding(dp(12), 0, 0, 0);
        t.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    private TextView button(String s, boolean primary, View.OnClickListener onClick) {
        TextView b = new TextView(activity);
        b.setText(s);
        b.setTypeface(titleFace);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        b.setGravity(Gravity.CENTER);
        b.setTextColor(primary ? OUTLINE : CREAM);
        if (!primary) {
            b.setShadowLayer(dp(2), 0, dp(1), OUTLINE);
        }
        int top = primary ? Color.rgb(255, 230, 120) : WOOD_TOP;
        int bottom = primary ? Color.rgb(230, 160, 30) : WOOD_BOTTOM;
        StateListDrawable states = new StateListDrawable();
        GradientDrawable pressed = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] { bottom, bottom });
        pressed.setCornerRadius(dp(28));
        pressed.setStroke(dp(3), WOOD_EDGE);
        GradientDrawable normal = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] { top, bottom });
        normal.setCornerRadius(dp(28));
        normal.setStroke(dp(3), WOOD_EDGE);
        states.addState(new int[] { android.R.attr.state_pressed }, pressed);
        GradientDrawable focused = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] { top, bottom });
        focused.setCornerRadius(dp(28));
        focused.setStroke(dp(4), BANANA);
        states.addState(new int[] { android.R.attr.state_focused }, focused);
        states.addState(new int[] {}, normal);
        b.setBackground(states);
        b.setClickable(true);
        b.setFocusable(true);
        b.setOnClickListener(onClick);
        return b;
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, activity.getResources().getDisplayMetrics()));
    }
}
