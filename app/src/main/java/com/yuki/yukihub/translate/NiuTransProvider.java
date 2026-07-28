package com.yuki.yukihub.translate;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

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
 * 小牛翻译的文本翻译实现（Java 移植自萌译 NiuTranslation.kt）。
 * 保留原项目 LGPL 版权声明。
 *
 * 请求方法：POST
 * URL：https://api.niutrans.com/NiuTransServer/translation
 * 请求体 JSON：{ from, to, apikey, src_text }
 * 响应 JSON：{ tgt_text }
 */
public final class NiuTransProvider implements TranslationTextProvider {

    private static final String TAG = "NiuTrans";
    private static final String API_HOST = "https://api.niutrans.com/NiuTransServer/translation";
    private static final long SOCKET_TIMEOUT = 10L;
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final String apiKey;
    private final OkHttpClient client;
    private final ExecutorService executor;
    private final Handler mainHandler;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    public NiuTransProvider(String apiKey) {
        this.apiKey = apiKey;
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
        JSONObject jsonBody = new JSONObject();
        jsonBody.put("from", modifyLanguage(from));
        jsonBody.put("to", modifyLanguage(to));
        jsonBody.put("apikey", apiKey);
        jsonBody.put("src_text", text);

        Request request = new Request.Builder()
                .url(API_HOST)
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
        if (jsonObject.has("error_code")) {
            throw new IOException("Translation error (code: " + jsonObject.getString("error_code") + "): " + jsonObject.getString("error_msg"));
        }
        return jsonObject.getString("tgt_text");
    }

    private String modifyLanguage(String lang) {
        if ("zh-TW".equals(lang)) return "cht";
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
