package com.yuki.yukihub.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.yuki.yukihub.model.MusicAlbum;
import com.yuki.yukihub.model.MusicTrack;

import java.util.ArrayList;
import java.util.List;

/**
 * 音乐厅数据仓库（专辑 / 曲目）。
 *
 * <p>风格对齐 {@link GameRepository}：每个方法自己开库、关 Cursor、不抛异常给调用方。
 *
 * <p><b>引用模式</b>：本仓库只管 URI 与元数据，**不复制、不删除用户的文件**。
 * 删除专辑/曲目只删数据库记录（原文件永远不动）。
 *
 * <p><b>不参与备份与云同步</b>（见 YukiDatabaseHelper#createMusicTables 的说明）。
 */
public class MusicRepository {

    private final YukiDatabaseHelper helper;

    public MusicRepository(Context context) {
        helper = new YukiDatabaseHelper(context.getApplicationContext());
    }

    /* ==================== 专辑 ==================== */

    /** 全部专辑（含"散装单曲"虚拟专辑，如果它下面有曲目）。默认最近添加优先。 */
    public List<MusicAlbum> getAllAlbums() {
        return getAllAlbums("created_at DESC, id DESC");
    }

    public List<MusicAlbum> getAllAlbums(String orderBy) {
        List<MusicAlbum> list = new ArrayList<>();
        SQLiteDatabase db = helper.getReadableDatabase();
        String order = (orderBy == null || orderBy.trim().isEmpty())
                ? "created_at DESC, id DESC" : orderBy;
        Cursor c = db.query("music_albums", null, null, null, null, null, order);
        try {
            while (c.moveToNext()) list.add(albumFromCursor(c));
        } finally {
            c.close();
        }
        return list;
    }

    /** 专辑墙用：最近添加的 N 张真实专辑（不含"散装单曲"），过滤掉没有曲目的空专辑。 */
    public List<MusicAlbum> getWallAlbums(int limit) {
        List<MusicAlbum> all = getAllAlbums("created_at DESC, id DESC");
        List<MusicAlbum> out = new ArrayList<>();
        for (MusicAlbum a : all) {
            if (a.id == MusicAlbum.SINGLES_ID) continue;   // 虚拟专辑不上墙
            if (a.trackCount <= 0) continue;               // 空专辑不上墙
            out.add(a);
            if (limit > 0 && out.size() >= limit) break;
        }
        return out;
    }

    public MusicAlbum getAlbum(long id) {
        if (id == MusicAlbum.SINGLES_ID) return virtualSinglesAlbum();
        SQLiteDatabase db = helper.getReadableDatabase();
        Cursor c = db.query("music_albums", null, "id=?", new String[]{String.valueOf(id)},
                null, null, null);
        try {
            return c.moveToNext() ? albumFromCursor(c) : null;
        } finally {
            c.close();
        }
    }

    /** 按 SAF 目录树 URI 找专辑（防止同一个文件夹被重复添加两次）。 */
    public MusicAlbum findAlbumByRootUri(String rootUri) {
        if (rootUri == null || rootUri.trim().isEmpty()) return null;
        SQLiteDatabase db = helper.getReadableDatabase();
        Cursor c = db.query("music_albums", null, "root_uri=?",
                new String[]{rootUri.trim()}, null, null, null);
        try {
            return c.moveToNext() ? albumFromCursor(c) : null;
        } finally {
            c.close();
        }
    }

    public long insertAlbum(MusicAlbum album) {
        if (album == null) return -1;
        SQLiteDatabase db = helper.getWritableDatabase();
        long now = System.currentTimeMillis();
        if (album.createdAt <= 0) album.createdAt = now;
        album.updatedAt = now;
        long id = db.insert("music_albums", null, albumToValues(album));
        album.id = id;
        return id;
    }

    public int updateAlbum(MusicAlbum album) {
        if (album == null || album.id <= 0) return 0;
        SQLiteDatabase db = helper.getWritableDatabase();
        album.updatedAt = System.currentTimeMillis();
        return db.update("music_albums", albumToValues(album), "id=?",
                new String[]{String.valueOf(album.id)});
    }

    /** 只更新封面（换封面用，避免整行重写）。 */
    public int updateAlbumCover(long albumId, String coverUri) {
        if (albumId <= 0) return 0;
        SQLiteDatabase db = helper.getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put("cover_uri", coverUri);
        v.put("updated_at", System.currentTimeMillis());
        return db.update("music_albums", v, "id=?", new String[]{String.valueOf(albumId)});
    }

