package com.cruisetune.dbaudit;

import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.database.Cursor;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.lang.reflect.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Same-signature instrumentation. Production tables are read only; writes use a private clone.
 * No player classes are compiled into this APK: reflection invokes the installed release DEX.
 */
public final class AuditInstrumentation extends Instrumentation {
    private static final String DB_NAME = "cruise-library.db";
    private final CountDownLatch appReady = new CountDownLatch(1);
    private Bundle arguments;
    private String stage = "starting";
    private Class<?> databaseClass, trackClass, sourceClass, entryClass, snapshotClass, kindClass;
    private Object codec;
    private Method decode, encode, scan, prune, saveQueue, savePosition, saveSource, restore;
    private final String prefix = "audit-" + UUID.randomUUID();
    private Set<String> realTrackIds;
    private final JSONArray cases = new JSONArray();
    private Object cloneHelper;
    private File scratch;

    @Override public void onCreate(Bundle args) { arguments = args; start(); }
    @Override public void callApplicationOnCreate(Application app) {
        // Keep the target quiescent: do not launch its asynchronous startup/cleanup jobs.
        // A normal application launch after finish() calls the original onCreate again.
        appReady.countDown();
    }
    @Override public void onStart() {
        JSONObject report = new JSONObject();
        Object realHelper = null;
        String beforeDigest = null;
        try {
            check(appReady.await(10, TimeUnit.SECONDS), "application initialization timed out");
            Context target = getTargetContext();
            String expected = arguments.getString("expectedApkSha", "");
            check(expected.matches("[0-9a-f]{64}"), "expected APK hash is required");
            String actual = sha(new File(target.getApplicationInfo().sourceDir));
            check(expected.equals(actual), "installed APK differs from the specified release");
            report.put("installedApkSha256", actual).put("sdk", Build.VERSION.SDK_INT);
            report.put("installedVersion", target.getPackageManager().getPackageInfo(target.getPackageName(), 0).versionName);
            loadReleaseClasses(target.getClassLoader());
            mark("read production database");
            File realFile = target.getDatabasePath(DB_NAME);
            report.put("realFilesBeforeOpen", new JSONObject().put("dbBytes", realFile.length()).put("walBytes", new File(realFile.getPath()+"-wal").length()));
            realHelper = databaseClass.getConstructor(Context.class).newInstance(target);
            SQLiteDatabase real = db(realHelper);
            report.put("realBefore", metrics(real));
            beforeDigest = digest(real);
            realTrackIds = ids(real);
            // Only SQLite performs checkpointing; no live WAL or SHM file is deleted manually.
            try (Cursor c = real.rawQuery("PRAGMA wal_checkpoint(PASSIVE)", null)) {
                check(c.moveToFirst() && c.getInt(0) == 0 && c.getInt(1) == c.getInt(2), "production database is busy; no clone made");
            }
            close(realHelper); realHelper = null;
            scratch = new File(target.getCacheDir(), prefix);
            check(scratch.mkdirs(), "cannot create private audit directory");
            File clonedFile = new File(scratch, DB_NAME);
            Files.copy(target.getDatabasePath(DB_NAME).toPath(), clonedFile.toPath());
            cloneHelper = databaseClass.getConstructor(Context.class).newInstance(new CloneContext(target, scratch));
            check(db(cloneHelper).getPath().equals(clonedFile.getAbsolutePath()), "clone path isolation failed");
            check(!db(cloneHelper).getPath().equals(target.getDatabasePath(DB_NAME).getAbsolutePath()), "refusing production writes");
            report.put("cloneIsolationVerified", true);
            check(beforeDigest.equals(digest(db(cloneHelper))), "initial clone is not logically identical");
            fileAndReferenceCases();
            rollbackAndChurnCases();
            walReaderCase();
            report.put("cases", cases).put("cloneFinal", metrics(db(cloneHelper), true));
            close(cloneHelper); cloneHelper = null;
            report.put("passed", true);
        } catch (Throwable error) {
            while (error instanceof InvocationTargetException && error.getCause() != null) error = error.getCause();
            try {
                report.put("passed", false).put("failedStage", stage).put("errorClass", error.getClass().getName()).put("cases", cases);
                if (error instanceof AssertionError) report.put("assertion", error.getMessage());
            } catch (Exception ignored) { }
        } finally {
            close(cloneHelper); close(realHelper);
            if (beforeDigest != null) {
                Object verification = null;
                try {
                    mark("verify production preservation");
                    verification = databaseClass.getConstructor(Context.class).newInstance(getTargetContext());
                    boolean unchanged = beforeDigest.equals(digest(db(verification)));
                    report.put("realAfter", metrics(db(verification))).put("realDataUnchanged", unchanged);
                    if (!unchanged) report.put("passed", false).put("preservationError", "production data changed during the audit");
                } catch (Exception error) {
                    try { report.put("passed", false).put("preservationErrorClass", error.getClass().getName()); } catch (Exception ignored) { }
                } finally { close(verification); }
            }
            if (scratch != null) deleteTree(scratch);
            try { report.put("privateCloneRemoved", scratch == null || !scratch.exists()); } catch (Exception ignored) { }
            Bundle result = new Bundle(); result.putString("report", report.toString());
            finish(report.optBoolean("passed") ? -1 : 0, result);
        }
    }

