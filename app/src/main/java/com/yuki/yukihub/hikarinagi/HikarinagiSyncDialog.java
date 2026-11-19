package com.yuki.yukihub.hikarinagi;

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
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.yuki.yukihub.model.Game;
import com.yuki.yukihub.util.AppExecutors;

import java.util.List;

/**
 * Hikarinagi 游玩状态同步的界面流程：授权 → 预览 → 推送 → 汇总。
 *
 * <h3>与 Bangumi / VNDB 版本的区别</h3>
 * 那两个是「粘贴 token」，这里是<b>独立 OAuth 授权</b>（与 NextMoe 同类型），
 * 因为 Hikarinagi 的写接口只认用户级令牌，不能靠粘一个 API key 解决。
 * 授权走 {@link HikarinagiSyncAuth}，结果由已有的回调 Activity 分流。
 *
 * <h3>线程约束</h3>
 * 所有网络请求（含令牌校验、刷新）一律在 IO 线程。
 * 主线程只做纯本地判断（{@link HikarinagiAuthStore#isConnected}），绝不联网。
 */
public final class HikarinagiSyncDialog {

    private HikarinagiSyncDialog() { }

    private static final Handler UI = new Handler(Looper.getMainLooper());

    private static boolean alive(Activity a) {
        if (a == null) return false;
        if (a.isFinishing()) return false;
        if (android.os.Build.VERSION.SDK_INT >= 17 && a.isDestroyed()) return false;
        return true;
    }

    private static void safeDismiss(ProgressDialog pd) {
        try {
            if (pd != null && pd.isShowing()) pd.dismiss();
        } catch (Throwable ignored) { }
    }

    // ======================== 入口 ========================

    /**
     * 从首页按钮调起。已在主线程。
     * <p>
     * 只做纯本地的 isConnected 判断，不联网。
     */
    public static void start(Activity activity) {
        if (!alive(activity)) return;
        if (!HikarinagiAuthStore.isConnected(activity)) {
            showConnectDialog(activity);
            return;
        }
        runPrepare(activity);
    }

    // ======================== 未授权：引导 ========================

