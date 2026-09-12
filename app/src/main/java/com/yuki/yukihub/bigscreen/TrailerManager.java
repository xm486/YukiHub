package com.yuki.yukihub.bigscreen;

import android.content.Context;
import android.media.MediaMetadataRetriever;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * 本地预告视频的存储与校验（对应 bigscreen_spec.md §S10.2 / §S10.3）。
 *
 * <p>设计要点：
 * <ul>
 *   <li>视频**复制进内部存储** {@code getFilesDir()/trailers/}，不走 content URI —— 规避 SAF 授权失效</li>
 *   <li>校验规则：&gt; 500MB 拒绝；&gt; 100MB 二次确认；&gt; 120 秒提示建议裁剪（不阻止）</li>
 *   <li>格式不做白名单，交给 MediaPlayer 自行判断（失败会在播放阶段自愈）</li>
 *   <li>提供占用统计与一键清理（M4 设置页会用到）</li>
 * </ul>
 */
public class TrailerManager {

    /** 超过这个大小直接拒绝 */
    public static final long REJECT_BYTES = 500L * 1024 * 1024;
    /** 超过这个大小需要二次确认 */
    public static final long WARN_BYTES = 100L * 1024 * 1024;
    /** 超过这个时长提示建议裁剪（不阻止） */
    public static final long WARN_DURATION_MS = 120_000L;

    /** 文件探测结果 */
    public static class Probe {
        public long sizeBytes = 0;
        public long durationMs = 0;
        public boolean readable = false;
    }

    private final Context context;

    public TrailerManager(Context context) {
        this.context = context.getApplicationContext();
    }

    public File dir() {
        File d = new File(context.getFilesDir(), "trailers");
        if (!d.exists()) { d.mkdirs(); }
        return d;
    }

    /** 读取时长与大小（失败不抛异常，交给调用方按默认值处理） */
    public Probe probe(Uri uri) {
        Probe p = new Probe();
        try {
            p.sizeBytes = sizeOf(uri);
            p.readable = true;
        } catch (Throwable ignored) { }

        MediaMetadataRetriever retriever = null;
        try {
            retriever = new MediaMetadataRetriever();
            retriever.setDataSource(context, uri);
            String duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (duration != null) { p.durationMs = Long.parseLong(duration); }
            p.readable = true;
        } catch (Throwable ignored) {
        } finally {
            if (retriever != null) {
                try { retriever.release(); } catch (Throwable ignored) { }
            }
        }
        return p;
    }

    /**
     * 取文件大小：优先用文件描述符的长度，**不流式读取内容**
     * （500MB 的视频流一遍要好几十秒，代价太大）
     *
     * @return 字节数；拿不到返回 0（显示为"大小未知"）
     */
    private long sizeOf(Uri uri) {
        try (android.content.res.AssetFileDescriptor afd =
                     context.getContentResolver().openAssetFileDescriptor(uri, "r")) {
            if (afd != null && afd.getLength() > 0) { return afd.getLength(); }
            if (afd != null && afd.getFileDescriptor() != null) {
                try (java.io.FileInputStream fis = new java.io.FileInputStream(afd.getFileDescriptor())) {
                    long size = fis.getChannel().size();
                    if (size > 0) { return size; }
                } catch (Throwable ignored) { }
            }
        } catch (Throwable ignored) { }

        try (android.os.ParcelFileDescriptor pfd =
                     context.getContentResolver().openFileDescriptor(uri, "r")) {
            if (pfd != null) {
                long size = pfd.getStatSize();
                if (size > 0) { return size; }
            }
        } catch (Throwable ignored) { }

        return 0;
    }

    /**
     * 复制进内部存储。命名 {@code trailer_<gameId>_<时间戳>.mp4}，便于排查与清理。
     *
     * @return 目标文件；失败返回 null
     */
    public File copyToInternal(Uri uri, long gameId) {
        File target = new File(dir(), "trailer_" + gameId + "_" + System.currentTimeMillis() + ".mp4");
        try (InputStream in = context.getContentResolver().openInputStream(uri);
             FileOutputStream out = new FileOutputStream(target)) {
            if (in == null) { return null; }
            byte[] buf = new byte[64 * 1024];
            int len;
            while ((len = in.read(buf)) != -1) { out.write(buf, 0, len); }
            out.flush();
            return target;
        } catch (Throwable t) {
            try { target.delete(); } catch (Throwable ignored) { }
            return null;
        }
    }

    /** 删除某个预告文件（路径不在 trailers 目录内的一律不动，防误删） */
    public void delete(String path) {
        if (path == null || path.trim().isEmpty()) { return; }
        try {
            File f = new File(path);
            String dirPath = dir().getAbsolutePath();
            if (f.getAbsolutePath().startsWith(dirPath) && f.exists()) { f.delete(); }
        } catch (Throwable ignored) { }
    }

    public boolean exists(String path) {
        if (path == null || path.trim().isEmpty()) { return false; }
        // 网络直链（M11）：交给播放器去取，这里不做文件检查
        if (TrailerPlayer.isRemote(path)) { return true; }
        try {
            File f = new File(path);
            return f.exists() && f.isFile();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 预告目录总占用（字节） */
    public long totalSize() {
        long total = 0;
        File[] files = dir().listFiles();
        if (files == null) { return 0; }
        for (File f : files) {
            if (f != null && f.isFile()) { total += f.length(); }
        }
        return total;
    }

    public int fileCount() {
        File[] files = dir().listFiles();
        if (files == null) { return 0; }
        int count = 0;
        for (File f : files) {
            if (f != null && f.isFile()) { count++; }
        }
        return count;
    }

    /** 清空全部预告文件（DB 里的 trailer_path 由调用方按需清除） */
    public int deleteAll() {
        int removed = 0;
        File[] files = dir().listFiles();
        if (files == null) { return 0; }
        for (File f : files) {
            if (f != null && f.isFile() && f.delete()) { removed++; }
        }
        return removed;
    }

    /** 人类可读的大小文案 */
    public static String formatSize(long bytes) {
        if (bytes < 1024) { return bytes + " B"; }
        if (bytes < 1024 * 1024) { return String.format(java.util.Locale.getDefault(), "%.0f KB", bytes / 1024.0); }
        if (bytes < 1024L * 1024 * 1024) { return String.format(java.util.Locale.getDefault(), "%.1f MB", bytes / 1024.0 / 1024.0); }
        return String.format(java.util.Locale.getDefault(), "%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0);
    }
}