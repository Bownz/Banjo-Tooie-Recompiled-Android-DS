package io.github.bownz.btrecomp;

import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.res.AssetManager;
import android.database.Cursor;
import android.hardware.display.DisplayManager;
import android.hardware.input.InputManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.OpenableColumns;
import android.util.Log;
import android.view.Display;
import android.view.InputDevice;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;

import org.libsdl.app.SDLActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * Hosts Banjo-Tooie: Recompiled. SDLActivity (compiled from the SDL2 source tree Gradle
 * downloads) loads libSDL2.so and libmain.so and runs SDL_main on its own thread.
 *
 * On top of SDLActivity this adds:
 *  - Asset extraction: the UI files that sit next to the desktop executable are packaged in
 *    the APK and extracted to internal storage, where the native code can open them.
 *  - Storage: where saves, mods and the ROM live, with the permission flow
 *    ({@link StorageAccess}); the native side asks via {@link #getDataDirectory()}.
 *  - A blocking document picker that the native nfd replacement calls over JNI.
 *  - Touch controls ({@link TouchControllerView}) as an overlay when there's no physical
 *    gamepad (or always / never, per Input > Touch > Touch Controls).
 *  - The bottom screen ({@link BottomScreenView}) on a second display, if there is one (e.g.
 *    the AYN Thor's lower screen), through {@link SecondScreenPresentation}.
 *  - The graphics driver choice ({@link GpuDrivers}), crash detection and log sharing
 *    ({@link Diagnostics}).
 *  - Game-friendly window behaviour: immersive fullscreen, screen kept on, drawing into
 *    the display cutout, a steady 60 Hz on adaptive-refresh screens.
 */
public class BTActivity extends SDLActivity
        implements InputManager.InputDeviceListener, DisplayManager.DisplayListener {
    private static final String TAG = "BTRecomp";
    private static final String ASSET_ROOT = "game";
    private static final int PICK_REQUEST_CODE = 0x6E6664; // "nfd"

    // Must match RequestMode in android/jni/nfd_android.cpp.
    private static final int MODE_OPEN_FILE = 0;
    private static final int MODE_OPEN_MULTIPLE = 1;

    private StorageAccess storage;

    private DisplayManager displayManager;
    private InputManager inputManager;
    private TouchControllerView overlay;
    private SecondScreenPresentation presentation;
    private boolean started;

    private final Object pickLock = new Object();
    private CountDownLatch pickLatch;
    private String pickResult;

    @Override
    protected String[] getLibraries() {
        return new String[] { "c++_shared", "SDL2", "main" };
    }

    // ------------------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Must finish before SDL starts the native thread, which happens in super.onCreate
        // and later lifecycle callbacks.
        extractAssetsIfNeeded();
        storage = new StorageAccess(this);
        Diagnostics.rotateLogs(this);
        // Checked before the game starts, so the crash prompt can offer last run's log.
        final String lastCrash = Diagnostics.takeUnreportedCrash(this);
        crashedLastRun = lastCrash != null;

        super.onCreate(savedInstanceState);

        configureWindow();
        offerCrashReport(lastCrash);

        displayManager = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        inputManager = (InputManager) getSystemService(Context.INPUT_SERVICE);

        // SDLActivity builds its layout (with the game surface) in onCreate. If the native
        // libraries failed to load it shows an error instead and mLayout stays null.
        if (mLayout != null) {
            // No focus outline around the game (the theme turns it off too; this covers views
            // SDL made before it applied, such as the game surface).
            mLayout.setDefaultFocusHighlightEnabled(false);
            for (int i = 0; i < mLayout.getChildCount(); i++) {
                mLayout.getChildAt(i).setDefaultFocusHighlightEnabled(false);
            }
            overlay = new TouchControllerView(this);
            mLayout.addView(overlay, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        started = true;
        updateTouchControls();
    }

    @Override
    protected void onStop() {
        // A Presentation is a window of this activity: it must go when the activity stops.
        started = false;
        dismissPresentation();
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        storage.onResume();
        displayManager.registerDisplayListener(this, null);
        inputManager.registerInputDeviceListener(this, null);
        setForeground(true);
        hideSystemBars();
        preferSixtyHz();
        // Controllers may have come or gone while we were away.
        updateTouchControls();
        Diagnostics.markPlaying(this);
    }

    @Override
    protected void onPause() {
        // Nothing may stay held while the game can't see the player's fingers.
        if (overlay != null) {
            overlay.releaseAll();
        }
        if (presentation != null && presentation.getBottomScreen() != null) {
            presentation.getBottomScreen().cancel();
        }
        displayManager.unregisterDisplayListener(this);
        inputManager.unregisterInputDeviceListener(this);
        Diagnostics.markLeaving(this);
        super.onPause();
        setForeground(false);
    }

    @Override
    protected void onDestroy() {
        Diagnostics.markLeaving(this);
        dismissPresentation();
        TouchInput.shutdown();
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            // System bars come back after dialogs, the notification shade, etc.
            hideSystemBars();
            preferSixtyHz();
            // By now the game has usually started SDL: create the virtual gamepad if the
            // touch controls wanted it before SDL was ready.
            TouchInput.ensure();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (!storage.onRequestPermissionsResult(requestCode)) {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        }
    }

    private void configureWindow() {
        Window window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // Use the whole panel, including the area beside a notch or camera cutout.
        WindowManager.LayoutParams attributes = window.getAttributes();
        attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        window.setAttributes(attributes);

        // Sustained performance mode is left off: where it's supported it holds the CPU and GPU
        // to clocks the device can keep up indefinitely without a fan, well below what a
        // cooled handheld like the Thor can run, and busy scenes then miss 30 FPS.
        PowerManager power = (PowerManager) getSystemService(Context.POWER_SERVICE);
        android.util.Log.i("BTRecomp", "Sustained performance mode supported: "
            + (power != null && power.isSustainedPerformanceModeSupported()) + " (not used)");
    }

    @SuppressWarnings("deprecation")
    private void hideSystemBars() {
        Window window = getWindow();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false);
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.systemBars());
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            window.getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        }
    }

    // ------------------------------------------------------------------------------
    // Storage (called from android/jni/android_main.cpp before the game starts)
    // ------------------------------------------------------------------------------

    /**
     * Called from the native side (android/jni/gpu_driver.cpp) as the renderer starts: the
     * custom Vulkan driver to load, or null for the system driver. Chosen from the app icon's
     * "Graphics driver" shortcut (GpuDriverActivity).
     */
    public String getGpuDriver() {
        String driver = GpuDrivers.forRenderer(this);
        if (GpuDrivers.takeRevertedNotice(this)) {
            runOnUiThread(() -> android.widget.Toast.makeText(this,
                "The custom graphics driver didn't start last time, so the system driver is being used.",
                android.widget.Toast.LENGTH_LONG).show());
        }
        if (driver != null) {
            // Still running after a while: the driver works, keep it.
            new android.os.Handler(getMainLooper()).postDelayed(() -> GpuDrivers.confirmWorking(this), GpuDrivers.TRIAL_MS);
        }
        return driver;
    }

    /** For the in-game Graphics settings: the selected driver id, then "id\tlabel" lines. */
    public String getGpuDriverList() {
        GpuDrivers.Driver selected = GpuDrivers.selected(this);
        StringBuilder sb = new StringBuilder(selected == null ? "" : selected.id);
        for (GpuDrivers.Driver d : GpuDrivers.installed(this)) {
            sb.append('\n').append(d.id).append('\t').append(d.label().replace('\t', ' ').replace('\n', ' '));
        }
        return sb.toString();
    }

    /** From the in-game Graphics settings: use this driver ("" = system) from the next start. */
    public String selectGpuDriver(String id) {
        GpuDrivers.Driver pick = null;
        for (GpuDrivers.Driver d : GpuDrivers.installed(this)) {
            if (d.id.equals(id)) {
                pick = d;
            }
        }
        GpuDrivers.select(this, pick);
        return null;
    }

    private static native void nativeSetForeground(boolean foreground);

    /** Tells the audio watchdog whether the app is in front (sound pauses in the background). */
    private static void setForeground(boolean foreground) {
        try { nativeSetForeground(foreground); } catch (UnsatisfiedLinkError ignored) { }
    }

    /** From native code (Settings > Gameplay > Game Files Location): offers to move the files. */
    public String changeStorageLocation() {
        runOnUiThread(storage::startChange);
        return null;
    }

    /** From native code: where the game's files are now, for that button's description. */
    public String getStorageDescription() {
        return storage.describeLocation();
    }

    /** From the in-game Video settings: pick, install or remove driver packages. */
    public String openGpuDriverManager() {
        runOnUiThread(() -> startActivity(new Intent(this, GpuDriverActivity.class)
            .putExtra(GpuDriverActivity.EXTRA_DRIVERS, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
        return null;
    }

    /** If the game crashed or froze last time, offer to share the log. */
    private static volatile boolean crashedLastRun;

    /** From native code: "1" if the game crashed or froze last time (skip launcher is ignored). */
    public String lastRunCrashed() {
        return crashedLastRun ? "1" : "0";
    }

    private void offerCrashReport(final String crash) {
        if (crash == null) {
            return;
        }
        new android.app.AlertDialog.Builder(this)
            .setTitle("Banjo-Tooie closed unexpectedly")
            .setMessage("Last time the game had a " + crash + ".\n\nShare the log so it can be fixed? "
                + "It contains the game's technical output and your device model, nothing personal.")
            .setPositiveButton("Share log", (dialog, which) -> Diagnostics.share(this, crash))
            .setNegativeButton("Not now", null)
            .show();
    }

    /** From the in-game settings (Technical > Share Log). */
    public String shareLog() {
        runOnUiThread(() -> Diagnostics.share(this, null));
        return null;
    }

    /** Called from native code when the game is shutting down (it was quit from its menu). */
    @SuppressWarnings("unused")
    public String gameClosing() {
        Diagnostics.markLeaving(this);
        return null;
    }

    /** For the in-game settings: e.g. "0.1.0-beta (76e2c32)". */
    public String getAppVersion() {
        return Diagnostics.version(this);
    }


    /**
     * Where saves, config, mods and the imported ROM go. On first launch this asks the
     * player (and requests the storage permission if needed), blocking the native thread
     * until they answer. That happens before the game creates its window.
     */
    @SuppressWarnings("unused")
    public String getDataDirectory() {
        return storage.resolveDataDirectory();
    }


    /**
     * Asks for a steady 60 Hz while the game runs. Screens with adaptive refresh (e.g. a
     * Galaxy Z Fold, 1-120 Hz) otherwise keep changing their rate, and 30 or 60 FPS lands on
     * the refreshes unevenly, so the frame rate looks all over the place even when the game
     * keeps up. 60 Hz divides evenly by both of the game's rates. Screens that only do one
     * rate are left alone.
     */
    private int preferredModeId = -1;

    // ------------------------------------------------------------------------------
    // Touch controls
    // ------------------------------------------------------------------------------

    private static final String PREFS_TOUCH = "touch";

    /** Input > Touch > Touch Controls: 0 auto (only without a controller), 1 on, 2 off. */
    private int touchControlsPref() {
        return getSharedPreferences(PREFS_TOUCH, MODE_PRIVATE).getInt("touch_controls", 0);
    }

    /** Shows the on-screen controller when it's wanted, and the bottom screen if there's one. */
    private void updateTouchControls() {
        if (overlay == null) {
            return;
        }
        int mode = touchControlsPref();
        overlay.setActive(mode == 1 || (mode == 0 && !hasPhysicalGamepad()));

        Display second = started && bottomScreenEnabled() ? findSecondDisplay() : null;
        if (second == null || (presentation != null && presentation.getDisplay().getDisplayId() != second.getDisplayId())) {
            dismissPresentation();
        }
        if (second != null && presentation == null) {
            showPresentation(second);
        }
    }

    // ------------------------------------------------------------------------------
    // Bottom screen (a second display)
    // ------------------------------------------------------------------------------

    /** Input > Dual Screen > Bottom Screen (kept here too, so it applies from the start). */
    private boolean bottomScreenEnabled() {
        return getSharedPreferences(PREFS_TOUCH, MODE_PRIVATE).getBoolean("bottom_screen", true);
    }

    /** From the game's settings (any thread): "1" on, "0" off. */
    @SuppressWarnings("unused")
    public String setBottomScreen(String on) {
        getSharedPreferences(PREFS_TOUCH, MODE_PRIVATE).edit().putBoolean("bottom_screen", "1".equals(on)).apply();
        runOnUiThread(this::updateTouchControls);
        return null;
    }

    /** A display other than ours that can host a presentation, if any. */
    @SuppressWarnings("deprecation")
    private Display findSecondDisplay() {
        Display ours = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ? getDisplay() : getWindowManager().getDefaultDisplay();
        for (Display display : displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)) {
            if ((ours == null || display.getDisplayId() != ours.getDisplayId()) && display.isValid()) {
                return display;
            }
        }
        return null;
    }

    private void showPresentation(Display display) {
        try {
            presentation = new SecondScreenPresentation(this, display);
            presentation.setOnDismissListener(d -> {
                if (presentation == d) {
                    presentation = null;
                }
            });
            presentation.show();
            Log.i(TAG, "Bottom screen on display " + display.getDisplayId() + " (" + display.getName() + ")");
        } catch (WindowManager.InvalidDisplayException e) {
            Log.w(TAG, "Second display went away", e);
            presentation = null;
        }
    }

    private void dismissPresentation() {
        if (presentation != null) {
            SecondScreenPresentation old = presentation;
            presentation = null;
            old.dismiss();
        }
    }

    /** From the game's settings (any thread): "" reads the mode, "0"-"2" sets it. */
    @SuppressWarnings("unused")
    public String touchControlsMode(String mode) {
        if (mode != null && !mode.isEmpty()) {
            int value = Math.max(0, Math.min(2, mode.charAt(0) - '0'));
            getSharedPreferences(PREFS_TOUCH, MODE_PRIVATE).edit().putInt("touch_controls", value).apply();
            runOnUiThread(this::updateTouchControls);
            return String.valueOf(value);
        }
        return String.valueOf(touchControlsPref());
    }

    /** From the game's settings (Input > Touch > Edit Touch Layout): opens the layout editor. */
    @SuppressWarnings("unused")
    public String editTouchLayout(String unused) {
        runOnUiThread(() -> {
            if (overlay != null) {
                overlay.bringToFront();
                overlay.startEditing();
            }
        });
        return null;
    }

    /** A real gamepad (a handheld's built-in controls count) is connected. */
    private boolean hasPhysicalGamepad() {
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice device = InputDevice.getDevice(id);
            if (device == null || device.isVirtual()) {
                continue;
            }
            int sources = device.getSources();
            if ((sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                    || (sources & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) {
                return true;
            }
        }
        return false;
    }

    @Override public void onInputDeviceAdded(int deviceId) { updateTouchControls(); }
    @Override public void onInputDeviceRemoved(int deviceId) { updateTouchControls(); }
    @Override public void onInputDeviceChanged(int deviceId) { updateTouchControls(); }

    @Override public void onDisplayAdded(int displayId) { updateTouchControls(); }
    @Override public void onDisplayRemoved(int displayId) { updateTouchControls(); }
    @Override public void onDisplayChanged(int displayId) {
        // Folding or unfolding moves the window to a different screen with its own modes.
        Display d = getWindow().getDecorView().getDisplay();
        if (d != null && d.getDisplayId() == displayId) {
            preferSixtyHz();
        }
    }

    private void preferSixtyHz() {
        Display display = getWindow().getDecorView().getDisplay();
        if (display == null) {
            display = getWindowManager().getDefaultDisplay();
        }
        if (display == null) {
            return;
        }
        Display.Mode current = display.getMode();
        Display.Mode best = null;
        for (Display.Mode m : display.getSupportedModes()) {
            if (m.getPhysicalWidth() != current.getPhysicalWidth() || m.getPhysicalHeight() != current.getPhysicalHeight()) {
                continue;   // only change the refresh rate, never the resolution
            }
            float rate = m.getRefreshRate();
            if (rate < 59f || rate > 61f) {
                continue;
            }
            best = m;
        }
        if (best == null) {
            return;
        }
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        if (lp.preferredDisplayModeId == best.getModeId() && preferredModeId == best.getModeId()) {
            return;
        }
        lp.preferredDisplayModeId = best.getModeId();
        lp.preferredRefreshRate = best.getRefreshRate();
        getWindow().setAttributes(lp);
        preferredModeId = best.getModeId();
        Log.i(TAG, "Display: asked for " + best.getRefreshRate() + " Hz (mode " + best.getModeId() + ", "
            + best.getPhysicalWidth() + "x" + best.getPhysicalHeight() + "; was " + current.getRefreshRate() + " Hz)");
    }

    // ------------------------------------------------------------------------------
    // Asset extraction
    // ------------------------------------------------------------------------------

    private void extractAssetsIfNeeded() {
        File target = new File(getFilesDir(), ASSET_ROOT);
        File marker = new File(target, ".extracted-version");
        String version = currentVersionTag();

        if (marker.isFile() && version.equals(readSmallFile(marker))) {
            return;
        }

        Log.i(TAG, "Extracting game assets to " + target);
        deleteRecursively(target);
        try {
            copyAssetTree(getAssets(), ASSET_ROOT, target);
            writeSmallFile(marker, version);
        } catch (IOException e) {
            Log.e(TAG, "Asset extraction failed", e);
        }
    }

    private String currentVersionTag() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            // lastUpdateTime changes on every install, so a reinstalled debug build with the
            // same versionCode still refreshes its assets.
            return info.versionName + "/" + info.lastUpdateTime;
        } catch (Exception e) {
            return "unknown";
        }
    }

    private static void copyAssetTree(AssetManager assets, String assetPath, File destination) throws IOException {
        String[] children = assets.list(assetPath);
        if (children == null || children.length == 0) {
            // A file (AssetManager.list returns an empty array for files).
            File parent = destination.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                throw new IOException("Could not create " + parent);
            }
            try (InputStream in = assets.open(assetPath); OutputStream out = new FileOutputStream(destination)) {
                copyStream(in, out);
            }
            return;
        }
        if (!destination.isDirectory() && !destination.mkdirs()) {
            throw new IOException("Could not create " + destination);
        }
        for (String child : children) {
            copyAssetTree(assets, assetPath + "/" + child, new File(destination, child));
        }
    }

    // ------------------------------------------------------------------------------
    // File picker used by android/jni/nfd_android.cpp
    // ------------------------------------------------------------------------------

    /**
     * Called from a native (non-UI) thread. Shows the system picker, waits for the user,
     * and returns real paths for the chosen documents, separated by '\n', or null if the
     * user cancelled. Documents the app can read directly (shared-folder mode) are used in
     * place; anything else is copied into app storage first.
     */
    @SuppressWarnings("unused")
    public String nfdRequest(int mode) {
        synchronized (pickLock) {
            pickResult = null;
            pickLatch = new CountDownLatch(1);

            runOnUiThread(() -> {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                // N64 ROMs and .nrm mods have no registered MIME type.
                intent.setType("*/*");
                if (mode == MODE_OPEN_MULTIPLE) {
                    intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                }
                try {
                    startActivityForResult(intent, PICK_REQUEST_CODE);
                } catch (Exception e) {
                    Log.e(TAG, "No document picker available", e);
                    pickLatch.countDown();
                }
            });

            try {
                pickLatch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
            return pickResult;
        }
    }


    /**
     * From native code (QUIT > Main Menu): restarts the app on the launcher. The game can't go
     * back to the launcher while it runs, so the app starts over.
     */
    @SuppressWarnings("unused")
    public String restartToLauncher() {
        Log.i(TAG, "Returning to the main menu: restarting the app");
        Diagnostics.markLeaving(this);
        runOnUiThread(() -> new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            // The short wait lets a save the game just made reach the file.
            startActivity(new Intent(this, RestartActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            overridePendingTransition(0, 0);
        }, 400));
        return null;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (storage != null && storage.onActivityResult(requestCode, resultCode, data)) {
            return;   // the data folder picker (first launch)
        }
        if (requestCode != PICK_REQUEST_CODE) {
            super.onActivityResult(requestCode, resultCode, data);
            return;
        }

        if (resultCode != RESULT_OK || data == null) {
            finishPick(null);
            return;
        }

        List<Uri> uris = new ArrayList<>();
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                uris.add(clip.getItemAt(i).getUri());
            }
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }

        // Copy off the UI thread: a ROM is tens of megabytes.
        new Thread(() -> {
            StringBuilder paths = new StringBuilder();
            for (Uri uri : uris) {
                try {
                    File file = storage.directFileFor(uri);
                    if (file == null) {
                        file = importDocument(uri);
                    }
                    if (paths.length() > 0) {
                        paths.append('\n');
                    }
                    paths.append(file.getAbsolutePath());
                } catch (IOException e) {
                    Log.e(TAG, "Could not import " + uri, e);
                }
            }
            finishPick(paths.length() > 0 ? paths.toString() : null);
        }, "bt-import").start();
    }

    private void finishPick(String result) {
        pickResult = result;
        if (pickLatch != null) {
            pickLatch.countDown();
        }
    }

    /** Copies a picked document into <app external files>/imports, keeping its display name. */
    private File importDocument(Uri uri) throws IOException {
        File base = getExternalFilesDir(null);
        if (base == null) {
            base = getFilesDir();
        }
        File importDir = new File(base, "imports");
        if (!importDir.isDirectory() && !importDir.mkdirs()) {
            throw new IOException("Could not create " + importDir);
        }

        File destination = new File(importDir, sanitize(displayName(uri)));
        try (InputStream in = getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(destination)) {
            if (in == null) {
                throw new IOException("Provider returned no stream");
            }
            copyStream(in, out);
        }
        return destination;
    }

    private String displayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, new String[] { OpenableColumns.DISPLAY_NAME }, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                String name = cursor.getString(0);
                if (name != null && !name.isEmpty()) {
                    return name;
                }
            }
        } catch (Exception ignored) {
        }
        String last = uri.getLastPathSegment();
        return last != null ? last : "imported_file";
    }

    private static String sanitize(String name) {
        return name.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    // ------------------------------------------------------------------------------
    // Small IO helpers
    // ------------------------------------------------------------------------------

    private static void copyStream(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[1 << 16];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
    }

    private static String readSmallFile(File file) {
        try (InputStream in = new java.io.FileInputStream(file)) {
            byte[] bytes = new byte[(int) Math.min(file.length(), 4096)];
            int read = in.read(bytes);
            return read > 0 ? new String(bytes, 0, read, "UTF-8") : "";
        } catch (IOException e) {
            return "";
        }
    }

    private static void writeSmallFile(File file, String contents) throws IOException {
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(contents.getBytes("UTF-8"));
        }
    }

    private static void deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}
