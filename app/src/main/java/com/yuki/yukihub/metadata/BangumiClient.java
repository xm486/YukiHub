package com.yuki.yukihub.metadata;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class BangumiClient {
    private static final String SEARCH_ENDPOINT_BGM = "https://api.bgm.tv/v0/search/subjects";
    private static final String SEARCH_ENDPOINT_MIRROR = "https://api.bangumi.one/v0/search/subjects";
    private static final int MAX_RETRIES = 2;
    private static final long RETRY_DELAY_MS = 1500;

    public static List<VnMetadata> searchCandidates(String keyword, String token, int limit) throws Exception {
        return searchCandidates(keyword, token, limit, false);
    }

    public static List<VnMetadata> searchCandidates(String keyword, String token, int limit, boolean useMirror) throws Exception {
        List<VnMetadata> out = new ArrayList<>();
        if (keyword == null || keyword.trim().isEmpty()) return out;
        if (token == null || token.trim().isEmpty()) throw new IllegalArgumentException("Bangumi token required");

        JSONObject body = new JSONObject();
        body.put("keyword", MetadataUtils.cleanTitle(keyword));
        body.put("sort", "match");
        JSONObject filter = new JSONObject();
        filter.put("type", new JSONArray().put(4));
        body.put("filter", filter);

        String endpoint = useMirror ? SEARCH_ENDPOINT_MIRROR : SEARCH_ENDPOINT_BGM;
        String url = endpoint + "?limit=" + Math.max(1, Math.min(10, limit)) + "&offset=0";

        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            HttpURLConnection conn = null;
            InputStream is = null;
            try {
                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(15000);
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                conn.setRequestProperty("Accept", "application/json");
                conn.setRequestProperty("Authorization", "Bearer " + token.trim());
                conn.setRequestProperty("User-Agent", "YukiHub/1.0 (https://github.com/YukiHub; Android Galgame manager)");

                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                conn.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream os = conn.getOutputStream()) { os.write(bytes); }

                int code = conn.getResponseCode();
                is = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
                String text = MetadataUtils.readAndClose(is);
                is = null; // readAndClose 已关闭流，置空防止 finally 二次关闭

                if (code < 200 || code >= 300) {
                    if (code == 429 || code >= 500) {
                        if (attempt < MAX_RETRIES) {
                            MetadataUtils.sleepBeforeRetry(RETRY_DELAY_MS * (attempt + 1));
                            continue;
                        }
                        throw new RuntimeException("Bangumi HTTP " + code + ": " + text);
                    }
                    throw new RuntimeException("Bangumi HTTP " + code + ": " + text);
                }

                JSONObject root = new JSONObject(text);
                JSONArray data = root.optJSONArray("data");
                if (data != null) {
                    for (int i = 0; i < data.length(); i++) {
                        JSONObject item = data.optJSONObject(i);
                        if (item == null) continue;
                        out.add(parseSubject(item));
                    }
                }
                return out;
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                if (attempt < MAX_RETRIES) {
                    MetadataUtils.sleepBeforeRetry(RETRY_DELAY_MS * (attempt + 1));
                    continue;
                }
                throw e;
            } finally {
                MetadataUtils.closeQuietly(is);
                MetadataUtils.closeQuietly(conn);
            }
        }
        throw new IllegalStateException("unreachable");
    }

    public static VnMetadata searchFirst(String keyword, String token) throws Exception {
        return searchFirst(keyword, token, false);
    }

    public static VnMetadata searchFirst(String keyword, String token, boolean useMirror) throws Exception {
        List<VnMetadata> list = searchCandidates(keyword, token, 1, useMirror);
        return list.isEmpty() ? null : list.get(0);
    }

    private static VnMetadata parseSubject(JSONObject o) {
        VnMetadata m = new VnMetadata();
        int id = o.optInt("id", 0);
        m.id = id > 0 ? String.valueOf(id) : o.optString("id", "");
        m.romanTitle = o.optString("name", "");
        m.chineseTitle = MetadataUtils.firstNonEmpty(o.optString("name_cn", ""), m.romanTitle);
        m.originalTitle = m.romanTitle;
        m.description = stripSummary(o.optString("summary", ""));
        m.released = o.optString("date", "");

        JSONObject images = o.optJSONObject("images");
        if (images != null) {
            m.coverUrl = MetadataUtils.firstNonEmpty(images.optString("large", ""), MetadataUtils.firstNonEmpty(images.optString("common", ""), images.optString("grid", "")));
            if (m.coverUrl.startsWith("//")) m.coverUrl = "https:" + m.coverUrl;
        }

        JSONObject rating = o.optJSONObject("rating");
        if (rating != null) {
            double score = rating.optDouble("score", 0);
            int total = rating.optInt("total", 0);
            if (score > 0) m.ratingText = total > 0 ? String.format(java.util.Locale.US, "评分：%.1f/10（%d人）", score, total) : String.format(java.util.Locale.US, "评分：%.1f/10", score);
        }

        JSONArray tags = o.optJSONArray("tags");
        if (tags != null) {
            List<String> names = new ArrayList<>();
            for (int i = 0; i < tags.length() && names.size() < 5; i++) {
                JSONObject tag = tags.optJSONObject(i);
                if (tag == null) continue;
                String name = tag.optString("name", "");
                if (!name.isEmpty()) names.add(name);
            }
            m.tagsText = MetadataUtils.join(names, "  ");
        }

        m.lengthText = "游玩时长：-";
        return m;
    }

    private static String stripSummary(String s) {
        if (s == null) return "";
        return s.replace("\\r", "").trim();
    }
}
