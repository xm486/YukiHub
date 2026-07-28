package com.yuki.yukihub.translate;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.IOException;
import java.util.Map;
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
 * 腾讯云翻译实现（Java 移植自萌译 TencentTranslationText.kt）。
 * 保留原项目 LGPL 版权声明。
 *
 * 请求方法：POST
 * URL：https://tmt.tencentcloudapi.com
 * 需要通过 TencentSign 进行 TC3-HMAC-SHA256 签名
 * 响应 JSON：{ Response: { TargetText: "翻译结果" } }
 */
public final class TencentTranslationProvider implements TranslationTextProvider {

    private static final String TAG = "Tencent";
    private static final String TRANS_API_HOST = "https://tmt.tencentcloudapi.com";
    private static final long SOCKET_TIMEOUT = 10L;
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final String secretId;
    private final String secretKey;
    private final OkHttpClient client;
    private final ExecutorService executor;
    private final Handler mainHandler;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    public TencentTranslationProvider(String secretId, String secretKey) {
        this.secretId = secretId;
        this.secretKey = secretKey;
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

    private String doTranslate(String query, String from, String to) throws Exception {
        JSONObject requestBody = new JSONObject();
        requestBody.put("SourceText", query);
        requestBody.put("Source", from);
        requestBody.put("Target", to);
        requestBody.put("ProjectId", 0);

        Map<String, String> headers = TencentSign.getSignature(secretId, secretKey, "TextTranslate", requestBody.toString());

        Request.Builder reqBuilder = new Request.Builder()
                .url(TRANS_API_HOST)
                .post(RequestBody.create(requestBody.toString(), JSON));
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            reqBuilder.addHeader(entry.getKey(), entry.getValue());
        }

        try (Response response = client.newCall(reqBuilder.build()).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Unexpected response " + response.code());
            }
            String body = response.body() != null ? response.body().string() : "";
            return parseResponse(body);
        }
    }

    private String parseResponse(String responseBody) throws Exception {
        JSONObject jsonObject = new JSONObject(responseBody);

        if (jsonObject.has("Error")) {
            JSONObject error = jsonObject.getJSONObject("Error");
            throw new IOException(error.getString("Code") + ": " + error.getString("Message"));
        }

        JSONObject resp = jsonObject.getJSONObject("Response");

        if (resp.has("Error")) {
            JSONObject error = resp.getJSONObject("Error");
            throw new IOException(error.getString("Code") + ": " + error.getString("Message"));
        }

        return resp.getString("TargetText");
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