    private static void showConnectDialog(Activity activity) {
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(activity, 16);
        root.setPadding(pad, dp(activity, 8), pad, dp(activity, 4));
        root.addView(bodyText(activity,
                "同步游玩状态与时长到 Hikarinagi 需要先授权。\n\n"
                        + "点「去授权」会打开系统浏览器，登录 Hikarinagi 并同意后自动回到 YukiHub。\n\n"
                        + "权限说明：\n"
                        + "· status:write —— 写入游玩状态（必需）\n"
                        + "· offline_access —— 长期免重复登录\n\n"
                        + "只会修改「游玩状态」和「通关耗时」，"
                        + "不会动你的评分、短评、剧透标记和私密设置。"));

        new AlertDialog.Builder(activity)
                .setTitle("连接 Hikarinagi")
                .setView(wrapScroll(activity, root))
                .setPositiveButton("去授权", (d, w) -> authorize(activity))
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 走一次完整授权（阻塞式，在后台线程）。
     * <p>
     * 与 NextMoe 的做法一致：先开环回监听，再拉浏览器，然后在同一 socket 上
     * 等回调回来，全程不经过任何 Activity。
     */
    private static void authorize(Activity activity) {
        ProgressDialog pd = new ProgressDialog(activity);
        pd.setMessage("请在浏览器中完成授权…");
        pd.setCancelable(false);
        pd.show();

        new Thread(() -> {
            String err;
            try {
                err = HikarinagiSyncAuth.connectAndWait(activity);
            } catch (Throwable t) {
                err = t.getMessage() == null ? "授权流程异常" : t.getMessage();
            }
            final String error = err;
            UI.post(() -> {
                safeDismiss(pd);
                if (!alive(activity)) return;
                boolean ok = error == null && HikarinagiAuthStore.isConnected(activity);
                if (ok) {
                    Toast.makeText(activity, "授权成功", Toast.LENGTH_SHORT).show();
                    runPrepare(activity);
                } else {
                    Toast.makeText(activity,
                            error == null ? "授权未完成" : ("授权失败：" + error),
                            Toast.LENGTH_LONG).show();
                }
            });
        }, "yukihub-hikarinagi-sync-auth").start();
    }

    // ======================== 阶段 1：匹配 ========================

    private static void runPrepare(Activity activity) {
        startPrepare(activity, false);
    }

    /**
     * @param forceResync true 表示忽略本地「已同步」记录，把所有能匹配上的条目重新推一遍
     */
    private static void startPrepare(Activity activity, boolean forceResync) {
        ProgressDialog pd = new ProgressDialog(activity);
        pd.setMessage(forceResync ? "正在准备重新同步…" : "正在匹配条目…");
        pd.setCancelable(false);
        pd.show();

        AppExecutors.runOnIo(() -> {
            try {
                HikarinagiSyncService.Preview preview = HikarinagiSyncService.prepare(
                        activity, forceResync,
                        (done, total) -> UI.post(() -> {
                            if (!alive(activity)) return;
                            try { pd.setMessage("正在匹配… " + done + " / " + total); }
                            catch (Throwable ignored) { }
                        }));
                UI.post(() -> {
                    safeDismiss(pd);
                    if (!alive(activity)) return;
                    showPreview(activity, preview);
                });
            } catch (HikarinagiSyncService.TokenRefreshFailedException e) {
                // 凭据还在，只是这次没刷成（多半网络问题）→ 给「重试」，劝用户别重新授权
                UI.post(() -> {
                    safeDismiss(pd);
                    if (!alive(activity)) return;
                    final String msg = e.getMessage();
                    new AlertDialog.Builder(activity)
                            .setTitle("暂时无法连接")
                            .setMessage(msg)
                            .setPositiveButton("重试", (d, w) -> startPrepare(activity, forceResync))
                            .setNegativeButton("取消", null)
                            .show();
                });
            } catch (HikarinagiSyncClient.NotAuthorizedException e) {
                safeDismiss(pd);
                handleAuthError(activity, e.getMessage());
            } catch (Throwable t) {
                UI.post(() -> {
                    safeDismiss(pd);
                    if (!alive(activity)) return;
                    showError(activity, t);
                });
            }
        });
    }

    /**
     * 授权失效：不静默清本地凭据（可能只是服务端暂时不可用），
     * 而是明确告知并给「重新授权」的入口。
     */
    private static void handleAuthError(Activity activity, String msg) {
        UI.post(() -> {
            if (!alive(activity)) return;
            new AlertDialog.Builder(activity)
                    .setTitle("Hikarinagi 授权有问题")
                    .setMessage(msg)
                    .setPositiveButton("重新授权", (d, w) -> {
                        HikarinagiAuthStore.clear(activity);
                        authorize(activity);
                    })
                    .setNegativeButton("取消", null)
                    .show();
        });
    }

    // ======================== 阶段 2：预览 ========================

    private static void showPreview(Activity activity, HikarinagiSyncService.Preview preview) {
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(activity, 16);
        root.setPadding(pad, dp(activity, 10), pad, dp(activity, 4));

        if (preview.isEmpty() && preview.unmatched.isEmpty()) {
            root.addView(bodyText(activity,
                    "没有可同步的游戏。\n\n"
                            + "只有标了游玩状态的游戏才能同步。"));
            new AlertDialog.Builder(activity)
                    .setTitle("同步到 Hikarinagi")
                    .setView(wrapScroll(activity, root))
                    .setPositiveButton("好", null)
                    .show();
            return;
        }

        TextView summary = bodyText(activity, buildSummary(preview));
        summary.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(summary);

        // ---- 本次有更新 ----
        List<HikarinagiSyncService.ChangeItem> changed = preview.changed;
        if (!changed.isEmpty()) {
            root.addView(sectionHeader(activity, "本次有更新（" + changed.size() + "）"));
            for (HikarinagiSyncService.ChangeItem c : changed) {
                root.addView(row(activity, safeTitle(c.matched.game), describeChange(c)));
            }
        }

        // ---- 已是最新，将跳过（折叠）----
        if (!preview.skipped.isEmpty()) {
            root.addView(collapsibleSection(activity,
                    "已是最新，将跳过（" + preview.skipped.size() + "）",
                    () -> {
                        LinearLayout box = new LinearLayout(activity);
                        box.setOrientation(LinearLayout.VERTICAL);
                        for (HikarinagiSyncService.Matched m : preview.skipped) {
                            box.addView(row(activity, safeTitle(m.game),
                                    describeCurrent(m.game)));
                        }
                        return box;
                    }));
        }

        // ---- 已合并的重复条目（折叠）----
        if (!preview.duplicates.isEmpty()) {
            root.addView(collapsibleSection(activity,
                    "多个本地条目指向同一作品，已合并（" + preview.duplicates.size() + " 组）",
                    () -> {
                        LinearLayout box = new LinearLayout(activity);
                        box.setOrientation(LinearLayout.VERTICAL);
                        for (HikarinagiSyncService.DuplicateGroup dg : preview.duplicates) {
                            // 代表项（真正写入的那条）
                            box.addView(row(activity,
                                    "✔ " + safeTitle(dg.canonical.game),
                                    "写入：" + HikarinagiSyncService.statusText(
                                            HikarinagiSyncService.mergedStatus(dg))));
                            for (HikarinagiSyncService.Matched m : dg.dropped) {
                                box.addView(row(activity,
                                        "　↳ " + safeTitle(m.game),
                                        "并入，不单独写入"));
                            }
                        }
                        TextView tip = bodyText(activity,
                                "Hikarinagi 上一部作品只有一个条目，而批量接口规定"
                                        + "「同一作品不能重复出现」，重复会让整批请求被拒。\n"
                                        + "所以这些条目按「进度最深」合并成一条写入，时长取最大值。\n"
                                        + "本地数据不受影响。");
                        tip.setTextColor(0xFF90CAF9);
                        tip.setPadding(0, dp(activity, 8), 0, 0);
                        box.addView(tip);
                        return box;
                    }));
        }

        // ---- 无法匹配 ----
        if (!preview.unmatched.isEmpty()) {
            root.addView(sectionHeader(activity, "无法匹配（" + preview.unmatched.size() + "）"));
            for (Game g : preview.unmatched) {
                root.addView(row(activity, "⚠ " + safeTitle(g), "Hikarinagi 上没找到"));
            }
            TextView warn = bodyText(activity,
                    "已优先用已有的 Hikarinagi id，没有的再用标题搜索，仍未精确命中。\n"
                            + "可能是 Hikarinagi 未收录，或标题与其条目名差异较大。\n"
                            + "本地数据不受影响。");
            warn.setTextColor(0xFFFFB74D);
            warn.setPadding(0, dp(activity, 8), 0, 0);
            root.addView(warn);
        }

        AlertDialog.Builder b = new AlertDialog.Builder(activity)
                .setTitle("同步到 Hikarinagi")
                .setView(wrapScroll(activity, root))
                .setNegativeButton("取消", null);
        if (preview.changesCount == 0) {
            // 全部「已是最新」不等于云端一定是对的：本地签名只证明「我这边没变」。
            // 远端被改动、单条写入曾经失败过等情况都看不出来。
            // 所以这里必须给出「强制重新同步」的入口，否则用户就彻底没法把数据推上去。
            if (preview.skippedCount > 0) {
                b.setNeutralButton("强制重新同步", (d, w) -> confirmForceResync(activity));
            }
            b.setPositiveButton("好", null);
            b.show();
            return;
        }
        if (preview.skippedCount > 0) {
            b.setNeutralButton("强制重新同步", (d, w) -> confirmForceResync(activity));
        }
        b.setPositiveButton("开始同步", (d, w) -> runExecute(activity, preview));
        b.show();
    }

    /** 确认后忽略本地签名，把所有能匹配上的条目重新推一遍。 */
    private static void confirmForceResync(Activity activity) {
        new AlertDialog.Builder(activity)
                .setTitle("强制重新同步")
                .setMessage("将忽略「已是最新」的本地记录，把所有能匹配到的游戏"
                        + "全部重新写入 Hikarinagi。\n\n"
                        + "用于远端数据被改动、或之前某次写入失败导致"
                        + "「一直显示已是最新却推不上去」的情况。\n\n"
                        + "只会覆盖游玩状态与时长，不会动你的评分与短评。")
                .setNegativeButton("取消", null)
                .setPositiveButton("重新同步", (d, w) -> startPrepare(activity, true))
                .show();
    }
    private static String buildSummary(HikarinagiSyncService.Preview p) {
        StringBuilder sb = new StringBuilder();
        if (p.username != null && !p.username.isEmpty()) {
            sb.append("Hikarinagi 账号：").append(p.username).append('\n');
        }
        if (p.changesCount == 0 && p.skippedCount == 0) {
            sb.append("没有可同步的游戏");
            return sb.toString();
        }
        if (p.changesCount == 0) {
            sb.append("全部 ").append(p.skippedCount).append(" 个都已是最新，无需同步");
            return sb.toString();
        }
        sb.append("本次将更新 ").append(p.changesCount).append(" 个游戏");
        if (p.skippedCount > 0) {
            sb.append("\n另有 ").append(p.skippedCount).append(" 个已是最新，会跳过");
        }
        if (!p.duplicates.isEmpty()) {
            // Hikarinagi 一部作品只有一个条目，本地多开时必须在合并后再写，
            // 否则批量接口会因「同一作品重复出现」整批拒绝。这里如实告知用户。
            sb.append("\n有 ").append(p.duplicates.size()).append(" 部作品存在多个本地条目，已合并写入");
        }
        return sb.toString();
    }

    // ======================== 阶段 3：执行 ========================

    private static void runExecute(Activity activity, HikarinagiSyncService.Preview preview) {
        ProgressDialog pd = new ProgressDialog(activity);
        pd.setMessage("正在同步…");
        pd.setCancelable(false);
        pd.show();

        AppExecutors.runOnIo(() -> {
            try {
                HikarinagiSyncService.Result result = HikarinagiSyncService.execute(activity, preview);
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

    private static void showResult(Activity activity, HikarinagiSyncService.Result r) {
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(activity, 16);
        root.setPadding(pad, dp(activity, 10), pad, dp(activity, 4));

        root.addView(bodyText(activity, "✅ 成功更新 " + r.ok + " 个"));
        if (r.requests > 0) {
            root.addView(bodyText(activity, "共发出 " + r.requests + " 次批量请求"));
        }
        if (r.fail > 0) {
            TextView tv = bodyText(activity, "⚠ 失败 " + r.fail + " 个");
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
            int shown = 0;
            for (HikarinagiSyncService.ItemFailure f : r.failures) {
                if (shown++ >= 20) {
                    root.addView(bodyText(activity, "…另有 " + (r.failures.size() - 20) + " 条"));
                    break;
                }
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
        if (t instanceof HikarinagiSyncClient.RateLimitedException) {
            msg = "Hikarinagi 请求过于频繁，请过几分钟再试。";
        } else if (t instanceof HikarinagiSyncClient.ValidationException) {
            // 422：整批被拒，一条都没写。必须说清楚是「什么都没写」，别让用户以为成功了一半。
            msg = safeStr(t.getMessage()) + "\n\n服务端拒绝了整批请求，本次一条都没有写入。";
        } else if (t instanceof HikarinagiSyncService.TokenRefreshFailedException) {
            msg = safeStr(t.getMessage());
        } else if (t instanceof HikarinagiSyncClient.NotAuthorizedException) {
            msg = "Hikarinagi 授权失效：" + safeStr(t.getMessage());
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

    /** 当前状态 + 时长，例如「通关 · 2小时6分」。 */
    private static String describeCurrent(Game g) {
        if (g == null) return "";
        String status = HikarinagiSyncService.statusText(
                HikarinagiSyncService.toHikarinagiStatus(g.playStatus));
        int minutes = HikarinagiSyncService.toMinutes(g.totalPlayTime);
        return minutes > 0 ? status + " · " + humanMinutes(minutes) : status;
    }

    /**
     * 一条变化的右栏文案。
     * 不读远端，所以只有「首次同步」与「设为某个状态」两种说法。
     */
    private static String describeChange(HikarinagiSyncService.ChangeItem c) {
        String target = HikarinagiSyncService.statusText(c.status);
        if (c.minutes > 0) target = target + " · " + humanMinutes(c.minutes);
        if (c.firstTime) return "加入 · " + target;
        return target;
    }

    /** 126 → "2小时6分"；45 → "45分"。 */
    private static String humanMinutes(int minutes) {
        if (minutes <= 0) return "0分";
        if (minutes < 60) return minutes + "分";
        int h = minutes / 60;
        int m = minutes % 60;
        return m == 0 ? h + "小时" : h + "小时" + m + "分";
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

    /** 可折叠分组标题：点一下才构建并显示子 View（几百条也不拖慢首屏）。 */
    private static View collapsibleSection(Context c, String header,
                                           java.util.concurrent.Callable<View> builder) {
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
                boolean vis = body[0].getVisibility() == View.VISIBLE;
                body[0].setVisibility(vis ? View.GONE : View.VISIBLE);
                title.setText(header + (vis ? "  ▸" : "  ▾"));
            }
        });
        return box;
    }

    /** 一行：左游戏名（可截断），右说明。 */
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