package com.yuki.yukihub.translate;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
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
 * 聚合 AI 翻译实现（Java 移植自萌译 OpenAITranslation.kt）。
 * 保留原项目 LGPL 版权声明。
 *
 * 兼容所有 OpenAI Chat Completions 格式的 API。
 * 请求方法：POST
 * URL：{baseUrl}/chat/completions
 * 请求头：Authorization: Bearer {apiKey}, Content-Type: application/json
 * 响应 JSON：{ choices: [{ message: { content: "翻译结果" } }] }
 *
 * 支持自定义系统提示词、用户提示词、温度、额外请求参数。
 * 使用 usefromlang/usetolang/usesourcetext 占位符（与萌译一致）。
 */
public final class OpenAiTranslationProvider implements TranslationTextProvider {

    private static final String TAG = "OpenAITranslation";
    private static final long SOCKET_TIMEOUT = 30L;
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    /** 默认系统提示词（与萌译一致） */
    public static final String DEFAULT_SYSTEM_PROMPT =
            "你是一名专业翻译。你的任务是准确、自然地翻译给定的文本。\n具体规则如下： \n" +
            "1、根据用户的要求，将文本翻译成指定的目标语言；\n" +
            "2、保持原意和语气；\n" +
            "3、尽可能保持格式和结构；\n" +
            "4、直接返回翻译后的文本，不要有任何解释或附加内容；\n" +
            "5、如果文本已经是目标语言，请按原样返回。";

    /** 默认用户提示词（与萌译一致，使用 usefromlang/usetolang/usesourcetext 占位符） */
    public static final String DEFAULT_USER_PROMPT =
            "请将下面的文本从usefromlang翻译为usetolang：\n\nusesourcetext";

    /** 默认温度（留空则不发送 temperature） */
    public static final String DEFAULT_TEMPERATURE = "0.2";

    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final String systemPrompt;
    private final String userPrompt;
    private final String temperature; // 空串表示不发送
    private final List<Pair> extraParams; // 自定义请求参数

    private final OkHttpClient client;
    private final ExecutorService executor;
    private final Handler mainHandler;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    /** 键值对，用于 extraParams */
    public static final class Pair {
        public final String key;
        public final String value;
        public Pair(String key, String value) {
            this.key = key;
            this.value = value;
        }
    }

    /**
     * 将设置页里的「自定义请求参数」键值对编码为 JSON 字符串，存入 SharedPreferences。
     * 格式：[{"key":"k1","value":"v1"},{"key":"k2","value":"v2"}]
     */
    public static String encodeExtraParams(List<Pair> pairs) {
        JSONArray arr = new JSONArray();
        if (pairs == null) return arr.toString();
        for (Pair p : pairs) {
            if (p.key != null && !p.key.trim().isEmpty()) {
                JSONObject o = new JSONObject();
                try {
                    o.put("key", p.key);
                    o.put("value", p.value != null ? p.value : "");
                    arr.put(o);
                } catch (Exception e) {
                    Log.e(TAG, "encodeExtraParams error", e);
                }
            }
        }
        return arr.toString();
    }

