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

import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Azure AI Translator 翻译实现（Java 移植自萌译 AzureTranslation.kt）。
 * 保留原项目 LGPL 版权声明。
 *
 * 请求方法：POST
 * URL：https://api.cognitive.microsofttranslator.com/translate?api-version=3.0&to={to}&from={from}
 * 请求头：Ocp-Apim-Subscription-Key: {subscriptionKey}
 * 请求体 JSON：[{ "Text": "text" }]
 * 响应 JSON：[{ "translations": [{ "text" }] }]
 */
public final class AzureTranslationProvider implements TranslationTextProvider {

    private static final String TAG = "Azure";
    private static final String API_HOST = "https://api.cognitive.microsofttranslator.com/translate";
    private static final String API_VERSION = "3.0";
    private static final long SOCKET_TIMEOUT = 10L;
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final String subscriptionKey;
    private final OkHttpClient client;
    private final ExecutorService executor;
    private final Handler mainHandler;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    public AzureTranslationProvider(String subscriptionKey) {
        this.subscriptionKey = subscriptionKey;
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
        HttpUrl url = HttpUrl.parse(API_HOST).newBuilder()
                .addQueryParameter("api-version", API_VERSION)
                .addQueryParameter("to", modifyLanguage(to))
                .addQueryParameter("from", modifyLanguage(from))
                .addQueryParameter("textType", "plain")
                .addQueryParameter("profanityAction", "NoAction")
                .build();

        JSONArray jsonBody = new JSONArray();
        JSONObject textObj = new JSONObject();
        textObj.put("Text", text);
        jsonBody.put(textObj);

        Request request = new Request.Builder()
                .url(url)
                .addHeader("Ocp-Apim-Subscription-Key", subscriptionKey)
                .addHeader("Content-Type", "application/json; charset=UTF-8")
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
        JSONArray jsonArray = new JSONArray(responseBody);
        if (jsonArray.length() == 0) {
            throw new IOException("Empty translation response");
        }
        JSONObject translationObject = jsonArray.getJSONObject(0);
        if (translationObject.has("error")) {
            JSONObject error = translationObject.getJSONObject("error");
            throw new IOException("Translation error (code: " + error.getString("code") + "): " + error.getString("message"));
        }
        JSONArray translations = translationObject.getJSONArray("translations");
        if (translations.length() == 0) {
            throw new IOException("No translation results");
        }
        return translations.getJSONObject(0).getString("text");
    }

    private String modifyLanguage(String lang) {
        if ("zh".equals(lang)) return "zh-Hans";
        if ("zh-TW".equals(lang)) return "zh-Hant";
        return lang;
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
