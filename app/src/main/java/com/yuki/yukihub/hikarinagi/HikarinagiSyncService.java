package com.yuki.yukihub.hikarinagi;

import android.content.Context;
import android.util.Log;

import com.yuki.yukihub.data.GameRepository;
import com.yuki.yukihub.model.Game;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Hikarinagi 游玩状态同步的业务层（状态 + 游玩时长）。
 * <p>
 * 设计目标与 NextMoe / VNDB / Bangumi 三个保持一致：
 * <ol>
 *   <li><b>只推有变化的条目</b>：靠本机签名比对，第二次同步零请求；</li>
 *   <li><b>预览与执行分离</b>：预览不写任何东西（含本地缓存），确认后才动；</li>
 *   <li><b>绝不动业务数据</b>：只写远端状态/时长 + 本地三个同步列。</li>
 * </ol>
 *
 * <h3>与另外三个源的关键差异</h3>
 * <ul>
 *   <li><b>能写时长</b>（VNDB 只能写状态；Bangumi 也写不了）；
 *       所以签名是「状态:分钟」，任一变化都会触发推送；</li>
 *   <li><b>能批量写</b>（一次最多 100 条），所以不像 VNDB/Bangumi 那样逐条往返，
 *       库大的用户也只有 1 次请求；</li>
 *   <li><b>状态是语义字符串</b>（GOING/COMPLETED/...），不是数字枚举。</li>
 * </ul>
 *
 * <h3>匹配策略（三级，与 Bangumi 一致）</h3>
 * <pre>
 *   第1级  cache     games.hikarinagi_galgame_id（上次搜索命中的结果，永久复用）
 *   第2级  metadata  metadata_cache 里 source='hikarinagi' 的 source_id（纯数字）
 *   第3级  title     用各源刮下来的正式标题搜：中文译名 > 原名 > 罗马字
 * </pre>
 * 第 3 级命中后把 id 写回 {@code games.hikarinagi_galgame_id}，之后每次走第 1 级零搜索。
 * <p>
 * 数据现状：本机 39 个有效状态游戏里，只有 <b>2</b> 个带 hikarinagi 锚点
 * （阳炎魔女 7691、千恋万花 780），所以第 3 级标题搜索是主力。
 */
public final class HikarinagiSyncService {

    private static final String TAG = "HikarinagiSync";

    private HikarinagiSyncService() { }

    // ======================== 状态映射 ========================

    /**
     * 本地 playStatus → Hikarinagi 状态字符串。
     * <pre>
     *   unplayed  -> PLAN       （已下载在库里的，按「想玩」处理）
     *   playing   -> GOING
     *   completed -> COMPLETED
     *   onhold    -> ON_HOLD
     *   dropped   -> DROPPED
     * </pre>
     * 注意这是<b>语义字符串</b>，与 Bangumi 的 1~5 数字枚举完全不同，
     * 千万别照抄那边的映射。
     *
     * @return 状态字符串；无法映射返回 null
     */
    public static String toHikarinagiStatus(String playStatus) {
        if (playStatus == null) return null;
        switch (playStatus.trim().toLowerCase()) {
            case "unplayed":  return HikarinagiSyncClient.STATUS_PLAN;
            case "playing":   return HikarinagiSyncClient.STATUS_GOING;
            case "completed": return HikarinagiSyncClient.STATUS_COMPLETED;
            case "onhold":    return HikarinagiSyncClient.STATUS_ON_HOLD;
            case "dropped":   return HikarinagiSyncClient.STATUS_DROPPED;
            default:          return null;
        }
    }

    /** 状态字符串 → 中文（UI 用）。 */
    public static String statusText(String status) {
        if (status == null) return "无";
        switch (status) {
            case HikarinagiSyncClient.STATUS_PLAN:      return "想玩";
            case HikarinagiSyncClient.STATUS_GOING:     return "在玩";
            case HikarinagiSyncClient.STATUS_COMPLETED: return "通关";
            case HikarinagiSyncClient.STATUS_ON_HOLD:   return "搁置";
            case HikarinagiSyncClient.STATUS_DROPPED:   return "弃坑";
            default:                                    return status;
        }
    }

