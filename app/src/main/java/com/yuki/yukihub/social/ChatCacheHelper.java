package com.yuki.yukihub.social;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.yuki.yukihub.data.YukiDatabaseHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * 聊天记录本地缓存（好友私聊 + 群聊）。
 *
 * 作用：
 * 1. 打开会话时优先渲染本地缓存（秒开、离线可看历史），再从服务器拉最近 20 条增量合并；
 * 2. 翻历史时优先读本地缓存，缓存到底才请求服务器；
 * 3. 轮询/发送的新消息写入缓存。
 *
 * 仅作为降载缓存使用，不参与备份与云同步（SyncManager 只导出 games/play_sessions/metadata_cache）。
 * 每个会话（好友/群）最多保留 {@link #MAX_CACHE_PER_PEER} 条，超出自动裁剪最旧消息。
 *
 * 所有方法在调用线程同步执行，应在 IO 线程调用。
 */
public class ChatCacheHelper {

    /** 每个会话最多缓存的条数（超出时裁剪最旧的） */
    public static final int MAX_CACHE_PER_PEER = 1000;

    // 全局共享一个 YukiDatabaseHelper（单例），避免多个连接并发 open/close
    private static YukiDatabaseHelper sHelper = null;
    private final YukiDatabaseHelper helper;

    public ChatCacheHelper(Context context) {
        if (sHelper == null) {
            synchronized (ChatCacheHelper.class) {
                if (sHelper == null) sHelper = new YukiDatabaseHelper(context.getApplicationContext());
            }
        }
        this.helper = sHelper;
    }

    public void close() {
        // 共享数据库，不在此关闭（由 App 生命周期管理）
    }

    // ==================== 好友私聊缓存 ====================

    /** 获取某好友最新的 limit 条缓存消息（正序：旧 → 新） */
    public List<ChatMessage> getFriendMessages(String friendId, int limit) {
        List<ChatMessage> list = new ArrayList<>();
        if (friendId == null || friendId.isEmpty()) return list;
        SQLiteDatabase db = helper.getReadableDatabase();
        Cursor c = null;
        try {
            c = db.rawQuery(
                    "SELECT id, sender_id, receiver_id, content, msg_type, created_at, is_mine, reply_to_id " +
                    "FROM friend_messages WHERE friend_id=? ORDER BY id DESC LIMIT ?",
                    new String[]{friendId, String.valueOf(Math.max(1, limit))});
            while (c.moveToNext()) list.add(readFriendMessage(c));
        } finally {
            if (c != null) c.close();
        }
        java.util.Collections.reverse(list);
        return list;
    }

    /** 获取某好友 id 小于 beforeId 的最新 limit 条缓存消息（正序，翻历史用） */
    public List<ChatMessage> getFriendMessagesBefore(String friendId, int beforeId, int limit) {
        List<ChatMessage> list = new ArrayList<>();
        if (friendId == null || friendId.isEmpty() || beforeId <= 0) return list;
        SQLiteDatabase db = helper.getReadableDatabase();
        Cursor c = null;
        try {
            c = db.rawQuery(
                    "SELECT id, sender_id, receiver_id, content, msg_type, created_at, is_mine, reply_to_id " +
                    "FROM friend_messages WHERE friend_id=? AND id<? ORDER BY id DESC LIMIT ?",
                    new String[]{friendId, String.valueOf(beforeId), String.valueOf(Math.max(1, limit))});
            while (c.moveToNext()) list.add(readFriendMessage(c));
        } finally {
            if (c != null) c.close();
        }
        java.util.Collections.reverse(list);
        return list;
    }

    /** 某好友缓存中 id 小于 beforeId 的消息条数（判断本地是否还能翻更早的） */
    public int countFriendMessagesBefore(String friendId, int beforeId) {
        if (friendId == null || friendId.isEmpty() || beforeId <= 0) return 0;
        SQLiteDatabase db = helper.getReadableDatabase();
        Cursor c = null;
        try {
            c = db.rawQuery(
                    "SELECT COUNT(*) FROM friend_messages WHERE friend_id=? AND id<?",
                    new String[]{friendId, String.valueOf(beforeId)});
            return c.moveToNext() ? c.getInt(0) : 0;
        } finally {
            if (c != null) c.close();
        }
    }

    /** 某好友缓存的最大消息 id（0 = 无缓存） */
    public int getFriendMaxId(String friendId) {
        if (friendId == null || friendId.isEmpty()) return 0;
        SQLiteDatabase db = helper.getReadableDatabase();
        Cursor c = null;
        try {
            c = db.rawQuery("SELECT MAX(id) FROM friend_messages WHERE friend_id=?",
                    new String[]{friendId});
            return c.moveToNext() ? c.getInt(0) : 0;
        } finally {
            if (c != null) c.close();
        }
    }

    /** 批量写入/更新好友消息（按服务端 id 去重覆盖） */
    public void upsertFriendMessages(String friendId, List<ChatMessage> msgs) {
        if (friendId == null || msgs == null || msgs.isEmpty()) return;
        SQLiteDatabase db = helper.getWritableDatabase();
        try {
            db.beginTransaction();
            for (ChatMessage m : msgs) {
                if (m == null || m.id <= 0) continue;
                db.execSQL(
                        "INSERT OR REPLACE INTO friend_messages" +
                        "(id, friend_id, sender_id, receiver_id, content, msg_type, created_at, is_mine, reply_to_id) " +
                        "VALUES(?,?,?,?,?,?,?,?,?)",
                        new Object[]{m.id, friendId, m.senderId, m.receiverId, m.content, m.msgType, m.createdAt, m.isMine ? 1 : 0, m.replyToId});
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /** 裁剪：只保留每个好友最新的 MAX_CACHE_PER_PEER 条 */
    public void pruneFriendMessages(String friendId) {
        if (friendId == null || friendId.isEmpty()) return;
        SQLiteDatabase db = helper.getWritableDatabase();
        try {
            db.execSQL(
                    "DELETE FROM friend_messages WHERE friend_id=? AND id NOT IN " +
                    "(SELECT id FROM friend_messages WHERE friend_id=? ORDER BY id DESC LIMIT ?)",
                    new Object[]{friendId, friendId, MAX_CACHE_PER_PEER});
        } finally {
        }
    }

    private ChatMessage readFriendMessage(Cursor c) {
        ChatMessage m = new ChatMessage();
        m.id = c.getInt(0);
        m.senderId = c.getString(1);
        m.receiverId = c.getString(2);
        m.content = c.getString(3);
        m.msgType = c.getString(4);
        m.createdAt = c.getString(5);
        m.isMine = c.getInt(6) != 0;
        m.replyToId = c.getColumnCount() > 7 ? c.getInt(7) : 0;
        return m;
    }

    // ==================== 群聊缓存 ====================

    /** 获取某群最新的 limit 条缓存消息（正序：旧 → 新） */
    public List<GroupMessage> getGroupMessages(int groupId, int limit) {
        List<GroupMessage> list = new ArrayList<>();
        SQLiteDatabase db = helper.getReadableDatabase();
        Cursor c = null;
        try {
            c = db.rawQuery(
                    "SELECT id, group_id, sender_id, sender_nickname, sender_avatar, sender_uid, " +
                    "sender_is_admin, content, msg_type, created_at, recalled, is_mine, reply_to_id " +
                    "FROM group_messages_cache WHERE group_id=? ORDER BY id DESC LIMIT ?",
                    new String[]{String.valueOf(groupId), String.valueOf(Math.max(1, limit))});
            while (c.moveToNext()) list.add(readGroupMessage(c));
        } finally {
            if (c != null) c.close();
        }
        java.util.Collections.reverse(list);
        return list;
    }

    /** 获取某群 id 小于 beforeId 的最新 limit 条缓存消息（正序，翻历史用） */
    public List<GroupMessage> getGroupMessagesBefore(int groupId, int beforeId, int limit) {
        List<GroupMessage> list = new ArrayList<>();
        if (beforeId <= 0) return list;
        SQLiteDatabase db = helper.getReadableDatabase();
        Cursor c = null;
        try {
            c = db.rawQuery(
                    "SELECT id, group_id, sender_id, sender_nickname, sender_avatar, sender_uid, " +
                    "sender_is_admin, content, msg_type, created_at, recalled, is_mine, reply_to_id " +
                    "FROM group_messages_cache WHERE group_id=? AND id<? ORDER BY id DESC LIMIT ?",
                    new String[]{String.valueOf(groupId), String.valueOf(beforeId), String.valueOf(Math.max(1, limit))});
            while (c.moveToNext()) list.add(readGroupMessage(c));
        } finally {
            if (c != null) c.close();
        }
        java.util.Collections.reverse(list);
        return list;
    }

    /** 某群缓存中 id 小于 beforeId 的消息条数（判断本地是否还能翻更早的） */
    public int countGroupMessagesBefore(int groupId, int beforeId) {
        if (beforeId <= 0) return 0;
        SQLiteDatabase db = helper.getReadableDatabase();
        Cursor c = null;
        try {
            c = db.rawQuery(
                    "SELECT COUNT(*) FROM group_messages_cache WHERE group_id=? AND id<?",
                    new String[]{String.valueOf(groupId), String.valueOf(beforeId)});
            return c.moveToNext() ? c.getInt(0) : 0;
        } finally {
            if (c != null) c.close();
        }
    }

    /** 某群缓存的最大消息 id（0 = 无缓存） */
    public int getGroupMaxId(int groupId) {
        SQLiteDatabase db = helper.getReadableDatabase();
        Cursor c = null;
        try {
            c = db.rawQuery("SELECT MAX(id) FROM group_messages_cache WHERE group_id=?",
                    new String[]{String.valueOf(groupId)});
            return c.moveToNext() ? c.getInt(0) : 0;
        } finally {
            if (c != null) c.close();
        }
    }

    /** 批量写入/更新群聊消息（按服务端 id 去重覆盖，含撤回/删除状态） */
    public void upsertGroupMessages(int groupId, List<GroupMessage> msgs) {
        if (msgs == null || msgs.isEmpty()) return;
        SQLiteDatabase db = helper.getWritableDatabase();
        try {
            db.beginTransaction();
            for (GroupMessage m : msgs) {
                if (m == null || m.id <= 0) continue;
                // 单条写入失败不能连累整批：事务一旦回滚，本次拉到的新消息
                // 全部丢失，界面就会一直停在旧缓存上（曾因此出过严重 bug）
                try {
                    if (m.deleted) {
                        // 已删除：从本地缓存移除（保证管理员删除对普通用户缓存生效）
                        db.execSQL("DELETE FROM group_messages_cache WHERE group_id=? AND id=?",
                                new Object[]{groupId, m.id});
                    } else {
                        db.execSQL(
                                "INSERT OR REPLACE INTO group_messages_cache" +
                                "(id, group_id, sender_id, sender_nickname, sender_avatar, sender_uid, " +
                                "sender_is_admin, content, msg_type, created_at, recalled, is_mine, reply_to_id) " +
                                "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                                new Object[]{m.id, groupId, m.senderId, m.senderNickname, m.senderAvatar,
                                        m.senderUid, m.senderIsAdmin ? 1 : 0, m.content, m.msgType,
                                        m.createdAt, m.recalled ? 1 : 0, m.isMine ? 1 : 0, m.replyToId});
                    }
                } catch (Throwable perRow) {
                    // 跳过这一条，继续写后面的
                }
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /** 裁剪：只保留每个群最新的 MAX_CACHE_PER_PEER 条 */
    public void pruneGroupMessages(int groupId) {
        SQLiteDatabase db = helper.getWritableDatabase();
        try {
            db.execSQL(
                    "DELETE FROM group_messages_cache WHERE group_id=? AND id NOT IN " +
                    "(SELECT id FROM group_messages_cache WHERE group_id=? ORDER BY id DESC LIMIT ?)",
                    new Object[]{groupId, groupId, MAX_CACHE_PER_PEER});
        } finally {
        }
    }

    /** 群消息撤回：将本地缓存更新为撤回状态（用于管理员撤回后即时同步） */
    public void markGroupMessageRecalled(int groupId, int messageId) {
        if (messageId <= 0) return;
        SQLiteDatabase db = helper.getWritableDatabase();
        try {
            db.execSQL("UPDATE group_messages_cache SET recalled=1, content='' WHERE group_id=? AND id=?",
                    new Object[]{groupId, messageId});
        } finally {
        }
    }

    /** 群消息删除：从本地缓存移除（用于管理员删除后即时同步） */
    public void deleteGroupMessage(int groupId, int messageId) {
        if (messageId <= 0) return;
        SQLiteDatabase db = helper.getWritableDatabase();
        try {
            db.execSQL("DELETE FROM group_messages_cache WHERE group_id=? AND id=?",
                    new Object[]{groupId, messageId});
        } finally {
        }
    }

    private GroupMessage readGroupMessage(Cursor c) {
        GroupMessage m = new GroupMessage();
        m.id = c.getInt(0);
        m.senderId = c.getString(2);
        m.senderNickname = c.getString(3);
        m.senderAvatar = c.getString(4);
        m.senderUid = c.getInt(5);
        m.senderIsAdmin = c.getInt(6) != 0;
        m.content = c.getString(7);
        m.msgType = c.getString(8);
        m.createdAt = c.getString(9);
        m.recalled = c.getInt(10) != 0;
        m.isMine = c.getInt(11) != 0;
        m.replyToId = c.getColumnCount() > 12 ? c.getInt(12) : 0;
        // senderLevel 不入缓存：等级会变，缓存值必然过期，一律用服务端实时值
        return m;
    }

    // ==================== 会话已读锚点（跳到未读起点） ====================

    /** 好友会话 key */
    private static String friendKey(String friendId) { return "f:" + friendId; }

    /** 群会话 key */
    private static String groupKey(int groupId) { return "g:" + groupId; }

    /**
     * 读取会话上次离开时读到的消息 id（0 = 无记录，视为全部已读）。
     */
    public int getLastReadId(String peerKey) {
        if (peerKey == null || peerKey.isEmpty()) return 0;
        SQLiteDatabase db = helper.getReadableDatabase();
        Cursor c = null;
        try {
            c = db.rawQuery("SELECT last_read_id FROM chat_read_marks WHERE peer_key=?",
                    new String[]{peerKey});
            return c.moveToNext() ? c.getInt(0) : 0;
        } catch (Throwable t) {
            return 0;
        } finally {
            if (c != null) c.close();
        }
    }

    public int getFriendLastReadId(String friendId) {
        return friendId == null ? 0 : getLastReadId(friendKey(friendId));
    }

    public int getGroupLastReadId(int groupId) {
        return getLastReadId(groupKey(groupId));
    }

    /**
     * 更新会话已读锚点（只前进不后退，避免翻历史时把锚点拉回去）。
     */
    public void setLastReadId(String peerKey, int messageId) {
        if (peerKey == null || peerKey.isEmpty() || messageId <= 0) return;
        SQLiteDatabase db = helper.getWritableDatabase();
        try {
            db.execSQL(
                    "INSERT INTO chat_read_marks(peer_key, last_read_id, updated_at) VALUES(?,?,?) " +
                    "ON CONFLICT(peer_key) DO UPDATE SET " +
                    "last_read_id = MAX(last_read_id, excluded.last_read_id), " +
                    "updated_at = excluded.updated_at",
                    new Object[]{peerKey, messageId, System.currentTimeMillis()});
        } catch (Throwable t) {
            // 兼容不支持 UPSERT 的老 SQLite（Android 10 以下部分设备）
            try {
                int current = getLastReadId(peerKey);
                if (messageId <= current) return;
                db.execSQL("INSERT OR REPLACE INTO chat_read_marks(peer_key, last_read_id, updated_at) VALUES(?,?,?)",
                        new Object[]{peerKey, messageId, System.currentTimeMillis()});
            } catch (Throwable ignored) {}
        }
    }

    public void setFriendLastReadId(String friendId, int messageId) {
        if (friendId != null) setLastReadId(friendKey(friendId), messageId);
    }

    public void setGroupLastReadId(int groupId, int messageId) {
        setLastReadId(groupKey(groupId), messageId);
    }
}
