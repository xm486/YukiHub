package com.yuki.yukihub.translate;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * DeepL 翻译实现（Java 移植自萌译 DeepLTranslation.kt）。
 * 保留原项目 LGPL 版权声明。
 *
 * 请求方法：POST
 * URL：{host}/v2/translate
 * 请求头：Authorization: DeepL-Auth-Key {apiKey}
 * 请求体 JSON：{ text: [...], source_lang, target_lang }
 * 响应 JSON：{ translations: [{ text }] }
 */
public final class DeepLProvider implements TranslationTextProvider {

    private static final String TAG = "DeepL";
    private static final long SOCKET_TIMEOUT = 10L;
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final String host;
    private final String apiKey;
    private final OkHttpClient client;
    private final ExecutorService executor;
    private final Handler mainHandler;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    public DeepLProvider(String host, String apiKey) {
        this.host = normalizeHost(host);
        this.apiKey = apiKey;
        this.client = new OkHttpClient.Builder()
                .connectTimeout(SOCKET_TIMEOUT, TimeUnit.SECONDS)
                .readTimeout(SOCKET_TIMEOUT, TimeUnit.SECONDS)
                .writeTimeout(SOCKET_TIMEOUT, TimeUnit.SECONDS)
                .build();
        this.executor = Executors.newSingleThreadExecutor();
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    private static String normalizeHost(String h) {
        String url = h.trim();
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://" + url;
        }
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    @Override
    public void translate(String text, String sourceLanguage, String targetLanguage, Callback callback) {
        cancelled.set(false);
        executor.execute(() -> {
            if (cancelled.get()) return;
            try {
                String result = doTranslate(text, sourceLanguage, targetLanguage);
                if (cancelled.get()) return;
                mainHandler.post(() -> {
                    if (!cancelled.get() && callback != null) callback.onSuccess(result);
                });
            } catch (Exception e) {
                Log.e(TAG, "Translation error", e);
                if (cancelled.get()) return;
                mainHandler.post(() -> {
                    if (!cancelled.get() && callback != null) callback.onError(e);
                });
            }
        });
    }

    private String doTranslate(String text, String from, String to) throws Exception {
        JSONObject jsonBody = new JSONObject();
        jsonBody.put("text", new JSONArray().put(text));
        jsonBody.put("source_lang", from.toUpperCase());
        jsonBody.put("target_lang", to.toUpperCase());

        Request request = new Request.Builder()
                .url(host + "/v2/translate")
                .addHeader("Authorization", "DeepL-Auth-Key " + apiKey)
                .post(RequestBody.create(jsonBody.toString(), JSON))
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Unexpected response " + response.code());
            }
            String body = response.body() != null ? response.body().string() : "";
            return parseResponse(body);
        }
    }

    private String parseResponse(String responseBody) throws Exception {
        JSONObject jsonObject = new JSONObject(responseBody);
        if (jsonObject.has("message")) {
            throw new IOException("DeepL error: " + jsonObject.getString("message"));
        }
        JSONArray translations = jsonObject.getJSONArray("translations");
        if (translations.length() == 0) {
            throw new IOException("No translation result");
        }
        return translations.getJSONObject(0).getString("text");
    }

    @Override
    public void cancel() {
        cancelled.set(true);
    }

    @Override
    public void release() {
        cancel();
        executor.shutdownNow();
    }
}
