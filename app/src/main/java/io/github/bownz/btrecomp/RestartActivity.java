package io.github.bownz.btrecomp;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;

import java.util.List;

/**
 * Restarts the game's process (used once after a ROM is supplied, see
 * BTActivity.restartAfterRomImport). Runs in its own process, invisibly: it stops the game,
 * waits for it to be gone, then opens it again.
 */
public class RestartActivity extends Activity {
    private static final long POLL_MS = 100, GIVE_UP_MS = 3000;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private long started;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        started = android.os.SystemClock.uptimeMillis();
        ActivityManager.RunningAppProcessInfo game = gameProcess();
        if (game != null) {
            Process.killProcess(game.pid);
        }
        handler.postDelayed(this::waitThenOpen, POLL_MS);
    }

    private void waitThenOpen() {
        if (gameProcess() != null && android.os.SystemClock.uptimeMillis() - started < GIVE_UP_MS) {
            handler.postDelayed(this::waitThenOpen, POLL_MS);
            return;
        }
        startActivity(new Intent(this, BTActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        finish();
        overridePendingTransition(0, 0);
    }

    private ActivityManager.RunningAppProcessInfo gameProcess() {
        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        List<ActivityManager.RunningAppProcessInfo> processes = am != null ? am.getRunningAppProcesses() : null;
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
}
