package com.yuki.yukihub.metadata;

import com.yuki.yukihub.net.ApiService;
import com.yuki.yukihub.net.HttpClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import okhttp3.OkHttpClient;
import retrofit2.Retrofit;

public class YmgalClient {
    private static final String BASE_URL = "https://www.ymgal.games";
    private static final String CLIENT_ID = "ymgal";
    private static final String CLIENT_SECRET = "luna0327";
    private static final int MAX_RETRIES = 2;
    private static final long RETRY_DELAY_MS = 1200;
    private static final long MIN_REQUEST_INTERVAL_MS = 900;

    private static volatile String cachedAccessToken = "";
    private static volatile long cachedTokenExpiresAt = 0L;
    private static volatile long lastRequestTime = 0L;

    private static volatile ApiService apiService;

    public static List<VnMetadata> searchCandidates(String keyword, int limit) throws Exception {
        List<VnMetadata> out = new ArrayList<>();
        String q = MetadataUtils.cleanTitle(keyword);
        if (q.isEmpty()) return out;
        JSONObject data = apiGet("/open/archive/search-game", new String[][]{
                {"mode", "list"},
                {"keyword", q},
                {"pageNum", "1"},
                {"pageSize", String.valueOf(Math.max(1, Math.min(20, limit)))}
        }, true);
        JSONArray result = data == null ? null : data.optJSONArray("result");
        if (result == null) return out;
        for (int i = 0; i < result.length(); i++) {
            JSONObject item = result.optJSONObject(i);
            if (item == null) continue;
            VnMetadata m = parseListItem(item);
            if (m != null && m.id != null && !m.id.isEmpty()) out.add(m);
        }
        return out;
    }

    public static VnMetadata searchFirst(String keyword) throws Exception {
        List<VnMetadata> list = searchCandidates(keyword, 1);
        if (list == null || list.isEmpty()) return null;
        return getGame(list.get(0).id, list.get(0));
    }

    public static VnMetadata getGame(String gid) throws Exception {
        return getGame(gid, null);
    }

    public static VnMetadata getGame(String gid, VnMetadata base) throws Exception {
        if (gid == null || gid.trim().isEmpty()) return base;
        JSONObject data = apiGet("/open/archive", new String[][]{{"gid", gid.trim()}}, true);
        JSONObject game = data == null ? null : data.optJSONObject("game");
        if (game == null) return base;
        return parseGame(game, base);
    }

    private static synchronized String accessToken(boolean forceRefresh) throws Exception {
        long now = System.currentTimeMillis();
        if (!forceRefresh && cachedAccessToken != null && !cachedAccessToken.isEmpty() && now < cachedTokenExpiresAt) return cachedAccessToken;
        String url = BASE_URL + "/oauth/token?" + query(new String[][]{
                {"grant_type", "client_credentials"},
                {"client_id", CLIENT_ID},
                {"client_secret", CLIENT_SECRET},
                {"scope", "public"}
        });
        String text = HttpClient.executeForString(getService().get(url));
        JSONObject root = new JSONObject(text);
        String token = root.optString("access_token", "");
        int expires = root.optInt("expires_in", 3600);
        if (token.isEmpty()) throw new RuntimeException("月幕 Gal Token 响应为空");
        cachedAccessToken = token;
        cachedTokenExpiresAt = now + Math.max(300, expires - 60) * 1000L;
        return cachedAccessToken;
    }

