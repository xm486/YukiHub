package com.yuki.yukihub.bigscreen;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;
import android.widget.Toast;

import com.yuki.yukihub.MainActivity;
import com.yuki.yukihub.data.GameRepository;
import com.yuki.yukihub.launcher.EmulatorLauncher;
import com.yuki.yukihub.model.EngineType;
import com.yuki.yukihub.model.Game;

/**
 * 大屏模式的**原地启动器**（M13）。
 *
 * <p>问题背景：之前 {@code BigScreenActivity.launch()} 是"跳去 MainActivity（游戏库）再替我们启动"。
 * 而 MainActivity 是 {@code singleTask}、启动时又加了 {@code FLAG_ACTIVITY_CLEAR_TOP} —— 结果：
 * <ol>
 *   <li>游戏库界面会**闪一下**；</li>
 *   <li>更严重的是 CLEAR_TOP 会**销毁大屏实例**，游戏退出后直接落在游戏库里 —— 体验割裂。</li>
 * </ol>
 *
 * <p>这里直接复用项目里公共的静态启动工具 {@link EmulatorLauncher}，与游戏库走**同一套
 * Intent 组装**，原地启动后返回仍在游戏库/大屏原本的页面上。
 *
 * <p>只有"必须由游戏库才能处理"的少数情况（Winlator / GameHub 的已安装应用扫描与
 * localGameId 校验、PPSSPP 未安装时的下载引导）才回退到老路径 —— 保证不会出现"点了没反应"。
 */
public final class BigScreenLauncher {

    /** 与游戏库共用的 App 级偏好文件与键名（必须与 MainActivity 里的一致） */
    private static final String APP_PREFS = "yukihub_prefs";
    private static final String KEY_KR_COMPAT_MODE = "kr_compat_mode";
    private static final String KEY_KR_ENGINE_VERSION = "kr_engine_version";

    /** 与游戏库一致的会话时长上限 */
    private static final long MAX_PLAY_SESSION_MS = 12L * 60L * 60L * 1000L;

    private BigScreenLauncher() { }

    /** 启动结果：inPlace=false 表示已经回退到"跳游戏库启动" */
    public static final class Result {
        public final boolean inPlace;
        /** 本地游玩会话 id（0 = 没有开始会话），返回大屏后交给 {@link #finishSession} 收尾 */
        public final long sessionId;
        public final long startedAt;

        Result(boolean inPlace, long sessionId, long startedAt) {
            this.inPlace = inPlace;
            this.sessionId = sessionId;
            this.startedAt = startedAt;
        }
    }

    /**
     * 在大屏里原地启动游戏。
     *
     * @param repo  用于记录本次游玩时长（与游戏库共用 play_sessions 表）
     * @param prefs 大屏偏好（读「KR存档兜底」开关）
     */
    public static Result launch(Activity act, Game game, GameRepository repo, BigScreenPrefs prefs) {
        if (act == null || game == null) { return new Result(false, 0L, 0L); }

        final String pkg = resolvePackage(game);
        if (pkg == null || pkg.isEmpty()) {
            // 包名需要游戏库才能确定（已安装应用扫描 / localGameId 校验）
            launchViaLibrary(act, game);
            return new Result(false, 0L, 0L);
        }

        long sessionId = 0L;
        final long start = System.currentTimeMillis();
        try {
            if (repo != null) {
                sessionId = repo.startPlaySession(game.id, start, resolveLaunchType(pkg));
                GameRepository.resetStaleSessionHandled();
            }
        } catch (Throwable ignored) { }

        boolean started;
        try {
            started = startGame(act, game, pkg, prefs);
        } catch (Throwable t) {
            Log.w("YukiHub", "bigscreen in-place launch failed", t);
            started = false;
        }

        if (!started) {
            // 原地启动失败 → 回滚会话并交给游戏库（保持可用性优先）
            if (repo != null && sessionId > 0L) {
                try { repo.cancelPlaySession(sessionId); } catch (Throwable ignored) { }
            }
            launchViaLibrary(act, game);
            return new Result(false, 0L, 0L);
        }

        try {
            com.yuki.yukihub.social.PresenceManager pm = com.yuki.yukihub.social.PresenceManager.get(act);
            pm.setPlayingGame(game.title);
            com.yuki.yukihub.social.PresenceService.sync(act);
            com.yuki.yukihub.social.PresenceService.refresh(act);
        } catch (Throwable ignored) { }

        return new Result(true, sessionId, start);
    }

    /** 从游戏返回大屏后收尾：结束本次游玩会话（本地时长）+ 清在线状态 */
    public static void finishSession(Activity act, GameRepository repo, long sessionId, long startedAt) {
        if (repo != null && sessionId > 0L && startedAt > 0L) {
            try {
                repo.finishPlaySession(sessionId, System.currentTimeMillis(), 0L, MAX_PLAY_SESSION_MS);
            } catch (Throwable ignored) { }
        }
        try {
            if (act != null) {
                com.yuki.yukihub.social.PresenceManager pm = com.yuki.yukihub.social.PresenceManager.get(act);
                pm.clearPlayingGame();
                com.yuki.yukihub.social.PresenceService.refresh(act);
            }
        } catch (Throwable ignored) { }
    }

    /** 老路径（兜底）：交给游戏库启动。行为与改造前完全一致。 */
    public static void launchViaLibrary(Activity act, Game game) {
        if (act == null || game == null) { return; }
        try {
            Intent intent = new Intent(act, MainActivity.class);
            intent.putExtra("home_target", "launch_game");
            intent.putExtra("home_game_id", game.id);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            act.startActivity(intent);
        } catch (Throwable t) {
            Toast.makeText(act, "启动失败：未找到可用的启动方式", Toast.LENGTH_LONG).show();
        }
    }

