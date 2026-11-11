package com.apexcsfc.takassist.maps.core;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteStatement;
import android.os.StatFs;

import com.apexcsfc.takassist.maps.core.build.MapBuilder;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Installs one area on the phone with no server of our own: downloads the area's
 * OpenStreetMap file from Geofabrik (or the administrator's mirror), checks it against
 * Geofabrik's MD5, converts it with {@link MapBuilder}, and saves it as a region.
 * Blocking; run on a worker thread or in a service.
 */
public final class Installer {

    private final Context ctx;
    private final String mirror;
    private final MapPackManager.Listener l;
    private final Downloader dl = new Downloader();
    private volatile boolean cancelled;

    public Installer(Context ctx, String mirror, MapPackManager.Listener l) {
        this.ctx = ctx;
        this.mirror = mirror;
        this.l = l;
    }

    public void cancel() {
        cancelled = true;
        dl.cancel();
    }

    private void say(String s, double f) {
        if (l != null) l.onStatus(s, f);
    }

    public void install(final String id) throws Exception {
        final String name = States.nameFor(id);
        File work = new File(ctx.getCacheDir(), "maps");
        work.mkdirs();
        String url = States.pbfUrl(mirror, id);

        // 1. Download.
        say(name + ": checking the download…", -1);
        final long size = dl.headSize(url);
        long need = size * 3 + (200L << 20);
        long free = new StatFs(work.getPath()).getAvailableBytes();
        if (size > 0 && free < need) {
            throw new IOException("Not enough free space for " + name + ": needs about "
                    + MapPackManager.mb(need) + ", " + MapPackManager.mb(free) + " free.");
        }
        File pbf = new File(work, id + ".osm.pbf");
        dl.download(url, false, pbf, size, null, new Downloader.Progress() {
            @Override
            public void onBytes(String what, long done, long total) {
                say(name + ": downloading " + MapPackManager.mb(done) + " of " + MapPackManager.mb(total),
                        total > 0 ? 0.4 * done / total : -1);
            }
        });
        if (cancelled) throw new IOException("Cancelled");

        // 2. Check it against the published MD5, when there is one.
        say(name + ": checking the file…", 0.4);
        String want = null;
        try {
            String t = dl.fetchText(url + ".md5").trim();
            if (t.length() >= 32) want = t.substring(0, 32).toLowerCase(Locale.US);
        } catch (IOException ignored) {
            // mirror without .md5 files
        }
        if (want != null && !want.equals(md5(pbf))) {
            pbf.delete();
            throw new IOException(name + " didn't download correctly. Try again.");
        }

        // 3. Convert.
        File build = new File(work, id + ".build");
        MapPackManager.deleteTree(build);
        build.mkdirs();
        File areaTmp = new File(build, "area.sqlite"), routeTmp = new File(build, "route.bin");
        SqliteSink sink = new SqliteSink(areaTmp);
        double[] box;
        try {
            MapBuilder b = new MapBuilder(new File(build, "tmp"), sink, new MapBuilder.Progress() {
                @Override
                public void onProgress(String stage, double f) {
                    say(name + ": " + stage + "…", 0.4 + 0.58 * f);
                }

                @Override
                public boolean cancelled() {
                    return cancelled;
                }
            });
            box = b.build(pbf, routeTmp);
        } finally {
            sink.close();
            MapPackManager.deleteTree(new File(build, "tmp"));
        }

        // 4. Save as an installed region.
        synchronized (MapPackManager.LOCK) {
            say(name + ": saving…", 0.98);
            File stage = new File(MapPacks.regionsDir(), "." + id + ".new");
            MapPackManager.deleteTree(stage);
            stage.mkdirs();
            copy(areaTmp, new File(stage, "area.sqlite"));
            copy(routeTmp, new File(stage, "route.bin"));
            String built = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
            MapPacks.writeTextAtomic(new File(stage, "region.json"),
                    new Region(id, name, built, box).toJson().toString());
            File dir = MapPacks.regionDir(id), old = new File(MapPacks.regionsDir(), "." + id + ".old");
            MapPackManager.deleteTree(old);
            if (dir.exists() && !dir.renameTo(old)) throw new IOException("Can't replace the old " + name);
            if (!stage.renameTo(dir)) throw new IOException("Can't save " + name);
            MapPackManager.deleteTree(old);
        }
        MapPackManager.deleteTree(build);
        pbf.delete();
        say(name + ": ready", 1);
    }

    private static String md5(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        try (InputStream in = new BufferedInputStream(new FileInputStream(f), 1 << 16)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        return Downloader.hex(md.digest());
    }

    private static void copy(File from, File to) throws IOException {
        try (InputStream in = new FileInputStream(from); OutputStream out = new FileOutputStream(to)) {
            byte[] buf = new byte[1 << 20];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    /** Writes area.sqlite with the same tables and indexes as the Python builder. */
    static final class SqliteSink implements MapBuilder.Sink {
        private final SQLiteDatabase db;
        private final SQLiteStatement feat, shape;
        private int pending;
        private boolean closed;

        SqliteSink(File f) {
            f.delete();
            db = SQLiteDatabase.openOrCreateDatabase(f, null);
            db.rawQuery("PRAGMA journal_mode=OFF", null).close();
            db.execSQL("PRAGMA synchronous=OFF");
            db.execSQL("CREATE TABLE meta(k TEXT PRIMARY KEY, v TEXT)");
            db.execSQL("CREATE TABLE features(name TEXT, name_lc TEXT, kind TEXT, cat TEXT, lat REAL, lon REAL, cx INTEGER, cy INTEGER)");
            db.execSQL("CREATE TABLE shapes(name TEXT, name_lc TEXT, kind TEXT, cat TEXT, coords BLOB, cx INTEGER, cy INTEGER)");
            feat = db.compileStatement("INSERT INTO features VALUES(?,?,?,?,?,?,?,?)");
            shape = db.compileStatement("INSERT INTO shapes VALUES(?,?,?,?,?,?,?)");
            db.beginTransaction();
        }

        private void tick() {
            if (++pending >= 20000) {
                db.setTransactionSuccessful();
                db.endTransaction();
                db.beginTransaction();
                pending = 0;
            }
        }

        @Override
        public void feature(String name, String lc, String kind, String cat, double lat, double lon, int cx, int cy) {
            feat.bindString(1, name);
            feat.bindString(2, lc);
            feat.bindString(3, kind);
            feat.bindString(4, cat);
            feat.bindDouble(5, lat);
            feat.bindDouble(6, lon);
            feat.bindLong(7, cx);
            feat.bindLong(8, cy);
            feat.executeInsert();
            tick();
        }

        @Override
        public void shape(String name, String lc, String kind, String cat, byte[] coords, int cx, int cy) {
            shape.bindString(1, name);
            shape.bindString(2, lc);
            shape.bindString(3, kind);
            shape.bindString(4, cat);
            shape.bindBlob(5, coords);
            shape.bindLong(6, cx);
            shape.bindLong(7, cy);
            shape.executeInsert();
            tick();
        }

        @Override
        public void finish(double[] b, String built) {
            String[][] meta = {{"south", "" + b[0]}, {"west", "" + b[1]}, {"north", "" + b[2]}, {"east", "" + b[3]},
                    {"cell", "0.01"}, {"chunk", "16"}, {"built", built},
                    {"source", "OpenStreetMap contributors (ODbL)"}};
            for (String[] kv : meta) db.execSQL("INSERT INTO meta VALUES(?,?)", kv);
            db.setTransactionSuccessful();
            db.endTransaction();
            db.execSQL("CREATE INDEX fx_cell ON features(cx, cy)");
            db.execSQL("CREATE INDEX fx_cat ON features(cat, cx, cy)");
            db.execSQL("CREATE INDEX fx_name ON features(name_lc)");
            db.execSQL("CREATE INDEX sh_cat ON shapes(cat, cx, cy)");
            db.execSQL("CREATE INDEX sh_name ON shapes(name_lc)");
            db.execSQL("ANALYZE");
            closed = true;
            db.close();
        }

        void close() {
            if (!closed) {
                try {
                    if (db.inTransaction()) db.endTransaction();
                } catch (Exception ignored) {
                }
                db.close();
                closed = true;
            }
        }
    }
}
