package com.yuki.yukihub.model;

/**
 * 音乐曲目（音乐厅用）。
 *
 * <p><b>引用模式</b>：{@link #audioUri} / {@link #pvUri} 都是本机 content:// URI，
 * 且必须是**做过持久化授权**的（{@code takePersistableUriPermission}），
 * 否则重启后读不到。文件本体不进 App 存储，也不参与备份。
 *
 * <p>失效处理：不在模型里存 "missing" 状态位 —— 原文件被删/移走时，
 * 由读取方（展厅 / 管理页）在运行时 try-catch 检测并静默降级，
 * 避免出现"文件已经没了但记录还标着正常"的陈旧状态。
 */
public class MusicTrack {

    public long id;
    /** 所属专辑 id；0 = 散装单曲（见 {@link MusicAlbum#SINGLES_ID}） */
    public long albumId = MusicAlbum.SINGLES_ID;
    /** 专辑内序号（从文件名/ID3 解析，0 = 未知） */
    public int trackNo;
    public String title;
    /** 艺术家 / 社团（ID3 读取，可能为空） */
    public String artist;
    /** 音频文件 URI（必填） */
    public String audioUri;
    /** PV 视频 URI（可选） */
    public String pvUri;
    /** 单曲封面 URI（可选，展示时优先于专辑封面） */
    public String coverUri;
    /** 时长（毫秒，0 = 未探测） */
    public long durationMs;
    /** 文件大小（字节，0 = 未知） */
    public long sizeBytes;
    public long createdAt;

    public MusicTrack() {
        createdAt = System.currentTimeMillis();
    }

    public boolean hasPv() {
        return pvUri != null && !pvUri.trim().isEmpty();
    }

    /** 时长文本 "m:ss"（未知时返回 "--:--"） */
    public String durationText() {
        if (durationMs <= 0) return "--:--";
        long total = durationMs / 1000L;
        long m = total / 60L;
        long s = total % 60L;
        return m + ":" + (s < 10 ? "0" : "") + s;
    }

    @Override
    public String toString() {
        return "MusicTrack{" + id + ", album=" + albumId + ", '" + title + "', pv=" + hasPv() + "}";
    }
}
