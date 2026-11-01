package com.folio.launcher;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

/** Process-wide hook: shows the Folio lock screen whenever the display turns off. */
public class FolioApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                if (LockActivity.mode(c) != LockActivity.OFF) LockActivity.show(c);
            }
        }, new IntentFilter(Intent.ACTION_SCREEN_OFF));
    }
}
