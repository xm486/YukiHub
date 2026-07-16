package com.yuki.yukihub.importer;

import android.content.Context;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * LunaBox ZIP 备份导入器。
 *
 * LunaBox 备份 ZIP 结构：
 * - database/games.csv          游戏列表（CSV，逗号分隔，双引号包裹含逗号的字段）
 * - database/play_sessions.csv  游玩记录（CSV）
 * - database/game_tags.csv      标签（CSV，每游戏多条）
 * - covers/*.webp              封面图片（文件名 = 游戏 ID + .webp）
 *
 * games.csv 列：
 *   id,name,cover_url,company,summary,rating,release_date,path,save_path,
 *   process_name,wine_runner,wine_args,wine_prefix,launch_mode,status,
 *   source_type,cached_at,source_id,created_at,updated_at,
 *   use_locale_emulator,use_magpie,metadata_locked
 *
 * play_sessions.csv 列：
 *   id,game_id,start_time,end_time,duration,updated_at
 *   duration 单位：秒
 *   start_time/end_time 格式：2026-07-16 17:34:23.673844+08（带时区的 PostgreSQL 风格时间戳）
 *
 * game_tags.csv 列：
 *   id,game_id,name,source,weight,is_spoiler,created_at,updated_at
 *
 * Android 上 path（Windows 路径）无效，导入时不设 rootUri。
 * 封面图片从 ZIP 中解压到缓存目录，路径写入 coverLocalPath。
 */
public class LunaBoxImporter {

    /**
     * 从 SAF Uri 读取 LunaBox ZIP 备份并解析成候选项列表。
     * 封面图片解压到应用缓存目录。
     */
    public static List<ImportGameData> parse(Context context, Uri uri) throws Exception {
        InputStream raw = context.getContentResolver().openInputStream(uri);
        if (raw == null) throw new Exception("无法打开 ZIP 文件");

        File tempDir = new File(context.getCacheDir(), "lunabox_import_" + System.currentTimeMillis());
        tempDir.mkdirs();

        // 从 ZIP 中提取各个 CSV 内容和封面图片
        String gamesCsv = null;
        String sessionsCsv = null;
        String tagsCsv = null;
        Map<String, File> coverFiles = new HashMap<>(); // 文件名(不含路径) -> File

        try {
            ZipInputStream zis = new ZipInputStream(raw);
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                String name = entry.getName();
                byte[] data = readZipEntryToBytes(zis);

                if (name.equals("database/games.csv") || name.endsWith("/games.csv")) {
                    gamesCsv = new String(data, StandardCharsets.UTF_8);
                } else if (name.equals("database/play_sessions.csv") || name.endsWith("/play_sessions.csv")) {
                    sessionsCsv = new String(data, StandardCharsets.UTF_8);
                } else if (name.equals("database/game_tags.csv") || name.endsWith("/game_tags.csv")) {
                    tagsCsv = new String(data, StandardCharsets.UTF_8);
                } else if (isImageFile(name)) {
                    // 解压封面图片到临时目录
                    String baseName = new File(name).getName();
                    File outFile = new File(tempDir, baseName);
                    try (FileOutputStream fos = new FileOutputStream(outFile)) {
                        fos.write(data);
                    }
                    coverFiles.put(baseName, outFile);
                }
                zis.closeEntry();
            }
            zis.close();
        } finally {
            raw.close();
        }

        if (gamesCsv == null) {
            throw new Exception("ZIP 中未找到 database/games.csv");
        }

        // 解析 tags CSV -> game_id -> list of tag names
        Map<String, List<String>> tagsByGameId = new HashMap<>();
        if (tagsCsv != null) {
            List<String[]> tagRows = parseCsv(tagsCsv);
            if (!tagRows.isEmpty()) {
                // 跳过表头
                for (int i = 1; i < tagRows.size(); i++) {
                    String[] row = tagRows.get(i);
                    if (row.length < 3) continue;
                    String gameId = row[1].trim();
                    String tagName = row[2].trim();
                    if (gameId.isEmpty() || tagName.isEmpty()) continue;
                    tagsByGameId.computeIfAbsent(gameId, k -> new ArrayList<>()).add(tagName);
                }
            }
        }

        // 解析 play_sessions CSV -> game_id -> list of [startTime, endTime, durationSec]
        Map<String, List<ImportGameData.LunaBoxSession>> sessionsByGameId = new HashMap<>();
        if (sessionsCsv != null) {
            List<String[]> sessionRows = parseCsv(sessionsCsv);
            if (!sessionRows.isEmpty()) {
                for (int i = 1; i < sessionRows.size(); i++) {
                    String[] row = sessionRows.get(i);
                    if (row.length < 5) continue;
                    String gameId = row[1].trim();
                    String startTime = row[2].trim();
                    String endTime = row[3].trim();
                    String durationStr = row[4].trim();
                    if (gameId.isEmpty()) continue;
                    ImportGameData.LunaBoxSession session = new ImportGameData.LunaBoxSession();
                    session.start = startTime;
                    session.end = endTime;
                    session.durationSeconds = parseIntSafe(durationStr, 0);
                    sessionsByGameId.computeIfAbsent(gameId, k -> new ArrayList<>()).add(session);
                }
            }
        }

