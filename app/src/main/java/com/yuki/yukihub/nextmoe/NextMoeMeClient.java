package com.yuki.yukihub.nextmoe;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * NextMoe /v2/me 用户面客户端：游玩状态与游玩时长。
 * <p>
 * 契约要点（developer.nextmoe.dev/docs/play-records）：
 * <ul>
 *   <li>这一面**只要用户访问令牌，不需要任何 scope**。既有的「未萌快捷登录」令牌
 *       已经够用，用户无需重新授权。</li>
 *   <li>游玩时长按<b>应用</b>各存一行：我们只覆盖自己那一行，
 *       不会抹掉用户在网页端或其他客户端上报的数据；读回来的是各应用的最大值。</li>
 *   <li>时长单位是累计分钟，范围 <b>0..60000</b>，超出会被拒，客户端先截断。</li>
 *   <li>批量写在 {@code POST /v2/me/playtimes} 与 {@code POST /v2/me/work-states}，
 *       一次最多 100 条，返回 <b>207 Multi-Status</b>：{@code items[]} 与请求逐位对应，
 *       每一项要么是资源，要么是一条 problem。**不能只看 HTTP 状态码判定成功**。</li>
 *   <li>{@code work_id} 是十进制 int64 字符串，全程用 String，不要转 long。</li>
 * </ul>
 */
public final class NextMoeMeClient {

    private static final String TAG = "NextMoeMe";
    private static final String API_BASE = "https://api.nextmoe.dev/v2";

    /** 用户令牌档 100/min；批量写本身不密集，这里给一个保守间隔兜底。 */
    private static final long MIN_INTERVAL_MS = 400L;
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 30_000;

    /** 服务端硬上限：一次批量写最多 100 条。 */
    public static final int MAX_BATCH = 100;
    /** 服务端硬上限：游玩时长 0..60000 分钟（1000 小时）。 */
    public static final int MAX_MINUTES = 60_000;

    /** 用户令牌缺失或已失效（需要重新登录/授权）。 */
    public static class NotAuthorizedException extends Exception {
        public NotAuthorizedException(String msg) { super(msg); }
    }

    /** 配额用尽，不应重试。 */
    public static class QuotaExceededException extends Exception {
        public QuotaExceededException(String msg) { super(msg); }
    }

    private static volatile long lastRequestTime = 0L;

    private NextMoeMeClient() { }

    // ======================== 批量写：游玩状态 ========================

    /**
     * 批量写入游玩状态。{@code state} 词表：
     * wish / doing / done / on_hold / dropped。
     * <p>
     * 刻意<b>不传 completion</b>：本地没有「通关到什么程度」这个信息，
     * 而 PUT 是整体替换语义，乱传会比不传更容易出错。
     *
     * @param pairs work_id → state，按顺序发送
     * @return 与入参逐位对应的结果
     */
    public static List<BatchItemResult> putWorkStates(List<StatePair> pairs) throws Exception {
        if (pairs == null || pairs.isEmpty()) return new ArrayList<>();
        if (pairs.size() > MAX_BATCH) {
            throw new IllegalArgumentException("批量上限 " + MAX_BATCH + " 条，实际 " + pairs.size());
        }
        JSONArray items = new JSONArray();
        for (StatePair p : pairs) {
            JSONObject o = new JSONObject();
            o.put("work_id", p.workId);
            o.put("state", p.state);
            items.put(o);
        }
        JSONObject body = new JSONObject();
        body.put("items", items);
        return doBatchPost("/me/work-states", body, pairs.size());
    }

    // ======================== 批量写：游玩时长 ========================

