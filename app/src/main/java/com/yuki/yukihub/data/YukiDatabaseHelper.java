package com.yuki.yukihub.data;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

public class YukiDatabaseHelper extends SQLiteOpenHelper {
    public static final String DB_NAME = "yukihub.db";
    public static final int DB_VERSION = 13;

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
                "hidden INTEGER DEFAULT 0," +
                "favorite INTEGER DEFAULT 0" +
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
        createChatCacheTables(db);
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
        if (oldVersion < 11) {
            safeAlter(db, "ALTER TABLE games ADD COLUMN favorite INTEGER DEFAULT 0");
        }
        if (oldVersion < 12) {
            upgradeMetadataCachePrimaryKey(db);
        }
        if (oldVersion < 13) {
            createChatCacheTables(db);
        }
    }

    private void createMetadataCacheTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS metadata_cache (" +
                "game_id INTEGER NOT NULL," +
                "source TEXT NOT NULL," +
                "source_id TEXT," +
                "json TEXT NOT NULL," +
                "updated_at INTEGER NOT NULL," +
                "PRIMARY KEY(game_id, source)" +
                ")");
    }

    /**
     * 聊天记录本地缓存表（好友私聊 + 群聊）。
     * 仅用于离线查看与减少服务器请求，不参与备份与云同步。
     */
    private void createChatCacheTables(SQLiteDatabase db) {
        // 好友私聊缓存
        db.execSQL("CREATE TABLE IF NOT EXISTS friend_messages (" +
                "id INTEGER PRIMARY KEY," +          // 服务端消息 id
                "friend_id TEXT NOT NULL," +          // 对方用户 id
                "sender_id TEXT," +
                "receiver_id TEXT," +
                "content TEXT," +
                "msg_type TEXT," +
                "created_at TEXT," +
                "is_mine INTEGER DEFAULT 0" +
                ")");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_friend_messages ON friend_messages(friend_id, id)");

        // 群聊消息缓存（含渲染所需发送者信息）
        db.execSQL("CREATE TABLE IF NOT EXISTS group_messages_cache (" +
                "id INTEGER PRIMARY KEY," +           // 服务端消息 id
                "group_id INTEGER NOT NULL," +
                "sender_id TEXT," +
                "sender_nickname TEXT," +
                "sender_avatar TEXT," +
                "sender_uid INTEGER DEFAULT 0," +
                "sender_is_admin INTEGER DEFAULT 0," +
                "content TEXT," +
                "msg_type TEXT," +
                "created_at TEXT," +
                "recalled INTEGER DEFAULT 0," +
                "is_mine INTEGER DEFAULT 0" +
                ")");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_group_messages_cache ON group_messages_cache(group_id, id)");
    }

    private void upgradeMetadataCachePrimaryKey(SQLiteDatabase db) {
        try {
            db.beginTransaction();
            db.execSQL("CREATE TABLE IF NOT EXISTS metadata_cache_new (" +
                    "game_id INTEGER NOT NULL," +
                    "source TEXT NOT NULL," +
                    "source_id TEXT," +
                    "json TEXT NOT NULL," +
                    "updated_at INTEGER NOT NULL," +
                    "PRIMARY KEY(game_id, source)" +
                    ")");
            db.execSQL("INSERT OR REPLACE INTO metadata_cache_new(game_id,source,source_id,json,updated_at) " +
                    "SELECT game_id,source,source_id,json,updated_at FROM metadata_cache");
            db.execSQL("DROP TABLE IF EXISTS metadata_cache");
            db.execSQL("ALTER TABLE metadata_cache_new RENAME TO metadata_cache");
            db.setTransactionSuccessful();
        } catch (Exception ignored) {
            try { createMetadataCacheTable(db); } catch (Exception ignored2) { }
        } finally {
            try { db.endTransaction(); } catch (Exception ignored) { }
        }
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