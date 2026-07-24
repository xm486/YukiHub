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

/**
 * 用户在线状态管理器。
 * 
 * 职责：
 * - App 在前台时，每 45 秒向服务端发送心跳
 * - App 退到后台（onPause）时，发一次 away 心跳，停止定时
 * - App 被销毁（onDestroy）时，尽力发一次 offline 标记
 * 
 * 服务端判定逻辑：
 * - 90s 内有心跳 → 保持用户设置的 status
 * - 90s~300s → 自动降为 away
 * - >300s → 判定离线
 */
public class PresenceManager {

    private static final String TAG = "PresenceManager";
    private static final String PREFS_NAME = "yukihub_prefs";
    private static final String KEY_AUTH_ACCESS_TOKEN = "auth_access_token";
    private static final String AUTH_BASE_URL = "https://yukihub.zh.kg/api";

    private static final long HEARTBEAT_INTERVAL_MS = 45_000L; // 45 秒
    private static final int CONNECT_TIMEOUT = 8_000;
    private static final int READ_TIMEOUT = 8_000;

    private final Context appContext;
    private ScheduledFuture<?> heartbeatFuture;
    private volatile boolean running = false;

    public PresenceManager(Context context) {
        this.appContext = context.getApplicationContext();
    }

    /**
     * 启动心跳定时器（onResume 调用）。
     * 如果已登录则立即发一次 online 心跳，并周期性发送。
     */
    public void startHeartbeat() {
        startHeartbeat(null);
    }

    /**
     * 启动心跳定时器，携带当前活动信息。
     * @param activity 当前活动描述，如 "正在玩：Clannad"，可为 null
     */
    public void startHeartbeat(String activity) {
        if (!isLoggedIn()) return;
        running = true; // 立即先标记为运行中，防止重复启动

        // 立即发一次
        sendHeartbeat("online", activity);

        // 如果已有定时器则不重复创建
        if (heartbeatFuture != null && !heartbeatFuture.isCancelled() && !heartbeatFuture.isDone()) {
            return;
        }

        // 启动定时
        heartbeatFuture = AppExecutorsProxy.scheduleAtFixedRate(() -> {
            if (!isLoggedIn()) {
                stopHeartbeat();
                return;
            }
            sendHeartbeat("online", activity);
        }, HEARTBEAT_INTERVAL_MS, HEARTBEAT_INTERVAL_MS);
    }

    /**
     * 停止心跳定时器（onPause 调用）。
     * 不发送 away 状态，让服务端自然判定（避免 Activity 切换时的状态闪烁）。
     */
    public void stopHeartbeat() {
        running = false;
        if (heartbeatFuture != null) {
            heartbeatFuture.cancel(false);
            heartbeatFuture = null;
        }
    }

    /**
     * 停止心跳，携带最终状态。
     */
    public void stopHeartbeat(String finalStatus) {
        running = false;
        if (heartbeatFuture != null) {
            heartbeatFuture.cancel(false);
            heartbeatFuture = null;
        }
        // 尽力发一次最终状态
        if (isLoggedIn()) {
            sendHeartbeat(finalStatus, null);
        }
    }

    /**
     * 标记离线（onDestroy 调用）。
     * 使用独立线程确保即使 Activity 被回收也能发出去。
     */
    public void markOffline() {
        running = false;
        if (heartbeatFuture != null) {
            heartbeatFuture.cancel(false);
            heartbeatFuture = null;
        }
        if (!isLoggedIn()) return;
        // 在单独线程上发，不阻塞 onDestroy
        new Thread(() -> {
            try {
                sendPresenceRequest("offline", null);
            } catch (Throwable t) {
                Log.w(TAG, "markOffline failed", t);
            }
        }, "YukiHub-Presence-Offline").start();
    }

    /**
     * 手动刷新活动状态（比如开始/停止玩游戏时调用）。
     */
    public void updateActivity(String activity) {
        if (!isLoggedIn() || !running) return;
        sendHeartbeat("online", activity);
    }

    // ==================== 内部方法 ====================

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
        // 对于 offline 状态，用 offline 端点
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
            if (activity != null && !activity.isEmpty()) {
                body.put("activity", activity);
            }

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

    // ==================== 代理类 ====================

    /**
     * 为了避免直接耦合 AppExecutors 的 ScheduledExecutorService，
     * 这里用代理统一管理。
     */
    private static class AppExecutorsProxy {
        static ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelayMs, long periodMs) {
            return com.yuki.yukihub.util.AppExecutors.scheduled()
                    .scheduleAtFixedRate(command, initialDelayMs, periodMs, TimeUnit.MILLISECONDS);
        }
    }
}