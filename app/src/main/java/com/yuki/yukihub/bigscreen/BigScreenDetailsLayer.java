package com.yuki.yukihub.bigscreen;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.LruCache;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.view.TextureView;

import androidx.core.content.ContextCompat;

import com.yuki.yukihub.R;
import com.yuki.yukihub.model.Game;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 游戏详情层（spec §S3）。
 *
 * <p>对齐实机截图：大标题 / 原文名 + 开发商行 / 分类 chips / 三栏统计 /
 * 可滚动描述 / <b>INTRODUCTION 截图画带</b> / 玻璃按钮组。
 *
 * <p>数据：{@link Game}（本地库）+ {@link BigScreenMeta}（NextMoe/VNDB/Bangumi/Ymgal 合并）。
 * 截图画带按需从网络加载，**加载失败会整块隐藏**（不显示破图）。
 */
public class BigScreenDetailsLayer {

    public interface Listener {
        /** 当前查看的游戏变了（背景层 / 底栏同步） */
        void onGameChanged(Game game);

        void onRequestLaunch(Game game);

        void onRequestFavorite(Game game);

        /** Ⓨ 操作菜单（S4） */
        void onRequestGameMenu(Game game);

        /** M15：请求全屏观看 PV（任意按键返回） */
        void onRequestWatchTrailer(Game game);
    }

    /** 截图缩略图缓存（跨打开复用） */
    private static final LruCache<String, Bitmap> SHOT_CACHE = new LruCache<>(24);

    private final Activity activity;
    private final FrameLayout root;
    private final BigScreenMeta metaLoader;
    private final Listener listener;
    private final TextView hintText;
    /** M16：当前按键风格（原来只在 setKeyStyle 里当局部变量，导致提示无法随焦点区变化重排） */
    private String keyStyle = BigScreenKeys.STYLE_XBOX;
    /** M16-1：操作按钮排的当前焦点索引（←→ 只在这里移动，详情层不再切换游戏） */
    private int actIndex = 0;
    /** 详情层背景图（PV 播放时让它让位） */
    private View bgView;
    /** 背景图的原始透明度（PV 结束后恢复用） */
    private float bgBaseAlpha = 1f;
    private final View coverFrame;

    /** NSFW 封面模糊（与主库共用 nsfw_blur_enabled） */
    private boolean nsfwBlur = true;

    /** 由 Activity 注入（设置变化时同步） */
    public void setNsfwBlurEnabled(boolean enabled) { this.nsfwBlur = enabled; }

    /** 当前详情页是否需要遮挡（NSFW 且开了模糊） */
    private boolean needBlur() {
        Game g = current();
        return g != null && g.nsfw && nsfwBlur;
    }

    private final ImageView bg;
    private final TextView titleView;
    private final TextView subView;
    private final LinearLayout chipsView;
    private final LinearLayout statsView;
    private final TextView descView;
    /** 详情层左列滚动容器（手柄 UP/DOWN 要能滚，M12） */
    private final android.widget.ScrollView leftScroll;
    /** 简介小节标题与滚动区（简介为空时整块隐藏，M10） */
    private final View introLabel;
    private final View descScroll;
    private final TextView shotsTitleView;
    private final LinearLayout shotsView;
    private final LinearLayout actsView;
    private final ImageView coverView;
    private final TextView coverPlaceholder;
    private final TextureView videoView;

    private final List<Game> list = new ArrayList<>();
    private int index = -1;

    private final ExecutorService imageExecutor = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private int shotsPending = 0;
    private int shotsLoaded = 0;

