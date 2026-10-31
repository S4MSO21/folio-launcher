package com.folio.launcher;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** One book on disk, in any supported format. Text formats are turned into HTML chapters for the reader. */
final class Book {
    enum Kind { EPUB, PDF, TXT, FB2, CBZ, HTML }

    private static final String HEAD = "<html><head><meta charset=\"utf-8\"></head><body>";
    private static final String TAIL = "</body></html>";
    private static final long MAX_TEXT_BYTES = 16L * 1024 * 1024;
    private static final Pattern DIGITS = Pattern.compile("\\d+");

    final File file;
    final Kind kind;
    String title = "";
    String author = "";
    private String coverEntry; // EPUB: zip entry. FB2: binary id.
    final List<String> spine = new ArrayList<>();
    private List<String> cbzNames;
    private String navEntry, ncxEntry; // EPUB table of contents documents

    private Book(File f, Kind k) {
        file = f;
        kind = k;
    }

    /** PDF and comics are read page by page; the rest by chapter. */
    boolean paged() {
        return kind == Kind.PDF || kind == Kind.CBZ;
    }

    static Kind kindOf(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".epub")) return Kind.EPUB;
        if (n.endsWith(".pdf")) return Kind.PDF;
        if (n.endsWith(".txt")) return Kind.TXT;
        if (n.endsWith(".fb2")) return Kind.FB2;
        if (n.endsWith(".cbz")) return Kind.CBZ;
        if (n.endsWith(".html") || n.endsWith(".htm")) return Kind.HTML;
        return null;
    }

    static boolean supported(String name) {
        return kindOf(name) != null;
    }

    static String id(File f) {
        return Integer.toHexString((f.getAbsolutePath() + f.lastModified()).hashCode());
    }

    static Book open(File f) throws Exception {
        Kind k = kindOf(f.getName());
        if (k == null) throw new IOException("formato nao suportado");
        Book b = new Book(f, k);
        if (k == Kind.EPUB) {
            try (ZipFile z = new ZipFile(f)) {
                b.parseEpub(z, rootfile(z));
            }
            if (b.spine.isEmpty()) throw new IOException("epub sem capitulos");
        } else if (k == Kind.FB2) {
            b.readFb2Meta();
        }
        if (b.title.isEmpty()) {
            b.title = f.getName().replaceAll("(?i)\\.(epub|pdf|txt|fb2|cbz|html?)$", "");
        }
        return b;
    }

    // ---------------------------------------------------------------- EPUB

    private static String rootfile(ZipFile z) throws Exception {
        try (InputStream in = z.getInputStream(z.getEntry("META-INF/container.xml"))) {
            XmlPullParser p = Xml.newPullParser();
            p.setInput(in, null);
            for (int t = p.getEventType(); t != XmlPullParser.END_DOCUMENT; t = p.next()) {
                if (t == XmlPullParser.START_TAG && "rootfile".equals(p.getName())) {
                    return p.getAttributeValue(null, "full-path");
                }
            }
        }
        throw new IOException("sem rootfile");
    }

    private void parseEpub(ZipFile z, String opf) throws Exception {
        String dir = opf.contains("/") ? opf.substring(0, opf.lastIndexOf('/') + 1) : "";
        Map<String, String> href = new HashMap<>();
        List<String> ids = new ArrayList<>();
        String coverId = null, coverHref = null;
        try (InputStream in = z.getInputStream(z.getEntry(opf))) {
            XmlPullParser p = Xml.newPullParser();
            p.setInput(in, null);
            for (int t = p.getEventType(); t != XmlPullParser.END_DOCUMENT; t = p.next()) {
                if (t != XmlPullParser.START_TAG) continue;
                String n = p.getName();
                n = n.substring(n.indexOf(':') + 1);
                switch (n) {
                    case "title":
                        if (title.isEmpty()) title = p.nextText().trim();
                        break;
                    case "creator":
                        if (author.isEmpty()) author = p.nextText().trim();
                        break;
                    case "meta":
                        if ("cover".equals(p.getAttributeValue(null, "name"))) {
                            coverId = p.getAttributeValue(null, "content");
                        }
                        break;
                    case "item": {
                        String id = p.getAttributeValue(null, "id");
                        String h = p.getAttributeValue(null, "href");
                        String props = p.getAttributeValue(null, "properties");
                        if (id != null && h != null) href.put(id, h);
                        if (props != null && props.contains("cover-image")) coverHref = h;
                        if (h != null && props != null && java.util.Arrays.asList(props.split("\\s+")).contains("nav")) navEntry = resolve(dir, h);
                        if (h != null && "application/x-dtbncx+xml".equals(p.getAttributeValue(null, "media-type"))) ncxEntry = resolve(dir, h);
                        break;
                    }
                    case "itemref":
                        ids.add(p.getAttributeValue(null, "idref"));
                        break;
                }
            }
        }
        for (String i : ids) {
            String h = href.get(i);
            if (h != null) spine.add(resolve(dir, h));
        }
        if (coverHref == null && coverId != null) coverHref = href.get(coverId);
        if (coverHref != null) coverEntry = resolve(dir, coverHref);
    }

    /**
     * Chapter titles from the EPUB's own table of contents (EPUB 3 nav, else NCX), one per spine entry;
     * null where the book gives none. Read on demand, never during import.
     */
    String[] chapterTitles() {
        String[] out = new String[spine.size()];
        if (kind != Kind.EPUB) return out;
        Map<String, String> byPath = new HashMap<>();
        try (ZipFile z = new ZipFile(file)) {
            if (navEntry != null && z.getEntry(navEntry) != null) readNav(z, byPath);
            if (byPath.isEmpty() && ncxEntry != null && z.getEntry(ncxEntry) != null) readNcx(z, byPath);
        } catch (Exception ignored) {
        }
        for (int i = 0; i < out.length; i++) out[i] = byPath.get(spine.get(i));
        return out;
    }

    private static String dirOf(String path) {
        return path.contains("/") ? path.substring(0, path.lastIndexOf('/') + 1) : "";
    }

    private static void addTitle(Map<String, String> m, String path, String title) {
        title = title == null ? "" : title.replaceAll("\\s+", " ").trim();
        if (!title.isEmpty() && !m.containsKey(path)) m.put(path, title);
    }

    private void readNav(ZipFile z, Map<String, String> m) throws Exception {
        String dir = dirOf(navEntry);
        try (InputStream in = z.getInputStream(z.getEntry(navEntry))) {
            XmlPullParser p = Xml.newPullParser();
            p.setInput(in, null);
            boolean inToc = false;
            String href = null;
            StringBuilder text = new StringBuilder();
            int aDepth = 0;
            for (int t = p.getEventType(); t != XmlPullParser.END_DOCUMENT; t = p.next()) {
                if (t == XmlPullParser.START_TAG) {
                    String n = p.getName();
                    if (n.equals("nav")) {
                        inToc = false;
                        for (int i = 0; i < p.getAttributeCount(); i++) {
                            if (p.getAttributeName(i).endsWith("type") && p.getAttributeValue(i).contains("toc")) inToc = true;
                        }
                    } else if (n.equals("a") && inToc) {
                        href = p.getAttributeValue(null, "href");
                        text.setLength(0);
                        aDepth = 1;
                    } else if (aDepth > 0) {
                        aDepth++;
                    }
                } else if (t == XmlPullParser.TEXT && aDepth > 0) {
                    text.append(p.getText());
                } else if (t == XmlPullParser.END_TAG) {
                    if (aDepth > 0 && --aDepth == 0 && href != null) addTitle(m, resolve(dir, href), text.toString());
                    else if (p.getName().equals("nav")) inToc = false;
                }
            }
        }
    }

    private void readNcx(ZipFile z, Map<String, String> m) throws Exception {
        String dir = dirOf(ncxEntry);
        try (InputStream in = z.getInputStream(z.getEntry(ncxEntry))) {
            XmlPullParser p = Xml.newPullParser();
            p.setInput(in, null);
            String label = null;
            boolean inLabel = false;
            for (int t = p.getEventType(); t != XmlPullParser.END_DOCUMENT; t = p.next()) {
                if (t != XmlPullParser.START_TAG) {
                    if (t == XmlPullParser.END_TAG && p.getName().equals("navLabel")) inLabel = false;
                    continue;
                }
                String n = p.getName();
                if (n.equals("navLabel")) inLabel = true;
                else if (n.equals("text") && inLabel) label = p.nextText();
                else if (n.equals("content")) {
                    String src = p.getAttributeValue(null, "src");
                    if (src != null) addTitle(m, resolve(dir, src), label);
                    label = null;
                }
            }
        }
    }

    static String resolve(String dir, String href) {
        int hash = href.indexOf('#');
        if (hash >= 0) href = href.substring(0, hash);
        Deque<String> out = new ArrayDeque<>();
        for (String s : (dir + Uri.decode(href)).split("/")) {
            if (s.equals("..")) {
                if (!out.isEmpty()) out.removeLast();
            } else if (!s.isEmpty() && !s.equals(".")) {
                out.addLast(s);
            }
        }
        return TextUtils.join("/", out);
    }

    private void extractEpub(File dest) throws IOException {
        String root = dest.getCanonicalPath() + File.separator;
        try (ZipFile z = new ZipFile(file)) {
            for (Enumeration<? extends ZipEntry> en = z.entries(); en.hasMoreElements(); ) {
                ZipEntry e = en.nextElement();
                File out = new File(dest, e.getName());
                if (!out.getCanonicalPath().startsWith(root)) continue;
                if (e.isDirectory()) {
                    out.mkdirs();
                    continue;
                }
                out.getParentFile().mkdirs();
                try (InputStream in = z.getInputStream(e)) {
                    copy(in, out);
                }
            }
        }
    }

    // ---------------------------------------------------------------- FB2

    private static String hrefOf(XmlPullParser p) {
        for (int i = 0; i < p.getAttributeCount(); i++) {
            if (p.getAttributeName(i).endsWith("href")) {
                String v = p.getAttributeValue(i);
                return v.startsWith("#") ? v.substring(1) : v;
            }
        }
        return null;
    }

    private void readFb2Meta() throws Exception {
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            XmlPullParser p = Xml.newPullParser();
            p.setInput(in, null);
            String first = "", last = "";
            boolean info = false, au = false;
            for (int t = p.getEventType(); t != XmlPullParser.END_DOCUMENT; t = p.next()) {
                if (t == XmlPullParser.START_TAG) {
                    String n = p.getName();
                    if (n.equals("body")) break;
                    switch (n) {
                        case "title-info":
                            info = true;
                            break;
                        case "author":
                            if (info && author.isEmpty()) au = true;
                            break;
                        case "first-name":
                            if (au) first = p.nextText().trim();
                            break;
                        case "last-name":
                            if (au) last = p.nextText().trim();
                            break;
                        case "book-title":
                            if (info) title = p.nextText().trim();
                            break;
                        case "image":
                            if (info && coverEntry == null) coverEntry = hrefOf(p);
                            break;
                    }
                } else if (t == XmlPullParser.END_TAG) {
                    String n = p.getName();
                    if (n.equals("title-info")) info = false;
                    if (n.equals("author") && au) {
                        au = false;
                        author = (first + " " + last).trim();
                    }
                }
            }
        }
    }

    private Bitmap fb2Cover(int maxW) throws Exception {
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            XmlPullParser p = Xml.newPullParser();
            p.setInput(in, null);
            for (int t = p.getEventType(); t != XmlPullParser.END_DOCUMENT; t = p.next()) {
                if (t == XmlPullParser.START_TAG && "binary".equals(p.getName())
                        && coverEntry.equals(p.getAttributeValue(null, "id"))) {
                    return decodeBytes(Base64.decode(p.nextText(), Base64.DEFAULT), maxW);
                }
            }
        }
        return null;
    }

    private void prepareFb2(File dest) throws Exception {
        Chapters w = new Chapters(dest, 24000);
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            XmlPullParser p = Xml.newPullParser();
            p.setInput(in, null);
            int depth = 0;
            boolean inBody = false, skip = false, inTitle = false;
            StringBuilder para = null;
            String block = "p";
            for (int t = p.getEventType(); t != XmlPullParser.END_DOCUMENT; t = p.next()) {
                if (t == XmlPullParser.START_TAG) {
                    String n = p.getName();
                    if (n.equals("binary")) break;
                    switch (n) {
                        case "body":
                            inBody = true;
                            skip = "notes".equals(p.getAttributeValue(null, "name"));
                            break;
                        case "section":
                            if (inBody && !skip) {
                                if (depth == 0) w.flush();
                                depth++;
                            }
                            break;
                        case "title":
                            inTitle = true;
                            para = new StringBuilder();
                            block = "h2";
                            break;
                        case "subtitle":
                            para = new StringBuilder();
                            block = "h3";
                            break;
                        case "text-author":
                            para = new StringBuilder("<em>");
                            block = "p";
                            break;
                        case "p":
                        case "v":
                            if (inTitle) {
                                if (para != null && para.length() > 0) para.append("<br>");
                            } else {
                                para = new StringBuilder();
                                block = "p";
                            }
                            break;
                        case "empty-line":
                            if (inBody && !skip) w.add("<p>&nbsp;</p>");
                            break;
                        case "emphasis":
                            if (para != null) para.append("<em>");
                            break;
                        case "strong":
                            if (para != null) para.append("<strong>");
                            break;
                    }
                } else if (t == XmlPullParser.TEXT) {
                    if (para != null && inBody && !skip) para.append(esc(p.getText()));
                } else if (t == XmlPullParser.END_TAG) {
                    String n = p.getName();
                    switch (n) {
                        case "body":
                            inBody = false;
                            skip = false;
                            break;
                        case "section":
                            if (inBody && !skip) {
                                depth--;
                                if (depth == 0) w.flush();
                            }
                            break;
                        case "title":
                            inTitle = false;
                            if (para != null && !skip) w.add("<h2>" + para + "</h2>");
                            para = null;
                            break;
                        case "subtitle":
                            if (para != null && !skip) w.add("<h3>" + para + "</h3>");
                            para = null;
                            break;
                        case "text-author":
                            if (para != null && !skip) w.add("<p>" + para + "</em></p>");
                            para = null;
                            break;
                        case "p":
                        case "v":
                            if (!inTitle && para != null) {
                                if (!skip) w.add("<" + block + ">" + para + "</" + block + ">");
                                para = null;
                            }
                            break;
                        case "emphasis":
                            if (para != null) para.append("</em>");
                            break;
                        case "strong":
                            if (para != null) para.append("</strong>");
                            break;
                    }
                }
            }
        }
        w.flush();
        if (spine.isEmpty()) throw new IOException("fb2 sem texto");
    }

    // ---------------------------------------------------------------- TXT / HTML

    private static String decodeText(byte[] raw) {
        try {
            String s = java.nio.charset.Charset.forName("UTF-8").newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(raw)).toString();
            return s.startsWith("﻿") ? s.substring(1) : s;
        } catch (CharacterCodingException e) {
            return new String(raw, java.nio.charset.Charset.forName("windows-1252"));
        }
    }

    private void prepareText(File dest) throws IOException {
        if (file.length() > MAX_TEXT_BYTES) throw new IOException("txt grande demais");
        byte[] raw;
        try (InputStream in = new FileInputStream(file)) {
            raw = readAll(in);
        }
        String[] lines = decodeText(raw).replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        int blank = 0, text = 0;
        for (String l : lines) {
            if (l.trim().isEmpty()) blank++;
            else text++;
        }
        boolean blocks = blank * 20 > text; // blank lines separate paragraphs
        Chapters w = new Chapters(dest, 12000);
        StringBuilder cur = new StringBuilder();
        for (String l : lines) {
            String s = l.trim();
            if (blocks) {
                if (s.isEmpty()) {
                    if (cur.length() > 0) w.add("<p>" + esc(cur.toString()) + "</p>");
                    cur.setLength(0);
                } else {
                    if (cur.length() > 0) cur.append(' ');
                    cur.append(s);
                }
            } else if (!s.isEmpty()) {
                w.add("<p>" + esc(s) + "</p>");
            }
        }
        if (cur.length() > 0) w.add("<p>" + esc(cur.toString()) + "</p>");
        w.flush();
        if (spine.isEmpty()) throw new IOException("txt vazio");
    }

    private void prepareHtml(File dest) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            copy(in, new File(dest, "ch000.html"));
        }
        spine.add("ch000.html");
    }

    // ---------------------------------------------------------------- CBZ

    private static boolean isImage(String n) {
        n = n.toLowerCase(Locale.ROOT);
        return !n.contains("__macosx") && (n.endsWith(".jpg") || n.endsWith(".jpeg")
                || n.endsWith(".png") || n.endsWith(".gif") || n.endsWith(".webp"));
    }

    private static String padNumbers(String s) {
        Matcher m = DIGITS.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String d = m.group();
            StringBuilder z = new StringBuilder();
            for (int i = d.length(); i < 10; i++) z.append('0');
            m.appendReplacement(sb, Matcher.quoteReplacement(z + d));
        }
        m.appendTail(sb);
        return sb.toString().toLowerCase(Locale.ROOT);
    }

    private List<String> cbzPages() throws IOException {
        List<String> names = new ArrayList<>();
        try (ZipFile z = new ZipFile(file)) {
            for (Enumeration<? extends ZipEntry> en = z.entries(); en.hasMoreElements(); ) {
                ZipEntry e = en.nextElement();
                if (!e.isDirectory() && isImage(e.getName())) names.add(e.getName());
            }
        }
        Collections.sort(names, (a, b) -> padNumbers(a).compareTo(padNumbers(b)));
        return names;
    }

    private void prepareCbz(File dest) throws IOException {
        List<String> names = cbzPages();
        if (names.isEmpty()) throw new IOException("cbz sem imagens");
        writeFile(new File(dest, "pages.txt"), TextUtils.join("\n", names));
        for (int i = 0; i < names.size(); i++) spine.add(String.format(Locale.ROOT, "p%04d.html", i));
    }

    /** Comics are extracted one page at a time so a big CBZ never fills the internal storage. */
    void ensurePage(File dest, int i) throws IOException {
        if (kind != Kind.CBZ) return;
        if (cbzNames == null) cbzNames = readLines(new File(dest, "pages.txt"));
        String name = cbzNames.get(i);
        String ext = name.substring(name.lastIndexOf('.'));
        File img = new File(dest, "img/" + i + ext);
        File[] old = img.getParentFile().listFiles();
        if (old != null) for (File o : old) if (!o.equals(img)) o.delete();
        if (!img.exists()) {
            img.getParentFile().mkdirs();
            try (ZipFile z = new ZipFile(file); InputStream in = z.getInputStream(z.getEntry(name))) {
                copy(in, img);
            }
        }
        writeFile(new File(dest, String.format(Locale.ROOT, "p%04d.html", i)),
                HEAD + "<img src=\"img/" + i + ext + "\">" + TAIL);
    }

    // ---------------------------------------------------------------- covers

    Bitmap cover(int maxW) {
        try {
            switch (kind) {
                case EPUB:
                    return coverEntry == null ? null : zipBitmap(coverEntry, maxW);
                case CBZ: {
                    List<String> n = cbzPages();
                    return n.isEmpty() ? null : zipBitmap(n.get(0), maxW);
                }
                case FB2:
                    return coverEntry == null ? null : fb2Cover(maxW);
                case PDF:
                    return pdfCover(maxW);
                default:
                    return null;
            }
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }

    private Bitmap zipBitmap(String entry, int maxW) throws IOException {
        try (ZipFile z = new ZipFile(file)) {
            ZipEntry e = z.getEntry(entry);
            if (e == null) return null;
            try (InputStream in = z.getInputStream(e)) {
                return decodeBytes(readAll(in), maxW);
            }
        }
    }

    private static Bitmap decodeBytes(byte[] raw, int maxW) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(raw, 0, raw.length, o);
        int s = 1;
        while (o.outWidth / (s * 2) >= maxW) s *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = s;
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeByteArray(raw, 0, raw.length, o);
    }

    private Bitmap pdfCover(int maxW) throws IOException {
        try (ParcelFileDescriptor fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
             PdfRenderer r = new PdfRenderer(fd)) {
            PdfRenderer.Page pg = r.openPage(0);
            Bitmap bm = Bitmap.createBitmap(maxW, Math.max(1, maxW * pg.getHeight() / pg.getWidth()),
                    Bitmap.Config.ARGB_8888);
            bm.eraseColor(Color.WHITE);
            pg.render(bm, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            pg.close();
            return bm;
        }
    }

    // ---------------------------------------------------------------- reader prep

    /** Builds the readable files for the web reader. PDF never goes through here. */
    void prepare(File dest) throws Exception {
        File done = new File(dest, ".done");
        if (done.exists()) {
            if (kind != Kind.EPUB) {
                spine.clear();
                spine.addAll(readLines(new File(dest, "spine.txt")));
            }
            return;
        }
        deleteTree(dest);
        dest.mkdirs();
        switch (kind) {
            case EPUB:
                extractEpub(dest);
                break;
            case TXT:
                prepareText(dest);
                break;
            case FB2:
                prepareFb2(dest);
                break;
            case CBZ:
                prepareCbz(dest);
                break;
            case HTML:
                prepareHtml(dest);
                break;
            default:
                throw new IOException("formato nao suportado aqui");
        }
        if (kind != Kind.EPUB) writeFile(new File(dest, "spine.txt"), TextUtils.join("\n", spine));
        done.createNewFile();
    }

    /** Groups paragraphs into chapter files of roughly {@code limit} characters. */
    private final class Chapters {
        private final File dest;
        private final int limit;
        private final StringBuilder cur = new StringBuilder();
        private int n;

        Chapters(File dest, int limit) {
            this.dest = dest;
            this.limit = limit;
        }

        void add(String html) throws IOException {
            cur.append(html);
            if (cur.length() > limit) flush();
        }

        void flush() throws IOException {
            if (cur.length() == 0) return;
            String name = String.format(Locale.ROOT, "ch%03d.html", n++);
            writeFile(new File(dest, name), HEAD + cur + TAIL);
            spine.add(name);
            cur.setLength(0);
        }
    }

    // ---------------------------------------------------------------- helpers

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] b = new byte[16384];
        int n;
        while ((n = in.read(b)) > 0) out.write(b, 0, n);
        return out.toByteArray();
    }

    private static void copy(InputStream in, File to) throws IOException {
        try (OutputStream os = new FileOutputStream(to)) {
            byte[] b = new byte[16384];
            int n;
            while ((n = in.read(b)) > 0) os.write(b, 0, n);
        }
    }

    private static void writeFile(File f, String text) throws IOException {
        try (OutputStream os = new FileOutputStream(f)) {
            os.write(text.getBytes("UTF-8"));
        }
    }

    private static List<String> readLines(File f) throws IOException {
        List<String> out = new ArrayList<>();
        try (InputStream in = new FileInputStream(f)) {
            for (String l : new String(readAll(in), "UTF-8").split("\n")) if (!l.isEmpty()) out.add(l);
        }
        return out;
    }

    static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }
}
