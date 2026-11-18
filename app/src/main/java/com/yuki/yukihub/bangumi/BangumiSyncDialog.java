package com.yuki.yukihub.bangumi;

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
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.yuki.yukihub.model.Game;
import com.yuki.yukihub.util.AppExecutors;

import java.util.List;

/**
 * Bangumi 游玩状态同步的界面流程：token 设置 → 预览 → 推送 → 汇总。
 * <p>
 * 结构与 VNDB 版本一致（同样是「粘贴 token」而非 OAuth）。
 *
 * <h3>线程约束</h3>
 * 所有网络请求（含 token 校验）一律在 IO 线程。
 * 主线程只做纯本地判断（{@link BangumiSyncService#isConnected}），绝不联网。
 * 这条是 NextMoe 那次的教训：主线程发请求会被 StrictMode 拦成
 * {@code NetworkOnMainThreadException}，表现得像「授权掉了」。
 */
public final class BangumiSyncDialog {

    private BangumiSyncDialog() { }

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
     * 这里只做<b>纯本地</b>的 isConnected 判断，不联网。
     */
    public static void start(Activity activity) {
        String token = BangumiSyncService.getToken(activity);
        if (token == null || token.trim().isEmpty()) {
            showTokenDialog(activity);
            return;
        }
        runPrepare(activity, token);
    }

    // ======================== token 设置 ========================

