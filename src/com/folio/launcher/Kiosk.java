package com.folio.launcher;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;

/** Kindle-style lock: immersive screen, screen pinning, hidden exit gesture. */
final class Kiosk {
    /** Hidden-exit PIN, generated at build time from the git-ignored pin.txt. */
    private static String pin(Context c) {
        return c.getString(c.getResources().getIdentifier("kiosk_pin", "string", c.getPackageName()));
    }
    private static final long EXIT_GRACE_MS = 5 * 60 * 1000;
    private static long suppressUntil;

    static void apply(Activity a) {
        a.getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        if (System.currentTimeMillis() < suppressUntil) return;
        lockDown(a, true);
        try {
            ActivityManager am = (ActivityManager) a.getSystemService(Context.ACTIVITY_SERVICE);
            if (am.getLockTaskModeState() == ActivityManager.LOCK_TASK_MODE_NONE) a.startLockTask();
        } catch (Exception ignored) {
        }
    }

    /**
     * Device-owner policies: pin without the system prompt, hide the status bar,
     * drop the lock screen. No-op until Folio is set as device owner.
     */
    private static void lockDown(Activity a, boolean on) {
        try {
            DevicePolicyManager dpm = (DevicePolicyManager) a.getSystemService(Context.DEVICE_POLICY_SERVICE);
            if (!dpm.isDeviceOwnerApp(a.getPackageName())) return;
            ComponentName admin = new ComponentName(a, AdminReceiver.class);
            dpm.setLockTaskPackages(admin, new String[]{a.getPackageName()});
            dpm.setStatusBarDisabled(admin, on);
            dpm.setKeyguardDisabled(admin, on);
        } catch (Exception ignored) {
        }
    }

    private static void releaseOwner(Activity a) {
        try {
            lockDown(a, false);
            DevicePolicyManager dpm = (DevicePolicyManager) a.getSystemService(Context.DEVICE_POLICY_SERVICE);
            dpm.clearDeviceOwnerApp(a.getPackageName());
            a.stopLockTask();
        } catch (Exception ignored) {
        }
    }

    /** Five taps on {@code corner} within four seconds ask for the PIN. */
    static void attachExit(final Activity a, View corner) {
        final int[] taps = {0};
        final long[] first = {0};
        corner.setOnClickListener(v -> {
            long now = SystemClock.uptimeMillis();
            if (now - first[0] > 4000) {
                taps[0] = 0;
                first[0] = now;
            }
            if (++taps[0] >= 5) {
                taps[0] = 0;
                askPin(a);
            }
        });
    }

    private static void askPin(final Activity a) {
        final EditText input = new EditText(a);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        new AlertDialog.Builder(a)
                .setTitle("Sair do Folio")
                .setView(input)
                .setNegativeButton("Cancelar", null)
                .setNeutralButton("Desativar quiosque", (d, w) -> {
                    if (pin(a).equals(input.getText().toString())) {
                        releaseOwner(a);
                        Toast.makeText(a, "Quiosque desativado", Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(a, "PIN incorreto", Toast.LENGTH_SHORT).show();
                    }
                })
                .setPositiveButton("Configurações", (d, w) -> {
                    if (pin(a).equals(input.getText().toString())) {
                        suppressUntil = System.currentTimeMillis() + EXIT_GRACE_MS;
                        lockDown(a, false);
                        try {
                            a.stopLockTask();
                        } catch (Exception ignored) {
                        }
                        a.startActivity(new Intent(Settings.ACTION_SETTINGS));
                    } else {
                        Toast.makeText(a, "PIN incorreto", Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
    }
}
