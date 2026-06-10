package com.yuki.yukihub.ai;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class AiReviewClient {
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

        HttpURLConnection c = (HttpURLConnection) new URL(settings.endpointUrl()).openConnection();
        c.setRequestMethod("POST");
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(15000);
        c.setReadTimeout(60000);
        c.setDoOutput(true);
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        c.setRequestProperty("Authorization", "Bearer " + settings.apiKey.trim());
        c.setRequestProperty("User-Agent", "YukiHub/1.0 (Android AI Review)");
        byte[] data = body.toString().getBytes(StandardCharsets.UTF_8);
        c.setFixedLengthStreamingMode(data.length);
        try (OutputStream os = new BufferedOutputStream(c.getOutputStream())) {
            os.write(data);
        }
        int code = c.getResponseCode();
        String text = readSmallText(code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream());
        if (code < 200 || code >= 300) throw new RuntimeException("AI API HTTP " + code + ": " + text);
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

    private static String readSmallText(InputStream is) throws Exception {
        if (is == null) return "";
        try (InputStream in = is; ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int len;
            int total = 0;
            while ((len = in.read(buf)) != -1) {
                total += len;
                if (total > 1024 * 1024) throw new RuntimeException("响应过大");
                bos.write(buf, 0, len);
            }
            return bos.toString("UTF-8");
        }
    }
}