    /**
     * 批量写入游玩时长（本应用的累计分钟）。
     *
     * @param pairs work_id → minutes，按顺序发送
     */
    public static List<BatchItemResult> putPlaytimes(List<MinutePair> pairs) throws Exception {
        if (pairs == null || pairs.isEmpty()) return new ArrayList<>();
        if (pairs.size() > MAX_BATCH) {
            throw new IllegalArgumentException("批量上限 " + MAX_BATCH + " 条，实际 " + pairs.size());
        }
        JSONArray items = new JSONArray();
        for (MinutePair p : pairs) {
            JSONObject o = new JSONObject();
            o.put("work_id", p.workId);
            o.put("minutes", clampMinutes(p.minutes));
            items.put(o);
        }
        JSONObject body = new JSONObject();
        body.put("items", items);
        return doBatchPost("/me/playtimes", body, pairs.size());
    }

    // ======================== 批量反查 work_id ========================

    /**
     * 用外部锚批量反查作品 id，返回 ref → work_id 的映射。
     * <p>
     * <b>必须带 nsfw=true</b>：文档写明「被 nsfw 闸挡住的键原样回在 missing[] 里」。
     * Galgame 库大半是 R18，不带这个参数会把它们全部误判成「NextMoe 没有收录」。
     * <p>
     * 对应关系不靠 items 下标（missing 的会被摘掉，下标不可靠），
     * 而是读每个 work 的 {@code refs[]}，用 source+external_id 反查回请求 key。
     *
     * @param refs 形如 "vndb:v20624"、"bangumi:204432"、"steam:1230140"（≤100 条）
     * @return ref → work_id；未命中的不在返回里
     */
    public static Map<String, String> resolveRefs(List<String> refs) throws Exception {
        Map<String, String> out = new LinkedHashMap<>();
        if (refs == null || refs.isEmpty()) return out;
        if (refs.size() > MAX_BATCH) {
            throw new IllegalArgumentException("批量反查上限 " + MAX_BATCH + " 条，实际 " + refs.size());
        }
        StringBuilder sb = new StringBuilder();
        for (String r : refs) {
            if (r == null || r.trim().isEmpty()) continue;
            if (sb.length() > 0) sb.append(',');
            sb.append(r.trim());
        }
        if (sb.length() == 0) return out;

        // 匿名档即可读目录；带上令牌（若已登录）也只是换配额档位，不影响结果。
        //
        // ⚠️ include=refs 是必须的：默认响应**不含** refs 字段（已实测确认）。
        // 少了它，31 个命中的作品一个都对应不回请求 key，会全部掉进「无法匹配」。
        // 这也是最初 39 个游戏只匹配上 13 个的真正原因。
        String url = API_BASE + "/catalog/works?nsfw=true&include=refs&refs="
                + URLEncoder.encode(sb.toString(), "UTF-8");
        JSONObject resp = doGetJson(url);

        JSONArray items = resp.optJSONArray("items");
        if (items == null) return out;
        for (int i = 0; i < items.length(); i++) {
            JSONObject work = items.optJSONObject(i);
            if (work == null) continue;
            String workId = work.optString("id", "");
            if (workId.isEmpty()) continue;
            JSONArray wr = work.optJSONArray("refs");
            if (wr == null) continue;
            for (int j = 0; j < wr.length(); j++) {
                JSONObject ref = wr.optJSONObject(j);
                if (ref == null) continue;
                String source = ref.optString("source", "");
                String externalId = ref.optString("external_id", "");
                if (source.isEmpty() || externalId.isEmpty()) continue;
                String key = source + ":" + externalId;
                // 只回填我们真正请求过的 key，避免把关联条目（r50076 之类）也当成命中
                if (refs.contains(key) && !out.containsKey(key)) {
                    out.put(key, workId);
                }
            }
        }
        return out;
    }

    // ======================== 第 2 轮：按标题搜索 ========================

