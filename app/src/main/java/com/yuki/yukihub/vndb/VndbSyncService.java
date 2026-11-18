package com.yuki.yukihub.vndb;

import android.content.Context;
import android.util.Log;

import com.yuki.yukihub.data.GameRepository;
import com.yuki.yukihub.model.Game;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * VNDB 游玩状态同步的编排层：收集 → 匹配 → 对比现状 → 预览 → 逐条推送 → 汇总。
 * <p>
 * 全部方法<b>阻塞</b>，必须在 IO 线程调用。
 *
 * <h3>和 NextMoe 版本的关键差异</h3>
 * <ul>
 *   <li>VNDB 的 VN id 直接来自 metadata_cache 里 source='vndb' 的 source_id
 *       （格式就是 vNNN），<b>不需要任何反查、搜索或标题匹配</b>。</li>
 *   <li>没有批量写：PATCH /ulist/{id} 一次只能改一个 VN，只能逐条发。</li>
 *   <li>labels_set / labels_unset 是<b>局部增删</b>，
 *       不会覆盖用户在 VNDB 上的评分、笔记、起止日期。</li>
 * </ul>
 *
 * <h3>状态映射</h3>
 * 本地 5 态 → VNDB 5 个内置 label，一一对应（label id 已实测确认）：
 * unplayed→Wishlist(5)、playing→Playing(1)、completed→Finished(2)、
 * onhold→Stalled(3)、dropped→Dropped(4)。
 */
public final class VndbSyncService {

    private static final String TAG = "VndbSync";

    /** 与 MainActivity 共用同一个 SharedPreferences 文件，各用各的 key。 */
    private static final String PREFS_NAME = "yukihub_prefs";
    private static final String KEY_LAST_SYNC_AT = "vndb_sync_at";
    private static final String KEY_TOKEN = "vndb_token";

    private VndbSyncService() { }

    // ======================== 状态映射 ========================

    /**
     * 本地 playStatus → VNDB 内置 label id。
     * <p>
     * 「未玩」映射到 Wishlist：库里已有的游戏本来就是打算玩的，
     * 放到想玩列表是最符合直觉的归类。
     *
     * @return label id；未知状态返回 -1（不上报，避免把用户数据写坏）
     */
    public static int toVndbLabel(String playStatus) {
        if (playStatus == null) return -1;
        switch (playStatus.trim().toLowerCase()) {
            case "unplayed":  return VndbClient.LABEL_WISHLIST;
            case "playing":   return VndbClient.LABEL_PLAYING;
            case "completed": return VndbClient.LABEL_FINISHED;
            case "onhold":    return VndbClient.LABEL_STALLED;
            case "dropped":   return VndbClient.LABEL_DROPPED;
            default:          return -1;
        }
    }

    /** 签名：目标 label 的字符串形式，用于判断「自上次同步以来状态有没有变」。 */
    public static String signatureOf(Game g) {
        if (g == null) return "";
        int label = toVndbLabel(g.playStatus);
        return label < 0 ? "" : String.valueOf(label);
    }

    /** label id → 中文名（UI 用）。 */
    public static String labelText(int label) {
        switch (label) {
            case VndbClient.LABEL_PLAYING:   return "在玩";
            case VndbClient.LABEL_FINISHED:  return "玩过";
            case VndbClient.LABEL_STALLED:   return "搁置";
            case VndbClient.LABEL_DROPPED:   return "弃坑";
            case VndbClient.LABEL_WISHLIST:  return "想玩";
            case VndbClient.LABEL_BLACKLIST: return "黑名单";
            default:                          return "无";
        }
    }

    // ======================== 数据结构 ========================

    /** 一条匹配好的待同步项。 */
    public static class Matched {
        public final Game game;
        /** VNDB 的 VN id，形如 v123。 */
        public final String vnId;
        /** 匹配来源：cache / metadata / title，仅用于日志与排错展示。 */
        public final String source;

