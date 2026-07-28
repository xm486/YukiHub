package com.yuki.yukihub.translate;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.FormBody;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Bing 网页翻译 API 实现（免费，无需 API Key）。
 *
 * 适配说明：参考 MoeTranslate 的 BingTranslation.kt，转换为 Java。
 * 保留原项目版权和 LGPL 声明。
 *
 * 实现原理：
 * 1. 请求 https://cn.bing.com/Translator 页面，从 HTML 中提取 IG、IID、token、key
 * 2. 使用这些 token 构建 POST 请求到 ttranslatev3 接口
 * 3. 解析 JSON 响应获取翻译结果
 *
 * 无需任何 API Key 或账户，完全基于网页模拟。
 */
public final class BingTranslationProvider implements TranslationTextProvider {

    private static final String TAG = "BingTrans";
    private static final long SOCKET_TIMEOUT = 10L;

    private static final String CN_HOST_URL = "https://cn.bing.com/Translator";
    private static final String EN_HOST_URL = "https://www.bing.com/Translator";
    private static final String API_ENDPOINT = "ttranslatev3";

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/107.0.0.0 Safari/537.36";

    // Token 提取正则
    private static final Pattern IG_PATTERN = Pattern.compile("IG:\"(.*?)\"");
    private static final Pattern IID_PATTERN =
            Pattern.compile("<div[ ]+id=\"tta_outGDCont\"[ ]+data-iid=\"(.*?)\">");
    private static final Pattern TOKEN_PATTERN =
            Pattern.compile("var params_AbusePreventionHelper = (.*?);");

    private final OkHttpClient client;
    private final ExecutorService executor;
    private final Handler mainHandler;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private volatile boolean released = false;

    /** Cookie 存储（内存） */
    private final Map<String, java.util.List<Cookie>> cookieStore = new HashMap<>();

