package com.yuki.yukihub.social;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import com.yuki.yukihub.MainActivity;
import com.yuki.yukihub.R;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 聊天消息通知。
 *
 * 推送策略（与欧尼确认的口径）：
 * - 私聊：好友发来的每条新消息都弹通知；
 * - 群聊：只有「有人回复了我的消息」才弹，普通群发言不推（否则刷屏）。
 *
 * 抑制规则：当前正在该会话界面里时不弹（由 {@link #setActiveConversation} 标记）。
 */
public class ChatNotifier {

    private static final String TAG = "ChatNotifier";

    public static final String CHANNEL_CHAT = "yukihub_chat_msg_v1";
    public static final String CHANNEL_GROUP_REPLY = "yukihub_group_reply_v1";

    private static final String PREFS_NAME = "yukihub_prefs";
    /** 消息通知总开关（默认开） */
    public static final String KEY_CHAT_NOTIFY = "chat_msg_notify_enabled";

    private static final int NOTIFY_BASE_CHAT = 5200;
    private static final int NOTIFY_BASE_REPLY = 5600;

    /** 当前打开的会话标识（"f:friendId" / "g:groupId"），在该会话内不弹通知 */
    private static volatile String activeConversation = null;

    private final Context appContext;
    private final SocialApiClient apiClient;
    /** 私聊已推送过的最大消息 id（进程内去重，避免轮询重复弹） */
    private int lastNotifiedChatId = 0;
    /** 首轮只建基线不弹通知，避免冷启动刷屏 */
    private boolean primed = false;

    public ChatNotifier(Context context) {
        this.appContext = context.getApplicationContext();
        this.apiClient = new SocialApiClient(this.appContext);
        ensureChannels(appContext);
    }

    /** 标记/清除当前所在会话，避免在看着的会话里还弹通知 */
    public static void setActiveConversation(String key) {
        activeConversation = key;
    }

    public static void clearActiveConversation() {
        activeConversation = null;
    }

    public static String friendKey(String friendId) { return "f:" + friendId; }

    public static String groupKey(int groupId) { return "g:" + groupId; }

    public static boolean isChatNotifyEnabled(Context context) {
        if (context == null) return false;
        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean(KEY_CHAT_NOTIFY, true);
    }

    public static void ensureChannels(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        Uri soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        AudioAttributes audioAttrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();

        NotificationChannel chat = new NotificationChannel(
                CHANNEL_CHAT, "私聊消息", NotificationManager.IMPORTANCE_HIGH);
        chat.setDescription("好友发来私聊消息时弹出通知");
        chat.setShowBadge(true);
        chat.enableVibration(true);
        chat.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
        chat.setSound(soundUri, audioAttrs);
        nm.createNotificationChannel(chat);

        NotificationChannel reply = new NotificationChannel(
                CHANNEL_GROUP_REPLY, "群聊回复提醒", NotificationManager.IMPORTANCE_HIGH);
        reply.setDescription("群聊中有人回复你的消息时弹出通知");
        reply.setShowBadge(true);
        reply.enableVibration(true);
        reply.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
        reply.setSound(soundUri, audioAttrs);
        nm.createNotificationChannel(reply);
    }

    public void reset() {
        lastNotifiedChatId = 0;
        primed = false;
    }

    /**
     * 轮询一次：私聊新消息 + 群聊被回复提醒。
     * 需在 IO 线程调用。
     */
    public void pollOnce() {
        if (!isChatNotifyEnabled(appContext)) return;
        pollPrivateMessages();
        pollGroupReplies();
    }

    // ==================== 私聊 ====================

    private void pollPrivateMessages() {
        try {
            // afterId 传当前已推送的最大 id：服务端只返回「别人发给我的」比它新的消息
            List<ChatMessage> msgs = apiClient.pollNewMessages(lastNotifiedChatId, null, true);
            if (msgs == null || msgs.isEmpty()) return;

            int maxId = lastNotifiedChatId;
            // 同一好友多条只弹一条（取最新），避免连发刷屏
            Map<String, ChatMessage> latestBySender = new HashMap<>();
            for (ChatMessage m : msgs) {
                if (m == null || m.id <= 0) continue;
                if (m.id > maxId) maxId = m.id;
                if (m.isMine) continue;                       // 自己发的不弹
                if (m.senderId == null || m.senderId.isEmpty()) continue;
                latestBySender.put(m.senderId, m);
            }

            if (!primed) {
                // 首轮只对齐游标，不弹历史堆积的通知
                lastNotifiedChatId = maxId;
                primed = true;
                return;
            }
            lastNotifiedChatId = maxId;

            if (latestBySender.isEmpty()) return;

            // 昵称需要好友列表才能拿到，一次性取回做映射
            Map<String, String> nickById = new HashMap<>();
            try {
                List<FriendInfo> friends = apiClient.getFriendsList();
                if (friends != null) {
                    for (FriendInfo f : friends) {
                        if (f == null || f.id == null) continue;
                        String show = (f.note != null && !f.note.trim().isEmpty())
                                ? f.note.trim()
                                : (f.nickname == null ? "" : f.nickname);
                        nickById.put(f.id, show);
                    }
                }
            } catch (Throwable ignored) {}

            for (Map.Entry<String, ChatMessage> e : latestBySender.entrySet()) {
                String senderId = e.getKey();
                if (friendKey(senderId).equals(activeConversation)) continue;  // 正在看这个会话
                ChatMessage m = e.getValue();
                String nick = nickById.get(senderId);
                if (nick == null || nick.trim().isEmpty()) nick = "好友";
                notifyPrivateMessage(senderId, nick, previewOf(m));
            }
        } catch (Throwable t) {
            Log.w(TAG, "pollPrivateMessages failed: " + t.getMessage());
        }
    }

    private void notifyPrivateMessage(String senderId, String nickname, String preview) {
        try {
            Notification n = buildNotification(CHANNEL_CHAT, nickname, preview, "friends", senderId, 0);
            NotificationManager nm = (NotificationManager) appContext.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.notify(NOTIFY_BASE_CHAT + Math.abs(senderId.hashCode() % 100000), n);
            }
        } catch (Throwable t) {
            Log.w(TAG, "notifyPrivateMessage failed", t);
        }
    }

    // ==================== 群聊被回复 ====================

    private void pollGroupReplies() {
        try {
            List<SocialApiClient.ReplyAlert> alerts = apiClient.getGroupReplyAlerts();
            if (alerts == null || alerts.isEmpty()) return;

            List<Integer> handled = new ArrayList<>();
            for (SocialApiClient.ReplyAlert a : alerts) {
                if (a == null || a.id <= 0) continue;
                handled.add(a.id);
                // 正在看这个群 → 不弹，但仍标记已读（用户已经看到了）
                if (groupKey(a.groupId).equals(activeConversation)) continue;

                String who = (a.actorNickname == null || a.actorNickname.trim().isEmpty())
                        ? "有人" : a.actorNickname.trim();
                String group = (a.groupName == null || a.groupName.isEmpty()) ? "群聊" : a.groupName;
                String icon = (a.groupIcon == null || a.groupIcon.isEmpty()) ? "" : a.groupIcon + " ";
                boolean isMention = "mention".equals(a.kind);
                String title = who + (isMention ? " 在群里 @ 了你" : " 回复了你");
                String text = icon + group + "：" + (a.summary == null ? "" : a.summary);

                Notification n = buildNotification(CHANNEL_GROUP_REPLY, title, text, "friends", null, a.groupId);
                NotificationManager nm = (NotificationManager) appContext.getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm != null) {
                    nm.notify(NOTIFY_BASE_REPLY + (a.messageId % 100000), n);
                }
            }

            // 标记已读，避免下一轮重复弹
            if (!handled.isEmpty()) {
                try { apiClient.markGroupReplyAlertsRead(handled); } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            Log.w(TAG, "pollGroupReplies failed: " + t.getMessage());
        }
    }

    // ==================== 工具 ====================

    private String previewOf(ChatMessage m) {
        if (m == null) return "";
        String type = m.msgType == null ? "text" : m.msgType;
        if ("image".equals(type)) return "[图片]";
        if ("emoji".equals(type)) return "[表情]";
        String c = m.content == null ? "" : m.content.trim();
        // 引用回复的首行是 "> 昵称: 原文"，通知里只展示实际回复内容更清楚
        int nl = c.indexOf('\n');
        if (c.startsWith(">") && nl > 0) {
            String rest = c.substring(nl).trim();
            if (!rest.isEmpty()) c = "[回复] " + rest;
        }
        return c.length() > 80 ? c.substring(0, 80) + "…" : c;
    }

    /**
     * @param chatFriendId 点击通知要直达的私聊好友 id（null = 不直达）
     * @param chatGroupId  点击通知要直达的群 id（0 = 不直达）
     */
    private Notification buildNotification(String channelId, String title, String text,
                                           String homeTarget, String chatFriendId, int chatGroupId) {
        // 落点是游戏库主界面（MainActivity），不是首页 HomeActivity
        Intent intent = new Intent(appContext, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        intent.putExtra("home_target", homeTarget);
        if (chatFriendId != null && !chatFriendId.isEmpty()) {
            intent.putExtra("chat_friend_id", chatFriendId);
        }
        if (chatGroupId > 0) {
            intent.putExtra("chat_group_id", chatGroupId);
        }

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        // requestCode 必须按会话区分，否则 FLAG_UPDATE_CURRENT 会让多个通知共用同一个 Intent，
        // 点任何一条都跳到最后创建的那个会话
        int reqCode = Math.abs((channelId + "|" + (chatFriendId == null ? "" : chatFriendId)
                + "|" + chatGroupId).hashCode() % 1000000);
        PendingIntent pi = PendingIntent.getActivity(appContext, reqCode, intent, flags);

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(appContext, channelId);
        } else {
            builder = new Notification.Builder(appContext);
            builder.setPriority(Notification.PRIORITY_HIGH);
        }

        return builder
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setDefaults(Notification.DEFAULT_ALL)
                .build();
    }
}