    /**
     * 本地毫秒 → 分钟（按服务端上限截断）。
     * <p>
     * 本地 {@code totalPlayTime} 是毫秒，Hikarinagi 要的是分钟，差 60 倍，
     * 换算错了会把时长写成天文数字，所以单独抽出来便于测试。
     */
    public static int toMinutes(long totalPlayTimeMs) {
        long minutes = totalPlayTimeMs / 60_000L;
        if (minutes < 0) return 0;
        return (int) Math.min(minutes, HikarinagiSyncClient.MAX_MINUTES);
    }

    /**
     * 内容签名：判断「自上次同步以来状态或时长有没有变」。
     * <p>
     * 形态 {@code "GOING:126"}，与 NextMoe 的 {@code "doing:210"} 一致。
     * 时长变化也必须触发推送——这正是本功能比 VNDB / Bangumi 多出来的能力。
     */
    public static String signatureOf(Game g) {
        if (g == null) return "";
        String status = toHikarinagiStatus(g.playStatus);
        if (status == null) return "";
        return status + ":" + toMinutes(g.totalPlayTime);
    }

    // ======================== 数据结构 ========================

    /** 一条匹配好的待同步项。 */
    public static class Matched {
        public final Game game;
        /** Hikarinagi galgame id。 */
        public final long galgameId;
        /** 匹配来源：cache / metadata / title，仅用于日志与排错展示。 */
        public final String source;
        /**
         * 强制重同步时，本次重新匹配得到的新 id。
         * <p>
         * 与 {@link #galgameId} 不同时说明「换了条目」，UI 要如实展示，
         * 让用户能看出重新匹配导致了什么变化，而不是悄悄覆盖。
         * 非强制模式下恒为 {@link #galgameId}。
         */
        public final long previousId;

        public Matched(Game game, long galgameId, String source) {
            this(game, galgameId, source, galgameId);
        }

        public Matched(Game game, long galgameId, String source, long previousId) {
            this.game = game;
            this.galgameId = galgameId;
            this.source = source;
            this.previousId = previousId;
        }

        /** 重新匹配后 id 是否发生了变化（仅强制重同步时可能为 true）。 */
        public boolean idChanged() {
            return previousId > 0L && previousId != galgameId;
        }
    }

    /** 一条待推送的变化明细。 */
    public static class ChangeItem {
        public final Matched matched;
        public final String status;
        public final int minutes;
        /** true 表示这款游戏是第一次同步（没有旧签名）。 */
        public final boolean firstTime;
        /** 非 null 表示这条代表了一组指向同一作品的本地条目（已合并）。 */
        public final DuplicateGroup duplicate;

        public ChangeItem(Matched matched, String status, int minutes, boolean firstTime) {
            this(matched, status, minutes, firstTime, null);
        }

        public ChangeItem(Matched matched, String status, int minutes, boolean firstTime,
                          DuplicateGroup duplicate) {
            this.matched = matched;
            this.status = status;
            this.minutes = minutes;
            this.firstTime = firstTime;
            this.duplicate = duplicate;
        }
    }
    /** 预览：还没写任何东西。 */
    public static class Preview {
        public final List<Matched> items = new ArrayList<>();
        public final List<Game> unmatched = new ArrayList<>();
        /** 本次标题搜索新解析出的映射，预览确认后回写缓存（下次零搜索）。 */
        public final Map<Long, Long> newlyResolved = new LinkedHashMap<>();
        public final List<ChangeItem> changed = new ArrayList<>();
        public final List<Matched> skipped = new ArrayList<>();
        /**
         * 本地多个条目指向同一部作品时被合并的组。
         * <p>
         * 常见于「HD 版」与「原版」分别入库，或同一部游戏用不同引擎各装一份。
         * Hikarinagi 上只有<b>一个</b>条目，而批量接口规定「同一作品不能重复出现」，
         * 一条重复就会让整批 422 全部失败，所以必须先在本地合并。
         */
        public final List<DuplicateGroup> duplicates = new ArrayList<>();

        /** 连接信息（展示用）。 */
        public String username = "";

        public int changesCount = 0;
        public int skippedCount = 0;

