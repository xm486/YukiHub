package com.yuki.yukihub.translate;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 火山引擎翻译实现（Java 移植自萌译 VolcTranslation.kt）。
 * 保留原项目 LGPL 版权声明。
 *
 * 请求方法：POST
 * 需要通过 VolcSign 进行 HMAC-SHA256 签名
 * 响应 JSON：{ TranslationList: [{ Translation: "翻译结果" }] }
 */
public final class VolcTranslationProvider implements TranslationTextProvider {

    private static final String TAG = "Volc";
    private static final String REGION = "cn-north-1";
    private static final String SERVICE = "translate";
    private static final String ENDPOINT = "translate.volcengineapi.com";
    private static final String SCHEMA = "https";
    private static final String PATH = "/";

    private final String ak;
    private final String sk;
    private final ExecutorService executor;
    private final Handler mainHandler;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    public VolcTranslationProvider(String ak, String sk) {
        this.ak = ak;
        this.sk = sk;
        this.executor = Executors.newSingleThreadExecutor();
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    @Override
    public void translate(String text, String sourceLanguage, String targetLanguage, Callback callback) {
        cancelled.set(false);
        executor.execute(() -> {
            if (cancelled.get()) return;
            try {
                String result = doTranslate(text, sourceLanguage, targetLanguage);
                if (cancelled.get()) return;
                mainHandler.post(() -> {
                    if (!cancelled.get() && callback != null) callback.onSuccess(result);
                });
            } catch (Exception e) {
                Log.e(TAG, "Translation error", e);
                if (cancelled.get()) return;
                mainHandler.post(() -> {
                    if (!cancelled.get() && callback != null) callback.onError(e);
                });
            }
        });
    }

    private String doTranslate(String text, String from, String to) throws Exception {
        VolcSign signer = new VolcSign(REGION, SERVICE, SCHEMA, ENDPOINT, PATH, ak, sk);

        JSONObject requestBody = new JSONObject();
        requestBody.put("TargetLanguage", to);
        if (from != null) requestBody.put("SourceLanguage", from);
        requestBody.put("TextList", new JSONArray(Collections.singletonList(text)));

        Map<String, String> queryParams = new HashMap<>();
        queryParams.put("Action", "TranslateText");
        queryParams.put("Version", "2020-06-01");

        String response = signer.doRequest(
                "POST",
                queryParams,
                requestBody.toString().getBytes("UTF-8"),
                new Date(),
                "TranslateText",
                "2020-06-01"
        );

        JSONObject jsonResponse = new JSONObject(response);

        JSONObject metadata = jsonResponse.getJSONObject("ResponseMetadata");
        if (metadata.has("Error") && !metadata.isNull("Error")) {
            JSONObject error = metadata.getJSONObject("Error");
            throw new Exception("Code: " + error.getString("Code") + " " + error.getString("Message"));
        }

        JSONArray translationList = jsonResponse.getJSONArray("TranslationList");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < translationList.length(); i++) {
            sb.append(translationList.getJSONObject(i).getString("Translation"));
        }
        return sb.toString();
    }

    @Override
    public void cancel() {
        cancelled.set(true);
    }

    @Override
    public void release() {
        cancel();
        executor.shutdownNow();
    }
}