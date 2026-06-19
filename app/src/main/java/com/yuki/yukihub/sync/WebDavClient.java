package com.yuki.yukihub.sync;

import android.util.Log;

import com.yuki.yukihub.net.HttpClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import okhttp3.Credentials;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * WebDAV 客户端
 * 支持坚果云、OneDrive、NextCloud 等任意 WebDAV 服务器
 * <p>
 * 基于 OkHttp 直接构建请求，避免 Retrofit 对 ResponseBody 的消费问题。
 */
public class WebDavClient {
    private static final String TAG = "WebDavClient";

    private static final MediaType MEDIA_TYPE_JSON = MediaType.parse("application/json; charset=utf-8");
    private static final MediaType MEDIA_TYPE_XML = MediaType.parse("application/xml");

    private final String serverUrl;
    private final String username;
    private final String password;
    private final OkHttpClient client;

    public WebDavClient(String serverUrl, String username, String password) {
        this.serverUrl = normalizeServerUrl(serverUrl);
        this.username = username;
        this.password = password;

        String credential = Credentials.basic(username, password);

        client = HttpClient.defaultBuilder()
                .addInterceptor(chain -> {
                    Request original = chain.request();
                    Request.Builder builder = original.newBuilder()
                            .header("Authorization", credential);
                    // PUT 请求需要显式设置 Content-Type
                    if ("PUT".equalsIgnoreCase(original.method()) && original.body() != null) {
                        MediaType contentType = original.body().contentType();
                        if (contentType == null) {
                            builder.header("Content-Type", "application/json; charset=utf-8");
                        }
                    }
                    return chain.proceed(builder.build());
                })
                .build();
    }

    /**
     * 规范化 WebDAV 地址。
     * 坚果云必须使用 https://dav.jianguoyun.com/dav/ ，如果用户漏填 /dav/ 自动补上。
     */
    private String normalizeServerUrl(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "https://" + s;
        String lower = s.toLowerCase();
        if (lower.contains("dav.jianguoyun.com") && !lower.contains("/dav")) {
            if (!s.endsWith("/")) s += "/";
            s += "dav/";
        }
        return s.endsWith("/") ? s : s + "/";
    }

    /**
     * 测试连接
     */
    public boolean testConnection() {
        try {
            testConnectionOrThrow();
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Connection test failed", e);
            return false;
        }
    }

    /**
     * 测试连接，失败时抛出具体原因。
     */
    public void testConnectionOrThrow() throws IOException {
        String testPath = "YukiHub/YukiHub_connection_test.txt";
        writeText(testPath, "ok");
        delete(testPath);
    }

    /**
     * 创建目录（如果不存在）
     */
    public boolean mkdirs(String path) {
        try {
            String[] parts = path.split("/");
            String current = "";
            for (String part : parts) {
                if (!part.isEmpty()) {
                    current += part + "/";
                    if (!exists(current)) {
                        mkcol(current);
                    }
                }
            }
            return true;
        } catch (Exception e) {
            Log.e(TAG, "mkdirs failed: " + path, e);
            return false;
        }
    }

    /**
     * MKCOL 创建目录
     */
    private boolean mkcol(String path) throws IOException {
        Request request = new Request.Builder()
                .url(resolveUrl(path))
                .method("MKCOL", null)
                .build();
        try (Response response = client.newCall(request).execute()) {
            int code = response.code();
            return (code >= 200 && code < 300) || code == 405 || code == 409;
        }
    }

    /**
     * 检查文件/目录是否存在
     */
    public boolean exists(String path) {
        Request request = new Request.Builder()
                .url(resolveUrl(path))
                .head()
                .build();
        try (Response response = client.newCall(request).execute()) {
            return response.code() >= 200 && response.code() < 300;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 读取文件内容
     */
    public byte[] readFile(String path) throws IOException {
        Request request = new Request.Builder()
                .url(resolveUrl(path))
                .get()
                .build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String err = "";
                ResponseBody errBody = response.body();
                if (errBody != null) err = errBody.string();
                throw new IOException("HTTP " + response.code() + (err.isEmpty() ? "" : ": " + err));
            }
            ResponseBody body = response.body();
            if (body == null) throw new IOException("Empty response body");
            return body.bytes();
        }
    }

    /**
     * 读取文件内容（字符串）
     */
    public String readText(String path) throws IOException {
        return new String(readFile(path), StandardCharsets.UTF_8);
    }

