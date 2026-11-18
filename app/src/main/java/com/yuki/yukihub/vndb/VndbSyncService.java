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

        public Matched(Game game, String vnId) {
            this.game = game;
            this.vnId = vnId;
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

        // 2) 找出本地有 VNDB 锚点的游戏
        Map<Long, String> vnIdByGameId = repo.getVndbSourceIds();

        List<Game> all = repo.getAll();
        List<Matched> matched = new ArrayList<>();
        List<Game> unmatched = new ArrayList<>();

        for (Game g : all) {
            if (g == null) continue;
            int label = toVndbLabel(g.playStatus);
            // 状态未知（空值/脏值）的跳过：既不上报，也不进「无法匹配」，避免噪音
            if (label < 0) continue;

            String vnId = vnIdByGameId.get(g.id);
            if (vnId == null || vnId.trim().isEmpty()) {
                unmatched.add(g);
                continue;
            }
            matched.add(new Matched(g, vnId.trim()));
        }
        if (progress != null) progress.onResolveProgress(all.size(), all.size());

        Preview p = new Preview();
        p.username = auth.username;
        p.items.addAll(matched);
        p.unmatched.addAll(unmatched);

        // 3) 读回远端现状，用于「跳过无变化」与展示「A → B」
        List<String> vnIds = new ArrayList<>();
        for (Matched m : matched) vnIds.add(m.vnId);
        Map<String, java.util.Set<Integer>> remote;
        try {
            remote = VndbClient.fetchStates(token, auth.userId, vnIds);
        } catch (Throwable t) {
            // 读失败不阻断：退化成「全部视为需要同步」。
            // 这只会多写几条（写入是幂等的），不会写错数据。
            Log.w(TAG, "fetchStates failed, treat all as changed", t);
            remote = new LinkedHashMap<>();
        }

        for (Matched m : matched) {
            int target = toVndbLabel(m.game.playStatus);
            java.util.Set<Integer> cur = remote.get(m.vnId);
            int remoteLabel = currentStateLabel(cur);
            boolean absent = (cur == null) || cur.isEmpty();
            boolean firstTime = absent;

            String sig = String.valueOf(target);
            boolean unchanged = !firstTime
                    && sig.equals(m.game.vndbSyncedSig)
                    && m.game.vndbSyncedAt > 0
                    && remoteLabel == target;

            if (unchanged) {
                p.skipped.add(m);
            } else {
                p.changed.add(new ChangeItem(m, target, remoteLabel, firstTime));
            }
        }

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