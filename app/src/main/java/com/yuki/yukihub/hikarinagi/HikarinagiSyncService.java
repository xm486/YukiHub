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
 *   第3级  title     原名 > 罗马字 > 本地标题，精确相等优先、包含须唯一
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

        public Matched(Game game, long galgameId, String source) {
            this.game = game;
            this.galgameId = galgameId;
            this.source = source;
        }
    }

    /** 一条待推送的变化明细。 */
    public static class ChangeItem {
        public final Matched matched;
        public final String status;
        public final int minutes;
        /** true 表示这款游戏是第一次同步（没有旧签名）。 */
        public final boolean firstTime;

        public ChangeItem(Matched matched, String status, int minutes, boolean firstTime) {
            this.matched = matched;
            this.status = status;
            this.minutes = minutes;
            this.firstTime = firstTime;
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
        public int unmatched = 0;
        public int requests = 0;
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
    public static Preview prepare(Context context, ResolveProgress progress) throws Exception {
        // 校验令牌并取账号名。用主线程之外的线程调用。
        String token = HikarinagiAuthStore.ensureAccessToken(context);
        if (token == null || token.trim().isEmpty()) {
            throw new HikarinagiSyncClient.NotAuthorizedException("Hikarinagi 授权已失效，请重新授权");
        }
        HikarinagiSyncClient.AuthInfo auth = HikarinagiSyncClient.authInfo(token);
        HikarinagiAuthStore.setAccountLabel(context, auth.displayName());

        GameRepository repo = new GameRepository(context);
        List<Game> all = repo.getAll();

        // 第1级：本机缓存（上次搜索命中的 galgame id）
        Map<Long, Long> cachedIds = new LinkedHashMap<>();
        for (Game g : all) {
            if (g.hikarinagiGalgameId > 0L) cachedIds.put(g.id, g.hikarinagiGalgameId);
        }

        // 第2级：metadata_cache 里 source='hikarinagi' 的 source_id
        Map<Long, Long> metadataIds = repo.getHikarinagiSourceIds();

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

        // 第3级：标题搜索。候选词按「原名 > 罗马字 > 本地标题」排序。
        List<Game> unmatched = new ArrayList<>();
        Map<Long, Long> newlyResolved = new LinkedHashMap<>();
        if (!needTitleSearch.isEmpty()) {
            // 只在这几个游戏确实要搜时才去解析原名，避免全表 JSON 解析
            Map<Long, List<String>> originalTitles = repo.getOriginalTitles(needTitleSearch);

            int done = matched.size();
            int total = all.size();
            for (Game g : needTitleSearch) {
                List<String> candidates = buildSearchCandidates(g, originalTitles.get(g.id));
                if (candidates.isEmpty()) {
                    unmatched.add(g);
                } else {
                    try {
                        Long id = HikarinagiSyncClient.searchGalgameId(candidates);
                        if (id == null || id <= 0L) {
                            unmatched.add(g);
                        } else {
                            matched.add(new Matched(g, id, "title"));
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
        p.username = auth.displayName();
        p.items.addAll(matched);
        p.unmatched.addAll(unmatched);
        p.newlyResolved.putAll(newlyResolved);

        // 判断哪些需要写：纯本地签名比对，不发任何读请求
        for (Matched m : matched) {
            String status = toHikarinagiStatus(m.game.playStatus);
            if (status == null) continue;
            int minutes = toMinutes(m.game.totalPlayTime);

            String sig = status + ":" + minutes;
            boolean syncedBefore = m.game.hikarinagiSyncedAt > 0;
            boolean firstTime = !syncedBefore;
            boolean unchanged = syncedBefore && sig.equals(m.game.hikarinagiSyncedSig);

            if (unchanged) {
                p.skipped.add(m);
            } else {
                p.changed.add(new ChangeItem(m, status, minutes, firstTime));
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
     * 构建标题搜索的候选词，按命中率排序。
     * <p>
     * 顺序：原文原名 → 罗马字 → 本地标题（兜底）。
     * Hikarinagi 的条目名多为日文原名，本地标题常是汉化组起的名字，命中率最低。
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