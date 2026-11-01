package com.folio.launcher;

import android.app.Activity;
import android.app.Dialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

/** Floating top and bottom reading bars plus bottom sheets, shared by the text and PDF readers. */
final class ReadingChrome {
    /** Reader colours. Independent from the app theme so the reader can be sepia. */
    static final class Pal {
        int bg, surface, text, sec, border, accent;
        boolean solid;
    }

    static final String[] THEMES = {"Padrão", "Claro", "Escuro", "Sépia"};

    static Pal pal(int mode) {
        Pal p = new Pal();
        if (mode == 1) set(p, 0xFFF3EEE3, 0xFFFAF7F0, 0xFF252420, 0xFF77736C, 0xFFDDD7CC, 0xFF665C4F);
        else if (mode == 2) set(p, 0xFF161717, 0xFF222323, 0xFFF1EEE8, 0xFFA5A19A, 0xFF393A3A, 0xFFC1B4A0);
        else if (mode == 3) set(p, 0xFFF4ECD8, 0xFFEADFC4, 0xFF4B3A2A, 0xFF8A7560, 0xFFD8C9A8, 0xFF7A5230);
        else {
            set(p, Theme.bg, Theme.surface, Theme.text, Theme.secondary, Theme.border, Theme.accent);
            p.solid = Theme.index == 2;
        }
        return p;
    }

    private static void set(Pal p, int bg, int surface, int text, int sec, int border, int accent) {
        p.bg = bg;
        p.surface = surface;
        p.text = text;
        p.sec = sec;
        p.border = border;
        p.accent = accent;
    }

    interface Callbacks {
        void back();

        void bookmark();

        void typography();

        void menu();

        void toc();

        /** permille 0..1000; done is true when the finger lifts. */
        void seek(int permille, boolean done);

        /** Called on any interaction so the owner can restart its auto-hide timer. */
        void touched();
    }

    static final int TOP_DP = 64, BOTTOM_DP = 88, GAP_DP = 12;

    final View top, bottom;
    private final TextView title, chapter, position;
    private final ImageView bookmark;
    private final SeekBar seek;
    private final Pal p;
    private final float d;
    private boolean dragging;

    ReadingChrome(Activity a, Pal pal, final Callbacks cb, String bookTitle, boolean showSlider) {
        p = pal;
        d = a.getResources().getDisplayMetrics().density;
        int fill = p.solid ? p.surface : (p.surface & 0x00FFFFFF) | 0xEB000000;

        LinearLayout t = new LinearLayout(a);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(px(8), 0, px(8), 0);
        t.setBackground(round(fill, 16, p.border));
        t.addView(icon(a, "ic_back", "Voltar à biblioteca", v -> cb.back()));
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(px(4), 0, px(4), 0);
        title = text(a, bookTitle, 15, p.text, true);
        title.setSingleLine();
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        chapter = text(a, "", 12, p.sec, false);
        chapter.setSingleLine();
        chapter.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(title);
        col.addView(chapter);
        t.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bookmark = icon(a, "ic_bookmark", "Marcador", v -> cb.bookmark());
        bookmark.setImageDrawable(icon(a, "ic_bookmark", p.sec));
        t.addView(bookmark);
        TextView aa = text(a, "Aa", 17, p.text, true);
        aa.setGravity(Gravity.CENTER);
        aa.setContentDescription("Tipografia");
        aa.setOnClickListener(v -> {
            cb.touched();
            cb.typography();
        });
        t.addView(aa, new LinearLayout.LayoutParams(px(44), px(44)));
        t.addView(icon(a, "ic_more", "Mais opções", v -> cb.menu()));
        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(TOP_DP), Gravity.TOP);
        tp.setMargins(px(GAP_DP), px(GAP_DP), px(GAP_DP), 0);
        t.setLayoutParams(tp);
        top = t;

