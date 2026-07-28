package com.yuki.yukihub.translate;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** PaddleOCR PP-OCRv6 离线 Provider。 */
public final class PaddleOcrProvider implements OcrProvider {
    private static final String TAG = "PaddleOcrProvider";
    private final Context appContext;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile boolean released;

    public PaddleOcrProvider(Context context) {
        this.appContext = context.getApplicationContext();
    }

    @Override
    public void recognize(Bitmap bitmap, String sourceLanguage, int mergeMode, Callback callback) {
        if (released) {
            if (callback != null) callback.onFailure(new IllegalStateException("PaddleOCR 已释放"));
            return;
        }
        if (bitmap == null || bitmap.isRecycled()) {
            if (callback != null) callback.onFailure(new IllegalArgumentException("Bitmap 无效"));
            return;
        }
        executor.execute(() -> {
            try {
                if (!PPOcrV6Engine.isInitialized()) PPOcrV6Engine.initialize(appContext);
                final String text = PPOcrV6Engine.runOCR(bitmap);
                mainHandler.post(() -> {
                    if (!released && callback != null) {
                        callback.onSuccess(text, normalizeLanguage(sourceLanguage));
                    }
                });
            } catch (Throwable error) {
                Log.e(TAG, "PaddleOCR failed", error);
                mainHandler.post(() -> {
                    if (!released && callback != null) {
                        callback.onFailure(error instanceof Exception
                                ? (Exception) error : new RuntimeException(error));
                    }
                });
            }
        });
    }

    @Override
    public void release() {
        released = true;
        executor.shutdownNow();
        PPOcrV6Engine.release();
    }

    private static String normalizeLanguage(String language) {
        return language == null || language.trim().isEmpty() ? "ja" : language;
    }
}