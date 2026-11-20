package com.yuki.yukihub.bangumi;

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
import java.util.List;

/**
 * Bangumi 游玩状态同步的 API 客户端。
 * <p>
 * 只用官方公开 API（{@code https://api.bgm.tv}），不碰网页、不碰 cookie。
 * 与元数据刮削用的 {@code metadata.BangumiClient} 分开：
 * 那个负责搜条目拿封面简介，这个只负责读写「我的收藏状态」，
 * 职责不同、失败模式也不同，混在一起会互相牵连。
 *
 * <h3>鉴权</h3>
 * {@code Authorization: Bearer <token>}，token 在
 * https://next.bgm.tv/demo/access-token 生成。
 * <p>
 * <b>User-Agent 是硬性要求</b>：官方「关于 User Agent」明确说
 * 「各种请求库的默认 UA 可能会被禁用」，并禁止形如 {@code Bangumi/1.0} 的写法。
 * 必须是「开发者ID/应用名/版本号」并附项目主页，所以这里用一个规范值。
 *
 * <h3>状态映射（SubjectCollectionType）</h3>
 * 官方枚举是：
 * <pre>
 *   1 = 想看    2 = 看过    3 = 在看    4 = 搁置    5 = 抛弃
 * </pre>
 * 注意 <b>不是</b>按「在玩/玩过」的直觉顺序排的：2 是「看过」而 3 是「在看」，
 * 照 VNDB 那套（1=在玩 2=玩过）来写会把状态写错。
 *
 * <h3>写接口只需要一个</h3>
 * {@code POST /v0/users/-/collections/{subject_id}}，官方原文：
 * 「如果不存在则创建，如果存在则修改」。
 * <p>
 * 所以<b>不需要先读远端判断条目存不存在</b>，一次 POST 完成创建或更新，
 * 天然幂等。这也是本功能能做到「第二次同步零请求」的基础。
 *
 * <h3>为什么安全</h3>
 * body 只传 {@code type}。文档写明所有字段均可选、不传的字段不改动，
 * 所以用户在 Bangumi 上写的评分、吐槽、标签、私密设置一概不受影响。
 */
public final class BangumiSyncClient {
    private static final String TAG = "BangumiSync";

    public static final String API_BASE = "https://api.bgm.tv";

    /**
     * 官方要求的 UA 形态：开发者/应用/版本 (平台) (主页)。
     * 别改成 "Bangumi/1.0" 这种——官方明确禁止，可能被直接拒。
     */
    private static final String USER_AGENT =
            "YukiHub/1.0 (Android) (https://github.com/yukihub/yukihub)";

    // ======================== 状态枚举 ========================
    /** 想看（本地「未玩」映射到这个）。 */
    public static final int TYPE_WISH = 1;
    /** 看过。 */
    public static final int TYPE_DONE = 2;
    /** 在看。 */
    public static final int TYPE_DOING = 3;
    /** 搁置。 */
    public static final int TYPE_ON_HOLD = 4;
    /** 抛弃。 */
    public static final int TYPE_DROPPED = 5;

    /** Bangumi 的条目类型：游戏。搜索时必须限定，否则会命中动画/书籍。 */
    public static final int SUBJECT_TYPE_GAME = 4;

    /** Bangumi 没有公开的硬性限流值，按 300ms 保守节流。 */
    private static final long MIN_INTERVAL_MS = 300L;
    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 25000;

    private static volatile long lastRequestTime = 0L;

    private BangumiSyncClient() { }

    // ======================== 鉴权信息 ========================

    /** /v0/me 的返回。 */
    public static class AuthInfo {
        public final String userId;
        public final String username;
        public final String nickname;

        AuthInfo(String userId, String username, String nickname) {
            this.userId = userId;
            this.username = username;
            this.nickname = nickname;
        }

        /** 优先显示昵称，没有就显示用户名。 */
        public String displayName() {
            if (nickname != null && !nickname.trim().isEmpty()) return nickname.trim();
            return username == null ? "" : username;
        }
    }

    /**
     * 校验 token 并取回用户信息。
     * <p>
     * 这一步同时验证了 token 有效性——省得用户在同步几十条之后
     * 才发现 token 早就失效了。
     */
    public static AuthInfo authInfo(String token) throws Exception {
        if (token == null || token.trim().isEmpty()) {
            throw new NotAuthorizedException("尚未填写 Bangumi Access Token");
        }
        JSONObject o = request("GET", "/v0/me", null, token);
        String username = o.optString("username", "");
        String id = o.optString("id", "");
        if (username.isEmpty() && id.isEmpty()) {
            throw new NotAuthorizedException("Bangumi 未返回用户信息，token 可能已失效");
        }
        return new AuthInfo(id, username, o.optString("nickname", ""));
    }