    public BigScreenDetailsLayer(Activity activity, BigScreenMeta metaLoader, Listener listener) {
        this.activity = activity;
        this.metaLoader = metaLoader;
        this.listener = listener;

        root = (FrameLayout) LayoutInflater.from(activity).inflate(R.layout.view_bs_details, null);
        bg = root.findViewById(R.id.bsDtBg);
        bgView = bg;
        bgBaseAlpha = bg != null ? bg.getAlpha() : 1f;
        titleView = root.findViewById(R.id.bsDtTitle);
        subView = root.findViewById(R.id.bsDtSub);
        chipsView = root.findViewById(R.id.bsDtChips);
        statsView = root.findViewById(R.id.bsDtStats);
        descView = root.findViewById(R.id.bsDtDesc);
        introLabel = root.findViewById(R.id.bsDtIntroLabel);
        leftScroll = root.findViewById(R.id.bsDtLeftScroll);
        descScroll = root.findViewById(R.id.bsDtDescScroll);
        shotsTitleView = root.findViewById(R.id.bsDtShotsTitle);
        shotsView = root.findViewById(R.id.bsDtShots);
        actsView = root.findViewById(R.id.bsDtActs);
        coverView = root.findViewById(R.id.bsDtCover);
        coverPlaceholder = root.findViewById(R.id.bsDtCoverPlaceholder);
        videoView = root.findViewById(R.id.bsDtVideo);
        coverFrame = root.findViewById(R.id.bsDtCoverFrame);
        hintText = root.findViewById(R.id.bsDtHint);
        // 触摸用户的关闭按钮（手柄按 Ⓑ）
        closeBtn = root.findViewById(R.id.bsDtClose);
        if (closeBtn != null) {
            closeBtn.setOnClickListener(v -> { BigScreenSound.open(); hide(); });
            closeBtn.setVisibility(View.GONE);   // M18-9：先藏起来，由 setTouchUi 决定显不显示
        }
        // M18-9：**详情层必须吃掉落在空白处的触摸**。
        // 之前 root 只是"有背景色"，ViewGroup 不消费 → 触摸穿透到底下的卡片/侧栏/顶栏，
        // 所以触屏能点到外面的按钮、能打开图片、甚至把右上角的设置点出来。
        root.setClickable(true);
        root.setOnTouchListener((v, e) -> true);
        setKeyStyle(BigScreenKeys.STYLE_XBOX);
    }

    /** 按键风格变化时同步底部提示（M4-2） */
    public void setKeyStyle(String style) {
        this.keyStyle = style == null ? BigScreenKeys.STYLE_XBOX : style;
        updateHintText();
    }

    /** 详情层的PV视频输出目标（由 TrailerPlayer.attach 切换过来） */
    public TextureView videoTarget() { return videoView; }

    public void attachTo(ViewGroup parent) { parent.addView(root); }

    public boolean isVisible() { return root.getVisibility() == View.VISIBLE; }

    public Game current() {
        if (index < 0 || index >= list.size()) { return null; }
        return list.get(index);
    }

    // ================= 显示 / 隐藏 =================

    public void show(List<Game> games, int startIndex) {
        list.clear();
        if (games != null) { list.addAll(games); }
        if (list.isEmpty()) { return; }
        index = Math.max(0, Math.min(startIndex, list.size() - 1));

        // M18-10：每次打开都先复位"内容层/背景图"的可见性 ——
        // 上次是"PV 播放中"关掉的，内容层还是透明的，重开就只剩背景了
        // M18-11：内容层的 alpha 动画已撤回（保留复位，防止历史残留）
        if (contentView == null) { contentView = root.findViewById(R.id.bsDtContent); }
        if (contentView != null) { contentView.animate().cancel(); contentView.setAlpha(1f); }
        if (bgView != null) { bgView.animate().cancel(); bgView.setAlpha(bgBaseAlpha); }

        // M18-11：入场淡入只作用于内容层（**视频层不参与淡入**，避免视频跟着"闪一下"）；
        // 内容层不再被 PV 隐藏，只是每次打开时先透明再淡入。
        root.setVisibility(View.VISIBLE);
        root.setAlpha(1f);
        if (contentView != null) {
            contentView.setAlpha(0f);
            contentView.animate().alpha(1f).setDuration(200L)
                    .setInterpolator(new DecelerateInterpolator()).start();
        }
        // M16-1：每次打开都复位按钮排焦点（否则上次的描边会残留）
        actIndex = 0;
        // M18-5：触摸模式下**不预选**任何按钮（手柄模式保持"游玩"选中）
        actsFocusVisible = !touchUi;
        applyAdaptiveCoverSize();
        render();
        updateHintText();
        applyActsFocus();
        if (listener != null) { listener.onGameChanged(current()); }
    }