    /**
     * 读取文本文件并限制最大字节数，避免云端异常大文件撑爆内存。
     */
    public String readTextLimited(String path, int maxBytes) throws IOException {
        Request request = new Request.Builder()
                .url(resolveUrl(path))
                .get()
                .build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String err = "";
                ResponseBody errBody = response.body();
                if (errBody != null) err = errBody.string();
                throw new IOException("HTTP " + response.code() + (err.isEmpty() ? "" : ": " + err));
            }
            ResponseBody body = response.body();
            if (body == null) throw new IOException("Empty response body");
            byte[] data = body.bytes();
            if (data.length > maxBytes) {
                throw new IOException("远程同步文件过大，已超过 " + (maxBytes / 1024) + "KB");
            }
            return new String(data, StandardCharsets.UTF_8);
        }
    }

    /**
     * 写入文件
     */
    public void writeFile(String path, byte[] data) throws IOException {
        RequestBody body = RequestBody.create(data, MEDIA_TYPE_JSON);
        Request request = new Request.Builder()
                .url(resolveUrl(path))
                .put(body)
                .build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String err = "";
                ResponseBody errBody = response.body();
                if (errBody != null) err = errBody.string();
                throw new IOException("PUT " + path + " failed: HTTP " + response.code() + (err.isEmpty() ? "" : ": " + err));
            }
        }
    }

    /**
     * 写入文本文件
     */
    public void writeText(String path, String text) throws IOException {
        writeFile(path, text.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 删除文件
     */
    public boolean delete(String path) {
        Request request = new Request.Builder()
                .url(resolveUrl(path))
                .delete()
                .build();
        try (Response response = client.newCall(request).execute()) {
            return response.isSuccessful();
        } catch (Exception e) {
            Log.e(TAG, "Delete failed: " + path, e);
            return false;
        }
    }

    /**
     * 列出目录内容
     */
    public List<WebDavItem> listFiles(String path) throws IOException {
        String xml = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
                "<D:propfind xmlns:D=\"DAV:\">\n" +
                "  <D:allprop/>\n" +
                "</D:propfind>";

        RequestBody body = RequestBody.create(xml.getBytes(StandardCharsets.UTF_8), MEDIA_TYPE_XML);
        Request request = new Request.Builder()
                .url(resolveUrl(path))
                .method("PROPFIND", body)
                .header("Depth", "1")
                .build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String err = "";
                ResponseBody errBody = response.body();
                if (errBody != null) err = errBody.string();
                throw new IOException("HTTP " + response.code() + ": " + err);
            }
            ResponseBody resBody = response.body();
            if (resBody == null) throw new IOException("Empty response body");
            String responseText = resBody.string();
            return parsePropfindResponse(responseText, path);
        }
    }

    /**
     * 获取文件最后修改时间
     */
    public long getLastModified(String path) {
        Request request = new Request.Builder()
                .url(resolveUrl(path))
                .head()
                .build();
        try (Response response = client.newCall(request).execute()) {
            String lastModified = response.header("Last-Modified");
            if (lastModified != null) {
                try {
                    return new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US).parse(lastModified).getTime();
                } catch (Exception ignored) {
                    try {
                        return new SimpleDateFormat("EEEE, dd-MMM-yy HH:mm:ss z", Locale.US).parse(lastModified).getTime();
                    } catch (Exception ignored2) {
                        try {
                            return new SimpleDateFormat("EEE MMM dd HH:mm:ss yyyy", Locale.US).parse(lastModified).getTime();
                        } catch (Exception ignored3) { }
                    }
                }
            }
            return 0;
        } catch (Exception e) {
            return 0;
        }
    }

    // ---- 内部辅助方法 ----

    private String resolveUrl(String path) {
        String fullPath = path.startsWith("/") ? path.substring(1) : path;
        return serverUrl + fullPath;
    }

    /**
     * 解析 PROPFIND 响应
     */
    private List<WebDavItem> parsePropfindResponse(String xml, String basePath) {
        List<WebDavItem> items = new ArrayList<>();
        String[] responses = xml.split("<D:response>|<d:response>");

        for (int i = 1; i < responses.length; i++) {
            String resp = responses[i];
            String href = extractTag(resp, "D:href", "d:href");
            if (href == null) continue;
            boolean isDir = resp.contains("<D:collection/>") || resp.contains("<d:collection/>");

            long lastModified = 0;
            String lastModStr = extractTag(resp, "D:getlastmodified", "d:getlastmodified");
            if (lastModStr != null) {
                try {
                    lastModified = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US).parse(lastModStr).getTime();
                } catch (Exception ignored) {
                    try {
                        lastModified = new SimpleDateFormat("EEEE, dd-MMM-yy HH:mm:ss z", Locale.US).parse(lastModStr).getTime();
                    } catch (Exception ignored2) { }
                }
            }

            String name = href;
            if (name.endsWith("/")) name = name.substring(0, name.length() - 1);
            int lastSlash = name.lastIndexOf('/');
            if (lastSlash >= 0) name = name.substring(lastSlash + 1);
            if (name.isEmpty() || name.equals(".")) continue;

            items.add(new WebDavItem(name, href, isDir, lastModified));
        }
        return items;
    }

    private String extractTag(String xml, String... tags) {
        for (String tag : tags) {
            int start = xml.indexOf("<" + tag + ">");
            if (start >= 0) {
                start += tag.length() + 2;
                int end = xml.indexOf("</" + tag + ">", start);
                if (end > start) return xml.substring(start, end).trim();
            }
        }
        return null;
    }

    /**
     * WebDAV 文件/目录项
     */
    public static class WebDavItem {
        public final String name;
        public final String href;
        public final boolean isDirectory;
        public final long lastModified;

        public WebDavItem(String name, String href, boolean isDirectory, long lastModified) {
            this.name = name;
            this.href = href;
            this.isDirectory = isDirectory;
            this.lastModified = lastModified;
        }

        @Override
        public String toString() {
            return (isDirectory ? "📁 " : "📄 ") + name;
        }
    }
}
