package com.yuki.yukihub.translate;

/**
 * 文本翻译引擎统一接口。
 *
 * 适配说明：本接口参考 MoeTranslate 的 TranslationTextAPI（Kotlin），
 * 已转换为 Java 接口。后续复制/修改具体翻译引擎实现时需保留原项目版权和 LGPL 声明。
 *
 * 约定：
 * - 翻译是异步操作，结果通过 Callback 返回。
 * - {@link #cancel()} 用于取消正在进行的翻译任务。
 * - {@link #release()} 用于释放翻译引擎占用的资源，服务停止时调用。
 * - 翻译进行中不接收新请求，由 TranslationManager 负责并发保护。
 */
public interface TranslationTextProvider {

    /**
     * 异步翻译文本。
     *
     * @param text           源文本
     * @param sourceLanguage 源语言代码（如 "ja"）
     * @param targetLanguage 目标语言代码（如 "zh"）
     * @param callback       翻译结果回调
     */
    void translate(String text, String sourceLanguage, String targetLanguage, Callback callback);

    /**
     * 取消正在进行的翻译任务。
     */
    void cancel();

    /**
     * 释放翻译引擎占用的资源。
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
