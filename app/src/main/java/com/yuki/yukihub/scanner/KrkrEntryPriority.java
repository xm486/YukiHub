package com.yuki.yukihub.scanner;

import java.util.Locale;

/**
 * KR（Kirikiri）启动入口优先级评分。
 *
 * 背景：部分中文汉化/整合版 KR 游戏会额外放一个中文命名的 xp3 作为真正入口，
 * 例如「启动游戏.xp3」「点这里启动游戏.xp3」，此时 data.xp3 往往只是资源包。
 * 因此把带「启动 / 游戏」字样的 xp3 排在 data.xp3 之前。
 *
 * 优先级（分数从高到低）：
 *   120  同时含「启动」和「游戏」  例：点这里启动游戏.xp3
 *   110  含「启动」（含繁体啟動 / 日文起動）
 *   100  含「游戏」（含繁体遊戲）
 *    90  data.xp3            ← 标准 KR 入口
 *    80  其它中文命名 xp3
 *    75  startup.tjs
 *    70  patch.xp3
 *    60  其它 xp3
 *
 * 三处调用方共用本类，保证扫描导入、编辑弹窗下拉、启动时兜底解析的口径一致：
 *   - EngineDetector：扫描识别引擎时决定写入的 launchTarget
 *   - MainActivity#buildLaunchOptions：编辑弹窗启动文件下拉的默认选中项
 *   - EmulatorLauncher：launchTarget 为 AUTO / 找不到时的兜底选择
 */
public final class KrkrEntryPriority {

    /** 不是 KR 启动候选文件时返回该值。 */
    public static final int SCORE_NONE = Integer.MIN_VALUE;

    private KrkrEntryPriority() { }

    /**
     * 给 KR 启动候选文件打分（同时支持 xp3 与 startup.tjs）。
     * 传入可以是纯文件名，也可以是含目录的相对路径（只取最后一段判定）。
     *
     * 注意：「启动 / 游戏」字样评分只在 .xp3 后缀内生效——
     * 例如「游戏启动说明.txt」不是 .xp3，会返回 SCORE_NONE，绝不会被选中。
     * 非 .xp3 文件仅有 startup.tjs 一个候选。
     */
    public static int scoreEntry(String fileName) {
        if (fileName == null) return SCORE_NONE;
        String base = baseName(fileName.trim());
        if (base.isEmpty()) return SCORE_NONE;
        String lower = base.toLowerCase(Locale.ROOT);

        if (lower.endsWith(".xp3")) {
            boolean hasLaunchWord = base.contains("启动") || base.contains("啟動") || base.contains("起動");
            boolean hasGameWord = base.contains("游戏") || base.contains("遊戲") || base.contains("遊戏") || base.contains("游戲");
            if (hasLaunchWord && hasGameWord) return 120;
            if (hasLaunchWord) return 110;
            if (hasGameWord) return 100;
            if ("data.xp3".equals(lower)) return 90;
            if (containsChinese(base)) return 80;
            if ("patch.xp3".equals(lower)) return 70;
            return 60;
        }
        if ("startup.tjs".equals(lower)) return 75;
        return SCORE_NONE;
    }

    /** 只给 xp3 打分；非 xp3（含 startup.tjs）返回 SCORE_NONE。 */
    public static int scoreXp3(String fileName) {
        if (fileName == null) return SCORE_NONE;
        String lower = baseName(fileName.trim()).toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".xp3")) return SCORE_NONE;
        return scoreEntry(fileName);
    }

    /** 是否是 KR 启动候选（xp3 或 startup.tjs）。 */
    public static boolean isEntryCandidate(String fileName) {
        return scoreEntry(fileName) != SCORE_NONE;
    }

    private static String baseName(String path) {
        if (path == null) return "";
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    private static boolean containsChinese(String text) {
        if (text == null) return false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            // CJK 统一汉字（含扩展 A）与兼容汉字
            if ((c >= 0x4E00 && c <= 0x9FFF) || (c >= 0x3400 && c <= 0x4DBF) || (c >= 0xF900 && c <= 0xFAFF)) {
                return true;
            }
        }
        return false;
    }
}