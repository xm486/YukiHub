package com.yuki.yukihub.social;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 前台服务：保活 + 心跳 + 好友「开始玩」轮询通知。
 *
 * 启动条件：已登录 且 账号设置里「好友开始玩游戏时通知」开启（默认开）。
 * 即使只是为了展示自己的正在玩状态，也建议开着——否则后台容易被杀导致状态掉线。
 */
public class PresenceService extends Service {

    private static final String TAG = "PresenceService";
    public static final int FG_NOTIFY_ID = 10086;

    public static final String ACTION_REFRESH = "com.yuki.yukihub.presence.REFRESH";
    public static final String ACTION_STOP = "com.yuki.yukihub.presence.STOP";

    // 15 秒一轮：后台仍能较快发现好友开玩；比 60s 更接近 Steam 体感
    private static final long FRIEND_POLL_INTERVAL_MS = 15_000L;

    private PresenceManager presenceManager;
    private FriendNotifier friendNotifier;
    private SocialApiClient apiClient;
    private ScheduledFuture<?> friendPollFuture;
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean heartbeatHeld = new AtomicBoolean(false);

    /** 按当前登录态与开关启动/停止服务。 */
    public static void sync(Context context) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        SharedPreferences prefs = app.getSharedPreferences(PresenceManager.PREFS_NAME, Context.MODE_PRIVATE);
        String token = prefs.getString(PresenceManager.KEY_AUTH_ACCESS_TOKEN, "");
        boolean loggedIn = token != null && !token.trim().isEmpty();
        boolean notifyOn = prefs.getBoolean(PresenceManager.KEY_FRIEND_PLAY_NOTIFY, true);

