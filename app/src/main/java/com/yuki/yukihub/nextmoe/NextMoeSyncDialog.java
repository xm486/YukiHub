package com.yuki.yukihub.nextmoe;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.yuki.yukihub.model.Game;
import com.yuki.yukihub.util.AppExecutors;

import java.util.ArrayList;
import java.util.List;

/**
 * NextMoe 游玩记录同步的界面流程。
 * <p>
 * 三段式：授权检查 → 匹配（进度） → 预览（用户确认） → 推送 → 汇总。
 * <p>
 * 设计约束（与「云同步」严格隔离）：
 * <ul>
 *   <li>入口独立，不与「云同步」按钮混在一起；</li>
 *   <li>同步失败不影响任何本地游戏数据，只写同步状态三列；</li>
 *   <li>「无法匹配」的游戏必须单独分组展示，不允许静默丢弃。</li>
 * </ul>
 */
public final class NextMoeSyncDialog {

    private NextMoeSyncDialog() { }

    private static final Handler UI = new Handler(Looper.getMainLooper());

    /**
     * Activity 是否还能安全地 show 对话框 / 弹 Toast。
     * <p>
     * 匹配与推送都在 IO 线程跑，可能有几百毫秒到数秒的延迟；
     * 期间用户完全可能退出页面。此时再 show() 会抛
     * {@code WindowManager$BadTokenException} 直接崩溃，
     * 所以所有异步回到主线程的 UI 操作都要先过这一关。
     */
    private static boolean alive(Activity a) {
        if (a == null) return false;
        if (a.isFinishing()) return false;
        if (android.os.Build.VERSION.SDK_INT >= 17 && a.isDestroyed()) return false;
        return true;
    }

    // ======================== 入口 ========================

    /**
     * 从首页按钮调起。已在主线程。
     * <p>
     * <b>绝对不能在这里调 {@link NextMoeAuth#ensureAccessToken()}</b>：
     * access token 15 分钟就过期，一旦过期该方法内部会发网络请求刷新，
     * 而主线程发网络请求会被 StrictMode 直接抛 {@code NetworkOnMainThreadException}。
     * <p>
     * 旧实现正是在这里同步刷新，结果每次「过期后点按钮」都必然异常，
     * 异常又被刷新逻辑当成凭据失效清掉 —— 表现为「登录莫名其妙掉了」。
     * <p>
     * 现在改为：只做纯本地的 {@code isConnected()} 判断（不联网），
     * 令牌过期与否交给 {@link #runPrepare} 在 IO 线程里处理。
     */
    public static void start(Activity activity) {
        if (!NextMoeAuthStore.isConnected()) {
            showNeedAuth(activity);
            return;
        }
        runPrepare(activity);
    }

    private static void showNeedAuth(Activity activity) {
        new AlertDialog.Builder(activity)
                .setTitle("需要 NextMoe 授权")
                .setMessage("同步游玩记录需要授权 NextMoe 账号。\n\n"
                        + "授权只用现有「未萌快捷登录」的权限，不会读取你的收藏夹或个人资料。\n\n"
                        + "同步内容只有游玩时长和游玩状态（想玩 / 在玩 / 玩过 / 搁置 / 弃坑），"
                        + "不会上传游戏文件、路径或封面。")
                .setPositiveButton("去授权", (d, w) -> authorize(activity))
                .setNegativeButton("取消", null)
                .show();
    }

    private static void authorize(Activity activity) {
        // 换成自定义环形进度弹窗（原系统 ProgressDialog 与深色主题不搭）
        final com.yuki.yukihub.ui.SyncProgressDialog spd =
                com.yuki.yukihub.ui.SyncProgressDialog.show(activity, "NextMoe");
        spd.stageReading();
        new Thread(() -> {
            String err = null;
            try {
                err = NextMoeConnectFlow.connectAndWait();
            } catch (Throwable t) {
                err = t.getMessage() == null ? "授权流程异常" : t.getMessage();
            }
            final String error = err;
            UI.post(() -> {
                spd.finish(); spd.close();
                if (!alive(activity)) return;
                boolean ok = error == null && NextMoeAuthStore.isConnected();
                if (ok) {
                    Toast.makeText(activity, "授权成功", Toast.LENGTH_SHORT).show();
                    runPrepare(activity);
                } else {
                    Toast.makeText(activity,
                            error == null ? "授权未完成" : ("授权失败：" + error),
                            Toast.LENGTH_LONG).show();
                }
            });
        }, "yukihub-nextmoe-sync-auth").start();
    }

    // ======================== 阶段 1：匹配 ========================