    /**
     * 删除专辑（连同其曲目记录）。
     * ⚠️ **不删用户的原文件** —— 只清理数据库。
     */
    public int deleteAlbum(long albumId) {
        if (albumId <= 0) return 0;
        SQLiteDatabase db = helper.getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("music_tracks", "album_id=?", new String[]{String.valueOf(albumId)});
            int n = db.delete("music_albums", "id=?", new String[]{String.valueOf(albumId)});
            db.setTransactionSuccessful();
            return n;
        } finally {
            db.endTransaction();
        }
    }

    /** 重新统计某专辑的曲目数（增删曲目后调用）。 */
    public void refreshTrackCount(long albumId) {
        if (albumId <= 0) return;
        SQLiteDatabase db = helper.getWritableDatabase();
        try {
            db.execSQL("UPDATE music_albums SET track_count=" +
                            "(SELECT COUNT(*) FROM music_tracks WHERE album_id=?)," +
                            "updated_at=? WHERE id=?",
                    new Object[]{albumId, System.currentTimeMillis(), albumId});
        } catch (Throwable ignored) {
        }
    }

    /** 曲目总数（全部）。 */
    public int countAllTracks() {
        SQLiteDatabase db = helper.getReadableDatabase();
        Cursor c = null;
        try {
            c = db.rawQuery("SELECT COUNT(*) FROM music_tracks", null);
            return c.moveToFirst() ? c.getInt(0) : 0;
        } catch (Throwable t) {
            return 0;
        } finally {
            if (c != null) c.close();
        }
    }

    /* ==================== 曲目 ==================== */

    /** 某专辑的曲目（按序号、再按添加时间）。 */
    public List<MusicTrack> getTracks(long albumId) {
        List<MusicTrack> list = new ArrayList<>();
        SQLiteDatabase db = helper.getReadableDatabase();
        Cursor c = db.query("music_tracks", null, "album_id=?",
                new String[]{String.valueOf(albumId)}, null, null,
                "track_no ASC, id ASC");
        try {
            while (c.moveToNext()) list.add(trackFromCursor(c));
        } finally {
            c.close();
        }
        return list;
    }

    public MusicTrack getTrack(long id) {
        SQLiteDatabase db = helper.getReadableDatabase();
        Cursor c = db.query("music_tracks", null, "id=?", new String[]{String.valueOf(id)},
                null, null, null);
        try {
            return c.moveToNext() ? trackFromCursor(c) : null;
        } finally {
            c.close();
        }
    }

    public long insertTrack(MusicTrack t) {
        if (t == null || t.audioUri == null || t.audioUri.trim().isEmpty()) return -1;
        SQLiteDatabase db = helper.getWritableDatabase();
        if (t.createdAt <= 0) t.createdAt = System.currentTimeMillis();
        long id = db.insert("music_tracks", null, trackToValues(t));
        t.id = id;
        return id;
    }

    /** 批量插入（一个事务，扫描文件夹后写库用）。返回成功条数。 */
    public int insertTracks(List<MusicTrack> tracks) {
        if (tracks == null || tracks.isEmpty()) return 0;
        SQLiteDatabase db = helper.getWritableDatabase();
        int n = 0;
        db.beginTransaction();
        try {
            long now = System.currentTimeMillis();
            for (MusicTrack t : tracks) {
                if (t == null || t.audioUri == null || t.audioUri.trim().isEmpty()) continue;
                if (t.createdAt <= 0) t.createdAt = now;
                long id = db.insert("music_tracks", null, trackToValues(t));
                if (id > 0) {
                    t.id = id;
                    n++;
                }
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return n;
    }

    public int updateTrack(MusicTrack t) {
        if (t == null || t.id <= 0) return 0;
        SQLiteDatabase db = helper.getWritableDatabase();
        return db.update("music_tracks", trackToValues(t), "id=?",
                new String[]{String.valueOf(t.id)});
    }

    /** 绑定 / 解绑 PV（传 null 或空串 = 解绑）。 */
    public int setTrackPv(long trackId, String pvUri) {
        if (trackId <= 0) return 0;
        SQLiteDatabase db = helper.getWritableDatabase();
        ContentValues v = new ContentValues();
        if (pvUri == null || pvUri.trim().isEmpty()) {
            v.putNull("pv_uri");
        } else {
            v.put("pv_uri", pvUri.trim());
        }
        return db.update("music_tracks", v, "id=?", new String[]{String.valueOf(trackId)});
    }

    /** 删除曲目记录（不删原文件）。 */
    public int deleteTrack(long trackId) {
        if (trackId <= 0) return 0;
        SQLiteDatabase db = helper.getWritableDatabase();
        return db.delete("music_tracks", "id=?", new String[]{String.valueOf(trackId)});
    }

    /** 只保留有曲目的专辑（清掉扫描失败留下的空壳）。 */
    public int pruneEmptyAlbums() {
        SQLiteDatabase db = helper.getWritableDatabase();
        try {
            return db.delete("music_albums",
                    "id != 0 AND NOT EXISTS (SELECT 1 FROM music_tracks WHERE music_tracks.album_id = music_albums.id)",
                    null);
        } catch (Throwable t) {
            return 0;
        }
    }

    /* ==================== 虚拟"散装单曲"专辑 ==================== */

    /** 构造"散装单曲"虚拟专辑（id=0，不入库）。没有散装曲目时返回 null。 */
    public MusicAlbum virtualSinglesAlbum() {
        List<MusicTrack> singles = getTracks(MusicAlbum.SINGLES_ID);
        if (singles.isEmpty()) return null;
        MusicAlbum a = new MusicAlbum();
        a.id = MusicAlbum.SINGLES_ID;
        a.title = "散装单曲";
        a.trackCount = singles.size();
        a.createdAt = singles.get(0).createdAt;
        a.updatedAt = a.createdAt;
        return a;
    }

    /* ==================== 映射 ==================== */

    private MusicAlbum albumFromCursor(Cursor c) {
        MusicAlbum a = new MusicAlbum();
        a.id = c.getLong(c.getColumnIndexOrThrow("id"));
        a.title = c.getString(c.getColumnIndexOrThrow("title"));
        a.rootUri = optString(c, "root_uri");
        a.coverUri = optString(c, "cover_uri");
        a.gameId = optLong(c, "game_id", MusicAlbum.NO_GAME);
        a.trackCount = (int) optLong(c, "track_count", 0);
        a.createdAt = optLong(c, "created_at", 0);
        a.updatedAt = optLong(c, "updated_at", 0);
        return a;
    }

    private ContentValues albumToValues(MusicAlbum a) {
        ContentValues v = new ContentValues();
        v.put("title", a.title == null ? "" : a.title);
        v.put("root_uri", a.rootUri);
        v.put("cover_uri", a.coverUri);
        v.put("game_id", a.gameId);
        v.put("track_count", a.trackCount);
        v.put("created_at", a.createdAt);
        v.put("updated_at", a.updatedAt);
        return v;
    }

    private MusicTrack trackFromCursor(Cursor c) {
        MusicTrack t = new MusicTrack();
        t.id = c.getLong(c.getColumnIndexOrThrow("id"));
        t.albumId = optLong(c, "album_id", MusicAlbum.SINGLES_ID);
        t.trackNo = (int) optLong(c, "track_no", 0);
        t.title = optString(c, "title");
        t.artist = optString(c, "artist");
        t.audioUri = optString(c, "audio_uri");
        t.pvUri = optString(c, "pv_uri");
        t.coverUri = optString(c, "cover_uri");
        t.durationMs = optLong(c, "duration_ms", 0);
        t.sizeBytes = optLong(c, "size_bytes", 0);
        t.createdAt = optLong(c, "created_at", 0);
        return t;
    }

    private ContentValues trackToValues(MusicTrack t) {
        ContentValues v = new ContentValues();
        v.put("album_id", t.albumId);
        v.put("track_no", t.trackNo);
        v.put("title", t.title == null ? "" : t.title);
        v.put("artist", t.artist);
        v.put("audio_uri", t.audioUri);
        v.put("pv_uri", t.pvUri);
        v.put("cover_uri", t.coverUri);
        v.put("duration_ms", t.durationMs);
        v.put("size_bytes", t.sizeBytes);
        v.put("created_at", t.createdAt);
        return v;
    }

    private static String optString(Cursor c, String col) {
        int i = c.getColumnIndex(col);
        return (i < 0 || c.isNull(i)) ? null : c.getString(i);
    }

    private static long optLong(Cursor c, String col, long def) {
        int i = c.getColumnIndex(col);
        return (i < 0 || c.isNull(i)) ? def : c.getLong(i);
    }
}