package com.yuki.yukihub.translate;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 自定义图片翻译 API 实现。
 *
 * 适配说明：参考 MoeTranslate 的 CustomTranslationImage.kt，转换为 Java。
 * 保留原项目版权和 LGPL 声明。
 *
 * 功能：
 *   - 支持 GET / POST 请求
 *   - POST + JSON：图片转 Base64 后作为 JSON body 字段上传
 *   - POST + multipart/form-data：图片写入临时文件后作为 form 文件字段上传
 *   - GET：图片转 Base64 后作为查询参数上传
 *   - 占位符："useimgbase64"（GET/JSON）和 "useimgfile"（multipart）
 *   - JSON 响应路径解析（通过 JsonPathParser）
 *
 * Bitmap 生命周期：调用方传入 Bitmap 副本，本类负责在翻译完成后回收。
 */
public final class CustomPicProvider implements TranslationPicProvider {

    private static final String TAG = "CustomPicApi";
    private static final long SOCKET_TIMEOUT = 10L;
    private static final MediaType JSON_TYPE = MediaType.get("application/json; charset=utf-8");
    private static final String PLACEHOLDER_BASE64 = "useimgbase64";
    private static final String PLACEHOLDER_FILE = "useimgfile";

    private final CustomApiConfig.PicConfig config;
    private final OkHttpClient client;
    private final ExecutorService executor;
    private final Handler mainHandler;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private volatile boolean released = false;

    public CustomPicProvider(CustomApiConfig.PicConfig config) {
        this.config = config;
        this.client = new OkHttpClient.Builder()
                .connectTimeout(SOCKET_TIMEOUT, TimeUnit.SECONDS)
                .readTimeout(SOCKET_TIMEOUT, TimeUnit.SECONDS)
                .writeTimeout(SOCKET_TIMEOUT, TimeUnit.SECONDS)
                .build();
        this.executor = Executors.newSingleThreadExecutor();
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    @Override
    public void translate(Bitmap bitmap, String sourceLanguage, String targetLanguage, Callback callback) {
        cancelled.set(false);
        executor.execute(() -> {
            if (released || cancelled.get()) {
                recycleBitmap(bitmap);
                return;
            }
            File tempFile = null;
            try {
                String base64Image = null;
                boolean isMultipart = "POST".equals(config.method)
                        && "multipart/form-data".equals(config.contentType);

                if (isMultipart) {
                    tempFile = File.createTempFile("translate_", ".jpg");
                    try (FileOutputStream out = new FileOutputStream(tempFile)) {
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out);
                    }
                } else {
                    base64Image = convertBitmapToBase64(bitmap);
                }
                // 回收 Bitmap
                recycleBitmap(bitmap);

                if (cancelled.get()) return;

                String result;
                if ("GET".equals(config.method)) {
                    result = executeGetRequest(base64Image);
                } else if ("application/json".equals(config.contentType)) {
                    result = executePostJsonRequest(base64Image);
                } else if (isMultipart) {
                    result = executePostMultipartRequest(tempFile);
                } else {
                    throw new Exception("Unsupported request type");
                }

                if (cancelled.get()) return;
                final String translatedText = result;
                mainHandler.post(() -> {
                    if (!cancelled.get() && callback != null) {
                        callback.onSuccess(translatedText);
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Image translation error", e);
                if (cancelled.get()) return;
                mainHandler.post(() -> {
                    if (!cancelled.get() && callback != null) {
                        callback.onError(e);
                    }
                });
            } finally {
                if (tempFile != null) {
                    //noinspection ResultOfMethodCallIgnored
                    tempFile.delete();
                }
            }
        });
    }

    private String executeGetRequest(String base64Image) throws Exception {
        HttpUrl.Builder urlBuilder = HttpUrl.parse(config.baseUrl).newBuilder();
        for (CustomApiConfig.KeyValuePair param : config.queryParams) {
            String value = PLACEHOLDER_BASE64.equals(param.value) ? base64Image : param.value;
            urlBuilder.addQueryParameter(param.key, value);
        }
        Request.Builder requestBuilder = new Request.Builder().url(urlBuilder.build());
        for (CustomApiConfig.KeyValuePair header : config.headers) {
            requestBuilder.addHeader(header.key, header.value);
        }
        return executeRequest(requestBuilder.get().build());
    }

    private String executePostJsonRequest(String base64Image) throws Exception {
        JSONObject jsonBody = new JSONObject();
        for (CustomApiConfig.KeyValuePair field : config.body) {
            String value = PLACEHOLDER_BASE64.equals(field.value) ? base64Image : field.value;
            jsonBody.put(field.key, value);
        }
        Request.Builder requestBuilder = new Request.Builder().url(config.baseUrl);
        for (CustomApiConfig.KeyValuePair header : config.headers) {
            requestBuilder.addHeader(header.key, header.value);
        }
        RequestBody body = RequestBody.create(jsonBody.toString(), JSON_TYPE);
        return executeRequest(requestBuilder.post(body).build());
    }

    private String executePostMultipartRequest(File imageFile) throws Exception {
        MultipartBody.Builder multipartBuilder = new MultipartBody.Builder()
                .setType(MultipartBody.FORM);
        for (CustomApiConfig.KeyValuePair field : config.body) {
            if (PLACEHOLDER_FILE.equals(field.value)) {
                multipartBuilder.addFormDataPart(field.key, "image.jpg",
                        RequestBody.create(imageFile, MediaType.get("multipart/form-data")));
            } else {
                multipartBuilder.addFormDataPart(field.key, field.value);
            }
        }
        Request.Builder requestBuilder = new Request.Builder().url(config.baseUrl);
        for (CustomApiConfig.KeyValuePair header : config.headers) {
            requestBuilder.addHeader(header.key, header.value);
        }
        return executeRequest(requestBuilder.post(multipartBuilder.build()).build());
    }

    private String executeRequest(Request request) throws Exception {
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new Exception("Unexpected response " + response.code());
            }
            String body = response.body() != null ? response.body().string() : "";
            return parseResponse(body);
        }
    }

    private String parseResponse(String responseBody) throws Exception {
        try {
            JSONObject json = new JSONObject(responseBody);
            return JsonPathParser.parse(json, config.jsonResponsePath);
        } catch (Exception e) {
            throw new Exception("Failed to parse response: " + e.getMessage());
        }
    }

    private String convertBitmapToBase64(Bitmap bitmap) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out);
            return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
        } catch (Exception e) {
            return "";
        }
    }

    private void recycleBitmap(Bitmap bitmap) {
        if (bitmap != null && !bitmap.isRecycled()) {
            bitmap.recycle();
        }
    }

    @Override
    public void cancel() {
        cancelled.set(true);
    }

    @Override
    public void release() {
        released = true;
        cancel();
        executor.shutdownNow();
    }
}