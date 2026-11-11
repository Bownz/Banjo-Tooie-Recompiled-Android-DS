package io.github.bownz.btrecomp;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import java.io.File;
import java.util.concurrent.CountDownLatch;

/**
 * Decides where Banjo-Tooie keeps saves, config, mods and the imported ROM, and asks for the
 * storage permission that the shared-folder option needs.
 *
 * Two options, chosen once on first launch and remembered:
 *  - Shared folder: a folder the player picks with the system folder picker (one they
 *    already use, or a new one; "BanjoTooieRecompiled" at the top of the device's storage is
 *    suggested). Easy to reach from a PC or file manager to drop in mods, and it survives
 *    uninstalling the app. Needs "All files access" on Android 11+ (a Settings screen, not
 *    a dialog), or the classic storage permission on Android 9-10, since the game opens
 *    the folder by its path.
 *  - App storage: Android/data/<package>/files. Needs no permission; removed on uninstall.
 *
 * {@link #resolveDataDirectory()} is called from the game's native thread before it
 * creates its window, and blocks until the player has answered. The activity must
 * forward onResume and onRequestPermissionsResult so the answer is noticed.
 *
 * The choice can be changed later ({@link #startChange()}, Settings > Gameplay > Game Files
 * Location, main menu only): the files are copied to the new place, the old copy can be
 * removed, and the app restarts using the new place.
 */
final class StorageAccess {
    private static final String TAG = "BTRecomp";
    private static final String PREFS = "storage";
    private static final String KEY_MODE = "mode";
    private static final String KEY_SHARED_PATH = "shared_path";   // the picked folder
    static final int REQUEST_PICK_FOLDER = 0x5355;
    private static final String MODE_SHARED = "shared";
    private static final String MODE_APP = "app";
    static final String SHARED_FOLDER_NAME = "BanjoTooieRecompiled";
    static final int REQUEST_LEGACY_STORAGE = 0x5354;

    private final Activity activity;
    private final SharedPreferences prefs;

    private CountDownLatch answer;
    private String resolvedPath;
    // The folder in use this run, and whether a change of location is under way (the
    // shared-folder steps then lead to moving the files instead of the first-run choice).
    private volatile String currentPath;
    private boolean changing;
    private boolean waitingForSettings;
    private boolean waitingForPermissionDialog;

