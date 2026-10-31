package com.folio.launcher;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.IntConsumer;

/** EPUB / TXT / FB2 / CBZ / HTML reader. Tap the edges to turn pages, tap the middle for the floating bars. */
public class ReaderActivity extends Activity implements ReadingChrome.Callbacks {
    private static final String COMIC =
            "html,body{background:#000 !important;margin:0 !important;padding:0 !important;}"
                    + "img{width:100% !important;height:auto !important;display:block;}";
    private static final int[] MARGIN_PX = {16, 26, 40};
    private static final long HIDE_MS = 6000;

    /** WebView that exposes its scroll range, used to turn the scroll offset into a 0..1 fraction. */
    private static final class RWebView extends WebView {
        RWebView(Context c) {
            super(c);
        }

        int range() {
            return computeVerticalScrollRange();
        }

        int extent() {
            return computeVerticalScrollExtent();
        }
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private FrameLayout root;
    private RWebView web;
    private TextView loading, indicator;
    private ReadingChrome chrome;
    private ReadingChrome.Pal pal;
    private Book epub;
    private File dir;
    private String id;
    private int chapter, pendingY;
    private float pendingFrac = -1;
    private boolean pendingIsProgress;
    private boolean chromeOn, sheetOpen, ready;
    private List<String[]> marks = new ArrayList<>();

    // typography; per-book or global
    private int sFamily, sZoom, sLine, sPara, sMargin, sAlign, sTheme, sBright;
    private boolean sScroll, perBook, indicators;

