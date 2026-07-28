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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 聚合 AI 翻译实现（Java 移植自萌译 OpenAITranslation.kt）。
 * 保留原项目 LGPL 版权声明。
 *
 * 兼容所有 OpenAI Chat Completions 格式的 API。
 * 请求方法：POST
 * URL：{baseUrl}/chat/completions
 * 请求头：Authorization: Bearer {apiKey}
 * 响应 JSON：{ choices: [{ message: { content: "翻译结果" } }] }
 */
public final class OpenAiTranslationProvider implements TranslationTextProvider {

    private static final String TAG = "OpenAI";
    private static final long SOCKET_TIMEOUT = 30L;
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final Pattern THINK_PATTERN = Pattern.compile("(?s)<think>.*?</think>");

    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final String systemPrompt;
    private final String userPrompt;
    private final Float temperature;
    private final OkHttpClient client;
    private final ExecutorService executor;
    private final Handler mainHandler;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    public OpenAiTranslationProvider(String apiKey, String baseUrl, String model,
                                     String systemPrompt, String userPrompt, Float temperature) {
        this.apiKey = apiKey;
        this.baseUrl = normalizeBaseUrl(baseUrl);
        this.model = model != null && !model.isEmpty() ? model : "gpt-3.5-turbo";
        this.systemPrompt = systemPrompt != null && !systemPrompt.isEmpty() ? systemPrompt
                : "You are a professional translator. Translate the following text accurately and naturally.";
        this.userPrompt = userPrompt != null && !userPrompt.isEmpty() ? userPrompt
                : "Translate the following text from {from} to {to}:\n\n{text}";
        this.temperature = temperature;
        this.client = new OkHttpClient.Builder()
                .connectTimeout(SOCKET_TIMEOUT, TimeUnit.SECONDS)
                .readTimeout(SOCKET_TIMEOUT, TimeUnit.SECONDS)
                .writeTimeout(SOCKET_TIMEOUT, TimeUnit.SECONDS)
                .build();
        this.executor = Executors.newSingleThreadExecutor();
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    private static String normalizeBaseUrl(String url) {
        if (url == null || url.trim().isEmpty()) return "https://api.openai.com/v1";
        String u = url.trim();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u;
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
        JSONArray messages = new JSONArray();
        JSONObject systemMsg = new JSONObject();
        systemMsg.put("role", "system");
        systemMsg.put("content", systemPrompt);
        messages.put(systemMsg);

        JSONObject userMsg = new JSONObject();
        userMsg.put("role", "user");
        String fullUserPrompt = userPrompt
                .replace("{from}", from)
                .replace("{to}", to)
                .replace("{text}", text);
        userMsg.put("content", fullUserPrompt);
        messages.put(userMsg);

        JSONObject requestBody = new JSONObject();
        requestBody.put("model", model);
        requestBody.put("messages", messages);
        requestBody.put("stream", false);
        if (temperature != null) {
            requestBody.put("temperature", temperature.doubleValue());
        }

        Request request = new Request.Builder()
                .url(baseUrl + "/chat/completions")
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(requestBody.toString(), JSON))
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Unexpected response " + response.code() + ": " + response.message());
            }
            String body = response.body() != null ? response.body().string() : "";
            return parseResponse(body);
        }
    }

    private String parseResponse(String responseBody) throws Exception {
        JSONObject jsonObject = new JSONObject(responseBody);

        if (jsonObject.has("error")) {
            JSONObject error = jsonObject.getJSONObject("error");
            throw new IOException("OpenAI API error: " + error.optString("message", "Unknown error"));
        }

        JSONArray choices = jsonObject.getJSONArray("choices");
        if (choices.length() == 0) {
            throw new IOException("No translation result in response");
        }

        JSONObject message = choices.getJSONObject(0).getJSONObject("message");
        String content = message.optString("content", "").trim();
        // 去除思考标签
        content = THINK_PATTERN.matcher(content).replaceAll("").trim();
        if (content.startsWith("</think>")) {
            content = content.substring("</think>".length()).trim();
        }

        if (content.isEmpty()) {
            throw new IOException("Empty translation result");
        }
        return content;
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