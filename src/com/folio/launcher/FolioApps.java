package com.folio.launcher;

import android.app.Activity;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Folio Apps: lists the launchable apps of the device, with search, pinned favourites and apps hidden
 * from the Folio interface only (hiding never disables or uninstalls anything). Uses the public
 * PackageManager launcher query; icons load on a background thread into a small LRU cache.
 */
final class FolioApps {
    private static final class App {
        String pkg, cls, label;
        boolean pinned, hidden;
    }

    private static final String PIN = "apps_pinned", HIDE = "apps_hidden";

    private final Activity a;
    private final SharedPreferences sp;
    private final float d;
    private final PackageManager pm;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final LruCache<String, Drawable> icons;
    private final List<App> all = new ArrayList<>(), shown = new ArrayList<>();
    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
    private int filter; // 0 todos, 1 fixados, 2 ocultos
    private String query = "";
    private LinearLayout root, chips;
    private GridView grid;
    private TextView empty;
    private final BaseAdapter adapter = new BaseAdapter() {
        @Override
        public int getCount() {
            return shown.size();
        }

        @Override
        public Object getItem(int i) {
            return shown.get(i);
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public View getView(int i, View v, ViewGroup p) {
            return bind(v, shown.get(i));
        }
    };

    private final BroadcastReceiver changed = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            reload();
        }
    };
    private boolean registered;

    FolioApps(Activity activity, int cacheEntries) {
        a = activity;
        sp = a.getSharedPreferences("folio", Context.MODE_PRIVATE);
        d = a.getResources().getDisplayMetrics().density;
        pm = a.getPackageManager();
        icons = new LruCache<>(Math.max(24, cacheEntries));
    }

    View view() {
        root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(px(24), px(8), px(24), 0);

        final EditText box = new EditText(a);
        box.setHint("Buscar aplicativo");
        box.setSingleLine();
        box.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        box.setTextColor(Theme.text);
        box.setHintTextColor(Theme.secondary);
        box.setPadding(px(16), 0, px(16), 0);
        box.setBackground(Theme.round(Theme.surface, 12, true));
        box.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int st, int c, int af) {
            }

            @Override
            public void onTextChanged(CharSequence s, int st, int b, int c) {
                query = s.toString().trim().toLowerCase(Locale.getDefault());
                apply();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
        root.addView(box, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(48)));

        chips = new LinearLayout(a);
        chips.setPadding(0, px(12), 0, px(8));
        root.addView(chips);

        grid = new GridView(a);
        grid.setNumColumns(GridView.AUTO_FIT);
        grid.setColumnWidth(px(96));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setVerticalSpacing(px(12));
        grid.setVerticalScrollBarEnabled(false);
        grid.setClipToPadding(false);
        grid.setPadding(0, px(4), 0, px(16));
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((p, v, i, id) -> open(shown.get(i)));
        grid.setOnItemLongClickListener((p, v, i, id) -> {
            menu(shown.get(i));
            return true;
        });
        root.addView(grid, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        empty = ReadingChrome.text(a, "", 14, Theme.secondary, false);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(0, px(32), 0, 0);
        root.addView(empty);
        drawChips();
        return root;
    }

    /** Call when the Apps page becomes visible. */
    void onShow() {
        if (!registered) {
            IntentFilter f = new IntentFilter();
            f.addAction(Intent.ACTION_PACKAGE_ADDED);
            f.addAction(Intent.ACTION_PACKAGE_REMOVED);
            f.addAction(Intent.ACTION_PACKAGE_CHANGED);
            f.addDataScheme("package");
            a.registerReceiver(changed, f);
            registered = true;
        }
        for (int i = 0; grid != null && i < grid.getChildCount(); i++) {
            grid.getChildAt(i).setScaleX(1f);
            grid.getChildAt(i).setScaleY(1f);
        }
        reload();
    }

    /** Call when the page is hidden or the activity stops: stops listening for package changes. */
    void onHide() {
        if (registered) {
            try {
                a.unregisterReceiver(changed);
            } catch (Exception ignored) {
            }
            registered = false;
        }
    }

    void destroy() {
        onHide();
        io.shutdownNow();
    }

    /** Apps matching an already-folded query, as {label, pkg, cls}; hidden apps are skipped. */
    List<String[]> find(String foldedQuery) {
        if (all.isEmpty()) reload(); // first use before the Apps tab was opened
        List<String[]> out = new ArrayList<>();
        for (App p : all) {
            if (!p.hidden && FolioSearch.fold(p.label).contains(foldedQuery)) out.add(new String[]{p.label, p.pkg, p.cls});
        }
        return out;
    }

    void launch(String pkg, String cls) {
        App app = new App();
        app.pkg = pkg;
        app.cls = cls;
        app.label = pkg;
        open(app);
    }

    // ---------- data ----------
    private void reload() {
        io.execute(() -> {
            Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> found = pm.queryIntentActivities(main, 0);
            Set<String> pinned = new HashSet<>(sp.getStringSet(PIN, new HashSet<>()));
            Set<String> hidden = new HashSet<>(sp.getStringSet(HIDE, new HashSet<>()));
            final List<App> list = new ArrayList<>();
            for (ResolveInfo ri : found) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(a.getPackageName())) continue;
                App app = new App();
                app.pkg = pkg;
                app.cls = ri.activityInfo.name;
                app.label = String.valueOf(ri.loadLabel(pm));
                app.pinned = pinned.contains(pkg);
                app.hidden = hidden.contains(pkg);
                list.add(app);
            }
            final Collator col = Collator.getInstance();
            Collections.sort(list, (x, y) -> col.compare(x.label, y.label));
            ui.post(() -> {
                all.clear();
                all.addAll(list);
                apply();
            });
        });
    }

    private void apply() {
        shown.clear();
        for (App p : all) {
            if (filter == 2 ? !p.hidden : p.hidden) continue;
            if (filter == 1 && !p.pinned) continue;
            if (!query.isEmpty() && !p.label.toLowerCase(Locale.getDefault()).contains(query)) continue;
            shown.add(p);
        }
        if (filter == 0) { // pinned apps first, keeping alphabetical order inside each group
            List<App> head = new ArrayList<>(), tail = new ArrayList<>();
            for (App p : shown) (p.pinned ? head : tail).add(p);
            shown.clear();
            shown.addAll(head);
            shown.addAll(tail);
        }
        adapter.notifyDataSetChanged();
        empty.setText(shown.isEmpty() ? (filter == 1 ? "Nenhum aplicativo fixado. Segure um aplicativo para fixar."
                : filter == 2 ? "Nenhum aplicativo oculto." : query.isEmpty() ? "Nenhum aplicativo encontrado." : "Nada encontrado para \"" + query + "\".") : "");
    }

    private void persist(String key, String pkg, boolean on) {
        Set<String> s = new HashSet<>(sp.getStringSet(key, new HashSet<>()));
        if (on) s.add(pkg);
        else s.remove(pkg);
        sp.edit().putStringSet(key, s).apply();
    }

    // ---------- UI ----------
    private void drawChips() {
        chips.removeAllViews();
        String[] names = {"Todos", "★ Fixados", "Ocultos"};
        for (int i = 0; i < names.length; i++) {
            final int k = i;
            boolean on = i == filter;
            TextView t = ReadingChrome.text(a, names[i], 14, on ? Theme.bg : Theme.text, on);
            t.setGravity(Gravity.CENTER);
            t.setPadding(px(16), 0, px(16), 0);
            t.setBackground(Theme.round(on ? Theme.accent : Theme.surface, 20, !on));
            t.setOnClickListener(v -> {
                filter = k;
                drawChips();
                apply();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(36));
            lp.setMargins(0, 0, px(8), 0);
            chips.addView(t, lp);
        }
    }

    private View bind(View v, final App app) {
        LinearLayout cell;
        ImageView im;
        TextView tv;
        if (v == null) {
            cell = new LinearLayout(a);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            cell.setPadding(px(4), px(8), px(4), px(8));
            im = new ImageView(a);
            cell.addView(im, new LinearLayout.LayoutParams(px(52), px(52)));
            tv = ReadingChrome.text(a, "", 12, Theme.text, false);
            tv.setGravity(Gravity.CENTER);
            tv.setMaxLines(2);
            tv.setEllipsize(TextUtils.TruncateAt.END);
            cell.addView(tv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            PaperMotion.press(cell);
        } else {
            cell = (LinearLayout) v;
            im = (ImageView) cell.getChildAt(0);
            tv = (TextView) cell.getChildAt(1);
        }
        tv.setText((app.pinned ? "★ " : "") + app.label);
        cell.setContentDescription(app.label);
        final String key = app.pkg + "/" + app.cls;
        im.setTag(key);
        Drawable cached = icons.get(key);
        if (cached != null) {
            im.setImageDrawable(cached);
        } else {
            im.setImageDrawable(null);
            final ImageView target = im;
            io.execute(() -> {
                try {
                    Drawable dr = pm.getActivityIcon(new android.content.ComponentName(app.pkg, app.cls));
                    icons.put(key, dr);
                    ui.post(() -> {
                        if (key.equals(target.getTag())) target.setImageDrawable(dr);
                    });
                } catch (Exception ignored) {
                }
            });
        }
        return cell;
    }

    private void open(App app) {
        try {
            Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    .setClassName(app.pkg, app.cls).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            a.startActivity(i);
        } catch (Exception e) {
            Toast.makeText(a, "Não foi possível abrir " + app.label, Toast.LENGTH_SHORT).show();
        }
    }

    private void menu(final App app) {
        ReadingChrome.Pal p = ReadingChrome.pal(0);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(px(20), px(16), px(20), px(24));
        box.addView(ReadingChrome.text(a, app.label, 18, p.text, true));
        final Dialog[] dlg = new Dialog[1];
        String[] labels = {app.pinned ? "Desafixar" : "Fixar no topo", app.hidden ? "Mostrar no Folio" : "Ocultar do Folio", "Informações do aplicativo"};
        for (int i = 0; i < labels.length; i++) {
            final int k = i;
            TextView t = ReadingChrome.text(a, labels[i], 16, p.text, false);
            t.setPadding(0, px(14), 0, px(14));
            t.setOnClickListener(v -> {
                dlg[0].dismiss();
                if (k == 0) {
                    app.pinned = !app.pinned;
                    persist(PIN, app.pkg, app.pinned);
                } else if (k == 1) {
                    app.hidden = !app.hidden;
                    persist(HIDE, app.pkg, app.hidden);
                    Toast.makeText(a, app.hidden ? "Oculto só no Folio. O app continua instalado." : "Visível novamente", Toast.LENGTH_SHORT).show();
                } else {
                    try {
                        a.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", app.pkg, null))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    } catch (Exception e) {
                        Toast.makeText(a, "Configurações indisponíveis", Toast.LENGTH_SHORT).show();
                    }
                }
                apply();
            });
            box.addView(t);
        }
        dlg[0] = ReadingChrome.sheet(a, p, box, null);
        PaperMotion.sheetIn(dlg[0]);
    }

    private int px(int dp) {
        return (int) (dp * d);
    }
}
