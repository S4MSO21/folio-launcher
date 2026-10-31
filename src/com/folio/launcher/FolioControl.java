package com.folio.launcher;

import android.app.Activity;
import android.app.Dialog;
import android.bluetooth.BluetoothAdapter;
import android.content.Intent;
import android.media.AudioManager;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Folio Control: quick controls using only public APIs. Wi-Fi and Bluetooth switch directly; what
 * Android restricts (rotation, network lists, full settings) opens the matching system screen instead
 * of pretending to work. Every row shows the real current state.
 */
final class FolioControl {
    interface Host {
        int brightness(); // 10..100

        void setBrightness(int percent);

        boolean transferRunning();

        void openTransfer();
    }

    private final Activity a;
    private final Host host;
    private final float d;
    private Dialog dlg;

    FolioControl(Activity activity, Host h) {
        a = activity;
        host = h;
        d = a.getResources().getDisplayMetrics().density;
    }

    void show() {
        final ReadingChrome.Pal p = ReadingChrome.pal(0);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(px(20), px(16), px(20), px(24));
        box.addView(ReadingChrome.text(a, "Central de controle", 18, p.text, true));

        // Wi-Fi
        final WifiManager wm = (WifiManager) a.getApplicationContext().getSystemService(android.content.Context.WIFI_SERVICE);
        final TextView wifiState = ReadingChrome.text(a, wifiText(wm), 13, p.sec, false);
        box.addView(row(p, "Wi-Fi", wifiState, wm != null && wm.isWifiEnabled(), on -> {
            try {
                if (wm == null || !wm.setWifiEnabled(on)) throw new SecurityException();
                wifiState.setText(on ? "Ligando…" : "Desligado");
            } catch (Exception e) {
                toast("O Android não permitiu mudar o Wi-Fi aqui. Abrindo as configurações.");
                open(Settings.ACTION_WIFI_SETTINGS);
            }
        }, "Redes", Settings.ACTION_WIFI_SETTINGS));

        // Bluetooth
        final BluetoothAdapter bt = BluetoothAdapter.getDefaultAdapter();
        if (bt != null) {
            final TextView btState = ReadingChrome.text(a, bt.isEnabled() ? "Ligado" : "Desligado", 13, p.sec, false);
            box.addView(row(p, "Bluetooth", btState, bt.isEnabled(), on -> {
                try {
                    boolean ok = on ? bt.enable() : bt.disable();
                    if (!ok) throw new SecurityException();
                    btState.setText(on ? "Ligando…" : "Desligado");
                } catch (Exception e) {
                    toast("O Android não permitiu mudar o Bluetooth aqui. Abrindo as configurações.");
                    open(Settings.ACTION_BLUETOOTH_SETTINGS);
                }
            }, "Dispositivos", Settings.ACTION_BLUETOOTH_SETTINGS));
        }

        // Brightness and volume
        box.addView(slider(p, "Brilho", 90, host.brightness() - 10, v -> host.setBrightness(v + 10)));
        final AudioManager am = (AudioManager) a.getSystemService(android.content.Context.AUDIO_SERVICE);
        box.addView(slider(p, "Volume", am.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
                am.getStreamVolume(AudioManager.STREAM_MUSIC), v -> am.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)));

        // Battery (read only)
        Intent bi = a.registerReceiver(null, new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        String bat = "Indisponível";
        if (bi != null) {
            int lv = bi.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), sc = bi.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            int st = bi.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            boolean ch = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL;
            if (lv >= 0) bat = Math.round(lv * 100f / sc) + "%" + (ch ? " · carregando" : "");
        }
        box.addView(info(p, "Bateria", bat));

        // Rotation: Android only lets apps with the WRITE_SETTINGS grant change it, so show state and open the screen
        String rot;
        try {
            rot = Settings.System.getInt(a.getContentResolver(), Settings.System.ACCELEROMETER_ROTATION) == 1 ? "Automática" : "Travada";
        } catch (Exception e) {
            rot = "Desconhecida";
        }
        box.addView(action(p, "Rotação da tela", rot + " · alterar nas configurações", () -> open(Settings.ACTION_DISPLAY_SETTINGS)));

        // Folio Transfer
        box.addView(action(p, "Folio Transfer", host.transferRunning() ? "Servidor ativo" : "Desligado até você abrir", host::openTransfer));
        box.addView(action(p, "Configurações do Android", "Todas as opções do sistema", () -> open(Settings.ACTION_SETTINGS)));

