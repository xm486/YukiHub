package com.yuki.yukihub.nextmoe;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
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

    private static void safeDismiss(android.app.ProgressDialog pd) {
        try {
            if (pd != null && pd.isShowing()) pd.dismiss();
        } catch (Throwable ignored) { }
    }

    // ======================== 入口 ========================

    /**
     * 从首页按钮调起。已在主线程。
     */
    public static void start(Activity activity) {
        if (!NextMoeAuthStore.isConnected()) {
            showNeedAuth(activity);
            return;
        }
        // 过期令牌会在这里被刷新；拿不到就是会话失效
        String token;
        try {
            token = NextMoeAuth.ensureAccessToken();
        } catch (Throwable t) {
            token = null;
        }
        if (token == null || token.isEmpty()) {
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
        ProgressDialog pd = new ProgressDialog(activity);
        pd.setMessage("请在浏览器中完成授权…");
        pd.setCancelable(false);
        pd.show();
        new Thread(() -> {
            String err = null;
            try {
                err = NextMoeConnectFlow.connectAndWait();
            } catch (Throwable t) {
                err = t.getMessage() == null ? "授权流程异常" : t.getMessage();
            }
            final String error = err;
            UI.post(() -> {
                safeDismiss(pd);
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
        ProgressDialog pd = new ProgressDialog(activity);
        pd.setMessage("正在匹配作品…");
        pd.setCancelable(false);
        pd.show();

        AppExecutors.runOnIo(() -> {
            try {
                NextMoeSyncService.Preview preview = NextMoeSyncService.prepare(activity, (done, total) -> {
                    UI.post(() -> {
                        if (!alive(activity)) return;
                        try { pd.setMessage("正在匹配作品… " + done + " / " + total); } catch (Throwable ignored) { }
                    });
                });
                UI.post(() -> {
                    safeDismiss(pd);
                    if (!alive(activity)) return;
                    showPreview(activity, preview);
                });
            } catch (Throwable t) {
                UI.post(() -> {
                    safeDismiss(pd);
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

        // ---- 可同步分组 ----
        if (!preview.items.isEmpty()) {
            root.addView(sectionHeader(activity,
                    "可同步（" + preview.items.size() + "）"));
            for (NextMoeWorkResolver.Matched m : preview.items) {
                root.addView(row(activity, m.game.title, describeGame(m.game)));
            }
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
            // 全部无变化：不给「开始同步」，避免发无意义的请求
            b.setPositiveButton("好", null);
            b.show();
            return;
        }

        b.setPositiveButton("开始同步", (d, w) -> runExecute(activity, preview));
        b.show();
    }

    private static String buildSummary(NextMoeSyncService.Preview p) {
        StringBuilder sb = new StringBuilder();
        if (p.items.isEmpty()) {
            sb.append("没有可同步的游戏");
        } else {
            sb.append("将同步 ").append(p.items.size()).append(" 个游戏");
        }
        if (p.skippedCount > 0) {
            sb.append("\n其中 ").append(p.skippedCount).append(" 个与上次相同，会跳过");
        }
        return sb.toString();
    }

    // ======================== 阶段 3：执行 ========================

    private static void runExecute(Activity activity, NextMoeSyncService.Preview preview) {
        ProgressDialog pd = new ProgressDialog(activity);
        pd.setMessage("正在同步…");
        pd.setCancelable(false);
        pd.show();

        AppExecutors.runOnIo(() -> {
            try {
                NextMoeSyncService.Result result = NextMoeSyncService.execute(activity, preview);
                UI.post(() -> {
                    safeDismiss(pd);
                    if (!alive(activity)) return;
                    showResult(activity, result);
                });
            } catch (Throwable t) {
                UI.post(() -> {
                    safeDismiss(pd);
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