    /**
     * 按标题搜索单个作品，用于 refs 反查失败时的兜底。
     * <p>
     * <b>只接受「归一化后完全相等」的命中</b>，不做模糊匹配。
     * 原因：搜索是模糊的，「千恋万花」会同时返回「涙恋花」「片恋スターマイン」，
     * 若取第一条，就会把用户的游玩时长写到完全不相干的作品上——那比不同步更糟。
     * <p>
     * 比对范围覆盖 display_name 与 localized 里的所有语言值，
     * 这样「千恋万花」能命中 localized["zh-Hans"]="千恋＊万花"。
     *
     * @param title 本地游戏标题
     * @return work_id；无精确命中返回 null
     */
    public static String searchWorkByExactTitle(String title) throws Exception {
        String q = normalizeTitle(title);
        if (q.isEmpty()) return null;

        JSONObject root = doGetJson(API_BASE + "/catalog/search?object=work&nsfw=true&limit=10&q="
                + URLEncoder.encode(title.trim(), "UTF-8"));
        JSONArray items = root.optJSONArray("items");
        if (items == null) return null;

        for (int i = 0; i < items.length(); i++) {
            JSONObject it = items.optJSONObject(i);
            if (it == null) continue;
            // 容忍开放词表：只处理 work 命中
            String target = it.optString("target_object", "work");
            if (!"work".equals(target)) continue;

            if (!titleMatches(q, it)) continue;
            String id = it.optString("id", "");
            if (!id.isEmpty()) return id;
        }
        return null;
    }

    /** 判断搜索结果的所有名称字段里，是否有与查询词归一化后完全相等的一个。 */
    private static boolean titleMatches(String normalizedQuery, JSONObject work) {
        // display_name
        if (normalizedQuery.equals(normalizeTitle(work.optString("display_name", "")))) return true;
        // latin
        if (normalizedQuery.equals(normalizeTitle(work.optString("latin", "")))) return true;
        // localized 各语言
        JSONObject loc = work.optJSONObject("localized");
        if (loc != null) {
            java.util.Iterator<String> keys = loc.keys();
            while (keys.hasNext()) {
                JSONObject entry = loc.optJSONObject(keys.next());
                if (entry == null) continue;
                if (normalizedQuery.equals(normalizeTitle(entry.optString("value", "")))) return true;
            }
        }
        return false;
    }

