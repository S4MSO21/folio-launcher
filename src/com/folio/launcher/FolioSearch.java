package com.folio.launcher;

import android.app.Activity;
import android.app.Dialog;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

/**
 * Folio Search: one local, offline search over books, authors, collections, apps and Folio settings.
 * Everything is matched in memory (accents and case ignored); nothing leaves the device.
 */
final class FolioSearch {
    interface Host {
        List<Book> books();

        List<String[]> collections(); // rows of {id, name}

        List<String[]> apps(String query); // rows of {label, pkg, cls}

        void openBook(Book b);

        void openCollection(String id);

        void openAuthor(String name);

        void openApp(String pkg, String cls);

        void openSetting(int index);
    }

    static final String[] SETTINGS = {"Tema", "Perfil de desempenho", "Movimento", "Meta diária de leitura", "Folio Transfer", "Sobre o Folio"};
    private static final String[] SETTING_NOTES = {"Paper Light, Ink Dark, Pure Paper", "Eco, Equilibrado, Premium",
            "Animações do Folio", "Minutos de leitura por dia", "Receber livros por Wi-Fi", "Versão e build"};
    private static final int MAX = 6; // results per category

    private final Activity a;
    private final Host host;
    private final float d;
    private LinearLayout results;
    private Dialog dlg;

    FolioSearch(Activity activity, Host h) {
        a = activity;
        host = h;
        d = a.getResources().getDisplayMetrics().density;
    }

    static String fold(String s) {
        return Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT);
    }

    void show() {
        ReadingChrome.Pal p = ReadingChrome.pal(0);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(px(20), px(16), px(20), px(16));
        final EditText in = new EditText(a);
        in.setHint("Buscar livros, autores, coleções, apps e ajustes");
        in.setSingleLine();
        in.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        in.setTextColor(p.text);
        in.setHintTextColor(p.sec);
        in.setPadding(px(16), 0, px(16), 0);
        in.setBackground(Theme.round(p.bg, 12, true));
        box.addView(in, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(48)));
        results = new LinearLayout(a);
        results.setOrientation(LinearLayout.VERTICAL);
        ScrollView sv = new ScrollView(a);
        sv.addView(results);
        box.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(380)));
        in.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int st, int c, int af) {
            }

            @Override
            public void onTextChanged(CharSequence s, int st, int b, int c) {
                render(s.toString());
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
        dlg = ReadingChrome.sheet(a, p, box, null);
        dlg.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        PaperMotion.sheetIn(dlg);
        render("");
        in.requestFocus();
    }

    private void render(String raw) {
        ReadingChrome.Pal p = ReadingChrome.pal(0);
        results.removeAllViews();
        String q = fold(raw.trim());
        if (q.isEmpty()) {
            // suggestions: the first books of the library
            heading("Na sua biblioteca", p);
            int n = 0;
            for (Book b : host.books()) {
                if (n++ >= MAX) break;
                row(b.title, b.author, p, () -> host.openBook(b));
            }
            if (n == 0) note("Sua biblioteca está vazia.", p);
            return;
        }
        int total = 0, shownBooks = 0;
        for (Book b : host.books()) {
            if (!fold(b.title).contains(q)) continue;
            if (shownBooks == 0) heading("Livros", p);
            if (shownBooks++ < MAX) row(b.title, b.author, p, () -> host.openBook(b));
            total++;
        }
        boolean authors = false;
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Book b : host.books()) {
            if (!fold(b.author).contains(q) || !seen.add(b.author)) continue;
            if (!authors) heading("Autores", p);
            authors = true;
            final String name = b.author;
            row(name, "Ver livros deste autor", p, () -> host.openAuthor(name));
            total++;
        }
        boolean cols = false;
        for (String[] c : host.collections()) {
            if (!fold(c[1]).contains(q)) continue;
            if (!cols) heading("Coleções", p);
            cols = true;
            row(c[1], "Coleção", p, () -> host.openCollection(c[0]));
            total++;
        }
        boolean ap = false;
        int n = 0;
        for (String[] app : host.apps(q)) {
            if (n++ >= MAX) break;
            if (!ap) heading("Aplicativos", p);
            ap = true;
            row(app[0], "Aplicativo", p, () -> host.openApp(app[1], app[2]));
            total++;
        }
        boolean st = false;
        for (int i = 0; i < SETTINGS.length; i++) {
            if (!fold(SETTINGS[i] + " " + SETTING_NOTES[i]).contains(q)) continue;
            if (!st) heading("Ajustes do Folio", p);
            st = true;
            final int k = i;
            row(SETTINGS[i], SETTING_NOTES[i], p, () -> host.openSetting(k));
            total++;
        }
        if (total == 0) note("Nada encontrado para \"" + raw.trim() + "\".", p);
    }

    private void heading(String s, ReadingChrome.Pal p) {
        TextView t = ReadingChrome.text(a, s.toUpperCase(Locale.getDefault()), 11, p.sec, true);
        t.setPadding(0, px(16), 0, px(4));
        results.addView(t);
    }

    private void note(String s, ReadingChrome.Pal p) {
        TextView t = ReadingChrome.text(a, s, 14, p.sec, false);
        t.setPadding(0, px(20), 0, 0);
        results.addView(t);
    }

    private void row(String title, String sub, ReadingChrome.Pal p, final Runnable go) {
        LinearLayout r = new LinearLayout(a);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setPadding(0, px(10), 0, px(10));
        TextView t = ReadingChrome.text(a, title, 16, p.text, true);
        t.setSingleLine();
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        r.addView(t);
        if (sub != null && !sub.isEmpty()) {
            TextView s = ReadingChrome.text(a, sub, 12, p.sec, false);
            s.setSingleLine();
            s.setEllipsize(android.text.TextUtils.TruncateAt.END);
            r.addView(s);
        }
        r.setOnClickListener(v -> {
            dlg.dismiss();
            go.run();
        });
        results.addView(r);
    }

    private int px(int dp) {
        return (int) (dp * d);
    }
}
