package com.yuki.yukihub.translate;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 百度翻译 API 文本翻译实现（Java 移植自萌译 BaiduTranslationText.kt）。
 * 保留原项目 LGPL 版权声明。
 *
 * 请求方法：GET
 * URL：https://fanyi-api.baidu.com/api/trans/vip/translate?q=...&from=...&to=...&appid=...&salt=...&sign=...
 * 签名：MD5(appid + query + salt + secretKey)
 * 响应 JSON：{ trans_result: [{ dst: "翻译结果" }] }
 */
public final class BaiduTranslationProvider implements TranslationTextProvider {

    private static final String TAG = "Baidu";
    private static final String TRANS_API_HOST = "https://fanyi-api.baidu.com/api/trans/vip/translate";
    private static final long SOCKET_TIMEOUT = 10L;

    private final String appId;
    private final String secretKey;
    private final OkHttpClient client;
    private final ExecutorService executor;
    private final Handler mainHandler;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    public BaiduTranslationProvider(String appId, String secretKey) {
        this.appId = appId;
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
        String salt = String.valueOf(System.currentTimeMillis());
        String sign = getMD5(appId + query + salt + secretKey);

        HttpUrl.Builder urlBuilder = HttpUrl.parse(TRANS_API_HOST).newBuilder()
                .addQueryParameter("q", query)
                .addQueryParameter("from", modifyLanguage(from))
                .addQueryParameter("to", modifyLanguage(to))
                .addQueryParameter("appid", appId)
                .addQueryParameter("salt", salt)
                .addQueryParameter("sign", sign);

        Request request = new Request.Builder()
                .url(urlBuilder.build())
                .get()
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
            throw new IOException(jsonObject.getString("error_msg"));
        }
        org.json.JSONArray transResult = jsonObject.getJSONArray("trans_result");
        if (transResult.length() == 0) {
            throw new IOException("Empty translation result");
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < transResult.length(); i++) {
            if (i > 0) sb.append("\n");
            sb.append(transResult.getJSONObject(i).getString("dst"));
        }
        return sb.toString();
    }

    private String modifyLanguage(String lang) {
        switch (lang) {
            case "ko": return "kor";
            case "ja": return "jp";
            case "zh-TW": return "cht";
            case "fr": return "fra";
            case "es": return "spa";
            case "vi": return "vie";
            case "sv": return "swe";
            case "ar": return "ara";
            case "bg": return "bul";
            case "fi": return "fin";
            case "sl": return "slo";
            case "da": return "dan";
            case "ro": return "rom";
            case "et": return "est";
            default: return lang;
        }
    }

    private static String getMD5(String str) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] bytes = str.getBytes(Charset.forName("UTF-8"));
        md.update(bytes);
        byte[] digest = md.digest();
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
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