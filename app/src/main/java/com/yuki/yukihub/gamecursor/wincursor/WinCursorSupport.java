package com.yuki.yukihub.gamecursor.wincursor;

import android.content.Context;
import android.net.Uri;

/**
 * Windows 光标包功能的统一入口。
 *
 * 游戏内 Overlay 与设置界面预览都走这里，保证两边行为一致
 * （之前预览只画内置箭头、不看自定义图，进游戏才发现不一样）。
 */
public final class WinCursorSupport {
    /** 当前常态光标的缓存 id。只支持一个，用固定名。 */
    public static final String ID_NORMAL = "normal";

    /** 帧数上限：防 256×256×60 帧那种极端包吃内存。 */
    public static final int MAX_FRAMES = 60;
    /** 单帧最大边长。光标实际显示才几十 dp，128 绰绰有余。 */
    public static final int MAX_SIZE = 128;
    /** 单个光标文件大小上限（丛雨包最大的 4MB，留足余量）。 */
    private static final int MAX_FILE_BYTES = 16 * 1024 * 1024;

    private WinCursorSupport() { }

    /** 解码一个 .ani 或 .cur 文件。失败返回 null。 */
    public static CursorPack decode(Context ctx, Uri uri, String fileName) {
        byte[] raw = CursorSchemeScanner.readAll(ctx, uri, MAX_FILE_BYTES);
        if (raw == null) return null;
        return decode(raw, fileName);
    }

    public static CursorPack decode(byte[] raw, String fileName) {
        if (raw == null || raw.length < 22) return null;
        boolean ani = fileName != null
                && fileName.toLowerCase(java.util.Locale.US).endsWith(".ani");
        // 优先按扩展名判，但扩展名不可信时按魔数兜底：
        // RIFF 开头就是 ani，否则当 cur
        boolean looksRiff = raw[0] == 'R' && raw[1] == 'I' && raw[2] == 'F' && raw[3] == 'F';
        if (ani || looksRiff) {
            CursorPack p = AniDecoder.decode(raw, MAX_FRAMES, MAX_SIZE);
            if (p != null) return p;
        }
        CursorFrame f = CurDecoder.decode(raw, MAX_SIZE);
        if (f == null) return null;
        return new CursorPack(new CursorFrame[]{f}, new int[]{0}, new int[]{0});
    }

    /** 解码并存入缓存，作为常态光标。成功返回帧数，失败返回 0。 */
    public static int install(Context ctx, Uri uri, String fileName) {
        CursorPack pack = decode(ctx, uri, fileName);
        if (pack == null) return 0;
        try {
            int n = pack.sequence != null ? pack.sequence.length : 1;
            boolean ok = CursorCache.store(ctx, ID_NORMAL, pack) != null;
            return ok ? n : 0;
        } finally {
            pack.recycle();
        }
    }

    /** 读取已安装的常态光标。没有返回 null。 */
    public static CursorPack loadInstalled(Context ctx) {
        return CursorCache.load(ctx, ID_NORMAL);
    }

    public static boolean hasInstalled(Context ctx) {
        return CursorCache.exists(ctx, ID_NORMAL);
    }

    public static void uninstall(Context ctx) {
        CursorCache.clear(ctx, ID_NORMAL);
    }
}