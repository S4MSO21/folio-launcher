package com.folio.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.ViewGroup;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Home screen: the library. Doubles as the device launcher. */
public class MainActivity extends Activity {
    private static final String[] FOLDERS = {"Folio", "Books", "Download"};

    private final List<Book> books = new ArrayList<>();
    private final Map<String, Book> parsed = new ConcurrentHashMap<>();
    private final Set<String> noCover = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private LruCache<String, Bitmap> covers;
    private int profile;
    private static final String[] PROFILES = {"Eco", "Equilibrado", "Premium"};
    private static final int[] CACHE_MB = {2, 6, 12};
    private static final int[] COVER_PX = {180, 300, 420};

    private static final int PICK = 41;

    private volatile Map<String, Integer> chapters = new java.util.HashMap<>();
    private BaseAdapter adapter;
    private GridView grid;
    private TextView empty, count, importBtn;
    private final List<Book> all = new ArrayList<>();
    private final Map<String, Book> byId = new java.util.HashMap<>();
    private java.util.Set<String> favs = new java.util.HashSet<>();
    private java.util.Set<String> members = new java.util.HashSet<>();
    private String filter = "all", query = "", lastId;
    private LinearLayout chips;
    private boolean importing;
    private float d;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        PaperMotion.load(this);
        Theme.load(this);
        PaperMotion.paintWindow(this, Theme.bg);
        profile = getSharedPreferences("folio", MODE_PRIVATE).getInt("profile", 1);
        covers = new LruCache<String, Bitmap>(CACHE_MB[profile] * 1024 * 1024) {
            @Override
            protected int sizeOf(String k, Bitmap b) {
                return b.getByteCount();
            }
        };
        d = getResources().getDisplayMetrics().density;

