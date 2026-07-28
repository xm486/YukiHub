package com.yuki.yukihub.translate;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 翻译管理器。
 *
 * 负责统一调用翻译引擎，提供以下能力：
 * - 并发保护：翻译进行中拒绝新请求。
 * - 取消上一次翻译：发起新翻译前取消旧翻译。
 * - 统一回调：在主线程回调结果。
 * - 资源释放：服务停止时释放翻译引擎。
 *
 * 适配说明：参考 MoeTranslate FloatingBallService 中的 isTranslating AtomicBoolean
 * 和 translatorText 调用逻辑，转换为 Java 管理器。
 */
public class TranslationManager {

    private static final String TAG = "TranslationManager";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean translating = new AtomicBoolean(false);

    private TranslationTextProvider provider;

    /**
     * 翻译结果回调。
     */
    public interface Callback {
        void onSuccess(String sourceText, String translatedText);
        void onError(Throwable error);
    }

    public TranslationManager(TranslationTextProvider provider) {
        this.provider = provider;
    }

    /**
     * 设置或替换翻译引擎实例。
     * 如果之前有引擎实例，先释放旧实例。
     */
    public void setProvider(TranslationTextProvider newProvider) {
        if (provider != null && provider != newProvider) {
            try {
                provider.release();
            } catch (Throwable ignored) {
            }
        }
        provider = newProvider;
    }

    /**
     * 发起翻译请求。
     *
     * @param text           源文本（不能为空或空白）
     * @param sourceLanguage 源语言代码
     * @param targetLanguage 目标语言代码
     * @param callback       结果回调（主线程）
     * @return true 表示请求已受理，false 表示当前正在翻译中（被跳过）
     */
    public boolean translate(String text, String sourceLanguage, String targetLanguage, Callback callback) {
        if (text == null || text.trim().isEmpty()) {
            Log.d(TAG, "translate: text is empty, skip");
            return false;
        }
        if (!translating.compareAndSet(false, true)) {
            Log.d(TAG, "translate: already translating, skip");
            return false;
        }
        if (provider == null) {
            translating.set(false);
            if (callback != null) {
                callback.onError(new IllegalStateException("翻译引擎未配置"));
            }
            return false;
        }

        // 取消上一次翻译（如果引擎支持）
        try {
            provider.cancel();
        } catch (Throwable ignored) {
        }

        final String sourceText = text;
        provider.translate(text, sourceLanguage, targetLanguage,
                new TranslationTextProvider.Callback() {
                    @Override
                    public void onSuccess(String translatedText) {
                        translating.set(false);
                        if (callback != null) {
                            mainHandler.post(() -> callback.onSuccess(sourceText, translatedText));
                        }
                    }

                    @Override
                    public void onError(Throwable error) {
                        translating.set(false);
                        if (callback != null) {
                            mainHandler.post(() -> callback.onError(error));
                        }
                    }
                });
        return true;
    }

    /**
     * 是否正在翻译中。
     */
    public boolean isTranslating() {
        return translating.get();
    }

    /**
     * 取消正在进行的翻译。
     */
    public void cancel() {
        if (provider != null) {
            try {
                provider.cancel();
            } catch (Throwable ignored) {
            }
        }
        translating.set(false);
    }

    /**
     * 释放翻译引擎资源。
     */
    public void release() {
        cancel();
        if (provider != null) {
            try {
                provider.release();
            } catch (Throwable ignored) {
            }
            provider = null;
        }
    }
}