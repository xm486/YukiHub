package com.yuki.yukihub.data;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

public class YukiDatabaseHelper extends SQLiteOpenHelper {
    public static final String DB_NAME = "yukihub.db";
    /**
     * 数据库版本。
     * 【重要】这个数字只能往上加，绝对不能改小。
     * 一旦有用户装过更高版本，库里记录的就是那个高版本号；
     * 版本号改小会触发 onDowngrade，默认实现直接抛异常导致打开数据库就闪退。
     * 历史：15 = 聊天回复引用 + 未读锚点；16 = 曾短暂加过群聊等级列（已废弃，等级改为不入缓存）
     *      18 = 清理 metadata_cache 孤儿行（历史存量）+ 压缩数据库
     */
    public static final int DB_VERSION = 18;

    /**
     * 升级时清理过孤儿行的标记。
     *
     * VACUUM 不能在事务内执行，而 onUpgrade 由 SQLiteOpenHelper 包在事务里，
     * 所以升级只置标记，压缩延后到 onOpen（事务外）执行。
     * static：SQLiteOpenHelper 实例在项目里是各处新建的（每个 Repository 一个），
     * 用实例字段会导致置标记的实例与执行压缩的实例不是同一个。
     */
    private static final java.util.concurrent.atomic.AtomicBoolean vacuumPending =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    public YukiDatabaseHelper(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    /**
     * 事务外的收尾工作：执行升级时挂起的 VACUUM。
     *
     * onOpen 在 onCreate/onUpgrade 之后、且已脱离升级事务时调用，
     * 是执行 VACUUM 的合适时机。compareAndSet 保证多个 helper 实例
     * 并发打开时只压缩一次。
     */
    @Override
    public void onOpen(SQLiteDatabase db) {
        super.onOpen(db);
        if (!vacuumPending.compareAndSet(true, false)) return;
        try {
            long before = db.getPageSize() * getPageCount(db);
            db.execSQL("VACUUM");
            long after = db.getPageSize() * getPageCount(db);
            android.util.Log.i("YukiDB", "vacuum done: " + (before / 1024) + "KB -> " + (after / 1024) + "KB");
        } catch (Throwable t) {
            // 压缩失败不影响功能，只是文件没变小
            android.util.Log.w("YukiDB", "vacuum failed", t);
        }
    }

    private long getPageCount(SQLiteDatabase db) {
        android.database.Cursor c = null;
        try {
            c = db.rawQuery("PRAGMA page_count", null);
            return c.moveToFirst() ? c.getLong(0) : 0L;
        } catch (Throwable t) {
            return 0L;
        } finally {
            if (c != null) c.close();
        }
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
                "favorite INTEGER DEFAULT 0," +
                "nsfw INTEGER DEFAULT 0" +
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
        if (oldVersion < 14) {
            safeAlter(db, "ALTER TABLE games ADD COLUMN nsfw INTEGER DEFAULT 0");
        }
        if (oldVersion < 15) {
            // 聊天：回复引用 + 未读锚点（跳到最后已读位置）
            safeAlter(db, "ALTER TABLE friend_messages ADD COLUMN reply_to_id INTEGER DEFAULT 0");
            safeAlter(db, "ALTER TABLE group_messages_cache ADD COLUMN reply_to_id INTEGER DEFAULT 0");
            createChatReadMarkTable(db);
        }
        if (oldVersion < 17) {
            // 无结构变更。
            // 16 版曾给 group_messages_cache 加过 sender_level 列，
            // 后来改成等级不入缓存（等级会变，缓存值必然过期），该列不再使用。
            // 多余的列留着无害，SQLite 也不支持简单地删列，因此不做处理。
            // 这里保留分支只为把版本号推进到 17，修复此前误将版本改小导致的闪退。
            ensureChatCacheTables(db);
        }
        if (oldVersion < 18) {
            // 历史存量清理：删游戏时曾漏掉 metadata_cache，
            // 导致已删游戏的资料缓存永久残留，并被备份原样导出（备份体积持续膨胀）。
            // 删除逻辑本身已修（见 GameRepository.delete/deleteBatch/deleteAll），
            // 这里只负责把之前攒下的垃圾一次性擦掉。
            try {
                int removed = db.delete("metadata_cache",
                        "game_id NOT IN (SELECT id FROM games)", null);
                if (removed > 0) {
                    android.util.Log.i("YukiDB", "upgrade 18: pruned " + removed + " orphan metadata rows");
                    // 删完只是把页标记为空闲，文件不会自动变小，需要 VACUUM 回收。
                    // 但 onUpgrade 运行在事务内，VACUUM 在事务里会失败，
                    // 因此这里只置标记，实际压缩延后到事务外执行。
                    vacuumPending.set(true);
                }
            } catch (Throwable t) {
                android.util.Log.w("YukiDB", "upgrade 18 prune failed", t);
            }
        }
    }

    /**
     * 版本降级兜底。
     * 默认实现会抛异常导致闪退，这里改成不破坏数据的安全处理：
     * 只确保当前代码需要的表和列都存在，多余的结构留着不管。
     */
    @Override
    public void onDowngrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        ensureChatCacheTables(db);
    }

    /**
     * 确保聊天相关表与列齐全（幂等，可重复调用）。
     * 供升级、降级两条路径共用，避免任一方向缺表缺列。
     */
    private void ensureChatCacheTables(SQLiteDatabase db) {
        createChatCacheTables(db);
        safeAlter(db, "ALTER TABLE friend_messages ADD COLUMN reply_to_id INTEGER DEFAULT 0");
        safeAlter(db, "ALTER TABLE group_messages_cache ADD COLUMN reply_to_id INTEGER DEFAULT 0");
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
                "is_mine INTEGER DEFAULT 0," +
                "reply_to_id INTEGER DEFAULT 0" +
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
                "is_mine INTEGER DEFAULT 0," +
                "reply_to_id INTEGER DEFAULT 0" +
                ")");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_group_messages_cache ON group_messages_cache(group_id, id)");
        createChatReadMarkTable(db);
    }

    /**
     * 会话已读锚点：记录每个会话「上次离开时读到哪条消息」。
     * 用于重进会话时提供「跳到未读起点」的定位（QQ 式体验），避免漏看消息。
     * peer_key 格式：好友 = "f:<friendId>"，群聊 = "g:<groupId>"。
     */
    private void createChatReadMarkTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS chat_read_marks (" +
                "peer_key TEXT PRIMARY KEY," +
                "last_read_id INTEGER DEFAULT 0," +
                "updated_at INTEGER DEFAULT 0" +
                ")");
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