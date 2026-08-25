package com.yuki.yukihub.social;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.Log;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.WeakHashMap;

/**
 * 聊天图片选择 + 压缩。
 *
 * 背景：{@link FriendsChatDialog} 是 Dialog 而非 Activity，无法自己 registerForActivityResult。
 * 因此由宿主 Activity（HomeActivity / MainActivity）在 onCreate 里调用 {@link #register}，
 * Dialog 需要选图时通过 {@link #pick} 借用宿主的 launcher。
 *
 * launcher 存在 WeakHashMap 里，Activity 销毁后自动回收，不会泄漏。
 */
public class ChatImagePicker {

    private static final String TAG = "ChatImagePicker";

    /** 服务端上限 500KB，客户端留一点余量 */
    private static final int MAX_BYTES = 480 * 1024;
    /** 长边上限，聊天图不需要原图尺寸 */
    private static final int MAX_PIXELS = 1280;

    private static final WeakHashMap<Activity, ActivityResultLauncher<String>> LAUNCHERS = new WeakHashMap<>();
    /** 当前等待结果的回调（选图是串行的，单槽足够） */
    private static volatile Callback pendingCallback;

    public interface Callback {
        /** @param uri 用户选中的图片（null = 取消） */
        void onPicked(Uri uri);
    }

    /**
     * 宿主 Activity 在 onCreate 中调用一次。
     * 必须在 Activity STARTED 之前注册，否则 registerForActivityResult 会抛异常。
     */
    public static void register(AppCompatActivity activity) {
        if (activity == null) return;
        try {
            ActivityResultLauncher<String> launcher = activity.registerForActivityResult(
                    new ActivityResultContracts.GetContent(),
                    uri -> {
                        Callback cb = pendingCallback;
                        pendingCallback = null;
                        if (cb != null) cb.onPicked(uri);
                    });
            LAUNCHERS.put(activity, launcher);
        } catch (Throwable t) {
            Log.w(TAG, "register failed", t);
        }
    }

    /**
     * 借用宿主 Activity 的 launcher 打开系统选图。
     * @return false = 宿主未注册（此时调用方应给用户提示）
     */
    public static boolean pick(Activity activity, Callback callback) {
        ActivityResultLauncher<String> launcher = LAUNCHERS.get(activity);
        if (launcher == null) return false;
        pendingCallback = callback;
        try {
            launcher.launch("image/*");
            return true;
        } catch (Throwable t) {
            pendingCallback = null;
            Log.w(TAG, "pick failed", t);
            return false;
        }
    }

    /** 压缩结果 */
    public static class Compressed {
        public final byte[] data;
        public final String mimeType;
        public final int width;
        public final int height;
        Compressed(byte[] data, String mimeType, int width, int height) {
            this.data = data;
            this.mimeType = mimeType;
            this.width = width;
            this.height = height;
        }
    }

    /**
     * 读取并压缩到 500KB 以内（长边 ≤1280，JPEG 质量逐级下降）。
     * 需在 IO 线程调用。
     *
     * @return null = 读取/解码失败或压不到限制内
     */
    public static Compressed compress(Context context, Uri uri) {
        if (context == null || uri == null) return null;
        Bitmap bitmap = null;
        try {
            // 先读尺寸，用 inSampleSize 避免大图直接 OOM
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream is = context.getContentResolver().openInputStream(uri)) {
                if (is == null) return null;
                BitmapFactory.decodeStream(is, null, bounds);
            }
            int sample = 1;
            int longSide = Math.max(bounds.outWidth, bounds.outHeight);
            while (longSide / sample > MAX_PIXELS * 2) sample *= 2;

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            try (InputStream is = context.getContentResolver().openInputStream(uri)) {
                if (is == null) return null;
                bitmap = BitmapFactory.decodeStream(is, null, opts);
            }
            if (bitmap == null) return null;

            // 缩放到长边 MAX_PIXELS
            int w = bitmap.getWidth();
            int h = bitmap.getHeight();
            if (w > MAX_PIXELS || h > MAX_PIXELS) {
                float scale = Math.min(MAX_PIXELS / (float) w, MAX_PIXELS / (float) h);
                Bitmap scaled = Bitmap.createScaledBitmap(bitmap,
                        Math.max(1, Math.round(w * scale)),
                        Math.max(1, Math.round(h * scale)), true);
                if (scaled != bitmap) {
                    bitmap.recycle();
                    bitmap = scaled;
                }
            }

            // 质量逐级下降，直到进入体积限制
            int[] qualities = {85, 75, 65, 55, 45, 35};
            byte[] out = null;
            for (int q : qualities) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream(64 * 1024);
                bitmap.compress(Bitmap.CompressFormat.JPEG, q, bos);
                out = bos.toByteArray();
                if (out.length <= MAX_BYTES) break;
            }
            // 质量降到底还超限 → 再缩一半尺寸重试一轮
            if (out != null && out.length > MAX_BYTES) {
                Bitmap half = Bitmap.createScaledBitmap(bitmap,
                        Math.max(1, bitmap.getWidth() / 2),
                        Math.max(1, bitmap.getHeight() / 2), true);
                bitmap.recycle();
                bitmap = half;
                ByteArrayOutputStream bos = new ByteArrayOutputStream(64 * 1024);
                bitmap.compress(Bitmap.CompressFormat.JPEG, 60, bos);
                out = bos.toByteArray();
            }
            if (out == null || out.length == 0 || out.length > MAX_BYTES) return null;

            return new Compressed(out, "image/jpeg", bitmap.getWidth(), bitmap.getHeight());
        } catch (Throwable t) {
            Log.w(TAG, "compress failed", t);
            return null;
        } finally {
            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
        }
    }
}