    private static void runPrepare(Activity activity) {
        startPrepare(activity, false);
    }

    /**
     * @param forceResync true 表示忽略本地「已同步」记录，把所有能匹配上的条目重新推一遍
     */
    private static void startPrepare(Activity activity, boolean forceResync) {
        // 换成自定义环形进度弹窗（原系统 ProgressDialog 与深色主题不搭）
        final com.yuki.yukihub.ui.SyncProgressDialog spd =
                com.yuki.yukihub.ui.SyncProgressDialog.show(activity, "NextMoe");
        spd.stageReading();

        AppExecutors.runOnIo(() -> {
            // 令牌过期时的刷新必须在这里做 —— 这是 IO 线程，可以发网络请求。
            // 主线程调 ensureAccessToken() 会抛 NetworkOnMainThreadException。
            String token = null;
            try {
                token = NextMoeAuth.ensureAccessToken();
            } catch (Throwable t) {
                android.util.Log.w("NextMoeSync", "ensureAccessToken failed in prepare", t);
            }
            if (token == null || token.isEmpty()) {
                // 拿不到令牌：要么凭据已被清掉，要么刷新暂时失败。
                // 不能只报「同步失败」——要区分「需要重新授权」和「网络问题，凭据还在」。
                final boolean stillConnected = NextMoeAuthStore.isConnected();
                UI.post(() -> {
                    spd.finish(); spd.close();
                    if (!alive(activity)) return;
                    if (stillConnected) {
                        new AlertDialog.Builder(activity)
                                .setTitle("暂时无法连接")
                                .setMessage("刷新登录状态失败，可能是网络问题。\n\n"
                                        + "你的 NextMoe 授权仍然保留，请检查网络后重试，不需要重新授权。")
                                .setPositiveButton("重试", (d, w) -> startPrepare(activity, forceResync))
                                .setNegativeButton("取消", null)
                                .show();
                    } else {
                        showNeedAuth(activity);
                    }
                });
                return;
            }

            try {
                NextMoeSyncService.Preview preview = NextMoeSyncService.prepare(activity, forceResync, (done, total) -> {
                    UI.post(() -> {
                        if (!alive(activity)) return;
                        try { spd.stageMatching(done, total); } catch (Throwable ignored) { }
                    });
                });
                UI.post(() -> {
                    spd.close();
                    if (!alive(activity)) return;
                    showPreview(activity, preview);
                });
            } catch (Throwable t) {
                UI.post(() -> {
                    spd.close();
                    if (!alive(activity)) return;
                    showError(activity, t);
                });
            }
        });
    }

    // ======================== 阶段 2：预览 ========================