    // ======================== 写：设置状态 ========================

    /**
     * 把某个条目设成指定收藏状态。
     * <p>
     * <b>这是本类唯一会写远端的方法。</b>
     * 官方语义是「不存在则创建，存在则修改」，所以不需要先判断是否存在。
     * <p>
     * 只传 {@code type}，其它字段（rate/comment/tags/private）一概不碰，
     * 用户自己在 Bangumi 上的设置不会被覆盖。
     *
     * @param subjectId Bangumi 条目 id，纯数字字符串
     * @param type      见 TYPE_* 常量
     */
    public static void setState(String token, String subjectId, int type) throws Exception {
        if (subjectId == null || subjectId.trim().isEmpty()) {
            throw new IllegalArgumentException("subjectId 为空");
        }
        if (type < TYPE_WISH || type > TYPE_DROPPED) {
            throw new IllegalArgumentException("非法状态值：" + type);
        }

        JSONObject body = new JSONObject();
        body.put("type", type);

        request("POST", "/v0/users/-/collections/" + subjectId.trim(), body, token);
        Log.i(TAG, "setState ok: " + subjectId + " -> type " + type);
    }

    // ======================== 读：按标题搜索 ========================

    /**
     * 按标题搜索游戏条目，用于「不是用 Bangumi 源刮削」的游戏。
     * <p>
     * 候选词按命中率排序（原名 > 罗马字 > 中文名），由调用方负责排序。
     * 命中必须是「归一化后完全相等」，或「查询词是标题的子串」——
     * Bangumi 的搜索同样模糊，取第一条会写到不相干的条目上。
     *
     * @param candidates 按优先级排列的候选查询词
     * @return subject_id（纯数字）；全部候选都无命中返回 null
     */
    public static String searchSubjectByTitle(String token, List<String> candidates) throws Exception {
        if (candidates == null || candidates.isEmpty()) return null;
        for (String q : candidates) {
            String trimmed = q == null ? "" : q.trim();
            if (trimmed.isEmpty()) continue;
            String hit = searchOnce(token, trimmed);
            if (hit != null && !hit.isEmpty()) {
                Log.i(TAG, "title search hit: 「" + trimmed + "」 -> " + hit);
                return hit;
            }
            // 未命中也要记：只记命中会让「为什么这个没匹配上」完全无从排查。
            // 之前时钟机关三部曲匹配不到，就是因为日志里只有 hit 没有 miss，
            // 连候选词有没有被搜过都看不出来。
            Log.w(TAG, "title search miss: 「" + trimmed + "」");
        }
        return null;
    }

    /** 单次搜索 + 精确/包含判定。 */
    private static String searchOnce(String token, String query) throws Exception {
        String nq = normalizeTitle(query);
        if (nq.isEmpty()) return null;

        // 变体 1：原样
        // 变体 2：把各种横线（全角－/半角-/破折号）换成空格
        //   原因：Bangumi 的搜索后端对全角连字符的处理不稳，
        //   而 VNDB 那边刮出来的日文原名常带全角「－」（U+FF0D），
        //   直接搜可能 0 候选。换成空格后分词更稳。
        java.util.LinkedHashSet<String> variants = new java.util.LinkedHashSet<>();
        variants.add(query);
        String spaced = replaceDashesWithSpace(query).trim();
        if (!spaced.isEmpty() && !spaced.equals(query)) variants.add(spaced);

        for (String q : variants) {
            JSONArray data = doSearch(token, q);
            if (data == null || data.length() == 0) continue;
            String got = pickFromCandidates(nq, data, q);
            if (got != null) return got;
        }
        return null;
    }

