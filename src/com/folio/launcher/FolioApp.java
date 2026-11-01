package com.folio.launcher;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

/** Process-wide hooks: first-boot provisioning and the Folio lock screen whenever the display turns off. */
public class FolioApp extends Application {
    /**
     * On Folio OS there is no vendor setup wizard. The system treats an unprovisioned device as "still being
     * set up" (status bar and keys restricted), so Folio flags it as set up. Needs WRITE_SECURE_SETTINGS, which
     * only a system priv-app has; on a normal install the calls fail silently and nothing changes.
     */
    private void markProvisioned() {
        try {
            android.content.ContentResolver cr = getContentResolver();
            if (android.provider.Settings.Global.getInt(cr, android.provider.Settings.Global.DEVICE_PROVISIONED, 0) == 0) {
                android.provider.Settings.Global.putInt(cr, android.provider.Settings.Global.DEVICE_PROVISIONED, 1);
            }
            if (android.provider.Settings.Secure.getInt(cr, "user_setup_complete", 0) == 0) {
                android.provider.Settings.Secure.putInt(cr, "user_setup_complete", 1);
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        markProvisioned();
        registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                if (LockActivity.mode(c) != LockActivity.OFF) LockActivity.show(c);
            }
        }, new IntentFilter(Intent.ACTION_SCREEN_OFF));
    }
}
