package com.yuki.yukihub.translate;

/**
 * 翻译结果封装。
 *
 * 适配说明：参考 MoeTranslate 的 TranslationResult（Kotlin sealed class），
 * 转换为 Java 中的成功/失败两种状态。
 *
 * 用于在内部流程中传递翻译结果或错误信息。
 */
public final class TranslationResult {

    private final boolean success;
    private final String translatedText;
    private final Throwable error;

    private TranslationResult(boolean success, String translatedText, Throwable error) {
        this.success = success;
        this.translatedText = translatedText;
        this.error = error;
    }

    public static TranslationResult success(String translatedText) {
        return new TranslationResult(true, translatedText, null);
    }

    public static TranslationResult error(Throwable error) {
        return new TranslationResult(false, null, error);
    }

    public boolean isSuccess() {
        return success;
    }

    public String getTranslatedText() {
        return translatedText;
    }

    public Throwable getError() {
        return error;
    }
}