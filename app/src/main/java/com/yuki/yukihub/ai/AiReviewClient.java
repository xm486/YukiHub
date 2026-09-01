package com.yuki.yukihub.ai;

import com.yuki.yukihub.net.ApiService;
import com.yuki.yukihub.net.HttpClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.RequestBody;
import retrofit2.Retrofit;

public class AiReviewClient {
    private static final MediaType JSON_TYPE = MediaType.parse("application/json; charset=utf-8");

    private static class ServiceHolder {
        final ApiService service;
        final String endpoint;

        ServiceHolder(ApiService service, String endpoint) {
            this.service = service;
            this.endpoint = endpoint;
        }
    }

    private static final AtomicReference<ServiceHolder> holderRef = new AtomicReference<>();

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
        // 连通性测试要的是「能不能通」，一次瞬时网络抖动不该判定为配置错误。
        // 只对连接层异常重试，鉴权失败、模型不存在这类业务错误立即抛出。
        Exception last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                String content = requestChatCompletions(settings, messages, 0f, 16);
                return content == null ? "" : content.trim();
            } catch (java.io.IOException e) {
                last = e;
                // 服务端明确返回了 HTTP 状态码，说明链路是通的，属于配置问题，不重试
                if (e.getMessage() != null && e.getMessage().startsWith("HTTP ")) throw e;
                if (attempt < 2) {
                    try { Thread.sleep(400L * (attempt + 1)); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                }
            }
        }
        throw last != null ? last : new RuntimeException("AI 连接测试失败");
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

        // 与 testConnection 同一策略：连接层抖动重试，业务错误直接抛。
        // 生成一次点评耗时较长，重试次数压到 2 次避免用户干等。
        Exception last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                return requestChatCompletions(settings, messages, settings.temperature, 0);
            } catch (java.io.IOException e) {
                last = e;
                if (e.getMessage() != null && e.getMessage().startsWith("HTTP ")) throw e;
                if (attempt < 1) {
                    try { Thread.sleep(500L); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                }
            }
        }
        throw last != null ? last : new RuntimeException("AI 点评生成失败");
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
        String baseUrl = extractBaseUrl(endpointUrl);
        ServiceHolder holder = holderRef.get();
        if (holder != null && baseUrl.equals(holder.endpoint)) {
            return holder.service;
        }
        synchronized (AiReviewClient.class) {
            holder = holderRef.get();
            if (holder != null && baseUrl.equals(holder.endpoint)) {
                return holder.service;
            }
            OkHttpClient client = HttpClient.defaultBuilder()
                    .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                    // HttpClient 全局关掉了 retryOnConnectionFailure，AI 这条链路要单独放开：
                    // 连接池里的 keep-alive 连接被服务端静默回收后，复用会拿到死连接直接抛 IO，
                    // 表现就是「隔一会儿点测试必失败、马上再点又成功」。放开后 OkHttp 会自动换新连接重试。
                    .retryOnConnectionFailure(true)
                    .addInterceptor(chain -> chain.proceed(chain.request().newBuilder()
                            .header("Accept", "application/json")
                            .build()))
                    .build();
            Retrofit retrofit = HttpClient.retrofit(baseUrl, client);
            ApiService service = retrofit.create(ApiService.class);
            holderRef.set(new ServiceHolder(service, baseUrl));
            return service;
        }
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
