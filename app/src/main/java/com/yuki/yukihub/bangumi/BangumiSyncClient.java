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
        }
        return null;
    }

    /** 单次搜索 + 精确/包含判定。 */
    private static String searchOnce(String token, String query) throws Exception {
        String nq = normalizeTitle(query);
        if (nq.isEmpty()) return null;

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
        if (data == null) return null;

        // 先找完全相等，再退一步找包含：
        // Bangumi 的 name 常带副标题或译名混排，
        // 本地往往只有主标题，永远不可能完全相等，但包含关系是明确的。
        String fuzzy = null;
        for (int i = 0; i < data.length(); i++) {
            JSONObject it = data.optJSONObject(i);
            if (it == null) continue;
            String id = it.optString("id", "");
            if (id.isEmpty()) continue;
            if (titleEquals(nq, it)) return id;
            if (fuzzy == null && titleContains(nq, it)) fuzzy = id;
        }
        return fuzzy;
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
            if (code == 403) throw new NotAuthorizedException("Bangumi 拒绝访问，token 权限不足");
            if (code == 404) throw new NotFoundException("Bangumi 找不到条目：" + preview(text));
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
