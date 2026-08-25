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
    private static final String KEY_AUTH_REFRESH_TOKEN = "auth_refresh_token";
    private static final String KEY_AUTH_NICKNAME = "auth_nickname";
    private static final String KEY_AUTH_AVATAR = "auth_avatar";
    private static final String KEY_AUTH_UID = "auth_uid";
    private static final String AUTH_BASE_URL = "https://yukihub.zh.kg/api";
    private static final int CONNECT_TIMEOUT = 12_000;
    private static final int READ_TIMEOUT = 15_000;

    private final Context appContext;
    private int lastPendingRequests = 0;

    /** 账号被禁用异常，上层应清除登录状态并提示用户 */
    public static class AccountDisabledException extends RuntimeException {
        public AccountDisabledException(String message) { super(message); }
    }

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
            if (code == 403 && body.contains("ACCOUNT_DISABLED")) {
                clearAuthSession();
                throw new AccountDisabledException("该账号已被禁用");
            }
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
            if (code == 403 && respBody.contains("ACCOUNT_DISABLED")) {
                clearAuthSession();
                throw new AccountDisabledException("该账号已被禁用");
            }
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

    /** 清除本地登录状态（账号被禁用时调用） */
    private void clearAuthSession() {
        try {
            SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit()
                    .remove(KEY_AUTH_ACCESS_TOKEN)
                    .remove(KEY_AUTH_REFRESH_TOKEN)
                    .remove(KEY_AUTH_NICKNAME)
                    .remove(KEY_AUTH_AVATAR)
                    .remove(KEY_AUTH_UID)
                    .apply();
            Log.w(TAG, "Auth session cleared: account disabled");
        } catch (Throwable ignored) {}
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

    /** 通过 UID 接受好友请求（资料页用） */
    public boolean acceptFriendRequestByUid(int uid) throws Exception {
        JSONObject body = new JSONObject();
        body.put("uid", uid);
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

    /** 表情包条目 */
    public static class EmojiInfo {
        public final String name;
        public final String url;
        public EmojiInfo(String name, String url) { this.name = name; this.url = url; }
    }

    /** 获取服务器表情包列表 */
    public java.util.List<EmojiInfo> getEmojiList() throws Exception {
        String resp = doGet("/chat/emojis", null);
        JSONObject root = new JSONObject(resp);
        JSONArray arr = root.optJSONArray("emojis");
        java.util.List<EmojiInfo> list = new java.util.ArrayList<>();
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject e = arr.getJSONObject(i);
                list.add(new EmojiInfo(e.optString("name"), e.optString("url")));
            }
        }
        return list;
    }

    /** 发送消息 */
    public ChatMessage sendMessage(String receiverId, String content) throws Exception {
        return sendMessage(receiverId, content, "text");
    }

    /** 发送消息（支持指定 msgType） */
    public ChatMessage sendMessage(String receiverId, String content, String msgType) throws Exception {
        return sendMessage(receiverId, content, msgType, 0);
    }

    /**
     * 发送消息（完整版）
     * @param replyToId 被回复的消息 id（0 = 普通消息），服务端据此给对方发「回复了你」通知
     */
    public ChatMessage sendMessage(String receiverId, String content, String msgType, int replyToId) throws Exception {
        JSONObject body = new JSONObject();
        body.put("receiverId", receiverId);
        body.put("content", content);
        body.put("msgType", msgType);
        if (replyToId > 0) body.put("replyToId", replyToId);
        String resp = doPost("/chat/send", body);
        JSONObject root = new JSONObject(resp);
        JSONObject msg = root.optJSONObject("message");
        if (msg == null) throw new RuntimeException("发送消息失败");
        ChatMessage parsed = parseMessage(msg);
        // 双保险：服务端已返回 isMine=true，这里再兜一层，
        // 避免老版本服务端漏字段导致本地缓存把自己的消息标成对方发的
        parsed.isMine = true;
        return parsed;
    }

    /**
     * 上传聊天图片（≤500KB，jpg/png/webp）
     * @param data 已压缩的图片字节
     * @param mimeType image/jpeg 等
     * @return 服务器返回的相对 URL（如 /uploads/chat/xxx/1_ab.jpg）
     */
    public String uploadChatImage(byte[] data, String mimeType) throws Exception {
        if (data == null || data.length == 0) throw new IllegalArgumentException("图片数据为空");
        if (data.length > 500 * 1024) throw new IllegalArgumentException("图片超过 500KB");
        String token = getToken();
        if (token == null) throw new IllegalStateException("未登录");

        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(AUTH_BASE_URL + "/chat/upload_image").openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(30_000);
            conn.setDoOutput(true);
            conn.setFixedLengthStreamingMode(data.length);
            conn.setRequestProperty("Content-Type", mimeType == null ? "image/jpeg" : mimeType);
            conn.setRequestProperty("Authorization", "Bearer " + token);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(data);
                os.flush();
            }
            int code = conn.getResponseCode();
            String respBody = readAll(conn, code);
            if (code == 403 && respBody.contains("ACCOUNT_DISABLED")) {
                clearAuthSession();
                throw new AccountDisabledException("该账号已被禁用");
            }
            if (code != 200 && code != 201) {
                throw new RuntimeException(extractError(respBody));
            }
            JSONObject root = new JSONObject(respBody);
            String url = root.optString("url", "");
            if (url.isEmpty()) throw new RuntimeException("上传失败：服务器未返回图片地址");
            return url;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * 举报聊天消息
     * @param scene "chat"（私聊）或 "group"（群聊）
     * @param messageId 被举报的消息 id
     * @param groupId scene=group 时必填
     * @param reason 举报理由
     */
    public String reportMessage(String scene, int messageId, int groupId, String reason) throws Exception {
        JSONObject body = new JSONObject();
        body.put("scene", scene);
        body.put("messageId", messageId);
        if (groupId > 0) body.put("groupId", groupId);
        body.put("reason", reason == null ? "" : reason);
        String resp = doPost("/community/report", body);
        JSONObject root = new JSONObject(resp);
        if (!root.optBoolean("success", false)) {
            throw new RuntimeException(root.optString("error", "举报失败"));
        }
        return root.optString("message", "举报已提交");
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
        return pollNewMessages(afterId, friendId, false);
    }

    /**
     * 轮询新消息
     * @param peek true = 只读不标记已读（后台通知轮询用，否则未读红点会被通知轮询清掉）
     */
    public List<ChatMessage> pollNewMessages(int afterId, String friendId, boolean peek) throws Exception {
        String params = "afterId=" + afterId;
        if (friendId != null && !friendId.isEmpty()) {
            params += "&friendId=" + URLEncoder.encode(friendId, "UTF-8");
        }
        if (peek) params += "&peek=1";
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

    /**
     * 修改昵称（云端）
     * 成功返回服务器最新的 nickname，失败抛异常。
     */
    public String updateNickname(String nickname) throws Exception {
        JSONObject body = new JSONObject();
        body.put("nickname", nickname == null ? "" : nickname);
        String resp = doPost("/user/update_nickname", body);
        JSONObject root = new JSONObject(resp);
        if (!root.optBoolean("success", false)) {
            throw new RuntimeException(root.optString("error", "修改失败"));
        }
        JSONObject user = root.optJSONObject("user");
        return user != null ? user.optString("nickname", nickname) : nickname;
    }

    // ==================== 签到 / 等级 / 经验 ====================

    /** 每日签到 */
    public JSONObject checkin() throws Exception {
        String resp = doPost("/community/checkin", new JSONObject());
        return new JSONObject(resp);
    }

    /** 查询我的等级 / 经验 / 签到状态 */
    public JSONObject getMyLevel() throws Exception {
        String resp = doGet("/user/level", null);
        return new JSONObject(resp);
    }

    /**
     * 上报游戏游玩时长（游戏会话结束时调用，用于获取游戏经验）
     * @param sessionUuid 会话唯一ID（防重复上报）
     * @param gameKey 游戏权威 key（可空）
     * @param title 游戏标题
     * @param startTime 会话开始时间戳(ms)
     * @param endTime 会话结束时间戳(ms)
     */
    public JSONObject reportPlayTime(String sessionUuid, String gameKey, String title, long startTime, long endTime) throws Exception {
        JSONObject body = new JSONObject();
        body.put("sessionUuid", sessionUuid == null ? "" : sessionUuid);
        body.put("gameKey", gameKey == null ? "" : gameKey);
        body.put("title", title == null ? "" : title);
        body.put("startTime", startTime);
        body.put("endTime", endTime);
        String resp = doPost("/game/play_report", body);
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
        msg.replyToId = obj.optInt("replyToId", 0);
        return msg;
    }

    // ==================== 群组 API ====================

    /** 获取群组列表 */
    public List<GroupInfo> getGroupsList() throws Exception {
        String resp = doGet("/groups/list", null);
        JSONObject root = new JSONObject(resp);
        JSONArray arr = root.optJSONArray("groups");
        List<GroupInfo> groups = new ArrayList<>();
        if (arr == null) return groups;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject g = arr.getJSONObject(i);
            GroupInfo info = new GroupInfo();
            info.id = g.optInt("id", 0);
            info.name = g.optString("name", "");
            info.icon = g.optString("icon", "🏛");
            info.type = g.optString("type", "chat");
            info.description = g.optString("description", "");
            info.memberRole = g.optString("memberRole", "member");
            info.unreadCount = g.optInt("unreadCount", 0);
            info.lastMessage = g.optString("lastMessage", "");
            info.lastMessageTime = g.optString("lastMessageTime", "");
            groups.add(info);
        }
        return groups;
    }

    /** 获取群组聊天历史 */
    public GroupHistoryResult getGroupMessages(int groupId, int offset, int limit) throws Exception {
        String params = "groupId=" + groupId + "&offset=" + offset + "&limit=" + limit;
        String resp = doGet("/groups/messages", params);
        JSONObject root = new JSONObject(resp);
        JSONArray arr = root.optJSONArray("messages");
        int onlineCount = root.optInt("onlineCount", 0);
        List<GroupMessage> messages = new ArrayList<>();
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                messages.add(parseGroupMessage(arr.getJSONObject(i)));
            }
        }
        return new GroupHistoryResult(messages, onlineCount);
    }

    /** 群组历史结果（消息 + 在线人数） */
    public static class GroupHistoryResult {
        public final List<GroupMessage> messages;
        public final int onlineCount;
        public GroupHistoryResult(List<GroupMessage> messages, int onlineCount) {
            this.messages = messages;
            this.onlineCount = onlineCount;
        }
    }

    /** 发送群组消息 */
    public GroupMessage sendGroupMessage(int groupId, String content) throws Exception {
        return sendGroupMessage(groupId, content, "text");
    }

    /** 发送群组消息（支持指定 msgType） */
    public GroupMessage sendGroupMessage(int groupId, String content, String msgType) throws Exception {
        return sendGroupMessage(groupId, content, msgType, 0);
    }

    /**
     * 发送群组消息（完整版）
     * @param replyToId 被回复的消息 id（0 = 普通发言）。群聊只有被回复才会给对方推通知
     */
    public GroupMessage sendGroupMessage(int groupId, String content, String msgType, int replyToId) throws Exception {
        JSONObject body = new JSONObject();
        body.put("groupId", groupId);
        body.put("content", content);
        body.put("msgType", msgType);
        if (replyToId > 0) body.put("replyToId", replyToId);
        String resp = doPost("/groups/send", body);
        JSONObject root = new JSONObject(resp);
        JSONObject msg = root.optJSONObject("message");
        if (msg == null) throw new RuntimeException("发送消息失败");
        GroupMessage parsed = parseGroupMessage(msg);
        parsed.isMine = true;
        return parsed;
    }

    /** 群聊「被回复」提醒条目 */
    public static class ReplyAlert {
        public int id;
        public int groupId;
        public String groupName;
        public String groupIcon;
        public int messageId;
        public String actorNickname;
        public String summary;
        /** "reply" = 回复了你，"mention" = @了你 */
        public String kind = "reply";
    }

    /** 拉取群聊被回复提醒（未读） */
    public List<ReplyAlert> getGroupReplyAlerts() throws Exception {
        String resp = doGet("/groups/reply_alerts", null);
        JSONObject root = new JSONObject(resp);
        JSONArray arr = root.optJSONArray("alerts");
        List<ReplyAlert> list = new ArrayList<>();
        if (arr == null) return list;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            ReplyAlert a = new ReplyAlert();
            a.id = o.optInt("id", 0);
            a.groupId = o.optInt("groupId", 0);
            a.groupName = o.optString("groupName", "群聊");
            a.groupIcon = o.optString("groupIcon", "");
            a.messageId = o.optInt("messageId", 0);
            a.actorNickname = o.optString("actorNickname", "");
            a.summary = o.optString("summary", "");
            a.kind = o.optString("kind", "reply");
            list.add(a);
        }
        return list;
    }

    /** 标记群聊被回复提醒为已读（避免重复弹通知） */
    public void markGroupReplyAlertsRead(List<Integer> ids) throws Exception {
        JSONObject body = new JSONObject();
        if (ids == null || ids.isEmpty()) {
            body.put("all", true);
        } else {
            JSONArray arr = new JSONArray();
            for (Integer id : ids) if (id != null && id > 0) arr.put((int) id);
            body.put("ids", arr);
        }
        doPost("/groups/reply_alerts", body);
    }

    /** 轮询群组新消息 */
    public GroupPollResult pollGroupMessages(int groupId, int afterId) throws Exception {
        String params = "groupId=" + groupId + "&afterId=" + afterId;
        String resp = doGet("/groups/poll", params);
        JSONObject root = new JSONObject(resp);
        JSONArray arr = root.optJSONArray("messages");
        List<GroupMessage> messages = new ArrayList<>();
        if (arr == null) return new GroupPollResult(messages, root.optInt("onlineCount", 0));
        for (int i = 0; i < arr.length(); i++) {
            messages.add(parseGroupMessage(arr.getJSONObject(i)));
        }
        return new GroupPollResult(messages, root.optInt("onlineCount", 0));
    }

    /** 群组轮询结果（消息 + 在线人数） */
    public static class GroupPollResult {
        public final List<GroupMessage> messages;
        public final int onlineCount;
        public GroupPollResult(List<GroupMessage> messages, int onlineCount) {
            this.messages = messages;
            this.onlineCount = onlineCount;
        }
    }

    /** 管理群组消息（撤回/删除） */
    public boolean manageGroupMessage(int messageId, String action) throws Exception {
        JSONObject body = new JSONObject();
        body.put("messageId", messageId);
        body.put("action", action);
        String resp = doPost("/groups/manage", body);
        JSONObject root = new JSONObject(resp);
        return root.optBoolean("success", false);
    }

    private GroupMessage parseGroupMessage(JSONObject obj) {
        GroupMessage msg = new GroupMessage();
        msg.id = obj.optInt("id", 0);
        msg.senderId = obj.optString("senderId", "");
        msg.senderNickname = obj.optString("senderNickname", "");
        msg.senderAvatar = obj.optString("senderAvatar", "");
        msg.senderUid = obj.optInt("senderUid", 0);
        msg.senderIsAdmin = obj.optBoolean("senderIsAdmin", false);
        msg.senderLevel = obj.optInt("senderLevel", 0);
        msg.content = obj.optString("content", "");
        msg.msgType = obj.optString("msgType", "text");
        msg.createdAt = obj.optString("createdAt", "");
        msg.recalled = obj.optBoolean("recalled", false);
        msg.deleted = obj.optBoolean("deleted", false);
        msg.isMine = obj.optBoolean("isMine", false);
        msg.replyToId = obj.optInt("replyToId", 0);
        return msg;
    }
}