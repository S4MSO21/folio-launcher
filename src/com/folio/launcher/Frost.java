package com.folio.launcher;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.View;

/**
 * Cheap "frosted glass": the screen behind a sheet is drawn once at 1/8 size, box-blurred (a few hundred
 * thousand additions, ~10 ms) and stretched back under a translucent tint, a soft top sheen and a light edge.
 * No RenderEffect and no per-frame work, so it is safe on a 700 MB tablet.
 */
final class Frost {
    private Frost() {
    }

    /** Draws {@code v} into a bitmap {@code div} times smaller than the view. */
    static Bitmap snapshot(View v, int div) {
        int w = Math.max(1, v.getWidth() / div), h = Math.max(1, v.getHeight() / div);
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawColor(Theme.bg);
        c.scale(1f / div, 1f / div);
        v.draw(c);
        return b;
    }

    /** In-place box blur, {@code passes} times, radius in pixels of the (already small) bitmap. */
    static Bitmap blur(Bitmap b, int radius, int passes) {
        int w = b.getWidth(), h = b.getHeight();
        int[] px = new int[w * h], tmp = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        for (int i = 0; i < passes; i++) {
            pass(px, tmp, w, h, radius, true);
            pass(tmp, px, w, h, radius, false);
        }
        b.setPixels(px, 0, w, 0, 0, w, h);
        return b;
    }

    private static void pass(int[] src, int[] dst, int w, int h, int r, boolean horizontal) {
        int lines = horizontal ? h : w, len = horizontal ? w : h;
        int stepIn = horizontal ? 1 : w, stepLine = horizontal ? w : 1;
        for (int l = 0; l < lines; l++) {
            int base = l * stepLine;
            int ra = 0, ga = 0, ba = 0, n = 0;
            for (int i = -r; i <= r; i++) {
                int p = src[base + Math.max(0, Math.min(len - 1, i)) * stepIn];
                ra += (p >> 16) & 255;
                ga += (p >> 8) & 255;
                ba += p & 255;
                n++;
            }
            for (int i = 0; i < len; i++) {
                dst[base + i * stepIn] = 0xFF000000 | ((ra / n) << 16) | ((ga / n) << 8) | (ba / n);
                int add = src[base + Math.min(len - 1, i + r + 1) * stepIn];
                int sub = src[base + Math.max(0, i - r) * stepIn];
                ra += ((add >> 16) & 255) - ((sub >> 16) & 255);
                ga += ((add >> 8) & 255) - ((sub >> 8) & 255);
                ba += (add & 255) - (sub & 255);
            }
        }
    }

    /**
     * Glass panel for a bottom sheet: the blurred screen (bottom part, matching the panel height), a tint of
     * {@code tint} at 70%, a top sheen and a light 1 dp edge. {@code radii} are the 8 corner values in px.
     */
    static Drawable glass(final Bitmap blurred, final int screenW, final float[] radii, final int tint, final float strokePx) {
        return new Drawable() {
            private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Paint img = new Paint(Paint.FILTER_BITMAP_FLAG); // separate: a fill colour's alpha must not fade the bitmap
            private final Path clip = new Path();

            @Override
            public void draw(Canvas c) {
                Rect b = getBounds();
                if (b.isEmpty()) return;
                clip.reset();
                clip.addRoundRect(new RectF(b), radii, Path.Direction.CW);
                c.save();
                c.clipPath(clip);
                float scale = blurred.getWidth() / (float) screenW;
                int srcH = Math.min(blurred.getHeight(), Math.max(1, Math.round(b.height() * scale)));
                c.drawBitmap(blurred, new Rect(0, blurred.getHeight() - srcH, blurred.getWidth(), blurred.getHeight()), b, img);
                p.setStyle(Paint.Style.FILL);
                p.setShader(null);
                p.setColor((tint & 0x00FFFFFF) | 0xB3000000); // 70% tint
                c.drawRect(b, p);
                p.setColor(0xFFFFFFFF);
                p.setShader(new LinearGradient(0, b.top, 0, b.top + Math.min(b.height(), strokePx * 90),
                        0x38FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
                c.drawRect(b, p);
                p.setShader(null);
                c.restore();
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(strokePx);
                p.setColor(Theme.dark() ? 0x55FFFFFF : 0xB3FFFFFF);
                c.drawPath(clip, p);
            }

            @Override
            public void setAlpha(int a) {
            }

            @Override
            public void setColorFilter(ColorFilter f) {
            }

            @Override
            public int getOpacity() {
                return PixelFormat.TRANSLUCENT;
            }
        };
    }
}
