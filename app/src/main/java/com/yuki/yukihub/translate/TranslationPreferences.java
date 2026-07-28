package com.yuki.yukihub.translate;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 翻译偏好设置集中管理。
 *
 * 使用 YukiHub 统一的 SharedPreferences ("yukihub_prefs")，
 * 所有翻译相关键均以 "translate_" 前缀存储。
 *
 * 提供默认值，与计划书 §13 配置项建议一致。
 */
public final class TranslationPreferences {

    private static final String PREFS_NAME = "yukihub_prefs";

    // 键名
    public static final String KEY_MODE = "translate_mode";
    public static final String KEY_OCR_ENGINE = "translate_ocr_engine";
    public static final String KEY_TEXT_ENGINE = "translate_text_engine";
    public static final String KEY_SOURCE_LANG = "translate_source_language";
    public static final String KEY_TARGET_LANG = "translate_target_language";
    public static final String KEY_AUTO_ENABLED = "translate_auto_enabled";
    public static final String KEY_AUTO_INTERVAL = "translate_auto_interval";
    public static final String KEY_AUTO_LENGTH_THRESHOLD = "translate_auto_length_threshold";
    public static final String KEY_AUTO_SIMILARITY = "translate_auto_similarity_threshold";
    public static final String KEY_SHOW_SOURCE_MODE = "translate_show_source_mode";
    public static final String KEY_RESULT_FONT_SIZE = "translate_result_font_size";
    public static final String KEY_RESULT_PENETRABLE = "translate_result_penetrable";
    public static final String KEY_OCR_MERGE_MODE = "translate_ocr_merge_mode";
    public static final String KEY_PIC_ENGINE = "translate_pic_engine";
    public static final String KEY_HISTORY_ENABLED = "translate_history_enabled";
    public static final String KEY_HISTORY_PROMPT = "translate_history_prompt";
    public static final String KEY_HISTORY_COUNT = "translate_history_count";
    public static final String KEY_CUSTOM_TEXT_API_SLOT = "translate_custom_text_api_slot";
    public static final String KEY_CUSTOM_PIC_API_SLOT = "translate_custom_pic_api_slot";

    // 默认值
    public static final String DEFAULT_MODE = "OCR_TEXT";
    public static final String DEFAULT_OCR_ENGINE = "PADDLE_OCR";
    public static final String DEFAULT_TEXT_ENGINE = "BING";
    public static final String DEFAULT_SOURCE_LANG = "ja";
    public static final String DEFAULT_TARGET_LANG = "zh";
    public static final boolean DEFAULT_AUTO_ENABLED = false;
    public static final long DEFAULT_AUTO_INTERVAL = 3000L;
    public static final int DEFAULT_AUTO_LENGTH_THRESHOLD = 10;
    public static final float DEFAULT_AUTO_SIMILARITY = 0.8f;
    public static final int DEFAULT_SHOW_SOURCE_MODE = 0; // TRANSLATED_ONLY
    public static final float DEFAULT_RESULT_FONT_SIZE = 16f;
    public static final boolean DEFAULT_RESULT_PENETRABLE = true;
    public static final int DEFAULT_OCR_MERGE_MODE = 2;
    public static final String DEFAULT_PIC_ENGINE = "CUSTOM";
    public static final boolean DEFAULT_HISTORY_ENABLED = false;
    public static final String DEFAULT_HISTORY_PROMPT = "以下是供参考的翻译记录：";
    public static final int DEFAULT_HISTORY_COUNT = 5;
    public static final int DEFAULT_CUSTOM_TEXT_API_SLOT = 0;
    public static final int DEFAULT_CUSTOM_PIC_API_SLOT = 0;

    // 各引擎 API Key 存储键
    public static final String KEY_NIUTRANS_APIKEY = "translate_niutrans_apikey";
    public static final String KEY_OPENAI_APIKEY = "translate_openai_apikey";
    public static final String KEY_OPENAI_BASEURL = "translate_openai_baseurl";
    public static final String KEY_OPENAI_MODEL = "translate_openai_model";
    public static final String KEY_VOLC_AK = "translate_volc_ak";
    public static final String KEY_VOLC_SK = "translate_volc_sk";
    public static final String KEY_AZURE_KEY = "translate_azure_key";
    public static final String KEY_DEEPL_HOST = "translate_deepl_host";
    public static final String KEY_DEEPL_APIKEY = "translate_deepl_apikey";
    public static final String KEY_BAIDU_APPID = "translate_baidu_appid";
    public static final String KEY_BAIDU_SECRETKEY = "translate_baidu_secretkey";
    public static final String KEY_TENCENT_SECRETID = "translate_tencent_secretid";
    public static final String KEY_TENCENT_SECRETKEY = "translate_tencent_secretkey";

