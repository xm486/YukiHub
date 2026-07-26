package com.yuki.yukihub.social;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import com.yuki.yukihub.HomeActivity;
import com.yuki.yukihub.R;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 好友「开始玩游戏」通知。
 * Steam 风格：标题=昵称，正文=开始玩 《游戏名》。
 */
public class FriendNotifier {

    private static final String TAG = "FriendNotifier";

    public static final String CHANNEL_FOREGROUND = "yukihub_presence";
    // 新 channel id：Android 不允许就地提升已创建 channel 的 importance
    // 旧的 yukihub_friend_play 是 DEFAULT，无法变成横幅；换 id 才能 HIGH
    public static final String CHANNEL_FRIEND_PLAY = "yukihub_friend_play_v2";

    private static final int NOTIFY_BASE = 4200;

    private final Context appContext;
    private final Map<String, String> lastActivityByFriend = new HashMap<>();
    private boolean primed = false;

    public FriendNotifier(Context context) {
        this.appContext = context.getApplicationContext();
        ensureChannels(appContext);
    }

    public static void ensureChannels(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        NotificationChannel fg = new NotificationChannel(
                CHANNEL_FOREGROUND,
                "在线状态",
                NotificationManager.IMPORTANCE_LOW
        );
        fg.setDescription("YukiHub 保持在线与好友动态监听");
        fg.setShowBadge(false);
        fg.enableVibration(false);
        fg.setSound(null, null);
        nm.createNotificationChannel(fg);

        // HIGH：允许弹出横幅（heads-up），更接近 Steam 提醒
        NotificationChannel play = new NotificationChannel(
                CHANNEL_FRIEND_PLAY,
                "好友开始玩游戏",
                NotificationManager.IMPORTANCE_HIGH
        );
        play.setDescription("好友开始玩游戏时弹出横幅通知");
        play.setShowBadge(true);
        play.enableVibration(true);
        play.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        // 显式设置系统默认铃声，部分 ROM 不会自动给 HIGH channel 配声
        Uri soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        AudioAttributes audioAttrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        play.setSound(soundUri, audioAttrs);
        nm.createNotificationChannel(play);
    }

    /**
     * 对比好友列表变化，检测「开始玩游戏」事件。
     * 首次调用只建立基线，不弹通知（避免冷启动刷屏）。
     */
    public void processFriendsSnapshot(List<FriendInfo> friends) {
        if (friends == null) return;
        Map<String, String> next = new HashMap<>();
        for (FriendInfo f : friends) {
            if (f == null || f.id == null || f.id.isEmpty()) continue;
            String act = f.activity == null ? "" : f.activity.trim();
            // 只在 online 状态下认 activity
            if (!f.isOnline()) act = "";
            next.put(f.id, act);

            if (primed && PresenceManager.isFriendPlayNotifyEnabled(appContext)) {
                String prev = lastActivityByFriend.get(f.id);
                if (prev == null) prev = "";
                // 从「没在玩」变成「正在玩 xxx」
                if (prev.isEmpty() && !act.isEmpty()) {
                    notifyFriendStartedPlaying(f, act);
                } else if (!prev.isEmpty() && !act.isEmpty() && !prev.equals(act)) {
                    // 换游戏也通知
                    notifyFriendStartedPlaying(f, act);
                }
            }
        }
        lastActivityByFriend.clear();
        lastActivityByFriend.putAll(next);
        primed = true;
    }

    public void reset() {
        lastActivityByFriend.clear();
        primed = false;
    }

    private void notifyFriendStartedPlaying(FriendInfo friend, String activity) {
        try {
            String nickname = friend.nickname == null || friend.nickname.trim().isEmpty()
                    ? "好友" : friend.nickname.trim();
            String game = PresenceManager.extractGameTitle(activity);
            if (game.isEmpty()) game = activity;

            Intent intent = new Intent(appContext, HomeActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            intent.putExtra("home_target", "friends");

            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }
            PendingIntent pi = PendingIntent.getActivity(appContext, friend.uid, intent, flags);

            Notification.Builder builder;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                builder = new Notification.Builder(appContext, CHANNEL_FRIEND_PLAY);
            } else {
                builder = new Notification.Builder(appContext);
                // 旧系统用 HIGH 才能 heads-up
                builder.setPriority(Notification.PRIORITY_HIGH);
            }

            Notification notification = builder
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle(nickname)
                    .setContentText("开始玩 《" + game + "》")
                    .setStyle(new Notification.BigTextStyle()
                            .bigText(nickname + " 开始玩 《" + game + "》"))
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .setOnlyAlertOnce(true)
                    .setCategory(Notification.CATEGORY_SOCIAL)
                    .setVisibility(Notification.VISIBILITY_PUBLIC)
                    .setDefaults(Notification.DEFAULT_ALL)
                    .build();

            NotificationManager nm = (NotificationManager) appContext.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                int id = NOTIFY_BASE + Math.abs(friend.id.hashCode() % 100000);
                nm.notify(id, notification);
            }
        } catch (Throwable t) {
            Log.w(TAG, "notifyFriendStartedPlaying failed", t);
        }
    }

    /** 构建前台保活通知。 */
    public static Notification buildForegroundNotification(Context context, String playingActivity) {
        ensureChannels(context);
        Intent intent = new Intent(context, HomeActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pi = PendingIntent.getActivity(context, 0, intent, flags);

        String title = "YukiHub";
        String text;
        if (playingActivity != null && !playingActivity.trim().isEmpty()) {
            text = playingActivity.trim() + " · 好友动态监听中";
        } else {
            text = "好友动态监听中";
        }

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(context, CHANNEL_FOREGROUND);
        } else {
            builder = new Notification.Builder(context);
            builder.setPriority(Notification.PRIORITY_MIN);
        }

        return builder
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .build();
    }
}
