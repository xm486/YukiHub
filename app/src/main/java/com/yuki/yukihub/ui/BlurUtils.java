package com.yuki.yukihub.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.renderscript.Allocation;
import android.renderscript.Element;
import android.renderscript.RenderScript;
import android.renderscript.ScriptIntrinsicBlur;
import android.util.LruCache;

import java.io.InputStream;

/**
 * 高斯模糊工具类，用于 NSFW 封面模糊。
 * 基于 RenderScript ScriptIntrinsicBlur（API 17+，minSdk 26 满足）。
 * 包含 LruCache 缓存，避免重复模糊同一张图。
 */
public class BlurUtils {

    private static RenderScript rsInstance;
    private static final LruCache<String, Bitmap> blurCache = new LruCache<String, Bitmap>(50) {
        @Override
        protected int sizeOf(String key, Bitmap bitmap) {
            return bitmap.getByteCount() / 1024; // KB
        }
    };

    private static RenderScript getRs(Context context) {
        if (rsInstance == null) {
            rsInstance = RenderScript.create(context.getApplicationContext());
        }
        return rsInstance;
    }

    /**
     * 获取缓存的模糊 Bitmap。
     */
    public static Bitmap getCached(String key) {
        return blurCache.get(key);
    }

    /**
     * 缓存模糊 Bitmap。
     */
    public static void putCache(String key, Bitmap bitmap) {
        if (key != null && bitmap != null) blurCache.put(key, bitmap);
    }

    /**
     * 对 Bitmap 应用高斯模糊。
     * @param bitmap 源图
     * @param radius 模糊半径，0~25（25 最强模糊，推荐 20~22 可勉强看到轮廓）
     * @return 模糊后的 Bitmap，失败时返回源图
     */
    public static Bitmap blur(Context context, Bitmap bitmap, float radius) {
        if (bitmap == null) return null;
        try {
            RenderScript rs = getRs(context);
            Allocation input = Allocation.createFromBitmap(rs, bitmap);
            Allocation output = Allocation.createTyped(rs, input.getType());
            ScriptIntrinsicBlur blur = ScriptIntrinsicBlur.create(rs, Element.U8_4(rs));
            blur.setInput(input);
            blur.setRadius(Math.max(0, Math.min(25, radius)));
            blur.forEach(output);
            output.copyTo(bitmap);
            input.destroy();
            output.destroy();
            blur.destroy();
            return bitmap;
        } catch (Throwable e) {
            // RenderScript 失败时降级：缩略图模糊
            try {
                int w = bitmap.getWidth();
                int h = bitmap.getHeight();
                Bitmap small = Bitmap.createScaledBitmap(bitmap, Math.max(1, w / 8), Math.max(1, h / 8), true);
                Bitmap scaled = Bitmap.createScaledBitmap(small, w, h, true);
                small.recycle();
                return scaled;
            } catch (Throwable ignored) {
                return bitmap;
            }
        }
    }

    /**
     * 从 URI 加载 Bitmap 并模糊。
     * @param context Context
     * @param uri 图片 URI
     * @param radius 模糊半径
     * @return 模糊后的 Bitmap，失败时返回 null
     */
    public static Bitmap blurUri(Context context, Uri uri, float radius) {
        try {
            InputStream is = context.getContentResolver().openInputStream(uri);
            if (is == null) return null;
            Bitmap bitmap = BitmapFactory.decodeStream(is);
            is.close();
            if (bitmap == null) return null;
            return blur(context, bitmap, radius);
        } catch (Throwable e) {
            return null;
        }
    }
}