    /**
     * 从 SharedPreferences 的 JSON 字符串还原键值对列表；为空或解析失败时返回空表。
     */
    public static List<Pair> decodeExtraParams(String json) {
        if (json == null || json.trim().isEmpty()) return new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(json);
            List<Pair> list = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                list.add(new Pair(o.optString("key", ""), o.optString("value", "")));
            }
            return list;
        } catch (Exception e) {
            Log.e(TAG, "decodeExtraParams failed", e);
            return new ArrayList<>();
        }
    }

    public OpenAiTranslationProvider(String apiKey, String baseUrl, String model,
                                     String systemPrompt, String userPrompt,
                                     String temperature, List<Pair> extraParams) {
        this.apiKey = apiKey != null ? apiKey : "";
        this.baseUrl = normalizeBaseUrl(baseUrl);
        this.model = (model != null && !model.isEmpty()) ? model : "gpt-3.5-turbo";
        this.systemPrompt = (systemPrompt != null && !systemPrompt.isEmpty())
                ? systemPrompt : DEFAULT_SYSTEM_PROMPT;
        this.userPrompt = (userPrompt != null && !userPrompt.isEmpty())
                ? userPrompt : DEFAULT_USER_PROMPT;
        this.temperature = temperature != null ? temperature.trim() : "";
        this.extraParams = extraParams != null ? extraParams : new ArrayList<>();
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

    /**
     * 获取模型列表（需要 API 支持）
     * @param apiKey API Key
     * @param baseUrl 基础 URL（留空使用默认）
     * @return 模型 ID 列表
     */
    public static List<String> fetchModels(String apiKey, String baseUrl) throws Exception {
        String url = normalizeBaseUrl(baseUrl) + "/models";
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build();
        Request request = new Request.Builder()
                .url(url)
                .get()
                .addHeader("Authorization", "Bearer " + (apiKey != null ? apiKey : ""))
                .build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Failed to get models: " + response.code());
            }
            String body = response.body() != null ? response.body().string() : "";
            JSONObject json = new JSONObject(body);
            JSONArray data = json.getJSONArray("data");
            List<String> models = new ArrayList<>();
            for (int i = 0; i < data.length(); i++) {
                models.add(data.getJSONObject(i).getString("id"));
            }
            return models;
        }
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
        // 构建翻译提示词（使用萌译的占位符替换方式）
        String fullSystemPrompt = systemPrompt;
        String fullUserPrompt = userPrompt
                .replace("usefromlang", from)
                .replace("usetolang", to)
                .replace("usesourcetext", text);

        // 构建请求体
        String requestBodyStr = buildRequestBody(fullSystemPrompt, fullUserPrompt);
        Log.d(TAG, "Request: " + requestBodyStr);

        Request request = new Request.Builder()
                .url(baseUrl + "/chat/completions")
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(requestBodyStr, JSON))
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Unexpected response " + response.code() + ": " + response.message());
            }
            String body = response.body() != null ? response.body().string() : "";
            Log.d(TAG, "Response: " + body);
            return parseResponse(body);
        }
    }

    /**
     * 构建请求体 JSON 字符串。
     * 温度留空则不发送 temperature（兼容只接受默认温度的推理模型）。
     * 合并用户自定义参数，按类型推断写入，可覆盖上面的字段（messages 除外）。
     * 这是关闭思考 / 适配新模型的通用入口：
     *   enable_thinking=false、reasoning_effort=low、
     *   max_completion_tokens=2048、top_p=0.9、
     *   chat_template_kwargs={"enable_thinking":false} 等
     */
    private String buildRequestBody(String systemPrompt, String userPrompt) throws Exception {
        JSONArray messages = new JSONArray();
        JSONObject systemMsg = new JSONObject();
        systemMsg.put("role", "system");
        systemMsg.put("content", systemPrompt);
        messages.put(systemMsg);

        JSONObject userMsg = new JSONObject();
        userMsg.put("role", "user");
        userMsg.put("content", userPrompt);
        messages.put(userMsg);

        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("messages", messages);
        body.put("stream", false);

        // 温度：空串不发，有值则转为 double
        if (!temperature.isEmpty()) {
            try {
                body.put("temperature", Double.parseDouble(temperature));
            } catch (NumberFormatException e) {
                Log.w(TAG, "Invalid temperature value: " + temperature + ", skipping");
            }
        }

        // 合并自定义参数（可覆盖上面的字段，但 messages 除外）
        for (Pair p : extraParams) {
            String k = p.key != null ? p.key.trim() : "";
            if (k.isEmpty() || k.equals("messages")) continue;
            body.put(k, inferJsonValue(p.value != null ? p.value : ""));
        }

        return body.toString();
    }

    /**
     * 把用户在设置页填入的字符串值推断成合适的 JSON 类型，使其在请求体里表达正确：
     *   true/false -> 布尔；整数/小数 -> 数字；{...}/[...] -> JSON 对象/数组；null -> JSON null；其余按字符串。
     * （与萌译 inferJsonValue 完全一致）
     */
    private Object inferJsonValue(String raw) {
        String v = raw.trim();
        if (v.isEmpty()) return "";
        if (v.equalsIgnoreCase("true")) return true;
        if (v.equalsIgnoreCase("false")) return false;
        if (v.equalsIgnoreCase("null")) return JSONObject.NULL;
        try { return Integer.parseInt(v); } catch (NumberFormatException ignored) {}
        try { return Long.parseLong(v); } catch (NumberFormatException ignored) {}
        try { return Double.parseDouble(v); } catch (NumberFormatException ignored) {}
        if (v.startsWith("{")) {
            try { return new JSONObject(v); } catch (Exception e) { return raw; }
        }
        if (v.startsWith("[")) {
            try { return new JSONArray(v); } catch (Exception e) { return raw; }
        }
        return raw;
    }

    private String parseResponse(String responseBody) throws Exception {
        JSONObject jsonObject = new JSONObject(responseBody);

        if (jsonObject.has("error")) {
            JSONObject error = jsonObject.getJSONObject("error");
            String message = error.optString("message", "Unknown error");
            String type = error.optString("type", "unknown");
            throw new IOException("OpenAI API error (" + type + "): " + message);
        }

        JSONArray choices = jsonObject.getJSONArray("choices");
        if (choices.length() == 0) {
            throw new IOException("No translation result in response");
        }

        JSONObject message = choices.getJSONObject(0).getJSONObject("message");
        String content = message.optString("content", "").trim();

        // 去除思考标签（与萌译 stripThinking 一致）
        content = stripThinking(content);

        if (content.isEmpty()) {
            throw new IOException("Empty translation result");
        }
        return content;
    }

    /**
     * 去除回答里夹带的思考：成对的 <think>...</think>，
     * 以及模板只回传闭合标签时位于开头的孤立 </think>。
     */
    private String stripThinking(String content) {
        String result = content.replaceAll("(?s)<think>.*?</think>", "").trim();
        if (result.startsWith("</think>")) {
            result = result.substring("</think>".length()).trim();
        }
        return result;
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