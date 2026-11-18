package com.yuki.yukihub.bangumi;

import android.content.Context;
import android.util.Log;

import com.yuki.yukihub.data.GameRepository;
import com.yuki.yukihub.model.Game;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bangumi 游玩状态同步的业务层。
 * <p>
 * 设计目标与 NextMoe / VNDB 那两个保持一致：
 * <ol>
 *   <li><b>只推有变化的条目</b>：靠本机签名比对，第二次同步零请求；</li>
 *   <li><b>预览与执行分离</b>：预览不写任何东西（含本地缓存），确认后才动；</li>
 *   <li><b>绝不动业务数据</b>：只写远端状态 + 本地两个同步列。</li>
 * </ol>
 *
 * <h3>匹配策略（按用户要求：优先 id，没有 id 再按名字搜）</h3>
 * <pre>
 *   第1级  cache     games.bangumi_subject_id（上次搜索命中的结果，永久复用）
 *   第2级  metadata  metadata_cache 里 source='bangumi' 的 source_id（纯数字）
 *   第3级  title     原名 > 罗马字 > 中文名，精确/包含匹配
 * </pre>
 * 第 3 级命中后把 subject_id 写回 games.bangumi_subject_id，
 * 之后每次同步都走第 1 级，零搜索。
 *
 * <h3>为什么不读远端</h3>
 * Bangumi 的 {@code POST /v0/users/-/collections/{id}} 语义是
 * 「不存在则创建，存在则修改」，写入本身幂等，
 * 所以不需要先读一次远端列表来判断「哪些已是最新」——
 * 那会白白多一个来回，库大的用户会明显变慢。
 */
public final class BangumiSyncService {

    private static final String TAG = "BangumiSync";

    /** 存 token 与上次同步时间的 prefs。独立的文件，绝不与 yukihub_prefs / nextmoe_auth 混用。 */
    private static final String PREFS_NAME = "bangumi_sync";
    private static final String KEY_TOKEN = "enc_token";
    private static final String KEY_LAST_SYNC_AT = "last_sync_at";

    private BangumiSyncService() { }

    // ======================== 凭据 ========================