        ScrollView sv = new ScrollView(a);
        sv.addView(box);
        dlg = ReadingChrome.sheet(a, p, sv, null);
        PaperMotion.sheetIn(dlg);
    }

    private String wifiText(WifiManager wm) {
        if (wm == null) return "Indisponível";
        if (!wm.isWifiEnabled()) return "Desligado";
        android.net.wifi.WifiInfo wi = wm.getConnectionInfo();
        String ssid = wi == null ? null : wi.getSSID();
        if (ssid == null || ssid.equals("<unknown ssid>") || wi.getNetworkId() == -1) return "Ligado · sem conexão";
        return "Conectado a " + ssid.replace("\"", "");
    }

    // ---------- row builders ----------
    private interface Toggle {
        void set(boolean on);
    }

    private interface Level {
        void set(int v);
    }

    private View row(ReadingChrome.Pal p, String title, TextView state, boolean on, final Toggle t, String linkLabel, final String linkAction) {
        LinearLayout r = new LinearLayout(a);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, px(12), 0, px(12));
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(ReadingChrome.text(a, title, 16, p.text, true));
        col.addView(state);
        r.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        r.addView(chip(p, linkLabel, false, v -> open(linkAction)));
        final boolean[] cur = {on};
        final TextView sw = chip(p, on ? "Ligado" : "Desligado", on, null);
        sw.setOnClickListener(v -> {
            cur[0] = !cur[0];
            sw.setText(cur[0] ? "Ligado" : "Desligado");
            sw.setBackground(Theme.round(cur[0] ? p.accent : p.surface, 12, !cur[0]));
            sw.setTextColor(cur[0] ? p.bg : p.text);
            t.set(cur[0]);
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(40));
        lp.setMargins(px(8), 0, 0, 0);
        r.addView(sw, lp);
        return r;
    }

    private View slider(ReadingChrome.Pal p, String title, int max, int cur, final Level l) {
        LinearLayout r = new LinearLayout(a);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, px(8), 0, px(8));
        TextView t = ReadingChrome.text(a, title, 16, p.text, true);
        r.addView(t, new LinearLayout.LayoutParams(px(88), ViewGroup.LayoutParams.WRAP_CONTENT));
        SeekBar sb = new SeekBar(a);
        sb.setMax(max);
        sb.setProgress(Math.max(0, Math.min(max, cur)));
        sb.setProgressTintList(android.content.res.ColorStateList.valueOf(p.accent));
        sb.setThumbTintList(android.content.res.ColorStateList.valueOf(p.accent));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int v, boolean user) {
                if (user) l.set(v);
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
            }
        });
        r.addView(sb, new LinearLayout.LayoutParams(0, px(40), 1f));
        return r;
    }

    private View info(ReadingChrome.Pal p, String title, String value) {
        LinearLayout r = new LinearLayout(a);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, px(12), 0, px(12));
        r.addView(ReadingChrome.text(a, title, 16, p.text, true), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        r.addView(ReadingChrome.text(a, value, 14, p.sec, false));
        return r;
    }

    private View action(ReadingChrome.Pal p, String title, String sub, final Runnable go) {
        LinearLayout r = new LinearLayout(a);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setPadding(0, px(12), 0, px(12));
        r.addView(ReadingChrome.text(a, title, 16, p.text, true));
        r.addView(ReadingChrome.text(a, sub, 13, p.sec, false));
        r.setOnClickListener(v -> {
            dlg.dismiss();
            go.run();
        });
        return r;
    }

    private TextView chip(ReadingChrome.Pal p, String s, boolean on, View.OnClickListener l) {
        TextView t = ReadingChrome.text(a, s, 13, on ? p.bg : p.text, on);
        t.setGravity(Gravity.CENTER);
        t.setPadding(px(14), 0, px(14), 0);
        t.setMinHeight(px(40));
        t.setBackground(Theme.round(on ? p.accent : p.surface, 12, !on));
        if (l != null) t.setOnClickListener(l);
        return t;
    }

    private void open(String action) {
        try {
            a.startActivity(new Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            toast("Configuração indisponível neste aparelho.");
        }
    }

    private void toast(String s) {
        Toast.makeText(a, s, Toast.LENGTH_SHORT).show();
    }

    private int px(int dp) {
        return (int) (dp * d);
    }
}