        if (loggedIn && notifyOn) {
            start(app);
        } else {
            stop(app);
        }
    }

    public static void start(Context context) {
        if (context == null) return;
        try {
            Intent i = new Intent(context.getApplicationContext(), PresenceService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.getApplicationContext().startForegroundService(i);
            } else {
                context.getApplicationContext().startService(i);
            }
        } catch (Throwable t) {
            Log.w(TAG, "start failed", t);
        }
    }

    public static void stop(Context context) {
        if (context == null) return;
        try {
            Intent i = new Intent(context.getApplicationContext(), PresenceService.class);
            i.setAction(ACTION_STOP);
            context.getApplicationContext().startService(i);
            context.getApplicationContext().stopService(
                    new Intent(context.getApplicationContext(), PresenceService.class));
        } catch (Throwable t) {
            Log.w(TAG, "stop failed", t);
        }
    }

    /** 游戏状态变化后刷新前台通知文案，并触发一次即时心跳。 */
    public static void refresh(Context context) {
        if (context == null) return;
        try {
            Intent i = new Intent(context.getApplicationContext(), PresenceService.class);
            i.setAction(ACTION_REFRESH);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // refresh 时若服务未起，按 sync 逻辑决定
                SharedPreferences prefs = context.getApplicationContext()
                        .getSharedPreferences(PresenceManager.PREFS_NAME, Context.MODE_PRIVATE);
                String token = prefs.getString(PresenceManager.KEY_AUTH_ACCESS_TOKEN, "");
                boolean loggedIn = token != null && !token.trim().isEmpty();
                boolean notifyOn = prefs.getBoolean(PresenceManager.KEY_FRIEND_PLAY_NOTIFY, true);
                if (loggedIn && notifyOn) {
                    context.getApplicationContext().startForegroundService(i);
                }
            } else {
                context.getApplicationContext().startService(i);
            }
        } catch (Throwable t) {
            Log.w(TAG, "refresh failed", t);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        presenceManager = PresenceManager.get(this);
        friendNotifier = new FriendNotifier(this);
        apiClient = new SocialApiClient(this);
        FriendNotifier.ensureChannels(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            shutdownAndStop();
            return START_NOT_STICKY;
        }

        SharedPreferences prefs = getSharedPreferences(PresenceManager.PREFS_NAME, MODE_PRIVATE);
        String token = prefs.getString(PresenceManager.KEY_AUTH_ACCESS_TOKEN, "");
        boolean loggedIn = token != null && !token.trim().isEmpty();
        boolean notifyOn = prefs.getBoolean(PresenceManager.KEY_FRIEND_PLAY_NOTIFY, true);

        if (!loggedIn || !notifyOn) {
            shutdownAndStop();
            return START_NOT_STICKY;
        }

        // 必须尽快 startForeground
        String activity = presenceManager.getCurrentActivity();
        Notification fg = FriendNotifier.buildForegroundNotification(this, activity);
        try {
            startForeground(FG_NOTIFY_ID, fg);
        } catch (Throwable t) {
            Log.w(TAG, "startForeground failed", t);
            stopSelf();
            return START_NOT_STICKY;
        }

        // 心跳交由 PresenceManager 统一管理（引用计数，只持有一次）
        if (heartbeatHeld.compareAndSet(false, true)) {
            presenceManager.retainHeartbeat();
        }
        // 已持有时不重复 retain，避免引用计数膨胀
        updateForegroundNotification();

        // 无论是首次启动还是 REFRESH，都确保好友轮询在跑
        // （之前 REFRESH 直接 return，导致只靠 Activity 前台时才像“能收到”）
        if (started.compareAndSet(false, true)) {
            startFriendPolling();
        } else if (friendPollFuture == null
                || friendPollFuture.isCancelled()
                || friendPollFuture.isDone()) {
            startFriendPolling();
        }

        // REFRESH：额外立刻拉一次好友，缩短“开始玩→通知”延迟（放到 IO，别堵主线程）
        if (ACTION_REFRESH.equals(action)) {
            com.yuki.yukihub.util.AppExecutors.runOnIo(this::pollFriendsOnce);
        }

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopFriendPolling();
        if (heartbeatHeld.compareAndSet(true, false) && presenceManager != null) {
            presenceManager.releaseHeartbeat();
        }
        // 不在这里 markOffline：系统重建 Service 时会误杀状态。
        // 真正离线由 logout + 服务端超时处理。
        started.set(false);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void startFriendPolling() {
        stopFriendPolling();
        // 立即先拉一轮（IO），避免必须等 interval
        com.yuki.yukihub.util.AppExecutors.runOnIo(this::pollFriendsOnce);
        friendPollFuture = com.yuki.yukihub.util.AppExecutors.scheduled().scheduleAtFixedRate(
                this::pollFriendsOnce,
                FRIEND_POLL_INTERVAL_MS,
                FRIEND_POLL_INTERVAL_MS,
                TimeUnit.MILLISECONDS
        );
    }

    private void pollFriendsOnce() {
        try {
            if (!PresenceManager.isFriendPlayNotifyEnabled(this)) return;
            SharedPreferences prefs = getSharedPreferences(PresenceManager.PREFS_NAME, MODE_PRIVATE);
            String token = prefs.getString(PresenceManager.KEY_AUTH_ACCESS_TOKEN, "");
            if (token == null || token.trim().isEmpty()) {
                shutdownAndStop();
                return;
            }
            List<FriendInfo> friends = apiClient.getFriendsList();
            if (friendNotifier != null) {
                friendNotifier.processFriendsSnapshot(friends);
            }
            updateForegroundNotification();
        } catch (Throwable t) {
            Log.w(TAG, "friend poll failed: " + t.getMessage());
        }
    }

    private void stopFriendPolling() {
        if (friendPollFuture != null) {
            friendPollFuture.cancel(false);
            friendPollFuture = null;
        }
    }

    private void updateForegroundNotification() {
        try {
            String activity = presenceManager == null ? null : presenceManager.getCurrentActivity();
            // 隐私关闭时前台通知也不暴露游戏名
            if (presenceManager != null && !presenceManager.isSharePlayingEnabled()) {
                activity = null;
            }
            Notification fg = FriendNotifier.buildForegroundNotification(this, activity);
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(FG_NOTIFY_ID, fg);
        } catch (Throwable t) {
            Log.w(TAG, "updateForegroundNotification failed", t);
        }
    }

    private void shutdownAndStop() {
        stopFriendPolling();
        if (friendNotifier != null) friendNotifier.reset();
        if (heartbeatHeld.compareAndSet(true, false) && presenceManager != null) {
            presenceManager.releaseHeartbeat();
        }
        started.set(false);
        try {
            stopForeground(true);
        } catch (Throwable ignored) {}
        stopSelf();
    }
}
