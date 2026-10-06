package com.yuki.yukihub.launcher;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

/**
 * PC 引擎（winlator-cn 外置启动）的单游戏覆盖参数。
 *
 * <p>设计对齐 Tyranor-Next 的「跟随容器配置」语义：**空串 = 不下发该参数**，
 * 交由 Winlator 按目标容器自身配置处理；只有用户显式选值的项才会随 Intent 下发。
 * 只做单游戏覆盖，不做全局设置层——想全局调就直接去 Winlator 容器里改，
 * 那边才是参数的正确归属地（YukiHub 不重复造轮子）。
 *
 * <p>存储：SharedPreferences（PREF_NAME），键为 "game_&lt;id&gt;"，值为 JSON。
 * 与 ONS 设置同为「引擎侧偏好」，不进数据库、不参与云同步。
 */
public final class PcLaunchPrefs {

    private static final String PREF_NAME = "pc_launch_prefs";
    private static final String KEY_PREFIX = "game_";

    /** 空串 = 跟随容器配置（不下发）。 */
    public static final String FOLLOW = "";

    /** 容器 ID（数字字符串，空 = 不下发；"0" 等同不下发）。 */
    public String containerId = FOLLOW;
    /** 容器名（大小写不敏感精确匹配，支持中文；容器 ID 优先）。 */
    public String containerName = FOLLOW;
    /** 图形驱动：vulkan,opengl 组合。 */
    public String graphicsDriver = FOLLOW;
    /** 图形加速：dxvk / wined3d。 */
    public String dxwrapper = FOLLOW;
    /** 分辨率：如 1600x900（winlator-cn 要求 ≥320x160）。 */
    public String screenSize = FOLLOW;
    /** 语言环境 LC_ALL。 */
    public String lcAll = FOLLOW;
    /** Box64 预设（英文枚举值；UI 展示中文标签）。 */
    public String box64Preset = FOLLOW;
    /** 追加启动参数（会话级，不写入容器配置）。 */
    public String execArgs = FOLLOW;
    /** 强制全屏："1" / "0"（空 = 不下发）。 */
    public String forceFullscreen = FOLLOW;
    /** 可切换全屏："1" / "0"（空 = 不下发）。 */
    public String toggleFullscreen = FOLLOW;
    /** 屏幕方向：landscape / portrait（空 = 不下发）。 */
    public String screenOrientation = FOLLOW;
    /** 交换宽高：true / false（空 = 不下发）。 */
    public String swapResolution = FOLLOW;
    /** 音频驱动：alsa / pulseaudio（空 = 不下发）。 */
    public String audioDriver = FOLLOW;
    /** 环境变量：空格分隔的 名称=值（如 WINEDLLOVERRIDES=... 或 TZ=Asia/Tokyo）。 */
    public String envVars = FOLLOW;
    /** true 时把本次覆盖写入容器配置（静默持久化）。默认关，避免改坏用户容器。 */
    public boolean save = false;

    // ================= 取值域（对齐 winlator-cn 校验规则） =================

    /** 图形驱动：Vulkan 驱动（Turnip/Vortek）× OpenGL 驱动（Zink/VirGL/Gladio）组合。 */
    public static final String[] GRAPHICS_DRIVER_LABELS = {
            "跟随容器配置", "Turnip + Zink（通用推荐）", "Turnip + VirGL（旧游戏兼容）", "Turnip + Gladio",
            "Vortek + Zink（默认驱动）", "Vortek + VirGL", "Vortek + Gladio"
    };
    public static final String[] GRAPHICS_DRIVER_VALUES = {
            FOLLOW, "turnip,zink", "turnip,virgl", "turnip,gladio",
            "vortek,zink", "vortek,virgl", "vortek,gladio"
    };

    /** 图形加速。 */
    public static final String[] DXWRAPPER_LABELS = {
            "跟随容器配置", "DXVK（D3D 转 Vulkan）", "WineD3D（兼容优先）"
    };
    public static final String[] DXWRAPPER_VALUES = {
            FOLLOW, "dxvk", "wined3d"
    };

    /** 分辨率固定档位（对齐 winlator-cn ≥320x160 校验）。 */
    public static final String[] SCREEN_SIZE_LABELS = {
            "跟随容器配置", "640x360", "640x480", "800x600", "1024x768",
            "1280x720", "1600x900", "1920x1080"
    };
    public static final String[] SCREEN_SIZE_VALUES = {
            FOLLOW, "640x360", "640x480", "800x600", "1024x768",
            "1280x720", "1600x900", "1920x1080"
    };

    /** 语言环境（日文 Gal 常需 ja_JP.UTF-8）。 */
    public static final String[] LC_ALL_LABELS = {
            "跟随容器配置", "ja_JP.UTF-8（日文游戏）", "zh_CN.utf8（中文游戏）", "en_US.UTF-8"
    };
    public static final String[] LC_ALL_VALUES = {
            FOLLOW, "ja_JP.UTF-8", "zh_CN.utf8", "en_US.UTF-8"
    };

