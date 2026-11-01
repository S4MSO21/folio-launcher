package com.folio.launcher;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.HashMap;
import java.util.Map;

/** Library database: books, reading progress, collections and favorites. Plain SQLite, no ORM. */
final class Db extends SQLiteOpenHelper {
    private static Db inst;

    static synchronized Db get(Context c) {
        if (inst == null) inst = new Db(c.getApplicationContext());
        return inst;
    }

    private final Context ctx;

    private Db(Context c) {
        super(c, "folio.db", null, 4);
        ctx = c;
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE books(id TEXT PRIMARY KEY, path TEXT NOT NULL, title TEXT, author TEXT,"
                + " format TEXT, size INTEGER, hash TEXT, added_at INTEGER, last_opened INTEGER DEFAULT 0,"
                + " favorite INTEGER DEFAULT 0, imported INTEGER DEFAULT 0, finished INTEGER DEFAULT 0)");
        db.execSQL("CREATE INDEX books_hash ON books(hash)");
        db.execSQL("CREATE TABLE progress(book_id TEXT PRIMARY KEY, chapter INTEGER DEFAULT 0,"
                + " scroll_y INTEGER DEFAULT 0, updated_at INTEGER, frac REAL DEFAULT -1)");
        db.execSQL("CREATE TABLE collections(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT UNIQUE NOT NULL)");
        db.execSQL("CREATE TABLE collection_books(collection_id INTEGER NOT NULL, book_id TEXT NOT NULL,"
                + " PRIMARY KEY(collection_id, book_id))");
        sessions(db);
        bookmarks(db);
        migrateFromPrefs(db);
    }

    private static void bookmarks(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS bookmarks(id INTEGER PRIMARY KEY AUTOINCREMENT, book_id TEXT NOT NULL,"
                + " chapter INTEGER NOT NULL, scroll_y INTEGER NOT NULL, label TEXT, created_at INTEGER NOT NULL)");
    }

