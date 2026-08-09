package com.yuki.yukihub.metadata;

import com.yuki.yukihub.net.ApiService;
import com.yuki.yukihub.net.HttpClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.RequestBody;
import retrofit2.Retrofit;

/**
 * Hikarinagi 公开数据 API 客户端。
 * <p>
 * 鉴权方式：OAuth 2.0 Client Credentials（Basic 认证获取令牌，令牌有效期 1 小时）。
 * 限速：60 次/分钟/应用。
 * API 文档：https://www.hikarinagi.org/developers/reference
 */
public class HikarinagiClient {

    private static final String API_BASE = "https://www.hikarinagi.org";
    private static final String TOKEN_URL = "https://id.hikarinagi.org/oidc/token";
    private static final String CLIENT_ID = "hkn_4poXX7v37j_iM2-o";
    private static final String CLIENT_SECRET = "hks_Wv6tW5O6ev8Mbifvg1tPJ7UexehLATQcKpZJiFhV48Y";
    private static final String SCOPE = "catalog:read";

    private static final int MAX_RETRIES = 2;
    private static final long RETRY_DELAY_MS = 1200;
    private static final long MIN_REQUEST_INTERVAL_MS = 1100; // 60/min ≈ 1s, 留余量

    private static final MediaType FORM_TYPE = MediaType.parse("application/x-www-form-urlencoded");

    private static volatile String cachedAccessToken = "";
    private static volatile long cachedTokenExpiresAt = 0L;
    private static volatile long lastRequestTime = 0L;

    private static volatile ApiService apiService;

    // ======================== public API ========================