    private final Runnable autoHide = () -> {
        if (sheetOpen) restartHide();
        else setChrome(false);
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        PaperMotion.load(this);
        Theme.load(this);
        prefs = getSharedPreferences("folio", MODE_PRIVATE);
        final File f = new File(getIntent().getStringExtra("path"));
        id = Book.id(f);
        loadSettings();
        pal = ReadingChrome.pal(sTheme);
        PaperMotion.paintWindow(this, pal.bg);

        root = new FrameLayout(this);
        root.setBackgroundColor(pal.bg);

        web = new RWebView(this);
        web.setBackgroundColor(pal.bg);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setAllowFileAccess(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setTextZoom(sZoom);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, String url) {
                return true;
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                injectCss();
                v.postDelayed(() -> {
                    if (pendingFrac >= 0) {
                        if (pendingIsProgress) scrollToFrac(pendingFrac);
                        else scrollToAnchor(pendingFrac);
                        pendingFrac = -1;
                        pendingIsProgress = false;
                    } else if (pendingY > 0) {
                        v.scrollTo(0, pendingY);
                        pendingY = 0;
                    }
                    ready = true;
                    refreshChrome();
                }, 300);
            }
        });
        web.setOnScrollChangeListener((v, x, y, ox, oy) -> {
            if (ready) refreshChrome();
        });
        final GestureDetector gd = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                float x = e.getX() / web.getWidth();
                if (sScroll || (x >= 0.3f && x <= 0.7f)) toggleChrome();
                else if (x < 0.3f) prev();
                else next();
                return true;
            }

            @Override
            public boolean onFling(MotionEvent a, MotionEvent c, float vx, float vy) {
                if (!sScroll && Math.abs(vx) > 800 && Math.abs(vx) > Math.abs(vy) * 1.5f) {
                    if (vx < 0) next();
                    else prev();
                    return true;
                }
                return false;
            }
        });
        web.setOnTouchListener((v, ev) -> {
            gd.onTouchEvent(ev);
            return !sScroll;
        });
        root.addView(web);

        loading = ReadingChrome.text(this, "Abrindo…", 18, pal.sec, false);
        loading.setGravity(Gravity.CENTER);
        root.addView(loading, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        indicator = ReadingChrome.text(this, "", 11, pal.sec, false);
        indicator.setGravity(Gravity.CENTER);
        indicator.setVisibility(View.GONE);
        root.addView(indicator, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        buildChrome(null);
        setContentView(root);
        applyBrightness();

        new Thread(() -> {
            try {
                Book e = Book.open(f);
                File base = new File(getCacheDir(), "books");
                File[] old = base.listFiles();
                if (old != null) for (File o : old) if (!o.getName().equals(id)) Book.deleteTree(o);
                File dest = new File(base, id);
                e.prepare(dest);
                ui.post(() -> start(e, dest));
            } catch (Exception ex) {
                ui.post(() -> loading.setText("Não foi possível abrir este livro."));
            }
        }).start();
    }

    private void buildChrome(String title) {
        boolean wasOn = chromeOn;
        if (chrome != null) {
            root.removeView(chrome.top);
            root.removeView(chrome.bottom);
            if (title == null) title = epub == null ? "" : epub.title;
        }
        pal = ReadingChrome.pal(sTheme);
        chrome = new ReadingChrome(this, pal, this, title == null ? "" : title, true);
        root.addView(chrome.top);
        root.addView(chrome.bottom);
        indicator.setTextColor(pal.sec);
        loading.setTextColor(pal.sec);
        chromeOn = !wasOn; // force setChrome to apply
        setChrome(wasOn);
    }

    private void start(Book e, File dest) {
        epub = e;
        dir = dest;
        int[] saved = Db.get(this).progress(id);
        chapter = Math.max(0, Math.min(saved == null ? 0 : saved[0], e.spine.size() - 1));
        float fr = Db.get(this).fraction(id);
        if (fr >= 0) pendingFrac = fr;
        else pendingY = saved == null ? 0 : saved[1];
        marks = Db.get(this).bookmarks(id);
        loading.setVisibility(View.GONE);
        buildChrome(e.title);
        if (e.kind == Book.Kind.CBZ) web.setBackgroundColor(0xFF000000);
        show(chapter);
    }

    private void show(int i) {
        chapter = i;
        ready = false;
        try {
            epub.ensurePage(dir, i);
        } catch (Exception ignored) {
        }
        web.loadUrl(Uri.fromFile(new File(dir, epub.spine.get(i))).toString());
    }

    private void next() {
        if (epub == null) return;
        setChrome(false);
        if (web.pageDown(false)) {
            PaperMotion.slide(web, 1);
        } else if (chapter + 1 < epub.spine.size()) {
            pendingFrac = -1;
            pendingY = 0;
            show(chapter + 1);
        }
    }

    private void prev() {
        if (epub == null) return;
        setChrome(false);
        if (web.pageUp(false)) {
            PaperMotion.slide(web, -1);
        } else if (chapter > 0) {
            pendingFrac = 1;
            pendingIsProgress = true;
            show(chapter - 1);
        }
    }

    // ---------- position ----------
    private float frac() {
        int r = web.range() - web.extent();
        return r > 0 ? Math.max(0f, Math.min(1f, web.getScrollY() / (float) r)) : 0f;
    }

    /** Top of the viewport as a share of the whole document: stays on the same text when the font size changes. */
    private float anchor() {
        int r = web.range();
        return r > 0 ? Math.max(0f, Math.min(1f, web.getScrollY() / (float) r)) : 0f;
    }

    private void scrollToAnchor(float a) {
        web.scrollTo(0, Math.round(a * web.range()));
    }

    private void scrollToFrac(float f) {
        int r = web.range() - web.extent();
        web.scrollTo(0, r > 0 ? Math.round(f * r) : 0);
    }

    private int permille() {
        if (epub == null) return 0;
        int n = epub.spine.size();
        if (epub.paged()) return n > 1 ? chapter * 1000 / (n - 1) : 0;
        return Math.round((chapter + frac()) / n * 1000f);
    }

    private void seekTo(int pm) {
        int n = epub.spine.size();
        if (epub.paged()) {
            int ch = n > 1 ? Math.round(pm / 1000f * (n - 1)) : 0;
            if (ch != chapter) {
                pendingFrac = -1;
                show(ch);
            }
            return;
        }
        float pos = pm / 1000f * n;
        int ch = Math.min(n - 1, (int) pos);
        float fr = pos - ch;
        if (ch == chapter) {
            scrollToFrac(fr);
        } else {
            pendingFrac = fr;
            pendingIsProgress = true;
            show(ch);
        }
    }

    private String chapterText() {
        int n = epub.spine.size();
        return (epub.paged() ? "Página " : "Capítulo ") + (chapter + 1) + " de " + n;
    }

    private String positionText(int pm) {
        if (epub.paged()) return chapterText();
        return (pm / 10) + "% do livro · " + chapterText();
    }

    private void refreshChrome() {
        if (epub == null) return;
        int pm = permille();
        chrome.setProgress(pm);
        chrome.setInfo(chapterText(), positionText(pm));
        chrome.setBookmarked(hasMarkHere());
        indicator.setText(positionText(pm));
    }

    // ---------- floating bars ----------
    private void toggleChrome() {
        setChrome(!chromeOn);
    }

    private void setChrome(boolean on) {
        if (chromeOn == on) return;
        chromeOn = on;
        if (on) {
            PaperMotion.show(chrome.top, 10);
            PaperMotion.show(chrome.bottom, 10);
        } else {
            PaperMotion.hide(chrome.top, 10);
            PaperMotion.hide(chrome.bottom, 10);
        }
        // Shrink the page instead of letting the bars cover text.
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) web.getLayoutParams();
        lp.topMargin = on ? chrome.topInset() : 0;
        lp.bottomMargin = on ? chrome.bottomInset() : 0;
        web.setLayoutParams(lp);
        indicator.setVisibility(!on && indicators && epub != null ? View.VISIBLE : View.GONE);
        ui.removeCallbacks(autoHide);
        if (on) {
            refreshChrome();
            restartHide();
        }
    }

    private void restartHide() {
        ui.removeCallbacks(autoHide);
        ui.postDelayed(autoHide, HIDE_MS);
    }

    @Override
    public void touched() {
        if (chromeOn) restartHide();
    }

    @Override
    public void back() {
        finish();
    }

    @Override
    public void seek(int pm, boolean done) {
        if (epub == null) return;
        if (done) seekTo(pm);
        else chrome.setInfo(chapterText(), epub.paged() ? chapterText() : (pm / 10) + "% do livro");
    }

    // ---------- bookmarks ----------
    private boolean hasMarkHere() {
        float f = anchor();
        for (String[] m : marks)
            if (Integer.parseInt(m[1]) == chapter && Math.abs(Integer.parseInt(m[2]) / 10000f - f) < 0.03f) return true;
        return false;
    }

    @Override
    public void bookmark() {
        if (epub == null) return;
        touched();
        Db db = Db.get(this);
        float f = anchor();
        boolean removed = false;
        for (String[] m : new ArrayList<>(marks)) {
            if (Integer.parseInt(m[1]) == chapter && Math.abs(Integer.parseInt(m[2]) / 10000f - f) < 0.03f) {
                db.deleteBookmark(m[0]);
                removed = true;
            }
        }
        if (!removed) {
            String label = (epub.paged() ? "Página " : "Cap. ") + (chapter + 1) + (epub.paged() ? "" : " · " + Math.round(frac() * 100) + "%");
            db.addBookmark(id, chapter, Math.round(f * 10000), label);
        }
        marks = db.bookmarks(id);
        refreshChrome();
        Toast.makeText(this, removed ? "Marcador removido" : "Marcador adicionado", Toast.LENGTH_SHORT).show();
    }

    private void showMarks() {
        if (marks.isEmpty()) {
            Toast.makeText(this, "Nenhum marcador neste livro. Toque no ícone de marcador para adicionar.", Toast.LENGTH_LONG).show();
            return;
        }
        final LinearLayout box = sheetBox();
        box.addView(ReadingChrome.text(this, "Marcadores", 17, pal.text, true));
        final Dialog[] dlg = new Dialog[1];
        for (final String[] m : marks) {
            TextView t = ReadingChrome.text(this, m[3], 15, pal.text, false);
            t.setPadding(0, dp(14), 0, dp(14));
            t.setOnClickListener(v -> {
                dlg[0].dismiss();
                int ch = Integer.parseInt(m[1]);
                float fr = Integer.parseInt(m[2]) / 10000f;
                if (ch == chapter) scrollToAnchor(fr);
                else {
                    pendingFrac = fr;
                    pendingIsProgress = false;
                    show(ch);
                }
            });
            t.setOnLongClickListener(v -> {
                Db.get(this).deleteBookmark(m[0]);
                marks = Db.get(this).bookmarks(id);
                dlg[0].dismiss();
                refreshChrome();
                Toast.makeText(this, "Marcador removido", Toast.LENGTH_SHORT).show();
                return true;
            });
            box.addView(t);
        }
        box.addView(ReadingChrome.text(this, "Segure um marcador para remover.", 12, pal.sec, false));
        dlg[0] = openSheet(scrolling(box));
    }

    // ---------- table of contents ----------
    @Override
    public void toc() {
        if (epub == null) return;
        final LinearLayout box = sheetBox();
        box.addView(ReadingChrome.text(this, "Sumário", 17, pal.text, true));
        final Dialog[] dlg = new Dialog[1];
        String[] titles = epub.chapterTitles();
        for (int i = 0; i < epub.spine.size(); i++) {
            final int k = i;
            boolean cur = i == chapter;
            String label = titles[i] != null ? titles[i] : (epub.paged() ? "Página " : "Capítulo ") + (i + 1);
            TextView t = ReadingChrome.text(this, label, 15, cur ? pal.accent : pal.text, cur);
            t.setPadding(0, dp(12), 0, dp(12));
            t.setOnClickListener(v -> {
                dlg[0].dismiss();
                pendingFrac = -1;
                pendingY = 0;
                show(k);
            });
            box.addView(t);
        }
        dlg[0] = openSheet(scrolling(box));
    }

    // ---------- menu ----------
    @Override
    public void menu() {
        touched();
        final LinearLayout box = sheetBox();
        final Dialog[] dlg = new Dialog[1];
        addMenuRow(box, dlg, "Sumário", this::toc);
        addMenuRow(box, dlg, "Marcadores (" + marks.size() + ")", this::showMarks);
        addMenuRow(box, dlg, indicators ? "Ocultar indicador de progresso" : "Mostrar indicador de progresso", () -> {
            indicators = !indicators;
            prefs.edit().putBoolean("indicators", indicators).apply();
            Toast.makeText(this, indicators ? "Indicador visível durante a leitura" : "Leitura sem indicadores", Toast.LENGTH_SHORT).show();
        });
        addMenuRow(box, dlg, "Voltar à biblioteca", this::finish);
        dlg[0] = openSheet(box);
    }

    private void addMenuRow(LinearLayout box, final Dialog[] dlg, String label, final Runnable r) {
        TextView t = ReadingChrome.text(this, label, 16, pal.text, false);
        t.setPadding(0, dp(14), 0, dp(14));
        t.setOnClickListener(v -> {
            dlg[0].dismiss();
            r.run();
        });
        box.addView(t);
    }

    // ---------- typography panel ----------
    @Override
    public void typography() {
        final LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(16), dp(20), dp(24));
        fillTypography(box);
        openSheet(scrolling(box));
    }

    private void fillTypography(final LinearLayout box) {
        box.removeAllViews();
        box.addView(ReadingChrome.text(this, "Aparência do texto", 17, pal.text, true));
        chips(box, "Tema", ReadingChrome.THEMES, sTheme, v -> change(() -> sTheme = v, box, true));
        chips(box, "Fonte", new String[]{"Serifada", "Sem serifa", "Mono"}, sFamily, v -> change(() -> sFamily = v, box, false));
        stepper(box, "Tamanho", sZoom + "%",
                () -> change(() -> sZoom = Math.max(70, sZoom - 10), box, false),
                () -> change(() -> sZoom = Math.min(200, sZoom + 10), box, false));
        stepper(box, "Linhas", String.format(Locale.US, "%.2f", sLine / 100f),
                () -> change(() -> sLine = Math.max(120, sLine - 10), box, false),
                () -> change(() -> sLine = Math.min(220, sLine + 10), box, false));
        stepper(box, "Parágrafos", String.format(Locale.US, "%.1f em", sPara * 0.4f),
                () -> change(() -> sPara = Math.max(0, sPara - 1), box, false),
                () -> change(() -> sPara = Math.min(5, sPara + 1), box, false));
        chips(box, "Margens", new String[]{"Estreita", "Média", "Larga"}, sMargin, v -> change(() -> sMargin = v, box, false));
        chips(box, "Alinhamento", new String[]{"Justificado", "Esquerda"}, sAlign, v -> change(() -> sAlign = v, box, false));
        chips(box, "Navegação", new String[]{"Por páginas", "Rolagem livre"}, sScroll ? 1 : 0, v -> change(() -> sScroll = v == 1, box, false));
        chips(box, "Virar página", new String[]{"Deslizar", "Imediato"}, PaperMotion.slidePages(this) ? 0 : 1,
                v -> PaperMotion.setSlidePages(this, v == 0));

        LinearLayout br = new LinearLayout(this);
        br.setGravity(Gravity.CENTER_VERTICAL);
        br.setPadding(0, dp(12), 0, 0);
        TextView bl = ReadingChrome.text(this, "Brilho", 14, pal.text, false);
        br.addView(bl, new LinearLayout.LayoutParams(dp(96), ViewGroup.LayoutParams.WRAP_CONTENT));
        SeekBar sb = new SeekBar(this);
        sb.setMax(90);
        sb.setProgress(sBright == 0 ? 90 : sBright - 10);
        sb.setProgressTintList(android.content.res.ColorStateList.valueOf(pal.accent));
        sb.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(pal.border));
        sb.setThumbTintList(android.content.res.ColorStateList.valueOf(pal.accent));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int v, boolean user) {
                if (!user) return;
                sBright = v + 10;
                prefs.edit().putInt("bright", sBright).apply();
                applyBrightness();
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
            }
        });
        br.addView(sb, new LinearLayout.LayoutParams(0, dp(40), 1f));
        TextView auto = chip("Auto", sBright == 0, () -> {
            sBright = 0;
            prefs.edit().putInt("bright", 0).apply();
            applyBrightness();
            fillTypography(box);
        });
        br.addView(auto);
        box.addView(br);

        chips(box, "Aplicar a", new String[]{"Todos os livros", "Só este livro"}, perBook ? 1 : 0, v -> {
            setScope(v == 1);
            fillTypography(box);
            applyStyle(false);
        });
        TextView reset = ReadingChrome.text(this, "Restaurar padrão", 14, pal.accent, true);
        reset.setPadding(0, dp(16), 0, 0);
        reset.setOnClickListener(v -> {
            resetSettings();
            applyStyle(true);
            fillTypography(box);
        });
        box.addView(reset);
    }

    private void change(Runnable setter, LinearLayout box, boolean themeChanged) {
        setter.run();
        saveSettings();
        applyStyle(themeChanged);
        fillTypography(box);
    }

    private void chips(LinearLayout box, String label, String[] opts, int cur, final IntConsumer pick) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(12), 0, 0);
        row.addView(ReadingChrome.text(this, label, 14, pal.text, false), new LinearLayout.LayoutParams(dp(96), ViewGroup.LayoutParams.WRAP_CONTENT));
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout in = new LinearLayout(this);
        for (int i = 0; i < opts.length; i++) {
            final int k = i;
            in.addView(chip(opts[i], i == cur, () -> pick.accept(k)));
        }
        hs.addView(in);
        row.addView(hs, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        box.addView(row);
    }

    private TextView chip(String s, boolean on, final Runnable r) {
        TextView t = ReadingChrome.text(this, s, 13, on ? pal.bg : pal.text, on);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(12), 0, dp(12), 0);
        t.setMinHeight(dp(36));
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(on ? pal.accent : pal.surface);
        g.setCornerRadius(dp(12));
        g.setStroke(1, pal.border);
        t.setBackground(g);
        t.setOnClickListener(v -> r.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, dp(8), 0);
        t.setLayoutParams(lp);
        return t;
    }

    private void stepper(LinearLayout box, String label, String value, final Runnable dec, final Runnable inc) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(12), 0, 0);
        row.addView(ReadingChrome.text(this, label, 14, pal.text, false), new LinearLayout.LayoutParams(dp(96), ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(chip("−", false, dec), new LinearLayout.LayoutParams(dp(48), dp(40)));
        TextView v = ReadingChrome.text(this, value, 14, pal.text, true);
        v.setGravity(Gravity.CENTER);
        row.addView(v, new LinearLayout.LayoutParams(dp(80), ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(chip("+", false, inc), new LinearLayout.LayoutParams(dp(48), dp(40)));
        box.addView(row);
    }

    private LinearLayout sheetBox() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(16), dp(20), dp(24));
        return box;
    }

    private View scrolling(View content) {
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(content);
        return sv;
    }

    private Dialog openSheet(View content) {
        sheetOpen = true;
        ui.removeCallbacks(autoHide);
        return ReadingChrome.sheet(this, pal, content, () -> {
            sheetOpen = false;
            if (chromeOn) restartHide();
        });
    }

    // ---------- applying settings ----------
    private String css() {
        String fam = sFamily == 1 ? "sans-serif" : sFamily == 2 ? "monospace" : "serif";
        return "html,body{background:" + hex(pal.bg) + " !important;color:" + hex(pal.text) + " !important;"
                + "font-family:" + fam + " !important;line-height:" + String.format(Locale.US, "%.2f", sLine / 100f) + " !important;}"
                + "body{margin:0 !important;padding:28px " + MARGIN_PX[sMargin] + "px 44px !important;max-width:none !important;}"
                + "p{margin:0 0 " + String.format(Locale.US, "%.2f", sPara * 0.4f) + "em !important;"
                + "text-align:" + (sAlign == 0 ? "justify" : "left") + " !important;}"
                + "a{color:" + hex(pal.accent) + " !important;}"
                + "img{max-width:100% !important;height:auto !important;border-radius:6px;}";
    }

    private static String hex(int c) {
        return String.format("#%06X", c & 0xFFFFFF);
    }

    private void injectCss() {
        String sheet = epub != null && epub.kind == Book.Kind.CBZ ? COMIC : css();
        web.evaluateJavascript("(function(){var s=document.getElementById('folio-s');if(!s){s=document.createElement('style');"
                + "s.id='folio-s';document.head.appendChild(s);}s.innerHTML='" + sheet + "';})()", null);
    }

    /** Re-applies typography keeping the logical reading point (the fraction of the chapter). */
    private void applyStyle(boolean themeChanged) {
        final float f = ready ? anchor() : -1;
        if (themeChanged) {
            buildChrome(null);
            root.setBackgroundColor(pal.bg);
            if (epub == null || epub.kind != Book.Kind.CBZ) web.setBackgroundColor(pal.bg);
        }
        web.getSettings().setTextZoom(sZoom);
        injectCss();
        if (f >= 0) web.postDelayed(() -> {
            scrollToAnchor(f);
            refreshChrome();
        }, 350);
        refreshChrome();
    }

    private void applyBrightness() {
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = sBright == 0 ? WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE : sBright / 100f;
        getWindow().setAttributes(lp);
    }

    // ---------- settings storage ----------
    private String pre() {
        return perBook ? "b" + id + "_" : "g_";
    }

    private void loadSettings() {
        perBook = prefs.getBoolean("b" + id + "_on", false);
        String k = pre();
        sFamily = prefs.getInt(k + "family", 0);
        sZoom = prefs.getInt(k + "zoom", prefs.getInt("zoom", 100));
        sLine = prefs.getInt(k + "line", 155);
        sPara = prefs.getInt(k + "para", 0);
        sMargin = prefs.getInt(k + "margin", 1);
        sAlign = prefs.getInt(k + "align", 0);
        sTheme = prefs.getInt(k + "theme", 0);
        sScroll = prefs.getBoolean(k + "scroll", false);
        sBright = prefs.getInt("bright", 0);
        indicators = prefs.getBoolean("indicators", true);
    }

    private void saveSettings() {
        String k = pre();
        prefs.edit().putInt(k + "family", sFamily).putInt(k + "zoom", sZoom).putInt(k + "line", sLine)
                .putInt(k + "para", sPara).putInt(k + "margin", sMargin).putInt(k + "align", sAlign)
                .putInt(k + "theme", sTheme).putBoolean(k + "scroll", sScroll).apply();
    }

    private void setScope(boolean book) {
        perBook = book;
        prefs.edit().putBoolean("b" + id + "_on", book).apply();
        if (book) saveSettings(); // start from what is on screen
        else loadSettings();
    }

    private void resetSettings() {
        sFamily = 0;
        sZoom = 100;
        sLine = 155;
        sPara = 0;
        sMargin = 1;
        sAlign = 0;
        sTheme = 0;
        sScroll = false;
        saveSettings();
    }

    // ---------- lifecycle ----------
    @Override
    protected void onResume() {
        super.onResume();
        t0 = System.currentTimeMillis();
        Kiosk.apply(this);
    }

    @Override
    public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (focus) Kiosk.apply(this);
    }

    @Override
    protected void onPause() {
        super.onPause();
        endSession();
        ui.removeCallbacks(autoHide);
        if (epub != null && ready) {
            Db.get(this).saveFraction(id, chapter, web.getScrollY(), anchor());
            if (chapter >= epub.spine.size() - 1) Db.get(this).markFinished(id);
        }
    }

    @Override
    protected void onDestroy() {
        if (chrome != null) PaperMotion.cancel(chrome.top, chrome.bottom);
        web.destroy();
        super.onDestroy();
    }

    @Override
    public void finish() {
        super.finish();
        PaperMotion.transition(this);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private long t0;

    /** Logs the time spent reading since onResume (ignores glances under 5 s). */
    private void endSession() {
        if (t0 == 0 || id == null) return;
        int secs = (int) ((System.currentTimeMillis() - t0) / 1000);
        if (secs >= 5) Db.get(this).addSession(id, t0, Math.min(secs, 4 * 3600));
        t0 = 0;
    }
}
