package com.yuki.yukihub.metadata;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class BangumiClient {
    private static final String SEARCH_ENDPOINT = "https://api.bgm.tv/v0/search/subjects";

    public static List<VnMetadata> searchCandidates(String keyword, String token, int limit) throws Exception {
        List<VnMetadata> out = new ArrayList<>();
        if (keyword == null || keyword.trim().isEmpty()) return out;
        if (token == null || token.trim().isEmpty()) throw new IllegalArgumentException("Bangumi token required");

        JSONObject body = new JSONObject();
        body.put("keyword", cleanTitle(keyword));
        body.put("sort", "match");
        JSONObject filter = new JSONObject();
        filter.put("type", new JSONArray().put(4)); // 4 = 游戏
        body.put("filter", filter);

        String url = SEARCH_ENDPOINT + "?limit=" + Math.max(1, Math.min(10, limit)) + "&offset=0";
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
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
        String text = readAll(code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream());
        if (code < 200 || code >= 300) throw new RuntimeException("Bangumi HTTP " + code + ": " + text);

        JSONObject root = new JSONObject(text);
        JSONArray data = root.optJSONArray("data");
        if (data == null) return out;
        for (int i = 0; i < data.length(); i++) {
            JSONObject item = data.optJSONObject(i);
            if (item == null) continue;
            out.add(parseSubject(item));
        }
        return out;
    }

    public static VnMetadata searchFirst(String keyword, String token) throws Exception {
        List<VnMetadata> list = searchCandidates(keyword, token, 1);
        return list.isEmpty() ? null : list.get(0);
    }

    private static VnMetadata parseSubject(JSONObject o) {
        VnMetadata m = new VnMetadata();
        int id = o.optInt("id", 0);
        m.id = id > 0 ? String.valueOf(id) : o.optString("id", "");
        m.romanTitle = o.optString("name", "");
        m.chineseTitle = firstNonEmpty(o.optString("name_cn", ""), m.romanTitle);
        m.originalTitle = m.romanTitle;
        m.description = stripSummary(o.optString("summary", ""));
        m.released = o.optString("date", "");

        JSONObject images = o.optJSONObject("images");
        if (images != null) {
            m.coverUrl = firstNonEmpty(images.optString("large", ""), firstNonEmpty(images.optString("common", ""), images.optString("grid", "")));
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
            m.tagsText = join(names, "  ");
        }

        m.lengthText = "游玩时长：-";
        return m;
    }

    private static String cleanTitle(String s) {
        if (s == null) return "";
        String x = s.replaceAll("[\\[\\]【】（）()].*", " ")
                .replaceAll("(?i)complete|汉化|中文版|日文版|体验版|trial|patch", " ")
                .replace('_', ' ')
                .trim();
        return x.isEmpty() ? s.trim() : x;
    }

    private static String stripSummary(String s) {
        if (s == null) return "";
        return s.replace("\\r", "").trim();
    }

    private static String readAll(InputStream is) throws Exception {
        if (is == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
        }
        return sb.toString();
    }

    private static String firstNonEmpty(String a, String b) {
        return a != null && !a.isEmpty() && !"null".equals(a) ? a : (b == null || "null".equals(b) ? "" : b);
    }

    private static String join(List<String> list, String sep) {
        StringBuilder sb = new StringBuilder();
        for (String s : list) {
            if (s == null || s.isEmpty()) continue;
            if (sb.length() > 0) sb.append(sep);
            sb.append(s);
        }
        return sb.toString();
    }
}