    /** Box64 预设：中文标签 ↔ winlator-cn Box64Preset 枚举值。 */
    public static final String[] BOX64_LABELS = {
            "跟随容器配置", "稳定模式", "兼容模式", "均衡模式", "性能模式"
    };
    public static final String[] BOX64_VALUES = {
            FOLLOW, "STABILITY", "CONSERVATIVE", "INTERMEDIATE", "PERFORMANCE"
    };

    /** 通用「1/0」三态（强制全屏、可切换全屏等）：空 = 不下发（此处无法表达覆盖为 0 的语义差异，按容器配置走）。 */
    public static final String[] BOOL10_LABELS = {
            "跟随容器配置", "开启", "关闭"
    };
    public static final String[] BOOL10_VALUES = {
            FOLLOW, "1", "0"
    };

    /** 屏幕方向：winlator-cn 仅接受 landscape / portrait。 */
    public static final String[] ORIENTATION_LABELS = {
            "跟随容器配置", "横屏（landscape）", "竖屏（portrait）"
    };
    public static final String[] ORIENTATION_VALUES = {
            FOLLOW, "landscape", "portrait"
    };

    /** 交换宽高：winlator-cn 接受 true / false。 */
    public static final String[] SWAP_LABELS = {
            "跟随容器配置", "交换宽高", "不交换宽高"
    };
    public static final String[] SWAP_VALUES = {
            FOLLOW, "true", "false"
    };

    /** 音频驱动：winlator-cn 仅接受 alsa / pulseaudio。 */
    public static final String[] AUDIO_LABELS = {
            "跟随容器配置", "ALSA", "PulseAudio"
    };
    public static final String[] AUDIO_VALUES = {
            FOLLOW, "alsa", "pulseaudio"
    };

    /** save 开关的两态（沿用「跟随含义」：关 = 不写回容器配置）。 */
    public static final String[] SAVE_LABELS = {"关（不写入容器配置）", "开（写入容器配置）"};

    // ================= 读写 =================

    public static PcLaunchPrefs load(Context context, long gameId) {
        PcLaunchPrefs p = new PcLaunchPrefs();
        if (context == null || gameId <= 0) return p;
        try {
            SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            String json = sp.getString(KEY_PREFIX + gameId, null);
            if (json == null || json.trim().isEmpty()) return p;
            JSONObject o = new JSONObject(json);
            p.containerId = normalizeContainerId(o.optString("container_id", FOLLOW));
            p.containerName = o.optString("container_name", FOLLOW).trim();
            p.graphicsDriver = normalizeIn(o.optString("graphics_driver", FOLLOW), GRAPHICS_DRIVER_VALUES);
            p.dxwrapper = normalizeIn(o.optString("dxwrapper", FOLLOW), DXWRAPPER_VALUES);
            p.screenSize = normalizeIn(o.optString("screen_size", FOLLOW), SCREEN_SIZE_VALUES);
            p.lcAll = normalizeIn(o.optString("lc_all", FOLLOW), LC_ALL_VALUES);
            p.box64Preset = normalizeIn(o.optString("box64_preset", FOLLOW), BOX64_VALUES);
            p.execArgs = normalizeExecArgs(o.optString("exec_args", FOLLOW));
            p.forceFullscreen = normalizeIn(o.optString("force_fullscreen", FOLLOW), BOOL10_VALUES);
            p.toggleFullscreen = normalizeIn(o.optString("toggle_fullscreen", FOLLOW), BOOL10_VALUES);
            p.screenOrientation = normalizeIn(o.optString("screen_orientation", FOLLOW), ORIENTATION_VALUES);
            p.swapResolution = normalizeIn(o.optString("swap_resolution", FOLLOW), SWAP_VALUES);
            p.audioDriver = normalizeIn(o.optString("audio_driver", FOLLOW), AUDIO_VALUES);
            p.envVars = normalizeEnvVars(o.optString("env_vars", FOLLOW));
            p.save = o.optBoolean("save", false);
        } catch (Throwable ignored) { }
        return p;
    }

    public void save(Context context, long gameId) {
        if (context == null || gameId <= 0) return;
        try {
            JSONObject o = new JSONObject();
            o.put("container_id", normalizeContainerId(containerId));
            o.put("container_name", containerName == null ? FOLLOW : containerName.trim());
            o.put("graphics_driver", normalizeIn(graphicsDriver, GRAPHICS_DRIVER_VALUES));
            o.put("dxwrapper", normalizeIn(dxwrapper, DXWRAPPER_VALUES));
            o.put("screen_size", normalizeIn(screenSize, SCREEN_SIZE_VALUES));
            o.put("lc_all", normalizeIn(lcAll, LC_ALL_VALUES));
            o.put("box64_preset", normalizeIn(box64Preset, BOX64_VALUES));
            o.put("exec_args", normalizeExecArgs(execArgs));
            o.put("force_fullscreen", normalizeIn(forceFullscreen, BOOL10_VALUES));
            o.put("toggle_fullscreen", normalizeIn(toggleFullscreen, BOOL10_VALUES));
            o.put("screen_orientation", normalizeIn(screenOrientation, ORIENTATION_VALUES));
            o.put("swap_resolution", normalizeIn(swapResolution, SWAP_VALUES));
            o.put("audio_driver", normalizeIn(audioDriver, AUDIO_VALUES));
            o.put("env_vars", normalizeEnvVars(envVars));
            o.put("save", save);
            context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                    .edit().putString(KEY_PREFIX + gameId, o.toString()).apply();
        } catch (Throwable ignored) { }
    }