    private final SharedPreferences prefs;

    public TranslationPreferences(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public String getMode() {
        return prefs.getString(KEY_MODE, DEFAULT_MODE);
    }

    public String getOcrEngine() {
        return prefs.getString(KEY_OCR_ENGINE, DEFAULT_OCR_ENGINE);
    }

    public String getTextEngine() {
        return prefs.getString(KEY_TEXT_ENGINE, DEFAULT_TEXT_ENGINE);
    }

    public String getSourceLanguage() {
        return prefs.getString(KEY_SOURCE_LANG, DEFAULT_SOURCE_LANG);
    }

    public String getTargetLanguage() {
        return prefs.getString(KEY_TARGET_LANG, DEFAULT_TARGET_LANG);
    }

    public boolean isAutoEnabled() {
        return prefs.getBoolean(KEY_AUTO_ENABLED, DEFAULT_AUTO_ENABLED);
    }

    public long getAutoInterval() {
        return prefs.getLong(KEY_AUTO_INTERVAL, DEFAULT_AUTO_INTERVAL);
    }

    public int getAutoLengthThreshold() {
        return prefs.getInt(KEY_AUTO_LENGTH_THRESHOLD, DEFAULT_AUTO_LENGTH_THRESHOLD);
    }

    public float getAutoSimilarityThreshold() {
        return prefs.getFloat(KEY_AUTO_SIMILARITY, DEFAULT_AUTO_SIMILARITY);
    }

    public int getShowSourceMode() {
        return prefs.getInt(KEY_SHOW_SOURCE_MODE, DEFAULT_SHOW_SOURCE_MODE);
    }

    public float getResultFontSize() {
        return prefs.getFloat(KEY_RESULT_FONT_SIZE, DEFAULT_RESULT_FONT_SIZE);
    }

    public boolean isResultPenetrable() {
        return prefs.getBoolean(KEY_RESULT_PENETRABLE, DEFAULT_RESULT_PENETRABLE);
    }

    public int getOcrMergeMode() {
        return prefs.getInt(KEY_OCR_MERGE_MODE, DEFAULT_OCR_MERGE_MODE);
    }

    public String getPicEngine() {
        return prefs.getString(KEY_PIC_ENGINE, DEFAULT_PIC_ENGINE);
    }

    public boolean isHistoryEnabled() {
        return prefs.getBoolean(KEY_HISTORY_ENABLED, DEFAULT_HISTORY_ENABLED);
    }

    public String getHistoryPrompt() {
        return prefs.getString(KEY_HISTORY_PROMPT, DEFAULT_HISTORY_PROMPT);
    }

    public int getHistoryCount() {
        return prefs.getInt(KEY_HISTORY_COUNT, DEFAULT_HISTORY_COUNT);
    }

    public int getCustomTextApiSlot() {
        return prefs.getInt(KEY_CUSTOM_TEXT_API_SLOT, DEFAULT_CUSTOM_TEXT_API_SLOT);
    }

    public int getCustomPicApiSlot() {
        return prefs.getInt(KEY_CUSTOM_PIC_API_SLOT, DEFAULT_CUSTOM_PIC_API_SLOT);
    }

    /**
     * 保存单项设置。
     */
    public void putString(String key, String value) {
        prefs.edit().putString(key, value).apply();
    }

    public void putInt(String key, int value) {
        prefs.edit().putInt(key, value).apply();
    }

    public void putLong(String key, long value) {
        prefs.edit().putLong(key, value).apply();
    }

    public void putFloat(String key, float value) {
        prefs.edit().putFloat(key, value).apply();
    }

    public void putBoolean(String key, boolean value) {
        prefs.edit().putBoolean(key, value).apply();
    }

    public SharedPreferences raw() {
        return prefs;
    }
}