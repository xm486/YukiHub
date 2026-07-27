package com.yuki.yukihub.social;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 好友/聊天 API 客户端。
 * 统一管理所有社交相关的网络请求，所有方法在调用线程同步执行（应在 IO 线程调用）。
 */
public class SocialApiClient {

    private static final String TAG = "SocialApiClient";
    private static final String PREFS_NAME = "yukihub_prefs";
    private static final String KEY_AUTH_ACCESS_TOKEN = "auth_access_token";
    private static final String AUTH_BASE_URL = "https://yukihub.zh.kg/api";
    private static final int CONNECT_TIMEOUT = 12_000;
    private static final int READ_TIMEOUT = 15_000;

    private final Context appContext;
    private int lastPendingRequests = 0;

    public SocialApiClient(Context context) {
        this.appContext = context.getApplicationContext();
    }

    // ==================== Token ====================

    private String getToken() {
        SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String token = prefs.getString(KEY_AUTH_ACCESS_TOKEN, "");
        return (token != null && !token.trim().isEmpty()) ? token.trim() : null;
    }

    // ==================== 请求方法 ====================

    private String doGet(String path, String queryParams) throws Exception {
        String token = getToken();
        if (token == null) throw new IllegalStateException("未登录");

        String url = AUTH_BASE_URL + path;
        if (queryParams != null && !queryParams.isEmpty()) url += "?" + queryParams;

        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT);
            conn.setRequestProperty("Authorization", "Bearer " + token);

            int code = conn.getResponseCode();
            String body = readAll(conn, code);
            if (code != 200) {
                throw new RuntimeException("HTTP " + code + (body.isEmpty() ? "" : ": " + extractError(body)));
            }
            return body;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private String doPost(String path, JSONObject body) throws Exception {
        String token = getToken();
        if (token == null) throw new IllegalStateException("未登录");

        String url = AUTH_BASE_URL + path;
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Authorization", "Bearer " + token);

            byte[] bodyBytes = (body != null ? body.toString() : "{}").getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(bodyBytes);
            }

