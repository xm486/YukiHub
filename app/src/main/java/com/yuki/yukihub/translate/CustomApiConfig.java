package com.yuki.yukihub.translate;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 自定义翻译 API 配置数据模型和 SharedPreferences 存储。
 *
 * 适配说明：参考 MoeTranslate 的 CustomStorage.kt（CustomTextAPIConfig /
 * CustomPicAPIConfig / ConfigurationStorage），转换为 Java。
 * 保留原项目版权和 LGPL 声明。
 *
 * 支持：
 *   - 自定义文本翻译 API（GET/POST + JSON body + headers + query params）
 *   - 自定义图片翻译 API（GET/POST + JSON/multipart + headers + query params/body）
 *   - JSON 响应路径解析（如 data.list[0].text）
 *
 * 配置持久化到 YukiHub 的 yukihub_prefs SharedPreferences，以 JSON 字符串形式存储。
 */
public final class CustomApiConfig {

    // ======================== 数据模型 ========================

    /** 键值对 */
    public static final class KeyValuePair {
        public final String key;
        public final String value;

        public KeyValuePair(String key, String value) {
            this.key = key;
            this.value = value;
        }
    }

    /** 自定义文本翻译 API 配置 */
    public static final class TextConfig {
        public final String method;             // "GET" 或 "POST"
        public final String baseUrl;
        public final List<KeyValuePair> queryParams;
        public final List<KeyValuePair> headers;
        public final List<KeyValuePair> jsonBody;
        public final String jsonResponsePath;

        public TextConfig(String method, String baseUrl,
                          List<KeyValuePair> queryParams, List<KeyValuePair> headers,
                          List<KeyValuePair> jsonBody, String jsonResponsePath) {
            this.method = method;
            this.baseUrl = baseUrl;
            this.queryParams = queryParams;
            this.headers = headers;
            this.jsonBody = jsonBody;
            this.jsonResponsePath = jsonResponsePath;
        }
    }

    /** 自定义图片翻译 API 配置 */
    public static final class PicConfig {
        public final String method;             // "GET" 或 "POST"
        public final String contentType;        // POST 时的 Content-Type，GET 时为 null
        public final String baseUrl;
        public final List<KeyValuePair> queryParams;
        public final List<KeyValuePair> headers;
        public final List<KeyValuePair> body;
        public final String jsonResponsePath;

        public PicConfig(String method, String contentType, String baseUrl,
                         List<KeyValuePair> queryParams, List<KeyValuePair> headers,
                         List<KeyValuePair> body, String jsonResponsePath) {
            this.method = method;
            this.contentType = contentType;
            this.baseUrl = baseUrl;
            this.queryParams = queryParams;
            this.headers = headers;
            this.body = body;
            this.jsonResponsePath = jsonResponsePath;
        }
    }

    // ======================== 存储键 ========================

    private static final String KEY_METHOD = "method";
    private static final String KEY_CONTENT_TYPE = "contentType";
    private static final String KEY_BASE_URL = "baseUrl";
    private static final String KEY_QUERY_PARAMS = "queryParams";
    private static final String KEY_HEADERS = "headers";
    private static final String KEY_BODY = "body";
    private static final String KEY_JSON_BODY = "jsonBody";
    private static final String KEY_JSON_RESPONSE_PATH = "jsonResponsePath";
    private static final String KEY_PAIR_KEY = "key";
    private static final String KEY_PAIR_VALUE = "value";

    private static final String PREF_TEXT_PREFIX = "translate_custom_text_api_";
    private static final String PREF_PIC_PREFIX = "translate_custom_pic_api_";

    // ======================== 存取方法 ========================

