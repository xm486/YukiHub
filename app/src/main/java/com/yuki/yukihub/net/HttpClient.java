package com.yuki.yukihub.net;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import retrofit2.Call;
import retrofit2.Retrofit;

/**
 * HTTP 客户端公共工厂。
 * <p>
 * 统一管理 OkHttpClient / Retrofit 实例的创建与配置，
 * 为项目中所有网络请求（WebDAV、VNDB、Bangumi、月幕 Gal、AI Review）提供一致的底层设施。
 */
public final class HttpClient {

    private static final String USER_AGENT = "YukiHub/1.0";

    private HttpClient() { }

    /**
     * 创建一个通用的 OkHttpClient Builder，预设了合理的超时和 User-Agent。
     * 调用方可在此基础上追加 Interceptor（如 Auth）或修改超时。
     */
    public static OkHttpClient.Builder okHttpClientBuilder() {
        return new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true);
    }

    /**
     * 创建带 User-Agent 的 OkHttpClient Builder。
     */
    public static OkHttpClient.Builder defaultBuilder() {
        return okHttpClientBuilder()
                .addInterceptor(chain -> {
                    Request original = chain.request();
                    Request request = original.newBuilder()
                            .header("User-Agent", USER_AGENT)
                            .build();
                    return chain.proceed(request);
                });
    }

    /**
     * 基于 OkHttpClient 创建 Retrofit 实例。
     *
     * @param baseUrl 基础 URL，必须以 / 结尾
     * @param client  已配置好的 OkHttpClient
     * @return Retrofit 实例
     */
    public static Retrofit retrofit(String baseUrl, OkHttpClient client) {
        return new Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(client)
                .build();
    }

    /**
     * 同步执行 Retrofit Call 并返回反序列化后的 ResponseBody 字符串。
     * 自动关闭 ResponseBody。
     */
    public static String executeForString(Call<okhttp3.ResponseBody> call) throws IOException {
        retrofit2.Response<okhttp3.ResponseBody> response = call.clone().execute();
        try {
            if (!response.isSuccessful()) {
                String err = "";
                okhttp3.ResponseBody errBody = response.errorBody();
                if (errBody != null) err = errBody.string();
                throw new IOException("HTTP " + response.code() + (err.isEmpty() ? "" : ": " + err));
            }
            okhttp3.ResponseBody body = response.body();
            if (body == null) throw new IOException("Empty response body");
            return body.string();
        } finally {
            closeQuietly(response);
        }
    }

    /**
     * 同步执行 Retrofit Call 并返回 ResponseBody 字节数组。
     * 自动关闭 ResponseBody。
     */
    public static byte[] executeForBytes(Call<okhttp3.ResponseBody> call) throws IOException {
        retrofit2.Response<okhttp3.ResponseBody> response = call.clone().execute();
        try {
            if (!response.isSuccessful()) {
                String err = "";
                okhttp3.ResponseBody errBody = response.errorBody();
                if (errBody != null) err = errBody.string();
                throw new IOException("HTTP " + response.code() + (err.isEmpty() ? "" : ": " + err));
            }
            okhttp3.ResponseBody body = response.body();
            if (body == null) throw new IOException("Empty response body");
            return body.bytes();
        } finally {
            closeQuietly(response);
        }
    }

    /**
     * 带重试的同步执行，返回 ResponseBody 字符串。
     * 对 429 / 5xx 自动重试，指数退避。
     *
     * @param callFactory 每次重试创建新 Call 的工厂
     * @param maxRetries  最大重试次数（不含首次）
     * @param baseDelayMs 首次重试延迟（ms），后续指数增长
     */
    public static String executeWithRetry(CallFactory callFactory, int maxRetries, long baseDelayMs) throws IOException {
        IOException lastError = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            retrofit2.Response<okhttp3.ResponseBody> response = null;
            try {
                response = callFactory.create().execute();
                if (response.isSuccessful()) {
                    okhttp3.ResponseBody body = response.body();
                    return body == null ? "" : body.string();
                }
                int code = response.code();
                String err = "";
                okhttp3.ResponseBody errBody = response.errorBody();
                if (errBody != null) err = errBody.string();
                String msg = "HTTP " + code + (err.isEmpty() ? "" : ": " + err);

                if ((code == 429 || code >= 500) && attempt < maxRetries) {
                    lastError = new IOException(msg);
                    closeQuietly(response);
                    response = null;
                    sleep(baseDelayMs * (attempt + 1));
                    continue;
                }
                throw new IOException(msg);
            } catch (IOException e) {
                if (attempt < maxRetries) {
                    lastError = e;
                    closeQuietly(response);
                    response = null;
                    sleep(baseDelayMs * (attempt + 1));
                    continue;
                }
                throw e;
            } finally {
                closeQuietly(response);
            }
        }
        throw lastError != null ? lastError : new IOException("unreachable");
    }

    /**
     * Call 工厂接口，用于重试场景下每次创建新的 Call。
     */
    @FunctionalInterface
    public interface CallFactory {
        Call<okhttp3.ResponseBody> create();
    }

    private static void closeQuietly(retrofit2.Response<? extends okhttp3.ResponseBody> response) {
        if (response != null) {
            try {
                okhttp3.ResponseBody body = response.body();
                if (body != null) body.close();
                okhttp3.ResponseBody errBody = response.errorBody();
                if (errBody != null && errBody != body) errBody.close();
            } catch (Exception ignored) { }
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(Math.max(0, ms));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
