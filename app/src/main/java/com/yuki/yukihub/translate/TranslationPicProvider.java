package com.yuki.yukihub.translate;

import android.graphics.Bitmap;

/**
 * 图片翻译引擎统一接口。
 *
 * 适配说明：参考 MoeTranslate 的 TranslationPicAPI（Kotlin），
 * 转换为 Java 接口。后续复制/修改具体图片翻译引擎实现时需保留原项目版权和 LGPL 声明。
 *
 * 与 {@link TranslationTextProvider} 的区别：
 * - 文本翻译：截图 → OCR → 文本翻译 API
 * - 图片翻译：截图 → 直接上传图片给翻译 API（API 内部做 OCR + 翻译）
 *
 * 约定：
 * - Bitmap 所有权：调用方传入 Bitmap 副本，实现负责在翻译完成后回收。
 * - 翻译是异步操作，结果通过 Callback 返回。
 * - {@link #cancel()} 用于取消正在进行的翻译任务。
 * - {@link #release()} 用于释放资源，服务停止时调用。
 */
public interface TranslationPicProvider {

    /**
     * 异步翻译图片。
     *
     * @param bitmap         图片 Bitmap（副本，调用方负责在回调后回收）
     * @param sourceLanguage 源语言代码（如 "ja"）
     * @param targetLanguage 目标语言代码（如 "zh"）
     * @param callback       翻译结果回调
     */
    void translate(Bitmap bitmap, String sourceLanguage, String targetLanguage, Callback callback);

    /**
     * 取消正在进行的翻译任务。
     */
    void cancel();

    /**
     * 释放资源。
     */
    void release();

    /**
     * 翻译结果回调。
     */
    interface Callback {
        void onSuccess(String translatedText);
        void onError(Throwable error);
    }
}