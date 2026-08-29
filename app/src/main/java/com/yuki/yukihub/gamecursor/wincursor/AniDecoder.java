package com.yuki.yukihub.gamecursor.wincursor;

import java.util.ArrayList;
import java.util.List;

/**
 * Windows .ani（动画光标）解码器。
 *
 * 结构是标准 RIFF：
 *   RIFF <size> ACON
 *     anih <36>   动画头
 *     rate <4×n>  每帧时长（jiffies，1/60 秒），可选
 *     seq  <4×n>  播放序列（帧下标），可选；没有就是 0,1,2,...
 *     LIST <size> fram
 *       icon <size>  一个完整的 .cur / .ico  ← 每帧
 *       icon <size>
 *       ...
 *
 * anih 的 36 字节（偏移从 chunk 数据区起算）：
 *   0  cbSize        总是 36
 *   4  nFrames       icon chunk 数量
 *   8  nSteps        播放步数（有 seq 时 = seq 长度）
 *   12 iWidth 16 iHeight 20 iBitCount 24 nPlanes
 *   28 iDispRate     默认帧时长（jiffies）
 *   32 bfAttributes  bit0=1 表示帧是 icon/cur 格式，bit1=1 表示有 seq
 *
 * 实测「千恋万花丛雨」包：nFrames=30 nSteps=30 128×128 32bpp iDispRate=4（≈67ms）。
 *
 * 注意 RIFF 的 chunk 长度若为奇数，后面要补一个填充字节 —— 遍历时必须处理，
 * 否则后续 chunk 全部错位。
 */
public final class AniDecoder {
    private AniDecoder() { }

    /** jiffy = 1/60 秒。 */
    private static final float JIFFY_MS = 1000f / 60f;

    /**
     * @param maxFrames 帧数上限（防极端包吃内存），超出部分丢弃
     * @param maxSize   单帧位图最大边长
     */
    public static CursorPack decode(byte[] d, int maxFrames, int maxSize) {
        try {
            if (d == null || d.length < 20) return null;
            if (!tag(d, 0, "RIFF") || !tag(d, 8, "ACON")) return null;

            int defaultRate = 0;
            int[] rates = null;
            int[] seq = null;
            List<int[]> icons = new ArrayList<>();   // 每项 {off, len}

            int pos = 12;
            int end = Math.min(d.length, 8 + CurDecoder.i32(d, 4));
            while (pos + 8 <= end) {
                String id = new String(d, pos, 4, "ISO-8859-1");
                int size = CurDecoder.i32(d, pos + 4);
                int body = pos + 8;
                if (size < 0 || body + size > d.length) break;

                if ("anih".equals(id)) {
                    if (size >= 32) defaultRate = CurDecoder.i32(d, body + 28);
                } else if ("rate".equals(id)) {
                    int n = size / 4;
                    rates = new int[n];
                    for (int i = 0; i < n; i++) rates[i] = CurDecoder.i32(d, body + i * 4);
                } else if ("seq ".equals(id)) {
                    int n = size / 4;
                    seq = new int[n];
                    for (int i = 0; i < n; i++) seq[i] = CurDecoder.i32(d, body + i * 4);
                } else if ("LIST".equals(id)) {
                    // LIST 的前 4 字节是子类型，"fram" 才是帧列表
                    if (size >= 4 && tag(d, body, "fram")) {
                        int p = body + 4;
                        int listEnd = body + size;
                        while (p + 8 <= listEnd) {
                            String sid = new String(d, p, 4, "ISO-8859-1");
                            int ssize = CurDecoder.i32(d, p + 4);
                            if (ssize < 0 || p + 8 + ssize > d.length) break;
                            if ("icon".equals(sid)) icons.add(new int[]{p + 8, ssize});
                            // 奇数长度补齐
                            p += 8 + ssize + (ssize & 1);
                        }
                    }
                }
                pos += 8 + size + (size & 1);
            }

            if (icons.isEmpty()) return null;

            int frameCount = Math.min(icons.size(), Math.max(1, maxFrames));
            List<CursorFrame> frames = new ArrayList<>(frameCount);
            for (int i = 0; i < frameCount; i++) {
                int[] r = icons.get(i);
                int rateJiffies = (rates != null && i < rates.length) ? rates[i] : defaultRate;
                if (rateJiffies <= 0) rateJiffies = 4;   // 缺省 4 jiffies ≈ 67ms
                int ms = Math.max(16, Math.round(rateJiffies * JIFFY_MS));
                CursorFrame f = CurDecoder.decode(d, r[0], r[1], maxSize, ms);
                if (f != null) frames.add(f);
            }
            if (frames.isEmpty()) return null;

            CursorFrame[] arr = frames.toArray(new CursorFrame[0]);

            // 构造播放序列：有 seq 用 seq，否则顺序播
            int[] sequence;
            if (seq != null && seq.length > 0) {
                List<Integer> valid = new ArrayList<>(seq.length);
                for (int s : seq) {
                    if (s >= 0 && s < arr.length) valid.add(s);
                }
                if (valid.isEmpty()) {
                    sequence = naturalSequence(arr.length);
                } else {
                    sequence = new int[valid.size()];
                    for (int i = 0; i < sequence.length; i++) sequence[i] = valid.get(i);
                }
            } else {
                sequence = naturalSequence(arr.length);
            }

            int[] durations = new int[sequence.length];
            for (int i = 0; i < sequence.length; i++) {
                // rate 是按**步**索引的（不是按帧），有 seq 时要注意这个区别
                int rateJiffies = (rates != null && i < rates.length) ? rates[i] : defaultRate;
                if (rateJiffies <= 0) {
                    durations[i] = arr[sequence[i]].durationMs;
                } else {
                    durations[i] = Math.max(16, Math.round(rateJiffies * JIFFY_MS));
                }
            }
            return new CursorPack(arr, sequence, durations);
        } catch (Throwable t) {
            return null;
        }
    }

    private static int[] naturalSequence(int n) {
        int[] s = new int[n];
        for (int i = 0; i < n; i++) s[i] = i;
        return s;
    }

    private static boolean tag(byte[] d, int off, String s) {
        if (off + 4 > d.length) return false;
        for (int i = 0; i < 4; i++) {
            if ((d[off + i] & 0xFF) != s.charAt(i)) return false;
        }
        return true;
    }
}