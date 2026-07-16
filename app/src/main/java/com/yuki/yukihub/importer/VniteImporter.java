package com.yuki.yukihub.importer;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Vnite 数据库导入器。
 *
 * Vnite 导出的是一个目录，包含：
 * - gameDocs.json：游戏文档（元数据、标签、计时器等）
 * - gameLocalDocs.json：本地文档（路径、启动器配置）
 * - covers/<id>.jpg：封面图片（可选）
 *
 * 注意：Vnite 的路径是 Windows 路径，Android 上不适用。
 */
public class VniteImporter {

    /**
     * 从 SAF 选中的目录 Uri 读取 Vnite 数据并解析。
     *
     * Vnite 的文档 ID 是 string 类型（UUID），需要合并 GameDocs 和 GameLocalDocs。
     */
    public static List<ImportGameData> parse(Context context, Uri dirUri) throws Exception {
        // 列举目录内容，找到 JSON 文件和封面目录
        String gameDocsJson = null;
        String gameLocalDocsJson = null;

        // SAF 列目录
        List<String> jsonFileNames = new ArrayList<>();
        java.util.Map<String, Uri> fileUris = new java.util.HashMap<>();

        try {
            android.content.ContentResolver cr = context.getContentResolver();
            android.net.Uri childrenUri = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(
                    dirUri, DocumentsContract.getTreeDocumentId(dirUri));

            Cursor cursor = cr.query(childrenUri, new String[]{
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE
            }, null, null, null);

            if (cursor != null) {
                try {
                    while (cursor.moveToNext()) {
                        String docId = cursor.getString(0);
                        String name = cursor.getString(1);
                        String mime = cursor.getString(2);
                        Uri fileUri = DocumentsContract.buildDocumentUriUsingTree(dirUri, docId);
                        fileUris.put(name, fileUri);

                        if ("application/json".equals(mime) || (name != null && name.endsWith(".json"))) {
                            jsonFileNames.add(name);
                        }
                    }
                } finally {
                    cursor.close();
                }
            }
        } catch (Exception e) {
            // ignore
        }

        // 读取 JSON 文件
        for (String name : jsonFileNames) {
            String content = readUriToString(context, fileUris.get(name));
            if (name.contains("gameDoc") || name.contains("GameDoc")) {
                gameDocsJson = content;
            } else if (name.contains("gameLocalDoc") || name.contains("GameLocalDoc")) {
                gameLocalDocsJson = content;
            }
        }

        // 也可能是单个 JSON 文件包含所有数据
        if (gameDocsJson == null && jsonFileNames.size() == 1) {
            gameDocsJson = readUriToString(context, fileUris.get(jsonFileNames.get(0)));
        }

        if (gameDocsJson == null) {
            throw new Exception("未找到 Vnite 游戏数据文件（gameDocs.json）");
        }

        // 尝试查找 covers 子目录
        try {
            android.net.Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                    dirUri, DocumentsContract.getTreeDocumentId(dirUri));
            Cursor cursor = context.getContentResolver().query(childrenUri, new String[]{
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE
            }, null, null, null);
            if (cursor != null) {
                try {
                    while (cursor.moveToNext()) {
                        String docId = cursor.getString(0);
                        String name = cursor.getString(1);
                        String mime = cursor.getString(2);
                        if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime) && "covers".equalsIgnoreCase(name)) {
                            // 保存 covers 目录 URI 以便后续读取封面
                            // 标记方式：存到标签
                        }
                    }
                } finally {
                    cursor.close();
                }
            }
        } catch (Exception ignored) {}

        // 解析 GameDocs（Vnite 格式可能是数组或 map）
        java.util.Map<String, JSONObject> gameDocsMap = new java.util.LinkedHashMap<>();
        Object parsed = new org.json.JSONTokener(gameDocsJson).nextValue();
        if (parsed instanceof JSONArray) {
            JSONArray arr = (JSONArray) parsed;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String id = o.optString("id", o.optString("_id", ""));
                if (id.isEmpty()) id = String.valueOf(i);
                gameDocsMap.put(id, o);
            }
        } else if (parsed instanceof JSONObject) {
            JSONObject obj = (JSONObject) parsed;
            java.util.Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                JSONObject o = obj.optJSONObject(key);
                if (o != null) gameDocsMap.put(key, o);
            }
        }

        // 解析 GameLocalDocs
        java.util.Map<String, JSONObject> localDocsMap = new java.util.HashMap<>();
        if (gameLocalDocsJson != null) {
            Object localParsed = new org.json.JSONTokener(gameLocalDocsJson).nextValue();
            if (localParsed instanceof JSONArray) {
                JSONArray arr = (JSONArray) localParsed;
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) continue;
                    String id = o.optString("id", o.optString("_id", o.optString("gameId", "")));
                    if (id.isEmpty()) id = String.valueOf(i);
                    localDocsMap.put(id, o);
                }
            } else if (localParsed instanceof JSONObject) {
                JSONObject obj = (JSONObject) localParsed;
                java.util.Iterator<String> keys = obj.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    JSONObject o = obj.optJSONObject(key);
                    if (o != null) localDocsMap.put(key, o);
                }
            }
        }

        // 合并并生成 ImportGameData 列表
        List<ImportGameData> result = new ArrayList<>();
        for (String id : gameDocsMap.keySet()) {
            JSONObject gameDoc = gameDocsMap.get(id);
            JSONObject localDoc = localDocsMap.get(id);
            ImportGameData g = convertFromVnite(gameDoc, localDoc);
            if (g != null && g.name != null && !g.name.trim().isEmpty()) {
                g.name = g.name.trim();
                result.add(g);
            }
        }

        return result;
    }

    private static ImportGameData convertFromVnite(JSONObject gameDoc, JSONObject localDoc) {
        ImportGameData g = new ImportGameData();

        // 名称
        JSONObject meta = gameDoc.optJSONObject("metadata");
        if (meta != null) {
            g.name = meta.optString("name", meta.optString("originalName", ""));
            g.originalName = meta.optString("originalName", "");
            g.developer = pickFirst(meta, "developers", "publishers");
            g.description = meta.optString("description", "");
            g.releaseDate = meta.optString("releaseDate", "");

            // 数据源
            g.sourceId = pickSourceId(meta);
            g.sourceType = pickSourceType(meta);

            // 标签
            JSONArray tagArr = meta.optJSONArray("tags");
            if (tagArr != null && tagArr.length() > 0) {
                g.tags = new ArrayList<>();
                for (int i = 0; i < tagArr.length(); i++) {
                    String t = tagArr.optString(i, "");
                    if (!t.trim().isEmpty()) g.tags.add(t.trim());
                }
                if (g.tags.isEmpty()) g.tags = null;
            }
        }

        if (g.name == null || g.name.isEmpty()) {
            g.name = gameDoc.optString("name", gameDoc.optString("title", ""));
        }
        if (g.name == null || g.name.isEmpty()) return null;

        // 计时器
        JSONObject record = gameDoc.optJSONObject("record");
        if (record != null) {
            JSONArray timers = record.optJSONArray("timers");
            if (timers != null && timers.length() > 0) {
                g.vniteTimers = new ArrayList<>();
                for (int i = 0; i < timers.length(); i++) {
                    JSONObject t = timers.optJSONObject(i);
                    if (t == null) continue;
                    ImportGameData.VniteTimer timer = new ImportGameData.VniteTimer();
                    timer.start = t.optString("start", "");
                    timer.end = t.optString("end", "");
                    if (!timer.start.isEmpty() && !timer.end.isEmpty()) {
                        g.vniteTimers.add(timer);
                    }
                }
            }
            g.createdAt = parseTime(record.optString("addDate", ""));
        }

        // 本地路径
        if (localDoc != null) {
            JSONObject launcher = localDoc.optJSONObject("launcher");
            if (launcher != null) {
                JSONObject fileConfig = launcher.optJSONObject("fileConfig");
                if (fileConfig != null) {
                    g.path = fileConfig.optString("path", "");
                }
            }
            JSONObject path = localDoc.optJSONObject("path");
            if (path != null) {
                if (g.path == null || g.path.isEmpty()) {
                    g.path = path.optString("gamePath", "");
                }
                JSONArray savePaths = path.optJSONArray("savePaths");
                if (savePaths != null && savePaths.length() > 0) {
                    g.savePath = savePaths.optString(0, "");
                }
            }
        }

        return g;
    }

    // ==================== Vnite 辅助方法 ====================

    private static String pickFirst(JSONObject meta, String... keys) {
        for (String key : keys) {
            JSONArray arr = meta.optJSONArray(key);
            if (arr != null && arr.length() > 0) {
                String v = arr.optString(0, "");
                if (!v.trim().isEmpty()) return v.trim();
            }
        }
        return "";
    }

    private static String pickSourceId(JSONObject meta) {
        String[] keys = {"vndbId", "ymgalId", "bangumiId", "steamId"};
        for (String key : keys) {
            String v = meta.optString(key, "");
            if (!v.trim().isEmpty()) return v.trim();
        }
        return "";
    }

    private static String pickSourceType(JSONObject meta) {
        String[] keys = {"vndbId", "ymgalId", "bangumiId", "steamId"};
        String[] types = {"vndb", "ymgal", "bangumi", "steam"};
        for (int i = 0; i < keys.length; i++) {
            String v = meta.optString(keys[i], "");
            if (!v.trim().isEmpty()) return types[i];
        }
        return "local";
    }

    private static long parseTime(String raw) {
        if (raw == null || raw.isEmpty()) return System.currentTimeMillis();
        String[] layouts = {
                "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'",
                "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", "yyyy-MM-dd'T'HH:mm:ssXXX",
                "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd"
        };
        for (String fmt : layouts) {
            try {
                java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat(fmt, java.util.Locale.US);
                if (fmt.endsWith("'Z'")) sdf.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
                return sdf.parse(raw).getTime();
            } catch (Exception ignored) {}
        }
        return System.currentTimeMillis();
    }

    private static String readUriToString(Context context, Uri uri) throws Exception {
        InputStream in = context.getContentResolver().openInputStream(uri);
        if (in == null) throw new Exception("无法读取文件: " + uri);
        try {
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            byte[] tmp = new byte[8192];
            int len;
            while ((len = in.read(tmp)) > 0) baos.write(tmp, 0, len);
            return baos.toString("UTF-8");
        } finally {
            in.close();
        }
    }
}