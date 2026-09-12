package com.yuki.yukihub.bigscreen;

import android.content.Context;
import android.net.Uri;
import android.text.TextUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * 大屏模式的自定义图（M10）：**游戏标题图**（Steam 式 logo）与**游戏背景图**。
 *
 * <p>文件都复制到应用私有目录 {@code files/bigscreen/art/}，路径写进 games 表的
 * {@code logo_path} / {@code bg_path}，与原视频分开管理（删除只删自己目录里的文件，防误删）。
 */
public class BigScreenArt {

    public static final String KIND_LOGO = "logo";
    public static final String KIND_BG = "bg";

    private final Context context;

    public BigScreenArt(Context context) {
        this.context = context.getApplicationContext();
    }

    private File dir() {
        File d = new File(context.getFilesDir(), "bigscreen/art");
        if (!d.exists()) { d.mkdirs(); }
        return d;
    }

    /** 把用户选中的图片复制进应用目录；失败返回 null */
    public File copyToInternal(Uri uri, long gameId, String kind) {
        if (uri == null) { return null; }
        String ext = guessExt(uri.toString());
        File target = new File(dir(), kind + "_" + gameId + "_" + System.currentTimeMillis() + ext);
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

    /** 删除自定义图（只删 art 目录内的文件） */
    public void delete(String path) {
        if (TextUtils.isEmpty(path)) { return; }
        try {
            File f = new File(path);
            if (f.getParentFile() != null && f.getParentFile().equals(dir())) { f.delete(); }
        } catch (Throwable ignored) { }
    }

    private static String guessExt(String uriStr) {
        if (uriStr == null) { return ".png"; }
        String s = uriStr.toLowerCase(java.util.Locale.US);
        if (s.contains(".jpg") || s.contains(".jpeg")) { return ".jpg"; }
        if (s.contains(".webp")) { return ".webp"; }
        return ".png";
    }
}
