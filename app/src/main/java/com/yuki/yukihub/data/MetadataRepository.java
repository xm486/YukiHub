package com.yuki.yukihub.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import com.yuki.yukihub.metadata.VnMetadata;

import org.json.JSONArray;
import org.json.JSONObject;


public class MetadataRepository {
    private final YukiDatabaseHelper helper;

    public MetadataRepository(Context context) {
        helper = new YukiDatabaseHelper(context.getApplicationContext());
    }

    public VnMetadata getVndb(long gameId) {
        SQLiteDatabase db = helper.getReadableDatabase();
        Cursor c = db.rawQuery("SELECT json FROM metadata_cache WHERE game_id=? AND source='vndb' LIMIT 1", new String[]{String.valueOf(gameId)});
        try {
            if (!c.moveToFirst()) return null;
            return VnMetadata.fromJson(c.getString(0));
        } finally {
            c.close();
        }
    }

    public void saveVndb(long gameId, VnMetadata data) {
        if (gameId <= 0 || data == null) return;
        SQLiteDatabase db = helper.getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put("game_id", gameId);
        v.put("source", "vndb");
        v.put("source_id", data.id);
        v.put("json", data.toJson().toString());
        v.put("updated_at", System.currentTimeMillis());
        db.insertWithOnConflict("metadata_cache", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public VnMetadata getBangumi(long gameId) {
        SQLiteDatabase db = helper.getReadableDatabase();
        Cursor c = db.rawQuery("SELECT json FROM metadata_cache WHERE game_id=? AND source='bangumi' LIMIT 1", new String[]{String.valueOf(gameId)});
        try {
            if (!c.moveToFirst()) return null;
            return VnMetadata.fromJson(c.getString(0));
        } finally {
            c.close();
        }
    }

    public void saveBangumi(long gameId, VnMetadata data) {
        if (gameId <= 0 || data == null) return;
        SQLiteDatabase db = helper.getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put("game_id", gameId);
        v.put("source", "bangumi");
        v.put("source_id", data.id);
        v.put("json", data.toJson().toString());
        v.put("updated_at", System.currentTimeMillis());
        db.insertWithOnConflict("metadata_cache", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public JSONArray exportMetadataJson() throws Exception {
        JSONArray arr = new JSONArray();
        SQLiteDatabase db = helper.getReadableDatabase();
        Cursor c = db.rawQuery("SELECT m.game_id,g.root_uri,m.source,m.source_id,m.json,m.updated_at FROM metadata_cache m LEFT JOIN games g ON g.id=m.game_id ORDER BY m.updated_at ASC", null);
        try {
            while (c.moveToNext()) {
                JSONObject o = new JSONObject();
                o.put("game_local_id", c.getLong(0));
                o.put("game_root_uri", c.getString(1));
                o.put("source", c.getString(2));
                o.put("source_id", c.getString(3));
                o.put("json", c.getString(4));
                o.put("updated_at", c.getLong(5));
                arr.put(o);
            }
        } finally {
            c.close();
        }
        return arr;
    }

    public int importMetadataJson(JSONArray arr) throws Exception {
        if (arr == null) return 0;
        int changed = 0;
        SQLiteDatabase db = helper.getWritableDatabase();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            long gameId = findGameIdByRootUri(db, o.optString("game_root_uri", ""));
            if (gameId <= 0) gameId = o.optLong("game_local_id", -1);
            String source = o.optString("source", "");
            String json = o.optString("json", "");
            if (gameId <= 0 || source.isEmpty() || json.isEmpty()) continue;
            ContentValues v = new ContentValues();
            v.put("game_id", gameId);
            v.put("source", source);
            v.put("source_id", o.optString("source_id", ""));
            v.put("json", json);
            v.put("updated_at", o.optLong("updated_at", System.currentTimeMillis()));
            db.insertWithOnConflict("metadata_cache", null, v, SQLiteDatabase.CONFLICT_REPLACE);
            changed++;
        }
        return changed;
    }

    private long findGameIdByRootUri(SQLiteDatabase db, String rootUri) {
        if (rootUri == null || rootUri.trim().isEmpty()) return -1;
        Cursor c = db.rawQuery("SELECT id FROM games WHERE root_uri=? LIMIT 1", new String[]{rootUri});
        try {
            return c.moveToFirst() ? c.getLong(0) : -1;
        } finally {
            c.close();
        }
    }

    public void clearVndb(long gameId) {
        SQLiteDatabase db = helper.getWritableDatabase();
        db.delete("metadata_cache", "game_id=? AND source='vndb'", new String[]{String.valueOf(gameId)});
    }

    public void clearBangumi(long gameId) {
        SQLiteDatabase db = helper.getWritableDatabase();
        db.delete("metadata_cache", "game_id=? AND source='bangumi'", new String[]{String.valueOf(gameId)});
    }
}