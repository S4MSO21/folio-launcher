package com.folio.launcher;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Full-screen loading page: Folio wordmark, the book name and a thin bar that fills once (no endless
 * animation, so it costs nothing while the book is being read from the SD card).
 */
final class LoadingView extends FrameLayout {
    private final TextView status;
    private final View fill;
    private boolean gone;

    LoadingView(Context c, String title) {
        super(c);
        float d = c.getResources().getDisplayMetrics().density;
        setBackgroundColor(Theme.bg);
        setClickable(true); // swallow taps while loading

        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding((int) (32 * d), 0, (int) (32 * d), 0);

        TextView brand = ReadingChrome.text(c, "Folio", 30, Theme.text, true);
        brand.setGravity(Gravity.CENTER);
        col.addView(brand);

        TextView name = ReadingChrome.text(c, title == null ? "" : title, 16, Theme.secondary, false);
        name.setGravity(Gravity.CENTER);
        name.setMaxLines(3);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setPadding(0, (int) (10 * d), 0, (int) (22 * d));
        col.addView(name);

        FrameLayout track = new FrameLayout(c);
        track.setBackgroundColor(Theme.border);
        fill = new View(c);
        fill.setBackgroundColor(Theme.accent);
        track.addView(fill, new FrameLayout.LayoutParams(-1, -1));
        col.addView(track, new LinearLayout.LayoutParams((int) (180 * d), Math.max(2, (int) (3 * d))));

        status = ReadingChrome.text(c, "Abrindo…", 13, Theme.secondary, false);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, (int) (12 * d), 0, 0);
        col.addView(status);

        addView(col, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));

        fill.setPivotX(0f);
        if (PaperMotion.enabled()) {
            fill.setScaleX(0.04f);
            fill.animate().scaleX(0.9f).setDuration(2600).setInterpolator(new DecelerateInterpolator(2f)).start();
        } else {
            fill.setScaleX(0.5f);
        }
    }

    /** Adds the page over {@code root} and returns it. */
    static LoadingView show(FrameLayout root, String title) {
        LoadingView v = new LoadingView(root.getContext(), title);
        root.addView(v, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return v;
    }

    /** Shows a message instead of the bar (e.g. the file could not be opened). */
    void fail(String message) {
        fill.animate().cancel();
        fill.setVisibility(View.GONE);
        status.setText(message);
    }

    void dismiss() {
        if (gone) return;
        gone = true;
        fill.animate().cancel();
        if (!PaperMotion.enabled()) {
            remove();
            return;
        }
        animate().alpha(0f).setDuration(PaperMotion.duration()).withEndAction(this::remove).start();
    }

    private void remove() {
        if (getParent() instanceof ViewGroup) ((ViewGroup) getParent()).removeView(this);
    }
}