    public static void saveTextConfig(android.content.SharedPreferences prefs, TextConfig config, int apiCode) {
        try {
            JSONObject json = new JSONObject();
            json.put(KEY_METHOD, config.method);
            json.put(KEY_BASE_URL, config.baseUrl);
            json.put(KEY_JSON_RESPONSE_PATH, config.jsonResponsePath);
            json.put(KEY_QUERY_PARAMS, keyValuePairsToJsonArray(config.queryParams));
            json.put(KEY_HEADERS, keyValuePairsToJsonArray(config.headers));
            json.put(KEY_JSON_BODY, keyValuePairsToJsonArray(config.jsonBody));
            prefs.edit().putString(PREF_TEXT_PREFIX + apiCode, json.toString()).apply();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void savePicConfig(android.content.SharedPreferences prefs, PicConfig config, int apiCode) {
        try {
            JSONObject json = new JSONObject();
            json.put(KEY_METHOD, config.method);
            json.put(KEY_CONTENT_TYPE, config.contentType == null ? JSONObject.NULL : config.contentType);
            json.put(KEY_BASE_URL, config.baseUrl);
            json.put(KEY_JSON_RESPONSE_PATH, config.jsonResponsePath);
            json.put(KEY_QUERY_PARAMS, keyValuePairsToJsonArray(config.queryParams));
            json.put(KEY_HEADERS, keyValuePairsToJsonArray(config.headers));
            json.put(KEY_BODY, keyValuePairsToJsonArray(config.body));
            prefs.edit().putString(PREF_PIC_PREFIX + apiCode, json.toString()).apply();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static TextConfig loadTextConfig(android.content.SharedPreferences prefs, int apiCode) {
        try {
            String jsonStr = prefs.getString(PREF_TEXT_PREFIX + apiCode, "");
            if (jsonStr == null || jsonStr.isEmpty()) return null;
            JSONObject json = new JSONObject(jsonStr);
            return new TextConfig(
                    json.getString(KEY_METHOD),
                    json.getString(KEY_BASE_URL),
                    parseKeyValuePairs(json.getJSONArray(KEY_QUERY_PARAMS)),
                    parseKeyValuePairs(json.getJSONArray(KEY_HEADERS)),
                    parseKeyValuePairs(json.getJSONArray(KEY_JSON_BODY)),
                    json.getString(KEY_JSON_RESPONSE_PATH)
            );
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public static PicConfig loadPicConfig(android.content.SharedPreferences prefs, int apiCode) {
        try {
            String jsonStr = prefs.getString(PREF_PIC_PREFIX + apiCode, "");
            if (jsonStr == null || jsonStr.isEmpty()) return null;
            JSONObject json = new JSONObject(jsonStr);
            String contentType = json.optString(KEY_CONTENT_TYPE, null);
            return new PicConfig(
                    json.getString(KEY_METHOD),
                    contentType,
                    json.getString(KEY_BASE_URL),
                    parseKeyValuePairs(json.getJSONArray(KEY_QUERY_PARAMS)),
                    parseKeyValuePairs(json.getJSONArray(KEY_HEADERS)),
                    parseKeyValuePairs(json.getJSONArray(KEY_BODY)),
                    json.getString(KEY_JSON_RESPONSE_PATH)
            );
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    // ======================== 内部工具 ========================

    private static JSONArray keyValuePairsToJsonArray(List<KeyValuePair> pairs) {
        JSONArray arr = new JSONArray();
        for (KeyValuePair pair : pairs) {
            JSONObject obj = new JSONObject();
            try {
                obj.put(KEY_PAIR_KEY, pair.key);
                obj.put(KEY_PAIR_VALUE, pair.value);
            } catch (Exception ignored) {
            }
            arr.put(obj);
        }
        return arr;
    }

    private static List<KeyValuePair> parseKeyValuePairs(JSONArray arr) {
        List<KeyValuePair> pairs = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            try {
                JSONObject obj = arr.getJSONObject(i);
                pairs.add(new KeyValuePair(obj.getString(KEY_PAIR_KEY), obj.getString(KEY_PAIR_VALUE)));
            } catch (Exception ignored) {
            }
        }
        return pairs;
    }

    private CustomApiConfig() {
    }
}