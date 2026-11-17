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
 *   <li><b>标题精确搜索</b>：前三级都没命中的，用游戏标题走 {@code /catalog/search}。
 *       只接受<b>归一化后完全相等</b>的命中（见 {@link NextMoeMeClient#normalizeTitle}），
 *       因为搜索是模糊的——「千恋万花」会同时返回「涙恋花」，
 *       取第一条就会把时长写到不相干的作品上，比不同步更糟。
 *       这一级逐个请求，所以只处理前面失败的少量条目。</li>
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

        public Matched(Game game, String workId, String via) {
            this.game = game;
            this.workId = workId;
            this.via = via;
        }
    }

    /** 解析进度回调（在调用线程触发，调用方自己切主线程）。 */
    public interface Progress {
        void onProgress(int done, int total);
    }

    // ======================== 主流程 ========================

    public static ResolveResult resolve(Context context, List<Game> games, Progress progress)
            throws Exception {
        ResolveResult result = new ResolveResult();
        if (games == null || games.isEmpty()) return result;

        int total = games.size();
        int done = 0;

        // ---- 第 1 级：已缓存 work_id ----
        List<Game> pending = new ArrayList<>();
        // 进度节流：这一层是纯内存遍历，几乎瞬间完成。
        // 如果每个游戏都回调一次，上千个游戏会上千次 post 到主线程刷 ProgressDialog，
        // 轻则掉帧，重则把消息队列堵住。改为每 25 个报一次。
        int lastReported = -1;
        for (Game g : games) {
            String cached = g.nextmoeWorkId == null ? "" : g.nextmoeWorkId.trim();
            if (!cached.isEmpty()) {
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

        // ---- 第 2 级：metadata_cache 里 source='nextmoe' ----
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
                    result.matched.add(new Matched(g, e.getValue(), linkSourceOf(e.getKey())));
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
        // 只认归一化后完全相等的命中，避免搜索的模糊性把时长写到别的作品上。
        // 逐个请求（搜索接口没有批量车道），所以放在最后一级，只处理前面全部失败的少量条目。
        for (Game g : stillUnmatched) {
            String title = g.title == null ? "" : g.title.trim();
            if (title.isEmpty()) {
                result.unmatched.add(g);
                continue;
            }
            try {
                String workId = NextMoeMeClient.searchWorkByExactTitle(title);
                if (workId != null && !workId.isEmpty()) {
                    result.matched.add(new Matched(g, workId, "title"));
                    result.newlyResolved.put(g.id, workId);
                } else {
                    result.unmatched.add(g);
                }
            } catch (NextMoeMeClient.QuotaExceededException q) {
                // 配额用尽：剩下的直接算未匹配，把原因往上抛
                result.unmatched.add(g);
                Log.w(TAG, "quota exceeded while title-searching", q);
                throw q;
            } catch (Throwable t) {
                // 单个标题搜索失败不影响其它条目
                Log.w(TAG, "title search failed for " + title, t);
                result.unmatched.add(g);
            }
        }

        if (progress != null) progress.onProgress(total, total);
        return result;
    }

    // ======================== 工具 ========================

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