    private static void sessions(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS sessions(id INTEGER PRIMARY KEY AUTOINCREMENT, book_id TEXT NOT NULL,"
                + " started_at INTEGER NOT NULL, seconds INTEGER NOT NULL)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int from, int to) {
        if (from < 2) sessions(db);
        if (from < 4) db.execSQL("ALTER TABLE progress ADD COLUMN frac REAL DEFAULT -1");
        if (from < 3) {
            db.execSQL("ALTER TABLE books ADD COLUMN finished INTEGER DEFAULT 0");
            bookmarks(db);
        }
    }

    /** Copies old c_<id>/y_<id> progress from SharedPreferences. The prefs are left untouched. */
    private void migrateFromPrefs(SQLiteDatabase db) {
        SharedPreferences p = ctx.getSharedPreferences("folio", Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        for (Map.Entry<String, ?> e : p.getAll().entrySet()) {
            String k = e.getKey();
            if (!k.startsWith("c_") || !(e.getValue() instanceof Integer)) continue;
            String id = k.substring(2);
            ContentValues v = new ContentValues();
            v.put("book_id", id);
            v.put("chapter", (Integer) e.getValue());
            v.put("scroll_y", p.getInt("y_" + id, 0));
            v.put("updated_at", now);
            db.insertWithOnConflict("progress", null, v, SQLiteDatabase.CONFLICT_IGNORE);
        }
    }

    void saveProgress(String id, int chapter, int y) {
        ContentValues v = new ContentValues();
        v.put("book_id", id);
        v.put("chapter", chapter);
        v.put("scroll_y", y);
        v.put("updated_at", System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict("progress", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    /** Saves the reading position as chapter plus a 0..1 fraction of the chapter, which survives font changes. */
    void saveFraction(String id, int chapter, int y, float frac) {
        ContentValues v = new ContentValues();
        v.put("book_id", id);
        v.put("chapter", chapter);
        v.put("scroll_y", y);
        v.put("frac", frac);
        v.put("updated_at", System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict("progress", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    /** Saved fraction of the chapter, or -1 when only a pixel offset exists. */
    float fraction(String id) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT frac FROM progress WHERE book_id=?", new String[]{id})) {
            return c.moveToFirst() && !c.isNull(0) ? c.getFloat(0) : -1f;
        }
    }

    /** {chapter, scrollY}, or null when the book was never opened. */
    int[] progress(String id) {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT chapter, scroll_y FROM progress WHERE book_id=?", new String[]{id})) {
            return c.moveToFirst() ? new int[]{c.getInt(0), c.getInt(1)} : null;
        }
    }

    Map<String, Integer> chapters() {
        Map<String, Integer> m = new HashMap<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT book_id, chapter FROM progress", null)) {
            while (c.moveToNext()) m.put(c.getString(0), c.getInt(1));
        }
        return m;
    }

    void upsert(Book b, String id, boolean imported, String hash) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put("path", b.file.getAbsolutePath());
        v.put("title", b.title);
        v.put("author", b.author);
        v.put("format", b.kind.name());
        v.put("size", b.file.length());
        if (imported) v.put("imported", 1);
        if (hash != null) v.put("hash", hash);
        if (db.update("books", v, "id=?", new String[]{id}) == 0) {
            v.put("id", id);
            v.put("added_at", System.currentTimeMillis());
            db.insert("books", null, v);
        }
    }

    void opened(String id) {
        ContentValues v = new ContentValues();
        v.put("last_opened", System.currentTimeMillis());
        getWritableDatabase().update("books", v, "id=?", new String[]{id});
    }

    /** True when an imported book with this content hash is already in the library and its file still exists. */
    boolean hasHash(String hash) {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT path FROM books WHERE hash=?", new String[]{hash})) {
            while (c.moveToNext()) if (new java.io.File(c.getString(0)).exists()) return true;
        }
        return false;
    }

    // ---- favorites ----
    java.util.Set<String> favorites() {
        java.util.Set<String> s = new java.util.HashSet<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT id FROM books WHERE favorite=1", null)) {
            while (c.moveToNext()) s.add(c.getString(0));
        }
        return s;
    }

    void setFavorite(String id, boolean on) {
        ContentValues v = new ContentValues();
        v.put("favorite", on ? 1 : 0);
        getWritableDatabase().update("books", v, "id=?", new String[]{id});
    }

    // ---- collections ----
    /** Rows of {id, name}. */
    java.util.List<String[]> collections() {
        java.util.List<String[]> l = new java.util.ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT id, name FROM collections ORDER BY name COLLATE NOCASE", null)) {
            while (c.moveToNext()) l.add(new String[]{c.getString(0), c.getString(1)});
        }
        return l;
    }

    boolean createCollection(String name) {
        ContentValues v = new ContentValues();
        v.put("name", name);
        return getWritableDatabase().insert("collections", null, v) != -1;
    }

    void deleteCollection(String cid) {
        SQLiteDatabase db = getWritableDatabase();
        db.delete("collection_books", "collection_id=?", new String[]{cid});
        db.delete("collections", "id=?", new String[]{cid});
    }

