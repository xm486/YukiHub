package com.yuki.yukihub.importer;

import android.content.ContentValues;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;

import com.yuki.yukihub.data.GameRepository;
import com.yuki.yukihub.data.YukiDatabaseHelper;
import com.yuki.yukihub.model.EngineType;
import com.yuki.yukihub.model.Game;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 导入服务：把三方平台解析出的 ImportGameData 列表写入 YukiHub 数据库。
 *
 * 导入策略：
 * - 按游戏标题去重（标题完全一致则跳过，不合并）
 * - 游戏路径（Windows 路径）不导入到 rootUri，留空
 * - 封面：如果有远程 URL 写入 coverUri 和 coverSourceType=1；如果有本地文件路径，复制到应用内部目录
 * - 游玩记录：PotatoVN 的 PlayedTime 转成 play_sessions；Vnite 的 Timers 转成 play_sessions
 * - 元数据：开发者、简介、标签写入 game.description 和 game.tags
 */
public class ImporterService {

    private final Context context;
    private final YukiDatabaseHelper dbHelper;

    public ImporterService(Context context) {
        this.context = context;
        this.dbHelper = new YukiDatabaseHelper(context.getApplicationContext());
    }

    /**
     * 预览：标记已存在的游戏
     */
    public void markExisting(List<ImportGameData> games) {
        GameRepository repo = new GameRepository(context);
        List<Game> existing = repo.getAll();
        Set<String> existingNames = new HashSet<>();
        for (Game g : existing) {
            if (g.title != null) existingNames.add(g.title.trim().toLowerCase());
        }
        for (ImportGameData g : games) {
            g.exists = existingNames.contains(g.name.toLowerCase());
            g.selected = !g.exists; // 默认：新游戏选中，已存在的不选
            g.conflictReason = g.exists ? "已存在" : null;
        }
    }

    /**
     * 导入进度回调（可选）。
     */
    public interface ProgressListener {
        void onProgress(int current, int total, String itemName);
    }

    /**
     * 只导入用户勾选的游戏（预览确认后调用）
     */
    public ImportResult importSelected(List<ImportGameData> games) {
        return importSelected(games, null);
    }

    /**
     * 只导入用户勾选的游戏（带进度回调）。
     */
    public ImportResult importSelected(List<ImportGameData> games, ProgressListener listener) {
        List<ImportGameData> selected = new ArrayList<>();
        for (ImportGameData g : games) {
            if (g.selected && !g.exists) selected.add(g);
        }
        return importGames(selected, true, listener);
    }

    /**
     * 执行导入（在后台线程调用）
     */
    public ImportResult importGames(List<ImportGameData> games, boolean skipExisting) {
        return importGames(games, skipExisting, null);
    }

