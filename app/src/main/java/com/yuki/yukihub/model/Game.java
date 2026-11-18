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

    // ==================== NextMoe 游玩记录同步（v23） ====================
    // 以下三个字段都是**本机状态**，描述「本机匹配到哪个作品、本机何时推过」，
    // 跨设备无意义，因此都不参与云同步快照（exportGamesJson 不导出）。
    /**
     * 匹配成功的 NextMoe work_id（十进制字符串）。
     * 为空表示尚未匹配。缓存下来避免每次同步都重新反查。
     * 注意：NextMoe id 是 int64 字符串，Java 侧一律用 String，不要转 long。
     */
    public String nextmoeWorkId = "";
    /** 上次成功推送到 NextMoe 的时间戳，0 表示从未成功。 */
    public long nextmoeSyncedAt = 0;
    /** 上次成功推送的内容签名（如 "doing:210"），用于跳过未变动的游戏。 */
    public String nextmoeSyncedSig = "";

    // ==================== VNDB 状态同步（v24） ====================
    // 同样是**本机状态**，不参与云同步快照。
    // 刻意不存 vndb 的 VN id：metadata_cache 里 source='vndb' 的 source_id
    // 本来就是 vNNN 格式，读取时直接取用，多存一份只会造成两份数据不一致。
    /** 上次成功推送到 VNDB 的时间戳，0 表示从未成功。 */
    public long vndbSyncedAt = 0;
    /** 上次成功推送的状态签名（VNDB label id，如 "2"），用于跳过未变动的游戏。 */
    public String vndbSyncedSig = "";

    /**
     * 匹配到的 VNDB VN id（如 "v123"），空表示尚未匹配。
     * <p>
     * 三级匹配的第 1 级直接取 metadata_cache 的 vNNN；第 2 级靠标题搜索拿到 id 后
     * 写回这里，之后每次同步就不必再搜（搜索要发请求、费配额）。
     */
    public String vndbVnId = "";

    // ==================== Bangumi 状态同步（v26） ====================
    // 同样是**本机状态**，不参与云同步快照。
    /** 上次成功推送到 Bangumi 的时间戳，0 表示从未成功。 */
    public long bangumiSyncedAt = 0;
    /** 上次成功推送的状态签名（Bangumi type，如 "3"），用于跳过未变动的游戏。 */
    public String bangumiSyncedSig = "";
    /**
     * 匹配到的 Bangumi subject_id（如 "109746"），空表示尚未匹配。
     * <p>
     * 绝大部分游戏不是用 Bangumi 源刮的，所以这一列多数情况下由「标题搜索」
     * 填上并缓存，之后每次同步就不必再搜。
     * <p>
     * 注意：存的是纯数字字符串。Bangumi 的 subject_id 就是数字，
     * 而 metadata_cache 里 source='bangumi' 的 source_id 也是纯数字，两者可直接互用。
     */
    public String bangumiSubjectId = "";

    // ==================== Hikarinagi 状态同步（v27） ====================
    // 同样是**本机状态**，不参与云同步快照，也不进 toValues()。
    /** 上次成功推送到 Hikarinagi 的时间戳，0 表示从未成功。 */
    public long hikarinagiSyncedAt = 0;
    /**
     * 上次成功推送的内容签名，形如 "GOING:126"（状态:分钟）。
     * <p>
     * 与 Bangumi / VNDB 只记状态的签名不同，这里把<b>时长</b>也纳入签名，
     * 因为 Hikarinagi 支持写 {@code time_to_finish_minutes}，
     * 时长变化同样需要推送。
     */
    public String hikarinagiSyncedSig = "";
    /**
     * 匹配到的 Hikarinagi galgame id（纯数字，如 7691），0 表示尚未匹配。
     * <p>
     * 本机数据里带 hikarinagi 锚点的游戏极少（实测 39 个里只有 2 个），
     * 所以这一列绝大多数情况由「标题搜索」填上并缓存，之后免搜索。
     */
    public long hikarinagiGalgameId = 0;

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