            int code = conn.getResponseCode();
            String respBody = readAll(conn, code);
            if (code != 200 && code != 201) {
                throw new RuntimeException("HTTP " + code + (respBody.isEmpty() ? "" : ": " + extractError(respBody)));
            }
            return respBody;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private String readAll(HttpURLConnection conn, int code) throws Exception {
        InputStream is = null;
        try {
            is = (code >= 200 && code < 400) ? conn.getInputStream() : conn.getErrorStream();
            if (is == null) return "";
            BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(line);
            }
            return sb.toString();
        } finally {
            if (is != null) try { is.close(); } catch (Throwable ignored) {}
        }
    }

    private String extractError(String json) {
        try {
            JSONObject obj = new JSONObject(json);
            return obj.optString("error", json);
        } catch (Throwable t) {
            return json;
        }
    }

    // ==================== 好友 API ====================

    /** 获取好友列表 */
    public List<FriendInfo> getFriendsList() throws Exception {
        String resp = doGet("/friends/list", null);
        JSONObject root = new JSONObject(resp);
        lastPendingRequests = root.optInt("pendingRequests", 0);
        JSONArray arr = root.optJSONArray("friends");
        List<FriendInfo> friends = new ArrayList<>();
        if (arr == null) return friends;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject f = arr.getJSONObject(i);
            FriendInfo info = new FriendInfo();
            info.id = f.optString("id", "");
            info.uid = f.optInt("uid", 0);
            info.nickname = f.optString("nickname", "");
            info.avatarUrl = f.optString("avatarUrl", "");
            info.signature = f.optString("signature", "");
            info.status = f.optString("status", "offline");
            info.activity = f.optString("activity", "");
            info.unreadCount = f.optInt("unreadCount", 0);
            info.lastHeartbeat = f.optString("lastHeartbeat", "");
            info.friendSince = f.optString("friendSince", "");
            info.note = f.optString("note", "");
            friends.add(info);
        }
        return friends;
    }

    /** 获取待处理请求的 pending 数量（复用上次 getFriendsList 的结果） */
    public int getPendingRequestsCount() {
        return lastPendingRequests;
    }

    /** 获取好友请求列表 */
    public JSONObject getFriendRequests() throws Exception {
        String resp = doGet("/friends/requests", null);
        return new JSONObject(resp);
    }

    /** 发送好友请求 */
    public boolean sendFriendRequest(String target) throws Exception {
        JSONObject body = new JSONObject();
        body.put("target", target);
        String resp = doPost("/friends/request", body);
        JSONObject root = new JSONObject(resp);
        return root.optBoolean("success", false);
    }

    /** 接受好友请求 */
    public boolean acceptFriendRequest(int friendshipId) throws Exception {
        JSONObject body = new JSONObject();
        body.put("friendshipId", friendshipId);
        String resp = doPost("/friends/accept", body);
        JSONObject root = new JSONObject(resp);
        return root.optBoolean("success", false);
    }

    /** 拒绝好友请求 */
    public boolean rejectFriendRequest(int friendshipId) throws Exception {
        JSONObject body = new JSONObject();
        body.put("friendshipId", friendshipId);
        String resp = doPost("/friends/reject", body);
        JSONObject root = new JSONObject(resp);
        return root.optBoolean("success", false);
    }

    /** 删除好友 */
    public boolean removeFriend(String friendId) throws Exception {
        JSONObject body = new JSONObject();
        body.put("friendId", friendId);
        String resp = doPost("/friends/remove", body);
        JSONObject root = new JSONObject(resp);
        return root.optBoolean("success", false);
    }

    /** 设置好友备注 */
    public boolean setFriendNote(String friendId, String note) throws Exception {
        JSONObject body = new JSONObject();
        body.put("friendId", friendId);
        body.put("note", note == null ? "" : note);
        String resp = doPost("/friends/note", body);
        JSONObject root = new JSONObject(resp);
        return root.optBoolean("success", false);
    }

    /** 搜索用户 */
    public JSONArray searchUsers(String keyword) throws Exception {
        String resp = doGet("/friends/search", "q=" + URLEncoder.encode(keyword, "UTF-8"));
        JSONObject root = new JSONObject(resp);
        return root.optJSONArray("results");
    }

    // ==================== 聊天 API ====================

    /** 发送消息 */
    public ChatMessage sendMessage(String receiverId, String content) throws Exception {
        JSONObject body = new JSONObject();
        body.put("receiverId", receiverId);
        body.put("content", content);
        body.put("msgType", "text");
        String resp = doPost("/chat/send", body);
        JSONObject root = new JSONObject(resp);
        JSONObject msg = root.optJSONObject("message");
        if (msg == null) throw new RuntimeException("发送消息失败");
        return parseMessage(msg);
    }

    /** 获取聊天历史 */
    public List<ChatMessage> getChatHistory(String friendId, int offset, int limit) throws Exception {
        String params = "friendId=" + URLEncoder.encode(friendId, "UTF-8")
                + "&offset=" + offset + "&limit=" + limit;
        String resp = doGet("/chat/history", params);
        JSONObject root = new JSONObject(resp);
        JSONArray arr = root.optJSONArray("messages");
        List<ChatMessage> messages = new ArrayList<>();
        if (arr == null) return messages;
        for (int i = 0; i < arr.length(); i++) {
            messages.add(parseMessage(arr.getJSONObject(i)));
        }
        return messages;
    }

    /** 轮询新消息 */
    public List<ChatMessage> pollNewMessages(int afterId, String friendId) throws Exception {
        String params = "afterId=" + afterId;
        if (friendId != null && !friendId.isEmpty()) {
            params += "&friendId=" + URLEncoder.encode(friendId, "UTF-8");
        }
        String resp = doGet("/chat/poll", params);
        JSONObject root = new JSONObject(resp);
        JSONArray arr = root.optJSONArray("messages");
        List<ChatMessage> messages = new ArrayList<>();
        if (arr == null) return messages;
        for (int i = 0; i < arr.length(); i++) {
            messages.add(parseMessage(arr.getJSONObject(i)));
        }
        return messages;
    }

    /** 获取未读消息总数 */
    public int getTotalUnread() throws Exception {
        String resp = doGet("/chat/unread", null);
        JSONObject root = new JSONObject(resp);
        return root.optInt("totalUnread", 0);
    }

    /** 获取用户公开资料 */
    public JSONObject getUserProfile(int uid) throws Exception {
        String resp = doGet("/user/profile", "uid=" + uid);
        return new JSONObject(resp);
    }

    private ChatMessage parseMessage(JSONObject obj) {
        ChatMessage msg = new ChatMessage();
        msg.id = obj.optInt("id", 0);
        msg.senderId = obj.optString("senderId", "");
        msg.receiverId = obj.optString("receiverId", "");
        msg.content = obj.optString("content", "");
        msg.msgType = obj.optString("msgType", "text");
        msg.createdAt = obj.optString("createdAt", "");
        msg.isMine = obj.optBoolean("isMine", false);
        return msg;
    }
}