package com.yuki.yukihub.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import org.json.JSONArray;
import org.json.JSONObject;

import com.yuki.yukihub.model.EngineType;
import com.yuki.yukihub.model.Game;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.UUID;
import java.util.List;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.Map;

public class GameRepository {
    private final YukiDatabaseHelper helper;

    public GameRepository(Context context) {
        helper = new YukiDatabaseHelper(context.getApplicationContext());
    }

    public List<Game> getAll() {
        return getAll("last_played_at DESC, created_at DESC");
    }

    public List<Game> getAll(String orderBy) {
        List<Game> list = new ArrayList<>();
        SQLiteDatabase db = helper.getReadableDatabase();
        Cursor c = db.query("games", null, "hidden=0", null, null, null, orderBy == null || orderBy.trim().isEmpty() ? "last_played_at DESC, created_at DESC" : orderBy);
        try {
            while (c.moveToNext()) list.add(fromCursor(c));
        } finally {
            c.close();
        }
        return list;
    }

    public long insert(Game game) {
        SQLiteDatabase db = helper.getWritableDatabase();
        long now = System.currentTimeMillis();
        game.createdAt = game.createdAt == 0 ? now : game.createdAt;
        game.updatedAt = now;
        long id = db.insert("games", null, toValues(game));
        game.id = id;
        return id;
    }

    public boolean existsByRootUri(String rootUri) {
        if (rootUri == null || rootUri.trim().isEmpty()) return false;
        SQLiteDatabase db = helper.getReadableDatabase();
        String key = normalizeRootUriKey(rootUri);
        Cursor c = db.rawQuery("SELECT root_uri FROM games WHERE root_uri IS NOT NULL AND root_uri != ''", null);
        try {
            while (c.moveToNext()) {
                if (key.equals(normalizeRootUriKey(c.getString(0)))) return true;
            }
            return false;
        } finally {
            c.close();
        }
    }

    public Set<String> getRootUriSet() {
        Set<String> set = new HashSet<>();
        SQLiteDatabase db = helper.getReadableDatabase();
        Cursor c = db.rawQuery("SELECT root_uri FROM games WHERE root_uri IS NOT NULL AND root_uri != ''", null);
        try {
            while (c.moveToNext()) set.add(c.getString(0));
        } finally {
            c.close();
        }
        return set;
    }

    public Set<String> getRootUriKeySet() {
        Set<String> set = new HashSet<>();
        SQLiteDatabase db = helper.getReadableDatabase();
        Cursor c = db.rawQuery("SELECT root_uri FROM games WHERE root_uri IS NOT NULL AND root_uri != ''", null);
        try {
            while (c.moveToNext()) set.add(normalizeRootUriKey(c.getString(0)));
        } finally {
            c.close();
        }
        return set;
    }

    public long insertIfNotExists(Game game) {
        if (game == null || game.rootUri == null || game.rootUri.trim().isEmpty()) return -1;
        if (existsByRootUri(game.rootUri)) return -1;
        return insert(game);
    }

    public int update(Game game) {
        SQLiteDatabase db = helper.getWritableDatabase();
        game.updatedAt = System.currentTimeMillis();
        return db.update("games", toValues(game), "id=?", new String[]{String.valueOf(game.id)});
    }

    public int delete(long id) {
        SQLiteDatabase db = helper.getWritableDatabase();
        return db.delete("games", "id=?", new String[]{String.valueOf(id)});
    }

    public long startPlaySession(long gameId, long start, String launchType) {
        SQLiteDatabase db = helper.getWritableDatabase();
        ContentValues session = new ContentValues();
        session.put("game_id", gameId);
        session.put("start_time", start);
        session.putNull("end_time");
        session.put("duration", 0L);
        session.put("launch_type", launchType == null ? "external" : launchType);
        session.put("session_uuid", UUID.randomUUID().toString());
        session.put("device_id", "local");
        session.put("created_at", start);
        session.put("updated_at", start);
        session.put("dirty", 1);
        session.put("deleted", 0);
        return db.insert("play_sessions", null, session);
    }

    public void cancelPlaySession(long sessionId) {
        if (sessionId <= 0) return;
        SQLiteDatabase db = helper.getWritableDatabase();
        db.delete("play_sessions", "id=? AND (end_time IS NULL OR duration=0)", new String[]{String.valueOf(sessionId)});
    }

