package com.yuki.yukihub.hikarinagi;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Hikarinagi 游玩状态同步的 API 客户端。
 * <p>
 * 与元数据刮削用的 {@code metadata.HikarinagiClient} 刻意分开：那个走
 * {@code client_credentials} 只读公开资料，这个走用户级 OAuth 令牌写「我的状态」。
 * 职责与失败模式都不同，混在一起会互相牵连，也违背各同步源互相隔离的约定。
 *
 * <h3>两条截然不同的认证路径</h3>
 * <ol>
 *   <li><b>写状态</b>：用户级 OAuth 令牌（授权码 + PKCE S256），scope {@code status:write}。
 *       令牌只代表「这个用户」，不能凭空生成。</li>
 *   <li><b>搜条目</b>：{@code GET /v3/search} 文档标注 {@code auth: app}，
 *       scope 为 {@code catalog:read}，即<b>应用级</b>令牌就够。
 *       所以搜索用 {@code client_credentials}，不依赖用户授权到 catalog scope，
 *       这样即使用户只勾了 status 权限也能正常匹配。</li>
 * </ol>
 *
 * <h3>写接口：批量 upsert</h3>
 * {@code POST /v3/user/me/rates/galgames/batch}，官方原文：
 * 「每次 1 至 100 条，同一作品不能重复出现。各条按请求顺序逐条写入，
 * 规则与单条写入相同，某条失败不影响其余各条，结果按请求顺序返回。」
 * <p>
 * 单条 {@code PUT /v3/user/me/rates/galgames/{id}} 虽存在，但逐条发会产生
 * N 次往返；批量端点一次最多 100 条，明显更省。两者写入规则一致。
 *
 * <h3>为什么只传三个字段是安全的</h3>
 * 站点作者明确说明该接口是<b>按字段更新</b>而非整体替换：
 * 「只传 {@code {"status":"going"}} 就可以单独更新标记，
 * 只传 {@code {"time_to_finish_minutes":600}} 就可以只更新时间」。
 * 因此本类<b>只传</b> {@code galgame_id} / {@code status} / {@code time_to_finish_minutes}，
 * 用户的评分、短评、剧透标记、私密设置一概不传，也就一概不会被改动。
 * <p>
 * 这点很关键：如果它是替换语义，就得先 GET 现状再读-改-写，
 * 那样每条都要多一次往返，「零请求」的优势就没了。
 *
 * <h3>状态枚举</h3>
 * <pre>
 *   GOING 在玩 / COMPLETED 通关 / ON_HOLD 搁置 / DROPPED 弃坑 / PLAN 想玩
 * </pre>
 * 这次是<b>语义字符串</b>，不是 Bangumi 那种 1~5 的数字，
 * 千万别照抄 Bangumi 的映射（那边的 2 是「看过」、3 才是「在看」，顺序反直觉）。
 */
public final class HikarinagiSyncClient {

    private static final String TAG = "HikarinagiSync";

    /** 开放 API 基址。 */
    public static final String API_BASE = "https://api.hikarinagi.org/v3";
    /** OIDC 授权端点（系统浏览器打开）。 */
    public static final String AUTHORIZE_URL = "https://id.hikarinagi.org/oidc/auth";
    /** OIDC 令牌端点。 */
    public static final String TOKEN_URL = "https://id.hikarinagi.org/oidc/token";
    /**
     * Android 客户端的 client_id（公共客户端，无 client_secret，靠 PKCE 保证安全）。
     * 与「Hikarinagi 快捷登录」用的是同一个 client —— 只是复用这个应用身份，
     * 授权通道本身是独立的（环回端口，不经过快捷登录的回调 Activity）。
     * 控制台里该 client 已登记两条回调：
     * <ul>
     *   <li>{@code yukihub://hikarinagi/callback} —— 快捷登录用；</li>
     *   <li>{@code http://127.0.0.1:14791/callback} —— 本同步功能用（环回）。</li>
     * </ul>
     */
    public static final String CLIENT_ID = "hkn_qtmXMJfBoxcNLA-a";
    /**
     * 回调地址：<b>环回</b>（RFC 8252 §7 loopback redirect），与 NextMoe 同一套路。
     * <p>
     * 这是「独立授权」的关键——不走 {@code yukihub://} 自定义 scheme，
     * 因此完全不经过 {@code HikarinagiOAuthCallbackActivity}，
     * 与「Hikarinagi 快捷登录」的登录态零交集。
     * <p>
     * 端口写死 14791 而不是随机端口：NextMoe 那边靠「端口无关匹配」才能用随机端口，
     * 而 Hikarinagi 文档明确要求 redirect_uri
     * 「必须与控制台登记的某一条完全一致，包括协议、端口、路径与结尾斜杠，
     * 不做前缀匹配」，所以端口必须固定，且必须在控制台里登记这条地址。
     * <p>
     * 该地址是各设备自己的回环：PC 上的 127.0.0.1 与手机上的 127.0.0.1
     * 属于不同网络栈，互不相通，所以 PC 版用同一个地址不受影响。
     */
    public static final String REDIRECT_URI = "http://127.0.0.1:14791/callback";
    /** 环回监听端口，必须与 {@link #REDIRECT_URI} 里的端口一致。 */
    public static final int LOOPBACK_PORT = 14791;

