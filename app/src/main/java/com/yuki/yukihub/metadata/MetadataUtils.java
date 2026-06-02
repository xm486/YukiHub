package com.yuki.yukihub.metadata;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.List;

final class MetadataUtils {

    private MetadataUtils() { }

    static String cleanTitle(String s) {
        if (s == null) return "";
        String x = s.replaceAll("[\\[\\]【】（）()].*", " ")
                .replaceAll("(?i)complete|汉化|中文版|日文版|体验版|trial|patch", " ")
                .replace('_', ' ')
                .trim();
        return x.isEmpty() ? s.trim() : x;
    }

    static String readAll(InputStream is) throws Exception {
        if (is == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            boolean first = true;
            while ((line = br.readLine()) != null) {
                if (!first) sb.append('\n');
                sb.append(line);
                first = false;
            }
        }
        return sb.toString();
    }

    static String firstNonEmpty(String a, String b) {
        return a != null && !a.isEmpty() && !"null".equals(a) ? a : (b == null || "null".equals(b) ? "" : b);
    }

    static String join(List<String> list, String sep) {
        StringBuilder sb = new StringBuilder();
        for (String s : list) {
            if (s == null || s.isEmpty()) continue;
            if (sb.length() > 0) sb.append(sep);
            sb.append(s);
        }
        return sb.toString();
    }

    static void closeQuietly(HttpURLConnection conn) {
        if (conn != null) {
            try { conn.disconnect(); } catch (Exception ignored) { }
        }
    }

    static void closeQuietly(InputStream is) {
        if (is != null) {
            try { is.close(); } catch (Exception ignored) { }
        }
    }

    static String readAndClose(InputStream is) throws Exception {
        return readAll(is);
    }

    static void sleepBeforeRetry(long delayMs) throws InterruptedException {
        try {
            Thread.sleep(Math.max(0L, delayMs));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        }
    }
}
