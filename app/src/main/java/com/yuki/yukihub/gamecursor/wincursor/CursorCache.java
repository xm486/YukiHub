package com.yuki.yukihub.gamecursor.wincursor;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 光标包的本地缓存。
 *
 * 解码 .ani 要遍历 RIFF、逐帧解 DIB，几十帧下来不算便宜，
 * 不能每次进游戏都重来一遍。这里把解码结果落盘：
 *
 *   getFilesDir()/gamecursor/<id>/
 *     meta.txt        第一行：帧数 热点X 热点Y
 *                     之后每行：帧文件名 时长ms
 *     f0.png f1.png ...
 *
 * 用纯文本而不是 JSON：没有依赖，格式一眼能看懂，出问题好排查。
 */
public final class CursorCache {
    private static final String DIR = "gamecursor";
    private static final String META = "meta.txt";

    private CursorCache() { }

    /** 缓存目录。id 用固定名即可，当前只支持一个常态光标。 */
    public static File dirFor(Context ctx, String id) {
        return new File(new File(ctx.getFilesDir(), DIR), id);
    }

    /** 把解码结果写入缓存。成功返回缓存目录，失败 null。 */
    public static File store(Context ctx, String id, CursorPack pack) {
        if (pack == null || pack.frames == null || pack.frames.length == 0) return null;
        File dir = dirFor(ctx, id);
        try {
            deleteRecursive(dir);
            if (!dir.mkdirs() && !dir.isDirectory()) return null;

            CursorFrame first = pack.frames[0];
            StringBuilder meta = new StringBuilder();
            meta.append(pack.sequence.length).append(' ')
                    .append(first.hotspotX).append(' ')
                    .append(first.hotspotY).append('\n');

            // 按播放序列展开：把帧复用摊平成线性序列，
            // 读的时候不用再处理 seq，逻辑更简单
            for (int step = 0; step < pack.sequence.length; step++) {
                int frameIdx = pack.sequence[step];
                if (frameIdx < 0 || frameIdx >= pack.frames.length) continue;
                Bitmap bmp = pack.frames[frameIdx].bitmap;
                if (bmp == null || bmp.isRecycled()) continue;
                String fn = "f" + step + ".png";
                FileOutputStream fos = null;
                try {
                    fos = new FileOutputStream(new File(dir, fn));
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
                } finally {
                    closeQuietly(fos);
                }
                int dur = step < pack.stepDurations.length ? pack.stepDurations[step] : 67;
                meta.append(fn).append(' ').append(dur).append('\n');
            }

            FileOutputStream mos = null;
            try {
                mos = new FileOutputStream(new File(dir, META));
                mos.write(meta.toString().getBytes("UTF-8"));
            } finally {
                closeQuietly(mos);
            }
            return dir;
        } catch (Throwable t) {
            android.util.Log.w("YukiGameCursor", "cursor cache store failed", t);
            deleteRecursive(dir);
            return null;
        }
    }

    /** 从缓存加载。不存在或损坏返回 null。 */
    public static CursorPack load(Context ctx, String id) {
        File dir = dirFor(ctx, id);
        File metaFile = new File(dir, META);
        if (!metaFile.isFile()) return null;
        try {
            String text = readText(metaFile);
            if (text == null) return null;
            String[] lines = text.split("\n");
            if (lines.length < 2) return null;

            String[] head = lines[0].trim().split("\\s+");
            if (head.length < 3) return null;
            float hx = Float.parseFloat(head[1]);
            float hy = Float.parseFloat(head[2]);

            List<CursorFrame> frames = new ArrayList<>();
            List<Integer> durations = new ArrayList<>();
            for (int i = 1; i < lines.length; i++) {
                String t = lines[i].trim();
                if (t.isEmpty()) continue;
                String[] parts = t.split("\\s+");
                if (parts.length < 2) continue;
                Bitmap bmp = BitmapFactory.decodeFile(new File(dir, parts[0]).getAbsolutePath());
                if (bmp == null) continue;
                int dur = Integer.parseInt(parts[1]);
                frames.add(new CursorFrame(bmp, hx, hy, dur));
                durations.add(dur);
            }
            if (frames.isEmpty()) return null;

            CursorFrame[] arr = frames.toArray(new CursorFrame[0]);
            int[] seq = new int[arr.length];
            int[] durs = new int[arr.length];
            for (int i = 0; i < arr.length; i++) {
                seq[i] = i;                 // 已在 store 时摊平，这里就是线性
                durs[i] = durations.get(i);
            }
            return new CursorPack(arr, seq, durs);
        } catch (Throwable t) {
            return null;
        }
    }

    public static void clear(Context ctx, String id) {
        deleteRecursive(dirFor(ctx, id));
    }

    public static boolean exists(Context ctx, String id) {
        return new File(dirFor(ctx, id), META).isFile();
    }

    private static String readText(File f) {
        java.io.FileInputStream fis = null;
        try {
            fis = new java.io.FileInputStream(f);
            // 必须循环读满：单次 read() 不保证读完，
            // meta 被截断会导致后面几帧静默丢失。
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            int total = 0;
            while ((n = fis.read(buf)) > 0) {
                bos.write(buf, 0, n);
                total += n;
                if (total > (1 << 20)) break;   // meta 不可能这么大，防异常文件
            }
            return total > 0 ? new String(bos.toByteArray(), "UTF-8") : null;
        } catch (Throwable t) {
            return null;
        } finally {
            closeQuietly(fis);
        }
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) {
                for (File k : kids) deleteRecursive(k);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    private static void closeQuietly(java.io.Closeable c) {
        try {
            if (c != null) c.close();
        } catch (Throwable ignored) { }
    }
}