    /**
     * 同步功能申请的权限。
     * <ul>
     *   <li>{@code openid} —— OIDC 基础；</li>
     *   <li>{@code user:read} —— 读 {@code /v3/user/me} 用于校验令牌并显示账号名；</li>
     *   <li>{@code status:read} —— 读已有记录（本功能暂不读，但便于以后做双向）；</li>
     *   <li>{@code status:write} —— 写游玩状态与时长，<b>缺了它就完全没法同步</b>；</li>
     *   <li>{@code offline_access} —— 签发 refresh token，否则 1 小时后就要重新授权。</li>
     * </ul>
     * 申请 {@code offline_access} 时授权请求<b>必须</b>带 {@code prompt=consent}，
     * 文档明确写了这一点，漏了拿不到 refresh token。
     */
    public static final String SCOPES =
            "openid user:read status:read status:write offline_access";

    /** 应用级令牌只用于搜索，只要这一个 scope。 */
    private static final String APP_SCOPE = "catalog:read";
    /** 应用级凭据（公共只读用途，与 metadata.HikarinagiClient 各自持有一份，互不依赖）。 */
    private static final String APP_CLIENT_ID = "hkn_4poXX7v37j_iM2-o";
    private static final String APP_CLIENT_SECRET = "hks_Wv6tW5O6ev8Mbifvg1tPJ7UexehLATQcKpZJiFhV48Y";

    /** 批量写入的硬上限，文档规定 1~100。 */
    public static final int MAX_BATCH = 100;
    /** 时长上限（分钟）。本地实测最大约 1194 分钟，这里留足余量防脏数据。 */
    public static final int MAX_MINUTES = 60_000;

    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 25000;
    /** Hikarinagi 限速 60 次/分钟/应用，按 1100ms 保守节流。 */
    private static final long MIN_INTERVAL_MS = 1100L;

    private static volatile long lastRequestTime = 0L;

    /** 应用级令牌（仅搜索用），进程内缓存，不持久化。 */
    private static volatile String appToken = "";
    private static volatile long appTokenExpiresAt = 0L;

    private HikarinagiSyncClient() { }

    // ======================== 状态枚举 ========================

    public static final String STATUS_GOING = "GOING";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_ON_HOLD = "ON_HOLD";
    public static final String STATUS_DROPPED = "DROPPED";
    public static final String STATUS_PLAN = "PLAN";

    // ======================== PKCE ========================

    public static String randomUrlSafe(int bytes) {
        byte[] data = new byte[bytes];
        new SecureRandom().nextBytes(data);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }

