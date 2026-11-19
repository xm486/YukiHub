package com.yuki.yukihub.nextmoe;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import com.yuki.yukihub.data.YukiDatabaseHelper;
import com.yuki.yukihub.model.Game;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 把本地游戏解析成 NextMoe work_id。
 * <p>
 * 采用四级策略，逐级降级，全部失败就进「无法匹配」列表（由 UI 明确展示，绝不静默丢弃）：
 * <ol>
 *   <li><b>已缓存</b>：{@code games.nextmoe_work_id} 非空，直接用（零请求）。</li>
 *   <li><b>NextMoe 源</b>：{@code metadata_cache} 里 source='nextmoe' 的 source_id 就是 work_id（零请求）。</li>
 *   <li><b>外部锚反查</b>：vndb / bangumi id 走 {@code /catalog/works?refs=...&nsfw=true}
 *       批量反查，每 100 个一次请求。</li>
 *   <li><b>标题精确搜索</b>：前三级都没命中的，用<b>元数据里的正式标题</b>
 *       （中文译名 > 原名 > 罗马字，不是 games.title）走 {@code /catalog/search}。
 *       只接受<b>归一化后完全相等</b>的命中（见 {@link NextMoeMeClient#normalizeTitle}），
 *       因为搜索是模糊的——「千恋万花」会同时返回「涙恋花」，
 *       取第一条就会把时长写到不相干的作品上，比不同步更糟。
 *       这一级逐个候选词试、命中即停，所以只处理前面失败的少量条目。</li>
 * </ol>
 * <p>
 * 第 4 级是必要的：只用 ymgal / hikarinagi 源刮削的游戏拿不到 NextMoe 认的 ref，
 * 但它们的标题往往就是 NextMoe 的 localized 值，标题搜索能让它们对上。
 */
public final class NextMoeWorkResolver {

    private static final String TAG = "NextMoeResolve";

    /** 能参与 refs 反查的元数据源；值与 NextMoe 的 refs source 前缀一致。 */
    private static final String[] REF_SOURCES = {"vndb", "bangumi"};

    private NextMoeWorkResolver() { }

    // ======================== 数据结构 ========================

    public static class ResolveResult {
        public final List<Matched> matched = new ArrayList<>();
        public final List<Game> unmatched = new ArrayList<>();
        /** 本次新解析出、需要回写缓存的映射（gameId → workId）。 */
        public final Map<Long, String> newlyResolved = new LinkedHashMap<>();
    }

    public static class Matched {
        public final Game game;
        public final String workId;
        /** nextmoe / vndb / bangumi / cache，仅用于日志与排错展示。 */
        public final String via;
        /**
         * 强制重同步时，重新匹配前用的 work_id。
         * 与 {@link #workId} 不同时说明「换了条目」，UI 要如实展示。
         * 非强制模式下恒为空串（无从比较，也不需要比较）。
         */
        public final String previousId;

        public Matched(Game game, String workId, String via) {
            this(game, workId, via, "");
        }

        /**
         * @param previousId 重新匹配前用的 work_id（强制重同步时用于展示变化）
         */
        public Matched(Game game, String workId, String via, String previousId) {
            this.game = game;
            this.workId = workId;
            this.via = via;
            this.previousId = previousId == null ? "" : previousId;
        }

        /** 重新匹配后 id 是否发生了变化（仅强制重同步时可能为 true）。 */
        public boolean idChanged() {
            String cur = workId == null ? "" : workId.trim();
            String prev = previousId.trim();
            return !prev.isEmpty() && !prev.equals(cur);
        }
    }

    /** 解析进度回调（在调用线程触发，调用方自己切主线程）。 */
    public interface Progress {
        void onProgress(int done, int total);
    }

    // ======================== 主流程 ========================

    public static ResolveResult resolve(Context context, List<Game> games, Progress progress)
            throws Exception {
        return resolve(context, games, false, progress);
    }

    /**
     * @param forceResync 强制重同步 = <b>重新匹配 + 重新推送</b>：
     *                    <ul>
     *                      <li>丢弃第 1 级的 {@code games.nextmoe_work_id}
     *                          —— 那是我们自己搜出来的结果，可能搜错；</li>
     *                      <li>保留第 2 级 metadata 锚点、第 3 级外部锚反查、第 4 级标题搜索
     *                          —— 这些都是可信的匹配手段，规则不动；</li>
     *                      <li>调用方拿到 matched 后不做签名比对，全部重新推送。</li>
     *                    </ul>
     *                    <p>
     *                    关键取舍：只丢「自搜缓存」，不丢「站点锚点」。
     *                    站点锚点是刮削时从该站直接拿到的 id，不存在搜错的问题；
     *                    丢掉它只会把本来匹配对的条目重新乱猜。
     */
    public static ResolveResult resolve(Context context, List<Game> games,
                                        boolean forceResync, Progress progress)
            throws Exception {
        ResolveResult result = new ResolveResult();
        if (games == null || games.isEmpty()) return result;

        int total = games.size();
        int done = 0;

        // ---- 第 1 级：已缓存的 work_id（<b>我们自己搜的</b>结果）----
        List<Game> pending = new ArrayList<>();
        // 进度节流：这一层是纯内存遍历，几乎瞬间完成。
        // 如果每个游戏都回调一次，上千个游戏会上千次 post 到主线程刷 ProgressDialog，
        // 轻则掉帧，重则把消息队列堵住。改为每 25 个报一次。
        int lastReported = -1;
        for (Game g : games) {
            String cached = g.nextmoeWorkId == null ? "" : g.nextmoeWorkId.trim();
            // 强制重同步 = 重新匹配 + 重新推送：
            //   丢掉「我们自己搜的 work_id」，让条目重新走后面的匹配；
            //   但保留第 2 级的 metadata 锚点与第 3 级的外部锚反查（那些是可信数据）。
            if (!forceResync && !cached.isEmpty()) {
                result.matched.add(new Matched(g, cached, "cache"));
            } else {
                pending.add(g);
            }
            done++;
            if (progress != null && (done % 25 == 0 || done == total)) {
                if (done != lastReported) {
                    lastReported = done;
                    progress.onProgress(done, total);
                }
            }
        }
        if (pending.isEmpty()) return result;

        // ---- 第 2 级：metadata_cache 里 source='nextmoe'（刮削锚点，可信，始终保留）----
        Map<Long, String> nextMoeIds = readCachedSourceIds(context, idsOf(pending), "nextmoe");
        List<Game> stillPending = new ArrayList<>();
        for (Game g : pending) {
            String id = nextMoeIds.get(g.id);
            if (id != null && !id.trim().isEmpty()) {
                result.matched.add(new Matched(g, id.trim(), "nextmoe"));
                result.newlyResolved.put(g.id, id.trim());
            } else {
                stillPending.add(g);
            }
        }
        if (stillPending.isEmpty()) {
            if (progress != null) progress.onProgress(total, total);
            return result;
        }

        // ---- 第 3 级：vndb / bangumi 外部锚批量反查 ----
        Map<String, Long> refToGame = new LinkedHashMap<>();
        Set<Long> pendingIds = new HashSet<>(idsOf(stillPending));
        Set<Long> usedGameIds = new HashSet<>();

        for (String source : REF_SOURCES) {
            Map<Long, String> sourceIds = readCachedSourceIds(context, pendingIds, source);
            for (Map.Entry<Long, String> e : sourceIds.entrySet()) {
                Long gameId = e.getKey();
                String sid = e.getValue();
                if (sid == null) continue;
                sid = sid.trim();
                if (sid.isEmpty()) continue;
                // 一个游戏只用一个锚去反查：vndb 优先（覆盖更全），bangumi 兜底
                if (usedGameIds.contains(gameId)) continue;
                // vndb 的 id 在目录里以 "vNNN"（原作）/ "rNNN"（发行）形式存在，只要原作
                if ("vndb".equals(source) && !sid.startsWith("v")) continue;
                String ref = source + ":" + sid;
                if (refToGame.containsKey(ref)) continue;   // 同一条 ref 不重复请求
                refToGame.put(ref, gameId);
                usedGameIds.add(gameId);
            }
        }

        if (!refToGame.isEmpty()) {
            List<String> allRefs = new ArrayList<>(refToGame.keySet());
            // 每 100 个一批，这是服务端的硬上限（TOO_MANY_IDS）
            for (int start = 0; start < allRefs.size(); start += NextMoeMeClient.MAX_BATCH) {
                int end = Math.min(start + NextMoeMeClient.MAX_BATCH, allRefs.size());
                List<String> chunk = new ArrayList<>(allRefs.subList(start, end));
                Map<String, String> hit;
                try {
                    hit = NextMoeMeClient.resolveRefs(chunk);
                } catch (NextMoeMeClient.QuotaExceededException q) {
                    // 配额用尽：停止继续反查，把剩下的都算未匹配，把原因往上抛
                    Log.w(TAG, "quota exceeded while resolving refs", q);
                    throw q;
                }
                Set<Long> resolvedGameIds = new HashSet<>();
                for (Map.Entry<String, String> e : hit.entrySet()) {
                    Long gameId = refToGame.get(e.getKey());
                    if (gameId == null) continue;
                    if (resolvedGameIds.contains(gameId)) continue;  // 一个游戏只认一次
                    resolvedGameIds.add(gameId);
                    Game g = findById(stillPending, gameId);
                    if (g == null) continue;
                    String prev = g.nextmoeWorkId == null ? "" : g.nextmoeWorkId.trim();
                    result.matched.add(new Matched(g, e.getValue(), linkSourceOf(e.getKey()), prev));
                    result.newlyResolved.put(gameId, e.getValue());
                }
            }
        }

        // 没有解析出来的，进 unmatched（由 UI 明确展示）
        Set<Long> matchedIds = new HashSet<>();
        for (Matched m : result.matched) matchedIds.add(m.game.id);
        List<Game> stillUnmatched = new ArrayList<>();
        for (Game g : stillPending) {
            if (!matchedIds.contains(g.id)) stillUnmatched.add(g);
        }

        // ---- 第 4 级：按标题精确搜索兜底 ----
        // 覆盖「只用了 ymgal / hikarinagi 源刮削」或「元数据 id 格式异常」的情况：
        // 这些游戏拿不到可反查的 ref，但标题往往就是 NextMoe 的 localized 值。
        //
        // 候选词只用元数据里的正式标题（中文译名优先），<b>不用 games.title</b>：
        // 那是用户自己起的名字，实测有
        // 「【Tyranor模拟器】保健室的老师…」「命运石之门(斯坦因之门)[简][KFC特创组]」。
        // 而 searchWorkByExactTitle 只接受归一化后<b>完全相等</b>，
        // 带这类标记的名字永远匹配不上，等于这一级白跑。
        //
        // 逐个候选词试，第一个命中就停；逐个请求（搜索接口没有批量车道），
        // 所以放在最后一级，只处理前面全部失败的少量条目。
        Map<Long, java.util.List<String>> mdTitles =
                new com.yuki.yukihub.data.GameRepository(context).getSearchTitles(stillUnmatched);

        for (Game g : stillUnmatched) {
            List<String> candidates = buildTitleCandidates(g, mdTitles.get(g.id));
            if (candidates.isEmpty()) {
                result.unmatched.add(g);
                continue;
            }
            boolean hit = false;
            try {
                for (String cand : candidates) {
                    String workId = NextMoeMeClient.searchWorkByExactTitle(cand);
                    if (workId != null && !workId.isEmpty()) {
                        String prev = g.nextmoeWorkId == null ? "" : g.nextmoeWorkId.trim();
                        result.matched.add(new Matched(g, workId, "title", prev));
                        result.newlyResolved.put(g.id, workId);
                        hit = true;
                        break;
                    }
                }
                if (!hit) result.unmatched.add(g);
            } catch (NextMoeMeClient.QuotaExceededException q) {
                // 配额用尽：剩下的直接算未匹配，把原因往上抛
                result.unmatched.add(g);
                Log.w(TAG, "quota exceeded while title-searching", q);
                throw q;
            } catch (Throwable t) {
                // 单个标题搜索失败不影响其它条目
                Log.w(TAG, "title search failed for " + candidates.get(0), t);
                result.unmatched.add(g);
            }
        }

        if (progress != null) progress.onProgress(total, total);
        return result;
    }

    // ======================== 工具 ========================

    /**
     * 构建标题搜索的候选词，<b>只用元数据里的正式标题</b>。
     * <p>
     * {@code games.title} 是用户自己起的名字，常带「【Tyranor模拟器】」
     * 「[简][KFC特创组]」这类标记；而 {@code searchWorkByExactTitle} 要求
     * 归一化后<b>完全相等</b>，带标记的名字永远匹配不上。
     * <p>
     * 顺序：中文译名 > 原名 > 罗马字。
     * NextMoe 的 {@code localized} 以中文为主，但并非每条都填了中文，
     * 所以中文没命中时还要试原名——这也是要逐个候选词试而不是只发一次的原因。
     * <p>
     * 元数据完全没有时才退回本地标题——那时它是唯一线索。
     */
    static List<String> buildTitleCandidates(Game g, List<String> mdTitles) {
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
        if (mdTitles != null) {
            for (String s : mdTitles) {
                if (s == null) continue;
                String v = s.trim();
                if (v.isEmpty() || "null".equalsIgnoreCase(v)) continue;
                seen.add(v);
            }
        }
        if (seen.isEmpty() && g != null && g.title != null) {
            String t = g.title.trim();
            if (!t.isEmpty()) seen.add(t);
        }
        return new ArrayList<>(seen);
    }

    /** 读 metadata_cache 里某源的 source_id，只取给定 gameId 集合。 */
    private static Map<Long, String> readCachedSourceIds(Context context, Set<Long> gameIds, String source) {
        Map<Long, String> out = new LinkedHashMap<>();
        if (gameIds == null || gameIds.isEmpty()) return out;
        SQLiteDatabase db = new YukiDatabaseHelper(context).getReadableDatabase();
        Cursor c = null;
        try {
            c = db.query("metadata_cache", new String[]{"game_id", "source_id"},
                    "source=?", new String[]{source}, null, null, null);
            while (c.moveToNext()) {
                long gameId = c.getLong(0);
                if (!gameIds.contains(gameId)) continue;
                String sid = c.isNull(1) ? "" : c.getString(1);
                if (sid == null || sid.trim().isEmpty()) continue;
                out.put(gameId, sid);
            }
        } catch (Throwable t) {
            Log.w(TAG, "read metadata_cache failed for " + source, t);
        } finally {
            if (c != null) c.close();
        }
        return out;
    }

    private static String linkSourceOf(String ref) {
        int i = ref == null ? -1 : ref.indexOf(':');
        return i > 0 ? ref.substring(0, i) : "";
    }

    private static Set<Long> idsOf(List<Game> games) {
        Set<Long> s = new HashSet<>();
        for (Game g : games) if (g != null) s.add(g.id);
        return s;
    }

    private static Game findById(List<Game> list, long id) {
        for (Game g : list) {
            if (g != null && g.id == id) return g;
        }
        return null;
    }
}