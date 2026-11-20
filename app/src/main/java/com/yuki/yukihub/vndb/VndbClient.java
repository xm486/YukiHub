package com.yuki.yukihub.vndb;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * VNDB Kana API 客户端（只做「游玩状态同步」需要的部分）。
 * <p>
 * 只用官方公开 API（{@code https://api.vndb.org/kana}），<b>不碰网页、不碰 cookie</b>。
 * 时长（length vote）不走这里：Kana API 没有写入时长的端点，
 * 官方入口是站内 {@code /js/VNLengthVote.json}（只认 HttpOnly 登录 cookie），
 * 那是自动化脚本的路子，稳定性和合规性都不适合内置，故本功能只同步状态。
 *
 * <h3>鉴权</h3>
 * 用「Token」方案，不是 Bearer：
 * {@code Authorization: Token xxxx-xxxxx-xxxxx-xxxx-xxxxx-xxxxx-xxxx}。
 * token 由用户在 https://vndb.org/u/tokens 自行生成，需勾选 listwrite。
 *
 * <h3>写接口只需要一个</h3>
 * {@code PATCH /ulist/{vndbid}}，body 用 {@code labels_set} / {@code labels_unset}
 * <b>局部增删</b>：文档明确「missing members are not modified」，
 * 所以不会像 NextMoe 的 PUT 那样整体替换掉用户在网站上的其他设置。
 * 也<b>不需要全量回传</b>已有的 votes/labels。
 *
 * <h3>最容易踩的坑</h3>
 * VNDB 网页端会自动把 Playing/Finished/Stalled/Dropped 做成互斥，
 * 但<b>服务端不强制</b>（官方文档原话：this is not enforced server-side）。
 * 所以每次写状态必须自己把其它几个状态 label 一并 unset，
 * 否则会出现「Playing + Finished 同时存在」这种脏状态。
 */
public final class VndbClient {

    private static final String TAG = "Vndb";

    public static final String API_BASE = "https://api.vndb.org/kana";

    /**
     * VNDB 内置状态 label 的 id（实测 GET /ulist_labels?user=u1 得到）：
     * 1=Playing 2=Finished 3=Stalled 4=Dropped 5=Wishlist 6=Blacklist 7=Voted(虚拟，不可设)
     */
    public static final int LABEL_PLAYING = 1;
    public static final int LABEL_FINISHED = 2;
    public static final int LABEL_STALLED = 3;
    public static final int LABEL_DROPPED = 4;
    public static final int LABEL_WISHLIST = 5;
    public static final int LABEL_BLACKLIST = 6;
    public static final int LABEL_VOTED = 7;

    /**
     * 会被我们主动管理的「状态类」label：写入前要确保它们互斥。
     * 不含 Blacklist(6)：那是用户主动拉黑的语义，我们不该碰。
     * 不含 Voted(7)：虚拟 label，由 vote 字段自动维护，设了也没用。
     */
    private static final int[] STATE_LABELS = {
            LABEL_PLAYING, LABEL_FINISHED, LABEL_STALLED, LABEL_DROPPED, LABEL_WISHLIST
    };

    /** 限流：VNDB 允许 200 请求 / 5 分钟。留足余量，每次请求间隔 250ms。 */
    private static final long MIN_INTERVAL_MS = 250L;
    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 25000;
    private static volatile long lastRequestTime = 0L;

    private VndbClient() { }

    // ======================== 鉴权信息 ========================

    /** /authinfo 的返回。 */
    public static class AuthInfo {
        public final String userId;
        public final String username;
        public final List<String> permissions;

        AuthInfo(String userId, String username, List<String> permissions) {
            this.userId = userId;
            this.username = username;
            this.permissions = permissions;
        }

        /** 是否有写列表的权限。没有的话所有 PATCH 都会 403，应提前拦下。 */
        public boolean canWrite() {
            return permissions != null && permissions.contains("listwrite");
        }
    }

    /**
     * 校验 token 并取回用户信息。
     * <p>
     * 顺便做权限检查——没有 listwrite 的 token 写入必失败，
     * 与其等用户在同步时才看到 403，不如连上时就说清楚。
     */
    public static AuthInfo authInfo(String token) throws Exception {
        if (token == null || token.trim().isEmpty()) {
            throw new NotAuthorizedException("尚未填写 VNDB API token");
        }
        JSONObject o = request("GET", "/authinfo", null, token);
        List<String> perms = new ArrayList<>();
        JSONArray arr = o.optJSONArray("permissions");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                String p = arr.optString(i, "");
                if (!p.isEmpty()) perms.add(p);
            }
        }
        String id = o.optString("id", "");
        String name = o.optString("username", "");
        if (id.isEmpty()) throw new NotAuthorizedException("VNDB 未返回用户 id");
        return new AuthInfo(id, name, perms);
    }

    // ======================== 读：按标题搜索 ========================