    /**
     * PV 播放中 → 详情层背景图让位（M8：否则视频层下面还压着一张封面，看着像视频半透明）。
     */
    public void setVideoPlaying(boolean playing) {
        // M18-11：**撤回 M18-10 的"内容层整块让位"** —— 用户实测：播放 PV 时进详情页
        // "就只剩个按钮了"（正文/封面/统计都被淡掉了，看着像坏了）。
        // 回到原行为：只让背景图让位，内容照常显示。
        if (bgView == null) { return; }
        bgView.animate().alpha(playing ? 0f : bgBaseAlpha).setDuration(260L).start();
    }

    /** 封面按可用高度缩放（原先是写死 286×382dp，横屏矮屏上会被裁掉） */
    private void applyAdaptiveCoverSize() {
        if (coverFrame == null) { return; }
        root.post(() -> {
            int h = root.getHeight();
            if (h <= 0) { return; }
            int availH = h - dp(96);
            int coverH = Math.max(dp(120), Math.min(dp(382), Math.round(availH * 0.86f)));
            int coverW = Math.round(coverH * 0.75f);
            ViewGroup.LayoutParams lp = coverFrame.getLayoutParams();
            if (lp == null || (lp.width == coverW && lp.height == coverH)) { return; }
            lp.width = coverW;
            lp.height = coverH;
            coverFrame.setLayoutParams(lp);
        });
    }
    private int dp(float v) {
        return Math.round(v * root.getResources().getDisplayMetrics().density);
    }

    public void hide() {
        // M18-9：**通知放到动画结束时**（root 已经 GONE）。
        // 之前是立刻通知，那一刻 isVisible() 还是 true → 活动层会把 PV 又 attach 到详情层的
        // 视频目标上；160ms 后这个 view 变 GONE、surface 被销毁 → PV 断在没人看的地方。
        // 现在结束回调里再通知，活动层就会把 PV 正确地接回主界面背景层。
        root.animate().alpha(0f).setDuration(160L)
                .withEndAction(() -> {
                    root.setVisibility(View.GONE);
                    Game g = current();
                    if (listener != null && g != null) { listener.onGameChanged(g); }
                }).start();
    }

    /** 设置变化（如开关 NSFW 模糊）后按当前游戏重画一次 */
    public void refreshCurrent() {
        if (root != null && root.getVisibility() == View.VISIBLE) { render(); }
    }

    private void move(int delta) {
        if (list.size() <= 1) { return; }
        index = (index + delta + list.size()) % list.size();
        render();
        if (listener != null) { listener.onGameChanged(current()); }
    }

    // ================= 输入 =================

    /** @return true 表示已消费 */
    public boolean handleIntent(InputRouter.Intent intent) {
        if (!isVisible() || intent == null) { return false; }
        switch (intent) {
            // M16：←→ 在「切换游戏」和「操作按钮排」两个区之间分工 ——
            //   默认 ←→ 切游戏；按 ↑ 进入按钮排后 ←→ 在按钮间移动（否则按钮永远选不到，用户反馈过）。
            // M16-1：详情层里**不再允许切换游戏**（用户反馈"很难搞、还只能影响到按钮"）。
        //   ←→ 永远作用于操作按钮排；↑↓ 用来滚动阅读左列内容；LB/RB 在详情层不做事。
        case LEFT:  moveAct(-1); return true;
            case RIGHT: moveAct(1);  return true;
            case PAGE_L:
            case PAGE_R:
                return true;                       // M16-1：详情层不切游戏（吞掉，避免误操作）
            case UP:    scrollLeftColumn(-1); return true;
            case DOWN:  scrollLeftColumn(1);  return true;
            case CONFIRM:
                runAct();                          // M16-1：Ⓐ = 执行当前聚焦的按钮
                return true;
            case FAVORITE:
                if (listener != null) { listener.onRequestFavorite(current()); }
                return true;
            case DETAILS:
                if (listener != null) { listener.onRequestGameMenu(current()); }
                return true;
            case BACK:
                hide();
                return true;
            default:
                return true;
        }
    }

