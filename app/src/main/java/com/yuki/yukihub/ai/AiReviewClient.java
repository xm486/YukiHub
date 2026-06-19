package com.yuki.yukihub.ai;

import com.yuki.yukihub.net.ApiService;
import com.yuki.yukihub.net.HttpClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.RequestBody;
import retrofit2.Retrofit;

public class AiReviewClient {
    private static final MediaType JSON_TYPE = MediaType.parse("application/json; charset=utf-8");

    private volatile ApiService apiService;
    private volatile String lastEndpoint;

    public String testConnection(AiReviewSettings settings) throws Exception {
        if (settings == null) settings = new AiReviewSettings();
        settings.normalize();
        if (settings.apiKey == null || settings.apiKey.trim().isEmpty()) throw new IllegalStateException("请先配置 AI API Key");
        JSONArray messages = new JSONArray();
        messages.put(new JSONObject()
                .put("role", "system")
                .put("content", "你是一个用于连通性测试的助手。只输出 OK。"));
        messages.put(new JSONObject()
                .put("role", "user")
                .put("content", "连接测试，请只回复 OK。"));
        String content = requestChatCompletions(settings, messages, 0f, 16);
        return content == null ? "" : content.trim();
    }

    public String requestReview(AiReviewSettings settings, WeeklyPlayStats stats) throws Exception {
        if (settings == null) settings = new AiReviewSettings();
        settings.normalize();
        if (settings.apiKey == null || settings.apiKey.trim().isEmpty()) throw new IllegalStateException("请先配置 AI API Key");

        JSONArray messages = new JSONArray();
        messages.put(new JSONObject()
                .put("role", "system")
                .put("content", AiReviewPromptBuilder.buildSystemPrompt(settings)));
        messages.put(new JSONObject()
                .put("role", "user")
                .put("content", AiReviewPromptBuilder.buildContextPrompt(stats) + "\n\n" + AiReviewPromptBuilder.buildTaskPrompt()));

        return requestChatCompletions(settings, messages, settings.temperature, 0);
    }

    private String requestChatCompletions(AiReviewSettings settings, JSONArray messages, float temperature, int maxTokens) throws Exception {
        JSONObject body = new JSONObject();
        body.put("model", settings.model);
        body.put("messages", messages);
        body.put("temperature", temperature);
        if (maxTokens > 0) body.put("max_tokens", maxTokens);
        body.put("stream", false);

        String url = settings.endpointUrl();
        String auth = "Bearer " + settings.apiKey.trim();
        RequestBody requestBody = RequestBody.create(body.toString().getBytes(StandardCharsets.UTF_8), JSON_TYPE);

        ApiService service = getService(url);
        String text = HttpClient.executeForString(service.postWithAuth(url, requestBody, auth));

        JSONObject root = text == null || text.trim().isEmpty() ? new JSONObject() : new JSONObject(text);
        JSONObject error = root.optJSONObject("error");
        if (error != null) throw new RuntimeException("AI API 错误：" + error.optString("message", error.toString()));
        JSONArray choices = root.optJSONArray("choices");
        if (choices == null || choices.length() == 0) throw new RuntimeException("AI 未返回 choices");
        JSONObject choice = choices.optJSONObject(0);
        JSONObject message = choice == null ? null : choice.optJSONObject("message");
        String content = message == null ? "" : message.optString("content", "");
        if (content == null || content.trim().isEmpty()) throw new RuntimeException("AI 返回内容为空");
        return content.trim();
    }

    private ApiService getService(String endpointUrl) {
        // 从 endpoint URL 提取 base URL
        String baseUrl = extractBaseUrl(endpointUrl);
        if (apiService == null || !baseUrl.equals(lastEndpoint)) {
            synchronized (this) {
                if (apiService == null || !baseUrl.equals(lastEndpoint)) {
                    OkHttpClient client = HttpClient.defaultBuilder()
                            .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                            .addInterceptor(chain -> chain.proceed(chain.request().newBuilder()
                                    .header("Accept", "application/json")
                                    .header("Content-Type", "application/json; charset=utf-8")
                                    .build()))
                            .build();
                    Retrofit retrofit = HttpClient.retrofit(baseUrl, client);
                    apiService = retrofit.create(ApiService.class);
                    lastEndpoint = baseUrl;
                }
            }
        }
        return apiService;
    }

    private static String extractBaseUrl(String url) {
        if (url == null) return "https://api.openai.com/";
        try {
            java.net.URL u = new java.net.URL(url);
            String base = u.getProtocol() + "://" + u.getHost();
            if (u.getPort() != -1) base += ":" + u.getPort();
            return base + "/";
        } catch (Exception e) {
            return "https://api.openai.com/";
        }
    }
}