        /** 被并入别条、本次不会单独写入的本地条目总数。 */
        public int mergedCount() {
            int n = 0;
            for (DuplicateGroup g : duplicates) n += g.dropped.size();
            return n;
        }

        public boolean isEmpty() {
            return items.isEmpty();
        }
    }

    /**
     * 一组指向同一部 Hikarinagi 作品的本地条目。
     * <p>
     * {@code canonical} 是代表项（真正会被写入的那条），
     * {@code dropped} 是并入它的其余本地条目。
     */
    public static class DuplicateGroup {
        public final long galgameId;
        public final Matched canonical;
        public final List<Matched> dropped = new ArrayList<>();

        public DuplicateGroup(long galgameId, Matched canonical) {
            this.galgameId = galgameId;
            this.canonical = canonical;
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
        public int requests = 0;
        public final List<ItemFailure> failures = new ArrayList<>();
    }

    /** 匹配进度回调（UI 用）。 */
    public interface ResolveProgress {
        void onResolveProgress(int done, int total);
    }

    /**
     * 令牌刷新失败，但<b>凭据仍在、可以重试</b>。
     * <p>
     * 与 {@link HikarinagiSyncClient.NotAuthorizedException} 的区别：
     * 那个表示「授权真的没了，必须重新授权」，这个表示「这次没刷成，
     * 排查网络后重试即可」。UI 据此给不同按钮（重试 vs 重新授权）。
     */
    public static class TokenRefreshFailedException extends Exception {
        public TokenRefreshFailedException(String message) { super(message); }
    }

    // ======================== 阶段 1：只读匹配 ========================

    /**
     * 收集并匹配，不写任何数据（本地缓存也留到 execute 成功后再写）。
     * <p>
     * 等价于 {@code prepare(context, false, progress)}。
     */
    public static Preview prepare(Context context, ResolveProgress progress) throws Exception {
        return prepare(context, false, progress);
    }

