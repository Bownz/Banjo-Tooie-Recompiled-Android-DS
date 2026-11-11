package io.github.bownz.btrecomp;

import android.app.Presentation;
import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Display;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;

/**
 * Content for a second display, such as the lower screen of a dual-screen handheld (AYN Thor):
 * the Banjo-Tooie bottom screen ({@link BottomScreenView}): the character, collectibles and
 * move buttons. The game keeps the main display to itself.
 *
 * A Presentation is a window that belongs to the activity, so it is only shown while the
 * activity is in the foreground; BTActivity creates and dismisses it with the lifecycle.
 */
final class SecondScreenPresentation extends Presentation {
    private BottomScreenView bottomScreen;

    SecondScreenPresentation(Context outerContext, Display display) {
        super(outerContext, display);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (getWindow() != null) {
            // Keep the second screen awake while the game runs, like the main one.
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            // Never take input focus from the game window: SDL would report a focus loss to
            // the game, and key/gamepad events would come here. Touches still arrive.
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        }

        FrameLayout root = new FrameLayout(getContext());
        root.setBackgroundColor(Color.rgb(16, 12, 22));
        bottomScreen = new BottomScreenView(getContext());
        root.addView(bottomScreen, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        bottomScreen.setActive(true);
    }

    BottomScreenView getBottomScreen() {
        return bottomScreen;
    }

    @Override
    protected void onStop() {
        if (bottomScreen != null) {
            bottomScreen.setActive(false);
        }
        super.onStop();
    }
}