    // ===== M16-1：详情层"操作按钮排"焦点（‹› 只作用于按钮，不切游戏）=====

    /** M18-5：焦点缩放幅度（0=不缩放，1=默认），由设置下发 */
    private float focusScaleFactor = 1f;
    /**
     * M18-5：触摸模式（由 Activity 下发）+ 按钮焦点是否可见。
     *
     * <p>用户反馈：**手机触屏进详情页时"游玩"是默认选中状态，不太好**。
     * 所以触摸模式下进入详情页**不预选任何按钮**；等玩家真的按了 ←/→ 或 Ⓐ 才出现焦点。
     * 手柄玩家不受影响（进详情页仍然是"游玩"选中）。
     */
    private boolean touchUi = false;
    private boolean actsFocusVisible = true;

    /** M18-2：设置下发焦点缩放幅度（百分比）—— 详情页操作按钮也跟着变 */
    public void setFocusScalePercent(int percent) {
        focusScaleFactor = Math.max(0f, Math.min(1.5f, percent / 100f));
    }

    /**
     * M18-5：Activity 下发"当前是触摸模式"。
     *
     * <p>触摸模式下不预选按钮（进详情页时"游玩"不该是选中态）；
     * 一旦切回手柄/键盘，就恢复默认选中，手柄玩家体验不变。
     */
    public void setTouchUi(boolean touch) {
        this.touchUi = touch;
        this.actsFocusVisible = !touch;
        // M18-9：触摸模式显示"✕ 关闭"（触屏没有 Ⓑ 键），手柄模式隐藏
        if (closeBtn != null) {
            closeBtn.setVisibility(touch ? View.VISIBLE : View.GONE);
        }
        updateHintText();
        applyActsFocus();
    }

    /** 在按钮排里左右移动 */
    private void moveAct(int delta) {
        if (actsView == null) { return; }
        final int n = actsView.getChildCount();
        if (n <= 0) { return; }
        // M18-5：触摸模式下一按方向键，焦点才"亮起来"（之前是不预选的）
        actsFocusVisible = true;
        actIndex = (actIndex + delta + n) % n;
        BigScreenSound.tick();
        applyActsFocus();
    }

    /** 执行当前聚焦的按钮 */
    private void runAct() {
        if (actsView == null) { return; }
        // M18-5：焦点还没亮（触摸模式下刚进详情页）→ 第一次 Ⓐ 只把焦点叫出来，不误触发按钮
        if (!actsFocusVisible) {
            actsFocusVisible = true;
            applyActsFocus();
            return;
        }
        if (actIndex < 0 || actIndex >= actsView.getChildCount()) {
            if (listener != null) { listener.onRequestLaunch(current()); }   // 兜底：至少能启动
            return;
        }
        actsView.getChildAt(actIndex).performClick();
    }