    /**
     * 执行导入（在后台线程调用）
     */
    public ImportResult importGames(List<ImportGameData> games, boolean skipExisting, ProgressListener listener) {
        ImportResult result = new ImportResult();
        GameRepository repo = new GameRepository(context);
        List<Game> existing = repo.getAll();
        Map<String, Game> existingByName = new HashMap<>();
        for (Game g : existing) {
            if (g.title != null) existingByName.put(g.title.trim().toLowerCase(), g);
        }

        SQLiteDatabase db = dbHelper.getWritableDatabase();
        int total = games == null ? 0 : games.size();
        int index = 0;
        for (ImportGameData igd : games) {
            index++;
            if (listener != null) listener.onProgress(index, total, igd == null ? "" : igd.name);
            if (igd.name == null || igd.name.trim().isEmpty()) {
                result.failed++;
                result.failedNames.add("(空名称)");
                continue;
            }

            String nameKey = igd.name.trim().toLowerCase();
            Game existingGame = existingByName.get(nameKey);
            if (existingGame != null) {
                result.skipped++;
                result.skippedNames.add(igd.name + " (已存在)");
                continue;
            }

            // 创建新游戏
            Game game = new Game();
            game.title = igd.name.trim();
            game.originalTitle = igd.originalName != null && !igd.originalName.isEmpty() ? igd.originalName : game.title;
            game.engine = EngineType.UNKNOWN;
            game.rootUri = ""; // 三方平台导入的都是 Windows 路径，不设 rootUri
            game.description = igd.description != null ? igd.description : "";
            if (igd.tags != null && !igd.tags.isEmpty()) {
                game.tags = String.join(", ", igd.tags);
            }
            // 游玩状态：如果三方数据有 playStatus 就用它，否则默认 unplayed
            if (igd.playStatus != null && !igd.playStatus.isEmpty()) {
                game.playStatus = igd.playStatus;
            }
            // NSFW：沿用来源平台的标记（目前只有 LunaBox 的 is_nsfw 提供该信息）
            game.nsfw = igd.nsfw;
            game.createdAt = igd.createdAt > 0 ? igd.createdAt : System.currentTimeMillis();
            game.updatedAt = System.currentTimeMillis();

            // 封面处理
            if (igd.coverUrl != null && !igd.coverUrl.isEmpty() && (igd.coverUrl.startsWith("http://") || igd.coverUrl.startsWith("https://"))) {
                game.coverUri = igd.coverUrl;
                game.coverSourceType = 1;
            } else if (igd.coverLocalPath != null && !igd.coverLocalPath.isEmpty()) {
                // 复制到应用内部目录
                String savedPath = copyCoverToInternal(igd.coverLocalPath, game.title);
                if (savedPath != null) {
                    game.coverPersistUri = savedPath;
                    game.coverSourceType = 2;
                }
            }

            long id = repo.insert(game);
            game.id = id;
            existingByName.put(nameKey, game);

            // 导入游玩记录
            int sessions = 0;
            if (igd.playedTimeMap != null && !igd.playedTimeMap.isEmpty()) {
                sessions = importPotatoVnPlaySessions(db, id, igd.playedTimeMap);
            } else if (igd.vniteTimers != null && !igd.vniteTimers.isEmpty()) {
                sessions = importVniteTimers(db, id, igd.vniteTimers);
            } else if (igd.lunaBoxSessions != null && !igd.lunaBoxSessions.isEmpty()) {
                sessions = importLunaBoxSessions(db, id, igd.lunaBoxSessions);
            }
            result.sessionsImported += sessions;

            // 如果有总时长但没有逐日记录，直接设置总时长（YukiHub 内部用毫秒）
            if (igd.totalPlayTime > 0 && sessions == 0) {
                // ImportGameData.totalPlayTime 是秒，YukiHub 的 total_play_time 是毫秒
                db.execSQL("UPDATE games SET total_play_time = ? WHERE id = ?",
                        new Object[]{igd.totalPlayTime * 1000L, id});
            }

            result.success++;
        }

        repo.recalculatePlayStats();
        return result;
    }

    /**
     * 导入 PotatoVN 的 PlayedTime（日期->分钟数）为 play_sessions
     */
    private int importPotatoVnPlaySessions(SQLiteDatabase db, long gameId, Map<String, Integer> playedTime) {
        int count = 0;
        for (Map.Entry<String, Integer> entry : playedTime.entrySet()) {
            try {
                long startTime = parseDateToMillis(entry.getKey());
                int minutes = entry.getValue();
                if (minutes <= 0) continue;
                long durationMs = minutes * 60L * 1000L;
                long endTime = startTime + durationMs;

                ContentValues v = new ContentValues();
                v.put("game_id", gameId);
                v.put("start_time", startTime);
                v.put("end_time", endTime);
                v.put("duration", durationMs);
                v.put("launch_type", "imported_potatovn");
                v.put("session_uuid", UUID.randomUUID().toString());
                v.put("device_id", "potatovn_import");
                v.put("created_at", startTime);
                v.put("updated_at", endTime);
                v.put("dirty", 1);
                v.put("deleted", 0);
                db.insert("play_sessions", null, v);
                count++;
            } catch (Exception ignored) {}
        }
        return count;
    }

    /**
     * 导入 Vnite 的 Timers 为 play_sessions
     */
    private int importVniteTimers(SQLiteDatabase db, long gameId, List<ImportGameData.VniteTimer> timers) {
        int count = 0;
        for (ImportGameData.VniteTimer timer : timers) {
            try {
                long startTime = parseIsoTime(timer.start);
                long endTime = parseIsoTime(timer.end);
                long durationMs = Math.max(0L, endTime - startTime);
                if (durationMs <= 0) continue;

                ContentValues v = new ContentValues();
                v.put("game_id", gameId);
                v.put("start_time", startTime);
                v.put("end_time", endTime);
                v.put("duration", durationMs);
                v.put("launch_type", "imported_vnite");
                v.put("session_uuid", UUID.randomUUID().toString());
                v.put("device_id", "vnite_import");
                v.put("created_at", startTime);
                v.put("updated_at", endTime);
                v.put("dirty", 1);
                v.put("deleted", 0);
                db.insert("play_sessions", null, v);
                count++;
            } catch (Exception ignored) {}
        }
        return count;
    }

