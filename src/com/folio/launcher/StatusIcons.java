package com.folio.launcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Environment;
import android.view.View;

import java.io.File;

/**
 * Status strip for the home header: SD card (only while a removable card is mounted), Wi-Fi level
 * and battery. The kiosk hides the system status bar, so the launcher shows these itself.
 * Listens to system broadcasts only while attached to a window.
 */
final class StatusIcons extends View {
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final Path path = new Path();
    private final float d;
    private int battery = -1;
    private boolean charging;
    private boolean sd;
    private int wifi = -1; // -1 no Wi-Fi, 0..4 signal bars

    private final BroadcastReceiver rx = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            String a = i.getAction();
            if (Intent.ACTION_BATTERY_CHANGED.equals(a)) readBattery(i);
            else if (a != null && a.startsWith("android.intent.action.MEDIA_")) readSd();
            else readWifi();
            invalidate();
        }
    };

    StatusIcons(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        setContentDescription("Bateria, Wi-Fi e cartão SD");
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        Context c = getContext();
        readBattery(c.registerReceiver(rx, new IntentFilter(Intent.ACTION_BATTERY_CHANGED)));
        IntentFilter net = new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION);
        net.addAction(WifiManager.RSSI_CHANGED_ACTION);
        net.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        c.registerReceiver(rx, net);
        IntentFilter media = new IntentFilter();
        for (String s : new String[]{Intent.ACTION_MEDIA_MOUNTED, Intent.ACTION_MEDIA_UNMOUNTED,
                Intent.ACTION_MEDIA_REMOVED, Intent.ACTION_MEDIA_EJECT, Intent.ACTION_MEDIA_BAD_REMOVAL}) {
            media.addAction(s);
        }
        media.addDataScheme("file");
        c.registerReceiver(rx, media);
        readWifi();
        readSd();
        invalidate();
    }

    @Override
    protected void onDetachedFromWindow() {
        try {
            getContext().unregisterReceiver(rx);
        } catch (Exception ignored) {
        }
        super.onDetachedFromWindow();
    }

    private void readBattery(Intent i) {
        if (i == null) return;
        int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        battery = level < 0 ? -1 : Math.round(level * 100f / scale);
        int st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL;
    }

    private void readWifi() {
        try {
            Context c = getContext().getApplicationContext();
            ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo ni = cm.getActiveNetworkInfo();
            if (ni == null || !ni.isConnected() || ni.getType() != ConnectivityManager.TYPE_WIFI) {
                wifi = -1;
                return;
            }
            WifiInfo wi = ((WifiManager) c.getSystemService(Context.WIFI_SERVICE)).getConnectionInfo();
            wifi = wi == null ? 2 : WifiManager.calculateSignalLevel(wi.getRssi(), 5);
        } catch (Exception e) {
            wifi = -1;
        }
    }

    /** True when a removable card (not the internal "external" storage) is mounted. */
    private void readSd() {
        boolean found = false;
        try {
            File[] dirs = getContext().getExternalFilesDirs(null);
            for (int i = 1; dirs != null && i < dirs.length; i++) {
                File f = dirs[i];
                if (f != null && Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState(f))
                        && Environment.isExternalStorageRemovable(f)) found = true;
            }
        } catch (Exception ignored) {
        }
        sd = found;
    }

    @Override
    protected void onMeasure(int w, int h) {
        setMeasuredDimension((int) ((sd ? 24 : 0) * d + 30 * d + 74 * d), (int) (28 * d));
    }

    @Override
    protected void onDraw(Canvas c) {
        float x = 0, mid = getHeight() / 2f;
        p.setColor(Theme.text);
        if (sd) {
            // SD card: a small card with a clipped corner and three contacts
            p.setStyle(Paint.Style.FILL);
            path.reset();
            path.moveTo(x + 2 * d, mid - 8 * d);
            path.lineTo(x + 11 * d, mid - 8 * d);
            path.lineTo(x + 15 * d, mid - 4 * d);
            path.lineTo(x + 15 * d, mid + 8 * d);
            path.lineTo(x + 2 * d, mid + 8 * d);
            path.close();
            c.drawPath(path, p);
            p.setColor(Theme.bg);
            for (int i = 0; i < 3; i++) c.drawRect(x + (5 + i * 3) * d, mid - 6 * d, x + (6.5f + i * 3) * d, mid - 2 * d, p);
            p.setColor(Theme.text);
            x += 24 * d;
        }
        // Wi-Fi: dot plus three arcs, filled up to the current level; crossed out when offline
        float cx = x + 11 * d, base = mid + 7 * d;
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2f * d);
        p.setStrokeCap(Paint.Cap.ROUND);
        for (int i = 0; i < 3; i++) {
            float rad = (5 + i * 4.5f) * d;
            r.set(cx - rad, base - rad, cx + rad, base + rad);
            int lit = wifi < 0 ? 0 : wifi <= 1 ? 1 : wifi <= 3 ? 2 : 3;
            p.setColor(i < lit ? Theme.text : Theme.border);
            c.drawArc(r, 225, 90, false, p);
        }
        p.setStyle(Paint.Style.FILL);
        p.setColor(wifi >= 0 ? Theme.text : Theme.border);
        c.drawCircle(cx, base, 1.7f * d, p);
        if (wifi < 0) {
            p.setStyle(Paint.Style.STROKE);
            p.setColor(Theme.secondary);
            c.drawLine(cx - 9 * d, mid - 8 * d, cx + 9 * d, mid + 9 * d, p);
        }
        x += 30 * d;
        // Battery: outline, level fill (accent while charging) and the percentage
        float bw = 24 * d, bh = 12 * d, top = mid - bh / 2f;
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1.5f * d);
        p.setColor(Theme.text);
        r.set(x, top, x + bw, top + bh);
        c.drawRoundRect(r, 2.5f * d, 2.5f * d, p);
        p.setStyle(Paint.Style.FILL);
        c.drawRect(x + bw + 1 * d, mid - 2.5f * d, x + bw + 3 * d, mid + 2.5f * d, p);
        if (battery >= 0) {
            p.setColor(charging ? Theme.accent : battery <= 15 ? 0xFFB3402E : Theme.text);
            float inner = Math.max(1.5f * d, (bw - 4 * d) * battery / 100f);
            r.set(x + 2 * d, top + 2 * d, x + 2 * d + inner, top + bh - 2 * d);
            c.drawRoundRect(r, 1.2f * d, 1.2f * d, p);
            p.setColor(Theme.secondary);
            p.setTextSize(12 * d);
            c.drawText(battery + "%", x + bw + 8 * d, mid + 4.5f * d, p);
        }
    }

    /** Re-measure when the SD card appears or disappears. */
    @Override
    public void invalidate() {
        super.invalidate();
        requestLayout();
    }
}