        // 解析 games CSV
        List<String[]> gameRows = parseCsv(gamesCsv);
        List<ImportGameData> result = new ArrayList<>();
        if (gameRows.isEmpty()) return result;

        // 第一行是表头
        String[] headers = gameRows.get(0);
        Map<String, Integer> colMap = new LinkedHashMap<>();
        for (int i = 0; i < headers.length; i++) {
            colMap.put(headers[i].trim().toLowerCase(), i);
        }

        for (int i = 1; i < gameRows.size(); i++) {
            String[] row = gameRows.get(i);
            if (row.length == 0) continue;

            ImportGameData g = new ImportGameData();
            String gameId = getCol(row, colMap, "id");
            g.name = getCol(row, colMap, "name");
            if (g.name == null || g.name.trim().isEmpty()) continue;
            g.name = g.name.trim();

            g.developer = getCol(row, colMap, "company");
            g.description = getCol(row, colMap, "summary");
            g.releaseDate = getCol(row, colMap, "release_date");
            g.rating = parseDoubleSafe(getCol(row, colMap, "rating"), 0);
            g.path = getCol(row, colMap, "path");
            g.savePath = getCol(row, colMap, "save_path");
            g.sourceType = mapSourceType(getCol(row, colMap, "source_type"));
            g.sourceId = getCol(row, colMap, "source_id");

            // 游玩状态映射：LunaBox 5态 → YukiHub 3态
            // not_started → unplayed, playing → playing, completed → completed
            // want_to_play → unplayed, on_hold → unplayed
            String lunaStatus = getCol(row, colMap, "status");
            g.playStatus = mapPlayStatus(lunaStatus);

            // 封面
            String coverUrl = getCol(row, colMap, "cover_url");
            if (coverUrl != null && !coverUrl.isEmpty()) {
                if (coverUrl.startsWith("http://") || coverUrl.startsWith("https://")) {
                    g.coverUrl = coverUrl;
                } else if (coverUrl.startsWith("/local/covers/")) {
                    // LunaBox 本地封面格式：/local/covers/<uuid>.webp
                    String fileName = coverUrl.substring("/local/covers/".length());
                    File coverFile = coverFiles.get(fileName);
                    if (coverFile != null && coverFile.exists()) {
                        g.coverLocalPath = coverFile.getAbsolutePath();
                    }
                }
            }

            // 标签
            if (gameId != null && !gameId.isEmpty()) {
                List<String> tags = tagsByGameId.get(gameId);
                if (tags != null && !tags.isEmpty()) {
                    // 去重，最多取 20 个
                    List<String> uniqueTags = new ArrayList<>();
                    for (String t : tags) {
                        String trimmed = t.trim();
                        if (!trimmed.isEmpty() && !uniqueTags.contains(trimmed)) {
                            uniqueTags.add(trimmed);
                            if (uniqueTags.size() >= 20) break;
                        }
                    }
                    if (!uniqueTags.isEmpty()) g.tags = uniqueTags;
                }
            }

            // 创建时间
            String createdAtStr = getCol(row, colMap, "created_at");
            g.createdAt = parseLunaBoxTime(createdAtStr);

            // 游玩记录
            if (gameId != null && !gameId.isEmpty()) {
                List<ImportGameData.LunaBoxSession> sessions = sessionsByGameId.get(gameId);
                if (sessions != null && !sessions.isEmpty()) {
                    g.lunaBoxSessions = sessions;
                    // 计算总时长（秒）
                    long totalSec = 0;
                    for (ImportGameData.LunaBoxSession s : sessions) {
                        totalSec += s.durationSeconds;
                    }
                    if (totalSec > 0) g.totalPlayTime = totalSec;
                }
            }

            result.add(g);
        }

