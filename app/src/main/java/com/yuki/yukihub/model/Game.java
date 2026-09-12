package com.yuki.yukihub.model;

public class Game {
    public long id;
    public String title;
    public String originalTitle;
    public EngineType engine;
    public String rootUri;
    public String coverUri;
    public String coverPersistUri;
    public int coverSourceType; // 0=none 1=uri 2=embedded/base64
    public String emulatorPackage;
    public String launchTarget; // AUTO, DIR, startup.tjs, data.xp3, patch.xp3, XP3_FIRST
    public String winlatorLaunchMode = "game"; // game / program
    public String description;
    public String tags;
    public String gamehubLocalGameId;
    public String gamehubLaunchMode = "game"; // game / program
    public String playStatus = "unplayed"; // unplayed / playing / completed
    public long totalPlayTime;
    public long lastPlayedAt;
    public long playtimeResetAt;
    public long createdAt;
    public long updatedAt;
    public boolean hidden;
    public boolean favorite;
    public boolean nsfw;
    /**
     * 本地预告视频路径（大屏模式用；spec §S10）
     * 指向 getFilesDir()/trailers/ 下的内部文件，为空表示未绑定。
     * 注意：这是**本机路径**，不参与跨设备同步（导出白名单不含它）。
     */
    public String trailerPath;
    /** 自定义标题图（Steam 式 logo，M10）：设置后大屏用图片替代文字标题 */
    public String logoPath;
    /** 自定义背景图（M10）：设置后大屏背景优先用它，而不是封面 */
    public String bgPath;

    public Game() {
        engine = EngineType.UNKNOWN;
        createdAt = System.currentTimeMillis();
        updatedAt = createdAt;
    }

    public static Game sample(String title, EngineType engine) {
        Game game = new Game();
        game.title = title;
        game.engine = engine;
        game.rootUri = "sample://" + title;
        game.description = "示例条目：可删除或编辑。";
        return game;
    }
}