    private static JSONObject apiGet(String path, String[][] params, boolean allowRefresh) throws Exception {
        throttle();
        Exception last = null;
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                return apiGetOnce(path, params, allowRefresh);
            } catch (Exception e) {
                last = e;
                String msg = e.getMessage() == null ? "" : e.getMessage();
                boolean retryable = msg.contains("HTTP 429") || msg.contains("HTTP 5") || msg.contains("TOO_MANY_REQUEST") || msg.contains("TIME_OUT") || msg.contains("SYSTEM_ERROR");
                if (!retryable || attempt >= MAX_RETRIES) throw e;
                MetadataUtils.sleepBeforeRetry(RETRY_DELAY_MS * (attempt + 1));
            }
        }
        throw last == null ? new IllegalStateException("月幕 Gal API 调用失败") : last;
    }

    private static JSONObject apiGetOnce(String path, String[][] params, boolean allowRefresh) throws Exception {
        String token = accessToken(false);
        String url = BASE_URL + path + "?" + query(params);
        HttpClient.ResponseResult result = HttpClient.executeForStringWithCode(
                getService().getWithHeader(url, "Bearer " + token));

        // HTTP 层 401/403 时刷新 token 重试
        if ((result.code == 401 || result.code == 403) && allowRefresh) {
            accessToken(true);
            return apiGetOnce(path, params, false);
        }
        if (!result.isSuccessful()) {
            throw new RuntimeException("月幕 Gal HTTP " + result.code + ": " + result.body);
        }

        String text = result.body;
        if (text.isEmpty()) return new JSONObject();

        // 检查 API 层面的错误码
        JSONObject root = new JSONObject(text);
        int apiCode = root.optInt("code", -1);
        boolean success = root.optBoolean("success", apiCode == 0);

        if ((!success || apiCode != 0) && (apiCode == 401 || apiCode == 403) && allowRefresh) {
            accessToken(true);
            return apiGetOnce(path, params, false);
        }
        if (!success || apiCode != 0) {
            throw new RuntimeException("月幕 Gal API " + apiCode + ": " + root.optString("msg", "调用失败"));
        }
        JSONObject data = root.optJSONObject("data");
        return data == null ? new JSONObject() : data;
    }

    private static ApiService getService() {
        if (apiService == null) {
            synchronized (YmgalClient.class) {
                if (apiService == null) {
                    OkHttpClient client = HttpClient.defaultBuilder()
                            .addInterceptor(chain -> chain.proceed(chain.request().newBuilder()
                                    .header("Accept", "application/json;charset=utf-8")
                                    .header("version", "1")
                                    .build()))
                            .build();
                    Retrofit retrofit = HttpClient.retrofit("https://www.ymgal.games/", client);
                    apiService = retrofit.create(ApiService.class);
                }
            }
        }
        return apiService;
    }

    private static synchronized void throttle() throws InterruptedException {
        long now = System.currentTimeMillis();
        long elapsed = now - lastRequestTime;
        if (elapsed < MIN_REQUEST_INTERVAL_MS) Thread.sleep(MIN_REQUEST_INTERVAL_MS - elapsed);
        lastRequestTime = System.currentTimeMillis();
    }

    private static String query(String[][] params) throws Exception {
        StringBuilder sb = new StringBuilder();
        if (params == null) return "";
        for (String[] p : params) {
            if (p == null || p.length < 2 || p[0] == null) continue;
            if (sb.length() > 0) sb.append('&');
            sb.append(URLEncoder.encode(p[0], "UTF-8"));
            sb.append('=');
            sb.append(URLEncoder.encode(p[1] == null ? "" : p[1], "UTF-8"));
        }
        return sb.toString();
    }

    private static VnMetadata parseListItem(JSONObject o) {
        if (o == null) return null;
        VnMetadata m = new VnMetadata();
        m.id = first(o.optString("id", ""), o.optString("gid", ""));
        m.romanTitle = o.optString("name", "");
        m.originalTitle = m.romanTitle;
        m.chineseTitle = MetadataUtils.firstNonEmpty(o.optString("chineseName", ""), m.romanTitle);
        m.coverUrl = normalizeImage(o.optString("mainImg", ""));
        m.released = o.optString("releaseDate", "");
        m.developer = o.optString("orgName", "");
        m.tagsText = buildTags(o);
        return m;
    }

    private static VnMetadata parseGame(JSONObject game, VnMetadata base) {
        VnMetadata m = new VnMetadata();
        if (base != null) {
            m.id = base.id;
            m.chineseTitle = base.chineseTitle;
            m.originalTitle = base.originalTitle;
            m.romanTitle = base.romanTitle;
            m.coverUrl = base.coverUrl;
            m.description = base.description;
            m.released = base.released;
            m.developer = base.developer;
            m.tagsText = base.tagsText;
            m.ratingText = base.ratingText;
            m.lengthText = base.lengthText;
        }
        m.id = first(game.optString("gid", ""), m.id);
        m.romanTitle = first(game.optString("name", ""), m.romanTitle);
        m.originalTitle = m.romanTitle;
        m.chineseTitle = MetadataUtils.firstNonEmpty(game.optString("chineseName", ""), MetadataUtils.firstNonEmpty(m.chineseTitle, m.romanTitle));
        m.coverUrl = MetadataUtils.firstNonEmpty(normalizeImage(game.optString("mainImg", "")), m.coverUrl);
        m.description = cleanText(game.optString("introduction", m.description));
        m.released = MetadataUtils.firstNonEmpty(game.optString("releaseDate", ""), m.released);
        m.developer = MetadataUtils.firstNonEmpty(game.optString("orgName", ""), m.developer);
        String tags = buildTags(game);
        if (!tags.isEmpty()) m.tagsText = tags;
        return m;
    }

    private static String buildTags(JSONObject o) {
        List<String> tags = new ArrayList<>();
        String type = o.optString("typeDesc", "");
        if (type != null && !type.trim().isEmpty()) tags.add(type.trim());
        if (o.has("haveChinese") && o.optBoolean("haveChinese", false)) tags.add("有中文版");
        if (o.has("restricted") && o.optBoolean("restricted", false)) tags.add("限制级");
        String country = o.optString("country", "");
        if (country != null && !country.trim().isEmpty()) tags.add("地区：" + country.trim().toUpperCase(Locale.ROOT));
        JSONArray releases = o.optJSONArray("releases");
        if (releases != null) {
            boolean pc = false, cn = false;
            for (int i = 0; i < releases.length() && i < 12; i++) {
                JSONObject r = releases.optJSONObject(i);
                if (r == null) continue;
                String platform = r.optString("platform", "");
                String lang = r.optString("releaseLanguage", "");
                if (platform.toLowerCase(Locale.ROOT).contains("windows") || platform.toLowerCase(Locale.ROOT).contains("pc")) pc = true;
                if (lang.toLowerCase(Locale.ROOT).contains("chinese") || lang.contains("中文")) cn = true;
            }
            if (pc) tags.add("PC");
            if (cn && !tags.contains("有中文版")) tags.add("有中文版");
        }
        return MetadataUtils.join(tags, "  ");
    }

    private static String cleanText(String s) {
        if (s == null) return "";
        return s.replace("\r", "").trim();
    }

    private static String normalizeImage(String url) {
        if (url == null) return "";
        String u = url.trim();
        if (u.startsWith("//")) return "https:" + u;
        return u;
    }

    private static String first(String a, String b) {
        if (a != null && !a.trim().isEmpty() && !"null".equalsIgnoreCase(a.trim())) return a.trim();
        return b == null ? "" : b.trim();
    }
}