/**
     * 按标题搜索 VN，用于「不是用 VNDB 源刮削」的游戏。
     * <p>
     * <b>必须优先用原名/罗马字搜，而不是本地标题。</b>
     * VNDB 是日语为核心的数据库，用中文译名搜命中率很低；
     * 而本地 metadata_cache 里通常已有 originalTitle / romanTitle
     * （来自任何源刮削都会记录），用它们搜才是对的。
     * <p>
     * 实测：「西园家的秘密」用中文名搜 0 候选，
     * 用原名「西園家のかくしゴト」搜能命中 v66872。
     *
     * @param token       API token
     * @param candidates  按优先级排列的候选查询词（原名 > 罗马字 > 本地标题），调用方负责排序
     * @return VN id（vNNN）；全部候选都无命中返回 null
     */
    public static String searchVnByTitle(String token, List<String> candidates) throws Exception {
        if (candidates == null || candidates.isEmpty()) return null;
        for (String q : candidates) {
            String trimmed = q == null ? "" : q.trim();
            if (trimmed.isEmpty()) continue;
            String hit = searchOnce(token, trimmed);
            if (hit != null && !hit.isEmpty()) {
                Log.i(TAG, "title search hit: 「" + trimmed + "」 -> " + hit);
                return hit;
            }
        }
        return null;
    }

    /** 单次搜索 + 精确/包含判定。 */
    private static String searchOnce(String token, String query) throws Exception {
        String nq = normalizeTitle(query);
        if (nq.isEmpty()) return null;

        JSONObject body = new JSONObject();
        JSONArray filters = new JSONArray();
        filters.put("search");
        filters.put("=");
        filters.put(query);
        body.put("filters", filters);
        body.put("fields", "id,title,alttitle,titles{lang,title,latin}");
        body.put("results", 10);

        JSONObject resp = request("POST", "/vn", body, token);
        JSONArray results = resp.optJSONArray("results");
        if (results == null) return null;

        // 先找「完全相等」，再退一步找「包含」。
        // 分两轮是为了优先选中真正对得上的那一个：
        // 搜「西園家のかくしゴト」时，VNDB 的标题是
        // 「西園家のかくしゴト - The Secret Revealed by the Lens -」，
        // 永远不可能完全相等，但包含关系是明确的。
        String fuzzy = null;
        for (int i = 0; i < results.length(); i++) {
            JSONObject it = results.optJSONObject(i);
            if (it == null) continue;
            String id = it.optString("id", "");
            if (id.isEmpty()) continue;
            if (titleEquals(nq, it)) return id;
            if (fuzzy == null && titleContains(nq, it)) fuzzy = id;
        }
        return fuzzy;
    }

    /** 判断搜索结果的所有名称字段里，是否有与查询词归一化后完全相等的一个。 */
    private static boolean titleEquals(String normalizedQuery, JSONObject vn) {
        if (normalizedQuery.equals(normalizeTitle(vn.optString("title", "")))) return true;
        if (normalizedQuery.equals(normalizeTitle(vn.optString("alttitle", "")))) return true;
        JSONArray titles = vn.optJSONArray("titles");
        if (titles != null) {
            for (int i = 0; i < titles.length(); i++) {
                JSONObject t = titles.optJSONObject(i);
                if (t == null) continue;
                if (normalizedQuery.equals(normalizeTitle(t.optString("title", "")))) return true;
                if (normalizedQuery.equals(normalizeTitle(t.optString("latin", "")))) return true;
            }
        }
        return false;
    }

    /**
     * 判断查询词是否是某个名称字段的「子串」。
     * <p>
     * 只在完全相等失败时才用。VNDB 的标题经常带副标题
     * （如 {@code 西園家のかくしゴト - The Secret Revealed by the Lens -}），
     * 而本地存的往往只是主标题，所以必须允许这种包含关系。
     * <p>
     * 约束：查询词归一化后至少 4 个字符，避免「爱」这种短词误匹配一堆作品。
     */
    private static boolean titleContains(String normalizedQuery, JSONObject vn) {
        if (normalizedQuery.length() < 4) return false;
        if (containsNorm(normalizedQuery, vn.optString("title", ""))) return true;
        if (containsNorm(normalizedQuery, vn.optString("alttitle", ""))) return true;
        JSONArray titles = vn.optJSONArray("titles");
        if (titles != null) {
            for (int i = 0; i < titles.length(); i++) {
                JSONObject t = titles.optJSONObject(i);
                if (t == null) continue;
                if (containsNorm(normalizedQuery, t.optString("title", ""))) return true;
                if (containsNorm(normalizedQuery, t.optString("latin", ""))) return true;
            }
        }
        return false;
    }

    private static boolean containsNorm(String normalizedNeedle, String haystack) {
        String h = normalizeTitle(haystack);
        return !h.isEmpty() && h.contains(normalizedNeedle);
    }

    /**
     * 标题归一化：只抹排版差异，不做语义模糊。
     * 去空白、去标点与符号、全角转半角、统一小写。
     * 这样「千恋万花」与「千恋＊万花」能对上，但「千恋万花」绝不会等于「泪恋花」。
     */
    static String normalizeTitle(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '\uFF01' && c <= '\uFF5E') c = (char) (c - 0xFEE0);  // 全角→半角
            if (c == '\u3000') c = ' ';
            if (Character.isWhitespace(c)) continue;
            if (Character.isLetterOrDigit(c)) sb.append(Character.toLowerCase(c));
            // 其余（标点、＊、－、括号等）一律丢弃
        }
        return sb.toString();
    }

    // ======================== 写：单个 VN 的状态 ========================

    /**
     * 把某个 VN 设成指定状态 label，并清掉其它互斥状态 label。
     * <p>
     * <b>这是本类唯一会写远端的方法，请只通过它写状态。</b>
     * 内部已处理「服务端不强制互斥」这个坑：
     * 传入 targetLabel 时，其余 4 个状态 label 全部放进 labels_unset。
     * <p>
     * 只动 labels，<b>不传 vote / notes / started / finished</b>，
     * 因为文档写明「missing members are not modified」——
     * 用户自己在 VNDB 上写的评分、笔记、起止日期一概不受影响。
     *
     * @param vnId        形如 v123
     * @param targetLabel 目标状态 label id（见本类常量）
     */
    public static void setState(String token, String vnId, int targetLabel) throws Exception {
        if (vnId == null || vnId.trim().isEmpty()) throw new IllegalArgumentException("vnId 为空");

        JSONArray unset = new JSONArray();
        for (int label : STATE_LABELS) {
            if (label != targetLabel) unset.put(label);
        }
        JSONArray set = new JSONArray();
        set.put(targetLabel);

        JSONObject body = new JSONObject();
        body.put("labels_unset", unset);
        body.put("labels_set", set);

        request("PATCH", "/ulist/" + vnId, body, token);
        Log.i(TAG, "setState ok: " + vnId + " -> label " + targetLabel);
    }

    // ======================== HTTP ========================

    private static JSONObject request(String method, String path, JSONObject body, String token)
            throws Exception {
        throttle();
        String url = API_BASE + path;
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod(method);
            c.setConnectTimeout(CONNECT_TIMEOUT_MS);
            c.setReadTimeout(READ_TIMEOUT_MS);
            // Token 方案，不是 Bearer
            c.setRequestProperty("Authorization", "Token " + token);
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("User-Agent", "YukiHub/1.0 (Android)");

            if (body != null) {
                byte[] data = body.toString().getBytes(StandardCharsets.UTF_8);
                c.setDoOutput(true);
                // Content-Type 必须在 getOutputStream() 之前设好（与 BangumiSyncClient 同一处坑）。
                // 显式补 Content-Length：setFixedLengthStreamingMode 在部分实现下不会
                // 反映到请求头，缺了会被某些中转层当成 chunked 处理。
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                c.setRequestProperty("Content-Length", String.valueOf(data.length));
                // 固定长度，避免 chunked 被中转层误判
                c.setFixedLengthStreamingMode(data.length);
                try (OutputStream os = new BufferedOutputStream(c.getOutputStream())) {
                    os.write(data);
                    os.flush();
                }
            }

            int code = c.getResponseCode();
            String text = readAll(code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream());

            if (code == 401) throw new NotAuthorizedException("VNDB token 无效或已撤销，请重新生成");
            if (code == 403) throw new NotAuthorizedException("VNDB token 权限不足：需要 listwrite 权限");
            if (code == 429) throw new RateLimitedException("VNDB 请求过于频繁，请稍后再试");
            if (code < 200 || code >= 300) {
                throw new Exception("HTTP " + code + "：" + preview(text));
            }
            if (text == null || text.trim().isEmpty()) return new JSONObject();
            return new JSONObject(text);
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static void throttle() {
        long now = System.currentTimeMillis();
        long wait = MIN_INTERVAL_MS - (now - lastRequestTime);
        if (wait > 0) {
            try { Thread.sleep(wait); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        lastRequestTime = System.currentTimeMillis();
    }

    private static String readAll(InputStream is) throws Exception {
        if (is == null) return "";
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
            return bos.toString("UTF-8");
        } finally {
            try { is.close(); } catch (Throwable ignored) { }
        }
    }

    /** 错误正文截断，避免整页 HTML 塞进日志/Toast。 */
    private static String preview(String text) {
        if (text == null) return "";
        String t = text.trim();
        if (t.startsWith("<")) return "服务器返回了网页而不是接口数据";
        return t.length() <= 200 ? t : t.substring(0, 200) + "…";
    }

    // ======================== 异常 ========================

    /** token 无效或权限不足。 */
    public static class NotAuthorizedException extends Exception {
        public NotAuthorizedException(String m) { super(m); }
    }

    /** 触发限流。 */
    public static class RateLimitedException extends Exception {
        public RateLimitedException(String m) { super(m); }
    }
}
