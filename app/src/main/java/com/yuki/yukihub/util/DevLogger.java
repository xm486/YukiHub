package com.yuki.yukihub.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStreamReader;

/**
 * 开发者日志收集 - 直接捕获 logcat 系统日志
 * 日志路径：Android/data/com.yuki.yukihub/files/logs/logcat.txt
 */
public final class DevLogger {
    private static final String TAG = "DevLogger";
    private static final String PREFS = "yukihub_prefs";
    private static final String KEY = "dev_log_enabled";
    private static volatile boolean enabled = false;
    private static File logcatFile;
    private static Process process;

    public static void init(Context ctx) {
        if (ctx == null) return;
        enabled = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false);
        File dir = new File(ctx.getExternalFilesDir(null), "logs");
        if (!dir.exists()) dir.mkdirs();
        logcatFile = new File(dir, "logcat.txt");
        if (enabled) startCapture();
    }

    public static boolean isEnabled() { return enabled; }

    public static void setEnabled(Context ctx, boolean on) {
        enabled = on;
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply();
        if (on) startCapture(); else stopCapture();
    }

    private static void startCapture() {
        stopCapture();
        new Thread(() -> {
            try {
                // 清除旧日志
                Runtime.getRuntime().exec("logcat -c").waitFor();
                // 启动logcat捕获
                process = Runtime.getRuntime().exec("logcat -v threadtime");
                BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                FileWriter writer = new FileWriter(logcatFile, true);
                writer.write("\n=== Logcat capture started ===\n");
                writer.flush();
                String line;
                while (enabled && (line = reader.readLine()) != null) {
                    writer.write(line);
                    writer.write("\n");
                    writer.flush();
                    // 超过10MB轮转
                    if (logcatFile.length() > 10 * 1024 * 1024) {
                        writer.close();
                        File bak = new File(logcatFile.getParent(), "logcat_old.txt");
                        if (bak.exists()) bak.delete();
                        logcatFile.renameTo(bak);
                        writer = new FileWriter(logcatFile);
                    }
                }
                reader.close();
                writer.close();
            } catch (Exception e) { Log.e(TAG, "Capture failed", e); }
        }, "LogcatCapture").start();
        Log.i(TAG, "Logcat capture started");
    }

    private static void stopCapture() {
        if (process != null) {
            process.destroy();
            process = null;
        }
    }

    public static File getLogFile() { return logcatFile; }
    public static String getLogPath() { return logcatFile != null ? logcatFile.getAbsolutePath() : null; }
    public static long getLogSize() { return logcatFile != null && logcatFile.exists() ? logcatFile.length() : 0; }

    public static boolean clearLog() {
        try {
            stopCapture();
            new FileWriter(logcatFile, false).close();
            if (enabled) startCapture();
            return true;
        } catch (Exception e) { return false; }
    }

    public static String formatSize(long b) {
        if (b < 1024) return b + " B";
        if (b < 1048576) return String.format("%.1f KB", b / 1024.0);
        return String.format("%.2f MB", b / 1048576.0);
    }
}