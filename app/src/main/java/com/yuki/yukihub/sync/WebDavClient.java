package com.yuki.yukihub.sync;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * WebDAV 客户端
 * 支持坚果云、OneDrive、NextCloud 等任意 WebDAV 服务器
 */
public class WebDavClient {
    private static final String TAG = "WebDavClient";
    
    private final String serverUrl;
    private final String username;
    private final String password;
    
    public WebDavClient(String serverUrl, String username, String password) {
        this.serverUrl = normalizeServerUrl(serverUrl);
        this.username = username;
        this.password = password;
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
        // 真正测试 WebDAV 写入能力：PUT 一个很小的临时文件，再删除。
        String testPath = "YukiHub/YukiHub_connection_test.txt";
        writeText(testPath, "ok");
        delete(testPath);
    }
    
    /**
     * 创建目录（如果不存在）
     */
    public boolean mkdirs(String path) {
        try {
            // 确保路径存在，逐级创建
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
        HttpURLConnection conn = createConnection(path, "MKCOL");
        int code = conn.getResponseCode();
        conn.disconnect();
        // 某些 WebDAV 服务器在目录已存在时会返回 405/409，
        // 这对我们来说等价于“目录可用”。
        return (code >= 200 && code < 300) || code == 405 || code == 409;
    }
    
    /**
     * 检查文件/目录是否存在
     */
    public boolean exists(String path) {
        try {
            HttpURLConnection conn = createConnection(path, "HEAD");
            int code = conn.getResponseCode();
            conn.disconnect();
            return code >= 200 && code < 300;
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * 读取文件内容
     */
    public byte[] readFile(String path) throws IOException {
        HttpURLConnection conn = createConnection(path, "GET");
        int code = conn.getResponseCode();
        
        if (code < 200 || code >= 300) {
            conn.disconnect();
            throw new IOException("HTTP " + code);
        }
        
        InputStream is = conn.getInputStream();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int len;
        while ((len = is.read(buf)) != -1) {
            bos.write(buf, 0, len);
        }
        conn.disconnect();
        return bos.toByteArray();
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
        HttpURLConnection conn = createConnection(path, "GET");
        int code = conn.getResponseCode();
        if (code < 200 || code >= 300) {
            String err = readStream(conn.getErrorStream());
            conn.disconnect();
            throw new IOException("HTTP " + code + (err.isEmpty() ? "" : ": " + err));
        }
        InputStream is = conn.getInputStream();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int len;
        int total = 0;
        while ((len = is.read(buf)) != -1) {
            total += len;
            if (total > maxBytes) {
                conn.disconnect();
                throw new IOException("远程同步文件过大，已超过 " + (maxBytes / 1024) + "KB");
            }
            bos.write(buf, 0, len);
        }
        conn.disconnect();
        return bos.toString("UTF-8");
    }
    
    /**
     * 写入文件
     */
    public void writeFile(String path, byte[] data) throws IOException {
        HttpURLConnection conn = createConnection(path, "PUT");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        conn.setRequestProperty("Accept", "*/*");
        conn.setFixedLengthStreamingMode(data.length);
        
        OutputStream os = conn.getOutputStream();
        os.write(data);
        os.flush();
        os.close();
        
        int code = conn.getResponseCode();
        String err = code >= 200 && code < 300 ? "" : readStream(conn.getErrorStream());
        conn.disconnect();
        
        if (code < 200 || code >= 300) {
            throw new IOException("PUT " + path + " failed: HTTP " + code + (err.isEmpty() ? "" : ": " + err));
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
        try {
            HttpURLConnection conn = createConnection(path, "DELETE");
            int code = conn.getResponseCode();
            conn.disconnect();
            return code >= 200 && code < 300;
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
        
        HttpURLConnection conn = createConnection(path, "PROPFIND");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/xml");
        conn.setRequestProperty("Depth", "1");
        
        OutputStream os = conn.getOutputStream();
        os.write(xml.getBytes(StandardCharsets.UTF_8));
        os.flush();
        os.close();
        
        int code = conn.getResponseCode();
        InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
        String response = readStream(is);
        conn.disconnect();
        
        if (code < 200 || code >= 300) {
            throw new IOException("HTTP " + code + ": " + response);
        }
        
        return parsePropfindResponse(response, path);
    }
    
    /**
     * 获取文件最后修改时间
     */
    public long getLastModified(String path) {
        try {
            HttpURLConnection conn = createConnection(path, "HEAD");
            long lastModified = conn.getLastModified();
            conn.disconnect();
            return lastModified;
        } catch (Exception e) {
            return 0;
        }
    }
    
    /**
     * 创建 HTTP 连接
     */
    private HttpURLConnection createConnection(String path, String method) throws IOException {
        // 构建完整 URL
        String fullPath = path.startsWith("/") ? path.substring(1) : path;
        URL url = new URL(serverUrl + fullPath);
        
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod(method);
        conn.setInstanceFollowRedirects(true);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        
        // Basic 认证
        String auth = username + ":" + password;
        String encodedAuth = android.util.Base64.encodeToString(
                auth.getBytes(StandardCharsets.UTF_8), 
                android.util.Base64.NO_WRAP
        );
        conn.setRequestProperty("Authorization", "Basic " + encodedAuth);
        conn.setRequestProperty("User-Agent", "YukiHub/1.0");
        
        return conn;
    }
    
    /**
     * 读取输入流
     */
    private String readStream(InputStream is) throws IOException {
        if (is == null) return "";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int len;
        while ((len = is.read(buf)) != -1) {
            bos.write(buf, 0, len);
        }
        return bos.toString("UTF-8");
    }
    
    /**
     * 解析 PROPFIND 响应
     */
    private List<WebDavItem> parsePropfindResponse(String xml, String basePath) {
        List<WebDavItem> items = new ArrayList<>();
        
        // 简单的 XML 解析（避免依赖）
        String[] responses = xml.split("<D:response>|<d:response>");
        
        for (int i = 1; i < responses.length; i++) {
            String resp = responses[i];
            
            // 提取 href
            String href = extractTag(resp, "D:href", "d:href");
            if (href == null) continue;
            
            // 检查是否是目录
            boolean isDir = resp.contains("<D:collection/>") || resp.contains("<d:collection/>");
            
            // 提取最后修改时间
            long lastModified = 0;
            String lastModStr = extractTag(resp, "D:getlastmodified", "d:getlastmodified");
            if (lastModStr != null) {
                try {
                    lastModified = java.text.SimpleDateFormat.getDateTimeInstance().parse(lastModStr).getTime();
                } catch (Exception ignored) {}
            }
            
            // 计算相对路径
            String name = href;
            if (name.endsWith("/")) name = name.substring(0, name.length() - 1);
            int lastSlash = name.lastIndexOf('/');
            if (lastSlash >= 0) name = name.substring(lastSlash + 1);
            
            // 跳过当前目录
            if (name.isEmpty() || name.equals(".")) continue;
            
            items.add(new WebDavItem(name, href, isDir, lastModified));
        }
        
        return items;
    }
    
    /**
     * 从 XML 中提取标签内容
     */
    private String extractTag(String xml, String... tags) {
        for (String tag : tags) {
            int start = xml.indexOf("<" + tag + ">");
            if (start >= 0) {
                start += tag.length() + 2;
                int end = xml.indexOf("</" + tag + ">", start);
                if (end > start) {
                    return xml.substring(start, end).trim();
                }
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