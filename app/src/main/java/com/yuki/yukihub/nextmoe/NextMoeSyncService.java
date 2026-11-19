package com.yuki.yukihub.nextmoe;

import android.content.Context;
import android.util.Log;

import com.yuki.yukihub.data.GameRepository;
import com.yuki.yukihub.model.Game;

import java.util.ArrayList;
import java.util.List;

/**
 * NextMoe 游玩记录同步的编排层：收集 → 匹配 → 预览 → 分批推送 → 汇总。
 * <p>
 * 全部方法都是<b>阻塞</b>的，必须在 IO 线程调用（项目里用 AppExecutors.runOnIo）。
 *
 * <h3>两阶段设计的理由</h3>
 * {@link #prepare} 只读不写，产出一个可展示的 {@link Preview}；
 * 用户确认后才由 {@link #execute} 真正写远端。
 * 这样「匹配失败的游戏」有机会被用户看见，而不是静默丢弃。
 *
 * <h3>失败隔离</h3>
 * 本类<b>只写远端 + 本地同步状态三列</b>，绝不修改 {@code playStatus} / {@code totalPlayTime}
 * 等业务数据。同步失败时本地游戏库保持原样。
 */
public final class NextMoeSyncService {

    private static final String TAG = "NextMoeSync";

    /** 与 MainActivity 共用同一个 SharedPreferences 文件，但各用各的 key。 */
    private static final String PREFS_NAME = "yukihub_prefs";
    /** 本模块自己的「上次成功同步时间」，不与云同步的 last_sync_at 混用。 */
    private static final String KEY_LAST_SYNC_AT = "nextmoe_sync_at";

    private NextMoeSyncService() { }

    // ======================== 数据结构 ========================

    /** 预览：还没写任何东西。 */
    public static class Preview {
        public final List<NextMoeWorkResolver.Matched> items = new ArrayList<>();
        public final List<Game> unmatched = new ArrayList<>();
        /** 本次新解析出的映射，执行成功后回写缓存。 */
        public final java.util.Map<Long, String> newlyResolved = new java.util.LinkedHashMap<>();

        /**
         * 本次真正会推送的条目（有变化的），按变化大小降序。
         * 单独列出来是为了让用户一眼看清「这次会改动什么」，
         * 而不是从几十条里自己找——第一次全量同步之后，绝大多数条目都是无变化的。
         */
        public final List<ChangeItem> changed = new ArrayList<>();
        /** 与上次完全相同、将被跳过的条目。 */
        public final List<NextMoeWorkResolver.Matched> skipped = new ArrayList<>();

        /** 兼容旧调用：实际会发出去的条数（去掉「与上次完全相同」的）。 */
        public int changesCount = 0;
        /** 因未变动而跳过的条数。 */
        public int skippedCount = 0;

        public boolean isEmpty() {
            return items.isEmpty();
        }
    }

    /**
     * 一条待推送的变化明细。
     * <p>
     * 保存「上次同步时的签名」与「当前签名」，UI 据此把
     * {@code doing:120} 这种机器串翻译成「在玩 · 2 小时」并标出改了哪一项。
     */
    public static class ChangeItem {
        public final NextMoeWorkResolver.Matched matched;
        /** 上次成功同步时的签名，空串表示从没同步过。 */
        public final String oldSig;
        /** 当前签名。 */
        public final String newSig;
        /** true 表示这款游戏是第一次同步（没有旧签名）。 */
        public final boolean firstTime;

        public ChangeItem(NextMoeWorkResolver.Matched matched, String oldSig, String newSig, boolean firstTime) {
            this.matched = matched;
            this.oldSig = oldSig == null ? "" : oldSig;
            this.newSig = newSig == null ? "" : newSig;
            this.firstTime = firstTime;
        }

        /** 状态是否变了（含首次同步）。 */
        public boolean stateChanged() {
            return firstTime || !statePart(oldSig).equals(statePart(newSig));
        }

        /** 时长是否变了（含首次同步）。 */
        public boolean timeChanged() {
            return firstTime || !timePart(oldSig).equals(timePart(newSig));
        }

        private static String statePart(String sig) {
            int i = sig.indexOf(':');
            return i < 0 ? "" : sig.substring(0, i);
        }