    private void mark(String value) {
        stage = value; Bundle progress = new Bundle(); progress.putString("stage", value); sendStatus(1, progress);
    }
    private void loadReleaseClasses(ClassLoader loader) throws Exception {
        databaseClass = loader.loadClass("com.cruisetune.player.data.LibraryDatabase");
        trackClass = loader.loadClass("com.cruisetune.player.core.Track");
        sourceClass = loader.loadClass("com.cruisetune.player.core.MusicSource");
        kindClass = loader.loadClass("com.cruisetune.player.core.SourceKind");
        entryClass = loader.loadClass("com.cruisetune.player.core.QueueEntry");
        snapshotClass = loader.loadClass("com.cruisetune.player.core.PlaybackSnapshot");
        Class<?> codecClass = loader.loadClass("com.cruisetune.player.data.JsonCodec");
        codec = codecClass.getField("INSTANCE").get(null);
        decode = codecClass.getMethod("decode", String.class); encode = codecClass.getMethod("encode", trackClass);
        scan = databaseClass.getMethod("replaceScan", String.class, List.class, Set.class);
        prune = databaseClass.getMethod("pruneMissingTracks", Set.class);
        saveSource = databaseClass.getMethod("saveSource", sourceClass);
        saveQueue = databaseClass.getMethod("saveQueue", snapshotClass);
        savePosition = databaseClass.getMethod("savePosition", snapshotClass);
        restore = databaseClass.getMethod("restore");
    }
    private Object source(String id) throws Exception {
        Object kind = kindClass.getField("LOCAL").get(null);
        Object value = sourceClass.getConstructor(String.class, kindClass, String.class, String.class, String.class, boolean.class)
                .newInstance(id, kind, "Isolated audit", scratch.toURI().toString(), "", false);
        saveSource.invoke(cloneHelper, value); return value;
    }
    private Object track(String id, String source, String title, File file) throws Exception {
        JSONObject j = new JSONObject().put("id", id).put("sourceId", source).put("fileId", id).put("title", title)
                .put("path", title).put("size", file == null ? 0 : file.length()).put("version", "fixture-v1")
                .put("uri", file == null ? "" : file.toURI().toString()).put("mime", "audio/wav");
        return decode.invoke(codec, j.toString());
    }
    private Object snapshot(long revision, List<Object> tracks, long position) throws Exception {
        List<Object> entries = new ArrayList<>();
        for (int i = 0; i < tracks.size(); i++) entries.add(entryClass.getConstructor(trackClass, int.class).newInstance(tracks.get(i), i));
        return snapshotClass.getConstructor(long.class, List.class, int.class, long.class, boolean.class, int.class, boolean.class)
                .newInstance(revision, entries, 0, position, true, 0, false);
    }
    private void scanFiles(String id, File folder, Set<String> retained) throws Exception {
        List<Object> tracks = new ArrayList<>();
        File[] files = folder.listFiles(); check(files != null, "fixture directory unavailable");
        Arrays.sort(files);
        for (File file : files) if (file.getName().endsWith(".wav")) tracks.add(track(id + "/" + file.getName(), id, file.getName(), file));
        scan.invoke(cloneHelper, id, tracks, retained);
    }
    private void fileAndReferenceCases() throws Exception {
        mark("real fixture file deletion");
        SQLiteDatabase sql = db(cloneHelper);
        String id = prefix + "/files"; source(id);
        File music = new File(scratch, "music"); check(music.mkdir(), "fixture directory creation failed");
        File a = new File(music, "A.wav"), b = new File(music, "B.wav"), c = new File(music, "C.wav");
        wav(a); wav(b); wav(c);
        scanFiles(id, music, realTrackIds); long initial = sourceRows(sql, id);
        check(initial == 3, "three fixture files were not indexed");
        check(c.delete(), "fixture C deletion failed"); scanFiles(id, music, realTrackIds);
        long after = sourceRows(sql, id); check(after == 2, "unreferenced missing row was not physically deleted");
        cases.put(new JSONObject().put("name", "physical_file_deletion").put("filesBefore", 3).put("filesAfter", 2)
                .put("rowsBefore", initial).put("rowsAfter", after).put("deletedRows", initial - after));
        mark("queue and fallback references");
        Object ta = track(id + "/A.wav", id, "A.wav", a), tb = track(id + "/B.wav", id, "B.wav", b);
        long rev = scalar(sql, "SELECT COALESCE(MAX(revision),0)+1 FROM checkpoints");
        saveQueue.invoke(cloneHelper, snapshot(rev, List.of(ta), 10000));
        saveQueue.invoke(cloneHelper, snapshot(rev + 1, List.of(tb), 20000));
        check(a.delete() && b.delete(), "referenced fixture files were not deleted");
        scanFiles(id, music, realTrackIds); check(sourceRows(sql, id) == 2, "recovery metadata was lost");
        sql.execSQL("UPDATE queue_items SET payload='damaged' WHERE revision=?", new Object[]{rev + 1});
        Object fallback = restore.invoke(cloneHelper);
        check(((Long)snapshotClass.getMethod("getRevision").invoke(fallback)) == rev, "fallback revision failed");
        sql.execSQL("UPDATE queue_items SET payload=? WHERE revision=?", new Object[]{encode.invoke(codec, tb), rev + 1});
        savePosition.invoke(cloneHelper, snapshot(rev + 1, List.of(tb), 25000));
        prune.invoke(cloneHelper, realTrackIds);
        check(sourceRows(sql, id) == 1 && scalar(sql, "SELECT COUNT(*) FROM queue_items") == 1, "displaced queue was retained");
        cases.put(new JSONObject().put("name", "queue_reference_cleanup").put("rowsWhileBothReferenced", 2)
                .put("rowsAfterBackupMoved", sourceRows(sql, id)).put("fallbackRestored", true));
        mark("repeated position saves");
        long peak = 0;
        for (int i = 0; i < 600; i++) {
            savePosition.invoke(cloneHelper, snapshot(rev + 1, List.of(tb), i * 2000L));
            peak = Math.max(peak, new File(sql.getPath() + "-wal").length());
        }
        check(scalar(sql, "SELECT COUNT(*) FROM checkpoints") == 2, "checkpoint rows accumulated");
        check(scalar(sql, "SELECT COUNT(*) FROM queue_items") == 1, "queue rows accumulated");
        cases.put(new JSONObject().put("name", "accelerated_position_saves").put("writes", 600)
                .put("checkpointRows", 2).put("queueRows", 1).put("peakWalBytes", peak));
        mark("external retained references");
        String external = prefix + "/retained"; source(external);
        String keep = external + "/keep", discard = external + "/discard";
        scan.invoke(cloneHelper, external, List.of(track(keep, external, "keep", null), track(discard, external, "discard", null)), realTrackIds);
        Set<String> retained = new HashSet<>(realTrackIds); retained.add(keep);
        scan.invoke(cloneHelper, external, Collections.emptyList(), retained);
        check(sourceRows(sql, external) == 1, "external reference was not retained");
        prune.invoke(cloneHelper, realTrackIds); check(sourceRows(sql, external) == 0, "released external reference was not removed");
        cases.put(new JSONObject().put("name", "external_reference_lifetime").put("retainedRows", 1).put("rowsAfterRelease", 0));
    }
    private void rollbackAndChurnCases() throws Exception {
        mark("failed transaction rollback");
        SQLiteDatabase sql = db(cloneHelper); String id = prefix + "/rollback"; source(id);
        Object old = track(id + "/old", id, "old", null);
        scan.invoke(cloneHelper, id, List.of(old), realTrackIds);
        boolean failed = false;
        try { scan.invoke(cloneHelper, id, List.of(track(id + "/new", id, "new", null), track(id + "/wrong", "wrong-source", "wrong", null)), realTrackIds); }
        catch (InvocationTargetException expected) { failed = true; }
        check(failed && sourceRows(sql, id) == 1, "failed scan did not roll back");
        try (Cursor c = sql.rawQuery("SELECT id,present FROM tracks WHERE source_id=?", new String[]{id})) {
            check(c.moveToFirst() && c.getString(0).equals(id + "/old") && c.getInt(1) == 1, "old row was changed by failed scan");
        }
        cases.put(new JSONObject().put("name", "failed_scan_rollback").put("originalRowPreserved", true));
        mark("repeated scan churn"); id = prefix + "/churn"; source(id);
        JSONArray rows = new JSONArray();
        for (int round = 0; round < 20; round++) {
            List<Object> tracks = new ArrayList<>();
            for (int i = 0; i < 100; i++) tracks.add(track(id + "/" + round + "/" + i, id, "Generated sample " + i, null));
            scan.invoke(cloneHelper, id, tracks, realTrackIds);
            long n = sourceRows(sql, id); rows.put(n); check(n == 100, "stale scan rows accumulated");
        }
        cases.put(new JSONObject().put("name", "twenty_replacement_scans").put("rowsByRound", rows));
    }
    private void walReaderCase() throws Exception {
        mark("pinned reader and WAL recovery");
        SQLiteDatabase sql = db(cloneHelper); String id = prefix + "/large"; Object source = source(id);
        scan.invoke(cloneHelper, id, List.of(track(id + "/seed", id, "seed", null)), realTrackIds);
        SQLiteDatabase reader = SQLiteDatabase.openDatabase(sql.getPath(), null, SQLiteDatabase.OPEN_READONLY);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicReference<Throwable> readError = new AtomicReference<>();
        reader.setCustomScalarFunction("audit_hold", value -> {
            entered.countDown();
            try { if (!release.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("reader timeout"); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("reader interrupted"); }
            return value;
        });
        Thread read = new Thread(() -> {
            try (Cursor c = reader.rawQuery("SELECT audit_hold(id) FROM tracks WHERE source_id=? LIMIT 1", new String[]{id})) { c.moveToFirst(); }
            catch (Throwable error) { readError.set(error); }
        }, "audit-pinned-reader");
        long heldWal = 0, elapsed = 0;
        try {
            read.start(); check(entered.await(5, TimeUnit.SECONDS), "reader did not acquire its snapshot");
            List<Object> tracks = new ArrayList<>(); String payload = "x".repeat(2048);
            for (int i = 0; i < 2500; i++) tracks.add(track(id + "/" + i, id, payload, null));
            Field timer = databaseClass.getDeclaredField("nextCheckpointAt"); timer.setAccessible(true);
            ((AtomicLong)timer.get(cloneHelper)).set(0);
            long started = SystemClock.elapsedRealtime();
            scan.invoke(cloneHelper, id, tracks, realTrackIds);
            elapsed = SystemClock.elapsedRealtime() - started;
            check(read.isAlive() && readError.get() == null, "reader released before write completed");
            heldWal = new File(sql.getPath() + "-wal").length();
            check(heldWal > 1048576, "fixture did not exercise a WAL larger than the reuse target");
        } finally {
            release.countDown(); read.join(5000); reader.close();
        }
        check(readError.get() == null && !read.isAlive(), "reader did not close cleanly");
        saveSource.invoke(cloneHelper, source); saveSource.invoke(cloneHelper, source);
        long recovered = new File(sql.getPath() + "-wal").length();
        check(recovered <= 1048576, "WAL did not recycle after reader release and later commits");
        check(sourceRows(sql, id) == 2500, "large scan lost rows");
        scan.invoke(cloneHelper, id, Collections.emptyList(), realTrackIds);
        saveSource.invoke(cloneHelper, source);
        cases.put(new JSONObject().put("name", "pinned_reader_wal").put("insertedRows", 2500)
                .put("walBytesWhileReaderHeld", heldWal).put("writeElapsedMsWhileReaderHeld", elapsed)
                .put("walBytesAfterReaderRelease", recovered).put("rowsAfterDeletion", sourceRows(sql, id))
                .put("freePagesAfterDeletion", scalar(sql, "PRAGMA freelist_count")));
    }

    private static SQLiteDatabase db(Object helper) { return ((SQLiteOpenHelper)helper).getWritableDatabase(); }
    private static void close(Object helper) { if (helper != null) try { ((SQLiteOpenHelper)helper).close(); } catch (Exception ignored) { } }
    private static long scalar(SQLiteDatabase db, String query) { try (Cursor c = db.rawQuery(query, null)) { check(c.moveToFirst(), "missing scalar result"); return c.getLong(0); } }
    private static long sourceRows(SQLiteDatabase db, String id) { try (Cursor c = db.rawQuery("SELECT COUNT(*) FROM tracks WHERE source_id=?", new String[]{id})) { c.moveToFirst(); return c.getLong(0); } }
    private static Set<String> ids(SQLiteDatabase db) { Set<String> result = new HashSet<>(); try (Cursor c = db.rawQuery("SELECT id FROM tracks", null)) { while(c.moveToNext()) result.add(c.getString(0)); } return result; }
    private static JSONObject metrics(SQLiteDatabase db) throws Exception { return metrics(db, false); }
    private static JSONObject metrics(SQLiteDatabase db, boolean writerPolicy) throws Exception {
        JSONObject result = new JSONObject().put("tracks", scalar(db, "SELECT COUNT(*) FROM tracks"))
                .put("missingTracks", scalar(db, "SELECT COUNT(*) FROM tracks WHERE present=0"))
                .put("queueRows", scalar(db, "SELECT COUNT(*) FROM queue_items"))
                .put("queueRevisions", scalar(db, "SELECT COUNT(DISTINCT revision) FROM queue_items"))
                .put("checkpoints", scalar(db, "SELECT COUNT(*) FROM checkpoints"))
                .put("dbBytes", new File(db.getPath()).length()).put("walBytes", new File(db.getPath()+"-wal").length())
                .put("pageCount", scalar(db, "PRAGMA page_count")).put("freePages", scalar(db, "PRAGMA freelist_count"));
        if (writerPolicy) {
        db.beginTransaction();
        try { result.put("synchronous", scalar(db, "PRAGMA synchronous")).put("walAutocheckpoint", scalar(db, "PRAGMA wal_autocheckpoint")).put("journalSizeLimit", scalar(db, "PRAGMA journal_size_limit")); }
        finally { db.endTransaction(); }
        }
        return result;
    }
    private static String digest(SQLiteDatabase db) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (String pair : new String[]{"sources:id", "tracks:id", "queue_items:revision,ordinal", "checkpoints:id"}) {
            String[] split = pair.split(":"); digest.update(split[0].getBytes(StandardCharsets.UTF_8));
            try (Cursor c = db.rawQuery("SELECT * FROM " + split[0] + " ORDER BY " + split[1], null)) {
                while(c.moveToNext()) for(int i=0;i<c.getColumnCount();i++) {
                    byte[] value = c.isNull(i) ? new byte[0] : c.getString(i).getBytes(StandardCharsets.UTF_8);
                    digest.update(ByteBuffer.allocate(4).putInt(value.length).array()); digest.update(value);
                }
            }
        }
        return hex(digest.digest());
    }
    static String sha(File file) throws Exception { MessageDigest d=MessageDigest.getInstance("SHA-256"); try(InputStream in=new FileInputStream(file)){byte[] b=new byte[65536];int n;while((n=in.read(b))>=0)d.update(b,0,n);}return hex(d.digest()); }
    private static String hex(byte[] bytes) { StringBuilder s=new StringBuilder();for(byte b:bytes)s.append(String.format(Locale.ROOT,"%02x",b&255));return s.toString(); }
    private static void check(boolean ok, String message) { if(!ok)throw new AssertionError(message); }
    private static void wav(File file) throws Exception {
        int data=3200; ByteBuffer b=ByteBuffer.allocate(44+data).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36+data).put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII));
        b.putInt(16).putShort((short)1).putShort((short)1).putInt(8000).putInt(16000).putShort((short)2).putShort((short)16);
        b.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(data);
        Files.write(file.toPath(),b.array());
    }
    private static void deleteTree(File file) { File[] children=file.listFiles();if(children!=null)for(File child:children)deleteTree(child);file.delete(); }
    private static final class CloneContext extends ContextWrapper {
        private final File directory;
        CloneContext(Context base, File directory) { super(base);this.directory=directory; }
        @Override public File getDatabasePath(String name) { check(DB_NAME.equals(name), "unexpected database name");return new File(directory,name); }
        @Override public SQLiteDatabase openOrCreateDatabase(String name,int mode,SQLiteDatabase.CursorFactory factory) { return openOrCreateDatabase(name,mode,factory,null); }
        @Override public SQLiteDatabase openOrCreateDatabase(String name,int mode,SQLiteDatabase.CursorFactory factory,DatabaseErrorHandler handler) {
            int flags=SQLiteDatabase.CREATE_IF_NECESSARY;
            if((mode&Context.MODE_ENABLE_WRITE_AHEAD_LOGGING)!=0)flags|=SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING;
            return SQLiteDatabase.openDatabase(getDatabasePath(name).getPath(),factory,flags,handler);
        }
    }
}
