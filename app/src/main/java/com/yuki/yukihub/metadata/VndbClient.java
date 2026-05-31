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

import com.yuki.yukihub.util.AppExecutors;

public class VndbClient {
    private static final String ENDPOINT = "https://api.vndb.org/kana/vn";
    private static final String FIELDS = "title,alttitle,titles.lang,titles.title,titles.latin,titles.official,titles.main,olang,released,image.url,image.thumbnail,image.sexual,image.violence,description,rating,length,length_minutes,length_votes,developers.name,developers.original,tags.name,tags.rating,tags.spoiler,screenshots.url,screenshots.thumbnail,screenshots.sexual,screenshots.violence";

    public interface Callback {
        void onSuccess(VnMetadata data);
        void onError(Exception error);
    }

    public interface CandidatesCallback {
        void onSuccess(List<VnMetadata> data);
        void onError(Exception error);
    }

    public static void searchAsync(String title, Callback callback) {
        AppExecutors.runOnIo(() -> {
            try {
                VnMetadata result = search(title);
                callback.onSuccess(result);
            } catch (Exception e) {
                callback.onError(e);
            }
        });
    }

    public static VnMetadata search(String title) throws Exception {
        List<VnMetadata> list = searchCandidates(title, 1);
        return list.isEmpty() ? null : list.get(0);
    }

    public static void searchCandidatesAsync(String title, int limit, CandidatesCallback callback) {
        AppExecutors.runOnIo(() -> {
            try { callback.onSuccess(searchCandidates(title, limit)); }
            catch (Exception e) { callback.onError(e); }
        });
    }

    public static List<VnMetadata> searchCandidates(String title, int limit) throws Exception {
        String q = cleanTitle(title);
        List<VnMetadata> out = new ArrayList<>();
        if (q.isEmpty()) return out;

        JSONObject body = new JSONObject();
        body.put("filters", new JSONArray().put("search").put("=").put(q));
        body.put("fields", FIELDS);
        body.put("sort", "searchrank");
        body.put("results", Math.max(1, Math.min(10, limit)));

        HttpURLConnection conn = (HttpURLConnection) new URL(ENDPOINT).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(9000);
        conn.setReadTimeout(9000);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("User-Agent", "YukiHub/1.0 metadata lookup");

        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        conn.setFixedLengthStreamingMode(bytes.length);
        try (OutputStream os = conn.getOutputStream()) { os.write(bytes); }

        int code = conn.getResponseCode();
        InputStream is = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
        String text = readAll(is);
        if (code < 200 || code >= 300) throw new RuntimeException("VNDB HTTP " + code + ": " + text);

        JSONObject root = new JSONObject(text);
        JSONArray results = root.optJSONArray("results");
        if (results == null) return out;
        for (int i = 0; i < results.length(); i++) out.add(parse(results.getJSONObject(i)));
        return out;
    }