    public BingTranslationProvider() {
        this.client = new OkHttpClient.Builder()
                .connectTimeout(SOCKET_TIMEOUT, TimeUnit.SECONDS)
                .readTimeout(SOCKET_TIMEOUT, TimeUnit.SECONDS)
                .writeTimeout(SOCKET_TIMEOUT, TimeUnit.SECONDS)
                .cookieJar(new CookieJar() {
                    @Override
                    public void saveFromResponse(HttpUrl url, java.util.List<Cookie> cookies) {
                        synchronized (cookieStore) {
                            cookieStore.put(url.host(), cookies);
                        }
                    }

                    @Override
                    public java.util.List<Cookie> loadForRequest(HttpUrl url) {
                        synchronized (cookieStore) {
                            java.util.List<Cookie> cookies = cookieStore.get(url.host());
                            return cookies != null ? cookies : java.util.Collections.emptyList();
                        }
                    }
                })
                .build();
        this.executor = Executors.newSingleThreadExecutor();
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    @Override
    public void translate(String text, String sourceLanguage, String targetLanguage, Callback callback) {
        cancelled.set(false);
        executor.execute(() -> {
            if (released || cancelled.get()) return;
            try {
                String result = doTranslate(text, sourceLanguage, targetLanguage);
                if (cancelled.get()) return;
                mainHandler.post(() -> {
                    if (!cancelled.get() && callback != null) {
                        callback.onSuccess(result);
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Translation error", e);
                if (cancelled.get()) return;
                mainHandler.post(() -> {
                    if (!cancelled.get() && callback != null) {
                        callback.onError(e);
                    }
                });
            }
        });
    }

    private String doTranslate(String text, String from, String to) throws Exception {
        // 获取 token
        TokenInfo tokenInfo = getTokenInfo();

        // 构建翻译 API URL
        HttpUrl apiUrl = new HttpUrl.Builder()
                .scheme("https")
                .host(useCnHost() ? "cn.bing.com" : "www.bing.com")
                .addPathSegment(API_ENDPOINT)
                .addQueryParameter("isVertical", "1")
                .addQueryParameter("IG", tokenInfo.ig)
                .addQueryParameter("IID", tokenInfo.iid)
                .build();

        // 构建表单请求
        FormBody formBody = new FormBody.Builder()
                .add("fromLang", modifyLanguage(from))
                .add("to", modifyLanguage(to))
                .add("text", text)
                .add("tryFetchingGenderDebiasedTranslations", "true")
                .add("token", tokenInfo.token)
                .add("key", tokenInfo.key)
                .build();

        Request request = new Request.Builder()
                .url(apiUrl)
                .header("User-Agent", USER_AGENT)
                .header("Referer", useCnHost() ? CN_HOST_URL : EN_HOST_URL)
                .post(formBody)
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Unexpected response " + response.code());
            }
            String body = response.body() != null ? response.body().string() : "";
            return parseTranslationResponse(body);
        }
    }

    private TokenInfo getTokenInfo() throws Exception {
        Request request = new Request.Builder()
                .url(useCnHost() ? CN_HOST_URL : EN_HOST_URL)
                .header("User-Agent", USER_AGENT)
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Failed to get tokens, response: " + response.code());
            }
            String html = response.body() != null ? response.body().string() : "";
            if (html.isEmpty()) {
                throw new IOException("Empty response when getting tokens");
            }

            Matcher igMatcher = IG_PATTERN.matcher(html);
            Matcher iidMatcher = IID_PATTERN.matcher(html);
            Matcher tokenMatcher = TOKEN_PATTERN.matcher(html);

            if (!igMatcher.find() || !iidMatcher.find() || !tokenMatcher.find()) {
                throw new IOException("Failed to extract necessary tokens from response");
            }

            // 解析 token 参数：形如 ["key","token"] 的数组字符串
            String tokenParamsRaw = tokenMatcher.group(1).trim();
            // 去掉首尾的 [ ]
            if (tokenParamsRaw.startsWith("[") && tokenParamsRaw.endsWith("]")) {
                tokenParamsRaw = tokenParamsRaw.substring(1, tokenParamsRaw.length() - 1);
            }
            String[] parts = tokenParamsRaw.split(",");
            if (parts.length < 2) {
                throw new IOException("Incomplete token parameters");
            }
            String key = parts[0].trim().replaceAll("^\"|\"$", "");
            String token = parts[1].trim().replaceAll("^\"|\"$", "");

            return new TokenInfo(
                    igMatcher.group(1),
                    iidMatcher.group(1),
                    key,
                    token
            );
        }
    }

    private String parseTranslationResponse(String responseBody) throws Exception {
        try {
            JSONArray jsonArray = new JSONArray(responseBody);
            if (jsonArray.length() == 0) {
                throw new IOException("Empty translation response");
            }
            return jsonArray.getJSONObject(0)
                    .getJSONArray("translations")
                    .getJSONObject(0)
                    .getString("text");
        } catch (Exception e) {
            throw new IOException("Failed to parse translation response: " + e.getMessage());
        }
    }

    /** 语言代码适配：Bing 使用 zh-Hans / zh-Hant */
    private String modifyLanguage(String lang) {
        if ("zh".equals(lang)) return "zh-Hans";
        if ("zh-TW".equals(lang)) return "zh-Hant";
        return lang;
    }

    /** 是否使用中国服务器（默认 true） */
    private boolean useCnHost() {
        return true;
    }

    @Override
    public void cancel() {
        cancelled.set(true);
    }

    @Override
    public void release() {
        released = true;
        cancel();
        executor.shutdownNow();
        synchronized (cookieStore) {
            cookieStore.clear();
        }
    }

    /** Token 信息 */
    private static final class TokenInfo {
        final String ig;
        final String iid;
        final String key;
        final String token;

        TokenInfo(String ig, String iid, String key, String token) {
            this.ig = ig;
            this.iid = iid;
            this.key = key;
            this.token = token;
        }
    }
}