    private static void showPreview(Activity activity, NextMoeSyncService.Preview preview) {
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(activity, 16);
        root.setPadding(pad, dp(activity, 10), pad, dp(activity, 4));

        if (preview.isEmpty() && preview.unmatched.isEmpty()) {
            TextView tv = bodyText(activity, "没有可同步的游玩记录。\n\n"
                    + "标记了游玩状态、或累计过游玩时长的游戏才会被同步。");
            root.addView(tv);
            new AlertDialog.Builder(activity)
                    .setTitle("同步游玩记录")
                    .setView(wrapScroll(activity, root))
                    .setPositiveButton("好", null)
                    .show();
            return;
        }

        // 摘要
        TextView summary = bodyText(activity, buildSummary(preview));
        summary.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(summary);

        // ---- 重新匹配：id 发生变化的条目（最需要用户确认的）----
        // 强制重同步会绕开 work_id 缓存重新匹配，可能搜出与原来不同的条目。
        List<NextMoeWorkResolver.Matched> idChanged = new ArrayList<>();
        for (NextMoeWorkResolver.Matched m : preview.items) {
            if (m.idChanged()) idChanged.add(m);
        }
        if (!idChanged.isEmpty()) {
            root.addView(sectionHeader(activity, "重新匹配后条目变了（" + idChanged.size() + "）"));
            for (NextMoeWorkResolver.Matched m : idChanged) {
                root.addView(row(activity, safeTitle(m.game),
                        "原 " + m.previousId + " → 现 " + m.workId));
            }
            TextView warn = bodyText(activity,
                    "这些游戏这次重新匹配后指向了不同的 NextMoe 作品。\n"
                            + "如果某一项明显不对，请取消。\n"
                            + "确认继续则会按「现」的作品写入。");
            warn.setTextColor(0xFFFFB74D);
            warn.setPadding(0, dp(activity, 8), 0, 0);
            root.addView(warn);
        }

        // ---- 本次有更新（最重要的分组，放最前面）----
        // 首次全量同步之后，绝大多数条目都是「没变化」的；
        // 把它们和真正要改动的混在一起列，用户根本看不出这次会动什么。
        //
        // 注意：这里绝对不能有「changed 为空就退化成一堆 items」的兜底。
        // changed 为空的意思就是「本次没有需要更新的」——那正是最常见的情况，
        // 硬凑一份列表出来反而会显示成「38 个都有更新」，与实际完全相反。
        // Preview 由 NextMoeSyncService.prepare 统一构造，changed/skipped 一定已分好类。
        List<NextMoeSyncService.ChangeItem> changed = preview.changed;
        if (!changed.isEmpty()) {
            root.addView(sectionHeader(activity, "本次有更新（" + changed.size() + "）"));
            for (NextMoeSyncService.ChangeItem c : changed) {
                root.addView(row(activity, c.matched.game.title, describeChange(c)));
            }
        }
        // ---- 与上次相同，将跳过（折叠，避免长列表淹没重点）----
        if (!preview.skipped.isEmpty()) {
            root.addView(collapsibleSection(activity,
                    "与上次相同，将跳过（" + preview.skipped.size() + "）",
                    () -> {
                        LinearLayout box = new LinearLayout(activity);
                        box.setOrientation(LinearLayout.VERTICAL);
                        for (NextMoeWorkResolver.Matched m : preview.skipped) {
                            box.addView(row(activity, m.game.title, describeGame(m.game)));
                        }
                        return box;
                    }));
        }

        // ---- 无法匹配分组（绝不隐藏）----
        if (!preview.unmatched.isEmpty()) {
            root.addView(sectionHeader(activity,
                    "无法匹配（" + preview.unmatched.size() + "）"));
            for (Game g : preview.unmatched) {
                root.addView(row(activity, "⚠ " + safeTitle(g), "NextMoe 上没有找到对应作品"));
            }
        }

        if (!preview.unmatched.isEmpty()) {
            TextView warn = bodyText(activity, "无法匹配的游戏不会被同步，你的本地数据不受影响。");
            warn.setTextColor(0xFFFFB74D);
            warn.setPadding(0, dp(activity, 8), 0, 0);
            root.addView(warn);
        }

        AlertDialog.Builder b = new AlertDialog.Builder(activity)
                .setTitle("同步预览")
                .setView(wrapScroll(activity, root))
                .setNegativeButton("取消", null);

        if (preview.changesCount == 0 && preview.skippedCount > 0) {
            // 全部无变化：不给「开始同步」，避免发无意义的请求。
            // 但「已是最新」只是本地签名判断，云端被改动或曾写入失败时看不出来，
            // 所以仍要留一个「强制重新同步」的入口。
            b.setNeutralButton("强制重新同步",
                    (d, w) -> confirmForceResync(activity));
            b.setPositiveButton("好", null);
            b.show();
            return;
        }

        if (preview.skippedCount > 0) {
            b.setNeutralButton("强制重新同步",
                    (d, w) -> confirmForceResync(activity));
        }
        b.setPositiveButton("开始同步", (d, w) -> runExecute(activity, preview));
        b.show();
    }

    /** 确认后忽略本地签名，把所有能匹配上的条目重新推一遍。 */
    private static void confirmForceResync(Activity activity) {
        new AlertDialog.Builder(activity)
                .setTitle("强制重新同步")
                .setMessage("将忽略「已是最新」的本地记录，把所有能匹配到的游戏"
                        + "全部重新写入 NextMoe。\n\n"
                        + "用于远端数据被改动、或之前某次写入失败导致"
                        + "「一直显示已是最新却推不上去」的情况。\n\n"
                        + "只会覆盖游玩状态与时长，不会动你的评分与短评。")
                .setNegativeButton("取消", null)
                .setPositiveButton("重新同步", (d, w) -> startPrepare(activity, true))
                .show();
    }

    private static String buildSummary(NextMoeSyncService.Preview p) {
        StringBuilder sb = new StringBuilder();
        if (p.changesCount == 0 && p.skippedCount == 0) {
            sb.append("没有可同步的游戏");
            return sb.toString();
        }
        if (p.changesCount == 0) {
            sb.append("全部 ").append(p.skippedCount).append(" 个游戏都与上次相同，无需同步");
            return sb.toString();
        }
        sb.append("本次将更新 ").append(p.changesCount).append(" 个游戏");
        if (p.skippedCount > 0) {
            sb.append("\n另有 ").append(p.skippedCount).append(" 个与上次相同，会跳过");
        }
        return sb.toString();
    }