    private static VnMetadata parse(JSONObject o) {
        VnMetadata m = new VnMetadata();
        m.id = o.optString("id", "");
        m.romanTitle = o.optString("title", "");
        m.originalTitle = o.optString("alttitle", "");
        m.description = stripVndbMarkup(o.optString("description", ""));
        m.released = o.optString("released", "");
        int rating = o.optInt("rating", 0);
        if (rating > 0) m.ratingText = String.format(java.util.Locale.US, "评分：%.1f/10", rating / 10.0f);
        m.lengthMinutes = o.optInt("length_minutes", 0);
        m.lengthVotes = o.optInt("length_votes", 0);
        m.lengthText = formatLengthText(o.optInt("length", 0), m.lengthMinutes, m.lengthVotes);

        JSONArray titles = o.optJSONArray("titles");
        if (titles != null) {
            for (int i = 0; i < titles.length(); i++) {
                JSONObject t = titles.optJSONObject(i);
                if (t == null) continue;
                String lang = t.optString("lang", "");
                String title = t.optString("title", "");
                boolean main = t.optBoolean("main", false);
                if ("zh-Hans".equals(lang) || "zh-Hant".equals(lang) || "zh".equals(lang)) {
                    if (m.chineseTitle == null || m.chineseTitle.isEmpty()) m.chineseTitle = title;
                }
                if (main && (m.originalTitle == null || m.originalTitle.isEmpty())) m.originalTitle = title;
            }
        }
        if (m.chineseTitle == null || m.chineseTitle.isEmpty()) m.chineseTitle = firstNonEmpty(m.originalTitle, m.romanTitle);
        if (m.originalTitle == null || m.originalTitle.isEmpty()) m.originalTitle = m.romanTitle;

        JSONObject image = o.optJSONObject("image");
        if (image != null) {
            // 主界面/右侧详情优先使用缩略图，避免原图加载过慢；同步卡片也用较轻量图片。
            m.coverUrl = firstNonEmpty(image.optString("thumbnail", ""), image.optString("url", ""));
            m.coverSexual = image.optDouble("sexual", 0);
            m.coverViolence = image.optDouble("violence", 0);
        }

        JSONArray devs = o.optJSONArray("developers");
        if (devs != null) {
            List<String> names = new ArrayList<>();
            for (int i = 0; i < devs.length() && names.size() < 3; i++) {
                JSONObject d = devs.optJSONObject(i);
                if (d == null) continue;
                String name = firstNonEmpty(d.optString("original", ""), d.optString("name", ""));
                if (!name.isEmpty()) names.add(name);
            }
            m.developer = join(names, " / ");
        }

        JSONArray tags = o.optJSONArray("tags");
        if (tags != null) {
            List<TagScore> scored = new ArrayList<>();
            for (int i = 0; i < tags.length(); i++) {
                JSONObject t = tags.optJSONObject(i);
                if (t == null || t.optInt("spoiler", 0) > 0) continue;
                String name = t.optString("name", "");
                if (!name.isEmpty()) scored.add(new TagScore(name, t.optDouble("rating", 0)));
            }
            scored.sort((a, b) -> Double.compare(b.score, a.score));
            List<String> top = new ArrayList<>();
            for (int i = 0; i < scored.size() && top.size() < 5; i++) top.add(scored.get(i).name);
            m.tagsText = join(top, "  ");
        }

        JSONArray shots = o.optJSONArray("screenshots");
        if (shots != null) {
            for (int i = 0; i < shots.length() && m.screenshotUrls.size() < 2; i++) {
                JSONObject s = shots.optJSONObject(i);
                if (s == null) continue;
                String url = firstNonEmpty(s.optString("thumbnail", ""), s.optString("url", ""));
                if (!url.isEmpty()) m.screenshotUrls.add(url);
            }
        }
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

    private static String stripVndbMarkup(String s) {
        if (s == null) return "";
        return s.replaceAll("\\[url=[^\\]]+]([^\\[]+)\\[/url]", "$1")
                .replaceAll("\\[[^\\]]+]", "")
                .replace("\\r", "")
                .trim();
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

    private static String formatLengthText(int length, int minutes, int votes) {
        String label;
        switch (length) {
            case 1: label = "很短"; break;
            case 2: label = "短"; break;
            case 3: label = "中等"; break;
            case 4: label = "长"; break;
            case 5: label = "很长"; break;
            default: label = ""; break;
        }
        String hoursText = "";
        if (minutes > 0) {
            hoursText = String.format(java.util.Locale.US, "%.1f小时", minutes / 60.0f);
        }
        String voteText = votes > 0 ? "，" + votes + "人统计" : "";
        if (!label.isEmpty() && !hoursText.isEmpty()) return "游玩时长：" + label + "（" + hoursText + voteText + "）";
        if (!hoursText.isEmpty()) return "游玩时长：" + hoursText + voteText;
        if (!label.isEmpty()) return "游玩时长：" + label;
        return "";
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

    private static class TagScore {
        final String name;
        final double score;
        TagScore(String name, double score) { this.name = name; this.score = score; }
    }
}
