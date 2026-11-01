package com.folio.launcher;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.pdf.PdfRenderer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** PDF reader: one page per screen, fitted to the display. Tap the edges to turn pages. */
public class PdfActivity extends Activity implements ReadingChrome.Callbacks {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private SharedPreferences prefs;
    private ImageView view;
    private FrameLayout root;
    private ReadingChrome chrome;
    private ReadingChrome.Pal pal;
    private String title = "";
    private boolean chromeOn, sheetOpen;
    private List<String[]> marks = new ArrayList<>();
    private final Runnable autoHide = new Runnable() {
        @Override
        public void run() {
            if (sheetOpen) ui.postDelayed(this, 6000);
            else setChrome(false);
        }
    };
    private LoadingView loading;
    private PaperMotion.Leaf leaf;
    private ParcelFileDescriptor fd;
    private PdfRenderer renderer;
    private Bitmap shown;
    private String id;
    private int page, count, token;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        PaperMotion.load(this);
        Theme.load(this);
        prefs = getSharedPreferences("folio", MODE_PRIVATE);

        pal = ReadingChrome.pal(0);
        PaperMotion.paintWindow(this, pal.bg);
        root = new FrameLayout(this);
        root.setBackgroundColor(Theme.bg);

        view = new ImageView(this);
        view.setScaleType(ImageView.ScaleType.FIT_CENTER);
        final GestureDetector gd = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                float x = e.getX() / view.getWidth();
                if (x < 0.3f) go(-1);
                else if (x > 0.7f) go(1);
                else setChrome(!chromeOn);
                return true;
            }

            @Override
            public boolean onFling(MotionEvent a, MotionEvent c, float vx, float vy) {
                if (Math.abs(vx) > 800 && Math.abs(vx) > Math.abs(vy) * 1.5f) {
                    go(vx < 0 ? 1 : -1);
                    return true;
                }
                return false;
            }
        });
        view.setOnTouchListener((v, ev) -> {
            gd.onTouchEvent(ev);
            return true;
        });
        root.addView(view, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        String fileName = new File(getIntent().getStringExtra("path")).getName();
        int dot = fileName.lastIndexOf('.');
        loading = LoadingView.show(root, dot > 0 ? fileName.substring(0, dot) : fileName);

        buildChrome();
        setContentView(root);
        applyBrightness();

        final File f = new File(getIntent().getStringExtra("path"));
        id = Book.id(f);
        io.execute(() -> {
            try {
                fd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
                renderer = new PdfRenderer(fd);
                count = renderer.getPageCount();
                try {
                    title = Book.open(f).title;
                } catch (Exception ignored) {
                }
                marks = Db.get(this).bookmarks(id);
                int[] saved = Db.get(this).progress(id);
                page = Math.max(0, Math.min(saved == null ? 0 : saved[0], count - 1));
                ui.post(() -> {
                    buildChrome();
                    render();
                });
            } catch (Exception e) {
                ui.post(() -> loading.fail("Não foi possível abrir este PDF."));
            }
        });
    }

    private void go(int delta) {
        if (renderer == null) return;
        setChrome(false);
        int p = page + delta;
        if (p < 0 || p >= count) return;
        page = p;
        if (leaf != null) leaf.discard();
        leaf = PaperMotion.lift(root, view, delta);
        render();
    }

    private void render() {
        if (renderer == null) return;
        final int p = page, mine = ++token;
        final int w = view.getWidth() > 0 ? view.getWidth() : getResources().getDisplayMetrics().widthPixels;
        final int h = view.getHeight() > 0 ? view.getHeight() : getResources().getDisplayMetrics().heightPixels;
        refreshChrome();
        io.execute(() -> {
            try {
                PdfRenderer.Page pg = renderer.openPage(p);
                float s = Math.min((float) w / pg.getWidth(), (float) h / pg.getHeight());
                Bitmap bm = Bitmap.createBitmap(Math.max(1, (int) (pg.getWidth() * s)),
                        Math.max(1, (int) (pg.getHeight() * s)), Bitmap.Config.ARGB_8888);
                bm.eraseColor(Color.WHITE);
                pg.render(bm, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                pg.close();
                ui.post(() -> {
                    if (mine != token) {
                        bm.recycle();
                        return;
                    }
                    Bitmap old = shown;
                    shown = bm;
                    view.setImageBitmap(bm);
                    if (old != null) old.recycle();
                    loading.dismiss();
                    if (leaf != null) {
                        final PaperMotion.Leaf l = leaf;
                        leaf = null;
                        view.post(l::release);
                    }
                });
            } catch (Exception ignored) {
            }
        });
    }

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
        if (renderer != null) {
            Db.get(this).saveProgress(id, page, 0);
            if (page >= count - 1) Db.get(this).markFinished(id);
        }
    }

    @Override
    protected void onDestroy() {
        if (chrome != null) PaperMotion.cancel(chrome.top, chrome.bottom);
        io.execute(() -> {
            try {
                if (renderer != null) renderer.close();
                if (fd != null) fd.close();
            } catch (Exception ignored) {
            }
        });
        io.shutdown();
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

    private TextView label(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.SERIF);
        return t;
    }

    // ---------- floating bars ----------
    private void buildChrome() {
        boolean was = chromeOn;
        if (chrome != null) {
            root.removeView(chrome.top);
            root.removeView(chrome.bottom);
        }
        chrome = new ReadingChrome(this, pal, this, title, true);
        root.addView(chrome.top);
        root.addView(chrome.bottom);
        chromeOn = !was;
        setChrome(was);
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
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) view.getLayoutParams();
        lp.topMargin = on ? chrome.topInset() : 0;
        lp.bottomMargin = on ? chrome.bottomInset() : 0;
        view.setLayoutParams(lp);
        ui.removeCallbacks(autoHide);
        if (on) {
            refreshChrome();
            ui.postDelayed(autoHide, 6000);
        }
        if (renderer != null) view.post(this::render); // refit the page to the new height
    }

    private void refreshChrome() {
        if (count == 0) return;
        String where = "Página " + (page + 1) + " de " + count;
        chrome.setInfo(where, where);
        chrome.setProgress(count > 1 ? page * 1000 / (count - 1) : 0);
        chrome.setBookmarked(markHere() != null);
    }

    private String[] markHere() {
        for (String[] m : marks) if (Integer.parseInt(m[1]) == page) return m;
        return null;
    }

    @Override
    public void touched() {
        if (chromeOn) {
            ui.removeCallbacks(autoHide);
            ui.postDelayed(autoHide, 6000);
        }
    }

    @Override
    public void back() {
        finish();
    }

    @Override
    public void seek(int pm, boolean done) {
        if (count == 0) return;
        int p = count > 1 ? Math.round(pm / 1000f * (count - 1)) : 0;
        chrome.setInfo("Página " + (p + 1) + " de " + count, "Ir para a página " + (p + 1));
        if (done && p != page) {
            page = p;
            render();
        }
    }

    @Override
    public void bookmark() {
        if (count == 0) return;
        touched();
        Db db = Db.get(this);
        String[] m = markHere();
        if (m != null) db.deleteBookmark(m[0]);
        else db.addBookmark(id, page, 0, "Página " + (page + 1));
        marks = db.bookmarks(id);
        refreshChrome();
        android.widget.Toast.makeText(this, m != null ? "Marcador removido" : "Marcador adicionado",
                android.widget.Toast.LENGTH_SHORT).show();
    }

    private LinearLayout sheetBox(String heading) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(16), dp(20), dp(24));
        box.addView(ReadingChrome.text(this, heading, 17, pal.text, true));
        return box;
    }

    private android.app.Dialog openSheet(View content) {
        sheetOpen = true;
        return ReadingChrome.sheet(this, pal, content, () -> sheetOpen = false);
    }

    private void listSheet(String heading, final boolean marksList) {
        final LinearLayout box = sheetBox(heading);
        final android.app.Dialog[] dlg = new android.app.Dialog[1];
        if (marksList && marks.isEmpty()) {
            TextView t = ReadingChrome.text(this, "Nenhum marcador neste livro. Toque no ícone de marcador para adicionar.", 14, pal.sec, false);
            t.setPadding(0, dp(12), 0, 0);
            box.addView(t);
        }
        int n = marksList ? marks.size() : count;
        for (int i = 0; i < n; i++) {
            final String[] mk = marksList ? marks.get(i) : null;
            final int target = marksList ? Integer.parseInt(mk[1]) : i;
            boolean cur = target == page;
            TextView t = ReadingChrome.text(this, marksList ? mk[3] : "Página " + (i + 1), 15, cur ? pal.accent : pal.text, cur);
            t.setPadding(0, dp(12), 0, dp(12));
            t.setOnClickListener(v -> {
                dlg[0].dismiss();
                page = target;
                render();
            });
            if (mk != null) t.setOnLongClickListener(v -> {
                Db.get(this).deleteBookmark(mk[0]);
                marks = Db.get(this).bookmarks(id);
                dlg[0].dismiss();
                refreshChrome();
                return true;
            });
            box.addView(t);
        }
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(box);
        dlg[0] = openSheet(sv);
    }

    @Override
    public void toc() {
        listSheet("Páginas", false);
    }

    @Override
    public void menu() {
        touched();
        final LinearLayout box = sheetBox("Opções");
        final android.app.Dialog[] dlg = new android.app.Dialog[1];
        String[] labels = {"Páginas", "Marcadores (" + marks.size() + ")", "Voltar à biblioteca"};
        for (int i = 0; i < labels.length; i++) {
            final int k = i;
            TextView t = ReadingChrome.text(this, labels[i], 16, pal.text, false);
            t.setPadding(0, dp(14), 0, dp(14));
            t.setOnClickListener(v -> {
                dlg[0].dismiss();
                if (k == 0) toc();
                else if (k == 1) listSheet("Marcadores", true);
                else finish();
            });
            box.addView(t);
        }
        dlg[0] = openSheet(box);
    }

    /** A PDF keeps its own layout, so the Aa button only offers window brightness. */
    @Override
    public void typography() {
        LinearLayout box = sheetBox("Brilho");
        TextView note = ReadingChrome.text(this, "PDF mantém o layout original. Fonte e margens não se aplicam.", 13, pal.sec, false);
        note.setPadding(0, dp(8), 0, dp(8));
        box.addView(note);
        android.widget.SeekBar sb = new android.widget.SeekBar(this);
        sb.setMax(90);
        int cur = prefs.getInt("bright", 0);
        sb.setProgress(cur == 0 ? 90 : cur - 10);
        sb.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar s, int v, boolean user) {
                if (!user) return;
                prefs.edit().putInt("bright", v + 10).apply();
                applyBrightness();
            }

            @Override
            public void onStartTrackingTouch(android.widget.SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(android.widget.SeekBar s) {
            }
        });
        box.addView(sb);
        openSheet(box);
    }

    private void applyBrightness() {
        int b = prefs.getInt("bright", 0);
        android.view.WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = b == 0 ? android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE : b / 100f;
        getWindow().setAttributes(lp);
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