    /**
     * 保存 token。
     * <p>
     * 独立存储（用户要求：不要复用元数据那个 bangumi_token，
     * 免得用户还得去设置里翻）。预文件也独立，与 NextMoe 的 nextmoe_auth 隔离。
     */
    public static void saveToken(Context context, String token) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_TOKEN, token == null ? "" : token.trim()).apply();
    }

    public static String getToken(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_TOKEN, "");
    }

    public static void clearToken(Context context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().remove(KEY_TOKEN).apply();
    }

    /** 是否已填过 token（纯本地判断，不发网络请求）。 */
    public static boolean isConnected(Context context) {
        String t = getToken(context);
        return t != null && !t.trim().isEmpty();
    }

    public static long lastSyncAt(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getLong(KEY_LAST_SYNC_AT, 0L);
    }

    // ======================== 状态映射 ========================

    /**
     * 本地 playStatus → Bangumi 收藏类型。
     * <pre>
     *   unplayed  -> 1 想看   （已下载在库里的，按「想玩」处理）
     *   completed -> 2 看过
     *   playing   -> 3 在看
     *   onhold    -> 4 搁置
     *   dropped   -> 5 抛弃
     * </pre>
     * 注意 2 是「看过」、3 是「在看」，不是直觉顺序，别照 VNDB 的写法套。
     */
    public static int toBangumiType(String playStatus) {
        if (playStatus == null) return -1;
        switch (playStatus.trim().toLowerCase()) {
            case "unplayed":  return BangumiSyncClient.TYPE_WISH;
            case "completed": return BangumiSyncClient.TYPE_DONE;
            case "playing":   return BangumiSyncClient.TYPE_DOING;
            case "onhold":    return BangumiSyncClient.TYPE_ON_HOLD;
            case "dropped":   return BangumiSyncClient.TYPE_DROPPED;
            default:          return -1;
        }
    }

    /** 签名：目标 type 的字符串形式，用于判断「自上次同步以来状态有没有变」。 */
    public static String signatureOf(Game g) {
        if (g == null) return "";
        int t = toBangumiType(g.playStatus);
        return t < 0 ? "" : String.valueOf(t);
    }

    /** type → 中文名（UI 用）。 */
    public static String typeText(int type) {
        switch (type) {
            case BangumiSyncClient.TYPE_WISH:    return "想看";
            case BangumiSyncClient.TYPE_DONE:    return "看过";
            case BangumiSyncClient.TYPE_DOING:   return "在看";
            case BangumiSyncClient.TYPE_ON_HOLD: return "搁置";
            case BangumiSyncClient.TYPE_DROPPED: return "抛弃";
            default:                             return "无";
        }
    }

    // ======================== 数据结构 ========================

    /** 一条匹配好的待同步项。 */
    public static class Matched {
        public final Game game;
        /** Bangumi 条目 id，纯数字。 */
        public final String subjectId;
        /** 匹配来源：cache / metadata / title，仅用于日志与排错展示。 */
        public final String source;

        public Matched(Game game, String subjectId, String source) {
            this.game = game;
            this.subjectId = subjectId;
            this.source = source;
        }
    }

    /** 一条待推送的变化明细。 */
    public static class ChangeItem {
        public final Matched matched;
        public final int targetType;
        /** true 表示这款游戏是第一次同步（没有旧签名）。 */
        public final boolean firstTime;

        public ChangeItem(Matched matched, int targetType, boolean firstTime) {
            this.matched = matched;
            this.targetType = targetType;
            this.firstTime = firstTime;
        }
    }

    /** 预览：还没写任何东西。 */
    public static class Preview {
        public final List<Matched> items = new ArrayList<>();
        public final List<Game> unmatched = new ArrayList<>();
        /** 本次标题搜索新解析出的映射，预览确认后回写缓存（下次零搜索）。 */
        public final Map<Long, String> newlyResolved = new LinkedHashMap<>();
        public final List<ChangeItem> changed = new ArrayList<>();
        public final List<Matched> skipped = new ArrayList<>();

        /** 连接信息（展示用）。 */
        public String username = "";

        public int changesCount = 0;
        public int skippedCount = 0;

        public boolean isEmpty() {
            return items.isEmpty();
        }
    }

    /** 单条失败。 */
    public static class ItemFailure {
        public final String gameTitle;
        public final String reason;
        public ItemFailure(String gameTitle, String reason) {
            this.gameTitle = gameTitle;
            this.reason = reason;
        }
    }

    /** 执行结果。 */
    public static class Result {
        public int ok = 0;
        public int fail = 0;
        public int markedSynced = 0;
        public int unmatched = 0;
        public final List<ItemFailure> failures = new ArrayList<>();
    }

    /** 匹配进度回调（UI 用）。 */
    public interface ResolveProgress {
        void onResolveProgress(int done, int total);
    }

    // ======================== 阶段 1：只读匹配 ========================

    /**
     * 收集并匹配，不写任何数据（本地缓存也留到 execute 成功后再写）。
     */
    public static Preview prepare(Context context, String token, ResolveProgress progress)
            throws Exception {
        BangumiSyncClient.AuthInfo auth = BangumiSyncClient.authInfo(token);

        GameRepository repo = new GameRepository(context);
        List<Game> all = repo.getAll();

        // 第1级：本机缓存（上次搜索命中的 subject_id）
        Map<Long, String> cachedIds = new LinkedHashMap<>();
        for (Game g : all) {
            String sid = g.bangumiSubjectId == null ? "" : g.bangumiSubjectId.trim();
            if (looksLikeSubjectId(sid)) cachedIds.put(g.id, sid);
        }

        // 第2级：metadata_cache 里 source='bangumi' 的 source_id
        Map<Long, String> metadataIds = repo.getBangumiSourceIds();

        List<Matched> matched = new ArrayList<>();
        List<Game> needTitleSearch = new ArrayList<>();

        for (Game g : all) {
            int target = toBangumiType(g.playStatus);
            // 状态无法映射（空 / 未知值）的不上报，避免把用户数据写坏
            if (target < 0) continue;

            String fromCache = cachedIds.get(g.id);
            if (fromCache != null) {
                matched.add(new Matched(g, fromCache, "cache"));
                continue;
            }
            String fromMeta = metadataIds.get(g.id);
            if (fromMeta != null && looksLikeSubjectId(fromMeta)) {
                matched.add(new Matched(g, fromMeta, "metadata"));
                continue;
            }
            needTitleSearch.add(g);
        }

        // 第3级：标题搜索。候选词按「原名 > 罗马字 > 本地标题」排序。
        List<Game> unmatched = new ArrayList<>();
        Map<Long, String> newlyResolved = new LinkedHashMap<>();
        if (!needTitleSearch.isEmpty()) {
            // 只在这几个游戏确实要搜时才去解析元数据，避免全表 JSON 解析
            Map<Long, List<String>> searchTitles = repo.getSearchTitles(needTitleSearch);

            int done = matched.size();
            int total = all.size();
            for (Game g : needTitleSearch) {
                List<String> candidates = buildSearchCandidates(g, searchTitles.get(g.id));
                if (candidates.isEmpty()) {
                    unmatched.add(g);
                } else {
                    try {
                        String sid = BangumiSyncClient.searchSubjectByTitle(token, candidates);
                        if (sid == null || sid.isEmpty()) {
                            unmatched.add(g);
                        } else {
                            matched.add(new Matched(g, sid, "title"));
                            newlyResolved.put(g.id, sid);
                        }
                    } catch (BangumiSyncClient.NotAuthorizedException
                             | BangumiSyncClient.RateLimitedException e) {
                        // 整体性错误：token 失效或限流，继续搜只会全失败
                        unmatched.add(g);
                        throw e;
                    } catch (Throwable t) {
                        // 单个搜索失败不影响其它条目
                        Log.w(TAG, "title search failed: " + candidates.get(0), t);
                        unmatched.add(g);
                    }
                }
                done++;
                if (progress != null) progress.onResolveProgress(done, total);
            }
        } else {
            if (progress != null) progress.onResolveProgress(all.size(), all.size());
        }

        Preview p = new Preview();
        p.username = auth.displayName();
        p.items.addAll(matched);
        p.unmatched.addAll(unmatched);
        p.newlyResolved.putAll(newlyResolved);

        // 判断哪些需要写：纯本地签名比对，不发任何读请求
        for (Matched m : matched) {
            int target = toBangumiType(m.game.playStatus);
            if (target < 0) continue;

            String sig = String.valueOf(target);
            boolean syncedBefore = m.game.bangumiSyncedAt > 0;
            boolean firstTime = !syncedBefore;
            boolean unchanged = syncedBefore && sig.equals(m.game.bangumiSyncedSig);

            if (unchanged) {
                p.skipped.add(m);
            } else {
                p.changed.add(new ChangeItem(m, target, firstTime));
            }
        }

        // 排序：首次同步的排最前（信息量最大）
        java.util.Collections.sort(p.changed, (a, b) -> {
            if (a.firstTime != b.firstTime) return a.firstTime ? -1 : 1;
            return 0;
        });

        p.changesCount = p.changed.size();
        p.skippedCount = p.skipped.size();

        Log.i(TAG, "prepare: matched=" + matched.size() + " unmatched=" + unmatched.size()
                + " changes=" + p.changesCount + " skipped=" + p.skippedCount);
        return p;
    }

    /**
     * 校验 Bangumi subject_id 形态：纯数字。
     * 缓存里可能混进脏数据（如误存成 vndb 的 vNNN），
     * 宁可当成「没匹配到」也不要写到别的条目上。
     */
    static boolean looksLikeSubjectId(String s) {
        if (s == null) return false;
        String v = s.trim();
        if (v.isEmpty() || v.length() > 12) return false;
        for (int i = 0; i < v.length(); i++) {
            if (!Character.isDigit(v.charAt(i))) return false;
        }
        // 全 0 之类明显不合法
        return !v.equals("0");
    }

    /**
     * 构建标题搜索的候选词。
     *
     * <h3>只用「已匹配源刮下来的正式标题」，绝不用本地游戏名</h3>
     * {@code games.title} 是用户自己起的名字，实测里有：
     * <pre>
     *   【Tyranor模拟器】保健室的老师与沉迷吹泡泡的助手
     *   命运石之门(斯坦因之门)[简][KFC特创组]
     * </pre>
     * 带汉化组/模拟器标记，拿去搜既不准也不干净。
     * <p>
     * 正确来源是 {@code metadata_cache}：从 VNDB / NextMoe / Bangumi 等源
     * 刮下来的正式条目名，含 {@code chineseTitle} / {@code originalTitle}
     * / {@code romanTitle}。
     * <p>
     * 顺序：<b>中文译名优先</b> → 原名 → 罗马字。
     * Bangumi 的条目名主要收录中文/日文名，中文译名命中率最高。
     *
     * @param titles 来自 {@code GameRepository.getSearchTitles}
     */
    static List<String> buildSearchCandidates(Game g, List<String> titles) {
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
        if (titles != null) {
            for (String s : titles) {
                if (s == null) continue;
                String v = s.trim();
                if (v.isEmpty() || "null".equalsIgnoreCase(v)) continue;
                seen.add(v);
            }
        }
        // 兜底：元数据什么都没刮到时，才退回本地标题（此时它可能是唯一线索）
        if (seen.isEmpty() && g != null && g.title != null) {
            String t = g.title.trim();
            if (!t.isEmpty()) seen.add(t);
        }
        return new ArrayList<>(seen);
    }

    // ======================== 阶段 2：执行推送 ========================

    /**
     * 逐条推送。只写远端 + 本地两个同步状态列，绝不改 playStatus 等业务数据。
     */
    public static Result execute(Context context, String token, Preview preview) throws Exception {
        Result result = new Result();
        if (preview == null) return result;
        result.unmatched = preview.unmatched.size();
        if (preview.changed.isEmpty()) {
            Log.i(TAG, "execute: nothing to push");
            return result;
        }

        GameRepository repo = new GameRepository(context);
        long now = System.currentTimeMillis();

        for (int i = 0; i < preview.changed.size(); i++) {
            ChangeItem c = preview.changed.get(i);
            try {
                BangumiSyncClient.setState(token, c.matched.subjectId, c.targetType);
                result.ok++;
                repo.updateBangumiSyncState(c.matched.game.id, now, String.valueOf(c.targetType));
                result.markedSynced++;
            } catch (BangumiSyncClient.NotAuthorizedException
                     | BangumiSyncClient.RateLimitedException e) {
                // 「整体性」错误：token 失效或已触发限流，继续跑只会全失败。
                int remaining = preview.changed.size() - i;
                result.fail += remaining;
                result.failures.add(new ItemFailure("（已中止后续 " + remaining + " 个）",
                        e.getMessage()));
                Log.w(TAG, "abort remaining due to: " + e.getMessage());
                break;
            } catch (Throwable t) {
                result.fail++;
                String title = c.matched.game.title;
                result.failures.add(new ItemFailure(
                        title == null || title.trim().isEmpty() ? "未命名游戏" : title,
                        describeFailure(t)));
                Log.w(TAG, "setState failed: " + c.matched.subjectId, t);
            }
        }

        if (result.markedSynced > 0) {
            try {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit().putLong(KEY_LAST_SYNC_AT, now).apply();
            } catch (Throwable t) {
                Log.w(TAG, "write last sync time failed", t);
            }
            // 本次新解析出的 subject_id 一并缓存，下次免搜索
            try {
                if (!preview.newlyResolved.isEmpty()) {
                    repo.updateBangumiSubjectIds(preview.newlyResolved);
                }
            } catch (Throwable t) {
                Log.w(TAG, "cache resolved subject ids failed", t);
            }
        }
        return result;
    }

    private static String describeFailure(Throwable t) {
        if (t == null) return "未知错误";
        String m = t.getMessage();
        if (m == null || m.trim().isEmpty()) return t.getClass().getSimpleName();
        return m;
    }
}