    /**
     * 导入 LunaBox 的 play_sessions 为 YukiHub 的 play_sessions。
     * LunaBox duration 单位是秒，YukiHub duration 单位是毫秒。
     */
    private int importLunaBoxSessions(SQLiteDatabase db, long gameId, List<ImportGameData.LunaBoxSession> sessions) {
        int count = 0;
        for (ImportGameData.LunaBoxSession session : sessions) {
            try {
                long startTime = LunaBoxImporter.parseLunaBoxTimestamp(session.start);
                long endTime = LunaBoxImporter.parseLunaBoxTimestamp(session.end);
                // LunaBox duration 是秒，转毫秒
                long durationMs = session.durationSeconds > 0
                        ? session.durationSeconds * 1000L
                        : Math.max(0L, endTime - startTime);
                if (durationMs <= 0 && session.durationSeconds <= 0) continue;

                ContentValues v = new ContentValues();
                v.put("game_id", gameId);
                v.put("start_time", startTime);
                v.put("end_time", endTime);
                v.put("duration", durationMs);
                v.put("launch_type", "imported_lunabox");
                v.put("session_uuid", UUID.randomUUID().toString());
                v.put("device_id", "lunabox_import");
                v.put("created_at", startTime);
                v.put("updated_at", endTime);
                v.put("dirty", 1);
                v.put("deleted", 0);
                db.insert("play_sessions", null, v);
                count++;
            } catch (Exception ignored) {}
        }
        return count;
    }

    private long parseDateToMillis(String dateStr) {
        // PotatoVN 日期格式：2006/1/2 或 2006/01/02
        String[] formats = {"yyyy/M/d", "yyyy/MM/dd"};
        for (String fmt : formats) {
            try {
                java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat(fmt, java.util.Locale.US);
                java.util.Calendar cal = java.util.Calendar.getInstance();
                cal.setTime(sdf.parse(dateStr));
                cal.set(java.util.Calendar.HOUR_OF_DAY, 12);
                return cal.getTimeInMillis();
            } catch (Exception ignored) {}
        }
        return System.currentTimeMillis();
    }

    private long parseIsoTime(String raw) {
        if (raw == null || raw.isEmpty()) return System.currentTimeMillis();
        String[] formats = {
                "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'",
                "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", "yyyy-MM-dd'T'HH:mm:ssXXX",
                "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd HH:mm:ss"
        };
        for (String fmt : formats) {
            try {
                java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat(fmt, java.util.Locale.US);
                if (fmt.contains("'Z'")) sdf.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
                return sdf.parse(raw).getTime();
            } catch (Exception ignored) {}
        }
        return System.currentTimeMillis();
    }

    /**
     * 把封面图片复制到应用内部目录，返回保存路径。
     */
    private String copyCoverToInternal(String sourcePath, String gameName) {
        try {
            File src = new File(sourcePath);
            if (!src.exists()) return null;

            File coverDir = new File(context.getFilesDir(), "imported_covers");
            coverDir.mkdirs();

            String safeName = gameName.replaceAll("[^a-zA-Z0-9\\u4e00-\\u9fff_-]", "_");
            String ext = ".jpg";
            String lower = sourcePath.toLowerCase();
            if (lower.endsWith(".png")) ext = ".png";
            else if (lower.endsWith(".webp")) ext = ".webp";

            File dest = new File(coverDir, safeName + "_" + System.currentTimeMillis() + ext);

            // 读取并保存
            FileInputStream fis = new FileInputStream(src);
            FileOutputStream fos = new FileOutputStream(dest);
            byte[] tmp = new byte[8192];
            int len;
            while ((len = fis.read(tmp)) > 0) fos.write(tmp, 0, len);
            fis.close();
            fos.close();

            return dest.getAbsolutePath();
        } catch (Exception e) {
            return null;
        }
    }
}