    /**
     * 标题归一化：用于「完全相同」比较。
     * <p>
     * 只抹掉排版差异，不做语义模糊：
     * 去空白、去标点与符号、全角转半角、统一大小写。
     * 这样「千恋万花」与「千恋＊万花」、「サクラノ詩」与「サクラノ诗」都能对上，
     * 但「千恋万花」绝不会等于「泪恋花」。
     */
    static String normalizeTitle(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            // 全角 ASCII → 半角
            if (c >= '\uFF01' && c <= '\uFF5E') c = (char) (c - 0xFEE0);
            if (c == '\u3000') c = ' ';              // 全角空格
            if (Character.isWhitespace(c)) continue;
            if (Character.isLetterOrDigit(c)) {
                sb.append(Character.toLowerCase(c));
            }
            // 其余（标点、＊、－、括注符号等）一律丢弃
        }
        return sb.toString();
    }

    private static List<BatchItemResult> doBatchPost(String path, JSONObject body, int expected)
            throws Exception {
        String token = requireToken();
        String respText = doPost(path, body.toString(), token);
        List<BatchItemResult> results = new ArrayList<>();

        JSONObject resp;
        try {
            resp = new JSONObject(respText);
        } catch (Throwable t) {
            // 207 理论上一定是 JSON；解析不了说明拿到的是别的东西（例如防护页）。
            throw new Exception("响应不是预期的 JSON：" + preview(respText));
        }

        JSONArray items = resp.optJSONArray("items");
        if (items == null) {
            throw new Exception("响应缺少 items 数组：" + preview(respText));
        }
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            BatchItemResult r = new BatchItemResult();
            r.index = i;
            if (item == null) {
                r.success = false;
                r.errorCode = "EMPTY_ITEM";
                r.errorMessage = "服务端返回了空项";
            } else if (item.has("code") && item.has("title")) {
                // RFC 9457 problem 形状
                r.success = false;
                r.errorCode = item.optString("code", "");
                r.errorMessage = item.optString("detail", item.optString("title", ""));
            } else {
                r.success = true;
                r.workId = item.optString("work_id", "");
            }
            results.add(r);
        }
        if (results.size() != expected) {
            Log.w(TAG, "batch size mismatch: sent " + expected + " got " + results.size());
        }
        return results;
    }

    private static String doPost(String path, String jsonBody, String token) throws Exception {
        throttle();
        String url = API_BASE + path;
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Authorization", "Bearer " + token);
            byte[] data = jsonBody.getBytes(StandardCharsets.UTF_8);
            // 固定长度：既避免 chunked 引发的中转层误判，也让服务端能立刻拿到长度。
            conn.setFixedLengthStreamingMode(data.length);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(data);
                os.flush();
            }
            int code = conn.getResponseCode();
            String text = readAll(conn, code);
            if (code == 401) throw new NotAuthorizedException("登录状态已过期，请重新授权 NextMoe");
            if (code == 403) throw new NotAuthorizedException("授权不足：" + preview(text));
            if (code == 429) throw new QuotaExceededException("同步次数已达上限，请稍后再试");
            if (code != 200 && code != 207 && code != 201) {
                throw new Exception("HTTP " + code + "：" + preview(text));
            }
            return text;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static JSONObject doGetJson(String url) throws Exception {
        throttle();
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            // 已登录时才带令牌：目录只读面匿名可见，令牌只影响配额档
            String token = NextMoeAuth.ensureAccessToken();
            if (token != null && !token.isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + token);
            }
            int code = conn.getResponseCode();
            String text = readAll(conn, code);
            if (code == 429) throw new QuotaExceededException("查询次数已达上限，请稍后再试");
            if (code != 200) throw new Exception("HTTP " + code + "：" + preview(text));
            return new JSONObject(text);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String requireToken() throws NotAuthorizedException {
        String token = NextMoeAuth.ensureAccessToken();
        if (token == null || token.isEmpty()) {
            throw new NotAuthorizedException("尚未绑定 NextMoe 账号");
        }
        return token;
    }

    private static void throttle() {
        long now = System.currentTimeMillis();
        long wait = MIN_INTERVAL_MS - (now - lastRequestTime);
        if (wait > 0) {
            try { Thread.sleep(wait); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        lastRequestTime = System.currentTimeMillis();
    }

    private static String readAll(HttpURLConnection conn, int code) throws Exception {
        InputStream is = null;
        try {
            is = (code >= 200 && code < 400) ? conn.getInputStream() : conn.getErrorStream();
            if (is == null) return "";
            try (ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
                byte[] buf = new byte[4096];
                int len;
                while ((len = is.read(buf)) != -1) bos.write(buf, 0, len);
                return bos.toString("UTF-8");
            }
        } finally {
            if (is != null) try { is.close(); } catch (Throwable ignored) { }
        }
    }

    /** 错误正文只截一小段，避免把整页 HTML 防护页塞进日志或 Toast。 */
    private static String preview(String text) {
        if (text == null) return "";
        String t = text.trim();
        if (t.startsWith("<")) {
            // 中转/WAF 的 HTML 页：对用户没有意义，直接说明情况
            return "服务器返回了网页而不是接口数据（可能被安全防护拦截），请稍后重试";
        }
        return t.length() <= 160 ? t : t.substring(0, 160) + "…";
    }

    private static int clampMinutes(int minutes) {
        if (minutes < 0) return 0;
        return Math.min(minutes, MAX_MINUTES);
    }

    // ======================== 数据类 ========================

    public static class StatePair {
        public final String workId;
        public final String state;
        public StatePair(String workId, String state) {
            this.workId = workId;
            this.state = state;
        }
    }

    public static class MinutePair {
        public final String workId;
        public final int minutes;
        public MinutePair(String workId, int minutes) {
            this.workId = workId;
            this.minutes = minutes;
        }
    }

    /** 批量写里的一项结果；index 与请求顺序对应。 */
    public static class BatchItemResult {
        public int index;
        public boolean success;
        public String workId = "";
        public String errorCode = "";
        public String errorMessage = "";
    }
}
