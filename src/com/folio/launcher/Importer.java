package com.folio.launcher;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;

/** Copies books picked through the system document picker into Folio's own storage. No storage permission needed. */
final class Importer {
    static final long MAX_BYTES = 300L * 1024 * 1024;

    private Importer() {
    }

    static File dir(Context c) {
        File d = c.getExternalFilesDir("books");
        if (d == null) d = new File(c.getFilesDir(), "books");
        d.mkdirs();
        return d;
    }

    /** Returns null on success, or a short message in Portuguese describing why the file was skipped. */
    static String importOne(Context c, Uri uri) {
        String name = displayName(c, uri);
        if (!Book.supported(name)) return name + ": formato não suportado";
        File dir = dir(c);
        File tmp = new File(dir, ".import.tmp");
        try {
            String hash;
            try (InputStream in = c.getContentResolver().openInputStream(uri)) {
                if (in == null) return name + ": não foi possível abrir";
                MessageDigest md = MessageDigest.getInstance("SHA-1");
                long total = 0;
                try (OutputStream out = new FileOutputStream(tmp)) {
                    byte[] buf = new byte[32 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        total += n;
                        if (total > MAX_BYTES) return name + ": arquivo grande demais (máx. 300 MB)";
                        md.update(buf, 0, n);
                        out.write(buf, 0, n);
                    }
                }
                if (total == 0) return name + ": arquivo vazio";
                StringBuilder sb = new StringBuilder();
                for (byte b : md.digest()) sb.append(String.format("%02x", b));
                hash = sb.toString();
            }
            Db db = Db.get(c);
            if (db.hasHash(hash)) return name + ": já está na biblioteca";

            File dest = unique(dir, safeName(name));
            if (!tmp.renameTo(dest)) return name + ": falha ao salvar";
            Book b;
            try {
                b = Book.open(dest);
            } catch (Exception e) {
                dest.delete();
                return name + ": arquivo inválido ou corrompido";
            }
            db.upsert(b, Book.id(dest), true, hash);
            return null;
        } catch (Exception e) {
            return name + ": erro (" + e.getClass().getSimpleName() + ")";
        } finally {
            tmp.delete();
        }
    }

    private static String displayName(Context c, Uri uri) {
        String n = null;
        try (Cursor q = c.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (q != null && q.moveToFirst()) n = q.getString(0);
        } catch (Exception ignored) {
        }
        if (n == null) n = uri.getLastPathSegment();
        return n == null ? "arquivo" : n;
    }

    /** Strips path separators and control characters so a name can never escape the books folder. */
    static String safeName(String n) {
        n = n.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        while (n.startsWith(".")) n = n.substring(1);
        if (n.length() > 120) {
            int dot = n.lastIndexOf('.');
            String ext = dot > 0 ? n.substring(dot) : "";
            n = n.substring(0, 120 - ext.length()) + ext;
        }
        return n.isEmpty() ? "livro" : n;
    }

    private static File unique(File dir, String name) {
        File f = new File(dir, name);
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 2; f.exists(); i++) f = new File(dir, base + " (" + i + ")" + ext);
        return f;
    }
}
