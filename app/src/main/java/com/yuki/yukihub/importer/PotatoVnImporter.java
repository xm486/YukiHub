package com.yuki.yukihub.importer;

import android.content.Context;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * PotatoVN ZIP 导入器。
 *
 * PotatoVN 导出的 ZIP 包含：
 * - data.galgames.json：游戏列表（核心数据）
 * - 封面图片文件（路径由 ImagePath 字段指定）
 *
 * 每条 Galgame 记录包含：名字、路径、开发商、简介、评分、标签、
 * 游玩记录（PlayedTime: {"2024/1/1": 120} 表示这天玩了 120 分钟）、
 * 数据源类型（RssType: 0=VNDB, 1=Bangumi, ...）和数据源 ID（Ids 数组）。
 *
 * Android 上 path（Windows 路径）无效，导入时不设 rootUri，
 * 以"无路径游戏"方式存入。
 */
public class PotatoVnImporter {

    /** PotatoVN 默认图标路径（不导入） */
    private static final String DEFAULT_IMAGE_PATH = "ms-appx:///Assets/WindowIcon.ico";

    /**
     * 从 SAF Uri 读取 PotatoVN ZIP 并解析成候选项列表。
     * 封面图片解压到应用缓存目录，路径写入 coverLocalPath。
     */
    public static List<ImportGameData> parse(Context context, Uri uri) throws Exception {
        InputStream raw = context.getContentResolver().openInputStream(uri);
        if (raw == null) throw new Exception("无法打开 ZIP 文件");

        File tempDir = new File(context.getCacheDir(), "potatovn_import_" + System.currentTimeMillis());
        tempDir.mkdirs();

        String galgamesJson = null;
        try {
            ZipInputStream zis = new ZipInputStream(raw);
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                String name = entry.getName();
                if ("data.galgames.json".equals(name)) {
                    galgamesJson = readZipEntryToString(zis);
                } else if (isImageFile(name)) {
                    // 解压图片到临时目录
                    File outFile = new File(tempDir, sanitizePath(name));
                    outFile.getParentFile().mkdirs();
                    FileOutputStream fos = new FileOutputStream(outFile);
                    byte[] tmp = new byte[8192];
                    int len;
                    while ((len = zis.read(tmp)) > 0) fos.write(tmp, 0, len);
                    fos.close();
                }
                zis.closeEntry();
            }
            zis.close();
        } finally {
            raw.close();
        }

        if (galgamesJson == null) {
            throw new Exception("ZIP 中未找到 data.galgames.json");
        }

        JSONArray arr = new JSONArray(galgamesJson);
        List<ImportGameData> result = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            ImportGameData g = new ImportGameData();
            g.name = pickDisplayName(o);
            if (g.name == null || g.name.trim().isEmpty()) continue;
            g.name = g.name.trim();
            g.originalName = optLockableString(o, "OriginalName");
            g.developer = optLockableString(o, "Developer");
            g.description = optLockableString(o, "Description");
            g.releaseDate = optLockableDate(o);
            g.rating = optLockableDouble(o, "Rating");
            g.tags = optLockableStringList(o, "Tags");

            // 封面
            String imagePath = optLockableString(o, "ImagePath");
            if (imagePath != null && !imagePath.isEmpty() && !DEFAULT_IMAGE_PATH.equals(imagePath)) {
                // 尝试在解压目录中找对应文件
                String relativePath = resolvePotatoVnImage(imagePath);
                if (relativePath != null) {
                    File coverFile = new File(tempDir, relativePath);
                    if (coverFile.exists()) {
                        g.coverLocalPath = coverFile.getAbsolutePath();
                    }
                }
                // 如果封面是远程 URL，也记录
                if (imagePath.startsWith("http://") || imagePath.startsWith("https://")) {
                    g.coverUrl = imagePath;
                }
            }

            // 游戏路径（Windows 路径，Android 上无效）
            String exePath = o.optString("ExePath", "");
            if (exePath != null && !exePath.isEmpty() && !exePath.equals("null")) {
                g.path = exePath.trim();
            }
            String savePath = o.optString("SavePath", "");
            if (savePath != null && !savePath.isEmpty() && !savePath.equals("null")) {
                g.savePath = savePath.trim();
            }

            // 数据源
            int rssType = o.optInt("RssType", 3);
            g.sourceType = mapRssType(rssType);
            g.sourceId = pickSourceId(o, rssType);

            // 创建时间
            String addTime = o.optString("AddTime", "");
            g.createdAt = parseFlexTime(addTime);

            // 游玩记录：PlayedTime {"2024/1/1": 120}
            JSONObject playedTime = o.optJSONObject("PlayedTime");
            if (playedTime != null && playedTime.length() > 0) {
                g.playedTimeMap = new LinkedHashMap<>();
                for (java.util.Iterator<String> it = playedTime.keys(); it.hasNext(); ) {
                    String dateStr = it.next();
                    int minutes = playedTime.optInt(dateStr, 0);
                    if (minutes > 0) g.playedTimeMap.put(dateStr, minutes);
                }
            }

