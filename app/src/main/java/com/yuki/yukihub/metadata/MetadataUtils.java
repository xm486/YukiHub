package com.yuki.yukihub.metadata;

import java.util.List;

final class MetadataUtils {

    /**
     * Bangumi 主站图片反代前缀。
     *
     * 主站图片域名 lain.bgm.tv 在国内基本连不上（实测直连超时），必须过一层反代。
     * 用法是把完整原始 URL 直接拼在前缀后面，不做 URL 编码：
     *   https://imagesp.yurari.moe/bangumi/https://lain.bgm.tv/pic/cover/l/...jpg
     */
    private static final String BGM_IMAGE_PROXY = "https://imagesp.yurari.moe/bangumi/";

    private MetadataUtils() { }

    /**
     * 把 Bangumi 主站图片地址换成走反代的地址。
     *
     * 【只处理 bgm.tv 主域及其子域】其它域名一律原样返回，包括：
     * - 镜像站图片（lain.bangumi.pro）：镜像自带图床，实测直连 200 可达，
     *   套反代只会多绕一跳，反而更慢。这是有意排除的，不要往匹配里加 bangumi.pro。
     * - 其它资料源（VNDB / 月幕 / Hikarinagi）：站点自身可达，同理不套。
     *
     * 已带前缀的地址直接返回，避免历史缓存里的地址被套两层。
     */
    static String proxyBangumiImage(String url) {
        if (url == null) return "";
        String u = url.trim();
        if (u.isEmpty()) return "";
        if (u.startsWith("//")) u = "https:" + u;
        if (u.startsWith(BGM_IMAGE_PROXY)) return u;
        // 只认 bgm.tv 主域及其子域，避免误伤形如 notbgm.tv 的地址
        String lower = u.toLowerCase(java.util.Locale.ROOT);
        boolean isBgm = lower.startsWith("http://") || lower.startsWith("https://");
        if (isBgm) {
            int hostStart = lower.indexOf("://") + 3;
            int hostEnd = hostStart;
            while (hostEnd < lower.length()) {
                char c = lower.charAt(hostEnd);
                if (c == '/' || c == '?' || c == '#') break;
                hostEnd++;
            }
            String host = lower.substring(hostStart, hostEnd);
            int colon = host.indexOf(':');
            if (colon >= 0) host = host.substring(0, colon);
            isBgm = host.equals("bgm.tv") || host.endsWith(".bgm.tv");
        }
        return isBgm ? BGM_IMAGE_PROXY + u : u;
    }

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