    public void finishPlaySession(long sessionId, long end, long minDuration, long maxDuration) {
        if (sessionId <= 0) return;
        SQLiteDatabase db = helper.getWritableDatabase();
        Cursor c = db.rawQuery("SELECT game_id,start_time FROM play_sessions WHERE id=? AND end_time IS NULL LIMIT 1", new String[]{String.valueOf(sessionId)});
        try {
            if (!c.moveToFirst()) return;
            long gameId = c.getLong(0);
            long start = c.getLong(1);
            long rawDuration = Math.max(0L, end - start);
            if (rawDuration < minDuration) {
                db.delete("play_sessions", "id=?", new String[]{String.valueOf(sessionId)});
                return;
            }
            long duration = Math.min(rawDuration, maxDuration);
            ContentValues values = new ContentValues();
            values.put("end_time", end);
            values.put("duration", duration);
            values.put("updated_at", end);
            values.put("dirty", 1);
            db.update("play_sessions", values, "id=?", new String[]{String.valueOf(sessionId)});
            db.execSQL("UPDATE games SET total_play_time = total_play_time + ?, last_played_at = ?, updated_at = ? WHERE id = ?",
                    new Object[]{duration, end, end, gameId});
        } finally {
            c.close();
        }
    }

    public void finishUnfinishedPlaySessions(long end, long minDuration, long maxDuration) {
        finishUnfinishedPlaySessions(end, minDuration, maxDuration, -1L);
    }

    public void finishUnfinishedPlaySessions(long end, long minDuration, long maxDuration, long exceptSessionId) {
        SQLiteDatabase db = helper.getWritableDatabase();
        List<Long> ids = new ArrayList<>();
        Cursor c = db.rawQuery("SELECT id FROM play_sessions WHERE end_time IS NULL ORDER BY start_time ASC", null);
        try {
            while (c.moveToNext()) {
                long id = c.getLong(0);
                if (id != exceptSessionId) ids.add(id);
            }
        } finally {
            c.close();
        }
        for (Long id : ids) finishPlaySession(id, end, minDuration, maxDuration);
    }

    public PlayActivity findLatestOpenPlaySession() {
        SQLiteDatabase db = helper.getReadableDatabase();
        Cursor c = db.rawQuery(
                "SELECT ps.id,ps.session_uuid,ps.game_id,g.title,ps.start_time,ps.end_time,ps.duration,ps.launch_type " +
                        "FROM play_sessions ps JOIN games g ON g.id=ps.game_id " +
                        "WHERE ps.end_time IS NULL AND IFNULL(ps.deleted,0)=0 " +
                        "ORDER BY ps.start_time DESC LIMIT 1", null);
        try {
            if (!c.moveToFirst()) return null;
            PlayActivity a = new PlayActivity();
            a.sessionId = c.getLong(0);
            a.sessionUuid = c.getString(1);
            a.gameId = c.getLong(2);
            a.gameTitle = c.getString(3);
            a.startTime = c.getLong(4);
            a.endTime = 0L;
            a.duration = c.getLong(6);
            a.launchType = c.getString(7);
            if (a.gameTitle == null || a.gameTitle.trim().isEmpty()) a.gameTitle = "未命名游戏";
            return a;
        } finally {
            c.close();
        }
    }

    public int deleteOpenPlaySessions() {
        SQLiteDatabase db = helper.getWritableDatabase();
        return db.delete("play_sessions", "end_time IS NULL", null);
    }

    public int deleteOpenPlaySession(long sessionId) {
        if (sessionId <= 0) return 0;
        SQLiteDatabase db = helper.getWritableDatabase();
        return db.delete("play_sessions", "id=? AND end_time IS NULL", new String[]{String.valueOf(sessionId)});
    }

    public void addPlayTime(long gameId, long start, long end, long duration) {
        if (duration <= 0) duration = Math.max(0L, end - start);
        addManualPlayTime(gameId, duration, end <= 0 ? System.currentTimeMillis() : end);
    }

    public void addManualPlayTime(long gameId, long duration) {
        addManualPlayTime(gameId, duration, System.currentTimeMillis());
    }

    public void addManualPlayTime(long gameId, long duration, long end) {
        if (gameId <= 0 || duration <= 0) return;
        SQLiteDatabase db = helper.getWritableDatabase();
        long now = System.currentTimeMillis();
        long safeEnd = end <= 0 ? now : end;
        long start = Math.max(0L, safeEnd - duration);
        ContentValues session = new ContentValues();
        session.put("game_id", gameId);
        session.put("start_time", start);
        session.put("end_time", safeEnd);
        session.put("duration", duration);
        session.put("launch_type", "manual");
        session.put("session_uuid", UUID.randomUUID().toString());
        session.put("device_id", "local");
        session.put("created_at", now);
        session.put("updated_at", now);
        session.put("dirty", 1);
        session.put("deleted", 0);
        db.insert("play_sessions", null, session);
        db.execSQL("UPDATE games SET total_play_time = total_play_time + ?, last_played_at = MAX(IFNULL(last_played_at,0), ?), updated_at = ? WHERE id = ?",
                new Object[]{duration, safeEnd, now, gameId});
    }

