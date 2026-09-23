package com.yuki.yukihub.model;

/**
 * 音乐专辑（音乐厅用）。
 *
 * <p><b>引用模式</b>：这里只存 URI，不存文件本体。
 * {@link #rootUri} 是用户选中的 SAF 目录树（已做持久化授权），
 * {@link #coverUri} 是封面（可以来自目录内的 cover.jpg，也可以是用户单独指定的图片）。
 *
 * <p>不参与备份与云同步（URI 授权绑定在设备上，换设备必然失效）。
 */
public class MusicAlbum {

    /** 未关联游戏（或散装单曲）的 sentinel */
    public static final long NO_GAME = 0L;
    /** 散装单曲的虚拟专辑 id */
    public static final long SINGLES_ID = 0L;

    public long id;
    public String title;
    /** SAF 目录树 URI（选文件夹添加时写入；单曲专辑为空） */
    public String rootUri;
    /** 封面 URI（content:// 或 file://） */
    public String coverUri;
    /** 关联的游戏 id（0 = 未关联） */
    public long gameId = NO_GAME;
    /** 曲目数（冗余字段，列表显示免查询） */
    public int trackCount;
    public long createdAt;
    public long updatedAt;

    public MusicAlbum() {
        createdAt = System.currentTimeMillis();
        updatedAt = createdAt;
    }

    /** 是否是"散装单曲"的虚拟专辑 */
    public boolean isSingles() {
        return id == SINGLES_ID;
    }

    @Override
    public String toString() {
        return "MusicAlbum{" + id + ", '" + title + "', tracks=" + trackCount + "}";
    }
}
