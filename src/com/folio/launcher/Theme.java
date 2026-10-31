package com.folio.launcher;

import android.content.Context;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewOutlineProvider;

/** Paper Light, Ink Dark and Pure Paper (monochrome, no transparency). The choice is persisted. */
final class Theme {
    static final String[] NAMES = {"Paper Light", "Ink Dark", "Pure Paper"};
    // bg, surface, elevated, text, secondary, border, accent
    private static final int[][] P = {
            {0xFFF3EEE3, 0xFFFAF7F0, 0xFFFFFFFF, 0xFF252420, 0xFF77736C, 0xFFDDD7CC, 0xFF665C4F},
            {0xFF161717, 0xFF222323, 0xFF2D2E2E, 0xFFF1EEE8, 0xFFA5A19A, 0xFF393A3A, 0xFFC1B4A0},
            {0xFFFFFFFF, 0xFFF0F0F0, 0xFFFFFFFF, 0xFF000000, 0xFF555555, 0xFF808080, 0xFF000000},
    };

    static int index, bg, surface, elevated, text, secondary, border, accent;
    static float density = 1;

    private Theme() {
    }

    static void load(Context c) {
        density = c.getResources().getDisplayMetrics().density;
        apply(c.getSharedPreferences("folio", Context.MODE_PRIVATE).getInt("theme", 0));
    }

    /** Switches to the next theme and stores it. */
    static void next(Context c) {
        int n = (index + 1) % P.length;
        c.getSharedPreferences("folio", Context.MODE_PRIVATE).edit().putInt("theme", n).apply();
        apply(n);
    }

    static void set(Context c, int i) {
        c.getSharedPreferences("folio", Context.MODE_PRIVATE).edit().putInt("theme", i).apply();
        apply(i);
    }

    private static void apply(int i) {
        index = i >= 0 && i < P.length ? i : 0;
        int[] p = P[index];
        bg = p[0];
        surface = p[1];
        elevated = p[2];
        text = p[3];
        secondary = p[4];
        border = p[5];
        accent = p[6];
    }

    static boolean dark() {
        return index == 1;
    }

    static int dialog() {
        return dark() ? android.R.style.Theme_Material_Dialog_Alert : android.R.style.Theme_Material_Light_Dialog_Alert;
    }

    /** Rounded rectangle with a thin border. */
    static GradientDrawable round(int fill, int radiusDp, boolean stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(radiusDp * density);
        if (stroke) g.setStroke(Math.max(1, (int) density), border);
        return g;
    }

    /** Clips a view's content (e.g. a cover image) to rounded corners. */
    static void clip(View v, final int radiusDp) {
        v.setClipToOutline(true);
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline o) {
                o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radiusDp * density);
            }
        });
    }

    /** Reader stylesheet in the current theme. */
    static String css() {
        return "html,body{background:" + hex(bg) + " !important;color:" + hex(text) + " !important;"
                + "font-family:serif !important;line-height:1.55 !important;}"
                + "a{color:" + hex(accent) + " !important;}"
                + "body{margin:0 !important;padding:28px 26px !important;max-width:none !important;}"
                + "img{max-width:100% !important;height:auto !important;border-radius:6px;}"
                + "p{text-align:justify;}";
    }

    private static String hex(int c) {
        return String.format("#%06X", c & 0xFFFFFF);
    }
}