    public void setManualPlayTimeForGame(long gameId, long totalDuration) {
        if (gameId <= 0) return;
        SQLiteDatabase db = helper.getWritableDatabase();
        long now = System.currentTimeMillis();
        long safeDuration = Math.max(0L, totalDuration);
        db.delete("play_sessions", "game_id=?", new String[]{String.valueOf(gameId)});
        long lastPlayed = 0L;
        if (safeDuration > 0) {
            lastPlayed = now;
            long start = Math.max(0L, now - safeDuration);
            ContentValues session = new ContentValues();
            session.put("game_id", gameId);
            session.put("start_time", start);
            session.put("end_time", now);
            session.put("duration", safeDuration);
            session.put("launch_type", "manual");
            session.put("session_uuid", UUID.randomUUID().toString());
            session.put("device_id", "local");
            session.put("created_at", now);
            session.put("updated_at", now);
            session.put("dirty", 1);
            session.put("deleted", 0);
            db.insert("play_sessions", null, session);
        }
        ContentValues v = new ContentValues();
        v.put("total_play_time", safeDuration);
        v.put("last_played_at", lastPlayed);
        v.put("playtime_reset_at", now);
        v.put("updated_at", now);
        db.update("games", v, "id=?", new String[]{String.valueOf(gameId)});
    }

    public static class PlayActivity {
        public long sessionId;
        public String sessionUuid;
        public long gameId;
        public String gameTitle;
        public long startTime;
        public long endTime;
        public long duration;
        public String launchType;
        public String playStatus;
    }

    public Map<String, Long> getPlayDurationsBetween(long startInclusive, long endExclusive) {
        LinkedHashMap<String, Long> result = new LinkedHashMap<>();
        SQLiteDatabase db = helper.getReadableDatabase();
        Cursor c = db.rawQuery(
                "SELECT g.title, SUM(ps.duration) FROM play_sessions ps " +
                        "JOIN games g ON g.id=ps.game_id " +
                        "WHERE ps.end_time IS NOT NULL AND ps.end_time>=? AND ps.end_time<? AND IFNULL(ps.deleted,0)=0 " +
                        "GROUP BY ps.game_id ORDER BY MAX(ps.end_time) DESC",
                new String[]{String.valueOf(startInclusive), String.valueOf(endExclusive)});
        try {
            while (c.moveToNext()) {
                String title = c.getString(0);
                long duration = c.getLong(1);
                result.put(title == null || title.trim().isEmpty() ? "未命名游戏" : title, duration);
            }
        } finally {
            c.close();
        }
        return result;
    }

    public List<PlayActivity> getRecentPlayActivities(int limit) {
        List<PlayActivity> list = new ArrayList<>();
        SQLiteDatabase db = helper.getReadableDatabase();
        Cursor c = db.rawQuery(
                "SELECT ps.id,ps.session_uuid,ps.game_id,g.title,ps.start_time,ps.end_time,ps.duration,ps.launch_type,g.play_status " +
                        "FROM play_sessions ps JOIN games g ON g.id=ps.game_id " +
                        "WHERE ps.end_time IS NOT NULL AND IFNULL(ps.deleted,0)=0 " +
                        "ORDER BY ps.end_time DESC LIMIT ?",
                new String[]{String.valueOf(Math.max(1, limit))});
        try {
            while (c.moveToNext()) {
                PlayActivity a = new PlayActivity();
                a.sessionId = c.getLong(0);
                a.sessionUuid = c.getString(1);
                a.gameId = c.getLong(2);
                a.gameTitle = c.getString(3);
                a.startTime = c.getLong(4);
                a.endTime = c.getLong(5);
                a.duration = c.getLong(6);
                a.launchType = c.getString(7);
                a.playStatus = normalizePlayStatus(c.getString(8));
                if (a.gameTitle == null || a.gameTitle.trim().isEmpty()) a.gameTitle = "未命名游戏";
                list.add(a);
            }
        } finally {
            c.close();
        }
        return list;
    }

    public List<PlayActivity> getPlayActivitiesBetween(long startInclusive, long endExclusive, int limit) {
        List<PlayActivity> list = new ArrayList<>();
        SQLiteDatabase db = helper.getReadableDatabase();
        Cursor c = db.rawQuery(
                "SELECT ps.id,ps.session_uuid,ps.game_id,g.title,ps.start_time,ps.end_time,ps.duration,ps.launch_type,g.play_status " +
                        "FROM play_sessions ps JOIN games g ON g.id=ps.game_id " +
                        "WHERE ps.end_time IS NOT NULL AND ps.end_time>=? AND ps.end_time<? AND IFNULL(ps.deleted,0)=0 " +
                        "ORDER BY ps.end_time DESC LIMIT ?",
                new String[]{String.valueOf(startInclusive), String.valueOf(endExclusive), String.valueOf(Math.max(1, limit))});
        try {
            while (c.moveToNext()) {
                PlayActivity a = new PlayActivity();
                a.sessionId = c.getLong(0);
                a.sessionUuid = c.getString(1);
                a.gameId = c.getLong(2);
                a.gameTitle = c.getString(3);
                a.startTime = c.getLong(4);
                a.endTime = c.getLong(5);
                a.duration = c.getLong(6);
                a.launchType = c.getString(7);
                a.playStatus = normalizePlayStatus(c.getString(8));
                if (a.gameTitle == null || a.gameTitle.trim().isEmpty()) a.gameTitle = "未命名游戏";
                list.add(a);
            }
        } finally {
            c.close();
        }
        return list;
    }


