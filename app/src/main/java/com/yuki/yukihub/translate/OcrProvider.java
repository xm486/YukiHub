package com.yuki.yukihub.translate;

import android.graphics.Bitmap;

/**
 * OCR 引擎统一接口。
 *
 * 适配说明：参考 MoeTranslate 的 OCRProvider 接口，将 suspend fun 改为 Java 回调，
 * 并保留原 LGPL 适配要求。上层只依赖本接口，新增引擎只需实现本接口。
 */
public interface OcrProvider {

    /**
     * 识别 Bitmap 中的文字，通过回调返回结果。
     *
     * @param bitmap         待识别图（调用方不应回收它，回调中传入的 Bitmap 属于调用方）
     * @param sourceLanguage 源语言代码（ja/zh/en/ko）
     * @param mergeMode      合并模式：0=不合并，1=分段合并，2=直接合并
     * @param callback       结果回调，在主线程执行
     */
    void recognize(Bitmap bitmap, String sourceLanguage, int mergeMode, Callback callback);

    /** 释放引擎占用的资源（识别器等）。服务停止时调用。 */
    void release();

    interface Callback {
        void onSuccess(String text, String sourceLanguage);
        void onFailure(Exception error);
    }
}