    /** 按钮排焦点视觉：聚焦项加描边 + 放大，其它恢复 */
    private void applyActsFocus() {
        if (actsView == null) { return; }
        // M18-5：触摸模式下（还没按过方向键/Ⓐ）**一个按钮都不高亮** ——
        // 用户反馈"触屏进详情页，游玩默认是选中状态，不太好"。
        if (!actsFocusVisible) {
            for (int i = 0; i < actsView.getChildCount(); i++) {
                View v = actsView.getChildAt(i);
                v.animate().cancel();
                v.setScaleX(1f);
                v.setScaleY(1f);
                v.setForeground(null);
                v.setZ(0f);
            }
            return;
        }
        for (int i = 0; i < actsView.getChildCount(); i++) {
            View v = actsView.getChildAt(i);
            final boolean focused = i == actIndex;
            v.animate().cancel();
            // M18：焦点缩放幅度作用到详情层按钮（与全局 GamepadFocus 同一设置）
            final float actsScale = 1f + (1.07f - 1f) * focusScaleFactor;
            v.animate().scaleX(focused ? actsScale : 1f).scaleY(focused ? actsScale : 1f)
                    .setDuration(140L).setInterpolator(new DecelerateInterpolator()).start();
            v.setForeground(focused ? androidx.core.content.ContextCompat.getDrawable(
                    activity, R.drawable.fg_focus_highlight) : null);
            v.setZ(focused ? 8f : 0f);
        }
    }

    private View closeBtn;   // M18-9：触摸模式的"✕ 关闭"（手柄模式隐藏）
    private View contentView;   // M18-10：正文 + 封面容器，PV 播放时整块让位

    /** 详情层底部提示（复用类里已有的 BigScreenKeys 风格） */
    private void updateHintText() {
        if (hintText == null) { return; }
        // M18-9：触摸模式别显示按键提示（没有手柄），改成点按说明
        if (touchUi) {
            hintText.setText("点按按钮执行　↕ 滑动查看简介/截图　✕ 关闭（右上角）");
            return;
        }
        BigScreenKeys k = new BigScreenKeys(keyStyle);
        hintText.setText("← → 选择操作　" + k.confirm() + " 执行　↑↓ 滚动内容　"
                + k.third() + " 收藏　" + k.back() + " 关闭");
    }

    /** 详情层左列滚动（手柄方向键）：一次滚半屏，到底就停住 */
    private void scrollLeftColumn(int direction) {
        if (leftScroll == null) { return; }
        int step = Math.max(dp(60), leftScroll.getHeight() / 2);
        // 注意 getChildAt 必须带索引（写 getChildAt() 会直接编译不过）
        int max = 0;
        if (leftScroll.getChildCount() > 0) {
            View child = leftScroll.getChildAt(0);
            if (child != null) { max = Math.max(0, child.getHeight() - leftScroll.getHeight()); }
        }
        int target = Math.max(0, Math.min(max, leftScroll.getScrollY() + direction * step));
        leftScroll.smoothScrollTo(0, target);
    }

    // ================= 渲染 =================