        public Matched(Game game, String vnId, String source) {
            this.game = game;
            this.vnId = vnId;
            this.source = source;
        }
    }

    /** 一条变化明细。 */
    public static class ChangeItem {
        public final Matched matched;
        public final int targetLabel;
        /** 远端当前的状态 label（没有则为 -1），仅用于展示「A → B」。 */
        public final int remoteLabel;
        /** true 表示远端原本没有这条记录（首次加入列表）。 */
        public final boolean firstTime;

        public ChangeItem(Matched m, int targetLabel, int remoteLabel, boolean firstTime) {
            this.matched = m;
            this.targetLabel = targetLabel;
            this.remoteLabel = remoteLabel;
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

        /**
         * 读远端列表是否失败过。
         * <p>
         * 为 true 时「远端现状未知」，此时不能把条目当成「远端没有」，
         * 也不能断言「与上次相同」。UI 必须把这个提示显示出来，
         * 否则用户会看到一堆“加入”，误以为缓存没生效。
         */
        public boolean remoteReadFailed = false;

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
        public int unmatched = 0;
        public final List<ItemFailure> failures = new ArrayList<>();
        public int markedSynced = 0;
    }

    public interface Progress {
        void onResolveProgress(int done, int total);
    }

    // ======================== 阶段 1：只读准备 ========================