    /** 把各种横线换成空格，供搜索变体使用。 */
    private static String replaceDashesWithSpace(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            // 全角－(FF0D) 已被全角转换覆盖，这里把几种常见横线统一成空格
            if (ch == '-' || ch == '\uFF0D' || ch == '\u2014' || ch == '\u2013'
                    || ch == '\u2212' || ch == '\u30FC' || ch == '\uFF70') {
                sb.append(' ');
            } else {
                sb.append(ch);
            }
        }
        // 压掉连续空格
        return sb.toString().replaceAll("\\s+", " ");
    }

    /** 发一次搜索，返回 data 数组（可能为 null）。 */
    private static JSONArray doSearch(String token, String query) throws Exception {
        JSONObject body = new JSONObject();
        body.put("keyword", query);
        body.put("sort", "match");
        JSONObject filter = new JSONObject();
        // 只搜游戏（type=4），否则同名动画/书籍会混进来
        filter.put("type", new JSONArray().put(SUBJECT_TYPE_GAME));
        body.put("filter", filter);

        // limit 走 query string
        JSONObject resp = requestWithQuery("POST", "/v0/search/subjects", "?limit=10", body, token);
        JSONArray data = resp.optJSONArray("data");
        if (data == null) {
            Log.w(TAG, "search 「" + query + "」 无 data 字段");
            return null;
        }
        if (data.length() == 0) {
            Log.w(TAG, "search 「" + query + "」 返回 0 个候选");
            return null;
        }
        // 打出候选，否则「为什么没匹配上」完全无从判断
        StringBuilder cand = new StringBuilder();
        for (int i = 0; i < Math.min(data.length(), 5); i++) {
            JSONObject o = data.optJSONObject(i);
            if (o == null) continue;
            if (cand.length() > 0) cand.append(" | ");
            cand.append(o.optString("id", "?"))
                .append('=').append(o.optString("name", ""))
                .append('/').append(o.optString("name_cn", ""));
        }
        Log.i(TAG, "search 「" + query + "」 候选" + data.length() + "个: " + cand);
        return data;
    }

    /**
     * 从候选里挑出唯一可接受的那一个。
     * <p>
     * 规则（宁可漏掉，也绝不能写错）：
     * <ol>
     *   <li>完全相等 → 直接采纳；</li>
     *   <li>包含关系 → <b>必须唯一</b>。若有多条都包含，说明查询词太泛
     *       （比如只搜系列名「時計仕掛けのレイライン」会命中四部），
     *       此时无法确定是哪一部，一律放弃。</li>
     * </ol>
     * 之前是「取第一个包含的」，那会把状态写到同系列的别的作品上，
     * 比不同步严重得多。
     */
    private static String pickFromCandidates(String nq, JSONArray data, String query) {
        for (int i = 0; i < data.length(); i++) {
            JSONObject it = data.optJSONObject(i);
            if (it == null) continue;
            String id = it.optString("id", "");
            if (id.isEmpty()) continue;
            if (titleEquals(nq, it)) {
                Log.i(TAG, "候选「" + query + "」完全相等 -> " + id);
                return id;
            }
        }

        // 退一步：包含关系，但要求唯一
        String one = null;
        int hits = 0;
        for (int i = 0; i < data.length(); i++) {
            JSONObject it = data.optJSONObject(i);
            if (it == null) continue;
            String id = it.optString("id", "");
            if (id.isEmpty()) continue;
            if (titleContains(nq, it)) {
                hits++;
                if (one == null) one = id;
            }
        }
        if (hits == 1) {
            Log.i(TAG, "候选「" + query + "」包含命中(唯一) -> " + one);
            return one;
        }
        if (hits > 1) {
            Log.w(TAG, "候选「" + query + "」有 " + hits + " 条都包含，无法确定是哪一部，放弃");
        }
        return null;
    }

    /** 判断搜索结果的名称字段里，是否有与查询词归一化后完全相等的一个。 */
    private static boolean titleEquals(String normalizedQuery, JSONObject subject) {
        if (normalizedQuery.equals(normalizeTitle(subject.optString("name", "")))) return true;
        if (normalizedQuery.equals(normalizeTitle(subject.optString("name_cn", "")))) return true;
        return false;
    }

    /**
     * 判断查询词是否是某个名称字段的子串。
     * <p>
     * 只在完全相等失败时才用。约束：查询词归一化后至少 4 个字符，
     * 避免「爱」这种短词误命中一堆条目。
     */
    private static boolean titleContains(String normalizedQuery, JSONObject subject) {
        if (normalizedQuery.length() < 4) return false;
        if (containsNorm(normalizedQuery, subject.optString("name", ""))) return true;
        if (containsNorm(normalizedQuery, subject.optString("name_cn", ""))) return true;
        return false;
    }

    private static boolean containsNorm(String normalizedNeedle, String haystack) {
        String h = normalizeTitle(haystack);
        return !h.isEmpty() && h.contains(normalizedNeedle);
    }

    /**
     * 标题归一化：只抹排版差异，不做语义模糊。
     * 去空白、去标点与符号、全角转半角、统一小写。
     * 这样「天使☆嚣嚣」与「天使☆騒々」不会误判，
     * 但「千恋万花」与「千恋＊万花」能对上。
     */
    static String normalizeTitle(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            // 全角 → 半角
            if (ch >= 0xFF01 && ch <= 0xFF5E) {
                ch = (char) (ch - 0xFEE0);
            } else if (ch == '\u3000') {
                ch = ' ';
            }
            if (Character.isWhitespace(ch)) continue;
            if (Character.isLetterOrDigit(ch)) {
                sb.append(Character.toLowerCase(ch));
            }
        }
        return sb.toString();
    }

    // ======================== HTTP ========================

    private static JSONObject request(String method, String path, JSONObject body, String token)
            throws Exception {
        return requestWithQuery(method, path, "", body, token);
    }

    private static JSONObject requestWithQuery(String method, String path, String query,
                                               JSONObject body, String token) throws Exception {
        throttle();
        String url = API_BASE + path + (query == null ? "" : query);
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod(method);
            c.setConnectTimeout(CONNECT_TIMEOUT_MS);
            c.setReadTimeout(READ_TIMEOUT_MS);
            // Bearer 方案（与 VNDB 的 Token 方案不同）
            c.setRequestProperty("Authorization", "Bearer " + token);
            c.setRequestProperty("Accept", "application/json");
            // 官方硬性要求，缺了可能被直接拒
            c.setRequestProperty("User-Agent", USER_AGENT);

            if (body != null) {
                byte[] data = body.toString().getBytes(StandardCharsets.UTF_8);
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                c.setFixedLengthStreamingMode(data.length);
                try (OutputStream os = new BufferedOutputStream(c.getOutputStream())) {
                    os.write(data);
                    os.flush();
                }
            }

            int code = c.getResponseCode();
            String text = readAll(code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream());
            if (code == 401) throw new NotAuthorizedException("Bangumi token 无效或已过期，请重新生成");
            if (code == 403) throw new NotAuthorizedException("Bangumi token 缺少 write:collection 权限，请重新生成 token");
            // 400 = Validation Error：请求体/参数不合法（例如 subject_id 不是有效条目、type 越界）。
            // 这条单独给文案，否则用户只会看到一坨 JSON。
            if (code == 400) {
                throw new Exception("Bangumi 认为这次请求不合法（HTTP 400）：" + preview(text));
            }
            // 注意：按官方 v0 规格，POST/PATCH /v0/users/-/collections/{subject_id} 的 404
            // 含义是「用户不存在」，不是「条目不存在」——因为该端点语义是
            // 「不存在则创建」，条目找不到不会报 404。
            // 老代码把它当 NotFoundException（条目不存在）是误读，会误导用户以为作品没收录。
            if (code == 404) throw new NotFoundException("Bangumi 返回 404（通常是 token 对应用户不存在或已失效）：" + preview(text));
            if (code == 429) throw new RateLimitedException("Bangumi 请求过于频繁，请稍后再试");
            if (code < 200 || code >= 300) {
                throw new Exception("HTTP " + code + "：" + preview(text));
            }
            // 204 之类可能没有 body
            if (text == null || text.trim().isEmpty()) return new JSONObject();
            return new JSONObject(text);
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** 保守节流，避免触发 Bangumi 的频控。 */
    private static synchronized void throttle() throws InterruptedException {
        long now = System.currentTimeMillis();
        long wait = MIN_INTERVAL_MS - (now - lastRequestTime);
        if (wait > 0 && lastRequestTime > 0) {
            Thread.sleep(wait);
        }
        lastRequestTime = System.currentTimeMillis();
    }

    private static String readAll(InputStream is) throws Exception {
        if (is == null) return "";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) != -1) {
            bos.write(buf, 0, n);
        }
        is.close();
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String preview(String s) {
        if (s == null) return "";
        String t = s.replace('\n', ' ').trim();
        return t.length() <= 180 ? t : t.substring(0, 180) + "…";
    }

    // ======================== 异常 ========================

    /** token 缺失/失效/权限不足。整体性错误，应中止后续所有请求。 */
    public static class NotAuthorizedException extends Exception {
        public NotAuthorizedException(String message) { super(message); }
    }

    /** 触发了限流。整体性错误，继续跑只会全失败。 */
    public static class RateLimitedException extends Exception {
        public RateLimitedException(String message) { super(message); }
    }

    /** 条目不存在。单条错误，不影响其它条目。 */
    public static class NotFoundException extends Exception {
        public NotFoundException(String message) { super(message); }
    }
}