    private static void showTokenDialog(Activity activity) {
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(activity, 16);
        root.setPadding(pad, dp(activity, 8), pad, dp(activity, 4));
        root.addView(bodyText(activity,
                "同步游玩状态到 Bangumi 需要一个 Access Token。\n\n"
                        + "1. 打开 next.bgm.tv/demo/access-token\n"
                        + "2. 登录后复制生成的 token\n"
                        + "3. 粘贴到下面\n\n"
                        + "只会修改「收藏状态」（想看 / 看过 / 在看 / 搁置 / 抛弃），"
                        + "不会动你的评分、吐槽、标签和私密设置。"));
        final EditText input = new EditText(activity);
        input.setHint("粘贴 Bangumi Access Token");
        input.setTextSize(12);
        input.setSingleLine(true);
        root.addView(input);

        new AlertDialog.Builder(activity)
                .setTitle("连接 Bangumi")
                .setView(wrapScroll(activity, root))
                .setPositiveButton("保存并校验", (d, w) -> {
                    String t = input.getText() == null ? "" : input.getText().toString().trim();
                    if (t.isEmpty()) {
                        Toast.makeText(activity, "token 不能为空", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    verifyAndSave(activity, t);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 在 IO 线程校验 token，成功才保存。 */
    private static void verifyAndSave(Activity activity, String token) {
        ProgressDialog pd = new ProgressDialog(activity);
        pd.setMessage("正在校验 token…");
        pd.setCancelable(false);
        pd.show();

        AppExecutors.runOnIo(() -> {
            String err = null;
            String name = null;
            try {
                BangumiSyncClient.AuthInfo info = BangumiSyncClient.authInfo(token);
                name = info.displayName();
            } catch (Throwable t) {
                err = t.getMessage() == null ? "校验失败" : t.getMessage();
            }
            final String e = err;
            final String n = name;
            UI.post(() -> {
                safeDismiss(pd);
                if (!alive(activity)) return;
                if (e != null) {
                    new AlertDialog.Builder(activity)
                            .setTitle("token 不可用")
                            .setMessage(e)
                            .setPositiveButton("重新输入", (d, w) -> showTokenDialog(activity))
                            .setNegativeButton("取消", null)
                            .show();
                    return;
                }
                BangumiSyncService.saveToken(activity, token);
                Toast.makeText(activity, "已连接：" + (n == null || n.isEmpty() ? "Bangumi" : n),
                        Toast.LENGTH_SHORT).show();
                runPrepare(activity, token);
            });
        });
    }

    // ======================== 阶段 1：匹配 ========================

    private static void runPrepare(Activity activity, String token) {
        ProgressDialog pd = new ProgressDialog(activity);
        pd.setMessage("正在匹配条目…");
        pd.setCancelable(false);
        pd.show();

        AppExecutors.runOnIo(() -> {
            try {
                BangumiSyncService.Preview preview = BangumiSyncService.prepare(activity, token,
                        (done, total) -> UI.post(() -> {
                            if (!alive(activity)) return;
                            try { pd.setMessage("正在匹配… " + done + " / " + total); }
                            catch (Throwable ignored) { }
                        }));
                UI.post(() -> {
                    safeDismiss(pd);
                    if (!alive(activity)) return;
                    showPreview(activity, token, preview);
                });
            } catch (BangumiSyncClient.NotAuthorizedException e) {
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
     * token 失效：不静默清除本地 token（可能只是服务端暂时不可用），
     * 而是明确告知并给「重新填写」的入口。
     */
    private static void handleAuthError(Activity activity, String msg) {
        UI.post(() -> {
            if (!alive(activity)) return;
            new AlertDialog.Builder(activity)
                    .setTitle("Bangumi 授权有问题")
                    .setMessage(msg)
                    .setPositiveButton("重新填写 token", (d, w) -> showTokenDialog(activity))
                    .setNegativeButton("取消", null)
                    .show();
        });
    }

    // ======================== 阶段 2：预览 ========================

    private static void showPreview(Activity activity, String token, BangumiSyncService.Preview preview) {
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(activity, 16);
        root.setPadding(pad, dp(activity, 10), pad, dp(activity, 4));

        if (preview.isEmpty() && preview.unmatched.isEmpty()) {
            root.addView(bodyText(activity,
                    "没有可同步的游戏。\n\n"
                            + "只有标了游玩状态的游戏才能同步。"));
            new AlertDialog.Builder(activity)
                    .setTitle("同步到 Bangumi")
                    .setView(wrapScroll(activity, root))
                    .setPositiveButton("好", null)
                    .show();
            return;
        }

        TextView summary = bodyText(activity, buildSummary(preview));
        summary.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(summary);

        // ---- 本次有更新 ----
        List<BangumiSyncService.ChangeItem> changed = preview.changed;
        if (!changed.isEmpty()) {
            root.addView(sectionHeader(activity, "本次有更新（" + changed.size() + "）"));
            for (BangumiSyncService.ChangeItem c : changed) {
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
                        for (BangumiSyncService.Matched m : preview.skipped) {
                            box.addView(row(activity, safeTitle(m.game),
                                    BangumiSyncService.typeText(
                                            BangumiSyncService.toBangumiType(m.game.playStatus))));
                        }
                        return box;
                    }));
        }

        // ---- 无法匹配 ----
        if (!preview.unmatched.isEmpty()) {
            root.addView(sectionHeader(activity, "无法匹配（" + preview.unmatched.size() + "）"));
            for (Game g : preview.unmatched) {
                root.addView(row(activity, "⚠ " + safeTitle(g), "Bangumi 上没找到"));
            }
            TextView warn = bodyText(activity,
                    "已优先用已有的 Bangumi id，没有的再用标题搜索，仍未精确命中。\n"
                            + "可能是 Bangumi 未收录，或标题与 Bangumi 的条目名差异较大。\n"
                            + "本地数据不受影响。");
            warn.setTextColor(0xFFFFB74D);
            warn.setPadding(0, dp(activity, 8), 0, 0);
            root.addView(warn);
        }

        AlertDialog.Builder b = new AlertDialog.Builder(activity)
                .setTitle("同步到 Bangumi")
                .setView(wrapScroll(activity, root))
                .setNegativeButton("取消", null);
        if (preview.changesCount == 0) {
            b.setPositiveButton("好", null);
            b.show();
            return;
        }
        b.setPositiveButton("开始同步", (d, w) -> runExecute(activity, token, preview));
        b.show();
    }

    private static String buildSummary(BangumiSyncService.Preview p) {
        StringBuilder sb = new StringBuilder();
        if (p.username != null && !p.username.isEmpty()) {
            sb.append("Bangumi 账号：").append(p.username).append('\n');
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
        return sb.toString();
    }

    // ======================== 阶段 3：执行 ========================

    private static void runExecute(Activity activity, String token, BangumiSyncService.Preview preview) {
        ProgressDialog pd = new ProgressDialog(activity);
        pd.setMessage("正在同步…");
        pd.setCancelable(false);
        pd.show();

        AppExecutors.runOnIo(() -> {
            try {
                BangumiSyncService.Result result = BangumiSyncService.execute(activity, token, preview);
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

    private static void showResult(Activity activity, BangumiSyncService.Result r) {
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(activity, 16);
        root.setPadding(pad, dp(activity, 10), pad, dp(activity, 4));

        root.addView(bodyText(activity, "✅ 成功更新 " + r.ok + " 个"));
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
            for (BangumiSyncService.ItemFailure f : r.failures) {
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
        if (t instanceof BangumiSyncClient.RateLimitedException) {
            msg = "Bangumi 请求过于频繁，请过几分钟再试。";
        } else if (t instanceof BangumiSyncClient.NotAuthorizedException) {
            msg = "Bangumi 授权失效：" + safeStr(t.getMessage());
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

    /**
     * 一条变化的右栏文案。
     * 不读远端，所以只有「首次同步」与「设为某个状态」两种说法。
     */
    private static String describeChange(BangumiSyncService.ChangeItem c) {
        String target = BangumiSyncService.typeText(c.targetType);
        if (c.firstTime) return "加入 · " + target;
        return "设为 " + target;
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