package com.yuki.yukihub.data;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

public class YukiDatabaseHelper extends SQLiteOpenHelper {
    public static final String DB_NAME = "yukihub.db";
    public static final int DB_VERSION = 10;

    public YukiDatabaseHelper(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE games (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "title TEXT NOT NULL," +
                "original_title TEXT," +
                "engine TEXT NOT NULL," +
                "root_uri TEXT NOT NULL," +
                "cover_uri TEXT," +
                "cover_persist_uri TEXT," +
                "cover_source_type INTEGER DEFAULT 0," +
                "emulator_package TEXT," +
                "launch_target TEXT DEFAULT 'data.xp3'," +
"winlator_launch_mode TEXT DEFAULT 'game'," +
"description TEXT," +
                "tags TEXT," +
                "gamehub_local_game_id TEXT," +
                "gamehub_launch_mode TEXT DEFAULT 'game'," +
                "gaishi_local_game_id TEXT," +
                "play_status TEXT DEFAULT 'unplayed'," +
                "total_play_time INTEGER DEFAULT 0," +
                "last_played_at INTEGER DEFAULT 0," +
                "playtime_reset_at INTEGER DEFAULT 0," +
                "created_at INTEGER NOT NULL," +
                "updated_at INTEGER NOT NULL," +
                "hidden INTEGER DEFAULT 0" +
                ")");
        db.execSQL("CREATE TABLE play_sessions (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "game_id INTEGER NOT NULL," +
                "start_time INTEGER NOT NULL," +
                "end_time INTEGER," +
                "duration INTEGER DEFAULT 0," +
                "launch_type TEXT," +
                "session_uuid TEXT," +
                "device_id TEXT," +
                "created_at INTEGER DEFAULT 0," +
                "updated_at INTEGER DEFAULT 0," +
                "dirty INTEGER DEFAULT 1," +
                "deleted INTEGER DEFAULT 0," +
                "FOREIGN KEY(game_id) REFERENCES games(id) ON DELETE CASCADE" +
                ")");
        db.execSQL("CREATE TABLE settings (key TEXT PRIMARY KEY, value TEXT)");
        createMetadataCacheTable(db);
        try { db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_play_sessions_uuid ON play_sessions(session_uuid)"); } catch (Exception ignored) { }
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            safeAlter(db, "ALTER TABLE games ADD COLUMN cover_persist_uri TEXT");
            safeAlter(db, "ALTER TABLE games ADD COLUMN cover_source_type INTEGER DEFAULT 0");
            safeAlter(db, "ALTER TABLE games ADD COLUMN launch_target TEXT DEFAULT 'data.xp3'");
        }
        if (oldVersion < 3) {
            createMetadataCacheTable(db);
        }
        if (oldVersion < 4) {
            upgradePlaySessionsForSync(db);
        }
        if (oldVersion < 5) {
safeAlter(db, "ALTER TABLE games ADD COLUMN play_status TEXT DEFAULT 'unplayed'");
}
if (oldVersion < 6) {
safeAlter(db, "ALTER TABLE games ADD COLUMN winlator_launch_mode TEXT DEFAULT 'game'");
}
if (oldVersion < 7) {
safeAlter(db, "ALTER TABLE games ADD COLUMN playtime_reset_at INTEGER DEFAULT 0");
}
if (oldVersion < 8) {
safeAlter(db, "ALTER TABLE games ADD COLUMN gaishi_local_game_id TEXT");
}
        if (oldVersion < 9) {
            safeAlter(db, "ALTER TABLE games ADD COLUMN gamehub_local_game_id TEXT");
            try { db.execSQL("UPDATE games SET gamehub_local_game_id=gaishi_local_game_id WHERE (gamehub_local_game_id IS NULL OR gamehub_local_game_id='') AND gaishi_local_game_id IS NOT NULL"); } catch (Exception ignored) { }
        }
        if (oldVersion < 10) {
            safeAlter(db, "ALTER TABLE games ADD COLUMN gamehub_launch_mode TEXT DEFAULT 'game'");
        }
    }

    private void createMetadataCacheTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS metadata_cache (" +
                "game_id INTEGER PRIMARY KEY," +
                "source TEXT NOT NULL," +
                "source_id TEXT," +
                "json TEXT NOT NULL," +
                "updated_at INTEGER NOT NULL" +
                ")");
    }

    private void upgradePlaySessionsForSync(SQLiteDatabase db) {
        safeAlter(db, "ALTER TABLE play_sessions ADD COLUMN session_uuid TEXT");
        safeAlter(db, "ALTER TABLE play_sessions ADD COLUMN device_id TEXT");
        safeAlter(db, "ALTER TABLE play_sessions ADD COLUMN created_at INTEGER DEFAULT 0");
        safeAlter(db, "ALTER TABLE play_sessions ADD COLUMN updated_at INTEGER DEFAULT 0");
        safeAlter(db, "ALTER TABLE play_sessions ADD COLUMN dirty INTEGER DEFAULT 1");
        safeAlter(db, "ALTER TABLE play_sessions ADD COLUMN deleted INTEGER DEFAULT 0");
        try { db.execSQL("UPDATE play_sessions SET session_uuid=lower(hex(randomblob(16))) WHERE session_uuid IS NULL OR session_uuid='' "); } catch (Exception ignored) { }
        try { db.execSQL("UPDATE play_sessions SET created_at=start_time WHERE created_at IS NULL OR created_at=0"); } catch (Exception ignored) { }
        try { db.execSQL("UPDATE play_sessions SET updated_at=COALESCE(end_time,start_time) WHERE updated_at IS NULL OR updated_at=0"); } catch (Exception ignored) { }
        try { db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_play_sessions_uuid ON play_sessions(session_uuid)"); } catch (Exception ignored) { }
    }

    private void safeAlter(SQLiteDatabase db, String sql) {
        try { db.execSQL(sql); } catch (Exception ignored) { }
    }
}