    public JSONArray exportGamesJson() throws Exception {
        JSONArray arr = new JSONArray();
        SQLiteDatabase db = helper.getReadableDatabase();
        Cursor c = db.query("games", null, null, null, null, null, "id ASC");
        try {
            while (c.moveToNext()) {
                JSONObject o = new JSONObject();
                long id = c.getLong(c.getColumnIndexOrThrow("id"));
                o.put("local_id", id);
                o.put("title", c.getString(c.getColumnIndexOrThrow("title")));
                o.put("original_title", c.getString(c.getColumnIndexOrThrow("original_title")));
                o.put("engine", c.getString(c.getColumnIndexOrThrow("engine")));
                o.put("root_uri", c.getString(c.getColumnIndexOrThrow("root_uri")));
                o.put("cover_uri", c.getString(c.getColumnIndexOrThrow("cover_uri")));
                o.put("cover_persist_uri", getStringOrNull(c, "cover_persist_uri"));
                o.put("cover_source_type", getIntOrDefault(c, "cover_source_type", 0));
                o.put("emulator_package", c.getString(c.getColumnIndexOrThrow("emulator_package")));
                o.put("launch_target", getStringOrNull(c, "launch_target"));
o.put("winlator_launch_mode", getStringOrNull(c, "winlator_launch_mode"));
o.put("description", c.getString(c.getColumnIndexOrThrow("description")));
                o.put("tags", c.getString(c.getColumnIndexOrThrow("tags")));
                String gamehubId = getStringOrNull(c, "gamehub_local_game_id");
                if (gamehubId == null || gamehubId.isEmpty()) gamehubId = getStringOrNull(c, "gaishi_local_game_id");
                o.put("gamehub_local_game_id", gamehubId);
                o.put("gaishi_local_game_id", gamehubId);
                o.put("gamehub_launch_mode", normalizeGameHubLaunchMode(getStringOrNull(c, "gamehub_launch_mode")));
                o.put("play_status", normalizePlayStatus(getStringOrNull(c, "play_status")));
                o.put("total_play_time", c.getLong(c.getColumnIndexOrThrow("total_play_time")));
                o.put("last_played_at", c.getLong(c.getColumnIndexOrThrow("last_played_at")));
                o.put("playtime_reset_at", getLongOrDefault(c, "playtime_reset_at", 0L));
                o.put("created_at", c.getLong(c.getColumnIndexOrThrow("created_at")));
                o.put("updated_at", c.getLong(c.getColumnIndexOrThrow("updated_at")));
                o.put("hidden", c.getInt(c.getColumnIndexOrThrow("hidden")) == 1);
                o.put("favorite", c.getInt(c.getColumnIndexOrThrow("favorite")) == 1);
                arr.put(o);
            }
        } finally {
            c.close();
        }
        return arr;
    }

    public JSONArray exportPlaySessionsJson() throws Exception {
        JSONArray arr = new JSONArray();
        SQLiteDatabase db = helper.getReadableDatabase();
        Cursor c = db.rawQuery("SELECT ps.*, g.root_uri, g.title AS game_title, g.engine AS game_engine, g.emulator_package AS game_emulator_package, g.gamehub_local_game_id AS gamehub_local_game_id, g.gaishi_local_game_id AS gaishi_local_game_id FROM play_sessions ps LEFT JOIN games g ON g.id=ps.game_id WHERE IFNULL(ps.deleted,0)=0 AND COALESCE(ps.end_time,ps.start_time,0)>=IFNULL(g.playtime_reset_at,0) ORDER BY ps.start_time ASC", null);
        try {
            while (c.moveToNext()) {
                JSONObject o = new JSONObject();
                o.put("session_uuid", getStringOrNull(c, "session_uuid"));
                o.put("game_local_id", c.getLong(c.getColumnIndexOrThrow("game_id")));
                o.put("game_root_uri", getStringOrNull(c, "root_uri"));
                String sessionGameHubId = getStringOrNull(c, "gamehub_local_game_id");
                if (sessionGameHubId == null || sessionGameHubId.isEmpty()) sessionGameHubId = getStringOrNull(c, "gaishi_local_game_id");
                o.put("gamehub_local_game_id", sessionGameHubId);
                o.put("gaishi_local_game_id", sessionGameHubId);
                o.put("game_title", getStringOrNull(c, "game_title"));
                o.put("game_engine", getStringOrNull(c, "game_engine"));
                o.put("game_emulator_package", getStringOrNull(c, "game_emulator_package"));
                o.put("start_time", c.getLong(c.getColumnIndexOrThrow("start_time")));
                int endIdx = c.getColumnIndex("end_time");
                if (endIdx >= 0 && !c.isNull(endIdx)) o.put("end_time", c.getLong(endIdx));
                o.put("duration", c.getLong(c.getColumnIndexOrThrow("duration")));
                o.put("launch_type", getStringOrNull(c, "launch_type"));
                o.put("device_id", getStringOrNull(c, "device_id"));
                o.put("created_at", getLongOrDefault(c, "created_at", c.getLong(c.getColumnIndexOrThrow("start_time"))));
                o.put("updated_at", getLongOrDefault(c, "updated_at", c.getLong(c.getColumnIndexOrThrow("start_time"))));
                arr.put(o);
            }
        } finally {
            c.close();
        }
        return arr;
    }