    StorageAccess(Activity activity) {
        this.activity = activity;
        this.prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------------------------
    // Queries
    // ------------------------------------------------------------------------------

    static boolean hasSharedStorageAccess(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        }
        return context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
            && context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    boolean usesSharedFolder() {
        return MODE_SHARED.equals(prefs.getString(KEY_MODE, null)) && hasSharedStorageAccess(activity);
    }

    private File defaultSharedFolder() {
        return new File(Environment.getExternalStorageDirectory(), SHARED_FOLDER_NAME);
    }

    /** The picked folder, or the default one (picked before folders could be chosen). */
    private File sharedFolder() {
        String picked = prefs.getString(KEY_SHARED_PATH, null);
        return picked != null ? new File(picked) : defaultSharedFolder();
    }

    private File appFolder() {
        File external = activity.getExternalFilesDir(null);
        return external != null ? external : new File(activity.getFilesDir(), "data");
    }

    /**
     * For a document picked with the system file picker, returns the real file when the
     * app can read it directly (shared-folder mode), so a ROM needn't be copied. Returns
     * null when the document has to be copied in through its content stream instead.
     */
    File directFileFor(Uri uri) {
        if (!usesSharedFolder() || uri == null || !DocumentsContract.isDocumentUri(activity, uri)) {
            return null;
        }
        String id = DocumentsContract.getDocumentId(uri);
        File file = null;
        if ("com.android.externalstorage.documents".equals(uri.getAuthority()) && id.startsWith("primary:")) {
            file = new File(Environment.getExternalStorageDirectory(), id.substring("primary:".length()));
        } else if (id.startsWith("raw:")) {
            // The Downloads provider exposes some documents by their real path.
            file = new File(id.substring("raw:".length()));
        }
        return (file != null && file.isFile() && file.canRead()) ? file : null;
    }

    // ------------------------------------------------------------------------------
    // Resolution (called from the native thread)
    // ------------------------------------------------------------------------------

    /** Returns the user-data directory, asking the player first if they haven't chosen. */
    String resolveDataDirectory() {
        currentPath = resolveDataDirectoryInner();
        return currentPath;
    }

    private String resolveDataDirectoryInner() {
        String mode = prefs.getString(KEY_MODE, null);

        if (MODE_APP.equals(mode)) {
            return ensureDirectory(appFolder());
        }
        if (MODE_SHARED.equals(mode)) {
            if (hasSharedStorageAccess(activity)) {
                String path = ensureDirectory(sharedFolder());
                if (path != null) {
                    return path;
                }
            }
            Log.w(TAG, "Shared folder chosen but not accessible (permission removed?); asking again");
        }

        answer = new CountDownLatch(1);
        activity.runOnUiThread(this::showChoice);
        try {
            answer.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ensureDirectory(appFolder());
        }
        return resolvedPath;
    }

    private void showChoice() {
        new StorageDialog(activity, new StorageDialog.Listener() {
            @Override
            public void onShared() {
                requestSharedAccess();
            }

            @Override
            public void onApp() {
                choose(MODE_APP);
            }
        }).show();
    }

    private void requestSharedAccess() {
        if (hasSharedStorageAccess(activity)) {
            finishSharedRequest();
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // "All files access" is granted on a Settings screen (the popup has just explained
            // the switch); we hear back in onResume().
            waitingForSettings = true;
            Intent appScreen = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:" + activity.getPackageName()));
            try {
                activity.startActivity(appScreen);
            } catch (ActivityNotFoundException e) {
                try {
                    activity.startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                } catch (ActivityNotFoundException e2) {
                    waitingForSettings = false;
                    Log.w(TAG, "No settings screen for all-files access", e2);
                    finishSharedRequest();
                }
            }
        } else {
            waitingForPermissionDialog = true;
            activity.requestPermissions(new String[] {
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
            }, REQUEST_LEGACY_STORAGE);
        }
    }

    /** Forward from Activity.onResume(): picks up the result of the Settings screen. */
    void onResume() {
        if (waitingForSettings) {
            waitingForSettings = false;
            finishSharedRequest();
        }
    }

    /** Forward from Activity.onRequestPermissionsResult(). Returns true if it was ours. */
    boolean onRequestPermissionsResult(int requestCode) {
        if (requestCode != REQUEST_LEGACY_STORAGE || !waitingForPermissionDialog) {
            return false;
        }
        waitingForPermissionDialog = false;
        finishSharedRequest();
        return true;
    }

    private void finishSharedRequest() {
        if (hasSharedStorageAccess(activity)) {
            pickFolder();
        } else if (changing) {
            changing = false;
            Toast.makeText(activity, R.string.storage_denied_change, Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(activity, R.string.storage_denied, Toast.LENGTH_LONG).show();
            choose(MODE_APP);
        }
    }

    // ------------------------------------------------------------------------------
    // Picking the shared folder
    // ------------------------------------------------------------------------------

    private void pickFolder() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        // Start in BanjoTooieRecompiled if it's there, else at the top of the storage.
        File start = defaultSharedFolder().isDirectory() ? defaultSharedFolder() : Environment.getExternalStorageDirectory();
        String rel = start.equals(Environment.getExternalStorageDirectory()) ? "" : start.getName();
        intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, DocumentsContract.buildDocumentUri(
            "com.android.externalstorage.documents", "primary:" + rel));
        try {
            activity.startActivityForResult(intent, REQUEST_PICK_FOLDER);
        } catch (ActivityNotFoundException e) {
            // No folder picker on this device: use the default folder.
            Log.w(TAG, "No folder picker; using " + defaultSharedFolder(), e);
            useSharedFolder(defaultSharedFolder());
        }
    }

