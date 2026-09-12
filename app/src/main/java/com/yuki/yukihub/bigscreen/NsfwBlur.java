package com.yuki.yukihub.bigscreen;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.view.View;

import com.yuki.yukihub.ui.BlurUtils;
import com.yuki.yukihub.util.AppExecutors;

import java.io.InputStream;

/**
 * NSFW 封面模糊（大屏版）。
 *
 * <p>规则与触摸模式**完全一致**：
 * <ul>
 *   <li>设置键同为 {@code yukihub_prefs} 里的 {@code nsfw_blur_enabled}（默认开），
 *       所以主库关掉之后大屏也一起关，不会出现"这边遮了那边没遮"</li>
 *   <li>判定：{@code game.nsfw && nsfwBlurEnabled()}</li>
 * </ul>
 *
 * <p>安全底线：**模糊图算不出来时绝不回落显示原图** —— 宁可只显示 🔞 占位。
 */
public final class NsfwBlur {

    /** 与主库一致的设置键（MainActivity.KEY_NSFW_BLUR） */
    private static final String KEY_NSFW_BLUR = "nsfw_blur_enabled";
    private static final String PREFS = "yukihub_prefs";

    private NsfwBlur() { }

    /** 是否需要对 NSFW 封面做模糊（默认开） */
    public static boolean enabled(Context context) {
        try {
            return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getBoolean(KEY_NSFW_BLUR, true);
        } catch (Throwable t) {
            return true;
        }
    }

    public interface Callback {
        /** bitmap == null 表示不可用（调用方应保持 🔞 占位，不要露原图） */
        void onReady(Bitmap bitmap);
    }

    /**
     * 异步解码 + 模糊，结果回主线程。
     *
     * @param host     用来 post 回主线程的 View（同时也用于 tag 校验宿主）
     * @param uriStr   封面 uri
     * @param cacheKey 模糊缓存键（**必须带封面 uri**：换封面后 gameId 不变）
     * @param sample   inSampleSize（封面卡 4 / 详情与背景 2）
     */
    public static void load(final View host, final String uriStr, final String cacheKey,
                            final int sample, final float radius, final Callback callback) {
        if (uriStr == null || uriStr.isEmpty()) {
            callback.onReady(null);
            return;
        }
        Bitmap cached = BlurUtils.getCached(cacheKey);
        if (cached != null) {
            callback.onReady(cached);
            return;
        }
        final Context app = host.getContext().getApplicationContext();
        AppExecutors.runOnIo(() -> {
            Bitmap out = null;
            try {
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inSampleSize = Math.max(1, sample);
                Bitmap src;
                // try-with-resources：不关流会在滚动时泄漏 fd（主库那边踩过这个坑）
                try (InputStream in = app.getContentResolver().openInputStream(Uri.parse(uriStr))) {
                    if (in == null) { return; }
                    src = BitmapFactory.decodeStream(in, null, opts);
                }
                if (src == null) { return; }
                out = BlurUtils.blur(app, src, radius);
                if (out != null) { BlurUtils.putCache(cacheKey, out); }
            } catch (Throwable ignored) {
                return;
            }
            final Bitmap result = out;
            if (result == null) { return; }
            host.post(() -> callback.onReady(result));
        });
    }

    /**
     * M18：对**已经解码好的位图**做异步模糊（首页用）。
     *
     * <p>首页的封面有两条来源：本地 uri（走 {@link #load}）和 http 远程（自己下载成 Bitmap）。
     * 远程那条已经拿到 Bitmap 了，没必要再走一次 ContentResolver，所以这里补一个位图入口。
     * 缓存键与其它入口共用同一套（{@link #cacheKey}），避免同一封面算出两张模糊图。
     */
    public static void blurAsync(final View host, final Bitmap src, final String cacheKey,
                                 final float radius, final Callback callback) {
        if (src == null) {
            callback.onReady(null);
            return;
        }
        Bitmap cached = BlurUtils.getCached(cacheKey);
        if (cached != null) {
            callback.onReady(cached);
            return;
        }
        final Context app = host.getContext().getApplicationContext();
        final Bitmap source = src;
        AppExecutors.runOnIo(() -> {
            Bitmap out = null;
            try {
                out = BlurUtils.blur(app, source, radius);
                if (out != null) { BlurUtils.putCache(cacheKey, out); }
            } catch (Throwable ignored) {
                return;
            }
            final Bitmap result = out;
            if (result == null) { return; }
            host.post(() -> callback.onReady(result));
        });
    }

    /** 缓存键（统一格式，避免各处手拼不一致） */
    public static String cacheKey(String prefix, long gameId, String coverUri) {
        return prefix + ":" + gameId + ":" + (coverUri == null ? "" : coverUri);
    }
}