    /** 游戏删除时顺带清理，避免 prefs 残留（按 id 复用不会串，但清理更干净）。 */
    public static void clear(Context context, long gameId) {
        if (context == null || gameId <= 0) return;
        try {
            context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                    .edit().remove(KEY_PREFIX + gameId).apply();
        } catch (Throwable ignored) { }
    }

    /** 是否全部跟随容器配置（下发时可直接跳过参数收集）。 */
    public boolean isAllFollow() {
        return normalizeContainerId(containerId).isEmpty()
                && (containerName == null || containerName.trim().isEmpty())
                && normalizeIn(graphicsDriver, GRAPHICS_DRIVER_VALUES).isEmpty()
                && normalizeIn(dxwrapper, DXWRAPPER_VALUES).isEmpty()
                && normalizeIn(screenSize, SCREEN_SIZE_VALUES).isEmpty()
                && normalizeIn(lcAll, LC_ALL_VALUES).isEmpty()
                && normalizeIn(box64Preset, BOX64_VALUES).isEmpty()
                && normalizeExecArgs(execArgs).isEmpty()
                && normalizeIn(forceFullscreen, BOOL10_VALUES).isEmpty()
                && normalizeIn(toggleFullscreen, BOOL10_VALUES).isEmpty()
                && normalizeIn(screenOrientation, ORIENTATION_VALUES).isEmpty()
                && normalizeIn(swapResolution, SWAP_VALUES).isEmpty()
                && normalizeIn(audioDriver, AUDIO_VALUES).isEmpty()
                && normalizeEnvVars(envVars).isEmpty()
                && !save;
    }

    // ================= 归一（非法值归回「跟随」，不硬塞给 Winlator） =================

    /** 容器 ID：只接受非负整数；"0"/非法/空 = 不下发。 */
    public static String normalizeContainerId(String value) {
        if (value == null) return FOLLOW;
        String v = value.trim();
        if (v.isEmpty()) return FOLLOW;
        try {
            int id = Integer.parseInt(v);
            return id > 0 ? String.valueOf(id) : FOLLOW;
        } catch (NumberFormatException e) {
            return FOLLOW;
        }
    }

    /** 白名单归一：命中返回值，否则归回「跟随」。 */
    private static String normalizeIn(String value, String[] allowed) {
        if (value == null) return FOLLOW;
        String v = value.trim();
        if (v.isEmpty()) return FOLLOW;
        for (String a : allowed) {
            if (a.equalsIgnoreCase(v)) return a;
        }
        return FOLLOW;
    }

    /**
     * 环境变量：空格分隔的「名称=值」，每段须含 '=' 且键非空（对齐 winlator-cn 校验）；非法归回「跟随」。
     * （时区需求可用环境变量表达，如 TZ=Asia/Tokyo，与 winlator-cn 的 tz 覆盖殊途同归。）
     */
    public static String normalizeEnvVars(String value) {
        if (value == null) return FOLLOW;
        String v = value.trim();
        if (v.isEmpty()) return FOLLOW;
        for (String token : v.split(" ")) {
            if (token.isEmpty() || token.indexOf('=') <= 0) return FOLLOW;
        }
        return v;
    }

    /** 追加启动参数：自由文本（winlator-cn 不校验），trim 后原样下发（会话级，不写入容器）。 */
    public static String normalizeExecArgs(String value) {
        if (value == null) return FOLLOW;
        String v = value.trim();
        return v.isEmpty() ? FOLLOW : v;
    }

    /** 标签数组 → 值（UI 保存用）；越界/非法归回「跟随」。 */
    public static String valueOfLabel(String[] labels, String[] values, String label) {
        if (labels == null || values == null || label == null) return FOLLOW;
        for (int i = 0; i < labels.length && i < values.length; i++) {
            if (labels[i].equals(label)) return values[i];
        }
        return FOLLOW;
    }

    /** 值 → 标签（UI 回显用）；非法归到「跟随容器配置」（第 0 项）。 */
    public static String labelOfValue(String[] labels, String[] values, String value) {
        if (labels == null || values == null || labels.length == 0) return FOLLOW;
        String v = value == null ? FOLLOW : value.trim();
        for (int i = 0; i < labels.length && i < values.length; i++) {
            if (values[i].equalsIgnoreCase(v)) return labels[i];
        }
        return labels[0];
    }

    /** 供 UI 生成「跟随容器配置 / xx / xx」下拉项。 */
    public static String[] labelOptions(String[] labels) {
        return labels == null ? new String[0] : labels.clone();
    }
}