            g.totalPlayTime = o.optLong("TotalPlayTime", 0); // PotatoVN 存秒

            result.add(g);
        }

        // 临时目录标记为可清理（不在 parse 阶段删，因为封面文件路径被引用）
        tempDir.deleteOnExit();

        return result;
    }

    // ==================== PotatoVN 字段提取辅助 ====================

    /**
     * 优先中文名 > 原始名
     */
    private static String pickDisplayName(JSONObject o) {
        String cn = optLockableString(o, "ChineseName");
        if (cn != null && !cn.trim().isEmpty()) return cn.trim();
        String name = optLockableString(o, "Name");
        if (name != null && !name.trim().isEmpty()) return name.trim();
        return "";
    }

    /**
     * PotatoVN 的可锁定属性格式：{"Value": "xxx", "IsLock": false}
     */
    private static String optLockableString(JSONObject o, String key) {
        JSONObject prop = o.optJSONObject(key);
        if (prop == null) return "";
        String val = prop.optString("Value", "");
        return val == null ? "" : val;
    }

    private static double optLockableDouble(JSONObject o, String key) {
        JSONObject prop = o.optJSONObject(key);
        if (prop == null) return 0;
        return prop.optDouble("Value", 0);
    }

    private static List<String> optLockableStringList(JSONObject o, String key) {
        JSONObject prop = o.optJSONObject(key);
        if (prop == null) return null;
        JSONArray arr = prop.optJSONArray("Value");
        if (arr == null) return null;
        List<String> list = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            String s = arr.optString(i, "");
            if (!s.trim().isEmpty()) list.add(s.trim());
        }
        return list.isEmpty() ? null : list;
    }

    private static String optLockableDate(JSONObject o) {
        JSONObject prop = o.optJSONObject("ReleaseDate");
        if (prop == null) return "";
        String raw = prop.optString("Value", "");
        if (raw == null || raw.isEmpty() || "null".equals(raw)) return "";
        // 尝试解析各种格式
        for (String fmt : new String[]{"yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd"}) {
            try {
                java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat(fmt, java.util.Locale.US);
                sdf.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
                java.util.Date d = sdf.parse(raw);
                if (d != null) return new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(d);
            } catch (Exception ignored) {}
        }
        return raw;
    }

    /**
     * 从 Ids 数组按 RssType 位置取 SourceID
     */
    private static String pickSourceId(JSONObject o, int rssType) {
        JSONArray ids = o.optJSONArray("Ids");
        if (ids == null || ids.length() == 0) return "";
        if (rssType >= 0 && rssType < ids.length()) {
            String id = ids.optString(rssType, "");
            if (!id.isEmpty()) return id;
        }
        // 兜底取第一个非空
        for (int i = 0; i < ids.length(); i++) {
            String id = ids.optString(i, "");
            if (!id.isEmpty()) return id;
        }
        return "";
    }

    private static String mapRssType(int rssType) {
        switch (rssType) {
            case 0: return "vndb";
            case 1: return "bangumi";
            case 5: return "ymgal";
            case 7: return "steam";
            default: return "local";
        }
    }

    private static long parseFlexTime(String raw) {
        if (raw == null || raw.isEmpty() || "null".equals(raw)) return System.currentTimeMillis();
        for (String fmt : new String[]{
                "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", "yyyy-MM-dd'T'HH:mm:ssXXX",
                "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd"
        }) {
            try {
                java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat(fmt, java.util.Locale.US);
                return sdf.parse(raw).getTime();
            } catch (Exception ignored) {}
        }
        return System.currentTimeMillis();
    }

    /**
     * PotatoVN 的 ImagePath 可能是各种格式，尝试提取相对路径
     */
    private static String resolvePotatoVnImage(String imagePath) {
        if (imagePath == null || imagePath.isEmpty()) return null;
        // 去掉开头的 / 或 \
        String cleaned = imagePath.replace("\\", "/").replaceAll("^/+", "");
        return cleaned;
    }

    private static boolean isImageFile(String name) {
        String lower = name.toLowerCase();
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png")
                || lower.endsWith(".webp") || lower.endsWith(".gif") || lower.endsWith(".bmp");
    }

    private static String sanitizePath(String path) {
        // 防止 zip slip
        return path.replace("\\", "/").replaceAll("\\.\\./", "");
    }

    private static String readZipEntryToString(ZipInputStream zis) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] tmp = new byte[8192];
        int len;
        while ((len = zis.read(tmp)) > 0) baos.write(tmp, 0, len);
        return baos.toString("UTF-8");
    }
}