    public static String pkceS256(String verifier) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] digest = md.digest(verifier.getBytes(StandardCharsets.US_ASCII));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    }

    /**
     * 拼授权 URL。
     * <p>
     * 注意 {@code prompt=consent}：申请 {@code offline_access} 时必须带，
     * 否则服务端不会下发 refresh token（文档「用户级令牌」页明确说明）。
     */
    public static String buildAuthorizeUrl(String verifier, String state, String nonce) throws Exception {
        return AUTHORIZE_URL
                + "?response_type=code"
                + "&client_id=" + url(CLIENT_ID)
                + "&redirect_uri=" + url(REDIRECT_URI)
                + "&scope=" + url(SCOPES)
                + "&state=" + url(state)
                + "&nonce=" + url(nonce)
                + "&code_challenge=" + url(pkceS256(verifier))
                + "&code_challenge_method=S256"
                + "&prompt=consent";
    }

    // ======================== 令牌 ========================

    /** 令牌响应。 */
    public static class Tokens {
        public final String accessToken;
        public final String refreshToken;
        /** 绝对过期时间（毫秒时间戳）。 */
        public final long expiresAt;
        public Tokens(String accessToken, String refreshToken, long expiresAt) {
            this.accessToken = accessToken == null ? "" : accessToken;
            this.refreshToken = refreshToken == null ? "" : refreshToken;
            this.expiresAt = expiresAt;
        }
    }

    /**
     * 用授权码换令牌（公共客户端：只传 client_id + code_verifier，没有 client_secret）。
     *
     * @param fallbackRefresh 令牌响应没给 refresh_token 时沿用的旧值（一般传空）
     */
    public static Tokens exchangeCode(String code, String verifier, String fallbackRefresh)
            throws Exception {
        String form = "grant_type=authorization_code"
                + "&code=" + url(code)
                + "&redirect_uri=" + url(REDIRECT_URI)
                + "&client_id=" + url(CLIENT_ID)
                + "&code_verifier=" + url(verifier);
        return tokenPost(form, fallbackRefresh);
    }

    /**
     * 刷新令牌。
     * <p>
     * 文档说每次刷新会轮换 refresh token、旧的立即失效，
     * 所以调用方拿到新值必须<b>原地覆盖</b>，不能保留旧的。
     */
    public static Tokens refresh(String refreshToken) throws Exception {
        String form = "grant_type=refresh_token"
                + "&refresh_token=" + url(refreshToken)
                + "&client_id=" + url(CLIENT_ID);
        return tokenPost(form, "");
    }

    private static Tokens tokenPost(String form, String fallbackRefresh) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(TOKEN_URL).openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(CONNECT_TIMEOUT_MS);
        c.setReadTimeout(READ_TIMEOUT_MS);
        c.setDoOutput(true);
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        c.setRequestProperty("User-Agent", USER_AGENT);
        byte[] data = form.getBytes(StandardCharsets.UTF_8);
        c.setFixedLengthStreamingMode(data.length);
        try (OutputStream os = new BufferedOutputStream(c.getOutputStream())) {
            os.write(data);
            os.flush();
        }
        int http = c.getResponseCode();
        String text = readAll(http >= 200 && http < 300 ? c.getInputStream() : c.getErrorStream());
        try { c.disconnect(); } catch (Throwable ignored) { }

        if (http >= 500) throw new OAuthTransientException("Hikarinagi 授权服务暂时不可用（HTTP " + http + "）");
        if (http >= 400) throw parseOAuthError(text, http);

        JSONObject o = new JSONObject(text == null || text.trim().isEmpty() ? "{}" : text);
        String access = o.optString("access_token", "");
        if (access == null || access.trim().isEmpty()) {
            throw new OAuthException("Hikarinagi 令牌响应缺少 access_token");
        }
        String refresh = o.optString("refresh_token", "");
        if (refresh == null || refresh.trim().isEmpty()) refresh = fallbackRefresh;
        long expiresIn = o.optLong("expires_in", 3600L);
        return new Tokens(access, refresh, System.currentTimeMillis() + expiresIn * 1000L);
    }

    /** 4xx 错误体是标准 RFC 6749 §5.2 形状 {error, error_description}。 */
    private static OAuthException parseOAuthError(String body, int http) {
        try {
            JSONObject o = new JSONObject(body == null ? "" : body);
            String err = o.optString("error", "");
            if (!err.isEmpty()) {
                String desc = o.optString("error_description", err);
                if ("server_error".equals(err) || "temporarily_unavailable".equals(err)) {
                    return new OAuthTransientException(desc + " (" + err + ")");
                }
                return new OAuthException(desc + " (" + err + ")", err);
            }
        } catch (Throwable ignored) { }
        return new OAuthException("HTTP " + http + (body == null || body.isEmpty() ? "" : "：" + preview(body)));
    }

    // ======================== 用户信息 ========================

    /** 用户信息（用于校验令牌并显示账号名）。 */
    public static class AuthInfo {
        /** 用户名（文档字段 {@code name}）。 */
        public final String userName;
        /** 昵称（文档字段 {@code nickname}，可为空）。 */
        public final String nickname;
        AuthInfo(String userName, String nickname) {
            this.userName = userName == null ? "" : userName;
            this.nickname = nickname == null ? "" : nickname;
        }
        /** 优先显示昵称，没有就用用户名。 */
        public String displayName() {
            if (!nickname.trim().isEmpty()) return nickname.trim();
            if (!userName.trim().isEmpty()) return userName.trim();
            return "Hikarinagi";
        }
    }

    /**
     * 校验用户令牌并取回账号信息。
     * <p>
     * 这一步会真正用只读接口验证令牌有效性，
     * 避免用户同步几十条之后才发现授权早已失效。
     *
     * <h3>字段名以文档为准（曾经写错，导致「授权成功但报授权失效」）</h3>
     * {@code GET /v3/user/me} 的真实响应里<b>没有 {@code id} / {@code uid} 字段</b>，
     * 只有 {@code name}（用户名）、{@code nickname}（昵称，可为 null）、
     * {@code role}、{@code signature}、{@code status}、{@code avatar} 等。
     * <p>
     * 早期实现拿 {@code id} 和 {@code uid} 当作「有没有用户信息」的判据，
     * 结果两个都取不到就误判成授权失效——而实际上令牌是好的，
     * 于是出现了「浏览器显示授权完成，回到 App 却弹授权有问题」。
     * 现在只认 {@code name} / {@code nickname}。
     */
    public static AuthInfo authInfo(String accessToken) throws Exception {
        if (accessToken == null || accessToken.trim().isEmpty()) {
            throw new NotAuthorizedException("尚未授权 Hikarinagi");
        }
        JSONObject resp = request("GET", "/user/me", null, accessToken);
        // 必须解统一信封：真实响应是 {"success":true,"data":{"id":..,"name":..,"nickname":..}}
        JSONObject o = unwrap(resp);

        // 文档里的用户名是 name，昵称是 nickname（nullable）。
        String userName = o.optString("name", "");
        if (userName == null) userName = "";
        String nickname = o.optString("nickname", "");
        if (nickname == null || "null".equalsIgnoreCase(nickname.trim())) nickname = "";

        // 两个都空才认为响应异常
        if (userName.trim().isEmpty() && nickname.trim().isEmpty()) {
            Log.w(TAG, "user/me 响应缺少 name/nickname：" + preview(resp.toString()));
            throw new NotAuthorizedException("Hikarinagi 未返回用户信息，授权可能已失效");
        }
        return new AuthInfo(userName.trim(), nickname.trim());
    }

    // ======================== 写：批量状态 + 时长 ========================

    /** 一条待写入的记录。<b>只允许</b>这三个字段，别加别的（会覆盖用户自己的评分）。 */
    public static class RateItem {
        public final long galgameId;
        public final String status;
        /** 通关耗时（分钟）；小于 0 表示不写这个字段。 */
        public final int minutes;

        public RateItem(long galgameId, String status, int minutes) {
            this.galgameId = galgameId;
            this.status = status;
            this.minutes = minutes;
        }

        JSONObject toJson() throws Exception {
            JSONObject o = new JSONObject();
            o.put("galgame_id", galgameId);
            if (status != null && !status.trim().isEmpty()) o.put("status", status.trim());
            if (minutes >= 0) o.put("time_to_finish_minutes", minutes);
            return o;
        }
    }

    /** 单条写入结果，与请求顺序一一对应。 */
    public static class BatchItemResult {
        public final boolean ok;
        public final String message;
        public BatchItemResult(boolean ok, String message) {
            this.ok = ok;
            this.message = message == null ? "" : message;
        }
    }

    /**
     * 批量写入状态与时长（一次最多 {@link #MAX_BATCH} 条）。
     * <p>
     * 返回结果与入参<b>逐位对应</b>：服务端承诺「结果按请求顺序返回，
     * 某条失败不影响其余各条」，所以单条失败不会牵连整批。
     *
     * @throws NotAuthorizedException 令牌无效或权限不足（整体性错误，应中止）
     * @throws RateLimitedException   触发限流（整体性错误，应中止）
     */
    public static List<BatchItemResult> setRatesBatch(String accessToken, List<RateItem> items)
            throws Exception {
        if (items == null || items.isEmpty()) return new ArrayList<>();
        if (items.size() > MAX_BATCH) {
            throw new IllegalArgumentException("批量上限 " + MAX_BATCH + " 条，实际 " + items.size());
        }
        JSONArray arr = new JSONArray();
        for (RateItem it : items) arr.put(it.toJson());
        JSONObject body = new JSONObject();
        body.put("items", arr);

        JSONObject resp = request("POST", "/user/me/rates/galgames/batch", body, accessToken);
        // 解统一信封：{"success":true,"data":[{ok:..},..]}
        // data 在这里是<b>数组</b>，不能用 unwrap()（那个只取对象）
        JSONArray data = resp.optJSONArray("data");
        if (data == null) {
            // 兼容：个别实现可能把数组放在 data 对象里的 items
            JSONObject inner = resp.optJSONObject("data");
            if (inner != null) data = inner.optJSONArray("items");
        }
        if (data == null) {
            // 找不到就按「全部成功」处理会掩盖失败，故明确报错
            throw new Exception("Hikarinagi 批量写入响应缺少 data 数组：" + preview(resp.toString()));
        }

        List<BatchItemResult> out = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            JSONObject one = data.optJSONObject(i);
            if (one == null) {
                out.add(new BatchItemResult(false, "服务端未返回该条结果"));
                continue;
            }
            if (one.optBoolean("ok", false)) {
                out.add(new BatchItemResult(true, ""));
            } else {
                JSONObject err = one.optJSONObject("error");
                String msg = err == null ? "写入失败" : err.optString("message", "写入失败");
                String code = err == null ? "" : err.optString("code", "");
                out.add(new BatchItemResult(false,
                        code.isEmpty() ? msg : msg + " (" + code + ")"));
            }
        }
        int ok = 0;
        for (BatchItemResult r : out) if (r.ok) ok++;
        Log.i(TAG, "batch write: " + ok + "/" + out.size() + " ok");
        return out;
    }

    // ======================== 读：按标题搜索条目 ========================

    /**
     * 按标题搜索 Galgame，返回命中的 galgame id；全部候选都没命中返回 null。
     * <p>
     * 走应用级令牌（{@code catalog:read}）——文档标注 {@code /v3/search} 的
     * {@code auth} 是 {@code app}，用户令牌不一定被接受，应用级令牌才是正路。
     *
     * <p>重要：返回体的 {@code title} 是<b>条目名称，有译名时优先返回译名</b>，
     * 而 {@code subtitle} 在 galgame 里是<b>发行年份</b>（四位数字），
     * 不是副标题，绝不能拼进标题参与比对。
     *
     * @param candidates 已按命中率排序的候选词（原名 > 罗马字 > 本地标题）
     */
    public static Long searchGalgameId(List<String> candidates) throws Exception {
        if (candidates == null || candidates.isEmpty()) return null;
        for (String q : candidates) {
            String trimmed = q == null ? "" : q.trim();
            if (trimmed.isEmpty()) continue;
            Long hit = searchOnce(trimmed);
            if (hit != null) {
                Log.i(TAG, "title search hit: 「" + trimmed + "」 -> " + hit);
                return hit;
            }
            // 未命中也要记：只记命中会让「为什么这个没匹配上」完全无从排查
            Log.w(TAG, "title search miss: 「" + trimmed + "」");
        }
        return null;
    }

    /** 单次搜索 + 精确/唯一包含判定。 */
    private static Long searchOnce(String query) throws Exception {
        String nq = normalizeTitle(query);
        if (nq.isEmpty()) return null;

        // 变体 1：原样。
        // 变体 2：把各种横线（全角－/半角-/破折号/长音符）换成空格。
        //   这是 Bangumi 那轮验证过的有效手段：日文原名常带全角「－」(U+FF0D)，
        //   原样搜会把目标挤出前若干条候选，换成空格后立刻回到第一条。
        java.util.LinkedHashSet<String> variants = new java.util.LinkedHashSet<>();
        variants.add(query);
        String spaced = replaceDashesWithSpace(query).trim();
        if (!spaced.isEmpty() && !spaced.equals(query)) variants.add(spaced);

        String token = appToken();
        for (String q : variants) {
            JSONArray items = doSearch(token, q);
            if (items == null || items.length() == 0) continue;
            Long got = pickFromCandidates(nq, items, q);
            if (got != null) return got;
        }
        return null;
    }

    private static JSONArray doSearch(String token, String query) throws Exception {
        String qs = "?q=" + url(query) + "&types=galgame&page=1&page_size=10";
        JSONObject resp = request("GET", "/search" + qs, null, token);
        // 解统一信封：{"success":true,"data":{"items":[...],"meta":{...}}}
        // 文档：「分页信息位于 data.meta，与 items 同级，而非信封顶层」
        JSONObject data = unwrap(resp);
        JSONArray items = data == null ? null : data.optJSONArray("items");
        if (items == null) {
            Log.w(TAG, "search 「" + query + "」 无 items 字段");
            return null;
        }
        if (items.length() == 0) {
            Log.w(TAG, "search 「" + query + "」 返回 0 个候选");
            return null;
        }
        StringBuilder cand = new StringBuilder();
        for (int i = 0; i < Math.min(items.length(), 5); i++) {
            JSONObject o = items.optJSONObject(i);
            if (o == null) continue;
            if (cand.length() > 0) cand.append(" | ");
            cand.append(o.optString("id", "?"))
                    .append('=').append(o.optString("title", ""));
        }
        Log.i(TAG, "search 「" + query + "」 候选" + items.length() + "个: " + cand);
        return items;
    }

    /**
     * 从候选里挑出唯一可接受的那一个。
     * <p>
     * 规则沿用 Bangumi 那次修正后的做法（宁可漏掉，也绝不能写错）：
     * <ol>
     *   <li>完全相等 → 直接采纳；</li>
     *   <li>包含关系 → <b>必须唯一</b>。多条都包含说明查询词太泛
     *       （比如只搜系列名会命中整个系列），无法确定是哪一部，一律放弃。</li>
     * </ol>
     * 早期 Bangumi 那边是「取第一个包含的」，结果差点把状态写到同一系列的
     * 别的作品上——写错作品比不同步严重得多。
     */
    private static Long pickFromCandidates(String nq, JSONArray items, String query) {
        for (int i = 0; i < items.length(); i++) {
            JSONObject it = items.optJSONObject(i);
            if (it == null) continue;
            long id = it.optLong("id", 0L);
            if (id <= 0L) continue;
            if (nq.equals(normalizeTitle(it.optString("title", "")))) {
                Log.i(TAG, "候选「" + query + "」完全相等 -> " + id);
                return id;
            }
        }

        Long one = null;
        int hits = 0;
        for (int i = 0; i < items.length(); i++) {
            JSONObject it = items.optJSONObject(i);
            if (it == null) continue;
            long id = it.optLong("id", 0L);
            if (id <= 0L) continue;
            if (nq.length() < 4) break;      // 太短的查询词不做包含匹配，免得误命中一片
            String t = normalizeTitle(it.optString("title", ""));
            if (!t.isEmpty() && t.contains(nq)) {
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

    /** 把各种横线换成空格，供搜索变体使用。 */
    static String replaceDashesWithSpace(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '-' || ch == '\uFF0D' || ch == '\u2014' || ch == '\u2013'
                    || ch == '\u2212' || ch == '\u30FC' || ch == '\uFF70') {
                sb.append(' ');
            } else {
                sb.append(ch);
            }
        }
        return sb.toString().replaceAll("\\s+", " ").trim();
    }

    /**
     * 标题归一化：只抹排版差异，不做语义模糊。
     * 去空白、去标点与符号、全角转半角、统一小写。
     */
    static String normalizeTitle(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
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

    // ======================== 应用级令牌 ========================

    /** 取应用级令牌（仅搜索用），进程内缓存 1 小时。 */
    private static synchronized String appToken() throws Exception {
        long now = System.currentTimeMillis();
        if (appToken != null && !appToken.isEmpty() && appTokenExpiresAt - 60_000L > now) {
            return appToken;
        }
        String form = "grant_type=client_credentials"
                + "&client_id=" + url(APP_CLIENT_ID)
                + "&client_secret=" + url(APP_CLIENT_SECRET)
                + "&scope=" + url(APP_SCOPE);
        HttpURLConnection c = (HttpURLConnection) new URL(TOKEN_URL).openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(CONNECT_TIMEOUT_MS);
        c.setReadTimeout(READ_TIMEOUT_MS);
        c.setDoOutput(true);
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        c.setRequestProperty("User-Agent", USER_AGENT);
        byte[] data = form.getBytes(StandardCharsets.UTF_8);
        c.setFixedLengthStreamingMode(data.length);
        try (OutputStream os = new BufferedOutputStream(c.getOutputStream())) {
            os.write(data);
            os.flush();
        }
        int http = c.getResponseCode();
        String text = readAll(http >= 200 && http < 300 ? c.getInputStream() : c.getErrorStream());
        try { c.disconnect(); } catch (Throwable ignored) { }
        if (http < 200 || http >= 300) {
            throw new Exception("Hikarinagi 搜索令牌获取失败（HTTP " + http + "）：" + preview(text));
        }
        JSONObject o = new JSONObject(text == null || text.trim().isEmpty() ? "{}" : text);
        String t = o.optString("access_token", "");
        if (t == null || t.trim().isEmpty()) throw new Exception("Hikarinagi 搜索令牌响应缺少 access_token");
        appToken = t;
        appTokenExpiresAt = System.currentTimeMillis() + o.optLong("expires_in", 3600L) * 1000L;
        return appToken;
    }

    /** 清掉进程内缓存的应用级令牌（用户手动重试时用）。 */
    public static void resetAppToken() {
        appToken = "";
        appTokenExpiresAt = 0L;
    }

    // ======================== 环回回调监听 ========================

    /**
     * 一次授权会话的回调结果。
     */
    public static class LoopbackResult {
        public final String code;
        public final String state;
        /** 空表示成功；否则是错误标识（access_denied / timeout / io / bind）。 */
        public final String error;

        LoopbackResult(String code, String state, String error) {
            this.code = code == null ? "" : code;
            this.state = state == null ? "" : state;
            this.error = error == null ? "" : error;
        }
    }

    /**
     * 环回监听句柄：{@link #open()} 到 {@link #close()} 之间保持监听。
     */
    public static class LoopbackHandle implements java.io.Closeable {
        private final java.net.ServerSocket server;

        LoopbackHandle(java.net.ServerSocket server) {
            this.server = server;
        }

        /** 阻塞收一个回调请求（必须在后台线程调用）。 */
        public LoopbackResult await() {
            try {
                server.setSoTimeout((int) LOOPBACK_TIMEOUT_MS);
                try (java.net.Socket socket = server.accept()) {
                    socket.setSoTimeout(10000);
                    String requestLine = new java.io.BufferedReader(
                            new java.io.InputStreamReader(
                                    socket.getInputStream(), StandardCharsets.US_ASCII)).readLine();
                    Map<String, String> q = parseLoopbackQuery(requestLine);
                    respondAndClose(socket);
                    String err = q.get("error");
                    if (err != null && !err.isEmpty()) {
                        String desc = q.get("error_description");
                        return new LoopbackResult("", q.get("state"),
                                desc == null || desc.trim().isEmpty() ? err : err + "：" + desc);
                    }
                    return new LoopbackResult(q.get("code"), q.get("state"), "");
                }
            } catch (java.net.SocketTimeoutException e) {
                return new LoopbackResult("", "", "timeout");
            } catch (Throwable t) {
                Log.w(TAG, "loopback await failed", t);
                return new LoopbackResult("", "", "io");
            }
        }

        @Override public void close() {
            try { server.close(); } catch (Throwable ignored) { }
        }
    }

    /** 授权页停留时间上限，与 NextMoe 一致取 5 分钟。 */
    private static final long LOOPBACK_TIMEOUT_MS = 5L * 60L * 1000L;

    /**
     * 在固定端口 {@link #LOOPBACK_PORT} 上开始监听。
     * <p>
     * 为什么固定端口而不是像 NextMoe 那样随机端口：Hikarinagi 要求 redirect_uri
     * 与控制台登记的字符串<b>完全一致（含端口）</b>，随机端口必然对不上。
     * <p>
     * 只绑 127.0.0.1：流量不出设备，也不需要任何网络权限暴露。
     *
     * @throws java.io.IOException 端口被占用等情况（多半是上一次授权没退干净）
     */
    public static LoopbackHandle openLoopback() throws java.io.IOException {
        java.net.ServerSocket server = new java.net.ServerSocket(
                LOOPBACK_PORT, 1, java.net.InetAddress.getByName("127.0.0.1"));
        Log.i(TAG, "loopback listening on 127.0.0.1:" + LOOPBACK_PORT);
        return new LoopbackHandle(server);
    }

    /** 请求行形如 GET /callback?code=..&state=.. HTTP/1.1 */
    private static Map<String, String> parseLoopbackQuery(String requestLine) {
        Map<String, String> out = new java.util.HashMap<>();
        try {
            if (requestLine == null) return out;
            int sp1 = requestLine.indexOf(' ');
            int sp2 = requestLine.lastIndexOf(' ');
            if (sp1 < 0 || sp2 <= sp1) return out;
            String target = requestLine.substring(sp1 + 1, sp2);
            int qm = target.indexOf('?');
            if (qm < 0) return out;
            for (String pair : target.substring(qm + 1).split("&")) {
                int eq = pair.indexOf('=');
                if (eq <= 0) continue;
                String k = java.net.URLDecoder.decode(pair.substring(0, eq), "UTF-8");
                String v = java.net.URLDecoder.decode(pair.substring(eq + 1), "UTF-8");
                out.put(k, v);
            }
        } catch (Throwable t) {
            Log.w(TAG, "parse loopback query failed", t);
        }
        return out;
    }

    /** 给浏览器回一个「可以关掉了」的页面。 */
    private static void respondAndClose(java.net.Socket socket) {
        try {
            String body = "<!DOCTYPE html><html><head><meta charset=\"utf-8\">"
                    + "<title>YukiHub</title></head>"
                    + "<body style=\"font-family:sans-serif;text-align:center;padding-top:15vh;"
                    + "background:#0B1020;color:#EAF7FF;\">"
                    + "<h2>授权完成</h2><p>可以关闭此页面，回到 YukiHub 继续同步。</p>"
                    + "</body></html>";
            byte[] data = body.getBytes(StandardCharsets.UTF_8);
            OutputStream os = socket.getOutputStream();
            os.write(("HTTP/1.1 200 OK\r\n"
                    + "Content-Type: text/html; charset=utf-8\r\n"
                    + "Content-Length: " + data.length + "\r\n"
                    + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            os.write(data);
            os.flush();
        } catch (Throwable ignored) { }
    }

    // ======================== 统一响应信封 ========================

    /**
     * 拆开 Hikarinagi 的统一响应信封，取出 {@code data}。
     *
     * <h3>文档原文（接入指南 · 响应约定）</h3>
     * 「所有响应均使用统一信封，业务数据位于 data 字段。
     * 端点参考中的响应示例展示的即为该字段的内容。」
     * <pre>
     * 成功：{ "success": true, "data": { … }, "request_id": "req-x", "timestamp": "..." }
     * 分页：分页信息位于 data.meta，与 items 同级，而非信封顶层。
     * </pre>
     *
     * <h3>为什么必须拆</h3>
     * 我最初照着各端点页的「响应示例」写解析，但那些示例展示的是
     * <b>{@code data} 里面的内容</b>，不含外面这层信封。
     * 于是 {@code resp.optString("name")} 之类全部取到空值，
     * 表现为「授权明明成功，却报授权可能已失效」。
     * 真实响应长这样：
     * <pre>
     * {"success":true,"data":{"id":44423,"name":"xm486","nickname":"永雏小夜",...}}
     * </pre>
     *
     * @return 内层 data 对象；没有信封（例如 204 或异常情况）时原样返回入参
     */
    static JSONObject unwrap(JSONObject resp) {
        if (resp == null) return null;
        Object inner = resp.optJSONObject("data");
        if (inner != null) return (JSONObject) inner;
        // 没有 data 字段就原样返回，保持向下兼容
        return resp;
    }

    // ======================== HTTP ========================

    private static final String USER_AGENT = "YukiHub/1.0 (Android) (https://github.com/yukihub/yukihub)";

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
            c.setRequestProperty("Authorization", "Bearer " + token);
            c.setRequestProperty("Accept", "application/json");
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

            if (code == 401) throw new NotAuthorizedException("Hikarinagi 授权已失效，请重新授权");
            if (code == 403) {
                throw new NotAuthorizedException(
                        "Hikarinagi 拒绝访问：令牌缺少所需权限（需 status:write）。"
                                + "请在控制台为应用勾选该权限后重新授权。" + preview(text));
            }
            if (code == 404) throw new NotFoundException("Hikarinagi 找不到条目：" + preview(text));
            if (code == 429) throw new RateLimitedException("Hikarinagi 请求过于频繁，请稍后再试");
            if (code < 200 || code >= 300) {
                throw new Exception("HTTP " + code + "：" + preview(text));
            }
            if (text == null || text.trim().isEmpty()) return new JSONObject();
            return new JSONObject(text);
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static synchronized void throttle() {
        try {
            long now = System.currentTimeMillis();
            long wait = MIN_INTERVAL_MS - (now - lastRequestTime);
            if (wait > 0 && lastRequestTime > 0) Thread.sleep(wait);
            lastRequestTime = System.currentTimeMillis();
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private static String readAll(InputStream is) throws Exception {
        if (is == null) return "";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
        is.close();
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String preview(String s) {
        if (s == null) return "";
        String t = s.replace('\n', ' ').trim();
        return t.length() <= 200 ? t : t.substring(0, 200) + "…";
    }

    private static String url(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (Exception e) {
            return s == null ? "" : s;
        }
    }

    // ======================== 异常 ========================

    /** 授权缺失/失效/权限不足。整体性错误，应中止后续所有请求。 */
    public static class NotAuthorizedException extends Exception {
        public NotAuthorizedException(String message) { super(message); }
    }

    /** 触发限流。整体性错误，继续跑只会全失败。 */
    public static class RateLimitedException extends Exception {
        public RateLimitedException(String message) { super(message); }
    }

    /** 条目不存在。单条错误，不影响其它条目。 */
    public static class NotFoundException extends Exception {
        public NotFoundException(String message) { super(message); }
    }

    /** OAuth 通用错误（带了 RFC 6749 的 error code 便于判定是否该清凭据）。 */
    public static class OAuthException extends Exception {
        public final String errorCode;
        public OAuthException(String message) { this(message, ""); }
        public OAuthException(String message, String errorCode) {
            super(message);
            this.errorCode = errorCode == null ? "" : errorCode;
        }
    }

    /** OAuth 服务端瞬态故障（5xx / server_error），不应据此清除本地凭据。 */
    public static class OAuthTransientException extends OAuthException {
        public OAuthTransientException(String message) { super(message); }
    }
}
