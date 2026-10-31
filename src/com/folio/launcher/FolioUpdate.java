package com.folio.launcher;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Atualizações do Folio. Nothing runs in the background: the user starts every check.
 * Three ways in: (1) install an APK picked from storage, (2) look for a published release on GitHub,
 * (3) adb install -r from a computer (explained on screen). Android keeps books and settings because
 * the package name and signing key stay the same and versionCode only goes up.
 * Releases use the tag "build-<versionCode>" with the APK attached.
 */
final class FolioUpdate {
    static final int REQ_APK = 77;
    private static final String REPO = "S4MSO21/folio-launcher";
    private static final long MAX_BYTES = 60L * 1024 * 1024;

    private final Activity a;
    private final float d;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView status;
    private Dialog dlg;

    FolioUpdate(Activity activity) {
        a = activity;
        d = a.getResources().getDisplayMetrics().density;
    }

    private PackageInfo me() throws Exception {
        return a.getPackageManager().getPackageInfo(a.getPackageName(), 0);
    }

    void show() {
        final ReadingChrome.Pal p = ReadingChrome.pal(0);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(px(20), px(16), px(20), px(24));
        box.addView(ReadingChrome.text(a, "Atualizações do Folio", 18, p.text, true));
        String cur = "Folio";
        try {
            cur = "Instalado: Folio " + me().versionName + " · build " + me().versionCode;
        } catch (Exception ignored) {
        }
        TextView v = ReadingChrome.text(a, cur, 13, p.sec, false);
        v.setPadding(0, px(4), 0, px(12));
        box.addView(v);

        box.addView(button(p, "Verificar no GitHub", () -> check()));
        box.addView(button(p, "Instalar APK do armazenamento", () -> {
            dlg.dismiss();
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/vnd.android.package-archive");
            a.startActivityForResult(i, REQ_APK);
        }));
        status = ReadingChrome.text(a, "Nenhuma verificação feita. O Folio só consulta a internet quando você pede.", 13, p.sec, false);
        status.setPadding(0, px(12), 0, px(8));
        box.addView(status);
        TextView adb = ReadingChrome.text(a, "Pelo computador: adb install -r folio.apk. Livros, progresso e ajustes são mantidos, desde que o APK tenha a mesma assinatura e um número de build maior.", 12, p.sec, false);
        adb.setPadding(0, px(8), 0, 0);
        box.addView(adb);
        ScrollView sv = new ScrollView(a);
        sv.addView(box);
        dlg = ReadingChrome.sheet(a, p, sv, null);
        PaperMotion.sheetIn(dlg);
    }

    private TextView button(ReadingChrome.Pal p, String s, final Runnable go) {
        TextView t = ReadingChrome.text(a, s, 15, p.bg, true);
        t.setGravity(Gravity.CENTER);
        t.setBackground(Theme.round(p.accent, 12, false));
        t.setOnClickListener(x -> go.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(48));
        lp.setMargins(0, px(8), 0, 0);
        t.setLayoutParams(lp);
        return t;
    }

    /** Hands an APK the user picked to the system installer. */
    void installFrom(Uri uri) {
        try {
            Intent i = new Intent(Intent.ACTION_INSTALL_PACKAGE).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true);
            a.startActivity(i);
        } catch (Exception e) {
            Toast.makeText(a, "Não foi possível abrir o instalador do Android.", Toast.LENGTH_LONG).show();
        }
    }

    // ---------- GitHub ----------
    private void say(final String s) {
        ui.post(() -> {
            if (status != null) status.setText(s);
        });
    }

    private void check() {
        say("Consultando o GitHub…");
        new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL("https://api.github.com/repos/" + REPO + "/releases/latest").openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                c.setRequestProperty("Accept", "application/vnd.github+json");
                int code = c.getResponseCode();
                if (code == 404) {
                    say("Nenhuma versão publicada, ou o repositório é privado. Use o APK do armazenamento ou o adb.");
                    return;
                }
                if (code != 200) {
                    say("O GitHub respondeu " + code + ". Tente de novo mais tarde.");
                    return;
                }
                String body = read(c.getInputStream(), 1 << 20);
                JSONObject j = new JSONObject(body);
                String tag = j.optString("tag_name", "");
                int remote = parseBuild(tag);
                int local = me().versionCode;
                String notes = j.optString("body", "").trim();
                if (remote <= local) {
                    say("Você já está na versão mais recente (build " + local + ").");
                    return;
                }
                JSONArray assets = j.optJSONArray("assets");
                String url = null;
                for (int i = 0; assets != null && i < assets.length(); i++) {
                    JSONObject as = assets.getJSONObject(i);
                    if (as.optString("name").endsWith(".apk")) url = as.optString("browser_download_url");
                }
                if (url == null) {
                    say("A versão build " + remote + " existe, mas não tem APK anexado.");
                    return;
                }
                final String dl = url;
                say("Nova versão: build " + remote + (notes.isEmpty() ? "" : "\n" + (notes.length() > 300 ? notes.substring(0, 300) + "…" : notes)) + "\nBaixando…");
                download(dl);
            } catch (Exception e) {
                say("Não foi possível consultar (sem internet?).");
            }
        }, "folio-update").start();
    }

    private static int parseBuild(String tag) {
        try {
            return Integer.parseInt(tag.replaceAll("[^0-9]", ""));
        } catch (Exception e) {
            return -1;
        }
    }

    private void download(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(20000);
        c.setInstanceFollowRedirects(true);
        long len = c.getContentLengthLong();
        if (c.getResponseCode() != 200 || len > MAX_BYTES) {
            say("Download recusado (tamanho ou resposta inválidos).");
            return;
        }
        File dir = new File(a.getExternalFilesDir(null), "updates");
        dir.mkdirs();
        File out = new File(dir, "folio-update.apk");
        long done = 0;
        try (InputStream in = c.getInputStream(); FileOutputStream os = new FileOutputStream(out)) {
            byte[] buf = new byte[32 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                os.write(buf, 0, n);
                done += n;
                if (done > MAX_BYTES) throw new java.io.IOException("grande demais");
            }
        }
        final Uri u = Uri.fromFile(out);
        ui.post(() -> {
            if (dlg != null) dlg.dismiss();
            installFrom(u);
        });
    }

    private static String read(InputStream in, int max) throws Exception {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0 && b.size() < max) b.write(buf, 0, n);
        return b.toString("UTF-8");
    }

    private int px(int dp) {
        return (int) (dp * d);
    }
}
