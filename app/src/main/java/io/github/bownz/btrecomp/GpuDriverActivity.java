package io.github.bownz.btrecomp;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Process;
import android.widget.Toast;

import java.util.List;

/**
 * "Graphics driver" screen, opened from the app icon's long-press shortcut. Lists the system
 * driver and any installed custom drivers, installs new ones from a .zip, and restarts the
 * game so the choice takes effect.
 *
 * Runs in its own process (":settings") so it can restart the game's process.
 */
public final class GpuDriverActivity extends Activity {
    private static final int PICK_ZIP = 0x6770;

    private boolean changed;

    /** Opens straight to the driver list (from the in-game settings). */
    static final String EXTRA_DRIVERS = "drivers";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // (Static shortcuts pass extras as strings.)
        if (getIntent().getBooleanExtra(EXTRA_DRIVERS, false) || "true".equals(getIntent().getStringExtra(EXTRA_DRIVERS))) {
            showChooser();
        } else {
            showTools();
        }
    }

    /** "Banjo-Tooie Tools": graphics driver, share log, version. */
    private void showTools() {
        GpuDrivers.Driver current = GpuDrivers.selected(this);
        String[] items = {
            "Graphics driver: " + (current == null ? "System driver" : current.label()),
            "Share log",
        };
        new AlertDialog.Builder(this)
            .setTitle("Banjo-Tooie Tools · " + Diagnostics.version(this))
            .setItems(items, (dialog, which) -> {
                if (which == 0) {
                    showChooser();
                } else {
                    Diagnostics.share(this, null);
                    finish();
                }
            })
            .setOnCancelListener(dialog -> finish())
            .show();
    }

    /**
     * The driver menu (Settings > Video > Graphics Driver): the system driver and each
     * installed one, with Apply (restarts the app to use it), Install and Remove.
     */
    private void showChooser() {
        List<GpuDrivers.Driver> drivers = GpuDrivers.installed(this);
        GpuDrivers.Driver current = GpuDrivers.selected(this);
        String[] labels = new String[drivers.size() + 1];
        labels[0] = "System driver" + (current == null ? "  (in use)" : "");
        int checked = 0;
        for (int i = 0; i < drivers.size(); i++) {
            boolean inUse = current != null && current.id.equals(drivers.get(i).id);
            labels[i + 1] = drivers.get(i).label() + (inUse ? "  (in use)" : "");
            if (inUse) {
                checked = i + 1;
            }
        }
        final int[] choice = { checked };
        AlertDialog dialog = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Graphics Driver")
            .setSingleChoiceItems(labels, checked, (d, which) -> {
                choice[0] = which;
                updateButtons((AlertDialog) d, which, drivers, GpuDrivers.selected(this));
            })
            .setPositiveButton("Apply", (d, which) -> {
                GpuDrivers.Driver picked = choice[0] == 0 ? null : drivers.get(choice[0] - 1);
                GpuDrivers.Driver before = GpuDrivers.selected(this);
                boolean differs = (before == null) != (picked == null) || (before != null && !before.id.equals(picked.id));
                if (!differs) {
                    finish();
                    return;
                }
                GpuDrivers.select(this, picked);
                restartGame();
            })
            .setNeutralButton("Install…", (d, which) -> pickZip())
            .setNegativeButton("Remove", (d, which) -> {
                if (choice[0] == 0) {
                    showChooser();
                    return;
                }
                confirmRemove(drivers.get(choice[0] - 1));
            })
            .setOnCancelListener(d -> finish())
            .create();
        dialog.setOnShowListener(d -> updateButtons(dialog, choice[0], drivers, current));
        dialog.show();
    }

    /** Apply only for a different driver; Remove only for an installed one. */
    private static void updateButtons(AlertDialog dialog, int which, List<GpuDrivers.Driver> drivers, GpuDrivers.Driver current) {
        GpuDrivers.Driver picked = which == 0 ? null : drivers.get(which - 1);
        boolean differs = (current == null) != (picked == null) || (current != null && !current.id.equals(picked.id));
        android.widget.Button apply = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        android.widget.Button remove = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
        if (apply != null) {
            apply.setEnabled(differs);
            apply.setText(differs ? "Apply & restart" : "Apply");
        }
        if (remove != null) {
            remove.setEnabled(which != 0);
        }
    }

    private void confirmRemove(GpuDrivers.Driver driver) {
        GpuDrivers.Driver current = GpuDrivers.selected(this);
        boolean inUse = current != null && current.id.equals(driver.id);
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Remove " + driver.label() + "?")
            .setMessage(inUse ? "It's the driver in use: the app goes back to the system driver and restarts." : "It's removed from the app.")
            .setPositiveButton("Remove", (d, which) -> {
                GpuDrivers.remove(this, driver);
                if (inUse) {
                    GpuDrivers.select(this, null);
                    restartGame();
                } else {
                    showChooser();
                }
            })
            .setNegativeButton("Cancel", (d, which) -> showChooser())
            .setOnCancelListener(d -> showChooser())
            .show();
    }

    /** Uses the new driver: restarts the game's process, back on the main menu. */
    private void restartGame() {
        Toast.makeText(this, "Restarting with the new graphics driver…", Toast.LENGTH_SHORT).show();
        // Back to the main menu even with Skip Launcher on (BTActivity.takeShowLauncherOnce).
        getSharedPreferences("launch", MODE_PRIVATE).edit().putBoolean("show_launcher_once", true).commit();
        startActivity(new Intent(this, RestartActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        finish();
    }

    private void pickZip() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[] { "application/zip", "application/x-zip-compressed", "application/octet-stream" });
        startActivityForResult(intent, PICK_ZIP);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_ZIP) {
            return;
        }
        Uri uri = resultCode == RESULT_OK && data != null ? data.getData() : null;
        if (uri != null) {
            try {
                GpuDrivers.Driver d = GpuDrivers.install(this, uri);
                Toast.makeText(this, "Installed " + d.label(), Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                new AlertDialog.Builder(this)
                    .setTitle("Couldn't install the driver")
                    .setMessage(e.getMessage())
                    .setPositiveButton("OK", (dialog, which) -> showChooser())
                    .setOnCancelListener(dialog -> showChooser())
                    .show();
                return;
            }
        }
        showChooser();
    }

    /** Applies a change by restarting the game (if it's running), then closes. */
    private void finishWithRestart() {
        if (changed && gameRunning()) {
            new AlertDialog.Builder(this)
                .setTitle("Restart the game?")
                .setMessage("The new graphics driver is used from the next start. Unsaved progress will be lost if you restart now.")
                .setPositiveButton("Restart now", (dialog, which) -> {
                    killGame();
                    startActivity(new Intent(this, BTActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    finish();
                })
                .setNegativeButton("Later", (dialog, which) -> finish())
                .setOnCancelListener(dialog -> finish())
                .show();
            return;
        }
        finish();
    }

    private ActivityManager.RunningAppProcessInfo gameProcess() {
        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        List<ActivityManager.RunningAppProcessInfo> processes = am.getRunningAppProcesses();
        if (processes == null) {
            return null;
        }
        for (ActivityManager.RunningAppProcessInfo info : processes) {
            if (info.processName.equals(getPackageName())) {
                return info;
            }
        }
        return null;
    }

    private boolean gameRunning() {
        return gameProcess() != null;
    }

    private void killGame() {
        ActivityManager.RunningAppProcessInfo game = gameProcess();
        if (game != null) {
            Process.killProcess(game.pid);
        }
    }
}
