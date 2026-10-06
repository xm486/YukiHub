package com.yuki.yukihub.launcher;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import java.io.File;

/**
 * PC 引擎（winlator-cn 外置启动协议）。
 *
 * <p>与旧 WINLATOR 引擎的猜测式启动完全不同：这里走的是 winlator-cn 官方提供的
 * 导出入口 {@code com.winlator.ExternalLaunchActivity}：调用方只需传「游戏目录 +
 * exe 文件名」，Winlator 会自动分配空闲盘符把目录临时挂载进容器（save=false 不写回
 * 容器配置），再按相对路径启动 exe。协议无 SDK、无 AIDL、无回调。
 *
 * <p>协议 extras（来源：winlator-cn docs/external-launch-guide.md）：
 * <ul>
 *   <li>dir_path —— 待挂载并启动的目录（Unix 绝对路径，必传）</li>
 *   <li>exe_path —— 可执行文件：绝对路径或相对 dir_path 的文件名（必传）</li>
 *   <li>confirm —— 是否弹 Winlator 侧确认框（默认 true）</li>
 *   <li>launch_id —— 调用方自定义标识，用于日志与会话内防抖</li>
 * </ul>
 */
public class WinlatorPcLauncher {

    /** winlator-cn 的安装包名（applicationId）。 */
    public static final String PACKAGE_NAME = "com.winlator";
    /** 外置启动协议入口。 */
    public static final String ACTIVITY_NAME = "com.winlator.ExternalLaunchActivity";

    /** 待挂载并启动的目录（Unix 绝对路径）。 */
    public static final String EXTRA_DIR_PATH = "dir_path";
    /** 可执行文件：绝对路径或相对 dir_path 的文件名。 */
    public static final String EXTRA_EXE_PATH = "exe_path";
    /** 是否弹 Winlator 侧确认框。 */
    public static final String EXTRA_CONFIRM = "confirm";
    /** 调用方自定义标识（日志/防抖）。 */
    public static final String EXTRA_LAUNCH_ID = "launch_id";

    // ===== 单游戏覆盖参数（空值不下发 = 跟随容器配置） =====
    /** 目标容器 id（0/缺省 = 走 Winlator 回退链）。 */
    public static final String EXTRA_CONTAINER_ID = "container_id";
    /** 目标容器名（大小写不敏感精确匹配；容器 id 优先）。 */
    public static final String EXTRA_CONTAINER_NAME = "container_name";
    /** 图形驱动（如 turnip,zink）。 */
    public static final String EXTRA_GRAPHICS_DRIVER = "graphics_driver";
    /** 图形加速：dxvk / wined3d。 */
    public static final String EXTRA_DXWRAPPER = "dxwrapper";
    /** 分辨率（如 1600x900，winlator-cn 要求 ≥320x160）。 */
    public static final String EXTRA_SCREEN_SIZE = "screen_size";
    /** 客机语言环境（如 ja_JP.UTF-8）。 */
    public static final String EXTRA_LC_ALL = "lc_all";
    /** 批量覆盖配置（JSON 对象字符串）。 */
    public static final String EXTRA_OVERRIDES = "overrides";
    /** true 时把本次覆盖写入容器配置（静默持久化）。 */
    public static final String EXTRA_SAVE = "save";
    /** overrides JSON 里的 Box64 预设键名。 */
    public static final String OVERRIDE_BOX64_PRESET = "box64Preset";

    public static final String CODE_SUCCESS = "success";
    public static final String CODE_PACKAGE_NOT_INSTALLED = "package_not_installed";
    public static final String CODE_EXTERNAL_LAUNCH_UNSUPPORTED = "external_launch_unsupported";
    public static final String CODE_DIR_UNRESOLVED = "dir_unresolved";
    public static final String CODE_EXE_UNRESOLVED = "exe_unresolved";
    public static final String CODE_ACTIVITY_NOT_FOUND = "activity_not_found";
    public static final String CODE_SECURITY_EXCEPTION = "security_exception";
    public static final String CODE_LAUNCH_EXCEPTION = "launch_exception";

    private WinlatorPcLauncher() { }

