package com.folio.launcher;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.provider.Settings;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * Paper Motion: the one place that decides how things move. Only alpha, translation and a small
 * scale are animated (never sizes or layouts), with no infinite animations. Eco mode and the
 * Android "animator duration scale = 0" setting both turn every transition into an instant change.
 */
final class PaperMotion {
    static final int ECO = 0, BALANCED = 1, PREMIUM = 2;
    static final String[] NAMES = {"Eco", "Equilibrado", "Premium"};
    static final String[] NOTES = {
            "Sem animações: transições imediatas e menos uso de CPU",
            "Transições curtas (150 ms) e feedback discreto",
            "Transições de 220 ms com movimento um pouco mais elaborado"};

    private static final DecelerateInterpolator DECEL = new DecelerateInterpolator(1.6f);
    private static int mode = BALANCED;
    private static boolean systemOff;
    private static float density = 1f;

    private PaperMotion() {
    }

    /** Reads the saved mode and the system animation setting. Cheap; call from onCreate/onResume. */
    static void load(Context c) {
        mode = c.getSharedPreferences("folio", Context.MODE_PRIVATE).getInt("motion", BALANCED);
        density = c.getResources().getDisplayMetrics().density;
        try {
            systemOff = Settings.Global.getFloat(c.getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f;
        } catch (Exception e) {
            systemOff = false;
        }
    }

    static int mode() {
        return mode;
    }

    static void setMode(Context c, int m) {
        mode = m;
        c.getSharedPreferences("folio", Context.MODE_PRIVATE).edit().putInt("motion", m).apply();
    }

    static boolean enabled() {
        return mode != ECO && !systemOff;
    }

    /** Transition length in ms for the current mode. */
    static int duration() {
        return !enabled() ? 0 : mode == PREMIUM ? 220 : 150;
    }

    /** Paints the window so an activity transition never flashes the default white background. */
    static void paintWindow(Activity a, int color) {
        a.getWindow().setBackgroundDrawable(new ColorDrawable(color));
    }

    // ---------- page turn ----------

    static boolean slidePages(Context c) {
        return c.getSharedPreferences("folio", Context.MODE_PRIVATE).getBoolean("pslide", true);
    }

    static void setSlidePages(Context c, boolean on) {
        c.getSharedPreferences("folio", Context.MODE_PRIVATE).edit().putBoolean("pslide", on).apply();
    }

    /** Short horizontal settle after a page turn: dir 1 = forward (content arrives from the right). Max 160 ms. */
    static void slide(View v, int dir) {
        if (!enabled() || !slidePages(v.getContext())) return;
        v.animate().cancel();
        v.setTranslationX(dir * 28 * density);
        v.setAlpha(0.55f);
        v.animate().translationX(0f).alpha(1f).setDuration(mode == PREMIUM ? 160 : 120)
                .setInterpolator(DECEL).start();
    }

    // ---------- bars and panels ----------

    /** Fades a bar in while sliding it from {@code dp} density-independent pixels away. */
    static void show(View v, int dp) {
        v.animate().cancel();
        if (!enabled()) {
            v.setAlpha(1f);
            v.setTranslationY(0f);
            v.setVisibility(View.VISIBLE);
            return;
        }
        float from = dp * density;
        v.setAlpha(0f);
        v.setTranslationY(from);
        v.setVisibility(View.VISIBLE);
        v.animate().alpha(1f).translationY(0f).setDuration(mode == PREMIUM ? 200 : 170)
                .setInterpolator(DECEL).start();
    }

    /** Inverse of {@link #show}; the view is set to GONE when the animation ends. */
    static void hide(final View v, int dp) {
        v.animate().cancel();
        if (!enabled() || v.getVisibility() != View.VISIBLE) {
            v.setVisibility(View.GONE);
            return;
        }
        v.animate().alpha(0f).translationY(dp * density).setDuration(mode == PREMIUM ? 180 : 150)
                .setInterpolator(DECEL).withEndAction(() -> {
                    v.setVisibility(View.GONE);
                    v.setAlpha(1f);
                    v.setTranslationY(0f);
                }).start();
    }

    /** Stops any running animation on the given views; call from onDestroy. */
    static void cancel(View... views) {
        for (View v : views) {
            if (v != null) v.animate().cancel();
        }
    }

    /** Bottom sheet entry: the window content rises a few dp while fading in. */
    static void sheetIn(Dialog d) {
        if (!enabled() || d.getWindow() == null) return;
        final View content = d.getWindow().getDecorView().findViewById(android.R.id.content);
        if (content == null) return;
        content.setAlpha(0f);
        content.setTranslationY(24 * density);
        content.animate().alpha(1f).translationY(0f).setDuration(mode == PREMIUM ? 240 : 180)
                .setInterpolator(DECEL).start();
    }

    // ---------- touch feedback ----------

    /** Press feedback: the view shrinks to about 98% while a finger is down. Clicks are unaffected. */
    static void press(View v) {
        v.setOnTouchListener((view, ev) -> {
            if (!enabled()) return false;
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    view.animate().scaleX(0.98f).scaleY(0.98f).setDuration(90).start();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    view.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
                    break;
                default:
                    break;
            }
            return false;
        });
    }

    // ---------- activity transitions ----------

    /** Fade between activities. Call right after startActivity or inside finish(). */
    static void transition(Activity a) {
        if (!enabled()) {
            a.overridePendingTransition(0, 0);
            return;
        }
        String suffix = mode == PREMIUM ? "_l" : "";
        int in = a.getResources().getIdentifier("pm_in" + suffix, "anim", a.getPackageName());
        int out = a.getResources().getIdentifier("pm_out" + suffix, "anim", a.getPackageName());
        a.overridePendingTransition(in, out);
    }

    static int solid(int color) {
        return Color.argb(255, Color.red(color), Color.green(color), Color.blue(color));
    }
}