    // ======================== 阶段 3：执行 ========================

    private static void runExecute(Activity activity, NextMoeSyncService.Preview preview) {
        // 换成自定义环形进度弹窗（原系统 ProgressDialog 与深色主题不搭）
        final com.yuki.yukihub.ui.SyncProgressDialog spd =
                com.yuki.yukihub.ui.SyncProgressDialog.show(activity, "NextMoe");
        spd.stageWriting();

        AppExecutors.runOnIo(() -> {
            try {
                NextMoeSyncService.Result result = NextMoeSyncService.execute(activity, preview);
                UI.post(() -> {
                    // 同 Bangumi：只调 finish() 不调 close() 会让弹窗停在 100% 且关不掉。
                    spd.finish();
                    UI.postDelayed(spd::close, 420);
                    if (!alive(activity)) return;
                    showResult(activity, result);
                });
            } catch (Throwable t) {
                UI.post(() -> {
                    spd.close();
                    if (!alive(activity)) return;
                    showError(activity, t);
                });
            }
        });
    }

    private static void showResult(Activity activity, NextMoeSyncService.Result r) {
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(activity, 16);
        root.setPadding(pad, dp(activity, 10), pad, dp(activity, 4));

        int okCount = Math.max(r.stateOk, r.timeOk);
        int failCount = Math.max(r.stateFail, r.timeFail);

        root.addView(bodyText(activity, "✅ 成功写入 " + okCount + " 个"));
        if (failCount > 0) {
            TextView tv = bodyText(activity, "⚠ 失败 " + failCount + " 个");
            tv.setTextColor(0xFFFFB74D);
            root.addView(tv);
        }
        if (r.unmatched > 0) {
            TextView tv = bodyText(activity, "⚠ 未匹配 " + r.unmatched + " 个");
            tv.setTextColor(0xFFFFB74D);
            root.addView(tv);
        }

        if (!r.failures.isEmpty()) {
            root.addView(sectionHeader(activity, "失败原因"));
            for (NextMoeSyncService.ItemFailure f : r.failures) {
                root.addView(row(activity, safeStr(f.gameTitle), safeStr(f.reason)));
            }
        }

        new AlertDialog.Builder(activity)
                .setTitle("同步完成")
                .setView(wrapScroll(activity, root))
                .setPositiveButton("知道了", null)
                .show();
    }

    // ======================== 错误 ========================

    private static void showError(Activity activity, Throwable t) {
        String msg;
        if (t instanceof NextMoeMeClient.NotAuthorizedException) {
            msg = "授权已失效，请重新绑定 NextMoe 账号。\n\n" + safeStr(t.getMessage());
        } else if (t instanceof NextMoeMeClient.QuotaExceededException) {
            msg = "今天的同步次数已经用完，请明天再试。\n\n你的本地数据没有任何改动。";
        } else {
            String m = t.getMessage();
            msg = (m == null || m.trim().isEmpty()) ? "同步失败，请稍后重试。" : m;
        }
        new AlertDialog.Builder(activity)
                .setTitle("同步失败")
                .setMessage(msg + "\n\n你的本地游戏数据没有任何改动。")
                .setPositiveButton("好", null)
                .show();
    }

    // ======================== 小工具 ========================

