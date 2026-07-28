package com.yuki.yukihub.translate;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 自定义文本翻译 API 实现。
 *
 * 适配说明：参考 MoeTranslate 的 CustomTranslationText.kt，转换为 Java。
 * 保留原项目版权和 LGPL 声明。
 *
 * 功能：
 *   - 支持 GET / POST 请求
 *   - GET 请求：查询参数中值为 "usesourcetext" 的替换为待翻译文本
 *   - POST 请求：JSON body 中值为 "usesourcetext" 的替换为待翻译文本
 *   - 自定义请求头
 *   - JSON 响应路径解析（通过 JsonPathParser）
 *
 * 线程模型：使用 ExecutorService 在后台线程执行网络请求，通过 Handler 回调主线程。
 */
public final class CustomTextApiProvider implements TranslationTextProvider {

    private static final String TAG = "CustomTextApi";
    private static final long SOCKET_TIMEOUT = 10L;
    private static final MediaType JSON_TYPE = MediaType.get("application/json; charset=utf-8");
    private static final String PLACEHOLDER_SOURCE = "usesourcetext";

    private final CustomApiConfig.TextConfig config;
    private final OkHttpClient client;
    private final ExecutorService executor;
    private final Handler mainHandler;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private volatile boolean released = false;

    public CustomTextApiProvider(CustomApiConfig.TextConfig config) {
        this.config = config;
        this.client = new OkHttpClient.Builder()
                .connectTimeout(SOCKET_TIMEOUT, TimeUnit.SECONDS)
                .readTimeout(SOCKET_TIMEOUT, TimeUnit.SECONDS)
                .writeTimeout(SOCKET_TIMEOUT, TimeUnit.SECONDS)
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
                String result = config.method.equals("GET")
                        ? executeGetRequest(text)
                        : executePostRequest(text);
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

    private String executeGetRequest(String sourceText) throws Exception {
        HttpUrl.Builder urlBuilder = HttpUrl.parse(config.baseUrl).newBuilder();

        for (CustomApiConfig.KeyValuePair param : config.queryParams) {
            String value = PLACEHOLDER_SOURCE.equals(param.value) ? sourceText : param.value;
            urlBuilder.addQueryParameter(param.key, value);
        }

        Request.Builder requestBuilder = new Request.Builder().url(urlBuilder.build());
        for (CustomApiConfig.KeyValuePair header : config.headers) {
            requestBuilder.addHeader(header.key, header.value);
        }

        try (Response response = client.newCall(requestBuilder.get().build()).execute()) {
            if (!response.isSuccessful()) {
                throw new Exception("Unexpected response " + response.code());
            }
            String body = response.body() != null ? response.body().string() : "";
            return parseResponse(body);
        }
    }

    private String executePostRequest(String sourceText) throws Exception {
        JSONObject jsonBody = new JSONObject();
        for (CustomApiConfig.KeyValuePair field : config.jsonBody) {
            String value = PLACEHOLDER_SOURCE.equals(field.value) ? sourceText : field.value;
            jsonBody.put(field.key, value);
        }

        Request.Builder requestBuilder = new Request.Builder().url(config.baseUrl);
        for (CustomApiConfig.KeyValuePair header : config.headers) {
            requestBuilder.addHeader(header.key, header.value);
        }

        RequestBody body = RequestBody.create(jsonBody.toString(), JSON_TYPE);

        try (Response response = client.newCall(requestBuilder.post(body).build()).execute()) {
            if (!response.isSuccessful()) {
                throw new Exception("Unexpected response " + response.code());
            }
            String respBody = response.body() != null ? response.body().string() : "";
            return parseResponse(respBody);
        }
    }

    private String parseResponse(String responseBody) throws Exception {
        try {
            JSONObject json = new JSONObject(responseBody);
            return JsonPathParser.parse(json, config.jsonResponsePath);
        } catch (Exception e) {
            throw new Exception("Failed to parse response: " + e.getMessage());
        }
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
    }
}