        private static String timePart(String sig) {
            int i = sig.indexOf(':');
            return i < 0 ? "" : sig.substring(i + 1);
        }

        public String oldState() { return statePart(oldSig); }
        public String newState() { return statePart(newSig); }

        public int oldMinutes() {
            try { return Integer.parseInt(timePart(oldSig)); } catch (Throwable t) { return 0; }
        }

        public int newMinutes() {
            try { return Integer.parseInt(timePart(newSig)); } catch (Throwable t) { return 0; }
        }
    }

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
        public int stateOk = 0;
        public int stateFail = 0;
        public int timeOk = 0;
        public int timeFail = 0;
        public int unmatched = 0;
        public final List<ItemFailure> failures = new ArrayList<>();
        /** 完成后回写本地同步时间的条数。 */
        public int markedSynced = 0;
    }

    public interface Progress {
        /** 匹配进度（主线程回调由调用方负责）。 */
        void onResolveProgress(int done, int total);
    }

    // ======================== 状态映射 ========================

    /**
     * 本地 playStatus → NextMoe state。
     * NextMoe 的 state 是封闭词表：wish / doing / done / on_hold / dropped。
     * 本地「未玩」对应 wish（想玩），语义一致。
     */
    public static String toNextMoeState(String playStatus) {
        if (playStatus == null) return null;
        switch (playStatus.trim().toLowerCase()) {
            case "playing":   return "doing";
            case "completed": return "done";
            case "onhold":    return "on_hold";
            case "dropped":   return "dropped";
            case "unplayed":  return "wish";
            default:          return null;   // 未知值不上报，避免把用户数据写坏
        }
    }

    /** 本地毫秒 → NextMoe 分钟，按服务端上限截断。 */
    public static int toMinutes(long totalPlayTimeMs) {
        long minutes = totalPlayTimeMs / 60_000L;
        if (minutes < 0) return 0;
        return (int) Math.min(minutes, NextMoeMeClient.MAX_MINUTES);
    }

    /** 内容签名：用于判断「自上次同步以来是否有变化」。 */
    public static String signatureOf(Game g) {
        if (g == null) return "";
        String state = toNextMoeState(g.playStatus);
        if (state == null) state = "";
        return state + ":" + toMinutes(g.totalPlayTime);
    }

    // ======================== 阶段 1：只读匹配 ========================

    /**
     * 收集并匹配，不写任何数据（包括本地缓存也不写，留到 execute 成功后再写）。
     */
    public static Preview prepare(Context context, Progress progress) throws Exception {
        return prepare(context, false, progress);
    }

    /**
     * 收集并匹配，不写任何数据（包括本地缓存也不写，留到 execute 成功后再写）。
     *
     * @param forceResync 强制重推：为 true 时忽略本地签名，把所有能匹配上的条目
     *                    都算作「有变化」，全部重新写入远端。
     *                    本地签名只能证明「我这边没变」，无法证明云端收到了；
     *                    远端被改动或曾写入失败时，签名看不出来。
     */
    public static Preview prepare(Context context, boolean forceResync, Progress progress)
            throws Exception {
        GameRepository repo = new GameRepository(context);
        List<Game> all = repo.getAll();

        // 只同步「有可上报内容」的游戏：有状态或有时长。
        // 完全是 unplayed + 0 分钟的条目没有信息量，不上报也不进「无法匹配」，避免干扰。
        List<Game> candidates = new ArrayList<>();
        for (Game g : all) {
            if (g == null) continue;
            String state = toNextMoeState(g.playStatus);
            int minutes = toMinutes(g.totalPlayTime);
            // 只有「想玩」且 0 分钟时，仍然有意义（用户标了想玩），所以只要 state 非空就纳入
            if (state == null && minutes == 0) continue;
            candidates.add(g);
        }

        NextMoeWorkResolver.ResolveResult resolved = NextMoeWorkResolver.resolve(
                context, candidates, (done, total) -> {
                    if (progress != null) progress.onResolveProgress(done, total);
                });

        Preview p = new Preview();
        p.items.addAll(resolved.matched);
        p.unmatched.addAll(resolved.unmatched);
        p.newlyResolved.putAll(resolved.newlyResolved);

        // 分类：有变化的 / 与上次相同的
        // 「首次同步」= 从没记录过 syncedAt，即使签名恰好与空串不同也必然是变化。
        for (NextMoeWorkResolver.Matched m : p.items) {
            String sig = signatureOf(m.game);
            // 强制重推时，一律视为「首次」，全部进 changed；签名比对被绕过。
            boolean firstTime = forceResync || m.game.nextmoeSyncedAt <= 0;
            boolean unchanged = !forceResync && !firstTime
                    && sig.equals(m.game.nextmoeSyncedSig);
            if (unchanged) {
                p.skipped.add(m);
            } else {
                p.changed.add(new ChangeItem(m, m.game.nextmoeSyncedSig, sig, firstTime));
            }
        }

        // 排序：变化越大的排越前（首次同步优先，其次按时长增量降序）。
        // 这样用户最先看到的就是「最值得注意的那几条」，而不是被一堆零碎淹没。
        java.util.Collections.sort(p.changed, (a, b) -> {
            if (a.firstTime != b.firstTime) return a.firstTime ? -1 : 1;
            int da = Math.abs(a.newMinutes() - a.oldMinutes());
            int db = Math.abs(b.newMinutes() - b.oldMinutes());
            if (da != db) return db - da;
            return 0;   // 稳定排序，保持原始顺序
        });

        p.changesCount = p.changed.size();
        p.skippedCount = p.skipped.size();

        Log.i(TAG, "prepare: matched=" + p.items.size() + " unmatched=" + p.unmatched.size()
                + " changes=" + p.changesCount + " skipped=" + p.skippedCount);
        return p;
    }

    // ======================== 阶段 2：执行推送 ========================

    /**
     * 执行推送。只写远端，以及本地三列同步状态。
     *
     * @param preview {@link #prepare} 的产物
     */
    public static Result execute(Context context, Preview preview) throws Exception {
        Result result = new Result();
        if (preview == null) return result;
        result.unmatched = preview.unmatched.size();

        if (preview.items.isEmpty()) return result;

        GameRepository repo = new GameRepository(context);

        // 只推有变化的
        List<NextMoeWorkResolver.Matched> toPush = new ArrayList<>();
        for (NextMoeWorkResolver.Matched m : preview.items) {
            String sig = signatureOf(m.game);
            boolean unchanged = sig.equals(m.game.nextmoeSyncedSig) && m.game.nextmoeSyncedAt > 0;
            if (unchanged) continue;
            toPush.add(m);
        }
        // 全部未变化：一个请求都不发。预览页正常情况下不会让用户走到这里，
        // 但状态可能在预览与执行之间被其它入口改动，这里兜一道。
        if (toPush.isEmpty()) {
            Log.i(TAG, "execute: nothing changed, skipped all requests");
            return result;
        }

        // ---- 状态批次 ----
        List<NextMoeMeClient.StatePair> statePairs = new ArrayList<>();
        List<NextMoeWorkResolver.Matched> stateMeta = new ArrayList<>();
        for (NextMoeWorkResolver.Matched m : toPush) {
            String state = toNextMoeState(m.game.playStatus);
            if (state == null) continue;
            statePairs.add(new NextMoeMeClient.StatePair(m.workId, state));
            stateMeta.add(m);
        }
        // 按 gameId 记录失败，不用标题（标题可能重复，会误伤其它游戏）
        java.util.Set<Long> failedIds = new java.util.HashSet<>();
        java.util.Set<Long> succeededIds = new java.util.HashSet<>();

        for (int start = 0; start < statePairs.size(); start += NextMoeMeClient.MAX_BATCH) {
            int end = Math.min(start + NextMoeMeClient.MAX_BATCH, statePairs.size());
            List<NextMoeMeClient.StatePair> chunk = statePairs.subList(start, end);
            List<NextMoeMeClient.BatchItemResult> rs = NextMoeMeClient.putWorkStates(chunk);
            for (int i = 0; i < rs.size() && i < chunk.size(); i++) {
                NextMoeMeClient.BatchItemResult r = rs.get(i);
                NextMoeWorkResolver.Matched m = stateMeta.get(start + i);
                if (r.success) {
                    result.stateOk++;
                    succeededIds.add(m.game.id);
                } else {
                    result.stateFail++;
                    failedIds.add(m.game.id);
                    result.failures.add(new ItemFailure(m.game.title,
                            describeFailure(r.errorCode, r.errorMessage)));
                }
            }
        }

        // ---- 时长批次 ----
        // 只在「本地有非零时长」时才写，避免把别人的记录用 0 覆盖掉。
        // （服务端是按应用各存一行，写 0 只会清掉我们自己那一行，但仍无意义。）
        List<NextMoeMeClient.MinutePair> timePairs = new ArrayList<>();
        List<NextMoeWorkResolver.Matched> timeMeta = new ArrayList<>();
        for (NextMoeWorkResolver.Matched m : toPush) {
            int minutes = toMinutes(m.game.totalPlayTime);
            if (minutes <= 0) continue;
            timePairs.add(new NextMoeMeClient.MinutePair(m.workId, minutes));
            timeMeta.add(m);
        }
        for (int start = 0; start < timePairs.size(); start += NextMoeMeClient.MAX_BATCH) {
            int end = Math.min(start + NextMoeMeClient.MAX_BATCH, timePairs.size());
            List<NextMoeMeClient.MinutePair> chunk = timePairs.subList(start, end);
            List<NextMoeMeClient.BatchItemResult> rs = NextMoeMeClient.putPlaytimes(chunk);
            for (int i = 0; i < rs.size() && i < chunk.size(); i++) {
                NextMoeMeClient.BatchItemResult r = rs.get(i);
                NextMoeWorkResolver.Matched m = timeMeta.get(start + i);
                if (r.success) {
                    result.timeOk++;
                    succeededIds.add(m.game.id);
                } else {
                    result.timeFail++;
                    failedIds.add(m.game.id);
                    result.failures.add(new ItemFailure(m.game.title,
                            "时长：" + describeFailure(r.errorCode, r.errorMessage)));
                }
            }
        }

        // ---- 回写本地：只标记真正成功的 ----
        // 规则：这条游戏至少有一项写成功，且没有任何一项失败，才记录 syncedAt + sig。
        // 否则下次同步会重新尝试它，不会因为一次失败就永久跳过。
        long now = System.currentTimeMillis();
        for (NextMoeWorkResolver.Matched m : toPush) {
            if (failedIds.contains(m.game.id)) continue;
            if (!succeededIds.contains(m.game.id)) continue;
            repo.updateNextMoeSyncState(m.game.id, m.workId, now, signatureOf(m.game));
            result.markedSynced++;
        }

        // 缓存本次新解析出的 work_id（即使推送失败，匹配结果本身是有价值的）
        if (preview.newlyResolved != null && !preview.newlyResolved.isEmpty()) {
            repo.updateNextMoeWorkIds(preview.newlyResolved);
        }

        // 记录「上次成功同步时间」，供首页按钮下方的状态小字显示。
        // 只在真有写入成功时才记，全部失败时不更新，避免显示成「刚同步过」。
        // 用独立的 key 与 MainActivity 的 KEY_LAST_SYNC_AT 区分开：
        // 那一份记录的是「本项目服务器云同步」，两者不能混。
        if (result.markedSynced > 0) {
            try {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit().putLong(KEY_LAST_SYNC_AT, now).apply();
            } catch (Throwable t) {
                Log.w(TAG, "write last sync time failed", t);
            }
        }

        Log.i(TAG, "execute done: stateOk=" + result.stateOk + " stateFail=" + result.stateFail
                + " timeOk=" + result.timeOk + " timeFail=" + result.timeFail
                + " marked=" + result.markedSynced);
        return result;
    }

    // ======================== 错误文案 ========================

    /** 把 apr 错误码翻译成用户能看懂的话。 */
    private static String describeFailure(String code, String message) {
        if (code == null) code = "";
        switch (code) {
            case "QUOTA_EXCEEDED": return "同步次数已达上限，请稍后再试";
            case "VALIDATION_FAILED": return "数据格式不被接受" + (message == null || message.isEmpty() ? "" : "：" + message);
            case "NOT_FOUND": return "该作品在 NextMoe 上已不存在";
            case "ENTITY_MERGED": return "该作品已被合并到新条目";
            case "RATE_LIMITED": return "请求过于频繁，请稍后再试";
            default:
                if (message != null && !message.isEmpty()) return message;
                return "同步失败" + (code.isEmpty() ? "" : "（" + code + "）");
        }
    }
}