    private static String describeGame(Game g) {
        StringBuilder sb = new StringBuilder();
        sb.append(minutesText(g.totalPlayTime));
        String state = NextMoeSyncService.toNextMoeState(g.playStatus);
        if (state != null) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(stateLabel(state));
        }
        return sb.toString();
    }

    /**
     * 一条「变化明细」的右栏文案。
     * <p>
     * 只标出真正变了的那一项，避免「状态也写了、时长也写了」让人误以为两项都改了：
     * <ul>
     *   <li>首次同步 → 「新增 · 在玩 · 6 小时」</li>
     *   <li>只涨时长 → 「+12 分钟」</li>
     *   <li>只改状态 → 「在玩 → 玩过」</li>
     *   <li>两者都改 → 「在玩 → 玩过 · +12 分钟」</li>
     * </ul>
     */
    private static String describeChange(NextMoeSyncService.ChangeItem c) {
        boolean sc = c.stateChanged();
        boolean tc = c.timeChanged();
        StringBuilder sb = new StringBuilder();

        // 强制重同步导致条目变了：放在最前面单独标出。
        // 注意与下面「状态 from → to」区分：那是游玩状态的变化，
        // 这里是「重新匹配前指哪个作品 → 现在指哪个作品」，完全是两回事。
        String idNote = "";
        if (c.matched.idChanged()) {
            idNote = "原作品 " + c.matched.previousId + " → 现 "
                    + c.matched.workId + " · ";
        }

        if (c.firstTime) {
            sb.append("新增");
            if (sc && !c.newState().isEmpty()) sb.append(" · ").append(stateLabel(c.newState()));
            if (tc && c.newMinutes() > 0) sb.append(" · ").append(minutesText(c.newMinutes() * 60_000L));
            return idNote + sb;
        }

        if (sc) {
            String from = c.oldState().isEmpty() ? "—" : stateLabel(c.oldState());
            String to = c.newState().isEmpty() ? "—" : stateLabel(c.newState());
            sb.append(from).append(" → ").append(to);
        }
        if (tc) {
            if (sb.length() > 0) sb.append(" · ");
            int delta = c.newMinutes() - c.oldMinutes();
            if (delta >= 0) {
                sb.append("+").append(minutesText(delta * 60_000L));
            } else {
                sb.append("-").append(minutesText(-delta * 60_000L));
            }
        }
        return idNote + (sb.length() == 0 ? "有更新" : sb.toString());
    }

    private static String minutesText(long ms) {
        long min = ms / 60_000L;
        if (min <= 0) return "无时长";
        if (min < 60) return min + " 分钟";
        long h = min / 60;
        long m = min % 60;
        return m == 0 ? (h + " 小时") : (h + " 小时 " + m + " 分");
    }

    private static String stateLabel(String state) {
        switch (state) {
            case "wish":    return "想玩";
            case "doing":   return "在玩";
            case "done":    return "玩过";
            case "on_hold": return "搁置";
            case "dropped": return "弃坑";
            default:        return state;
        }
    }

    private static TextView bodyText(Context c, String text) {
        TextView tv = new TextView(c);
        tv.setText(text);
        tv.setTextSize(13);
        tv.setLineSpacing(dp(c, 2), 1.0f);
        tv.setPadding(0, dp(c, 4), 0, dp(c, 4));
        return tv;
    }

    private static TextView sectionHeader(Context c, String text) {
        TextView tv = new TextView(c);
        tv.setText(text);
        tv.setTextSize(12);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setPadding(0, dp(c, 10), 0, dp(c, 2));
        tv.setTextColor(0xFF90CAF9);
        return tv;
    }

    /**
     * 可折叠的分组标题。
     * <p>
     * 「与上次相同」这类几百条的分组默认折叠，点标题才展开，
     * 否则长列表会把「本次有更新」那几条挤出可视区——那正是用户唯一关心的部分。
     */
    private static View collapsibleSection(Context c, String header, java.util.concurrent.Callable<View> builder) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);

        TextView title = sectionHeader(c, header + "  ▸");
        box.addView(title);

        final View[] body = new View[1];
        title.setOnClickListener(v -> {
            if (body[0] == null) {
                try {
                    body[0] = builder.call();
                } catch (Throwable t) {
                    body[0] = bodyText(c, "（展开失败）");
                }
                box.addView(body[0]);
                title.setText(header + "  ▾");
            } else {
                body[0].setVisibility(body[0].getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
                title.setText(header + (body[0].getVisibility() == View.VISIBLE ? "  ▾" : "  ▸"));
            }
        });
        return box;
    }

    /** 一行：左边游戏名（可截断），右边说明。 */
    private static View row(Context c, String title, String desc) {
        LinearLayout line = new LinearLayout(c);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setPadding(0, dp(c, 3), 0, dp(c, 3));

        TextView left = new TextView(c);
        left.setText(title == null ? "" : title);
        left.setTextSize(12);
        left.setSingleLine(true);
        left.setEllipsize(android.text.TextUtils.TruncateAt.END);
        line.addView(left, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView right = new TextView(c);
        right.setText(desc == null ? "" : desc);
        right.setTextSize(11);
        right.setSingleLine(true);
        right.setGravity(Gravity.END);
        right.setTextColor(0xFF9E9E9E);
        right.setPadding(dp(c, 8), 0, 0, 0);
        line.addView(right, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return line;
    }

    private static ScrollView wrapScroll(Context c, View content) {
        ScrollView sv = new ScrollView(c);
        sv.addView(content, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return sv;
    }

    private static String safeTitle(Game g) {
        if (g == null) return "";
        if (g.title == null || g.title.trim().isEmpty()) return "未命名游戏";
        return g.title;
    }

    private static String safeStr(String s) {
        return s == null ? "" : s;
    }

    private static int dp(Context c, int v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }
}