    /**
     * 收集并匹配，不写任何数据。
     *
     * @param token 用户填写的 VNDB API token
     */
    public static Preview prepare(Context context, String token, Progress progress) throws Exception {
        GameRepository repo = new GameRepository(context);

        // 1) 校验 token，拿 user_id（读列表要用）与权限
        VndbClient.AuthInfo auth = VndbClient.authInfo(token);
        if (!auth.canWrite()) {
            throw new VndbClient.NotAuthorizedException(
                    "这个 token 没有 listwrite 权限，无法同步状态。\n\n"
                            + "请到 vndb.org/u/tokens 重新生成，并勾选「listwrite」。");
        }

        // 2) 三级匹配
        //   第1级 cache：games.vndb_vn_id（上次标题搜索命中的结果，零请求）
        //   第2级 metadata：metadata_cache 里 source='vndb' 的 source_id（vNNN，零请求）
        //   第3级 title：标题搜索（要发请求、费配额，只有前两级都没命中才走）
        // 实测（39 个游戏的真实备份）：第2级只能覆盖 32 个，
        // 剩下 7 个里有 4 个能被标题搜索救回来 —— 所以第3级是必要的，不是锦上添花。
        Map<Long, String> metadataVnIds = repo.getVndbSourceIds();

        List<Game> all = repo.getAll();
        List<Matched> matched = new ArrayList<>();
        List<Game> needTitleSearch = new ArrayList<>();

        for (Game g : all) {
            if (g == null) continue;
            int label = toVndbLabel(g.playStatus);
            // 状态未知（空值/脏值）的跳过：既不上报，也不进「无法匹配」，避免噪音
            if (label < 0) continue;

            // 第1级：缓存的标题搜索成果
            String cached = g.vndbVnId == null ? "" : g.vndbVnId.trim();
            if (!cached.isEmpty() && looksLikeVnId(cached)) {
                matched.add(new Matched(g, cached, "cache"));
                continue;
            }
            // 第2级：metadata_cache 的 vndb 锚点
            String fromMeta = metadataVnIds.get(g.id);
            if (fromMeta != null && !fromMeta.trim().isEmpty()) {
                matched.add(new Matched(g, fromMeta.trim(), "metadata"));
                continue;
            }
            // 前两级都没命中 → 待标题搜索
            needTitleSearch.add(g);
        }

        // 第3级：标题搜索。候选词按「原名 > 罗马字 > 本地标题」排序，
        // 因为 VNDB 是日语为核心的库，用中文译名搜命中率很低。
        List<Game> unmatched = new ArrayList<>();
        Map<Long, String> newlyResolved = new LinkedHashMap<>();
        if (!needTitleSearch.isEmpty()) {
            // 只在前两级确实有漏网时才去读原名，且只读这几个游戏的。
            // （getOriginalTitles 要解析 metadata_cache 的 json 字段，
            //  全表扫一遍是白费功夫——多数情况下走向缓存，一个都不用搜。）
            Map<Long, java.util.List<String>> originalTitles =
                    repo.getOriginalTitles(needTitleSearch);

            int done = matched.size();
            int total = all.size();
            for (Game g : needTitleSearch) {
                List<String> candidates = buildSearchCandidates(g, originalTitles.get(g.id));
                if (candidates.isEmpty()) {
                    unmatched.add(g);
                } else {
                    try {
                        String vnId = VndbClient.searchVnByTitle(token, candidates);
                        if (vnId == null || vnId.isEmpty()) {
                            unmatched.add(g);
                        } else {
                            matched.add(new Matched(g, vnId, "title"));
                            newlyResolved.put(g.id, vnId);
                        }
                    } catch (VndbClient.NotAuthorizedException | VndbClient.RateLimitedException e) {
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
        p.username = auth.username;
        p.items.addAll(matched);
        p.unmatched.addAll(unmatched);
        p.newlyResolved.putAll(newlyResolved);

        // 3) 读回远端现状，用于「跳过无变化」与展示「A → B」
        List<String> vnIds = new ArrayList<>();
        for (Matched m : matched) vnIds.add(m.vnId);
        Map<String, java.util.Set<Integer>> remote;
        boolean remoteReadFailed = false;
        try {
            remote = VndbClient.fetchStates(token, auth.userId, vnIds);
        } catch (Throwable t) {
            // 读失败不阻断（写入是幂等的，多写几条不会写错数据），
            // 但必须把「读取失败」这件事显式告诉 UI。
            // 否则所有条目都会被当成「远端不存在」而显示“加入”，
            // 看起来像“缓存没生效、每次全量同步”——这个 bug 就是因此藏了很久。
            Log.w(TAG, "fetchStates failed, treat all as changed", t);
            remote = new LinkedHashMap<>();
            remoteReadFailed = true;
        }

        for (Matched m : matched) {
            int target = toVndbLabel(m.game.playStatus);
            java.util.Set<Integer> cur = remote.get(m.vnId);
            int remoteLabel = currentStateLabel(cur);
            boolean absent = (cur == null) || cur.isEmpty();

            // 关键：只有「确实查过远端、而且远端没有这条」才算首次加入。
            // 若远端压根没读成功，absent 不代表“远端没有”，
            // 此时一律按「状态可能已变」处理（宁可多写一次幂等写），
            // 但 UI 会明确提示读取失败，不会伪装成 36 条新增。
            boolean firstTime = absent && !remoteReadFailed;

            String sig = String.valueOf(target);
            boolean unchanged = !remoteReadFailed
                    && !absent
                    && sig.equals(m.game.vndbSyncedSig)
                    && m.game.vndbSyncedAt > 0
                    && remoteLabel == target;

            if (unchanged) {
                p.skipped.add(m);
            } else {
                p.changed.add(new ChangeItem(m, target, remoteLabel, firstTime));
            }
        }

        p.remoteReadFailed = remoteReadFailed;

        // 排序：远端还没有的排最前（信息量最大）
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
     * 粗略校验 {vNNN} 形式。缓存里的值来自我们自己写入，但要防脏数据
     * （例如手动改库、或早期版本写错）导致 PATCH 一个非法 id。
     */
    private static boolean looksLikeVnId(String s) {
        if (s == null || s.length() < 2) return false;
        if (s.charAt(0) != 'v' && s.charAt(0) != 'V') return false;
        for (int i = 1; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) return false;
        }
        return true;
    }

    /**
     * 构建标题搜索的候选词，<b>按命中率排序</b>。
     * <p>
     * 顺序很重要：VNDB 是日语为核心的数据库。
     * 「西园家的秘密」这类中文译名在其库里根本不存在，搜了也是 0 候选；
     * 而本地元数据的 originalTitle 通常是日文原名，几乎一定能命中。
     * <p>
     * 顺序：原文原名 → 罗马字 → 本地标题（兜底）。
     * 去重后返回，避免同一个词搜两次。
     */
    static List<String> buildSearchCandidates(Game g, List<String> originals) {
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
        if (originals != null) {
            for (String s : originals) {
                if (s == null) continue;
                String v = s.trim();
                if (v.isEmpty() || "null".equalsIgnoreCase(v)) continue;
                seen.add(v);
            }
        }
        if (g != null && g.title != null) {
            String t = g.title.trim();
            if (!t.isEmpty()) seen.add(t);
        }
        return new ArrayList<>(seen);
    }

    /** 从远端 label 集合里挑出「状态类」的那一个（互斥，最多一个）。 */
    private static int currentStateLabel(java.util.Set<Integer> labels) {
        if (labels == null || labels.isEmpty()) return -1;
        int best = -1;
        for (int id : labels) {
            // 跳过 Blacklist 与虚拟的 Voted：它们不是「游玩状态」
            if (id == VndbClient.LABEL_BLACKLIST || id == VndbClient.LABEL_VOTED) continue;
            if (id < VndbClient.LABEL_PLAYING || id > VndbClient.LABEL_WISHLIST) continue;
            if (best < 0 || id < best) best = id;
        }
        return best;
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
                VndbClient.setState(token, c.matched.vnId, c.targetLabel);
                result.ok++;
                repo.updateVndbSyncState(c.matched.game.id, now, String.valueOf(c.targetLabel));
                result.markedSynced++;
            } catch (VndbClient.NotAuthorizedException | VndbClient.RateLimitedException e) {
                // 「整体性」错误：token 失效或已触发限流，继续跑只会全失败。
                // 直接中止，剩下的记为失败，把已完成的部分交给 UI。
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
                Log.w(TAG, "setState failed: " + c.matched.vnId, t);
            }
        }

        if (result.markedSynced > 0) {
            try {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit().putLong(KEY_LAST_SYNC_AT, now).apply();
            } catch (Throwable t) {
                Log.w(TAG, "write last sync time failed", t);
            }
        }

        // 回写「标题搜索」新解析出的映射：即使推送失败，匹配结果本身是有价值的
        // （下次同步就不必再搜，省请求也省配额）。
        if (preview.newlyResolved != null && !preview.newlyResolved.isEmpty()) {
            repo.updateVndbVnIds(preview.newlyResolved);
            Log.i(TAG, "cached " + preview.newlyResolved.size() + " title-resolved vn ids");
        }

        Log.i(TAG, "execute done: ok=" + result.ok + " fail=" + result.fail
                + " marked=" + result.markedSynced);
        return result;
    }

    private static String describeFailure(Throwable t) {
        String m = t.getMessage();
        if (m == null || m.trim().isEmpty()) return "同步失败";
        return m;
    }

    // ======================== token 存取 ========================

    /** 已保存的 VNDB token（供下次免输入）。 */
    public static String loadToken(Context context) {
        try {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getString(KEY_TOKEN, "");
        } catch (Throwable t) {
            return "";
        }
    }

    /** 保存 token。传空串表示解绑。 */
    public static void saveToken(Context context, String token) {
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().putString(KEY_TOKEN, token == null ? "" : token.trim()).apply();
        } catch (Throwable t) {
            Log.w(TAG, "save token failed", t);
        }
    }

    /** 是否已绑定（有 token）。纯本地判断，不联网。 */
    public static boolean isConnected(Context context) {
        return !loadToken(context).isEmpty();
    }

    /** 上次成功同步时间（首页状态小字用）。 */
    public static long lastSyncAt(Context context) {
        try {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getLong(KEY_LAST_SYNC_AT, 0L);
        } catch (Throwable t) {
            return 0L;
        }
    }
}