    /**
     * 收集并匹配，不写任何数据（本地缓存也留到 execute 成功后再写）。
     *
     * @param forceResync 强制重推：为 true 时<b>忽略本地签名</b>，把所有能匹配上的条目
     *                    都算作「有变化」，全部重新写入远端。
     *                    <p>
     *                    这是必需品而非可选项：本地签名只能证明「我这边数据没变」，
     *                    无法证明「云端确实收到了」。远端数据被改动、回档、单条写入
     *                    曾经失败过等情况，签名是看不出来的——那时界面会一直显示
     *                    「全部已是最新，无需同步」，用户就彻底没办法把数据推上去。
     */
    public static Preview prepare(Context context, boolean forceResync,
                                  ResolveProgress progress) throws Exception {
        // 校验令牌并取账号名。用主线程之外的线程调用。
        String token = HikarinagiAuthStore.ensureAccessToken(context);
        if (token == null || token.trim().isEmpty()) {
            // 令牌拿不到要区分两种情况，提示完全不同：
            //   能自救（有 refresh token）→ 多半是网络问题，让用户重试，别重新授权
            //   不能自救（没 refresh token）→ 只能重新授权
            if (HikarinagiAuthStore.canRecover(context)) {
                throw new TokenRefreshFailedException(
                        "刷新登录状态失败，可能是网络问题。你的 Hikarinagi 授权仍然保留，"
                                + "请检查网络后重试，不需要重新授权。");
            }
            throw new HikarinagiSyncClient.NotAuthorizedException("Hikarinagi 授权已失效，请重新授权");
        }

        // 取账号名只用于展示，失败不该拦住同步：
        // /user/me 只是只读校验，而真正的写入走 rates/galgames/batch，
        // 两者权限与故障面都不一样。这里降级为「用缓存的名字」而不是抛错。
        String displayName = HikarinagiAuthStore.getAccountLabel(context);
        try {
            HikarinagiSyncClient.AuthInfo auth = HikarinagiSyncClient.authInfo(token);
            displayName = auth.displayName();
            HikarinagiAuthStore.setAccountLabel(context, displayName);
        } catch (HikarinagiSyncClient.NotAuthorizedException e) {
            // 只有 name/nickname 完全缺失才走到这里，说明令牌确实不可用
            throw e;
        } catch (Throwable t) {
            Log.w(TAG, "取账号名失败，继续同步（仅影响展示）", t);
        }

        GameRepository repo = new GameRepository(context);
        List<Game> all = repo.getAll();

        // 第1级：本机缓存（上次搜索命中的 galgame id）
        Map<Long, Long> cachedIds = new LinkedHashMap<>();
        for (Game g : all) {
            if (g.hikarinagiGalgameId > 0L) cachedIds.put(g.id, g.hikarinagiGalgameId);
        }

        // 第2级：metadata_cache 里 source='hikarinagi' 的 source_id
        Map<Long, Long> metadataIds = repo.getHikarinagiSourceIds();

        // 强制重同步的语义（重要，别理解错）：
        //   它要解决的是「云端数据可能被改动/曾经写入失败，本地却以为已同步」，
        //   属于<b>重新推送</b>，不是<b>重新匹配</b>。
        //   所以第 1、2 级锚点<b>照常使用</b>——那些是已经验证过的正确映射，
        //   丢掉它们只会让本来匹配对的条目重新乱猜（实测曾把
        //   《星空的记忆 Eternal Heart》从正确的 68 认成 2202）。
        //   唯一的区别在后面：签名比对被绕过，所有 matched 都算「有变化」。
        // （早期版本在这里清空了缓存，是错的，已回退。）

        List<Matched> matched = new ArrayList<>();
        List<Game> needTitleSearch = new ArrayList<>();

        for (Game g : all) {
            String status = toHikarinagiStatus(g.playStatus);
            // 状态无法映射（空值 / 未知值）的不上报，避免把用户数据写坏
            if (status == null) continue;

            Long fromCache = cachedIds.get(g.id);
            if (fromCache != null && fromCache > 0L) {
                matched.add(new Matched(g, fromCache, "cache"));
                continue;
            }
            Long fromMeta = metadataIds.get(g.id);
            if (fromMeta != null && fromMeta > 0L) {
                matched.add(new Matched(g, fromMeta, "metadata"));
                continue;
            }
            needTitleSearch.add(g);
        }

        // 第3级：标题搜索。候选词来自各源刮下来的正式标题（中文译名优先）
        List<Game> unmatched = new ArrayList<>();
        Map<Long, Long> newlyResolved = new LinkedHashMap<>();
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
                        Long id = HikarinagiSyncClient.searchGalgameId(candidates);
                        if (id == null || id <= 0L) {
                            unmatched.add(g);
                        } else {
                            // 重新搜到的新 id；previousId 保留原来缓存里的值（若有），
                            // 供 UI 展示「原 2044 → 现 2046」这种变化。
                            long prev = g.hikarinagiGalgameId;
                            matched.add(new Matched(g, id, "title", prev));
                            newlyResolved.put(g.id, id);
                        }
                    } catch (HikarinagiSyncClient.RateLimitedException e) {
                        // 整体性错误：已限流，继续搜只会全失败
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
        p.username = displayName;

        // ===== 去重：多个本地条目指向同一部作品时合并 =====
        // 批量接口硬性规定「同一作品不能重复出现」，违反会让整批 422 全部失败
        // （实测：39 个条目一条都没写进去）。所以必须在发请求前就在本地合并。
        Map<Long, List<Matched>> byGalgame = new LinkedHashMap<>();
        for (Matched m : matched) {
            List<Matched> bucket = byGalgame.get(m.galgameId);
            if (bucket == null) {
                bucket = new ArrayList<>(2);
                byGalgame.put(m.galgameId, bucket);
            }
            bucket.add(m);
        }

        List<Matched> effective = new ArrayList<>(matched.size());
        Map<Long, DuplicateGroup> groupOf = new LinkedHashMap<>();
        for (Map.Entry<Long, List<Matched>> e : byGalgame.entrySet()) {
            List<Matched> bucket = e.getValue();
            if (bucket.size() == 1) {
                effective.add(bucket.get(0));
                continue;
            }
            Matched canonical = pickCanonical(bucket);
            DuplicateGroup dg = new DuplicateGroup(e.getKey(), canonical);
            for (Matched m : bucket) {
                if (m != canonical) dg.dropped.add(m);
            }
            p.duplicates.add(dg);
            groupOf.put(canonical.galgameId, dg);
            effective.add(canonical);
            Log.i(TAG, "dup galgame_id=" + e.getKey() + " 合并 " + bucket.size()
                    + " 个本地条目 -> " + safeTitle(canonical.game));
        }

        p.items.addAll(effective);
        p.unmatched.addAll(unmatched);
        p.newlyResolved.putAll(newlyResolved);

        // 判断哪些需要写：纯本地签名比对，不发任何读请求
        for (Matched m : effective) {
            DuplicateGroup dg = groupOf.get(m.galgameId);
            // 合并组的状态/时长必须取「合并后」的值，不能只用代表项自己的，
            // 否则可能把组里进度更深的那条丢掉（例：一个通关、一个想玩）。
            String status = dg == null
                    ? toHikarinagiStatus(m.game.playStatus)
                    : mergedStatus(dg);
            if (status == null) continue;
            int minutes = dg == null ? toMinutes(m.game.totalPlayTime) : mergedMinutes(dg);

            String sig = status + ":" + minutes;
            boolean syncedBefore = m.game.hikarinagiSyncedAt > 0;
            // 强制重推时，一律视为「首次」，全部进 changed；签名比对被绕过。
            boolean firstTime = forceResync || !syncedBefore;
            boolean unchanged = !forceResync && syncedBefore
                    && sig.equals(m.game.hikarinagiSyncedSig);

            if (unchanged) {
                p.skipped.add(m);
            } else {
                p.changed.add(new ChangeItem(m, status, minutes, firstTime, dg));
            }
        }

        // 排序：首次同步的排最前（信息量最大）
        java.util.Collections.sort(p.changed, (a, b) -> {
            if (a.firstTime != b.firstTime) return a.firstTime ? -1 : 1;
            return 0;
        });

        p.changesCount = p.changed.size();
        p.skippedCount = p.skipped.size();

        Log.i(TAG, "prepare: matched=" + matched.size() + " effective=" + effective.size()
                + " dupGroups=" + p.duplicates.size() + " unmatched=" + unmatched.size()
                + " changes=" + p.changesCount + " skipped=" + p.skippedCount);
        return p;
    }

