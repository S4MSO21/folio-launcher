package com.folio.launcher;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.security.MessageDigest;
import java.util.Locale;

/** Folio lock screen: clock, date and the book being read. Swipe up to open, or enter a PIN. */
public class LockActivity extends Activity {
    static final int OFF = 0, SWIPE = 1, PIN = 2;
    private static final String PREFS = "folio", K_MODE = "lock_mode", K_PIN = "lock_pin";

    private FrameLayout root;
    private LinearLayout face;
    private LinearLayout pad;
    private TextView dots;
    private final StringBuilder entry = new StringBuilder();
    private float downY;

    static int mode(Context c) {
        SharedPreferences p = c.getSharedPreferences(PREFS, MODE_PRIVATE);
        int m = p.getInt(K_MODE, SWIPE);
        return m == PIN && p.getString(K_PIN, "").isEmpty() ? SWIPE : m;
    }

    static void setMode(Context c, int mode) {
        c.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt(K_MODE, mode).apply();
    }

    static void setPin(Context c, String pin) {
        c.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt(K_MODE, PIN).putString(K_PIN, hash(pin)).apply();
    }

    private static String hash(String pin) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(("folio-lock:" + pin).getBytes("UTF-8"));
            StringBuilder s = new StringBuilder();
            for (byte b : d) s.append(String.format("%02x", b));
            return s.toString();
        } catch (Exception e) {
            return "";
        }
    }

    static void show(Context c) {
        c.startActivity(new Intent(c, LockActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private int px(int dp) {
        return Math.round(dp * Theme.density);
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Theme.load(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        root = new FrameLayout(this);
        root.setBackgroundColor(Theme.bg);

        face = new LinearLayout(this);
        face.setOrientation(LinearLayout.VERTICAL);
        face.setGravity(Gravity.CENTER_HORIZONTAL);
        face.setPadding(px(24), px(120), px(24), px(24));

        TextClock clock = new TextClock(this);
        clock.setFormat24Hour("HH:mm");
        clock.setFormat12Hour("HH:mm");
        clock.setTextSize(72);
        clock.setTypeface(Typeface.SERIF, Typeface.BOLD);
        clock.setTextColor(Theme.text);
        clock.setGravity(Gravity.CENTER);
        face.addView(clock);

        String ds = new java.text.SimpleDateFormat("EEEE, d 'de' MMMM", new Locale("pt", "BR")).format(new java.util.Date());
        face.addView(label(Character.toUpperCase(ds.charAt(0)) + ds.substring(1), 18, Theme.secondary, false));

        String[] last = Db.get(this).lastBook();
        if (last != null) {
            face.addView(new View(this), new LinearLayout.LayoutParams(1, px(56)));
            face.addView(label("Lendo agora", 13, Theme.secondary, false));
            face.addView(label(last[0], 22, Theme.text, true));
            if (last[1] != null && !last[1].isEmpty()) face.addView(label(last[1], 15, Theme.secondary, false));
        }
        root.addView(face, new FrameLayout.LayoutParams(-1, -1));

        TextView hint = label(mode(this) == PIN ? "Deslize para cima e digite o PIN" : "Deslize para cima para abrir", 15, Theme.secondary, false);
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        hp.bottomMargin = px(48);
        root.addView(hint, hp);

        root.setOnTouchListener((v, e) -> {
            if (pad != null) return false;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downY = e.getRawY();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    face.setTranslationY(Math.min(0, e.getRawY() - downY) * 0.6f);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    boolean open = downY - e.getRawY() > v.getHeight() * 0.18f;
                    face.setTranslationY(0);
                    if (open) unlock();
                    return true;
                default:
                    return true;
            }
        });
        setContentView(root);
    }

    private TextView label(String s, int sp, int color, boolean bold) {
        TextView t = ReadingChrome.text(this, s, sp, color, bold);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, px(4), 0, px(4));
        return t;
    }

    private void unlock() {
        if (mode(this) == PIN) showPad();
        else finish();
    }

    private void showPad() {
        face.setVisibility(View.GONE);
        pad = new LinearLayout(this);
        pad.setOrientation(LinearLayout.VERTICAL);
        pad.setGravity(Gravity.CENTER);
        dots = label(" ", 28, Theme.text, true);
        pad.addView(dots);
        String[][] rows = {{"1", "2", "3"}, {"4", "5", "6"}, {"7", "8", "9"}, {"<", "0", "OK"}};
        for (String[] r : rows) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER);
            for (final String k : r) {
                TextView key = label(k, 22, Theme.text, true);
                key.setGravity(Gravity.CENTER);
                GradientDrawable bg = new GradientDrawable();
                bg.setColor(Theme.surface);
                bg.setStroke(Math.max(1, px(1)), Theme.border);
                bg.setCornerRadius(px(14));
                key.setBackground(bg);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(76), px(64));
                lp.setMargins(px(6), px(6), px(6), px(6));
                key.setOnClickListener(v -> press(k));
                row.addView(key, lp);
            }
            pad.addView(row);
        }
        TextView cancel = label("Cancelar", 15, Theme.secondary, false);
        cancel.setPadding(px(24), px(16), px(24), px(16));
        cancel.setOnClickListener(v -> {
            entry.setLength(0);
            root.removeView(pad);
            pad = null;
            face.setVisibility(View.VISIBLE);
        });
        pad.addView(cancel);
        root.addView(pad, new FrameLayout.LayoutParams(-1, -1));
    }

    private void press(String k) {
        if (k.equals("<")) {
            if (entry.length() > 0) entry.setLength(entry.length() - 1);
        } else if (k.equals("OK")) {
            String stored = getSharedPreferences(PREFS, MODE_PRIVATE).getString(K_PIN, "");
            if (hash(entry.toString()).equals(stored)) {
                finish();
                return;
            }
            Toast.makeText(this, "PIN incorreto", Toast.LENGTH_SHORT).show();
            entry.setLength(0);
        } else if (entry.length() < 6) {
            entry.append(k);
        }
        StringBuilder d = new StringBuilder();
        for (int i = 0; i < entry.length(); i++) d.append("* ");
        dots.setText(d.length() == 0 ? " " : d.toString().trim());
    }

    @Override
    public void onBackPressed() {
        // the lock screen can only be left by unlocking
    }
}
