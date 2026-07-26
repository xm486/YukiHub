package com.yuki.yukihub.social;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 用户在线状态管理器（单例）。
 *
 * 职责：
 * - 维护当前 activity（正在玩的游戏），供心跳上报
 * - 用 retain/release 引用计数协调 Activity 与 PresenceService
 * - 每 45 秒向服务端发送心跳
 *
 * 服务端判定逻辑：
 * - 90s 内有心跳 → 保持用户设置的 status
 * - 90s~300s → 自动降为 away
 * - >300s → 判定离线
 */
public class PresenceManager {

    private static final String TAG = "PresenceManager";
    public static final String PREFS_NAME = "yukihub_prefs";
    public static final String KEY_AUTH_ACCESS_TOKEN = "auth_access_token";
    public static final String KEY_SHARE_PLAYING = "share_playing_status";
    public static final String KEY_FRIEND_PLAY_NOTIFY = "friend_play_notify";
    public static final String KEY_CURRENT_PLAYING = "current_playing_activity";

    private static final String AUTH_BASE_URL = "https://yukihub.zh.kg/api";
    private static final long HEARTBEAT_INTERVAL_MS = 45_000L;
    private static final int CONNECT_TIMEOUT = 8_000;
    private static final int READ_TIMEOUT = 8_000;

    private static volatile PresenceManager sInstance;

    private final Context appContext;
    private ScheduledFuture<?> heartbeatFuture;
    private final AtomicInteger retainCount = new AtomicInteger(0);
    private volatile String currentActivity = null;

    public static PresenceManager get(Context context) {
        if (sInstance == null) {
            synchronized (PresenceManager.class) {
                if (sInstance == null) {
                    sInstance = new PresenceManager(context.getApplicationContext());
                }
            }
        }
        return sInstance;
    }

    public PresenceManager(Context context) {
        this.appContext = context.getApplicationContext();
        SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String cached = prefs.getString(KEY_CURRENT_PLAYING, "");
        if (cached != null && !cached.trim().isEmpty()) {
            currentActivity = cached.trim();
        }
    }

    /**
     * 兼容旧调用：等同 retainHeartbeat()。
     * Activity onResume / Service onStart 时调用。
     */
    public void startHeartbeat() {
        retainHeartbeat();
    }

    /** 兼容旧调用：等同 releaseHeartbeat()。 */
    public void stopHeartbeat() {
        releaseHeartbeat();
    }

    /**
     * 获取心跳所有权（引用计数 +1）。
     * 首次 acquire 时启动定时器并立即发一次 online 心跳。
     */
    public void retainHeartbeat() {
        if (!isLoggedIn()) return;
        int count = retainCount.incrementAndGet();
        if (count == 1) {
            ensureTimerRunning();
            sendHeartbeat("online", effectiveActivity());
        } else {
            // 已有持有者：只刷新一次状态
            sendHeartbeat("online", effectiveActivity());
        }
    }

    /**
     * 释放心跳所有权（引用计数 -1）。
     * 归零时停止定时器，但不主动发 away（让服务端自然超时）。
     */
    public void releaseHeartbeat() {
        int count = retainCount.decrementAndGet();
        if (count <= 0) {
            retainCount.set(0);
            cancelTimer();
        }
    }

    /** 停止心跳并上报最终状态。 */
    public void stopHeartbeat(String finalStatus) {
        retainCount.set(0);
        cancelTimer();
        if (isLoggedIn()) {
            sendHeartbeat(finalStatus, null);
        }
    }

    /**
     * 标记离线（退出登录时调用）。
     * 会清空 activity 并尽力发 offline。
     */
    public void markOffline() {
        retainCount.set(0);
        cancelTimer();
        clearCurrentActivity();
        if (!isLoggedIn()) return;
        new Thread(() -> {
            try {
                sendPresenceRequest("offline", null);
            } catch (Throwable t) {
                Log.w(TAG, "markOffline failed", t);
            }
        }, "YukiHub-Presence-Offline").start();
    }

    /**
     * 更新当前活动（开始/停止玩游戏时调用）。
     * 会立即发一次心跳，后续周期也使用新值。
     */
    public void updateActivity(String activity) {
        setCurrentActivityInternal(activity, true);
        if (!isLoggedIn()) return;
        sendHeartbeat("online", effectiveActivity());
    }

    /** 设置正在玩的游戏（含隐私开关判断）。 */
    public void setPlayingGame(String gameTitle) {
        if (gameTitle == null || gameTitle.trim().isEmpty()) {
            clearPlayingGame();
            return;
        }
        String text = buildPlayingText(gameTitle);
        if (!isSharePlayingEnabled()) {
            // 隐私关闭：本地记下「在玩」，但上报 activity 为空
            // 前台通知仍可用本地缓存，但好友看不到
            SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit().putString(KEY_CURRENT_PLAYING, text).apply();
            currentActivity = null; // 上报空
            if (isLoggedIn()) sendHeartbeat("online", null);
            return;
        }
        updateActivity(text);
    }