    /**
     * 构建标题搜索的候选词。
     *
     * <h3>只用「已匹配源刮下来的正式标题」，绝不用本地游戏名</h3>
     * {@code games.title} 是用户自己起的名字，可能带「【Tyranor模拟器】」这类
     * 噪声前缀，也可能根本不是正式译名。拿它去搜既不准也不干净。
     * <p>
     * 正确来源是 {@code metadata_cache}：那是从 VNDB / NextMoe / Bangumi 等源
     * 刮下来的正式条目名，每个源都会给 {@code chineseTitle}（中文译名）、
     * {@code originalTitle}（原名）、{@code romanTitle}（罗马字）。
     * <p>
     * 顺序：<b>中文译名优先</b> → 原名 → 罗马字。
     * 因为 Hikarinagi 的搜索返回 {@code title} 是「有译名时优先返回译名」，
     * 用中文译名去搜才能与返回值精确相等，一次命中。
     * （这点与 VNDB/Bangumi 相反，那两个源的条目名是原名。）
     *
     * @param titles 来自 {@code GameRepository.getSearchTitles}，已按上述顺序排好
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

    // ======================== 去重与合并规则 ========================

    /**
     * 本地状态 → 「进度」排序权重，越大越靠前（越「玩过」）。
     * <p>
     * 合并时状态取<b>进度最深</b>的那个：同一部作品你在一个条目里通关、
     * 另一个条目里还是「想玩」，真正的事实是通关。
     * 取最浅的会把已通关刷回「想玩」，属于毁数据。
     */
    public static int statusRank(String status) {
        if (status == null) return -1;
        switch (status) {
            case HikarinagiSyncClient.STATUS_COMPLETED: return 4;
            case HikarinagiSyncClient.STATUS_GOING:     return 3;
            case HikarinagiSyncClient.STATUS_ON_HOLD:   return 2;
            case HikarinagiSyncClient.STATUS_DROPPED:   return 1;
            case HikarinagiSyncClient.STATUS_PLAN:      return 0;
            default:                                    return -1;
        }
    }