        tempDir.deleteOnExit();
        return result;
    }

    // ==================== CSV 解析 ====================

    /**
     * 简易 CSV 解析器，支持双引号包裹的字段（含逗号、换行、转义双引号）。
     * 返回 List<String[]>，每行一个数组。
     */
    private static List<String[]> parseCsv(String csv) {
        List<String[]> rows = new ArrayList<>();
        if (csv == null || csv.isEmpty()) return rows;

        List<String> currentRow = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        int i = 0;
        int len = csv.length();

        while (i < len) {
            char c = csv.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < len && csv.charAt(i + 1) == '"') {
                        // 转义的双引号 -> 一个双引号
                        field.append('"');
                        i += 2;
                    } else {
                        // 引号结束
                        inQuotes = false;
                        i++;
                    }
                } else {
                    field.append(c);
                    i++;
                }
            } else {
                if (c == '"') {
                    inQuotes = true;
                    i++;
                } else if (c == ',') {
                    currentRow.add(field.toString());
                    field.setLength(0);
                    i++;
                } else if (c == '\n') {
                    currentRow.add(field.toString());
                    field.setLength(0);
                    rows.add(currentRow.toArray(new String[0]));
                    currentRow.clear();
                    i++;
                } else if (c == '\r') {
                    // 跳过 \r，\n 会处理换行
                    i++;
                } else {
                    field.append(c);
                    i++;
                }
            }
        }
        // 处理最后一行最后一个字段
        if (field.length() > 0 || !currentRow.isEmpty()) {
            currentRow.add(field.toString());
            rows.add(currentRow.toArray(new String[0]));
        }

        return rows;
    }

    private static String getCol(String[] row, Map<String, Integer> colMap, String colName) {
        Integer idx = colMap.get(colName.toLowerCase());
        if (idx == null || idx >= row.length) return "";
        return row[idx];
    }

    // ==================== 时间解析 ====================

    /**
     * 解析 LunaBox 的时间戳格式：2026-07-16 11:55:03.747711+08
     * PostgreSQL 风格，带时区偏移。
     */
    private static long parseLunaBoxTime(String raw) {
        if (raw == null || raw.isEmpty() || "null".equals(raw)) return System.currentTimeMillis();
        // 去掉毫秒和时区，简化为 yyyy-MM-dd HH:mm:ss
        // 格式：2026-07-16 11:55:03.747711+08
        String cleaned = raw.trim();
        // 尝试多种格式
        String[] formats = {
            "yyyy-MM-dd HH:mm:ss.SSSSSSX",  // 带微秒和时区
            "yyyy-MM-dd HH:mm:ss.SSX",
            "yyyy-MM-dd HH:mm:ssX",
            "yyyy-MM-dd HH:mm:ss.SSSSSS",
            "yyyy-MM-dd HH:mm:ss.SS",
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd"
        };
        for (String fmt : formats) {
            try {
                SimpleDateFormat sdf = new SimpleDateFormat(fmt, Locale.US);
                return sdf.parse(cleaned).getTime();
            } catch (Exception ignored) {}
        }
        // 如果失败，尝试去掉微秒部分
        try {
            // 取 yyyy-MM-dd HH:mm:ss 部分
            if (cleaned.length() >= 19) {
                String simplified = cleaned.substring(0, 19);
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
                return sdf.parse(simplified).getTime();
            }
        } catch (Exception ignored) {}
        return System.currentTimeMillis();
    }

    /**
     * 解析 LunaBox 的 start_time/end_time，用于 play_session 的时间戳。
     * 和 parseLunaBoxTime 类似，但需要更高精度。
     */
    public static long parseLunaBoxTimestamp(String raw) {
        return parseLunaBoxTime(raw);
    }

    // ==================== 辅助方法 ====================

    /**
     * LunaBox 5 态 → YukiHub 3 态映射：
     * not_started → unplayed  (未开始 → 未玩)
     * playing     → playing   (游玩中 → 在玩)
     * completed   → completed  (已通关 → 玩过)
     * want_to_play → unplayed (想玩   → 未玩)
     * on_hold     → unplayed  (搁置   → 未玩)
     */
    private static String mapPlayStatus(String lunaStatus) {
        if (lunaStatus == null || lunaStatus.isEmpty()) return "unplayed";
        String s = lunaStatus.trim().toLowerCase();
        switch (s) {
            case "playing":
                return "playing";
            case "completed":
                return "completed";
            default:
                return "unplayed";
        }
    }

    private static String mapSourceType(String raw) {
        if (raw == null || raw.isEmpty()) return "local";
        String lower = raw.trim().toLowerCase();
        switch (lower) {
            case "vndb":
            case "bangumi":
            case "ymgal":
            case "steam":
            case "hikarinagi":
                return lower;
            default:
                return "local";
        }
    }

    private static double parseDoubleSafe(String s, double def) {
        if (s == null || s.isEmpty()) return def;
        try { return Double.parseDouble(s.trim()); } catch (Exception e) { return def; }
    }

    private static int parseIntSafe(String s, int def) {
        if (s == null || s.isEmpty()) return def;
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
    }

    private static boolean isImageFile(String name) {
        String lower = name.toLowerCase();
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png")
                || lower.endsWith(".webp") || lower.endsWith(".gif") || lower.endsWith(".bmp");
    }

    private static byte[] readZipEntryToBytes(ZipInputStream zis) throws Exception {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        byte[] tmp = new byte[8192];
        int len;
        while ((len = zis.read(tmp)) > 0) baos.write(tmp, 0, len);
        return baos.toByteArray();
    }
}