    // ================= 内部 =================

    /** 包名兜底规则与游戏库 doLaunchGame 保持一致；返回 null = 必须交给游戏库 */
    private static String resolvePackage(Game game) {
        String pkg = game.emulatorPackage == null ? "" : game.emulatorPackage.trim();
        EngineType e = game.engine;

        if (e == EngineType.ARTEMIS) { pkg = normalizeArtemisPackage(pkg); }
        if (pkg.isEmpty() && e == EngineType.KIRIKIRI) pkg = "internal.krkr";
        if (pkg.isEmpty() && e == EngineType.ONS) pkg = "internal.ons";
        if (pkg.isEmpty() && e == EngineType.TYRANO) pkg = "internal.tyrano";
        if (pkg.isEmpty() && e == EngineType.PSP) pkg = "org.ppsspp.ppsspp";

        // 这两个引擎依赖"扫描已安装应用"和 localGameId 校验 —— 交给游戏库更稳妥
        if (e == EngineType.WINLATOR && pkg.isEmpty()) { return null; }
        if (e == EngineType.GAMEHUB) {
            String mode = game.gamehubLaunchMode == null ? "game"
                    : game.gamehubLaunchMode.trim().toLowerCase(java.util.Locale.ROOT);
            boolean normalMode = "program".equals(mode) || "normal".equals(mode);
            String localId = game.gamehubLocalGameId == null ? "" : game.gamehubLocalGameId.trim();
            if (pkg.isEmpty() || (!normalMode && localId.isEmpty())) { return null; }
        }
        return pkg;
    }

    /** 与游戏库 normalizeArtemisPackage 同一套判定（写在这里避免跨类调用私有方法） */
    private static String normalizeArtemisPackage(String value) {
        String pkg = value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
        if (pkg.contains("compat.v2") || pkg.contains("compatible_v2") || pkg.endsWith(".2")) {
            return "internal.artemis.compat.v2";
        }
        if (pkg.contains("compat")) { return "internal.artemis.compat"; }
        return "internal.artemis";
    }

    /** 会话类型标记，与游戏库 resolveLaunchType 保持一致（用于统计区分内/外部启动） */
    private static String resolveLaunchType(String emulatorPackage) {
        String pkg = emulatorPackage == null ? "" : emulatorPackage.trim().toLowerCase(java.util.Locale.ROOT);
        if (pkg.startsWith("internal.krkr") || pkg.equals("org.tvp.kirikiri2.internal")) { return "internal.krkr"; }
        if (pkg.startsWith("internal.ons") || pkg.equals("com.yuki.yukihub.ons")) { return "internal.ons"; }
        if (pkg.startsWith("internal.tyrano") || pkg.equals("com.yuki.yukihub.tyrano")) { return "internal.tyrano"; }
        if (pkg.startsWith("internal.artemis")) { return pkg; }
        return "external";
    }

    /** 真正启动：分支与游戏库 launchGameInternal 一一对应 */
    private static boolean startGame(Activity act, Game game, String pkg, BigScreenPrefs prefs) {
        String target = game.launchTarget;
        if (game.engine == EngineType.ARTEMIS || game.engine == EngineType.TYRANO) { target = "[游戏目录]"; }
        if (game.engine == EngineType.GAMEHUB) { target = game.title; }

        if (pkg.startsWith("internal.krkr") || pkg.equals("org.tvp.kirikiri2.internal")) {
            SharedPreferences app = act.getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE);
            boolean compat = app.getBoolean(KEY_KR_COMPAT_MODE, false);
            String engineVersion = app.getString(KEY_KR_ENGINE_VERSION, "auto");
            // 游戏库会先做一次存储探测再决定 SAF 兜底；大屏不做探测（探测代码在 MainActivity 内部），
            // 改为由设置项「KR存档兜底」手动控制 —— 默认关，与"游戏目录可写"的常见情况一致。
            boolean safFallback = prefs != null && prefs.krSafFallback();
            return start(act, EmulatorLauncher.buildInternalKrkrIntent(
                    act, game.rootUri, target, false, compat, engineVersion, safFallback));
        }
        if (pkg.startsWith("internal.tyrano") || pkg.equals("com.yuki.yukihub.tyrano")) {
            return start(act, EmulatorLauncher.buildInternalTyranoIntent(act, game.rootUri, target));
        }
        if (pkg.startsWith("internal.ons") || pkg.equals("com.yuki.yukihub.ons")) {
            return start(act, EmulatorLauncher.buildInternalOnsIntent(act, game.rootUri, target));
        }
        if (pkg.startsWith("internal.artemis")) {
            return start(act, EmulatorLauncher.buildInternalArtemisIntent(act, pkg, game.rootUri, target));
        }
        if (pkg.startsWith("internal.psp") || pkg.equals("org.ppsspp.ppsspp")) {
            // 没装 PPSSPP：交给游戏库（那里有下载引导对话框）
            if (!EmulatorLauncher.isPPSSPPInstalled(act)) { return false; }
            return start(act, EmulatorLauncher.buildInternalPspIntent(act, game.rootUri, target));
        }
        if (game.engine == EngineType.ANDROID) {
            return EmulatorLauncher.launch(act, pkg);
        }
        // 外部模拟器（Winlator / GameHub / 其它）
        return EmulatorLauncher.launchGame(act, pkg, game.rootUri, target,
                game.winlatorLaunchMode, game.gamehubLaunchMode, game.gamehubLocalGameId);
    }

    private static boolean start(Activity act, Intent intent) {
        if (intent == null) { return false; }
        try {
            act.startActivity(intent);
            return true;
        } catch (Throwable t) {
            Log.w("YukiHub", "startActivity failed", t);
            return false;
        }
    }
}