    /** 清除正在玩的游戏。 */
    public void clearPlayingGame() {
        clearCurrentActivity();
        if (isLoggedIn()) {
            sendHeartbeat("online", null);
        }
    }

    public String getCurrentActivity() {
        // 前台通知想显示游戏名时：即使隐私关闭也读本地缓存
        if (currentActivity != null && !currentActivity.isEmpty()) return currentActivity;
        SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String cached = prefs.getString(KEY_CURRENT_PLAYING, "");
        return (cached == null || cached.trim().isEmpty()) ? null : cached.trim();
    }

    public boolean isRunning() {
        return retainCount.get() > 0 && heartbeatFuture != null
                && !heartbeatFuture.isCancelled() && !heartbeatFuture.isDone();
    }

    public boolean isSharePlayingEnabled() {
        SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean(KEY_SHARE_PLAYING, true);
    }

    public static boolean isFriendPlayNotifyEnabled(Context context) {
        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean(KEY_FRIEND_PLAY_NOTIFY, true);
    }

    public static String buildPlayingText(String gameTitle) {
        String title = gameTitle == null ? "" : gameTitle.trim();
        if (title.isEmpty()) return "";
        if (title.length() > 80) title = title.substring(0, 80) + "…";
        return "正在玩：" + title;
    }

    /** 从 "正在玩：xxx" 里提取游戏名。 */
    public static String extractGameTitle(String activity) {
        if (activity == null) return "";
        String s = activity.trim();
        if (s.startsWith("正在玩：")) return s.substring("正在玩：".length()).trim();
        if (s.startsWith("正在玩:")) return s.substring("正在玩:".length()).trim();
        return s;
    }

    // ==================== 内部方法 ====================

    private void ensureTimerRunning() {
        if (heartbeatFuture != null && !heartbeatFuture.isCancelled() && !heartbeatFuture.isDone()) {
            return;
        }
        heartbeatFuture = AppExecutorsProxy.scheduleAtFixedRate(() -> {
            if (!isLoggedIn()) {
                retainCount.set(0);
                cancelTimer();
                return;
            }
            if (retainCount.get() <= 0) {
                cancelTimer();
                return;
            }
            sendHeartbeat("online", effectiveActivity());
        }, HEARTBEAT_INTERVAL_MS, HEARTBEAT_INTERVAL_MS);
    }

    private void cancelTimer() {
        if (heartbeatFuture != null) {
            heartbeatFuture.cancel(false);
            heartbeatFuture = null;
        }
    }

    private void setCurrentActivityInternal(String activity, boolean persist) {
        String normalized = (activity == null || activity.trim().isEmpty()) ? null : activity.trim();
        currentActivity = normalized;
        if (persist) {
            SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            if (normalized == null) {
                prefs.edit().remove(KEY_CURRENT_PLAYING).apply();
            } else {
                prefs.edit().putString(KEY_CURRENT_PLAYING, normalized).apply();
            }
        }
    }

    private void clearCurrentActivity() {
        currentActivity = null;
        SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().remove(KEY_CURRENT_PLAYING).apply();
    }

    /** 上报用的 activity：受隐私开关控制。 */
    private String effectiveActivity() {
        if (!isSharePlayingEnabled()) return null;
        // currentActivity 可能因隐私关闭被置 null，但 KEY_CURRENT_PLAYING 还在
        if (currentActivity != null && !currentActivity.isEmpty()) return currentActivity;
        return null;
    }

    private boolean isLoggedIn() {
        SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String token = prefs.getString(KEY_AUTH_ACCESS_TOKEN, "");
        return token != null && !token.trim().isEmpty();
    }

    private void sendHeartbeat(String status, String activity) {
        new Thread(() -> {
            try {
                sendPresenceRequest(status, activity);
            } catch (Throwable t) {
                Log.w(TAG, "heartbeat failed: " + t.getMessage());
            }
        }, "YukiHub-Presence-Heartbeat").start();
    }

    private void sendPresenceRequest(String status, String activity) throws Exception {
        SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String token = prefs.getString(KEY_AUTH_ACCESS_TOKEN, "");
        if (token == null || token.trim().isEmpty()) return;

        String url = AUTH_BASE_URL + "/presence/heartbeat";
        if ("offline".equals(status)) {
            url = AUTH_BASE_URL + "/presence/offline";
        }

        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Authorization", "Bearer " + token);

            JSONObject body = new JSONObject();
            body.put("status", status);
            // 始终带上 activity 字段：空字符串表示清除
            body.put("activity", activity == null ? "" : activity);

            byte[] bodyBytes = body.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(bodyBytes);
            }

            int code = conn.getResponseCode();
            if (code != 200) {
                Log.w(TAG, "presence response code: " + code);
            }
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static class AppExecutorsProxy {
        static ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelayMs, long periodMs) {
            return com.yuki.yukihub.util.AppExecutors.scheduled()
                    .scheduleAtFixedRate(command, initialDelayMs, periodMs, TimeUnit.MILLISECONDS);
        }
    }
}