    /** Forward from Activity.onActivityResult(). Returns true if it was ours. */
    boolean onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST_PICK_FOLDER) {
            return false;
        }
        File folder = resultCode == Activity.RESULT_OK && data != null ? folderFor(data.getData()) : null;
        if (folder == null) {
            if (resultCode == Activity.RESULT_OK) {
                Toast.makeText(activity, R.string.storage_bad_folder, Toast.LENGTH_LONG).show();
            }
            if (changing) {
                changing = false;   // a change of location was cancelled: nothing moves
            } else {
                showChoice();   // cancelled or unusable: back to the choice
            }
            return true;
        }
        useSharedFolder(folder);
        return true;
    }

    private void useSharedFolder(File folder) {
        if (ensureDirectory(folder) == null || !folder.canWrite()) {
            Toast.makeText(activity, R.string.storage_bad_folder, Toast.LENGTH_LONG).show();
            if (changing) {
                changing = false;
            } else {
                showChoice();
            }
            return;
        }
        if (changing) {
            changing = false;
            moveTo(folder, MODE_SHARED);
            return;
        }
        prefs.edit().putString(KEY_SHARED_PATH, folder.getAbsolutePath()).apply();
        choose(MODE_SHARED);
    }

    /** The real folder behind a picked tree, for internal storage or an SD card. */
    private static File folderFor(Uri tree) {
        if (tree == null || !"com.android.externalstorage.documents".equals(tree.getAuthority())) {
            return null;
        }
        String id;
        try {
            id = DocumentsContract.getTreeDocumentId(tree);
        } catch (IllegalArgumentException e) {
            return null;
        }
        int colon = id.indexOf(':');
        if (colon < 0) {
            return null;
        }
        String volume = id.substring(0, colon), path = id.substring(colon + 1);
        File root = "primary".equalsIgnoreCase(volume) ? Environment.getExternalStorageDirectory() : new File("/storage/" + volume);
        File folder = path.isEmpty() ? root : new File(root, path);
        return folder.isDirectory() ? folder : null;
    }

    private void choose(String mode) {
        prefs.edit().putString(KEY_MODE, mode).apply();
        String path = MODE_SHARED.equals(mode) ? ensureDirectory(sharedFolder()) : null;
        if (path == null) {
            path = ensureDirectory(appFolder());
        }
        Log.i(TAG, "User data folder: " + path);
        resolvedPath = path;
        if (answer != null) {
            answer.countDown();
        }
    }

    // ------------------------------------------------------------------------------
    // Changing the location later (Settings > Gameplay > Game Files Location)
    // ------------------------------------------------------------------------------

    /** A short description of where the files are now, for the settings button. */
    String describeLocation() {
        String path = currentPath;
        if (path == null) {
            return "";
        }
        return (usesSharedFolder() ? "a shared folder, " : "app storage, ") + path;
    }

    /** Shows where the files are and offers to move them. Call on the UI thread. */
    void startChange() {
        final String path = currentPath;
        if (path == null) {
            return;
        }
        final boolean shared = usesSharedFolder();
        android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(activity)
            .setTitle("Game files location")
            .setMessage("Your saves, settings, mods and ROM are in "
                + (shared ? "a shared folder" : "app storage") + ":\n\n" + path + "\n\n"
                + (shared
                    ? "App storage needs no permission, but is removed if the app is uninstalled and is hard to reach from a PC."
                    : "A shared folder is easy to reach from a PC or file manager (to add mods) and stays if the app is uninstalled. "
                        + "It needs the \"All files access\" permission.")
                + "\n\nMoving copies everything to the new place, then the app restarts.")
            .setNegativeButton("Cancel", null);
        if (shared) {
            b.setPositiveButton("Move to app storage", (d, w) -> moveTo(appFolder(), MODE_APP));
            b.setNeutralButton("Pick another folder…", (d, w) -> {
                changing = true;
                requestSharedAccess();
            });
        } else {
            b.setPositiveButton("Move to a shared folder…", (d, w) -> {
                changing = true;
                requestSharedAccess();
            });
        }
        b.show();
    }

    /** Copies the game's files to {@code dest}, then switches to it and restarts. */
    private void moveTo(File dest, String mode) {
        final File src = new File(currentPath);
        final File to;
        try {
            File a = src.getCanonicalFile(), b = dest.getCanonicalFile();
            if (a.equals(b)) {
                Toast.makeText(activity, "The files are already there.", Toast.LENGTH_SHORT).show();
                return;
            }
            if (b.getPath().startsWith(a.getPath() + File.separator) || a.getPath().startsWith(b.getPath() + File.separator)) {
                Toast.makeText(activity, "Pick a folder that isn't inside the current one (or around it).", Toast.LENGTH_LONG).show();
                return;
            }
            to = b;
        } catch (java.io.IOException e) {
            Toast.makeText(activity, R.string.storage_bad_folder, Toast.LENGTH_LONG).show();
            return;
        }
        if (ensureDirectory(to) == null || !to.canWrite()) {
            Toast.makeText(activity, R.string.storage_bad_folder, Toast.LENGTH_LONG).show();
            return;
        }
        if (hasGameFiles(to)) {
            new android.app.AlertDialog.Builder(activity)
                .setTitle("That folder already has Banjo-Tooie files")
                .setMessage(to.getAbsolutePath() + " already has saves, settings or a ROM from before.\n\n"
                    + "Use the files there (your current ones stay where they are), or replace them with your current files?")
                .setPositiveButton("Replace with mine", (d, w) -> copyThenSwitch(src, to, mode))
                .setNeutralButton("Use the files there", (d, w) -> switchTo(to, mode, null))
                .setNegativeButton("Cancel", null)
                .show();
            return;
        }
        copyThenSwitch(src, to, mode);
    }

    private static boolean hasGameFiles(File dir) {
        File saves = new File(dir, "saves");
        String[] inSaves = saves.list();
        if (inSaves != null && inSaves.length > 0) {
            return true;
        }
        if (new File(dir, "bt.n64.us.1.0.z64").isFile()) {
            return true;
        }
        String[] names = dir.list();
        if (names != null) {
            for (String n : names) {
                if (n.endsWith(".json")) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Copies everything in {@code src} to {@code dest} (replacing what's there), with progress. */
    private void copyThenSwitch(File src, File dest, String mode) {
        final android.app.AlertDialog progress = new android.app.AlertDialog.Builder(activity)
            .setTitle("Moving game files")
            .setMessage("Getting ready…")
            .setCancelable(false)
            .show();
        new Thread(() -> {
            java.util.List<String> files = new java.util.ArrayList<>();
            listFiles(src, "", files);
            long total = 0;
            for (String rel : files) {
                total += new File(src, rel).length();
            }
            long done = 0;
            String error = null;
            byte[] buffer = new byte[1 << 16];
            for (int i = 0; i < files.size() && error == null; i++) {
                String rel = files.get(i);
                File from = new File(src, rel), to = new File(dest, rel);
                final String status = "Copying " + (i + 1) + " of " + files.size() + " files ("
                    + (total > 0 ? (done * 100 / total) : 100) + "%)\n" + rel;
                activity.runOnUiThread(() -> progress.setMessage(status));
                File parent = to.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                    error = "Couldn't create " + parent;
                    break;
                }
                File temp = new File(to.getPath() + ".moving");
                try (java.io.InputStream in = new java.io.FileInputStream(from);
                     java.io.OutputStream out = new java.io.FileOutputStream(temp)) {
                    int n;
                    while ((n = in.read(buffer)) > 0) {
                        out.write(buffer, 0, n);
                    }
                } catch (java.io.IOException e) {
                    error = "Couldn't copy " + rel + ": " + e.getMessage();
                    temp.delete();
                    break;
                }
                if (temp.length() != from.length() || (to.exists() && !to.delete()) || !temp.renameTo(to)) {
                    error = "Couldn't write " + to;
                    temp.delete();
                    break;
                }
                to.setLastModified(from.lastModified());
                done += from.length();
            }
            final String failed = error;
            activity.runOnUiThread(() -> {
                progress.dismiss();
                if (failed != null) {
                    Log.w(TAG, "Moving game files failed: " + failed);
                    new android.app.AlertDialog.Builder(activity)
                        .setTitle("Couldn't move the files")
                        .setMessage(failed + "\n\nNothing was changed: the game keeps using " + src.getAbsolutePath() + ".")
                        .setPositiveButton("OK", null)
                        .show();
                    return;
                }
                Log.i(TAG, "Moved " + files.size() + " game files from " + src + " to " + dest);
                switchTo(dest, mode, new Runnable[] { () -> removeOldCopy(src, files) });
            });
        }, "Move game files").start();
    }

    private static void listFiles(File dir, String prefix, java.util.List<String> out) {
        File[] entries = dir.listFiles();
        if (entries == null) {
            return;
        }
        for (File f : entries) {
            String rel = prefix.isEmpty() ? f.getName() : prefix + File.separator + f.getName();
            if (f.isDirectory()) {
                listFiles(f, rel, out);
            } else if (f.isFile() && !f.getName().endsWith(".moving")) {
                out.add(rel);
            }
        }
    }

    /**
     * Uses {@code dest} from now on. With {@code removeOld}, first asks whether to remove the
     * copy in the old place. Then restarts the app, back on the main menu.
     */
    private void switchTo(File dest, String mode, Runnable[] removeOld) {
        SharedPreferences.Editor editor = prefs.edit().putString(KEY_MODE, mode);
        if (MODE_SHARED.equals(mode)) {
            editor.putString(KEY_SHARED_PATH, dest.getAbsolutePath());
        }
        editor.commit();
        if (removeOld == null) {
            restart();
            return;
        }
        new android.app.AlertDialog.Builder(activity)
            .setTitle("Files moved")
            .setMessage("Everything is now in " + dest.getAbsolutePath() + ".\n\nRemove the old copy in "
                + currentPath + "? Keeping it does no harm, but it's no longer used.")
            .setPositiveButton("Keep it", (d, w) -> restart())
            .setNegativeButton("Remove it", (d, w) -> {
                removeOld[0].run();
                restart();
            })
            .setCancelable(false)
            .show();
    }

    /** Deletes the files that were copied (and folders left empty), nothing else. */
    private static void removeOldCopy(File src, java.util.List<String> files) {
        for (String rel : files) {
            new File(src, rel).delete();
        }
        removeEmptyFolders(src);
    }

    private static void removeEmptyFolders(File dir) {
        File[] entries = dir.listFiles();
        if (entries == null) {
            return;
        }
        for (File f : entries) {
            if (f.isDirectory()) {
                removeEmptyFolders(f);
                f.delete();   // only succeeds if it's now empty
            }
        }
    }

    private void restart() {
        Toast.makeText(activity, "Restarting with the new location…", Toast.LENGTH_SHORT).show();
        activity.getSharedPreferences("launch", Context.MODE_PRIVATE).edit().putBoolean("show_launcher_once", true).commit();
        activity.startActivity(new Intent(activity, RestartActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        activity.finish();
    }

    private static String ensureDirectory(File dir) {
        if (dir.isDirectory() || dir.mkdirs()) {
            return dir.getAbsolutePath();
        }
        Log.w(TAG, "Could not create " + dir);
        return null;
    }
}