    /**
     * 从一组指向同一作品的本地条目里，选出代表项。
     * <p>
     * 排序依据（依次比较）：
     * <ol>
     *   <li><b>进度更深</b>的优先（通关 &gt; 在玩 &gt; 搁置 &gt; 弃坑 &gt; 想玩）</li>
     *   <li>已同步过的优先（避免把已有签名的那条丢成「未同步」）</li>
     *   <li>时长更长的优先</li>
     *   <li>最后按本地 id 升序，保证结果稳定可复现</li>
     * </ol>
     */
    static Matched pickCanonical(List<Matched> bucket) {
        Matched best = null;
        for (Matched m : bucket) {
            if (best == null) {
                best = m;
                continue;
            }
            int r1 = statusRank(toHikarinagiStatus(m.game.playStatus));
            int r2 = statusRank(toHikarinagiStatus(best.game.playStatus));
            if (r1 != r2) {
                if (r1 > r2) best = m;
                continue;
            }
            boolean s1 = m.game.hikarinagiSyncedAt > 0;
            boolean s2 = best.game.hikarinagiSyncedAt > 0;
            if (s1 != s2) {
                if (s1) best = m;
                continue;
            }
            long t1 = m.game.totalPlayTime;
            long t2 = best.game.totalPlayTime;
            if (t1 != t2) {
                if (t1 > t2) best = m;
                continue;
            }
            if (m.game.id < best.game.id) best = m;
        }
        return best;
    }

    /**
     * 合并后的状态：取这一组里进度<b>最深</b>的那个状态。
     * <p>
     * 代表项本身就是按「进度最深」选出来的，所以直接用它即可；
     * 但 {@link #pickCanonical} 的后几个比较项可能把代表项换成进度相同的另一条，
     * 因此这里独立计算一遍，保证「状态」与「时长」两个维度互不干扰。
     */
    static String mergedStatus(DuplicateGroup dg) {
        if (dg == null) return null;
        String best = toHikarinagiStatus(dg.canonical.game.playStatus);
        int bestRank = statusRank(best);
        for (Matched m : dg.dropped) {
            String s = toHikarinagiStatus(m.game.playStatus);
            int r = statusRank(s);
            if (r > bestRank) {
                bestRank = r;
                best = s;
            }
        }
        return best;
    }

    /**
     * 合并后的时长：<b>取最大值，不是相加</b>。
     * <p>
     * 「HD 版 + 原版」是两个可独立启动的安装，同一个游戏时间通常只记在其中一边，
     * 相加会把时长翻倍，写到远端就是错数据。取最大值是保守且不会虚增的做法。
     */
    static int mergedMinutes(DuplicateGroup dg) {
        if (dg == null) return 0;
        int best = toMinutes(dg.canonical.game.totalPlayTime);
        for (Matched m : dg.dropped) {
            int v = toMinutes(m.game.totalPlayTime);
            if (v > best) best = v;
        }
        return best;
    }

    // ======================== 阶段 2：执行推送 ========================