    public int importGamesJson(JSONArray arr) throws Exception {
        if (arr == null) return 0;
        int changed = 0;
        SQLiteDatabase db = helper.getWritableDatabase();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String rootUri = o.optString("root_uri", "").trim();
            Game g = findBySyncIdentity(o, rootUri);
            boolean exists = g != null;
            if (g == null) g = new Game();
            long existingUpdatedAt = exists ? Math.max(0L, g.updatedAt) : 0L;
            long incomingUpdatedAt = Math.max(0L, o.optLong("updated_at", existingUpdatedAt));
            boolean applyDetails = !exists || incomingUpdatedAt >= existingUpdatedAt;
            if (applyDetails) {
                g.title = o.optString("title", g.title == null ? "未命名游戏" : g.title);
                g.originalTitle = o.optString("original_title", g.originalTitle);
                g.engine = EngineType.fromString(o.optString("engine", g.engine == null ? EngineType.UNKNOWN.name() : g.engine.name()));
                if (!rootUri.isEmpty() || g.rootUri == null || g.rootUri.trim().isEmpty()) g.rootUri = rootUri;
                g.coverUri = o.optString("cover_uri", g.coverUri);
                g.coverPersistUri = o.optString("cover_persist_uri", g.coverPersistUri);
                g.coverSourceType = o.optInt("cover_source_type", g.coverSourceType);
                g.emulatorPackage = o.optString("emulator_package", g.emulatorPackage);
                g.launchTarget = o.optString("launch_target", g.launchTarget);
                g.winlatorLaunchMode = normalizeWinlatorLaunchMode(o.optString("winlator_launch_mode", g.winlatorLaunchMode));
                g.description = o.optString("description", g.description);
                g.tags = o.optString("tags", g.tags);
                g.gamehubLocalGameId = o.optString("gamehub_local_game_id", o.optString("gaishi_local_game_id", g.gamehubLocalGameId));
                g.gamehubLaunchMode = normalizeGameHubLaunchMode(o.optString("gamehub_launch_mode", g.gamehubLaunchMode));
                g.playStatus = normalizePlayStatus(o.optString("play_status", g.playStatus));
                g.hidden = o.optBoolean("hidden", g.hidden);
                g.favorite = o.optBoolean("favorite", g.favorite);
            } else {
                // Older card metadata must not overwrite newer local edits, but it may
                // still fill identity fields that are missing locally.
                if ((g.rootUri == null || g.rootUri.trim().isEmpty()) && !rootUri.isEmpty()) g.rootUri = rootUri;
                if ((g.gamehubLocalGameId == null || g.gamehubLocalGameId.trim().isEmpty())) {
                    g.gamehubLocalGameId = o.optString("gamehub_local_game_id", o.optString("gaishi_local_game_id", g.gamehubLocalGameId));
                }
            }
            if (g.title == null || g.title.trim().isEmpty()) g.title = "未命名游戏";
            if (g.engine == null) g.engine = EngineType.UNKNOWN;
            long incomingResetAt = Math.max(0L, o.optLong("playtime_reset_at", 0L));
            long incomingTotalPlayTime = Math.max(0L, o.optLong("total_play_time", 0L));
            long incomingLastPlayedAt = Math.max(0L, o.optLong("last_played_at", 0L));
            boolean resetAdvanced = incomingResetAt > g.playtimeResetAt;
            if (resetAdvanced) {
                // A newer reset/manual-set on another device is an explicit operation;
                // accept its aggregate value and discard older local sessions below.
                g.playtimeResetAt = incomingResetAt;
                g.totalPlayTime = incomingTotalPlayTime;
                g.lastPlayedAt = incomingLastPlayedAt;
            } else if (incomingResetAt == g.playtimeResetAt) {
                // Total play time is cumulative. During smart merge or normal download,
                // never let an older/empty device overwrite a larger aggregate with 0.
                g.totalPlayTime = Math.max(g.totalPlayTime, incomingTotalPlayTime);
                g.lastPlayedAt = Math.max(g.lastPlayedAt, incomingLastPlayedAt);
            } else {
                // Local reset is newer. Ignore incoming aggregate to avoid resurrecting
                // play time that was intentionally cleared locally. Newer sessions, if any,
                // can still be imported by importPlaySessionsJson() when they pass resetAt.
            }
            g.createdAt = o.optLong("created_at", g.createdAt);
            g.updatedAt = Math.max(g.updatedAt, o.optLong("updated_at", g.updatedAt));
            if (g.createdAt <= 0) g.createdAt = System.currentTimeMillis();
            if (g.updatedAt <= 0) g.updatedAt = g.createdAt;
            if (exists) {
                db.update("games", toValues(g), "id=?", new String[]{String.valueOf(g.id)});
            } else {
                long id = db.insert("games", null, toValues(g));
                g.id = id;
            }
            if (resetAdvanced && g.id > 0) {
                db.delete("play_sessions", "game_id=? AND (COALESCE(end_time,start_time,0) <= ?)", new String[]{String.valueOf(g.id), String.valueOf(g.playtimeResetAt)});
            }
            changed++;
        }
        recalculatePlayStats();
        return changed;
    }

    public int importPlaySessionsJson(JSONArray arr) throws Exception {
        if (arr == null) return 0;
        int changed = 0;
        SQLiteDatabase db = helper.getWritableDatabase();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String uuid = o.optString("session_uuid", "").trim();
            if (uuid.isEmpty()) uuid = UUID.randomUUID().toString();
            Cursor dup = db.rawQuery("SELECT id,updated_at FROM play_sessions WHERE session_uuid=? LIMIT 1", new String[]{uuid});
            long existingSessionId = -1L;
            long existingUpdatedAt = 0L;
            try {
                if (dup.moveToFirst()) {
                    existingSessionId = dup.getLong(0);
                    existingUpdatedAt = dup.getLong(1);
                }
            } finally { dup.close(); }
            String rootUri = o.optString("game_root_uri", "").trim();
            Game g = findByRootUri(rootUri);
            if (g == null) {
                JSONObject identity = new JSONObject();
                identity.put("root_uri", rootUri);
                identity.put("gamehub_local_game_id", o.optString("gamehub_local_game_id", o.optString("gaishi_local_game_id", "")));
                identity.put("gaishi_local_game_id", o.optString("gaishi_local_game_id", o.optString("gamehub_local_game_id", "")));
                identity.put("title", o.optString("game_title", ""));
                identity.put("engine", o.optString("game_engine", ""));
                identity.put("emulator_package", o.optString("game_emulator_package", ""));
                g = findBySyncIdentity(identity, rootUri);
            }
            if (g == null || g.id <= 0) continue;
            long endTime = o.has("end_time") && !o.isNull("end_time") ? o.optLong("end_time") : 0L;
            long startTime = o.optLong("start_time", 0L);
            long sessionTime = endTime > 0 ? endTime : startTime;
            if (g.playtimeResetAt > 0 && sessionTime > 0 && sessionTime < g.playtimeResetAt) continue;
            long incomingUpdatedAt = o.optLong("updated_at", System.currentTimeMillis());
            if (existingSessionId > 0 && incomingUpdatedAt < existingUpdatedAt) continue;
            ContentValues v = new ContentValues();
            v.put("game_id", g.id);
            v.put("start_time", o.optLong("start_time", 0));
            if (o.has("end_time") && !o.isNull("end_time")) v.put("end_time", o.optLong("end_time")); else v.putNull("end_time");
            v.put("duration", Math.max(0L, o.optLong("duration", 0)));
            v.put("launch_type", o.optString("launch_type", "external"));
            v.put("session_uuid", uuid);
            v.put("device_id", o.optString("device_id", "imported"));
            v.put("created_at", o.optLong("created_at", o.optLong("start_time", 0)));
            v.put("updated_at", incomingUpdatedAt);
            v.put("dirty", 1);
            v.put("deleted", 0);
            if (existingSessionId > 0) {
                db.update("play_sessions", v, "id=?", new String[]{String.valueOf(existingSessionId)});
            } else {
                db.insert("play_sessions", null, v);
            }
            changed++;
        }
        recalculatePlayStats();
        return changed;
    }

    public void recalculatePlayStats() {
        SQLiteDatabase db = helper.getWritableDatabase();
        // Preserve imported/manual aggregate play time when the synced snapshot only
        // contains a limited tail of play_sessions. Explicit clear/manual reset is
        // represented by a newer playtime_reset_at plus the aggregate value imported
        // in importGamesJson(), so this method must never blindly lower totals.
        db.execSQL("UPDATE games SET total_play_time=MAX(IFNULL(total_play_time,0),IFNULL((SELECT SUM(duration) FROM play_sessions WHERE game_id=games.id AND end_time IS NOT NULL AND IFNULL(deleted,0)=0 AND end_time>=IFNULL(games.playtime_reset_at,0)),0)), last_played_at=MAX(IFNULL(last_played_at,0),IFNULL((SELECT MAX(end_time) FROM play_sessions WHERE game_id=games.id AND end_time IS NOT NULL AND IFNULL(deleted,0)=0 AND end_time>=IFNULL(games.playtime_reset_at,0)),0))");
    }

    private Game findByRootUri(String rootUri) {
        if (rootUri == null || rootUri.trim().isEmpty()) return null;
        SQLiteDatabase db = helper.getReadableDatabase();
        String key = normalizeRootUriKey(rootUri);
        Cursor c = db.query("games", null, "root_uri IS NOT NULL AND root_uri != ''", null, null, null, null);
        try {
            while (c.moveToNext()) {
                Game g = fromCursor(c);
                if (key.equals(normalizeRootUriKey(g.rootUri))) return g;
            }
            return null;
        } finally {
            c.close();
        }
    }

    private Game findBySyncIdentity(JSONObject o, String rootUri) {
        Game byRoot = findByRootUri(rootUri);
        if (byRoot != null) return byRoot;
        if (o == null) return null;
        String gamehubId = o.optString("gamehub_local_game_id", o.optString("gaishi_local_game_id", "")).trim();
        if (!gamehubId.isEmpty()) {
            Game byGameHub = findByGameHubLocalId(gamehubId);
            if (byGameHub != null) return byGameHub;
        }
        return findByTitleForEmptyRoot(o.optString("title", "").trim());
    }

    private Game findByGameHubLocalId(String gamehubId) {
        if (gamehubId == null || gamehubId.trim().isEmpty()) return null;
        SQLiteDatabase db = helper.getReadableDatabase();
        Cursor c = db.query("games", null, "gamehub_local_game_id=? OR gaishi_local_game_id=?", new String[]{gamehubId, gamehubId}, null, null, "updated_at DESC", "1");
        try {
            if (!c.moveToFirst()) return null;
            return fromCursor(c);
        } finally {
            c.close();
        }
    }

    private Game findByTitleForEmptyRoot(String title) {
        if (title == null || title.trim().isEmpty()) return null;
        SQLiteDatabase db = helper.getReadableDatabase();
        Cursor c = db.query("games", null,
                "IFNULL(root_uri,'')='' AND IFNULL(title,'')=?",
                new String[]{title.trim()},
                null, null, "updated_at DESC", "1");
        try {
            if (!c.moveToFirst()) return null;
            return fromCursor(c);
        } finally {
            c.close();
        }
    }

    public boolean isEmpty() {
        SQLiteDatabase db = helper.getReadableDatabase();
        Cursor c = db.rawQuery("SELECT COUNT(*) FROM games", null);
        try {
            return c.moveToFirst() && c.getInt(0) == 0;
        } finally {
            c.close();
        }
    }

    public void insertSamplesIfEmpty() {
        // Production build should not auto-create placeholder games.
    }

    public int deleteSampleGames() {
        SQLiteDatabase db = helper.getWritableDatabase();
        return db.delete("games", "root_uri LIKE ?", new String[]{"sample://%"});
    }

    private ContentValues toValues(Game g) {
        ContentValues v = new ContentValues();
        v.put("title", nvl(g.title));
        v.put("original_title", g.originalTitle);
        v.put("engine", g.engine == null ? EngineType.UNKNOWN.name() : g.engine.name());
        v.put("root_uri", nvl(g.rootUri));
        v.put("cover_uri", g.coverUri);
        v.put("cover_persist_uri", g.coverPersistUri);
        v.put("cover_source_type", g.coverSourceType);
        v.put("emulator_package", g.emulatorPackage);
        v.put("launch_target", g.launchTarget == null || g.launchTarget.isEmpty() ? "[游戏目录]" : g.launchTarget);
v.put("winlator_launch_mode", normalizeWinlatorLaunchMode(g.winlatorLaunchMode));
v.put("description", g.description);
        v.put("tags", g.tags);
        v.put("gamehub_local_game_id", g.gamehubLocalGameId);
        v.put("gaishi_local_game_id", g.gamehubLocalGameId);
        v.put("gamehub_launch_mode", normalizeGameHubLaunchMode(g.gamehubLaunchMode));
        v.put("play_status", normalizePlayStatus(g.playStatus));
        v.put("total_play_time", g.totalPlayTime);
        v.put("last_played_at", g.lastPlayedAt);
        v.put("playtime_reset_at", g.playtimeResetAt);
        v.put("created_at", g.createdAt);
        v.put("updated_at", g.updatedAt);
        v.put("hidden", g.hidden ? 1 : 0);
        v.put("favorite", g.favorite ? 1 : 0);
        return v;
    }

    private Game fromCursor(Cursor c) {
        Game g = new Game();
        g.id = c.getLong(c.getColumnIndexOrThrow("id"));
        g.title = c.getString(c.getColumnIndexOrThrow("title"));
        g.originalTitle = c.getString(c.getColumnIndexOrThrow("original_title"));
        g.engine = EngineType.fromString(c.getString(c.getColumnIndexOrThrow("engine")));
        g.rootUri = c.getString(c.getColumnIndexOrThrow("root_uri"));
        g.coverUri = c.getString(c.getColumnIndexOrThrow("cover_uri"));
        g.coverPersistUri = getStringOrNull(c, "cover_persist_uri");
        g.coverSourceType = getIntOrDefault(c, "cover_source_type", 0);
        g.emulatorPackage = c.getString(c.getColumnIndexOrThrow("emulator_package"));
        g.launchTarget = getStringOrNull(c, "launch_target");
if (g.launchTarget == null || g.launchTarget.isEmpty()) g.launchTarget = "[游戏目录]";
g.winlatorLaunchMode = normalizeWinlatorLaunchMode(getStringOrNull(c, "winlator_launch_mode"));
g.description = c.getString(c.getColumnIndexOrThrow("description"));
        g.tags = c.getString(c.getColumnIndexOrThrow("tags"));
        g.gamehubLocalGameId = getStringOrNull(c, "gamehub_local_game_id");
        if (g.gamehubLocalGameId == null || g.gamehubLocalGameId.isEmpty()) g.gamehubLocalGameId = getStringOrNull(c, "gaishi_local_game_id");
        g.gamehubLaunchMode = normalizeGameHubLaunchMode(getStringOrNull(c, "gamehub_launch_mode"));
        g.playStatus = normalizePlayStatus(getStringOrNull(c, "play_status"));
        g.totalPlayTime = c.getLong(c.getColumnIndexOrThrow("total_play_time"));
        g.lastPlayedAt = c.getLong(c.getColumnIndexOrThrow("last_played_at"));
        g.playtimeResetAt = getLongOrDefault(c, "playtime_reset_at", 0L);
        g.createdAt = c.getLong(c.getColumnIndexOrThrow("created_at"));
        g.updatedAt = c.getLong(c.getColumnIndexOrThrow("updated_at"));
        g.hidden = c.getInt(c.getColumnIndexOrThrow("hidden")) == 1;
        int favoriteIndex = c.getColumnIndex("favorite");
        g.favorite = favoriteIndex >= 0 && !c.isNull(favoriteIndex) && c.getInt(favoriteIndex) == 1;
        return g;
    }

    private String getStringOrNull(Cursor c, String column) {
        int index = c.getColumnIndex(column);
        return index >= 0 ? c.getString(index) : null;
    }

    private int getIntOrDefault(Cursor c, String column, int def) {
        int index = c.getColumnIndex(column);
        return index >= 0 && !c.isNull(index) ? c.getInt(index) : def;
    }

    private long getLongOrDefault(Cursor c, String column, long def) {
        int index = c.getColumnIndex(column);
        return index >= 0 && !c.isNull(index) ? c.getLong(index) : def;
    }

    private String normalizePlayStatus(String status) {
        if (status == null) return "unplayed";
        String s = status.trim().toLowerCase();
        if ("completed".equals(s) || "played".equals(s) || "done".equals(s)) return "completed";
        if ("playing".equals(s) || "current".equals(s)) return "playing";
        return "unplayed";
    }

    private String normalizeWinlatorLaunchMode(String mode) {
        if (mode == null) return "game";
        String s = mode.trim().toLowerCase();
        if ("program".equals(s) || "normal".equals(s)) return "program";
        // 兼容旧数据：root/shizuku 曾表示强制直启游戏，现在统一迁移为 game。
        if ("root".equals(s) || "shizuku".equals(s) || "game".equals(s)) return "game";
        return "game";
    }

    private String normalizeGameHubLaunchMode(String mode) {
        if (mode == null) return "game";
        String s = mode.trim().toLowerCase();
        if ("program".equals(s) || "normal".equals(s)) return "program";
        return "game";
    }

    private String nvl(String value) {
        return value == null ? "" : value;
    }

    public static String normalizeRootUriKey(String value) {
        if (value == null) return "";
        String s = value.trim();
        if (s.startsWith("file://")) s = s.substring("file://".length());
        try {
            if (s.startsWith("content://")) {
                android.net.Uri uri = android.net.Uri.parse(s);
                String docId = null;
                try { docId = android.provider.DocumentsContract.getDocumentId(uri); } catch (Throwable ignored) { }
                if (docId == null || docId.isEmpty()) {
                    try { docId = android.provider.DocumentsContract.getTreeDocumentId(uri); } catch (Throwable ignored) { }
                }
                if (docId != null && !docId.isEmpty()) s = android.net.Uri.decode(docId);
            }
        } catch (Throwable ignored) { }
        while (s.contains("//")) s = s.replace("//", "/");
        while (s.endsWith("/") && s.length() > 1) s = s.substring(0, s.length() - 1);
        return s.toLowerCase(java.util.Locale.ROOT);
    }
}