    /** winlator-cn（com.winlator）是否已安装。 */
    public static boolean isInstalled(Context context) {
        try {
            context.getPackageManager().getPackageInfo(PACKAGE_NAME, 0);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 外置启动入口是否可用：已安装但缺少导出入口时返回 false
     * （旧版/官版 Winlator 没有 ExternalLaunchActivity，错误码 external_launch_unsupported）。
     */
    public static boolean isExternalLaunchSupported(Context context) {
        try {
            context.getPackageManager()
                    .getActivityInfo(new android.content.ComponentName(PACKAGE_NAME, ACTIVITY_NAME), 0);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 启动结果：code 含错误码，便于调用方按码提示。 */
    public static class Result {
        public final boolean success;
        public final String code;

        Result(boolean success, String code) {
            this.success = success;
            this.code = code;
        }
    }

    /**
     * 经 winlator-cn 外置启动协议启动一个 Windows 游戏（不下发任何覆盖参数，全部走容器配置）。
     *
     * @param rootUri  游戏目录（YukiHub 存的 SAF/file 路径，内部会转成真实文件路径）
     * @param exeName  相对游戏目录的 exe 路径（如 "game.exe"、"bin/game.exe"），也接受目录内的绝对路径
     */
    public static Result launch(Context context, String rootUri, String exeName) {
        return launch(context, rootUri, exeName, null);
    }

    /**
     * 经 winlator-cn 外置启动协议启动一个 Windows 游戏。
     *
     * @param overrides 单游戏覆盖参数（null 或全「跟随容器配置」= 不下发，交由 Winlator 按容器处理）
     */
    public static Result launch(Context context, String rootUri, String exeName, PcLaunchPrefs overrides) {
        Context app = context.getApplicationContext();
        if (!isInstalled(app)) return new Result(false, CODE_PACKAGE_NOT_INSTALLED);
        if (!isExternalLaunchSupported(app)) return new Result(false, CODE_EXTERNAL_LAUNCH_UNSUPPORTED);

        String dirPath = resolveRealPath(rootUri);
        if (dirPath == null || dirPath.trim().isEmpty()) {
            return new Result(false, CODE_DIR_UNRESOLVED);
        }
        File dir = new File(dirPath);
        if (!dir.isDirectory()) {
            return new Result(false, CODE_DIR_UNRESOLVED);
        }
        String exe = exeName == null ? "" : exeName.trim();
        if (exe.isEmpty() || "[游戏目录]".equals(exe)) {
            // 未指定启动文件：目录内恰有一个 .exe 时自动选中
            String auto = findSingleExe(dir);
            if (auto == null) return new Result(false, CODE_EXE_UNRESOLVED);
            exe = auto;
        } else if (exe.startsWith("/")) {
            // 绝对路径：位于游戏目录内时转为相对路径（协议 exe_path 相对 dir_path）
            try {
                String dirCanon = dir.getCanonicalPath();
                String exeCanon = new File(exe).getCanonicalPath();
                if (exeCanon.startsWith(dirCanon + "/")) {
                    exe = exeCanon.substring(dirCanon.length() + 1);
                }
            } catch (Throwable ignored) { }
        }
        // 存在性校验（相对 dir，支持子目录如 bin/game.exe）；下拉提示语/已删除的文件直接拦截
        if (!new File(dir, exe).isFile()) {
            return new Result(false, CODE_EXE_UNRESOLVED);
        }

        Intent intent = new Intent();
        intent.setClassName(PACKAGE_NAME, ACTIVITY_NAME);
        intent.putExtra(EXTRA_DIR_PATH, dirPath);
        intent.putExtra(EXTRA_EXE_PATH, exe);
        intent.putExtra(EXTRA_CONFIRM, true);
        intent.putExtra(EXTRA_LAUNCH_ID, "yukihub-" + System.currentTimeMillis());
        // 单游戏覆盖参数：空值不下发（「跟随容器配置」），只下发用户显式选择的项
        if (overrides != null) applyOverrides(intent, overrides);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            app.startActivity(intent);
            return new Result(true, CODE_SUCCESS);
        } catch (ActivityNotFoundException e) {
            return new Result(false, CODE_ACTIVITY_NOT_FOUND);
        } catch (SecurityException e) {
            return new Result(false, CODE_SECURITY_EXCEPTION);
        } catch (Throwable t) {
            return new Result(false, CODE_LAUNCH_EXCEPTION);
        }
    }

    /** 把覆盖参数按 winlator-cn 协议塞进 Intent（空值一律不下发）。 */
    private static void applyOverrides(Intent intent, PcLaunchPrefs o) {
        if (o == null) return;
        try {
            // 专用 extra：容器定位 + 五件套（最新版 winlator-cn 会把它们 merge 进 overrides，
            // 优先于 JSON 同名键；已实机验证生效）
            String containerId = PcLaunchPrefs.normalizeContainerId(o.containerId);
            if (!containerId.isEmpty()) intent.putExtra(EXTRA_CONTAINER_ID, Integer.parseInt(containerId));
            String containerName = o.containerName == null ? "" : o.containerName.trim();
            if (!containerName.isEmpty()) intent.putExtra(EXTRA_CONTAINER_NAME, containerName);
            if (notBlank(o.graphicsDriver)) intent.putExtra(EXTRA_GRAPHICS_DRIVER, o.graphicsDriver.trim());
            if (notBlank(o.dxwrapper)) intent.putExtra(EXTRA_DXWRAPPER, o.dxwrapper.trim());
            if (notBlank(o.screenSize)) intent.putExtra(EXTRA_SCREEN_SIZE, o.screenSize.trim());
            if (notBlank(o.lcAll)) intent.putExtra(EXTRA_LC_ALL, o.lcAll.trim());

            // 其余键没有专用 extra，走 overrides JSON。
            // 用 JSONObject 序列化：envVars 可能含引号、空格、逗号，手拼字符串会破坏 JSON。
            org.json.JSONObject overrides = new org.json.JSONObject();
            putIfNotBlank(overrides, OVERRIDE_BOX64_PRESET, o.box64Preset);
            putIfNotBlank(overrides, "execArgs", o.execArgs);
            putIfNotBlank(overrides, "forceFullscreen", o.forceFullscreen);
            putIfNotBlank(overrides, "toggleFullscreen", o.toggleFullscreen);
            putIfNotBlank(overrides, "screenOrientation", o.screenOrientation);
            putIfNotBlank(overrides, "swapResolution", o.swapResolution);
            putIfNotBlank(overrides, "audioDriver", o.audioDriver);
            putIfNotBlank(overrides, "envVars", PcLaunchPrefs.normalizeEnvVars(o.envVars));
            if (overrides.length() > 0) {
                intent.putExtra(EXTRA_OVERRIDES, overrides.toString());
                android.util.Log.i("WinlatorPcLauncher", "pc launch overrides=" + overrides);
            }

            if (o.save) intent.putExtra(EXTRA_SAVE, true);
        } catch (Throwable ignored) { }
    }

    private static void putIfNotBlank(org.json.JSONObject json, String key, String value) {
        if (value == null) return;
        String v = value.trim();
        if (v.isEmpty()) return;
        try {
            json.put(key, v);
        } catch (Throwable ignored) { }
    }

    private static boolean notBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }

    /** SAF/file URI → 真实文件路径；失败返回 null（交给调用方降级提示）。 */
    private static String resolveRealPath(String rootUri) {
        if (rootUri == null) return null;
        String t = rootUri.trim();
        if (t.isEmpty()) return null;
        if (t.startsWith("/")) return t;
        if (t.startsWith("file://")) {
            String p = Uri.parse(t).getPath();
            return p == null ? null : p;
        }
        if (t.startsWith("content://")) {
            try {
                String resolved = EmulatorLauncher.uriToFilePath(t);
                // content:// 解析失败时原样返回 content://，这里无法使用
                return (resolved == null || resolved.startsWith("content://")) ? null : resolved;
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    /** 目录里只有一个 .exe 时返回它的文件名，找不到唯一候选返回 null。 */
    private static String findSingleExe(File dir) {
        File[] children = dir.listFiles();
        if (children == null) return null;
        String found = null;
        for (File child : children) {
            if (child == null || !child.isFile()) continue;
            String name = child.getName();
            if (name != null && name.toLowerCase(java.util.Locale.ROOT).endsWith(".exe")) {
                if (found != null) return null; // 多个 exe，无法自动选择
                found = name;
            }
        }
        return found;
    }
}