    /**
     * 批量推送。只写远端 + 本地三个同步状态列，绝不改 playStatus / totalPlayTime 等业务数据。
     * <p>
     * 分批规则：{@link HikarinagiSyncClient#MAX_BATCH} 条一批，
     * 每批结果与请求顺序一一对应，单条失败只影响自己，不中断整批。
     */
    public static Result execute(Context context, Preview preview) throws Exception {
        Result result = new Result();
        if (preview == null) return result;
        result.unmatched = preview.unmatched.size();
        if (preview.changed.isEmpty()) {
            Log.i(TAG, "execute: nothing to push");
            return result;
        }

        String token = HikarinagiAuthStore.ensureAccessToken(context);
        if (token == null || token.trim().isEmpty()) {
            // 与 prepare 一致：能自救就说「重试」，自救不了才说「重新授权」
            if (HikarinagiAuthStore.canRecover(context)) {
                throw new TokenRefreshFailedException(
                        "刷新登录状态失败，可能是网络问题。你的 Hikarinagi 授权仍然保留，"
                                + "请检查网络后重试，不需要重新授权。");
            }
            throw new HikarinagiSyncClient.NotAuthorizedException("Hikarinagi 授权已失效，请重新授权");
        }

        GameRepository repo = new GameRepository(context);
        long now = System.currentTimeMillis();

        int total = preview.changed.size();
        for (int start = 0; start < total; start += HikarinagiSyncClient.MAX_BATCH) {
            int end = Math.min(start + HikarinagiSyncClient.MAX_BATCH, total);
            List<ChangeItem> chunk = preview.changed.subList(start, end);

            List<HikarinagiSyncClient.RateItem> batch = new ArrayList<>(chunk.size());
            for (ChangeItem c : chunk) {
                batch.add(new HikarinagiSyncClient.RateItem(
                        c.matched.galgameId, c.status, c.minutes));
            }

            List<HikarinagiSyncClient.BatchItemResult> res;
            try {
                result.requests++;
                res = HikarinagiSyncClient.setRatesBatch(token, batch);
            } catch (HikarinagiSyncClient.NotAuthorizedException e) {
                // 401/403：令牌问题，重试一次刷新后再发，仍失败则整体中止
                Log.w(TAG, "batch 未授权，尝试刷新令牌后重试", e);
                String fresh = HikarinagiAuthStore.refresh(context, true);
                if (fresh == null) {
                    int remaining = total - start;
                    result.fail += remaining;
                    result.failures.add(new ItemFailure("（已中止后续 " + remaining + " 个）", e.getMessage()));
                    break;
                }
                token = fresh;
                result.requests++;
                res = HikarinagiSyncClient.setRatesBatch(token, batch);
            } catch (HikarinagiSyncClient.RateLimitedException e) {
                int remaining = total - start;
                result.fail += remaining;
                result.failures.add(new ItemFailure("（已中止后续 " + remaining + " 个）", e.getMessage()));
                Log.w(TAG, "abort remaining due to rate limit");
                break;
            } catch (HikarinagiSyncClient.ValidationException e) {
                // 422 是整批拒绝：本批一条没写，但之前已成功的批次不作废。
                // 记录原因后中止，把「前面成功的」如实报给用户，而不是整次同步算失败。
                int remaining = total - start;
                result.fail += remaining;
                result.failures.add(new ItemFailure("（已中止后续 " + remaining + " 个）", e.getMessage()));
                Log.w(TAG, "abort remaining due to validation failure: " + e.getMessage());
                break;
            }

            // 逐条记账：成功的才写签名，失败的保留旧签名以便下次重试
            for (int i = 0; i < chunk.size(); i++) {
                ChangeItem c = chunk.get(i);
                HikarinagiSyncClient.BatchItemResult r = i < res.size() ? res.get(i) : null;
                if (r != null && r.ok) {
                    result.ok++;
                    try {
                        repo.updateHikarinagiSyncState(c.matched.game.id, now,
                                c.status + ":" + c.minutes);
                    } catch (Throwable t) {
                        Log.w(TAG, "write local sync state failed", t);
                    }
                } else {
                    result.fail++;
                    result.failures.add(new ItemFailure(safeTitle(c.matched.game),
                            r == null ? "服务端未返回该条结果" : r.message));
                }
            }
        }

        if (result.ok > 0) {
            try {
                HikarinagiAuthStore.markSyncedAt(context, now);
            } catch (Throwable t) {
                Log.w(TAG, "write last sync time failed", t);
            }
            // 本次新解析出的 id 一并缓存，下次免搜索
            try {
                if (!preview.newlyResolved.isEmpty()) {
                    repo.updateHikarinagiGalgameIds(preview.newlyResolved);
                }
            } catch (Throwable t) {
                Log.w(TAG, "cache resolved galgame ids failed", t);
            }
        }
        Log.i(TAG, "execute done: ok=" + result.ok + " fail=" + result.fail
                + " requests=" + result.requests);
        return result;
    }

    private static String safeTitle(Game g) {
        if (g == null) return "未命名游戏";
        if (g.title == null || g.title.trim().isEmpty()) return "未命名游戏";
        return g.title;
    }
}