package com.yuki.yukihub.metadata;

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

    static void sleepBeforeRetry(long delayMs) throws InterruptedException {
        try {
            Thread.sleep(Math.max(0L, delayMs));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        }
    }
}