        LinearLayout b = new LinearLayout(a);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setPadding(px(16), px(8), px(8), px(8));
        b.setBackground(round(fill, 16, p.border));
        position = text(a, "", 12, p.sec, false);
        position.setSingleLine();
        b.addView(position, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout row = new LinearLayout(a);
        row.setGravity(Gravity.CENTER_VERTICAL);
        seek = new SeekBar(a);
        seek.setMax(1000);
        seek.setProgressTintList(ColorStateList.valueOf(p.accent));
        seek.setProgressBackgroundTintList(ColorStateList.valueOf(p.border));
        seek.setThumbTintList(ColorStateList.valueOf(p.accent));
        seek.setContentDescription("Posição de leitura");
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int v, boolean user) {
                if (user) {
                    cb.touched();
                    cb.seek(v, false);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
                dragging = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                dragging = false;
                cb.seek(s.getProgress(), true);
            }
        });
        if (showSlider) row.addView(seek, new LinearLayout.LayoutParams(0, px(40), 1f));
        else row.addView(new View(a), new LinearLayout.LayoutParams(0, px(40), 1f));
        row.addView(icon(a, "ic_toc", "Sumário", v -> cb.toc()));
        b.addView(row);
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(BOTTOM_DP), Gravity.BOTTOM);
        bp.setMargins(px(GAP_DP), 0, px(GAP_DP), px(GAP_DP));
        b.setLayoutParams(bp);
        bottom = b;
    }

    void setBookmarked(boolean on) {
        bookmark.setImageDrawable(icon(bookmark.getContext(), "ic_bookmark", on ? p.accent : p.sec));
        bookmark.setContentDescription(on ? "Remover marcador" : "Adicionar marcador");
    }

    void setInfo(String chapterText, String positionText) {
        chapter.setText(chapterText);
        position.setText(positionText);
    }

    void setProgress(int permille) {
        if (!dragging) seek.setProgress(permille);
    }

    int topInset() {
        return px(GAP_DP * 2 + TOP_DP);
    }

    int bottomInset() {
        return px(GAP_DP * 2 + BOTTOM_DP);
    }

    // ---------- helpers ----------
    private int px(int dp) {
        return (int) (dp * d);
    }

    private static GradientDrawable round(int fill, int radiusDp, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(radiusDp * Theme.density);
        g.setStroke(Math.max(1, (int) Theme.density), stroke);
        return g;
    }

    static Drawable icon(android.content.Context c, String name, int color) {
        Drawable dr = c.getResources().getDrawable(
                c.getResources().getIdentifier(name, "drawable", c.getPackageName()), null).mutate();
        dr.setTint(color);
        return dr;
    }

    private ImageView icon(Activity a, String name, String desc, View.OnClickListener l) {
        ImageView v = new ImageView(a);
        v.setImageDrawable(icon(a, name, p.text));
        v.setPadding(px(10), px(10), px(10), px(10));
        v.setContentDescription(desc);
        v.setLayoutParams(new LinearLayout.LayoutParams(px(44), px(44)));
        v.setOnClickListener(l);
        return v;
    }

    static TextView text(android.content.Context c, String s, int sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.SERIF, bold ? Typeface.BOLD : Typeface.NORMAL);
        return t;
    }

    private TextView text(Activity a, String s, int sp, int color, boolean bold) {
        return text((android.content.Context) a, s, sp, color, bold);
    }

    /** Bottom sheet with rounded top corners. The caller fills and owns the content view. */
    static Dialog sheet(Activity a, Pal p, View content, final Runnable onClose) {
        float dd = a.getResources().getDisplayMetrics().density;
        Dialog dlg = new Dialog(a);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(p.surface);
        bg.setCornerRadii(new float[]{20 * dd, 20 * dd, 20 * dd, 20 * dd, 0, 0, 0, 0});
        bg.setStroke(Math.max(1, (int) dd), p.border);
        content.setBackground(bg);
        // frosted glass over the blurred screen; falls back to the solid paper sheet in Eco, Pure Paper or on any error
        if (PaperMotion.enabled() && Theme.index != 2) {
            try {
                View decor = a.getWindow().getDecorView();
                if (decor.getWidth() > 0 && decor.getHeight() > 0) {
                    android.graphics.Bitmap snap = Frost.blur(Frost.snapshot(decor, 8), 3, 2);
                    float r = 20 * dd;
                    content.setBackground(Frost.glass(snap, decor.getWidth(),
                            new float[]{r, r, r, r, 0, 0, 0, 0}, p.surface, Math.max(1f, dd)));
                }
            } catch (Throwable ignored) {
            }
        }
        dlg.setContentView(content);
        Window w = dlg.getWindow();
        w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        w.setGravity(Gravity.BOTTOM);
        w.setDimAmount(0.25f);
        w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        dlg.setOnDismissListener(x -> {
            if (onClose != null) onClose.run();
        });
        dlg.show();
        PaperMotion.sheetIn(dlg);
        return dlg;
    }
}