        buildUi();
        handleSend(getIntent());
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        handleSend(i);
    }

    /** Books shared to Folio from other apps (ACTION_SEND / ACTION_SEND_MULTIPLE). */
    private void handleSend(Intent i) {
        if (i == null) return;
        String a = i.getAction();
        if (!Intent.ACTION_SEND.equals(a) && !Intent.ACTION_SEND_MULTIPLE.equals(a)) return;
        List<Uri> uris = new ArrayList<>();
        if (Intent.ACTION_SEND.equals(a)) {
            Uri u = i.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u != null) uris.add(u);
        } else {
            ArrayList<Uri> l = i.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (l != null) uris.addAll(l);
        }
        i.setAction(Intent.ACTION_MAIN); // don't re-import on rotation/relaunch
        ui.post(() -> runImport(uris));
    }

    private TransferServer server;

    private void startTransfer() {
        if (server != null) {
            showTransferSheet();
            return;
        }
        String ip = TransferServer.localIp();
        if (ip == null) {
            new AlertDialog.Builder(this, Theme.dialog()).setMessage("Conecte o tablet a uma rede Wi-Fi para receber livros.")
                    .setPositiveButton("OK", null).show();
            return;
        }
        try {
            server = new TransferServer(new File(getCacheDir(), "incoming"), new TransferServer.Host() {
                @Override
                public boolean confirm(String name, long size, String from) {
                    final java.util.concurrent.CountDownLatch l = new java.util.concurrent.CountDownLatch(1);
                    final boolean[] ok = {false};
                    ui.post(() -> new AlertDialog.Builder(MainActivity.this, Theme.dialog())
                            .setTitle("Receber livro?")
                            .setMessage(name + "\n" + (size / 1024) + " KB\nDe " + from)
                            .setCancelable(false)
                            .setPositiveButton("Aceitar", (d1, w) -> {
                                ok[0] = true;
                                l.countDown();
                            })
                            .setNegativeButton("Recusar", (d1, w) -> l.countDown()).show());
                    try {
                        l.await(60, java.util.concurrent.TimeUnit.SECONDS);
                    } catch (InterruptedException ignored) {
                    }
                    return ok[0];
                }

                @Override
                public void progress(String name, long done, long total) {
                    ui.post(() -> {
                        if (transferBar == null) return;
                        transferBar.set(total > 0 ? done / (float) total : 0f);
                        transferStatus.setText("Recebendo " + name + " · " + (done / 1024) + " de " + (total / 1024) + " KB");
                    });
                }

                @Override
                public void received(File tmp) {
                    final String shown = tmp.getName();
                    io.execute(() -> {
                        String err = Importer.importOne(MainActivity.this, Uri.fromFile(tmp));
                        tmp.delete();
                        ui.post(() -> {
                            receivedNames.add(err == null ? shown : shown + " — " + err);
                            refreshTransferList();
                            if (transferBar != null) {
                                transferBar.set(0f);
                                transferStatus.setText(err == null ? "Recebido: " + shown : "Não importado: " + err);
                            }
                            scan();
                            android.widget.Toast.makeText(MainActivity.this,
                                    err == null ? "Livro recebido" : err, android.widget.Toast.LENGTH_LONG).show();
                        });
                    });
                }

                @Override
                public void stopped() {
                    ui.post(() -> {
                        server = null;
                        if (transferSheet != null) transferSheet.dismiss();
                        android.widget.Toast.makeText(MainActivity.this, "Folio Transfer encerrado", android.widget.Toast.LENGTH_SHORT).show();
                    });
                }
            });
        } catch (Exception e) {
            new AlertDialog.Builder(this, Theme.dialog()).setMessage("Não foi possível iniciar o servidor: " + e.getMessage())
                    .setPositiveButton("OK", null).show();
            return;
        }
        receivedNames.clear();
        transferUrl = "http://" + ip + ":" + server.port;
        showTransferSheet();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (server != null) server.stop();
        if (apps != null) apps.destroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Kiosk.apply(this);
        ensureStorage();
        applyBrightness();
        if (autoNight()) return;
        long now = android.os.SystemClock.elapsedRealtime();
        if (profile > 0 || now - lastScan > 30000 || all.isEmpty()) scan();
        lastScan = now;
    }

    private long lastScan;

    /**
     * Automatic night theme: Ink Dark from 19:00 to 05:59, the chosen day theme otherwise. Acts only when the
     * period changes, so a manual choice sticks until the next sunset/sunrise. Returns true when it recreated.
     */
    private boolean autoNight() {
        android.content.SharedPreferences sp = getSharedPreferences("folio", MODE_PRIVATE);
        if (!sp.getBoolean("auto_night", false)) return false;
        int h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        boolean night = h >= 19 || h < 6;
        if (sp.getInt("auto_period", -1) == (night ? 1 : 0)) return false;
        sp.edit().putInt("auto_period", night ? 1 : 0).apply();
        int want = night ? 1 : sp.getInt("day_theme", 0);
        if (want == Theme.index) return false;
        Theme.set(this, want);
        recreate();
        return true;
    }

    private boolean askedStorage;

    /** Since targetSdk 23 the storage permission is a runtime grant; ask once per launch and rescan when answered. */
    private void ensureStorage() {
        if (android.os.Build.VERSION.SDK_INT < 23 || askedStorage) return;
        if (checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED) return;
        askedStorage = true;
        requestPermissions(new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE}, 41);
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        if (req == 41) scan();
    }


    private void globalSearch() {
        new FolioSearch(this, new FolioSearch.Host() {
            @Override
            public List<Book> books() {
                return all;
            }

            @Override
            public List<String[]> collections() {
                return Db.get(MainActivity.this).collections();
            }

            @Override
            public List<String[]> apps(String q) {
                return apps.find(q);
            }

            @Override
            public void openBook(Book b) {
                MainActivity.this.openBook(b);
            }

            @Override
            public void openCollection(String id) {
                filter = "c:" + id;
                setTab(1);
                scan();
            }

            @Override
            public void openAuthor(String name) {
                filter = "all";
                setTab(1);
                search.setVisibility(View.VISIBLE);
                search.setText(name);
            }

            @Override
            public void openApp(String pkg, String cls) {
                apps.launch(pkg, cls);
            }

            @Override
            public void openSetting(int i) {
                switch (i) {
                    case 0: chooseTheme(); break;
                    case 1: chooseProfile(); break;
                    case 2: chooseMotion(); break;
                    case 3: chooseGoal(); break;
                    case 4: startTransfer(); break;
                    default: setTab(3);
                }
            }
        }).show();
    }

    /** "Folio 0.2 · build 7", read from the installed package so it always matches the APK. */
    private String versionLine() {
        try {
            android.content.pm.PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            return "Folio " + pi.versionName + " · build " + pi.versionCode;
        } catch (Exception e) {
            return "Folio";
        }
    }

    /** Round "−"/"+" button that changes the screen brightness in 10% steps (shared with the reader's "bright" setting). */
    private TextView brightnessButton(String label, String desc, final int delta) {
        TextView t = text(label, 20, Theme.text, true);
        t.setGravity(Gravity.CENTER);
        t.setContentDescription(desc);
        t.setBackground(pressable(Theme.surface, 12, true));
        t.setOnClickListener(v -> stepBrightness(delta));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(40), px(40));
        lp.setMargins(px(4), 0, delta > 0 ? px(8) : 0, 0);
        t.setLayoutParams(lp);
        return t;
    }

    private int currentBrightness() {
        int cur = getSharedPreferences("folio", MODE_PRIVATE).getInt("bright", 0);
        if (cur == 0) {
            try {
                cur = Math.round(android.provider.Settings.System.getInt(getContentResolver(),
                        android.provider.Settings.System.SCREEN_BRIGHTNESS) / 2.55f / 10f) * 10;
            } catch (Exception e) {
                cur = 50;
            }
        }
        return Math.max(10, Math.min(100, cur));
    }

    private void openControl() {
        new FolioControl(this, new FolioControl.Host() {
            @Override
            public int brightness() {
                return currentBrightness();
            }

            @Override
            public void setBrightness(int percent) {
                getSharedPreferences("folio", MODE_PRIVATE).edit().putInt("bright", percent).apply();
                applyBrightness();
            }

            @Override
            public boolean transferRunning() {
                return server != null;
            }

            @Override
            public void openTransfer() {
                startTransfer();
            }
        }).show();
    }

    private void stepBrightness(int delta) {
        int now = Math.max(10, Math.min(100, currentBrightness() + delta));
        getSharedPreferences("folio", MODE_PRIVATE).edit().putInt("bright", now).apply();
        applyBrightness();
        android.widget.Toast.makeText(this, "Brilho " + now + "%", android.widget.Toast.LENGTH_SHORT).show();
    }

    private void applyBrightness() {
        int b = getSharedPreferences("folio", MODE_PRIVATE).getInt("bright", 0);
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = b == 0 ? WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE : b / 100f;
        getWindow().setAttributes(lp);
    }

    @Override
    public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (focus) Kiosk.apply(this);
    }

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        grid.setNumColumns(columns());
        adapter.notifyDataSetChanged();
    }

    @Override
    public void onBackPressed() {
        // Home screen: nowhere to go back to.
    }

    private int px(int dp) {
        return (int) (dp * d);
    }

    private int columns() {
        if (listMode) return 1;
        float widthDp = getResources().getDisplayMetrics().widthPixels / d;
        return Math.max(2, (int) (widthDp / 150));
    }

    private int cellWidth() {
        int w = getResources().getDisplayMetrics().widthPixels - px(48) - px(12) * (columns() - 1);
        return w / columns();
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.SERIF, bold ? Typeface.BOLD : Typeface.NORMAL);
        return t;
    }

    private void pick() {
        if (importing) return;
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                .putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                        "application/epub+zip", "application/pdf", "text/plain", "text/html",
                        "application/x-fictionbook+xml", "application/x-fb2", "application/vnd.comicbook+zip",
                        "application/x-cbz", "application/zip", "application/octet-stream"});
        try {
            startActivityForResult(i, PICK);
        } catch (Exception e) {
            new AlertDialog.Builder(this, Theme.dialog()).setMessage("Nenhum seletor de arquivos disponível neste aparelho.")
                    .setPositiveButton("OK", null).show();
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == FolioUpdate.REQ_APK) {
            if (res == RESULT_OK && data != null && data.getData() != null) new FolioUpdate(this).installFrom(data.getData());
            return;
        }
        if (req != PICK || res != RESULT_OK || data == null) return;
        final List<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) {
            for (int k = 0; k < data.getClipData().getItemCount(); k++) uris.add(data.getClipData().getItemAt(k).getUri());
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        runImport(uris);
    }

    /** Called with any set of document URIs (picker or, later, share intents). */
    void runImport(final List<Uri> uris) {
        if (uris.isEmpty() || importing) return;
        importing = true;
        importBtn.setEnabled(false);
        final List<String> problems = new ArrayList<>();
        io.execute(() -> {
            int ok = 0;
            for (int k = 0; k < uris.size(); k++) {
                final int n = k + 1;
                ui.post(() -> count.setText("Importando " + n + " de " + uris.size() + "…"));
                String err = Importer.importOne(this, uris.get(k));
                if (err == null) ok++;
                else problems.add(err);
            }
            final int added = ok;
            ui.post(() -> {
                importing = false;
                importBtn.setEnabled(true);
                scan();
                StringBuilder m = new StringBuilder();
                m.append(added == 1 ? "1 livro importado." : added + " livros importados.");
                for (String p : problems) m.append("\n• ").append(p);
                new AlertDialog.Builder(this, Theme.dialog()).setTitle("Importação")
                        .setMessage(m.toString()).setPositiveButton("OK", null).show();
            });
        });
    }

    /** Same size + same name start = same book; very long names get cut at different lengths on SD vs internal. */
    private static String dupKey(File f) {
        String n = f.getName().toLowerCase(java.util.Locale.ROOT);
        return "n:" + f.length() + ":" + n.substring(0, Math.min(40, n.length()));
    }

    private void scan() {
        io.execute(() -> {
            Db db = Db.get(this);
            File ext = Environment.getExternalStorageDirectory();
            final File imp = Importer.dir(this);
            final File legacy = Importer.legacyDir(this);
            List<File> dirs = new ArrayList<>();
            File sd = Importer.sdRoot(this);
            // the SD card comes first so its copy wins when the same book exists in two places
            if (sd != null) for (String dir : FOLDERS) dirs.add(new File(sd, dir));
            dirs.add(imp);
            if (!legacy.equals(imp)) dirs.add(legacy);
            for (String dir : FOLDERS) dirs.add(new File(ext, dir));
            List<Book> found = new ArrayList<>();
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (File dir : dirs) {
                File[] fs = dir.listFiles((x, n) -> Book.supported(n));
                if (fs == null) continue;
                for (File f : fs) {
                    String canon;
                    try {
                        canon = f.getCanonicalPath();
                    } catch (java.io.IOException ex) {
                        canon = f.getAbsolutePath();
                    }
                    // same file reached by two paths, or the same book copied into two folders
                    if (!seen.add(canon) || !seen.add(dupKey(f))) continue;
                    String key = Book.id(f);
                    Book e = parsed.get(key);
                    if (e == null) {
                        try {
                            e = Book.open(f);
                            parsed.put(key, e);
                            db.upsert(e, key, dir.equals(imp) || dir.equals(legacy), null);
                        } catch (Exception ignored) {
                            continue;
                        }
                    }
                    found.add(e);
                }
            }
            Collections.sort(found, (a, c) -> a.title.compareToIgnoreCase(c.title));
            java.util.Set<String> present = new java.util.HashSet<>();
            for (Book x : found) present.add(Book.id(x.file));
            if (!present.isEmpty()) db.prune(present); // never prune on an empty scan (storage may be unavailable)
            chapters = db.chapters();
            final java.util.Set<String> f = db.favorites();
            final java.util.List<String[]> cols = db.collections();
            final java.util.Set<String> mem = filter.startsWith("c:") ? db.members(filter.substring(2)) : new java.util.HashSet<String>();
            final String last = db.lastReading();
            ui.post(() -> {
                all.clear();
                all.addAll(found);
                byId.clear();
                for (Book x : found) byId.put(Book.id(x.file), x);
                favs = f;
                members = mem;
                lastId = last;
                boolean known = filter.equals("all") || filter.equals("fav") || filter.equals("reading");
                for (String[] c : cols) if (filter.equals("c:" + c[0])) known = true;
                if (!known) filter = "all";
                buildChips(cols);
                applyFilter();
            });
        });
    }

    private void applyFilter() {
        books.clear();
        for (Book b : all) {
            String id = Book.id(b.file);
            if (!query.isEmpty() && !(b.title + " " + b.author).toLowerCase().contains(query)) continue;
            if (filter.equals("fav") && !favs.contains(id)) continue;
            if (filter.equals("reading") && !chapters.containsKey(id)) continue;
            if (filter.startsWith("c:") && !members.contains(id)) continue;
            books.add(b);
        }
        adapter.notifyDataSetChanged();
        empty.setText(all.isEmpty()
                ? "Nenhum livro ainda.\nToque em Importar, use o Wi-Fi ou copie arquivos para a pasta Folio do tablet."
                : filter.equals("fav") && query.isEmpty() ? "Nenhum favorito.\nSegure o dedo sobre um livro para favoritar."
                : filter.startsWith("c:") && query.isEmpty() ? "Coleção vazia.\nSegure o dedo sobre um livro para adicioná-lo."
                : "Nenhum livro encontrado.");
        empty.setVisibility(books.isEmpty() ? View.VISIBLE : View.GONE);
        if (!importing) count.setText(books.size() == all.size()
                ? (all.size() == 1 ? "1 livro" : all.size() + " livros")
                : books.size() + " de " + all.size() + " livros");
        if (tab == 0) refreshHome();
    }

    private void buildChips(List<String[]> cols) {
        chips.removeAllViews();
        addChip("Todos", "all", null);
        addChip("★ Favoritos", "fav", null);
        addChip("Lendo", "reading", null);
        for (String[] c : cols) addChip(c[1], "c:" + c[0], c[0]);
        TextView add = text("＋ Coleção", 14, Theme.secondary, false);
        add.setPadding(px(14), px(7), px(14), px(7));
        add.setOnClickListener(v -> askCollection(null));
        chips.addView(add);
    }

    private void addChip(final String label, final String key, final String cid) {
        boolean on = filter.equals(key);
        TextView t = text(label, 14, on ? Theme.bg : Theme.text, on);
        t.setPadding(px(14), px(7), px(14), px(7));
        t.setBackground(Theme.round(on ? Theme.accent : Theme.surface, 18, true));
        t.setOnClickListener(v -> {
            filter = key;
            scan();
        });
        if (cid != null) t.setOnLongClickListener(v -> {
            new AlertDialog.Builder(this, Theme.dialog()).setMessage("Excluir a coleção \"" + label + "\"? Os livros não são apagados.")
                    .setPositiveButton("Excluir", (d1, w) -> io.execute(() -> {
                        Db.get(this).deleteCollection(cid);
                        if (filter.equals(key)) filter = "all";
                        ui.post(this::scan);
                    })).setNegativeButton("Cancelar", null).show();
            return true;
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, px(8), 0);
        chips.addView(t, lp);
    }

    private void openBook(Book bk) {
        io.execute(() -> Db.get(this).opened(Book.id(bk.file)));
        Class<?> screen = bk.kind == Book.Kind.PDF ? PdfActivity.class : ReaderActivity.class;
        startActivity(new Intent(this, screen).putExtra("path", bk.file.getAbsolutePath()));
        PaperMotion.transition(this);
    }

    private void bookMenu(final Book bk) {
        final String id = Book.id(bk.file);
        final boolean fav = favs.contains(id);
        new AlertDialog.Builder(this, Theme.dialog()).setTitle(bk.title)
                .setItems(new String[]{fav ? "Remover dos favoritos" : "★ Favoritar", "Coleções…"}, (d1, which) -> {
                    if (which == 0) {
                        io.execute(() -> {
                            Db.get(this).setFavorite(id, !fav);
                            ui.post(this::scan);
                        });
                    } else {
                        collectionsFor(bk);
                    }
                }).show();
    }

    private void collectionsFor(final Book bk) {
        final String id = Book.id(bk.file);
        io.execute(() -> {
            final Db db = Db.get(this);
            final List<String[]> cols = db.collections();
            if (cols.isEmpty()) {
                ui.post(() -> askCollection(bk));
                return;
            }
            final boolean[] in = new boolean[cols.size()];
            final String[] names = new String[cols.size()];
            for (int k = 0; k < in.length; k++) {
                names[k] = cols.get(k)[1];
                in[k] = db.members(cols.get(k)[0]).contains(id);
            }
            ui.post(() -> new AlertDialog.Builder(this, Theme.dialog()).setTitle("Coleções")
                    .setMultiChoiceItems(names, in, (d1, which, checked) -> {
                        final String cid = cols.get(which)[0];
                        io.execute(() -> {
                            if (db.members(cid).contains(id) != checked) db.toggleMember(cid, id);
                        });
                    })
                    .setNeutralButton("＋ Nova", (d1, w) -> askCollection(bk))
                    .setPositiveButton("Pronto", (d1, w) -> scan()).show());
        });
    }

    /** Asks for a collection name; when a book is given, adds it to the new collection. */
    private void askCollection(final Book bk) {
        final EditText in = new EditText(this);
        in.setSingleLine();
        in.setHint("Nome da coleção");
        new AlertDialog.Builder(this, Theme.dialog()).setTitle("Nova coleção").setView(in)
                .setPositiveButton("Criar", (d1, w) -> {
                    final String name = in.getText().toString().trim();
                    if (name.isEmpty()) return;
                    io.execute(() -> {
                        Db db = Db.get(this);
                        boolean ok = db.createCollection(name);
                        if (ok && bk != null) {
                            for (String[] c : db.collections()) if (c[1].equals(name)) db.toggleMember(c[0], Book.id(bk.file));
                        }
                        ui.post(() -> {
                            if (!ok) android.widget.Toast.makeText(this, "Já existe uma coleção com esse nome", android.widget.Toast.LENGTH_LONG).show();
                            scan();
                        });
                    });
                }).setNegativeButton("Cancelar", null).show();
    }

    private static String dur(long s) {
        if (s < 60) return s == 0 ? "0 min" : "<1 min";
        long m = s / 60;
        return m >= 60 ? (m / 60) + " h " + String.format("%02d", m % 60) + " min" : m + " min";
    }

    /** App-styled bottom sheet with one option per row; the current one is highlighted. */
    private void choiceSheet(String heading, String[] labels, String[] notes, int cur, final java.util.function.IntConsumer pick) {
        ReadingChrome.Pal p = ReadingChrome.pal(0);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(px(20), px(16), px(20), px(24));
        box.addView(ReadingChrome.text(this, heading, 18, p.text, true));
        final android.app.Dialog[] dlg = new android.app.Dialog[1];
        for (int i = 0; i < labels.length; i++) {
            final int k = i;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(px(16), px(12), px(16), px(12));
            row.setBackground(pressable(i == cur ? p.accent : p.surface, 12, true));
            row.addView(ReadingChrome.text(this, labels[i], 15, i == cur ? p.bg : p.text, true));
            if (notes != null && notes[i] != null)
                row.addView(ReadingChrome.text(this, notes[i], 12, i == cur ? p.bg : p.sec, false));
            row.setOnClickListener(v -> {
                dlg[0].dismiss();
                pick.accept(k);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, px(8), 0, 0);
            box.addView(row, lp);
        }
        dlg[0] = ReadingChrome.sheet(this, p, box, null);
    }

    private void chooseProfile() {
        choiceSheet("Perfil de desempenho", PROFILES,
                new String[]{"Menos memória e bateria; sem capas de PDF e quadrinho", "Capas médias e cache de 6 MB", "Capas maiores e cache de 12 MB"},
                profile, which -> {
                    getSharedPreferences("folio", MODE_PRIVATE).edit().putInt("profile", which).apply();
                    recreate();
                });
    }

    // ---------- app-styled sheets (goal, transfer) ----------
    private ReadingChrome.Pal appPal() {
        return ReadingChrome.pal(0);
    }

    private TextView sheetChip(String s, boolean on, View.OnClickListener l) {
        ReadingChrome.Pal p = appPal();
        TextView t = ReadingChrome.text(this, s, 14, on ? p.bg : p.text, on);
        t.setGravity(Gravity.CENTER);
        t.setPadding(px(16), 0, px(16), 0);
        t.setMinHeight(px(44));
        t.setBackground(pressable(on ? p.accent : p.surface, 12, true));
        t.setOnClickListener(l);
        return t;
    }

    private void saveGoal(int minutes) {
        getSharedPreferences("folio", MODE_PRIVATE).edit().putInt("goal", minutes).apply();
        if (tab == 4) refreshProgress();
        else if (tab == 3) refreshSettings();
        else if (tab == 0) refreshHome();
    }

    private String lockLabel() {
        int m = LockActivity.mode(this);
        return m == LockActivity.OFF ? "Desligada" : m == LockActivity.PIN ? "Deslizar e PIN" : "Deslizar para abrir";
    }

    private void chooseLock() {
        final String[] items = {"Desligada", "Deslizar para abrir", "Deslizar e PIN"};
        new android.app.AlertDialog.Builder(this, Theme.dialog())
                .setTitle("Tela de bloqueio")
                .setSingleChoiceItems(items, LockActivity.mode(this), (d, which) -> {
                    d.dismiss();
                    if (which == LockActivity.PIN) {
                        askLockPin();
                    } else {
                        LockActivity.setMode(this, which);
                        refreshSettings();
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void askLockPin() {
        final android.widget.EditText in = new android.widget.EditText(this);
        in.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        in.setHint("4 a 6 dígitos");
        new android.app.AlertDialog.Builder(this, Theme.dialog())
                .setTitle("PIN da tela de bloqueio")
                .setView(in)
                .setPositiveButton("Salvar", (d, w) -> {
                    String pin = in.getText().toString();
                    if (pin.length() < 4 || pin.length() > 6) {
                        android.widget.Toast.makeText(this, "Use de 4 a 6 dígitos", android.widget.Toast.LENGTH_SHORT).show();
                    } else {
                        LockActivity.setPin(this, pin);
                    }
                    refreshSettings();
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void chooseGoal() {
        final ReadingChrome.Pal p = appPal();
        final int cur = getSharedPreferences("folio", MODE_PRIVATE).getInt("goal", 20);
        final int[] opts = {10, 20, 30, 45, 60, 90};
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(px(20), px(16), px(20), px(24));
        box.addView(ReadingChrome.text(this, "Meta diária", 18, p.text, true));
        TextView hint = ReadingChrome.text(this, "Quanto tempo você quer ler por dia? Salva ao tocar.", 13, p.sec, false);
        hint.setPadding(0, px(4), 0, px(12));
        box.addView(hint);
        final android.app.Dialog[] dlg = new android.app.Dialog[1];
        boolean preset = false;
        for (int r = 0; r < 2; r++) {
            LinearLayout row = new LinearLayout(this);
            for (int c = 0; c < 3; c++) {
                final int m = opts[r * 3 + c];
                if (m == cur) preset = true;
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, px(48), 1f);
                lp.setMargins(0, 0, c < 2 ? px(8) : 0, px(8));
                row.addView(sheetChip(m + " min", m == cur, v -> {
                    saveGoal(m);
                    dlg[0].dismiss();
                }), lp);
            }
            box.addView(row);
        }
        LinearLayout custom = new LinearLayout(this);
        custom.setGravity(Gravity.CENTER_VERTICAL);
        final EditText in = new EditText(this);
        in.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        in.setHint("Personalizada (1–600 min)");
        in.setTextColor(p.text);
        in.setHintTextColor(p.sec);
        in.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        in.setSingleLine();
        in.setPadding(px(16), 0, px(16), 0);
        in.setBackground(Theme.round(p.surface, 12, true));
        if (!preset) in.setText(String.valueOf(cur));
        custom.addView(in, new LinearLayout.LayoutParams(0, px(48), 1f));
        custom.addView(sheetChip(preset ? "Salvar" : "Salvar ✓", !preset, v -> {
            int m;
            try {
                m = Integer.parseInt(in.getText().toString().trim());
            } catch (NumberFormatException e) {
                m = 0;
            }
            if (m < 1 || m > 600) {
                android.widget.Toast.makeText(this, "Digite um valor entre 1 e 600 minutos", android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
            saveGoal(m);
            dlg[0].dismiss();
        }), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(48)));
        ((LinearLayout.LayoutParams) custom.getChildAt(1).getLayoutParams()).setMargins(px(8), 0, 0, 0);
        box.addView(custom);
        dlg[0] = ReadingChrome.sheet(this, p, box, null);
        dlg[0].getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        box.setFocusableInTouchMode(true);
        box.requestFocus();
    }

    // ---------- Folio Transfer sheet ----------
    private TextView tvTransferState, tvTransferList;
    private Widgets.Bar transferBar;
    private final List<String> receivedNames = new ArrayList<>();
    private android.app.Dialog transferSheet;
    private String transferUrl;

    private void showTransferSheet() {
        final ReadingChrome.Pal p = appPal();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(px(20), px(16), px(20), px(24));
        box.addView(ReadingChrome.text(this, "Folio Transfer", 18, p.text, true));
        tvTransferState = ReadingChrome.text(this, "● Servidor ativo · desliga em 5 minutos", 13, p.accent, true);
        tvTransferState.setPadding(0, px(4), 0, px(12));
        box.addView(tvTransferState);

        LinearLayout addr = new LinearLayout(this);
        addr.setOrientation(LinearLayout.VERTICAL);
        addr.setPadding(px(16), px(12), px(16), px(12));
        addr.setBackground(Theme.round(p.bg, 16, true));
        addr.addView(ReadingChrome.text(this, "No celular ou PC (mesma rede Wi-Fi), abra:", 12, p.sec, false));
        TextView url = ReadingChrome.text(this, transferUrl, 18, p.text, true);
        url.setPadding(0, px(4), 0, px(8));
        addr.addView(url);
        LinearLayout codeRow = new LinearLayout(this);
        codeRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout codeCol = new LinearLayout(this);
        codeCol.setOrientation(LinearLayout.VERTICAL);
        codeCol.addView(ReadingChrome.text(this, "Código de pareamento", 12, p.sec, false));
        TextView code = ReadingChrome.text(this, server.code, 26, p.text, true);
        code.setLetterSpacing(0.15f);
        codeCol.addView(code);
        codeRow.addView(codeCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        codeRow.addView(sheetChip("Copiar endereço", false, v -> {
            ((android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE))
                    .setPrimaryClip(android.content.ClipData.newPlainText("Folio Transfer", transferUrl));
            android.widget.Toast.makeText(this, "Endereço copiado", android.widget.Toast.LENGTH_SHORT).show();
        }));
        addr.addView(codeRow);
        box.addView(addr);

        TextView qrHint = ReadingChrome.text(this, "Ou aponte a câmera do celular para o código:", 12, p.sec, false);
        qrHint.setPadding(0, px(12), 0, px(6));
        box.addView(qrHint);
        LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(px(176), px(176));
        qlp.gravity = Gravity.CENTER_HORIZONTAL;
        box.addView(QrCode.view(this, transferUrl), qlp);

        TextView st =ReadingChrome.text(this, "Aguardando envio…", 13, p.sec, false);
        st.setPadding(0, px(16), 0, px(8));
        transferBar = new Widgets.Bar(this);
        box.addView(st);
        box.addView(transferBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(8)));
        transferStatus = st;

        TextView lh = ReadingChrome.text(this, "Recebidos nesta sessão", 14, p.text, true);
        lh.setPadding(0, px(16), 0, px(4));
        box.addView(lh);
        tvTransferList = ReadingChrome.text(this, "", 13, p.sec, false);
        box.addView(tvTransferList);
        refreshTransferList();

        TextView warn = ReadingChrome.text(this, "Cada arquivo precisa ser aceito aqui. A conexão é HTTP, sem criptografia: use só em rede confiável.", 12, p.sec, false);
        warn.setPadding(0, px(16), 0, px(12));
        box.addView(warn);
        TextView stop = sheetChip("Encerrar servidor", true, v -> {
            if (server != null) server.stop();
        });
        box.addView(stop, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(48)));
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(box);
        transferSheet = ReadingChrome.sheet(this, p, sv, null);
    }

    private TextView transferStatus;

    private void refreshTransferList() {
        if (tvTransferList == null) return;
        if (receivedNames.isEmpty()) {
            tvTransferList.setText("Nenhum arquivo ainda.");
            return;
        }
        StringBuilder b = new StringBuilder();
        for (String n : receivedNames) b.append("• ").append(n).append('\n');
        tvTransferList.setText(b.toString().trim());
    }

    // ---------- UI shell: header, four pages, bottom navigation ----------
    private final View[] page = new View[5];
    private FolioApps apps;
    private final LinearLayout[] navItem = new LinearLayout[4];
    private static final String[] NAV_LABELS = {"Início", "Biblioteca", "Apps", "Ajustes"};
    private static final String[] NAV_ICONS = {"ic_home", "ic_library", "ic_apps", "ic_settings"};
    private LinearLayout homeBox, progBox, setBox;
    private EditText search;
    private ImageView viewBtn;
    private int tab;
    private boolean listMode;

    private android.graphics.drawable.Drawable ic(String name, int color) {
        android.graphics.drawable.Drawable dr = getResources()
                .getDrawable(getResources().getIdentifier(name, "drawable", getPackageName()), null).mutate();
        dr.setTint(color);
        return dr;
    }

    /** Rounded background with a visible pressed state. */
    private android.graphics.drawable.StateListDrawable pressable(int fill, int radiusDp, boolean stroke) {
        android.graphics.drawable.StateListDrawable s = new android.graphics.drawable.StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed}, Theme.round(Theme.border, radiusDp, stroke));
        s.addState(new int[]{}, Theme.round(fill, radiusDp, stroke));
        return s;
    }

    private ImageView iconButton(String icon, int sizeDp, String desc) {
        ImageView v = new ImageView(this);
        v.setImageDrawable(ic(icon, Theme.text));
        v.setPadding(px(10), px(10), px(10), px(10));
        v.setBackground(pressable(Theme.surface, 12, true));
        v.setContentDescription(desc);
        v.setLayoutParams(new LinearLayout.LayoutParams(px(sizeDp), px(sizeDp)));
        return v;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(px(16), px(16), px(16), px(16));
        c.setBackground(Theme.round(Theme.surface, 16, true));
        return c;
    }

    private LinearLayout.LayoutParams stack(int topDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.setMargins(0, px(topDp), 0, 0);
        return p;
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Theme.bg);

        FrameLayout header = new FrameLayout(this);
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(px(24), px(32), px(24), px(12));
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(text("Folio", 28, Theme.text, true));
        count = text("", 13, Theme.secondary, false);
        titles.addView(count);
        head.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(new StatusIcons(this), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        head.addView(brightnessButton("−", "Diminuir brilho", -10));
        head.addView(brightnessButton("+", "Aumentar brilho", 10));
        ImageView searchBtn = iconButton("ic_search", 44, "Pesquisar");
        searchBtn.setOnClickListener(v -> globalSearch());
        head.addView(searchBtn);
        head.addView(new View(this), new LinearLayout.LayoutParams(px(8), 1));
        importBtn = text("Importar", 15, Theme.bg, true);
        importBtn.setGravity(Gravity.CENTER);
        importBtn.setCompoundDrawablesWithIntrinsicBounds(ic("ic_plus", Theme.bg), null, null, null);
        importBtn.setCompoundDrawablePadding(px(8));
        importBtn.setPadding(px(16), 0, px(20), 0);
        importBtn.setBackground(pressable(Theme.accent, 12, false));
        importBtn.setContentDescription("Importar livros");
        importBtn.setOnClickListener(v -> pick());
        head.addView(importBtn, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(44)));
        header.addView(head);
        View corner = new View(this);
        header.addView(corner, new FrameLayout.LayoutParams(px(72), px(32), Gravity.TOP | Gravity.END));
        Kiosk.attachExit(this, corner);
        root.addView(header);

        FrameLayout pages = new FrameLayout(this);
        page[0] = scroll(homeBox = new LinearLayout(this));
        page[1] = buildLibrary();
        apps = new FolioApps(this, profile == 0 ? 32 : profile == 1 ? 64 : 128);
        page[2] = apps.view();
        page[4] = scroll(progBox = new LinearLayout(this));
        page[3] = scroll(setBox = new LinearLayout(this));
        for (View p : page) pages.addView(p, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(pages, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        TextView foot = text(versionLine() + " · Dev Samuel Lima", 11, Theme.secondary, false);
        foot.setGravity(Gravity.CENTER);
        foot.setPadding(0, px(4), 0, px(4));
        root.addView(foot, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        View line = new View(this);
        line.setBackgroundColor(Theme.border);
        root.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
        LinearLayout nav = new LinearLayout(this);
        nav.setBackgroundColor(Theme.surface);
        for (int i = 0; i < 4; i++) {
            final int k = i;
            LinearLayout it = new LinearLayout(this);
            it.setOrientation(LinearLayout.VERTICAL);
            it.setGravity(Gravity.CENTER);
            it.setPadding(0, px(8), 0, px(8));
            it.setContentDescription(NAV_LABELS[i]);
            ImageView im = new ImageView(this);
            it.addView(im, new LinearLayout.LayoutParams(px(24), px(24)));
            it.addView(text(NAV_LABELS[i], 11, Theme.secondary, false));
            ((TextView) it.getChildAt(1)).setGravity(Gravity.CENTER);
            it.setOnClickListener(v -> setTab(k));
            navItem[i] = it;
            nav.addView(it, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        root.addView(nav);

        root.setFocusableInTouchMode(true);
        getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        setContentView(root);
        root.requestFocus();
        setTab(tab);
    }

    private View scroll(LinearLayout box) {
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(px(24), px(8), px(24), px(24));
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(box);
        return sv;
    }

    private void setTab(int t) {
        tab = t;
        for (int i = 0; i < page.length; i++) page[i].setVisibility(i == t ? View.VISIBLE : View.GONE);
        if (t == 2) apps.onShow();
        else apps.onHide();
        for (int i = 0; i < 4; i++) {
            boolean on = i == (t == 4 ? 0 : t); // Progresso is a sub-page of Início
            ImageView im = (ImageView) navItem[i].getChildAt(0);
            im.setImageDrawable(ic(NAV_ICONS[i], on ? Theme.accent : Theme.secondary));
            TextView tv = (TextView) navItem[i].getChildAt(1);
            tv.setTextColor(on ? Theme.accent : Theme.secondary);
            tv.setTypeface(Typeface.SERIF, on ? Typeface.BOLD : Typeface.NORMAL);
        }
        if (t == 0) refreshHome();
        else if (t == 4) refreshProgress();
        else if (t == 3) refreshSettings();
    }

    private View buildLibrary() {
        LinearLayout lib = new LinearLayout(this);
        lib.setOrientation(LinearLayout.VERTICAL);

        search = new EditText(this);
        search.setHint("Buscar por título ou autor");
        search.setSingleLine();
        search.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        search.setTextColor(Theme.text);
        search.setHintTextColor(Theme.secondary);
        search.setPadding(px(16), px(8), px(16), px(8));
        search.setBackground(Theme.round(Theme.surface, 12, true));
        search.setVisibility(View.GONE);
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence c, int a, int b2, int n) {
            }

            @Override
            public void onTextChanged(CharSequence c, int a, int b2, int n) {
                query = c.toString().trim().toLowerCase();
                applyFilter();
            }

            @Override
            public void afterTextChanged(Editable e) {
            }
        });
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(44));
        sp.setMargins(px(24), px(4), px(24), 0);
        lib.addView(search, sp);

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(px(24), px(8), px(24), 0);
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        chips = new LinearLayout(this);
        hs.addView(chips);
        bar.addView(hs, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        listMode = getSharedPreferences("folio", MODE_PRIVATE).getBoolean("list", false);
        viewBtn = iconButton(listMode ? "ic_grid" : "ic_list", 36, listMode ? "Ver em grade" : "Ver em lista");
        viewBtn.setPadding(px(8), px(8), px(8), px(8));
        viewBtn.setOnClickListener(v -> {
            listMode = !listMode;
            getSharedPreferences("folio", MODE_PRIVATE).edit().putBoolean("list", listMode).apply();
            viewBtn.setImageDrawable(ic(listMode ? "ic_grid" : "ic_list", Theme.text));
            viewBtn.setContentDescription(listMode ? "Ver em grade" : "Ver em lista");
            grid.setNumColumns(columns());
            grid.setAdapter(adapter); // reset the recycler: the two layouts use different cell types
            if (PaperMotion.enabled()) {
                grid.animate().cancel();
                grid.setAlpha(0f);
                grid.animate().alpha(1f).setDuration(PaperMotion.duration()).start();
            }
        });
        bar.addView(viewBtn);
        lib.addView(bar);

        FrameLayout body = new FrameLayout(this);
        grid = new GridView(this);
        grid.setPadding(px(24), px(16), px(24), px(16));
        grid.setClipToPadding(false);
        grid.setVerticalSpacing(px(16));
        grid.setHorizontalSpacing(px(12));
        grid.setVerticalScrollBarEnabled(false);
        grid.setSelector(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        grid.setNumColumns(columns());
        adapter = new Shelf();
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((p, v, pos, id) -> openBook(books.get(pos)));
        grid.setOnItemLongClickListener((p, v, pos, id) -> {
            bookMenu(books.get(pos));
            return true;
        });
        body.addView(grid);
        empty = text("", 16, Theme.secondary, false);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(px(40), 0, px(40), 0);
        empty.setVisibility(View.GONE);
        body.addView(empty, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        lib.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return lib;
    }

    // ---------- Home ----------
    /** Loads a cover into the views asynchronously; shows the placeholder until it arrives. */
    private void bindCover(final Book b, final ImageView img, final TextView ph) {
        final String key = b.file.getAbsolutePath();
        img.setTag(key);
        Bitmap bm = covers.get(key);
        if (bm != null) {
            img.setImageBitmap(bm);
            ph.setVisibility(View.GONE);
            return;
        }
        img.setImageDrawable(null);
        ph.setText(b.title);
        ph.setVisibility(View.VISIBLE);
        if (noCover.contains(key)) return;
        io.execute(() -> {
            Bitmap loaded = profile == 0 && b.paged() ? null : b.cover(COVER_PX[profile]);
            if (loaded == null) {
                noCover.add(key);
                return;
            }
            covers.put(key, loaded);
            ui.post(() -> {
                if (key.equals(img.getTag())) {
                    img.setImageBitmap(loaded);
                    ph.setVisibility(View.GONE);
                }
            });
        });
    }

    private FrameLayout coverBox(Book b, int wDp) {
        FrameLayout box = new FrameLayout(this);
        box.setBackground(Theme.round(Theme.surface, 12, true));
        Theme.clip(box, 12);
        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        box.addView(img, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        TextView ph = text("", wDp >= 100 ? 14 : 11, Theme.text, true);
        ph.setGravity(Gravity.CENTER);
        ph.setPadding(px(8), px(8), px(8), px(8));
        box.addView(ph, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        box.setLayoutParams(new LinearLayout.LayoutParams(px(wDp), px(wDp) * 4 / 3));
        bindCover(b, img, ph);
        return box;
    }

    private boolean homePref(String key) {
        return getSharedPreferences("folio", MODE_PRIVATE).getBoolean("home_" + key, true);
    }

    private void homeHeader() {
        if (homePref("clock")) {
            LinearLayout c = card();
            android.widget.TextClock clock = new android.widget.TextClock(this);
            clock.setFormat24Hour("HH:mm");
            clock.setFormat12Hour("HH:mm");
            clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 44);
            clock.setTypeface(Typeface.SERIF, Typeface.BOLD);
            clock.setTextColor(Theme.text);
            c.addView(clock);
            String ds = new java.text.SimpleDateFormat("EEEE, d 'de' MMMM", new Locale("pt", "BR")).format(new java.util.Date());
            TextView date = text(Character.toUpperCase(ds.charAt(0)) + ds.substring(1), 14, Theme.secondary, false);
            c.addView(date);
            int hr = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
            String hi = hr < 5 ? "Boa madrugada" : hr < 12 ? "Bom dia" : hr < 18 ? "Boa tarde" : "Boa noite";
            Book lb = lastId == null ? null : byId.get(lastId);
            TextView greet = text(lb == null ? hi + "." : hi + ". Continue de onde parou.", 15, Theme.text, true);
            greet.setPadding(0, px(8), 0, 0);
            c.addView(greet);
            homeBox.addView(c, stack(8));
        }
        if (homePref("shortcuts")) {
            HorizontalScrollView hs = new HorizontalScrollView(this);
            hs.setHorizontalScrollBarEnabled(false);
            LinearLayout row = new LinearLayout(this);
            String[] names = {"Biblioteca", "Apps", "Progresso", "Central de controle", "Folio Transfer"};
            for (int i = 0; i < names.length; i++) {
                final int k = i;
                TextView chip = sheetChip(names[i], false, v -> {
                    if (k == 0) setTab(1);
                    else if (k == 1) setTab(2);
                    else if (k == 2) setTab(4);
                    else if (k == 3) openControl();
                    else startTransfer();
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(44));
                lp.setMargins(0, 0, px(8), 0);
                row.addView(chip, lp);
            }
            hs.addView(row);
            homeBox.addView(hs, stack(8));
        }
    }

    private void customizeHome() {
        final ReadingChrome.Pal p = appPal();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(px(20), px(16), px(20), px(24));
        box.addView(ReadingChrome.text(this, "Personalizar Início", 18, p.text, true));
        final String[] keys = {"clock", "shortcuts", "goal", "recent"};
        final String[] names = {"Relógio e data", "Atalhos", "Meta de hoje", "Abertos recentemente"};
        for (int i = 0; i < keys.length; i++) {
            final String key = keys[i];
            LinearLayout r = new LinearLayout(this);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(0, px(8), 0, px(8));
            r.addView(ReadingChrome.text(this, names[i], 16, p.text, false), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            final TextView sw = sheetChip(homePref(key) ? "Visível" : "Oculto", homePref(key), null);
            sw.setOnClickListener(v -> {
                boolean now = !homePref(key);
                getSharedPreferences("folio", MODE_PRIVATE).edit().putBoolean("home_" + key, now).apply();
                sw.setText(now ? "Visível" : "Oculto");
                sw.setTextColor(now ? p.bg : p.text);
                sw.setTypeface(Typeface.SERIF, now ? Typeface.BOLD : Typeface.NORMAL);
                sw.setBackground(pressable(now ? p.accent : p.surface, 12, true));
                refreshHome();
            });
            r.addView(sw, new LinearLayout.LayoutParams(px(104), px(44)));
            box.addView(r);
        }
        PaperMotion.sheetIn(ReadingChrome.sheet(this, p, box, null));
    }

    private int homeGen;

    private void refreshHome() {
        final int gen = ++homeGen;
        homeBox.removeAllViews();
        homeHeader();
        Book last = lastId == null ? null : byId.get(lastId);
        if (all.isEmpty()) {
            LinearLayout c = card();
            c.addView(text("Sua estante está vazia", 18, Theme.text, true));
            TextView t = text("Toque em Importar para escolher livros, receba por Wi-Fi em Ajustes, ou copie arquivos para a pasta Folio do tablet.", 14, Theme.secondary, false);
            t.setPadding(0, px(8), 0, 0);
            c.addView(t);
            homeBox.addView(c, stack(8));
            return;
        }
        if (last != null) {
            LinearLayout c = card();
            c.setOrientation(LinearLayout.HORIZONTAL);
            c.addView(coverBox(last, 84));
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setPadding(px(16), 0, 0, 0);
            col.addView(text("CONTINUAR LENDO", 11, Theme.secondary, true));
            TextView tt = text(last.title, 18, Theme.text, true);
            tt.setMaxLines(2);
            tt.setEllipsize(TextUtils.TruncateAt.END);
            col.addView(tt);
            col.addView(text(last.author, 13, Theme.secondary, false));
            Integer ch = chapters.get(lastId);
            if (ch != null) col.addView(text((last.paged() ? "Página " : "Capítulo ") + (ch + 1), 13, Theme.secondary, false));
            TextView go = text("Continuar", 14, Theme.bg, true);
            go.setGravity(Gravity.CENTER);
            go.setPadding(px(20), 0, px(20), 0);
            go.setBackground(pressable(Theme.accent, 12, false));
            LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(40));
            gp.setMargins(0, px(12), 0, 0);
            col.addView(go, gp);
            c.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            c.setOnClickListener(v -> openBook(last));
            PaperMotion.press(c);
            homeBox.addView(c, stack(8));
        }

        if (homePref("goal")) {
        final LinearLayout goal = card();
        goal.setOrientation(LinearLayout.HORIZONTAL);
        goal.setGravity(Gravity.CENTER_VERTICAL);
        final Widgets.Ring gb = new Widgets.Ring(this);
        goal.addView(gb, new LinearLayout.LayoutParams(px(76), px(76)));
        LinearLayout gcol = new LinearLayout(this);
        gcol.setOrientation(LinearLayout.VERTICAL);
        gcol.setPadding(px(16), 0, 0, 0);
        gcol.addView(text("Meta de hoje", 15, Theme.text, true));
        final TextView gt = text("", 13, Theme.secondary, false);
        gt.setPadding(0, px(4), 0, 0);
        gcol.addView(gt);
        goal.addView(gcol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        goal.setOnClickListener(v -> setTab(4));
        homeBox.addView(goal, stack(16));
        io.execute(() -> {
            long today = Db.get(this).secondsSince(dayStart(System.currentTimeMillis()));
            int goalMin = getSharedPreferences("folio", MODE_PRIVATE).getInt("goal", 20);
            ui.post(() -> {
                gt.setText(today >= goalMin * 60L ? "Meta cumprida! " + dur(today) + " de " + goalMin + " min"
                        : dur(today) + " de " + goalMin + " min");
                gb.set(today / (goalMin * 60f));
            });
        });
        }

        io.execute(() -> {
            final List<String> ids = Db.get(this).recent(8);
            ui.post(() -> {
                List<Book> rec = new ArrayList<>();
                for (String id : ids) if (byId.get(id) != null && !id.equals(lastId)) rec.add(byId.get(id));
                if (gen != homeGen || rec.isEmpty() || tab != 0 || !homePref("recent")) return;
                TextView h = text("Abertos recentemente", 15, Theme.text, true);
                homeBox.addView(h, stack(24));
                HorizontalScrollView hs = new HorizontalScrollView(this);
                hs.setHorizontalScrollBarEnabled(false);
                LinearLayout row = new LinearLayout(this);
                for (final Book b : rec) {
                    LinearLayout item = new LinearLayout(this);
                    item.setOrientation(LinearLayout.VERTICAL);
                    item.setPadding(0, 0, px(12), 0);
                    item.addView(coverBox(b, 96));
                    TextView t = text(b.title, 12, Theme.text, true);
                    t.setMaxLines(2);
                    t.setEllipsize(TextUtils.TruncateAt.END);
                    t.setWidth(px(96));
                    t.setPadding(0, px(4), 0, 0);
                    item.addView(t);
                    item.setOnClickListener(v -> openBook(b));
                    PaperMotion.press(item);
                    row.addView(item);
                }
                hs.addView(row);
                homeBox.addView(hs, stack(8));
            });
        });
    }

    private long dayStart(long now) {
        long off = java.util.TimeZone.getDefault().getOffset(now);
        return now - ((now + off) % 86400000L);
    }

    // ---------- Progress dashboard ----------
    private TextView statCard(LinearLayout row, String label, String value) {
        LinearLayout c = card();
        c.setPadding(px(12), px(12), px(12), px(12));
        TextView v = text(value, 22, Theme.text, true);
        c.addView(v);
        c.addView(text(label, 12, Theme.secondary, false));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        p.setMargins(row.getChildCount() == 0 ? 0 : px(8), 0, 0, 0);
        row.addView(c, p);
        return v;
    }

    private void refreshProgress() {
        io.execute(() -> {
            Db db = Db.get(this);
            long now = System.currentTimeMillis();
            long off = java.util.TimeZone.getDefault().getOffset(now);
            long d0 = dayStart(now);
            int goal = getSharedPreferences("folio", MODE_PRIVATE).getInt("goal", 20);
            final long today = db.secondsSince(d0), total = db.secondsSince(0);
            final float[] week = new float[7];
            final String[] lab = new String[7];
            java.text.SimpleDateFormat wf = new java.text.SimpleDateFormat("EEE", new java.util.Locale("pt", "BR"));
            for (int k = 0; k < 7; k++) {
                long a = d0 - (6 - k) * 86400000L;
                week[k] = db.secondsBetween(a, a + 86400000L) / 60f;
                String w = wf.format(new java.util.Date(a + 43200000L));
                lab[k] = w.substring(0, 1).toUpperCase();
            }
            java.util.Calendar cal = java.util.Calendar.getInstance();
            int dom = cal.get(java.util.Calendar.DAY_OF_MONTH), dim = cal.getActualMaximum(java.util.Calendar.DAY_OF_MONTH);
            long m0 = d0 - (dom - 1) * 86400000L;
            final long month = db.secondsSince(m0);
            int readDays = 0;
            long dayNow = (now + off) / 86400000L, expect = dayNow;
            int streak = 0;
            List<Long> days = db.readingDays();
            for (long dd : days) if (dd >= dayNow - (dom - 1)) readDays++;
            if (!days.isEmpty() && days.get(0) == dayNow - 1) expect = dayNow - 1;
            for (long dd : days) {
                if (dd == expect) {
                    streak++;
                    expect--;
                } else if (dd < expect) break;
            }
            final int fStreak = streak, fRead = readDays, fGoal = goal, fDim = dim, fDom = dom;
            final int nBooks = all.size(), nDone = db.finishedCount();
            final List<String[]> rec = db.recentSessions(6);
            ui.post(() -> {
                if (tab != 4) return;
                progBox.removeAllViews();
                LinearLayout r1 = new LinearLayout(this);
                statCard(r1, "Livros", String.valueOf(nBooks));
                statCard(r1, "Concluídos", String.valueOf(nDone));
                progBox.addView(r1, stack(8));
                LinearLayout r2 = new LinearLayout(this);
                statCard(r2, "Tempo total", dur(total));
                statCard(r2, "Sequência", fStreak + (fStreak == 1 ? " dia" : " dias"));
                progBox.addView(r2, stack(8));

                LinearLayout g = card();
                g.addView(text("Meta diária", 15, Theme.text, true));
                TextView gt = text(dur(today) + " de " + fGoal + " min", 13, Theme.secondary, false);
                gt.setPadding(0, px(4), 0, px(8));
                g.addView(gt);
                Widgets.Bar gb = new Widgets.Bar(this);
                gb.set(today / (fGoal * 60f));
                g.addView(gb, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(8)));
                g.setOnClickListener(v -> chooseGoal());
                progBox.addView(g, stack(16));

                LinearLayout w = card();
                w.addView(text("Últimos 7 dias (minutos)", 15, Theme.text, true));
                Widgets.Chart ch = new Widgets.Chart(this);
                ch.set(week, lab, 6);
                w.addView(ch, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(140)));
                progBox.addView(w, stack(16));

                LinearLayout mo = card();
                mo.addView(text("Este mês", 15, Theme.text, true));
                TextView mt = text(dur(month) + " lidos em " + fRead + (fRead == 1 ? " dia" : " dias")
                        + " · meta " + (fGoal * fDim) + " min", 13, Theme.secondary, false);
                mt.setPadding(0, px(4), 0, px(8));
                mo.addView(mt);
                Widgets.Bar mb = new Widgets.Bar(this);
                mb.set(month / (fGoal * fDim * 60f));
                mo.addView(mb, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(8)));
                progBox.addView(mo, stack(16));

                LinearLayout hist = card();
                hist.addView(text("Histórico recente", 15, Theme.text, true));
                if (rec.isEmpty()) {
                    TextView e = text("Nenhuma leitura registrada ainda. Abra um livro e leia por alguns segundos.", 13, Theme.secondary, false);
                    e.setPadding(0, px(8), 0, 0);
                    hist.addView(e);
                }
                java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.getDefault());
                for (String[] s : rec) {
                    LinearLayout row = new LinearLayout(this);
                    row.setPadding(0, px(8), 0, 0);
                    TextView a = text(s[0], 13, Theme.text, false);
                    a.setSingleLine();
                    a.setEllipsize(TextUtils.TruncateAt.END);
                    row.addView(a, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                    row.addView(text(fmt.format(new java.util.Date(Long.parseLong(s[1]))) + " · " + dur(Long.parseLong(s[2])), 12, Theme.secondary, false));
                    hist.addView(row);
                }
                progBox.addView(hist, stack(16));
            });
        });
    }

    // ---------- Settings ----------
    private void settingRow(String title, String value, View.OnClickListener click) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(px(16), px(12), px(16), px(12));
        row.setMinimumHeight(px(56));
        row.setBackground(pressable(Theme.surface, 16, true));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(text(title, 15, Theme.text, true));
        if (value != null) col.addView(text(value, 13, Theme.secondary, false));
        row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (click != null) row.setOnClickListener(click);
        setBox.addView(row, stack(8));
    }

    private void refreshSettings() {
        setBox.removeAllViews();
        settingRow("Central de controle", "Wi-Fi, Bluetooth, brilho, volume e mais", v -> openControl());
        settingRow("Personalizar Início", "Escolha o que aparece na tela inicial", v -> customizeHome());
        settingRow("Atualizações do Folio", "Verificar versão, instalar APK", v -> new FolioUpdate(this).show());
        settingRow("Tema", Theme.NAMES[Theme.index], v -> chooseTheme());
        settingRow("Modo noite automático", getSharedPreferences("folio", MODE_PRIVATE).getBoolean("auto_night", false)
                ? "Ligado: tema escuro das 19h às 6h" : "Desligado", v -> {
            android.content.SharedPreferences sp = getSharedPreferences("folio", MODE_PRIVATE);
            boolean on = !sp.getBoolean("auto_night", false);
            android.content.SharedPreferences.Editor ed = sp.edit().putBoolean("auto_night", on).putInt("auto_period", -1);
            if (on && Theme.index != 1) ed.putInt("day_theme", Theme.index);
            ed.apply();
            if (on && autoNight()) return;
            refreshSettings();
        });
        settingRow("Tela de bloqueio", lockLabel(), v -> chooseLock());
        settingRow("Perfil de desempenho", PROFILES[profile], v -> chooseProfile());
        settingRow("Movimento", PaperMotion.NAMES[PaperMotion.mode()], v -> chooseMotion());
        settingRow("Meta diária de leitura", getSharedPreferences("folio", MODE_PRIVATE).getInt("goal", 20) + " minutos", v -> chooseGoal());
        settingRow("Folio Transfer", "Receber livros por Wi-Fi (desligado até você abrir)", v -> startTransfer());
        settingRow("Sobre", versionLine() + " · Android " + android.os.Build.VERSION.RELEASE + " · Dev Samuel Lima", null);
    }

    private void chooseMotion() {
        choiceSheet("Movimento", PaperMotion.NAMES, PaperMotion.NOTES, PaperMotion.mode(), which -> {
            PaperMotion.setMode(this, which);
            refreshSettings();
        });
    }

    private void chooseTheme() {
        choiceSheet("Tema", Theme.NAMES,
                new String[]{"Papel creme, texto grafite", "Grafite escuro, texto claro", "Preto e branco, contraste alto, sem transparência"},
                Theme.index, which -> {
                    if (which != 1) getSharedPreferences("folio", MODE_PRIVATE).edit().putInt("day_theme", which).apply();
                    Theme.set(this, which);
                    recreate();
                });
    }

    private final class Shelf extends BaseAdapter {
        @Override
        public int getCount() {
            return books.size();
        }

        @Override
        public Object getItem(int i) {
            return books.get(i);
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public int getViewTypeCount() {
            return 2;
        }

        @Override
        public int getItemViewType(int i) {
            return listMode ? 1 : 0;
        }

        @Override
        public View getView(int i, View v, ViewGroup parent) {
            Cell c;
            int w = listMode ? px(64) : cellWidth();
            if (v == null) {
                c = new Cell();
                c.root = new LinearLayout(MainActivity.this);
                c.root.setOrientation(listMode ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
                FrameLayout box = new FrameLayout(MainActivity.this);
                box.setBackground(Theme.round(Theme.surface, 12, true));
                Theme.clip(box, 12);
                c.img = new ImageView(MainActivity.this);
                c.img.setScaleType(ImageView.ScaleType.CENTER_CROP);
                box.addView(c.img, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                c.ph = text("", listMode ? 10 : 14, Theme.text, true);
                c.ph.setGravity(Gravity.CENTER);
                c.ph.setPadding(px(8), px(8), px(8), px(8));
                box.addView(c.ph, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                c.box = box;
                LinearLayout col = new LinearLayout(MainActivity.this);
                col.setOrientation(LinearLayout.VERTICAL);
                c.title = text("", 14, Theme.text, true);
                c.title.setMaxLines(2);
                c.title.setEllipsize(TextUtils.TruncateAt.END);
                c.sub = text("", 12, Theme.secondary, false);
                c.sub.setSingleLine();
                c.sub.setEllipsize(TextUtils.TruncateAt.END);
                if (listMode) {
                    c.root.setGravity(Gravity.CENTER_VERTICAL);
                    c.root.addView(box);
                    col.setPadding(px(12), 0, 0, 0);
                    col.addView(c.title);
                    col.addView(c.sub);
                    c.root.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                } else {
                    c.root.addView(box);
                    c.title.setPadding(0, px(8), 0, 0);
                    c.root.addView(c.title);
                    c.root.addView(c.sub);
                }
                c.root.setTag(c);
                PaperMotion.press(c.root);
                v = c.root;
            } else {
                c = (Cell) v.getTag();
            }
            c.box.setLayoutParams(new LinearLayout.LayoutParams(w, w * 4 / 3));

            Book b = books.get(i);
            final String key = b.file.getAbsolutePath();
            String id = Book.id(b.file);
            Integer ch = chapters.get(id);
            String where = ch != null ? "Continuar · " + (b.paged() ? "pág. " : "cap. ") + (ch + 1) : null;
            c.title.setText((favs.contains(id) ? "★ " : "") + b.title);
            c.sub.setText(where != null ? where : b.author);
            bindCover(b, c.img, c.ph);
            return v;
        }
    }

    private static final class Cell {
        LinearLayout root;
        FrameLayout box;
        ImageView img;
        TextView ph, title, sub;
    }
}
