package com.yuki.yukihub.importer;

/**
 * 三方平台导入时的单条游戏数据（中间格式）。
 * 各 Importer 把原始格式（Playnite JSON / PotatoVN ZIP / Vnite 目录）
 * 解析成 ImportGameData，再由 ImporterService 统一写库。
 */
public class ImportGameData {
    public String name;             // 游戏标题（显示名）
    public String originalName;     // 原始标题（如有）
    public String developer;
    public String description;
public String coverUrl;     // 远程封面 URL（http/https）
    public String coverLocalPath;     // 本地封面路径（ZIP 内解压的文件）
    public String releaseDate;       // YYYY-MM-DD
    public double rating;
    public String path;             // 游戏路径（Windows 路径，Android 上可能无效）
    public String savePath;
    public String sourceType;       // vndb / bangumi / ymgal / steam / local
    public String sourceId;
    public java.util.List<String> tags;
    public long createdAt;
    public long totalPlayTime;       // 总游戏时长（秒）
    public String playStatus;         // 游玩状态：unplayed / playing / completed（来自 LunaBox status 映射）
    public boolean exists;           // 是否已有同标题游戏（预览标记）

    // ===== 以下为预览阶段使用的字段 =====
    public boolean selected;        // 用户是否勾选导入此条
    public String conflictReason;   // 如果已存在，标记原因（"已存在"等）

    // PotatoVN PlayedTime: date -> minutes
    // 由 ImporterService 转成 play_sessions
    public java.util.Map<String, Integer> playedTimeMap;

    // Vnite Timers: 每条有 start / end 时间字符串
    public java.util.List<VniteTimer> vniteTimers;

    public static class VniteTimer {
        public String start;
        public String end;
    }

    // LunaBox Sessions: 每条有 start / end 时间字符串 + duration(秒)
    // 由 ImporterService 转成 play_sessions
    public java.util.List<LunaBoxSession> lunaBoxSessions;

    public static class LunaBoxSession {
        public String start;          // PostgreSQL 风格时间戳 "2026-07-16 17:34:23.673844+08"
        public String end;            // 同上
        public int durationSeconds;   // LunaBox duration 以秒为单位
    }
}