    /**
     * 搜索 Galgame 候选列表。
     * 仅返回 type=galgame 的搜索结果。
     */
    public static List<VnMetadata> searchCandidates(String keyword, int limit) throws Exception {
        List<VnMetadata> out = new ArrayList<>();
        String q = MetadataUtils.cleanTitle(keyword);
        if (q.isEmpty()) return out;

        int pageSize = Math.max(1, Math.min(20, limit));
        JSONObject data = apiGet("/api/v3/open/search", new String[][]{
                {"q", q},
                {"types", "galgame"},
                {"page", "1"},
                {"page_size", String.valueOf(pageSize)}
        }, true);

        JSONArray items = data == null ? null : data.optJSONArray("items");
        if (items == null) return out;

        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            String type = item.optString("type", "");
            if (!"galgame".equals(type)) continue;
            VnMetadata m = parseSearchHit(item);
            if (m != null && !m.id.isEmpty()) out.add(m);
        }
        return out;
    }

    /**
     * 搜索并返回第一个匹配（自动获取详情）。
     */
    public static VnMetadata searchFirst(String keyword) throws Exception {
        List<VnMetadata> list = searchCandidates(keyword, 1);
        if (list == null || list.isEmpty()) return null;
        return getGalgame(list.get(0).id, list.get(0));
    }

    /**
     * 根据 Hikarinagi galgame ID 获取详情。
     *
     * @param id   Hikarinagi galgame ID
     * @param base 搜索阶段获得的简要信息（可为 null）
     */
    public static VnMetadata getGalgame(String id, VnMetadata base) throws Exception {
        if (id == null || id.trim().isEmpty()) return base;
        JSONObject data = apiGet("/api/v3/open/galgames/" + id.trim(), null, true);
        if (data == null) return base;
        return parseGalgame(data, base);
    }

    // ======================== token ========================

    private static synchronized String accessToken(boolean forceRefresh) throws Exception {
        long now = System.currentTimeMillis();
        if (!forceRefresh && cachedAccessToken != null && !cachedAccessToken.isEmpty() && now < cachedTokenExpiresAt) {
            return cachedAccessToken;
        }
        // Basic 认证：base64(client_id:client_secret)
        String credentials = CLIENT_ID + ":" + CLIENT_SECRET;
        String basicAuth = "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));

        String body = "grant_type=client_credentials&scope=" + URLEncoder.encode(SCOPE, "UTF-8");
        RequestBody requestBody = RequestBody.create(body.getBytes(StandardCharsets.UTF_8), FORM_TYPE);

        ApiService svc = getService();
        HttpClient.ResponseResult result = HttpClient.executeForStringWithCode(
                svc.postWithAuth(TOKEN_URL, requestBody, basicAuth));

        if (!result.isSuccessful()) {
            throw new RuntimeException("Hikarinagi Token HTTP " + result.code + ": " + result.body);
        }

        JSONObject root = new JSONObject(result.body);
        String token = root.optString("access_token", "");
        int expires = root.optInt("expires_in", 3600);
        if (token.isEmpty()) throw new RuntimeException("Hikarinagi Token 响应为空");

        cachedAccessToken = token;
        cachedTokenExpiresAt = now + Math.max(300, expires - 60) * 1000L;
        return cachedAccessToken;
    }

    // =================------- HTTP ----------------=======

    private static JSONObject apiGet(String path, String[][] params, boolean allowRefresh) throws Exception {
        throttle();
        Exception last = null;
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                return apiGetOnce(path, params, allowRefresh);
            } catch (Exception e) {
                last = e;
                String msg = e.getMessage() == null ? "" : e.getMessage();
                boolean retryable = msg.contains("HTTP 429") || msg.contains("HTTP 5");
                if (!retryable || attempt >= MAX_RETRIES) throw e;
                MetadataUtils.sleepBeforeRetry(RETRY_DELAY_MS * (attempt + 1));
            }
        }
        throw last == null ? new IllegalStateException("Hikarinagi API 调用失败") : last;
    }

    private static JSONObject apiGetOnce(String path, String[][] params, boolean allowRefresh) throws Exception {
        String token = accessToken(false);
        String url = API_BASE + path;
        if (params != null && params.length > 0) {
            url += "?" + buildQuery(params);
        }
        HttpClient.ResponseResult result = HttpClient.executeForStringWithCode(
                getService().getWithHeader(url, "Bearer " + token));

        // 401/403 时刷新 token 重试
        if ((result.code == 401 || result.code == 403) && allowRefresh) {
            accessToken(true);
            return apiGetOnce(path, params, false);
        }
        if (!result.isSuccessful()) {
            throw new RuntimeException("Hikarinagi HTTP " + result.code + ": " + result.body);
        }

        String text = result.body;
        if (text == null || text.isEmpty()) return new JSONObject();

        JSONObject root = new JSONObject(text);
        // 统一信封：{ success, data, request_id, timestamp }
        boolean success = root.optBoolean("success", true);
        if (!success) {
            throw new RuntimeException("Hikarinagi API 错误: " + root.optString("error", "未知错误"));
        }
        JSONObject data = root.optJSONObject("data");
        return data == null ? new JSONObject() : data;
    }

    // =================------- Retrofit / throttle ========================

    private static ApiService getService() {
        if (apiService == null) {
            synchronized (HikarinagiClient.class) {
                if (apiService == null) {
                    OkHttpClient client = HttpClient.defaultBuilder()
                            .addInterceptor(chain -> chain.proceed(chain.request().newBuilder()
                                    .header("Accept", "application/json")
                                    .build()))
                            .build();
                    Retrofit retrofit = HttpClient.retrofit("https://www.hikarinagi.org/", client);
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

    private static String buildQuery(String[][] params) throws Exception {
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

    // ======================== JSON parsing ========================

    /**
     * 解析搜索结果条目（摘要信息）。
     * 注意：Hikarinagi 搜索接口的 subtitle 字段是发售年份（如 "2019"），不是副标题/中文名。
     */
    private static VnMetadata parseSearchHit(JSONObject o) {
        if (o == null) return null;
        VnMetadata m = new VnMetadata();
        m.id = String.valueOf(o.optLong("id", 0));
        if ("0".equals(m.id)) m.id = o.optString("id", "");
        String title = o.optString("title", "");
        m.romanTitle = title;
        m.originalTitle = title;
        // subtitle 是年份（纯 4 位数字）时放入发售日期，绝不当标题用
        m.chineseTitle = title;
        String subtitle = o.optString("subtitle", "");
        if (subtitle != null && subtitle.matches("\\d{4}")) m.released = subtitle;
        m.developer = o.optString("developer", "");

        JSONObject cover = o.optJSONObject("cover");
        if (cover != null) {
            m.coverUrl = cover.optString("url", "");
            m.coverSexual = cover.optDouble("sexual", 0);
            m.coverViolence = cover.optDouble("violence", 0);
        }
        return m;
    }

    /**
     * 解析 Galgame 详情。
     */
    private static VnMetadata parseGalgame(JSONObject o, VnMetadata base) {
        VnMetadata m = new VnMetadata();
        // 保留搜索阶段的简要信息作为 fallback
        if (base != null) {
            m.id = base.id;
            m.chineseTitle = base.chineseTitle;
            m.originalTitle = base.originalTitle;
            m.romanTitle = base.romanTitle;
            m.coverUrl = base.coverUrl;
            m.coverSexual = base.coverSexual;
            m.coverViolence = base.coverViolence;
            m.developer = base.developer;
        }

        long id = o.optLong("id", 0);
        if (id > 0) m.id = String.valueOf(id);

        // 标题
        String originTitle = o.optString("origin_title", "");
        if (!originTitle.isEmpty()) m.originalTitle = originTitle;
        m.romanTitle = MetadataUtils.firstNonEmpty(originTitle, m.romanTitle);

        String transTitle = o.optString("trans_title", "");
        if (!transTitle.isEmpty()) {
            m.chineseTitle = transTitle;
        } else if (m.chineseTitle == null || m.chineseTitle.isEmpty()) {
            m.chineseTitle = MetadataUtils.firstNonEmpty(m.originalTitle, m.romanTitle);
        }

        // 别名
        JSONArray aliases = o.optJSONArray("aliases");
        if (aliases != null && aliases.length() > 0) {
            String firstAlias = aliases.optString(0, "");
            if (!firstAlias.isEmpty() && (m.romanTitle == null || m.romanTitle.isEmpty())) {
                m.romanTitle = firstAlias;
            }
        }

        // 简介
        String originIntro = o.optString("origin_intro", "");
        if (!originIntro.isEmpty()) m.description = cleanText(originIntro);
        String transIntro = o.optString("trans_intro", "");
        if (!transIntro.isEmpty()) m.translatedDescription = cleanText(transIntro);

        // 发售日期（ISO 时间戳截断为日期，如 2019-08-29）
        String releaseDate = o.optString("release_date", "");
        if (releaseDate != null && releaseDate.length() >= 10) releaseDate = releaseDate.substring(0, 10);
        if (releaseDate != null && !releaseDate.isEmpty()) m.released = releaseDate;

        // 封面（covers 数组取第一个）
        JSONArray covers = o.optJSONArray("covers");
        if (covers != null && covers.length() > 0) {
            JSONObject cover = covers.optJSONObject(0);
            if (cover != null) {
                String url = cover.optString("url", "");
                if (!url.isEmpty()) m.coverUrl = url;
                m.coverSexual = cover.optDouble("sexual", 0);
                m.coverViolence = cover.optDouble("violence", 0);
            }
        }

        // 截图（images 数组，取前 2 张）
        JSONArray images = o.optJSONArray("images");
        if (images != null) {
            for (int i = 0; i < images.length() && m.screenshotUrls.size() < 2; i++) {
                JSONObject img = images.optJSONObject(i);
                if (img == null) continue;
                String url = img.optString("url", "");
                if (!url.isEmpty()) m.screenshotUrls.add(url);
            }
        }

        // 标签
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

        // 开发状态和引擎作为额外标签信息
        String devStatus = o.optString("dev_status", "");
        if (!devStatus.isEmpty()) {
            String statusLabel = devStatusLabel(devStatus);
            if (!statusLabel.isEmpty() && !m.tagsText.isEmpty()) {
                m.tagsText += "  " + statusLabel;
            } else if (!statusLabel.isEmpty()) {
                m.tagsText = statusLabel;
            }
        }

        // 平台信息
        JSONArray platforms = o.optJSONArray("platforms");
        if (platforms != null && platforms.length() > 0) {
            List<String> plats = new ArrayList<>();
            for (int i = 0; i < platforms.length() && plats.size() < 3; i++) {
                String p = platforms.optString(i, "");
                if (!p.isEmpty()) plats.add(p);
            }
            String platText = MetadataUtils.join(plats, " / ");
            if (!platText.isEmpty()) {
                if (!m.tagsText.isEmpty()) m.tagsText += "  " + platText;
                else m.tagsText = platText;
            }
        }

        // Hikarinagi 不提供评分和游戏时长数据
        m.ratingText = "";
        m.lengthText = "";

        return m;
    }

    private static String devStatusLabel(String status) {
        if (status == null) return "";
        switch (status) {
            case "RELEASED": return "已发售";
            case "IN_DEVELOPMENT": return "开发中";
            case "CANCELLED": return "已取消";
            default: return "";
        }
    }

    private static String cleanText(String s) {
        if (s == null) return "";
        return s.replace("\r", "").trim();
    }
}