    java.util.Set<String> members(String cid) {
        java.util.Set<String> s = new java.util.HashSet<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT book_id FROM collection_books WHERE collection_id=?", new String[]{cid})) {
            while (c.moveToNext()) s.add(c.getString(0));
        }
        return s;
    }

    /** Adds the book to the collection, or removes it if already there. Returns true when now inside. */
    boolean toggleMember(String cid, String bookId) {
        SQLiteDatabase db = getWritableDatabase();
        if (members(cid).contains(bookId)) {
            db.delete("collection_books", "collection_id=? AND book_id=?", new String[]{cid, bookId});
            return false;
        }
        ContentValues v = new ContentValues();
        v.put("collection_id", cid);
        v.put("book_id", bookId);
        db.insert("collection_books", null, v);
        return true;
    }

    // ---- reading sessions and stats ----
    void addSession(String bookId, long startedAt, int seconds) {
        ContentValues v = new ContentValues();
        v.put("book_id", bookId);
        v.put("started_at", startedAt);
        v.put("seconds", seconds);
        getWritableDatabase().insert("sessions", null, v);
    }

    long secondsSince(long t) {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT COALESCE(SUM(seconds),0) FROM sessions WHERE started_at>=?", new String[]{String.valueOf(t)})) {
            return c.moveToFirst() ? c.getLong(0) : 0;
        }
    }

    int count(String table) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM " + table, null)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    /** Distinct local days (as day numbers) that have at least one session, newest first. */
    java.util.List<Long> readingDays() {
        long off = java.util.TimeZone.getDefault().getRawOffset();
        java.util.List<Long> l = new java.util.ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT DISTINCT (started_at+" + off
                + ")/86400000 d FROM sessions ORDER BY d DESC", null)) {
            while (c.moveToNext()) l.add(c.getLong(0));
        }
        return l;
    }

    /** Rows of {title, startedAt, seconds}, newest first. */
    java.util.List<String[]> recentSessions(int n) {
        java.util.List<String[]> l = new java.util.ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COALESCE(b.title,'?'), s.started_at, s.seconds FROM sessions s"
                + " LEFT JOIN books b ON b.id=s.book_id ORDER BY s.started_at DESC LIMIT " + n, null)) {
            while (c.moveToNext()) l.add(new String[]{c.getString(0), c.getString(1), c.getString(2)});
        }
        return l;
    }

    /** Most recently opened book that has progress, or null. */
    String lastReading() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT b.id FROM books b JOIN progress p ON p.book_id=b.id"
                + " WHERE b.last_opened>0 ORDER BY b.last_opened DESC LIMIT 1", null)) {
            return c.moveToFirst() ? c.getString(0) : null;
        }
    }

    /** Title and author of the book opened most recently, or null when nothing was read yet. */
    String[] lastBook() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT title, author FROM books WHERE last_opened>0"
                + " ORDER BY last_opened DESC LIMIT 1", null)) {
            return c.moveToFirst() ? new String[]{c.getString(0), c.getString(1)} : null;
        }
    }

    /** Drops rows for books whose file is gone (progress and sessions are kept in case the file returns). */
    void prune(java.util.Set<String> present) {
        SQLiteDatabase db = getWritableDatabase();
        java.util.List<String> gone = new java.util.ArrayList<>();
        try (Cursor c = db.rawQuery("SELECT id FROM books", null)) {
            while (c.moveToNext()) if (!present.contains(c.getString(0))) gone.add(c.getString(0));
        }
        for (String id : gone) {
            db.delete("books", "id=?", new String[]{id});
            db.delete("collection_books", "book_id=?", new String[]{id});
        }
    }

    void markFinished(String id) {
        ContentValues v = new ContentValues();
        v.put("finished", 1);
        getWritableDatabase().update("books", v, "id=?", new String[]{id});
    }

    int finishedCount() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM books WHERE finished=1", null)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    /** Ids of the most recently opened books, newest first. */
    java.util.List<String> recent(int n) {
        java.util.List<String> l = new java.util.ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT id FROM books WHERE last_opened>0 ORDER BY last_opened DESC LIMIT " + n, null)) {
            while (c.moveToNext()) l.add(c.getString(0));
        }
        return l;
    }

    long secondsBetween(long from, long to) {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT COALESCE(SUM(seconds),0) FROM sessions WHERE started_at>=? AND started_at<?",
                new String[]{String.valueOf(from), String.valueOf(to)})) {
            return c.moveToFirst() ? c.getLong(0) : 0;
        }
    }

    // ---- bookmarks ----
    void addBookmark(String bookId, int chapter, int y, String label) {
        ContentValues v = new ContentValues();
        v.put("book_id", bookId);
        v.put("chapter", chapter);
        v.put("scroll_y", y);
        v.put("label", label);
        v.put("created_at", System.currentTimeMillis());
        getWritableDatabase().insert("bookmarks", null, v);
    }

    /** Rows of {id, chapter, scrollY, label}, newest first. */
    java.util.List<String[]> bookmarks(String bookId) {
        java.util.List<String[]> l = new java.util.ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT id, chapter, scroll_y, label FROM bookmarks"
                + " WHERE book_id=? ORDER BY created_at DESC", new String[]{bookId})) {
            while (c.moveToNext()) l.add(new String[]{c.getString(0), c.getString(1), c.getString(2), c.getString(3)});
        }
        return l;
    }

    void deleteBookmark(String id) {
        getWritableDatabase().delete("bookmarks", "id=?", new String[]{id});
    }
}
