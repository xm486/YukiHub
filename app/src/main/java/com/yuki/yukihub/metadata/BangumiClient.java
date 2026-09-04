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
    /**
     * 镜像站基址。
     *
     * 迁移历史：bangumi.lol（已停止服务）→ bangumi.pro。
     * 镜像站随时可能再挂，所以域名只在这里出现一次，换站只改这两个常量。
     */
    static final String MIRROR_BASE = "https://api.bangumi.pro/";

    private static final String SEARCH_ENDPOINT_BGM = "https://api.bgm.tv/v0/search/subjects";
    private static final String SEARCH_ENDPOINT_MIRROR = MIRROR_BASE + "v0/search/subjects";
    private static final int MAX_RETRIES = 2;
    private static final long RETRY_DELAY_MS = 1500;

    private static final MediaType JSON_TYPE = MediaType.parse("application/json; charset=UTF-8");

    private static volatile ApiService bgmService;
    private static volatile ApiService mirrorService;

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
        String auth = "Bearer " + token.trim();

        RequestBody requestBody = RequestBody.create(body.toString().getBytes(StandardCharsets.UTF_8), JSON_TYPE);

        ApiService service = useMirror ? getMirrorService() : getBgmService();
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
        return searchFirst(keyword, token, false);
    }

    public static VnMetadata searchFirst(String keyword, String token, boolean useMirror) throws Exception {
        List<VnMetadata> list = searchCandidates(keyword, token, 1, useMirror);
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

    private static ApiService getMirrorService() {
        if (mirrorService == null) {
            synchronized (BangumiClient.class) {
                if (mirrorService == null) {
                    OkHttpClient client = HttpClient.defaultBuilder()
                            .addInterceptor(chain -> chain.proceed(chain.request().newBuilder()
                                    .header("Accept", "application/json")
                                    .build()))
                            .build();
                    Retrofit retrofit = HttpClient.retrofit(MIRROR_BASE, client);
                    mirrorService = retrofit.create(ApiService.class);
                }
            }
        }
        return mirrorService;
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
            // 主站与镜像共用这个 parse，靠域名区分：
            // 主站返回 lain.bgm.tv（国内不通，套反代），镜像返回 lain.bangumi.pro
            // （自带图床、直连可达，原样保留）。proxyBangumiImage 内部按域名判断，
            // 同时处理 "//" 开头的协议相对地址。
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
}
