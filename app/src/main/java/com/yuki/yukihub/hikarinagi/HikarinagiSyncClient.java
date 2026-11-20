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
 *       scope 需要 {@code catalog:full}（<b>不能用 {@code catalog:read}</b>，
 *       那个看不到 NSFW 与乙女向条目），走<b>应用级</b>令牌。
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

    /**
     * 应用级令牌的 scope，只用于标题搜索。
     * <p>
     * <b>必须是 {@code catalog:full}，不能用 {@code catalog:read}。</b>
     * 文档 {@code guide/scopes} 原文：
     * <ul>
     *   <li>{@code catalog:read} ——「读取条目数据，<b>不含 NSFW 与乙女向条目</b>」</li>
     *   <li>{@code catalog:full} ——「读取条目数据（全部），<b>含 NSFW 与乙女向条目</b>」，
     *       且「{@code catalog:full} 包含 {@code catalog:read}，申请上位 scope 即可」</li>
     * </ul>
     * 早期这里是 {@code catalog:read}，导致大批 NSFW 条目在搜索结果里<b>根本不存在</b>，
     * 而不是排在后面。实测「末世孤雄」四部曲：
     * <pre>
     *   catalog:read -> 只剩 id 2044（Vol 1）
     *   catalog:full -> 2045(Vol 0) / 2044(Vol 1) / 2046(Vol 2) / 2047(Vol 3) 全在
     * </pre>
     * 更糟的是「看不见」会触发相关度兜底，把续作全部错认成第一作。
     * 控制条目可见性的是 scope，<b>不是</b>请求参数（{@code nsfw} 等一律 422 白名单拒绝）。
     * <p>
     * 用户令牌下的可见范围以该用户账号的内容偏好为上限，与这里无关。
     */
    private static final String APP_SCOPE = "catalog:full";
    /** 应用级凭据（公共只读用途，与 metadata.HikarinagiClient 各自持有一份，互不依赖）。 */
    private static final String APP_CLIENT_ID = "hkn_4poXX7v37j_iM2-o";
    private static final String APP_CLIENT_SECRET = "hks_Wv6tW5O6ev8Mbifvg1tPJ7UexehLATQcKpZJiFhV48Y";

    /** 批量写入的硬上限，文档规定 1~100。 */
    public static final int MAX_BATCH = 100;
    /** 时长上限（分钟）。本地实测最大约 1194 分钟，这里留足余量防脏数据。 */
    public static final int MAX_MINUTES = 60_000;

    /**
     * 「标题 ⊆ 查询词」方向的最小标题长度。
     * <p>
     * 短标题几乎必然出现在别的名字里，采纳它等于乱认：
     * <ul>
     *   <li>「原神牛比」→「原神」（2 字）</li>
     *   <li>「星空のメモリア Eternal Heart」→「メモリア」（4 字，但覆盖率低）</li>
     * </ul>
     */
    private static final int MIN_COVERED_TITLE_LEN = 5;

    /**
     * 「标题 ⊆ 查询词」方向的最低覆盖率。
     * <p>
     * 例：「原神牛比」里「原神」占 50%，明显串了别的名字，不采纳；
     * 「星空的记忆(汉化版)」里「星空的记忆」占 83%，可以采纳。
     */
    private static final double MIN_TITLE_COVERAGE = 0.8d;

    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 25000;
    /**
     * Hikarinagi 限速以<b>应用</b>为单位计数，默认 <b>60 次 / 分钟</b>
     * （见接入指南「限流」：超出返回 429 COMMON_RATE_LIMITED，
     *  配额为应用级，批量同步需自行控制节奏）。
     * <p>
     * 60/min = 1000ms/次，这里取 1000ms 贴着上限跑，并留出网络耗时的自然间隔。
     * 早期写死 1100ms 是凭感觉取的保守值，白白多出 10% 耗时；
     * 39 个游戏上百次搜索时这点差异很可观。
     */
    private static final long MIN_INTERVAL_MS = 1000L;

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
        // 请求头必须在 setDoOutput(true) 之前设好。
        // setDoOutput 之后 HttpURLConnection 会重建请求头，部分 Android ROM
        // 会把后设的 Content-Type 丢掉，OAuth token 端点会因缺它而失败。
        // 同型问题已修：BangumiSyncClient / VndbClient / NextMoeMeClient。
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        c.setRequestProperty("User-Agent", USER_AGENT);
        c.setDoOutput(true);
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
        // 服务端规则：「同一作品不能重复出现」。一旦重复，整批会被 422 拒绝、
        // 一条都写不进去（实测踩过：两个本地条目指向同一个 galgame）。
        // 这里做前置拦截，把「服务端 422 整批失败」变成「本地立刻报出是哪一条」。
        java.util.HashSet<Long> seenIds = new java.util.HashSet<>();
        for (RateItem it : items) {
            if (!seenIds.add(it.galgameId)) {
                throw new IllegalArgumentException(
                        "批量请求里 galgame_id " + it.galgameId + " 重复出现。"
                                + "同一作品一次批量只能写一条，请先在调用方合并。");
            }
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
     * 走应用级令牌（{@code catalog:full}）——文档标注 {@code /v3/search} 的
     * {@code auth} 是 {@code app}。必须用 {@code catalog:full}，因为
     * {@code catalog:read} 看不到 NSFW 条目（详见 {@link #APP_SCOPE} 的说明）。
     *
     * <h3>两轮策略：先把「精确」试完，再谈「兜底」</h3>
     * <ol>
     *   <li><b>第一轮</b>：逐个候选词，只认「完全相等」和「包含关系」；</li>
     *   <li><b>第二轮</b>：只有第一轮全部落空时，才对<b>第一个候选词</b>启用
     *       「服务端相关度第 1 条」这种模糊兜底。</li>
     * </ol>
     * 这个顺序差别很关键。实测反例：本地「快乐☆玛格丽特！」的中文译名搜不到，
     * 第一轮落空后立刻兜底拿了「爱丽丝学园」（id 16749，完全不相干）；
     * 而它的<b>日文原名</b>「はっぴぃ☆マーガレット!」能搜到精确相等的 id 3390——
     * 可那个候选词根本没机会被尝试。
     * <p>
     * 换句话说：兜底一旦混在第一轮里，就会在「后面还有更准的候选词」时提前认错。
     * 挪到第二轮后，只要任一候选词能精确命中就用精确的，兜底只对
     * 「全部候选都精确落空」的条目生效。
     *
     * <h3>性能要点</h3>
     * 每个请求都要占 1 次配额（60/分钟），所以<b>一命中就立刻返回</b>，
     * 绝不再试后面的候选词。早期版本对每个候选词都再试一遍「横线换空格」变体，
     * 等于把请求数翻倍，是那次「扫 39 个游戏花了 7 分钟」的主要原因之一。
     *
     * @param candidates 已按命中率排序的候选词（元数据正式标题优先）
     */
    public static Long searchGalgameId(List<String> candidates) throws Exception {
        if (candidates == null || candidates.isEmpty()) return null;

        // ---------- 第一轮：精确（完全相等 / 包含）----------
        for (String q : candidates) {
            String trimmed = q == null ? "" : q.trim();
            if (trimmed.isEmpty()) continue;
            Long hit = searchOnce(trimmed, false);
            if (hit != null) {
                Log.i(TAG, "title search hit: 「" + trimmed + "」 -> " + hit);
                return hit;
            }
            // 未命中也要记：只记命中会让「为什么这个没匹配上」完全无从排查
            Log.w(TAG, "title search miss: 「" + trimmed + "」");
        }

        // ---------- 第二轮：模糊兜底（仅第一个候选词）----------
        // 走到这里说明所有候选词都没能精确命中。此时才允许「服务端第 1 条即命中」，
        // 用来兜住跨语言（中日译名差异）这类归一化也解决不了的情况。
        // 只试第一个候选词：它是命中率最高的那个，且能避免请求数成倍膨胀。
        for (String q : candidates) {
            String trimmed = q == null ? "" : q.trim();
            if (trimmed.isEmpty()) continue;
            Long hit = searchOnce(trimmed, true);
            if (hit != null) {
                Log.i(TAG, "title search fallback hit: 「" + trimmed + "」 -> " + hit);
                return hit;
            }
            break; // 兜底只对第一个非空候选词做一次
        }

        return null;
    }

    /**
     * 单次搜索 + 判定。
     *
     * @param allowRelevanceFallback 是否允许「服务端相关度第 1 条」兜底。
     *                               第一轮传 false（只认精确），第二轮传 true。
     */
    private static Long searchOnce(String query, boolean allowRelevanceFallback) throws Exception {
        String nq = normalizeTitle(query);
        if (nq.isEmpty()) return null;

        // 只在查询词含横线时才多试一次「横线换空格」变体：
        // 不带横线的查询词，这个变体与原文完全相同，纯属浪费一次配额。
        java.util.LinkedHashSet<String> variants = new java.util.LinkedHashSet<>();
        variants.add(query);
        String spaced = replaceDashesWithSpace(query).trim();
        if (!spaced.isEmpty() && !spaced.equals(query)) variants.add(spaced);

        String token = appToken();
        for (String q : variants) {
            JSONArray items = doSearch(token, q);
            if (items == null || items.length() == 0) continue;
            Long got = pickFromCandidates(nq, items, q, allowRelevanceFallback);
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
     *
     * <h3>为什么不能只靠「查询词 vs 返回标题」比对</h3>
     * 文档写明搜索返回的 {@code title} 是「条目名称，<b>有译名时优先返回译名</b>」，
     * 也就是说它是<b>中文译名</b>，而我们的查询词常常是日文原名。
     * 于是「保健室のセンセーとシャボン玉中毒の助手」去搜，
     * 第 1 条明明就是「保健室的老师与沉迷吹泡泡的助手」（同一个作品），
     * 但日文查询词与中文标题既不相等等，也不互为子串，会被判成 miss。
     * 这不是标点差异，是<b>跨语言</b>——再怎么归一化也匹配不上。
     *
     * <h3>改为「按搜索意图」判定</h3>
     * 搜索本身就是服务端做的相关性排序，服务端认为第 1 条最相关，
     * 就说明它大概率就是我们查的条目。所以：
     * <ol>
     *   <li>归一化后<b>完全相等</b> → 直接采纳（最可靠）；</li>
     *   <li>否则<b>只接受第 1 条</b>，且要求查询词足够具体
     *       （避免「爱」这种短词乱撞）——服务端排序第一即视为命中；</li>
     *   <li>命中后立刻停，不再尝试后面的候选词。</li>
     * </ol>
     * 这样做的前提是<b>查询词本身足够精确</b>（原名/罗马字/完整标题），
     * 而不是「時計仕掛けのレイライン」这种只有系列名的泛词。
     * <p>
     * 注意这里<b>不做泛词过滤</b>：没有额外的「系列名识别层」，
     * 这一级只认「完全相等」与「包含关系」两种判据。
     * 也就是说匹配刻意偏宽松——宁可在少量条目上认错，
     * 也不因为用户多打一个字就整条匹配不上。
     *
     * @param allowRelevanceFallback 是否允许「服务端相关度第 1 条」兜底。
     *       由 {@link #searchGalgameId} 控制：第一轮传 {@code false}（只认精确），
     *       等所有候选词都精确落空后，第二轮才对第一个候选词传 {@code true}。
     *       这样「后面还有更准的候选词」时不会被提前兜底认错。
     */
    private static Long pickFromCandidates(String nq, JSONArray items, String query,
                                           boolean allowRelevanceFallback) {
        if (items == null || items.length() == 0) return null;

        // 1) 完全相等优先（含全角/空格归一化后相等）
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

        // 2) 包含关系：查询词是返回标题的子串（或反之），且唯一
        //
        //    两个方向的安全性完全不同，必须分开判：
        //    a) 查询词 ⊆ 标题（如「星空的记忆」→「星空的记忆 -Wish upon a star」）
        //       安全：用户给的比完整标题短，正常。但要求标题别短得离谱，
        //       防止「末」这种单字标题匹配上任何长查询词。
        //    b) 标题 ⊆ 查询词（如「星空のメモリア Eternal Heart」→「メモリア」）
        //       <b>危险方向</b>：实测这条把《星空的记忆 Eternal Heart》错认成
        //       《メモリア》(id 2202)——目标 id 68 明明排在候选第 1，
        //       却因为 2202 的字面包含关系被「唯一命中」捡走了。
        //       这个方向必须要求标题<b>足够长</b>且覆盖查询词的大部分。
        Long one = null;
        int hits = 0;
        for (int i = 0; i < items.length(); i++) {
            JSONObject it = items.optJSONObject(i);
            if (it == null) continue;
            long id = it.optLong("id", 0L);
            if (id <= 0L) continue;
            String rawTitle = it.optString("title", "");
            String t = normalizeTitle(rawTitle);
            if (t.isEmpty()) continue;
            if (nq.length() < 4) continue;

            if (t.contains(nq)) {
                // 查询词 ⊆ 标题
                hits++;
                if (one == null) one = id;
            } else if (nq.contains(t)) {
                // 标题 ⊆ 查询词：危险方向，需要覆盖率把关
                if (acceptCoveredTitle(nq, t)) {
                    hits++;
                    if (one == null) one = id;
                } else {
                    double cov = (double) t.length() / (double) nq.length();
                    Log.w(TAG, "候选「" + query + "」里「" + rawTitle + "」只覆盖 "
                            + Math.round(cov * 100) + "%，疑似串了别的游戏名，不采纳");
                }
            }
        }
        if (hits == 1) {
            Log.i(TAG, "候选「" + query + "」包含命中(唯一) -> " + one);
            return one;
        }

        // 3) 跨语言兜底：服务端排序第一即视为命中。
        //    两道开关同时满足才敢这么做：
        //      a) allowRelevanceFallback —— 由调用方控制。第一轮传 false，
        //         这样「中文译名没命中但日文原名能精确命中」的情况不会在这里认错
        //         （实测「快乐☆玛格丽特！」：中文落空后若立刻兜底会拿到不相关的
        //         「爱丽丝学园」，而它的日文原名其实能精确命中）。
        //      b) 查询词足够具体，否则「爱」这种短词会乱撞。
        if (hits == 0 && allowRelevanceFallback && nq.length() >= 4) {
            JSONObject first = items.optJSONObject(0);
            if (first != null) {
                long id = first.optLong("id", 0L);
                if (id > 0L) {
                    Log.i(TAG, "候选「" + query + "」按服务端相关度第1条采纳 -> " + id
                            + "（返回标题「" + first.optString("title", "") + "」）");
                    return id;
                }
            }
        }

        if (hits > 1) {
            Log.w(TAG, "候选「" + query + "」有 " + hits + " 条都包含，无法确定是哪一部，放弃");
        }
        return null;
    }

    /**
     * 「返回标题 ⊆ 查询词」方向是否可采纳。
     * <p>
     * 这个方向天然危险：查询词里只要<b>碰巧包含</b>某个短标题，就会认错作品。
     * <ul>
     *   <li>实测反例 1：本地「原神牛比」→ 站上「原神」，覆盖率 50%，会把
     *       用户真正的游戏覆盖成「原神」。</li>
     *   <li>实测反例 2：「星空のメモリア Eternal Heart」→「メモリア」(id 2202)，
     *       覆盖率很低，却因为「唯一包含」把本来正确的 id 68 挤掉了。</li>
     * </ul>
     * 因此要求两条同时满足：标题本身不算太短、且覆盖查询词的绝大部分。
     *
     * @param nq 归一化后的查询词
     * @param t  归一化后的返回标题
     */
    static boolean acceptCoveredTitle(String nq, String t) {
        if (nq == null || t == null) return false;
        if (nq.isEmpty() || t.isEmpty()) return false;
        if (t.length() < MIN_COVERED_TITLE_LEN) return false;
        double cov = (double) t.length() / (double) nq.length();
        return cov >= MIN_TITLE_COVERAGE;
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
        // 同 tokenPost：请求头必须在 setDoOutput 之前设定。
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        c.setRequestProperty("User-Agent", USER_AGENT);
        c.setDoOutput(true);
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
                // Content-Type 必须在 setDoOutput 之前设定（同型问题已修）。
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                c.setDoOutput(true);
                byte[] data = body.toString().getBytes(StandardCharsets.UTF_8);
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
            if (code == 422) {
                // 参数校验失败。服务端会整批拒绝（不是单条失败），所以必须按整体错误抛出。
                // 错误体是 {"success":false,"error":{"code":..,"message":..,"field_errors":[..]}}
                throw new ValidationException("Hikarinagi 拒绝了这次请求：" + validationMessage(text));
            }
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

    /**
     * 从 422 的响应体里提取人能看懂的原因。
     * <p>
     * 服务端错误信封（与成功信封<b>不同</b>）：<br>
     * {@code {"success":false,"error":{"code":"COMMON_VALIDATION_FAILED",
     * "message":"请求参数校验失败","field_errors":[{"field":"items",
     * "message":"同一 Galgame 不能重复出现","code":"arrayUnique"}]}}}
     * <p>
     * 只取 {@code error.message} 太笼统（只会得到「请求参数校验失败」），
     * 真正有用的信息在 {@code field_errors[].message} 里，必须拼上。
     * 解析失败一律退回原始文本，绝不因为解析挂了而丢掉错误原因。
     */
    static String validationMessage(String text) {
        if (text == null || text.trim().isEmpty()) return "参数校验未通过（服务端未返回原因）";
        try {
            JSONObject root = new JSONObject(text);
            // 这里不能用 unwrap()：错误信封的字段是 error，不是 data
            JSONObject err = root.optJSONObject("error");
            if (err == null) err = root;
            StringBuilder sb = new StringBuilder();
            String msg = err.optString("message", "").trim();
            if (!msg.isEmpty()) sb.append(msg);
            JSONArray fields = err.optJSONArray("field_errors");
            if (fields != null && fields.length() > 0) {
                for (int i = 0; i < fields.length() && i < 5; i++) {
                    JSONObject f = fields.optJSONObject(i);
                    if (f == null) continue;
                    String fm = f.optString("message", "").trim();
                    String fn = f.optString("field", "").trim();
                    if (fm.isEmpty()) continue;
                    if (sb.length() > 0) sb.append("；");
                    sb.append(fn.isEmpty() ? fm : fn + ": " + fm);
                }
            }
            if (sb.length() == 0) {
                String code = err.optString("code", "").trim();
                sb.append(code.isEmpty() ? "参数校验未通过" : code);
            }
            return sb.toString();
        } catch (Throwable t) {
            return preview(text);
        }
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
    /**
     * 参数校验失败（HTTP 422）。
     * <p>
     * <b>整批拒绝</b>，不是单条失败：请求体里只要有一处不合规
     * （例如同一个 galgame_id 出现两次），服务端就一个字段都不写。
     * 因此必须按整体性错误处理，不能当成某一条的失败。
     */
    public static class ValidationException extends Exception {
        public ValidationException(String message) { super(message); }
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
