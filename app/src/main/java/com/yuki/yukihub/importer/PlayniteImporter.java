package com.yuki.yukihub.importer;

import android.content.Context;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Playnite JSON 导入器。
 *
 * Playnite 导出的是 JSON 数组，每个元素包含：
 * id, name, cover_url, company, summary, rating, release_date,
 * path, save_path, source_type, source_id, created_at, cached_at
 *
 * 注意：Playnite 的 path 是 Windows 路径，Android 上不适用。
 * 导入时 path 留空，以"无路径游戏"方式存入（和 YukiHub 收藏但没设目录的游戏一样）。
 */
public class PlayniteImporter {

    /**
     * 从 SAF Uri 读取 Playnite JSON 并解析成候选项列表。
     */
    public static List<ImportGameData> parse(Context context, Uri uri) throws Exception {
        InputStream in = context.getContentResolver().openInputStream(uri);
        if (in == null) throw new Exception("无法打开文件");
        byte[] buf;
        try {
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            byte[] tmp = new byte[8192];
            int len;
            while ((len = in.read(tmp)) > 0) baos.write(tmp, 0, len);
            buf = baos.toByteArray();
        } finally {
            in.close();
        }

        // 去掉 UTF-8 BOM
        if (buf.length >= 3 && buf[0] == (byte) 0xEF && buf[1] == (byte) 0xBB && buf[2] == (byte) 0xBF) {
            byte[] trimmed = new byte[buf.length - 3];
            System.arraycopy(buf, 3, trimmed, 0, trimmed.length);
            buf = trimmed;
        }

        String json = new String(buf, StandardCharsets.UTF_8);
        JSONArray arr = new JSONArray(json);

        List<ImportGameData> result = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            ImportGameData g = new ImportGameData();
            g.name = o.optString("name", "").trim();
            if (g.name.isEmpty()) continue;
            g.originalName = g.name;
            g.developer = o.optString("company", "").trim();
            g.description = o.optString("summary", "").trim();
            g.coverUrl = o.optString("cover_url", "").trim();
            g.releaseDate = o.optString("release_date", "").trim();
            g.rating = o.optDouble("rating", 0);
            g.path = o.optString("path", "").trim();
            g.savePath = o.optString("save_path", "").trim();
            g.sourceType = mapSourceType(o.optString("source_type", ""));
            g.sourceId = o.optString("source_id", "").trim();
            long createdAt = o.optLong("created_at", 0);
            g.createdAt = createdAt > 0 ? createdAt * 1000L : System.currentTimeMillis();
            result.add(g);
        }
        return result;
    }

    /**
     * 把 Playnite 的 source_type 字符串映射成 YukiHub 的 source 字符串。
     */
    private static String mapSourceType(String raw) {
        if (raw == null) return "local";
        switch (raw.toLowerCase().trim()) {
            case "bangumi": return "bangumi";
            case "vndb": return "vndb";
            case "ymgal": return "ymgal";
            case "steam": return "steam";
            default: return "local";
        }
    }
}