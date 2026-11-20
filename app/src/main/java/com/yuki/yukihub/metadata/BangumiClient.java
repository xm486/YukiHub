package com.yuki.yukihub.metadata;

import com.yuki.yukihub.net.ApiService;
import com.yuki.yukihub.net.HttpClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.RequestBody;
import retrofit2.Retrofit;

public class BangumiClient {
    private static final String SEARCH_ENDPOINT_BGM = "https://api.bgm.tv/v0/search/subjects";
    private static final int MAX_RETRIES = 2;
    private static final long RETRY_DELAY_MS = 1500;
    private static final MediaType JSON_TYPE = MediaType.parse("application/json; charset=UTF-8");
    private static volatile ApiService bgmService;
    public static List<VnMetadata> searchCandidates(String keyword, String token, int limit) throws Exception {
        List<VnMetadata> out = new ArrayList<>();
        if (keyword == null || keyword.trim().isEmpty()) return out;
        if (token == null || token.trim().isEmpty()) throw new IllegalArgumentException("Bangumi token required");
        JSONObject body = new JSONObject();
        body.put("keyword", MetadataUtils.cleanTitle(keyword));
        body.put("sort", "match");
        JSONObject filter = new JSONObject();
        filter.put("type", new JSONArray().put(4));
        body.put("filter", filter);
        String url = SEARCH_ENDPOINT_BGM + "?limit=" + Math.max(1, Math.min(10, limit)) + "&offset=0";
        String auth = "Bearer " + token.trim();
        RequestBody requestBody = RequestBody.create(body.toString().getBytes(StandardCharsets.UTF_8), JSON_TYPE);
        ApiService service = getBgmService();
        String text = HttpClient.executeWithRetry(
                () -> service.postWithAuth(url, requestBody, auth),
                MAX_RETRIES, RETRY_DELAY_MS);
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
    }
    public static VnMetadata searchFirst(String keyword, String token) throws Exception {
        List<VnMetadata> list = searchCandidates(keyword, token, 1);
        return list.isEmpty() ? null : list.get(0);
    }
    private static ApiService getBgmService() {
        if (bgmService == null) {
            synchronized (BangumiClient.class) {
                if (bgmService == null) {
                    OkHttpClient client = HttpClient.defaultBuilder()
                            .addInterceptor(chain -> chain.proceed(chain.request().newBuilder()
                                    .header("Accept", "application/json")
                                    .build()))
                            .build();
                    Retrofit retrofit = HttpClient.retrofit("https://api.bgm.tv/", client);
                    bgmService = retrofit.create(ApiService.class);
                }
            }
        }
        return bgmService;
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
            // Bangumi 官源返回 lain.bgm.tv（国内不通，套反代）。
            // proxyBangumiImage 内部按域名判断，同时处理 "//" 开头的协议相对地址。
            m.coverUrl = MetadataUtils.proxyBangumiImage(m.coverUrl);
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

    /**
     * 按 Bangumi 条目 ID 直接取详情。
     * <p>
     * <b>为什么需要这条通路</b>：v0 搜索接口（/v0/search/subjects）的索引
     * 并不覆盖全部条目，实测「时钟机关的Ley-line-阳炎中彷徨的魔女-」
     * （subject 207413）在任何关键词下都搜不出来（连搜「レイライン」返回 18 条、
     * 同系列另外三部都在，也唯独缺它），但按 ID 取详情 100% 正常。
     * <p>
     * 所以遇到搜索搜不到的作品，让用户自己去 bgm.tv 找到条目、把 ID 填进来，
     * 我们只按 ID 取数据，彻底绕开搜索索引的问题。
     * <p>
     * 端点：{@code GET /v0/subjects/{id}}
     *
     * @param subjectId 纯数字条目 ID（如 207413）
     * @return 解析好的元数据；ID 非法或条目不存在时返回 null
     */
    public static VnMetadata fetchById(String subjectId) {
        if (subjectId == null) return null;
        String id = subjectId.trim();
        if (id.isEmpty()) return null;
        // 只接受纯数字，避免把普通关键词误当 ID（bv 之类的也没法当 ID）
        for (int i = 0; i < id.length(); i++) {
            if (!Character.isDigit(id.charAt(i))) return null;
        }
        try {
            String url = "https://api.bgm.tv/v0/subjects/" + id;
            okhttp3.Request req = new okhttp3.Request.Builder()
                    .url(url)
                    .header("Accept", "application/json")
                    .header("User-Agent", "yukihub/1.0 (https://github.com/yukihub)")
                    .get()
                    .build();
            OkHttpClient client = HttpClient.defaultBuilder().build();
            try (okhttp3.Response resp = client.newCall(req).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) return null;
                String text = resp.body().string();
                if (text == null || text.trim().isEmpty()) return null;
                JSONObject o = new JSONObject(text);
                // 只接受「游戏」类型：免得用户填了动画/书籍的 ID 被当游戏资料
                int type = o.optInt("type", 0);
                if (type != 0 && type != 4) return null;
                return parseSubject(o);
            }
        } catch (Exception e) {
            return null;
        }
    }
}