    private void render() {
        final Game game = current();
        if (game == null) { return; }

        // 背景 + 封面（用本地封面，避免依赖网络）
        String coverUri = !TextUtils.isEmpty(game.coverPersistUri)
                ? game.coverPersistUri : game.coverUri;
        boolean hasCover = false;
        final boolean blur = game.nsfw && nsfwBlur;
        if (!TextUtils.isEmpty(coverUri) && blur) {
            // NSFW：背景与封面一律用模糊图，算不出来就保持 🔞（绝不露原图）
            final String key = NsfwBlur.cacheKey("bs_dt_nsfw", game.id, coverUri);
            coverView.setImageDrawable(null);
            coverView.setVisibility(View.GONE);
            coverPlaceholder.setVisibility(View.VISIBLE);
            coverPlaceholder.setText("🔞");
            bg.setImageDrawable(null);
            NsfwBlur.load(root, coverUri, key, 2, 24f, bmp -> {
                if (bmp == null) { return; }
                if (current() == null || current().id != game.id) { return; }
                coverView.setImageBitmap(bmp);
                coverView.setVisibility(View.VISIBLE);
                coverPlaceholder.setVisibility(View.GONE);
                bg.setImageBitmap(bmp);
            });
        } else if (!TextUtils.isEmpty(coverUri)) {
            try {
                Uri uri = Uri.parse(coverUri);
                bg.setImageURI(uri);
                coverView.setImageURI(uri);
                hasCover = coverView.getDrawable() != null;
            } catch (Throwable ignored) { }
        }
        if (!blur) {
            coverView.setVisibility(hasCover ? View.VISIBLE : View.GONE);
            coverPlaceholder.setVisibility(hasCover ? View.GONE : View.VISIBLE);
            if (!hasCover) {
                coverPlaceholder.setText(TextUtils.isEmpty(game.title) ? "?"
                        : game.title.substring(0, 1));
            }
        }

        titleView.setText(TextUtils.isEmpty(game.title) ? "未命名" : game.title);

        // 副标题 / 开发商行：先用本地信息兜底，元数据到了再补全
        subView.setText(buildSubLine(game, null));

        // 统计三栏
        buildStats(game);

        // 描述（M10：没有简介就整块收起，不再留一块"（暂无简介）"的空框）
        descView.setText(TextUtils.isEmpty(game.description) ? "" : game.description);
        setDescVisible(!TextUtils.isEmpty(descView.getText()));

        // 动作按钮组
        buildActions(game);

        // 元数据（异步；开发商 / 发行日期 / chips / 截图）
        final long gameId = game.id;
        chipsView.removeAllViews();
        if (game.nsfw) { addChip("R18"); }
        metaLoader.load(gameId, (id, data) -> {
            if (!isVisible() || current() == null || current().id != id) { return; }
            subView.setText(buildSubLine(current(), data));
            // 统计块重排：补上评分（引擎已在基础块里）
            buildStats(current(), data);
            // chips：只放标签（M9：引擎/评分不再混进来），最多 3 个
            chipsView.removeAllViews();
            for (String tag : BigScreenMeta.normalizeTags(
                    data.tags, current().tags, 3)) {
                addChip(tag);
            }
            if (current().nsfw) { addChip("R18"); }
            // 描述优先用元数据
            if (!data.description.isEmpty()) { descView.setText(data.description); }
            setDescVisible(!TextUtils.isEmpty(descView.getText()));
            // 截图带
            renderScreenshots(data.screenshots);
        });

        shotsTitleView.setVisibility(View.GONE);
        shotsView.setVisibility(View.GONE);
        shotsView.removeAllViews();
    }

