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