package com.folio.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** Small native drawn components used by the progress screen and home. */
final class Widgets {
    private Widgets() {
    }

    /** Horizontal progress bar. Zero draws only the track; any value above zero shows at least a rounded stub. */
    static final class Bar extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private float value, target = -1f;
        private android.animation.ValueAnimator anim;

        Bar(Context c) {
            super(c);
        }

        /** Moves to the new value; animates only when the value really changed and the view is on screen. */
        void set(float v) {
            v = Math.max(0f, Math.min(1f, v));
            if (v == target) return;
            boolean first = target < 0f;
            target = v;
            if (anim != null) anim.cancel();
            if (first || !PaperMotion.enabled() || !isShown()) {
                value = v;
                invalidate();
                return;
            }
            anim = android.animation.ValueAnimator.ofFloat(value, v);
            anim.setDuration(PaperMotion.duration());
            anim.addUpdateListener(a -> {
                value = (float) a.getAnimatedValue();
                invalidate();
            });
            anim.start();
        }

        @Override
        protected void onDetachedFromWindow() {
            if (anim != null) anim.cancel();
            super.onDetachedFromWindow();
        }

        @Override
        protected void onDraw(Canvas c) {
            float h = getHeight(), w = getWidth(), rad = h / 2f;
            p.setStyle(Paint.Style.FILL);
            p.setColor(Theme.border);
            r.set(0, 0, w, h);
            c.drawRoundRect(r, rad, rad, p);
            if (value > 0f) {
                p.setColor(Theme.accent);
                r.set(0, 0, Math.max(h, w * value), h);
                c.drawRoundRect(r, rad, rad, p);
            }
        }
    }

    /** Seven-day bar chart. Days without reading show a thin baseline mark instead of a blank gap. */
    static final class Chart extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private float[] vals = new float[0];
        private String[] labels = new String[0];
        private int today = -1;

        Chart(Context c) {
            super(c);
        }

        void set(float[] v, String[] l, int todayIndex) {
            vals = v;
            labels = l;
            today = todayIndex;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            int n = vals.length;
            if (n == 0) return;
            float d = getResources().getDisplayMetrics().density;
            float w = getWidth(), h = getHeight(), lab = 22 * d, top = 18 * d, base = h - lab;
            float max = 0;
            for (float v : vals) max = Math.max(max, v);
            float slot = w / n, bw = Math.min(slot * 0.55f, 36 * d);
            p.setTextAlign(Paint.Align.CENTER);
            p.setTextSize(11 * d);
            for (int i = 0; i < n; i++) {
                float cx = slot * i + slot / 2f;
                float bh = max <= 0 ? 0 : (base - top) * vals[i] / max;
                p.setStyle(Paint.Style.FILL);
                p.setColor(vals[i] > 0 ? (i == today ? Theme.accent : Theme.secondary) : Theme.border);
                float minH = 3 * d;
                r.set(cx - bw / 2f, base - Math.max(bh, minH), cx + bw / 2f, base);
                c.drawRoundRect(r, 4 * d, 4 * d, p);
                p.setColor(i == today ? Theme.text : Theme.secondary);
                p.setFakeBoldText(i == today);
                c.drawText(labels[i], cx, h - 5 * d, p);
                p.setFakeBoldText(false);
                if (vals[i] > 0) {
                    p.setColor(Theme.secondary);
                    c.drawText(Math.max(1, Math.round(vals[i])) + "", cx, base - bh - 4 * d, p);
                }
            }
        }
    }
}