    private String buildSubLine(Game game, BigScreenMeta.Data meta) {
        List<String> parts = new ArrayList<>();
        if (!TextUtils.isEmpty(game.originalTitle)) { parts.add(game.originalTitle); }
        if (meta != null && !meta.developer.isEmpty()) { parts.add(meta.developer); }
        if (meta != null && !meta.released.isEmpty()) { parts.add(meta.released); }
        // 引擎不放在这一行（M9：它现在是"引擎"统计块）
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) { sb.append("　•　"); }
            sb.append(parts.get(i));
        }
        return sb.toString();
    }

    private void buildStats(Game game) {
        buildStats(game, null);
    }

    /**
     * 统计块（M9 重排）：游玩时长 / 上次游玩 / 状态 / 引擎，评分有数据时再补一格。
     * 之前引擎和评分被塞在标签那一排里，用户反馈"乱放、没逻辑"。
     */
    private void buildStats(Game game, BigScreenMeta.Data meta) {
        statsView.removeAllViews();
        addStat("游玩时长", formatHours(game.totalPlayTime));
        addStat("上次游玩", formatLastPlayed(game.lastPlayedAt));
        addStat("状态", statusLabel(game.playStatus));
        // M15：**删掉「引擎」**（用户反馈"不太需要"）
        if (meta != null && !meta.rating.isEmpty()) { addStat("评分", shortRating(meta.rating)); }
    }

    /** 简介块显隐（M10）：没有简介就把小节标题和滚动区一起收掉，不留空框 */
    private void setDescVisible(boolean visible) {
        if (introLabel != null) { introLabel.setVisibility(visible ? View.VISIBLE : View.GONE); }
        if (descScroll != null) { descScroll.setVisibility(visible ? View.VISIBLE : View.GONE); }
    }

    /** 评分文本瘦身（M10）："6.9/10 (37 票)" → "6.9/10"，避免统计块里换行 */
    private static String shortRating(String rating) {
        if (rating == null) { return ""; }
        String s = rating.trim();
        // 去掉"评分："/"★"/"Score:" 这类前缀（截图里出现过"评分：6.9…"被截断）
        int colon = s.indexOf('：');
        if (colon < 0) { colon = s.indexOf(':'); }
        if (colon >= 0 && colon < s.length() - 1) { s = s.substring(colon + 1).trim(); }
        s = s.replace("★", "").trim();
        int cut = s.indexOf('(');
        if (cut > 0) { s = s.substring(0, cut).trim(); }
        int sp = s.indexOf(' ');
        if (sp > 0) { s = s.substring(0, sp).trim(); }
        return s;
    }

    private void addStat(String key, String value) {
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(46), 1f);
        lp.rightMargin = dp(8);
        box.setLayoutParams(lp);
        box.setBackgroundResource(R.drawable.bg_bs_glass);
        box.setPadding(dp(11), 0, dp(11), 0);

        TextView k = new TextView(activity);
        k.setText(key);
        k.setTextSize(10f);
        k.setTextColor(Color.parseColor("#7E8AB0"));
        k.setSingleLine(true);
        box.addView(k);

        // 值必须单一、单行、超长省略 —— 上一版让 "GameHub" / "评分6.9/10(37票)" 换行，
        // 把统计块撑成高矮不一的一堆方块（用户："这个样子能看吗"）。
        TextView v = new TextView(activity);
        v.setText(value);
        v.setTextSize(13.5f);
        v.setSingleLine(true);
        v.setEllipsize(TextUtils.TruncateAt.END);
        v.setTextColor(ContextCompat.getColor(activity, R.color.bs_text));
        v.getPaint().setFakeBoldText(true);
        v.setPadding(0, dp(3), 0, 0);
        box.addView(v);

        statsView.addView(box);
    }

    private void addChip(String text) {
        if (TextUtils.isEmpty(text)) { return; }
        TextView chip = new TextView(activity);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(7);
        chip.setLayoutParams(lp);
        chip.setText(text);
        chip.setTextSize(12f);
        chip.setTextColor(Color.parseColor("#C7D3F0"));
        chip.setBackgroundResource(R.drawable.bg_bs_chip);
        chip.setPadding(dp(10), dp(4), dp(10), dp(4));
        chipsView.addView(chip);
    }

    private void buildActions(Game game) {
        actsView.removeAllViews();
        addAction("游玩", true, () -> {
            if (listener != null) { listener.onRequestLaunch(current()); }
        });
        // M15：有 PV 才显示「观看 PV」—— 全屏预览，任意键返回
        final Game cur = current();
        if (cur != null && !android.text.TextUtils.isEmpty(cur.trailerPath)) {
            addAction("观看 PV", false, () -> {
                if (listener != null) { listener.onRequestWatchTrailer(cur); }
            });
        }
        addAction("详细", false, () -> {
            if (listener != null) { listener.onRequestGameMenu(current()); }
        });
    }

    private void addAction(String label, boolean primary, Runnable action) {
        TextView btn = new TextView(activity);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(44));
        lp.rightMargin = dp(10);
        btn.setLayoutParams(lp);
        btn.setText(label);
        btn.setTextSize(15f);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(dp(28), 0, dp(28), 0);
        // 详情层的操作按钮同样统一风格（M14-1：不再给"启动"单独配色）
        btn.setBackgroundResource(R.drawable.bg_bs_glass);
        btn.setTextColor(ContextCompat.getColor(activity,
                primary ? R.color.bs_text : R.color.bs_text_muted));
        btn.setClickable(true);
        btn.setOnClickListener(v -> { BigScreenSound.confirm(); action.run(); });
        actsView.addView(btn);
    }

    // ================= 截图画带 =================

    private void renderScreenshots(List<String> urls) {
        shotsView.removeAllViews();
        // NSFW 且开启模糊：预览图整块不渲染（截图内容无从模糊，直接不给看）
        if (needBlur()) {
            shotsTitleView.setVisibility(View.GONE);
            shotsView.setVisibility(View.GONE);
            return;
        }
        if (urls == null || urls.isEmpty()) {
            shotsTitleView.setVisibility(View.GONE);
            shotsView.setVisibility(View.GONE);
            return;
        }
        shotsPending = 0;
        shotsLoaded = 0;
        shotsTitleView.setVisibility(View.VISIBLE);
        shotsView.setVisibility(View.VISIBLE);

        final int limit = Math.min(urls.size(), 4);
        for (int i = 0; i < limit; i++) {
            final String url = urls.get(i);
            ImageView iv = new ImageView(activity);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(196), dp(110));
            lp.rightMargin = dp(8);
            iv.setLayoutParams(lp);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setBackgroundResource(R.drawable.bg_bs_cell);
            iv.setClipToOutline(true);
            iv.setVisibility(View.INVISIBLE);
            shotsView.addView(iv);
            shotsPending++;
            loadShot(url, iv);
        }
    }

    private void loadShot(final String url, final ImageView target) {
        Bitmap cached = SHOT_CACHE.get(url);
        if (cached != null) {
            target.setImageBitmap(cached);
            target.setVisibility(View.VISIBLE);
            onShotDone(true);
            return;
        }
        imageExecutor.execute(() -> {
            Bitmap bitmap = null;
            try {
                bitmap = download(url);
            } catch (Throwable ignored) { }
            if (bitmap != null) { SHOT_CACHE.put(url, bitmap); }
            final Bitmap result = bitmap;
            ui.post(() -> {
                if (result != null) {
                    target.setImageBitmap(result);
                    target.setVisibility(View.VISIBLE);
                    onShotDone(true);
                } else {
                    target.setVisibility(View.GONE);
                    onShotDone(false);
                }
            });
        });
    }

    /** 一张都没加载出来时，把整块收起来（不留空标题） */
    private void onShotDone(boolean success) {
        shotsPending--;
        if (success) { shotsLoaded++; }
        if (shotsPending <= 0 && shotsLoaded == 0) {
            shotsTitleView.setVisibility(View.GONE);
            shotsView.setVisibility(View.GONE);
        }
    }

    private static Bitmap download(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(12000);
        conn.setRequestProperty("User-Agent", "YukiHub/1.0 (Android; big screen)");
        try (InputStream in = conn.getInputStream()) {
            return BitmapFactory.decodeStream(in);
        } finally {
            try { conn.disconnect(); } catch (Throwable ignored) { }
        }
    }

    // ================= 其它 =================

    public void shutdown() { imageExecutor.shutdownNow(); }

    private String engineLabel(Game game) {
        if (game == null || game.engine == null) { return "未知引擎"; }
        return game.engine.getDisplayName();
    }

    private static String statusLabel(String status) {
        if (status == null) { return "未游玩"; }
        switch (status) {
            case "playing":   return "游玩中";
            case "completed": return "已完成";
            default:          return "未游玩";
        }
    }

    private static String formatHours(long ms) {
        if (ms <= 0) { return "未游玩"; }
        double hours = ms / 3600000.0;
        return hours >= 10 ? String.format(Locale.getDefault(), "%.0f 小时", hours)
                : String.format(Locale.getDefault(), "%.1f 小时", hours);
    }

    private static String formatLastPlayed(long timestamp) {
        if (timestamp <= 0) { return "从未"; }
        long diff = System.currentTimeMillis() - timestamp;
        long days = diff / 86400000L;
        if (days <= 0) { return "今天"; }
        if (days == 1) { return "昨天"; }
        if (days < 30) { return days + " 天前"; }
        return new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date(timestamp));
    }
}