package com.yuki.yukihub.bigscreen;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.hardware.input.InputManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import android.app.AlertDialog;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.yuki.yukihub.R;
import com.yuki.yukihub.ui.GamepadFocus;
import com.yuki.yukihub.data.GameRepository;
import com.yuki.yukihub.model.Game;
import com.yuki.yukihub.ui.BlurUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 大屏模式宿主 Activity —— M1 + M1b + M2。
 *
 * <p>已实现（详见 bigscreen_spec.md §8）：
 * <ul>
 *   <li><b>M1</b>：72dp 图标侧栏（焦点进入展开 250dp、离开收回）· shelf 行 · 二维焦点 · 顶栏 · 空态</li>
 *   <li><b>M1b</b>：选中信息层（标题 + 操作按钮排）· Ⓧ 收藏写库并即时刷新 ·
 *       背景 Ken Burns + 交叉淡入 · 卡片按键徽章 · 按键提示自动淡出 · 加载态</li>
 *   <li><b>M2</b>：游戏详情层（真实元数据 + 截图画带 + 玻璃按钮）· 游戏操作菜单（S4）·
 *       主菜单（S5）· <b>启动游戏接入既有 Intent 通路</b></li>
 * </ul>
 *
 * <p>启动通路：<b>M13 起改为原地启动</b>（{@link BigScreenLauncher}）。早先的实现复用
 * {@code home_target=launch_game} 交给 MainActivity，并且带了 CLEAR_TOP —— 结果大屏实例会被
 * 销毁、游戏库会闪一下，退出游戏后落在游戏库里，"割裂感"明显。现在直接调用项目公共的
 * {@code EmulatorLauncher} 原地启动，返回时仍停在大屏原来的位置。
 */
public class BigScreenActivity extends AppCompatActivity
        implements InputRouter.Listener, FocusEngine.Listener,
        BigScreenRailView.Listener, BigScreenShelfAdapter.Listener,
        BigScreenPanel.Listener, BigScreenDetailsLayer.Listener,
        InputManager.InputDeviceListener {

    // ===== 筛选 id =====
    private static final String F_ALL = "ALL";
    private static final String F_FAV = "FAV";
    private static final String F_RECENT = "RECENT";
    private static final String F_PLAYING = "PLAYING";
    private static final String F_DONE = "DONE";
    private static final String F_TODO = "TODO";
    private static final String ENGINE_PREFIX = "ENGINE:";

    /** 单排最多放多少张卡（RecyclerView 复用，几千张也不卡） */
    private static final int SHELF_MAX = 500;
    /**
     * M18-16：信息层底边与卡片行（含行标题）顶部之间的间隙（dp）。
     * 用户要求：**别贴死（0dp），留 2~4dp 就差不多** → 取 4dp（偏安全的一档）。
     * 之前是 8dp，配合 M18-15 的"真实底栏高度"会看着略空。
     */
    private static final int INFO_GAP_DP = 4;
    private static final long HINT_FADE_DELAY_MS = 4000L;

    // ===== 核心 =====
    private BigScreenPrefs prefs;
    private InputRouter inputRouter;
    private FocusEngine focusEngine;
    private InputManager inputManager;
    private GameRepository repository;
    private BigScreenMeta metaLoader;

    // ===== 视图 =====
    private BigScreenRailView rail;
    private LinearLayout shelfContainer;
    private ScrollView shelfScroll;
    private FrameLayout overlayLayer;
    private ImageView bgA;
    private ImageView bgB;
    private TextView clockView;
    private TextView netView;
    private TextView batteryView;
    private TextView gamepadView;
    private TextView hintView;
    private TextView infoTitleView;
    private TextView infoMetaView;
    private LinearLayout infoActions;
    private LinearLayout infoChips;
    private View infoBar;
    private ImageView infoLogo;
    /** PV 遮罩相关（M11） */
    private View scrimLeft;
    private View scrimBottom;
    private View videoScrim;
    /** 信息浮层当前已请求元数据的游戏（避免每次移动焦点都查库） */
    private long lastInfoMetaGameId = -1L;
    /** 信息浮层上次显示的游戏（用于切换时的入场动效） */
    private long lastInfoGameId = -1L;
    private View emptyView;
    private View loadingView;
    private com.yuki.yukihub.ui.DynamicSnowBackgroundView snowView;

    // ===== 横屏铺满与触摸（M4-6）=====
    /** 按屏幕高度算出的全套尺寸（横屏手机上高度只有 ~350dp，尺寸必须算不能写死） */
    private BigScreenSizes sizes;
    private View topBar;
    private View bottomBar;
    private View menuBtn;
    /** 最近一次输入来自触摸 —— 提示文案随之切换（手柄/键盘一动就切回来） */
    private boolean touchMode = false;
    /** M13：原地启动后待收尾的游玩会话（0 = 无） */
    private long pendingLaunchSessionId = 0L;
    private long pendingLaunchAt = 0L;
    /** M14：焦点在信息层按钮排（启动/收藏/详情/更多）里 */
    private boolean infoZone = false;
    private int infoFocusIndex = 0;
    /**
     * M14-1：侧栏是否处于展开状态（信息浮层让位的**唯一真源**）。
     *
     * <p>之前只在展开回调里直接把 infoBar 淡出，但 {@code updateInfoBar()} 里
     * 那句"换游戏淡入"会把 alpha 重新设成 1 —— 于是"往下按一个分类，标题又露出来"。
     * 现在所有设置 infoBar 透明度的地方都必须走 {@link #applyInfoBarAlpha()}。
     */
    private boolean railExpandedUi = false;
    /** M15：全屏 PV 预览中（任意键/触摸退出） */
    private boolean trailerFullscreen = false;
    private boolean trailerFullscreenWasDetails = false;
    /** M17：入场动画期间攒下来的提示与 PV 计时（动画播完才生效） */
    private String pendingIntroNotice;
    private boolean trailerWaitIntro = false;

    /**
     * M17：入场动画结束 —— 这时才允许显示提示条，并开始计 PV 的停顿时间。
     *
     * <p>用户反馈："手柄已连接不应该在启动动画的时候显示；PV 停顿时间也得从启动动画播完才开始计。"
     */
    private void onIntroEnded() {
        if (banner != null) { banner.setSuppressed(false); }
        if (pendingIntroNotice != null) {
            showGamepadNotice(pendingIntroNotice);
            pendingIntroNotice = null;
        }
        // M18-2：**每次入场动画结束后都提示一次手柄状态**。
        // 之前只有"插拔事件"才会提示，而手柄多半是启动前就插着的 —— 于是入场完什么都看不到。
        if (gamepadConnected()) { showGamepadNotice("手柄已连接"); }
        if (trailerWaitIntro) {
            trailerWaitIntro = false;
            syncTrailer(null);   // PV 的延迟计时从这一刻开始
        }
    }

    /** M18-2：带手柄图标的提示（用户要的是**提示消息里**的图标，不是顶栏常驻图标） */
    private void showGamepadNotice(String msg) {
        if (banner != null) {
            banner.show(msg, R.drawable.bs_ic_gamepad);
        } else {
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
        }
    }

    /** 手柄是否处于已连接状态（与顶栏那块文案同一判断：`inputRouter.isGamepadConnected()`） */
    private boolean gamepadConnected() {
        try {
            return inputRouter != null && inputRouter.isGamepadConnected();
        } catch (Throwable t) {
            return false;
        }
    }

    // ===== 浮层 =====
    private BigScreenDetailsLayer detailsLayer;
    private BigScreenPanel panel;

    // ===== PV视频（M2.5 / spec §S10）=====
    private TrailerManager trailerManager;
    private TrailerPlayer trailerPlayer;
    private TextureView bgVideo;
    private ActivityResultLauncher<String> trailerPickerLauncher;
    /** 自定义标题图 / 背景图（M10） */
    private BigScreenArt artManager;
    private ActivityResultLauncher<String> artPickerLauncher;
    private Game pendingArtGame;
    private String pendingArtKind;
    /** 正在等待选视频的游戏 */
    private Game pendingTrailerGame;

    // ===== 入场动画与音效（M3）=====
    private BigScreenIntro intro;
    private BigScreenSound sound;

    // ===== 设置 / 提示条 / 按键风格（M4）=====
    private BigScreenBanner banner;
    private BigScreenSettings settings;
    private BigScreenKeys keys = new BigScreenKeys(BigScreenKeys.STYLE_XBOX);

    // ===== 数据 =====
    private final List<Game> allGames = new ArrayList<>();
    private final List<Shelf> shelves = new ArrayList<>();
    private String filter = F_ALL;
    private String sortMode = "recent";   // recent | newest | name
    private boolean railZone = false;

    // ===== 背景 =====
    private String bgUri = null;
    private String bgFront = "A";
    private ValueAnimator kenBurns;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService bgExecutor = Executors.newSingleThreadExecutor();
    private final Random random = new Random();

    /** 一行 shelf */
    private static class Shelf {
        String title;
        int accentColor;
        List<Game> games = new ArrayList<>();
        BigScreenShelfAdapter adapter;
        RecyclerView rv;
    }

    // ================= 生命周期 =================

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_bigscreen);

        prefs = new BigScreenPrefs(this);
        repository = new GameRepository(this);
        metaLoader = new BigScreenMeta(this);
        inputRouter = new InputRouter(this);
        inputManager = (InputManager) getSystemService(Context.INPUT_SERVICE);
        focusEngine = new FocusEngine();
        focusEngine.setListener(this);

        bindViews();
        applyImmersive();
        setupRail();
        setupOverlays();
        setupTrailer();
        setupSound();

        updateBattery();
        tickClock();
        uiHandler.postDelayed(clockTask, 20000);
        resetHintFade();

        // 入场动画 + 游戏库读取一起跑（读取被入场层盖住，用户看不到等待）
        showLoading(true);
        uiHandler.post(() -> {
            setupIntro();
            loadGames();
            showLoading(false);
        });
    }

    private void bindViews() {
        rail = findViewById(R.id.bsRail);
        shelfContainer = findViewById(R.id.bsShelfContainer);
        shelfScroll = findViewById(R.id.bsShelfScroll);
        overlayLayer = findViewById(R.id.bsOverlayLayer);
        bgA = findViewById(R.id.bsBgA);
        bgB = findViewById(R.id.bsBgB);
        bgVideo = findViewById(R.id.bsBgVideo);
        clockView = findViewById(R.id.bsClock);
        netView = findViewById(R.id.bsNet);
        batteryView = findViewById(R.id.bsBattery);
        gamepadView = findViewById(R.id.bsGamepadState);
        hintView = findViewById(R.id.bsHints);
        infoTitleView = findViewById(R.id.bsInfoTitle);
        infoMetaView = findViewById(R.id.bsInfoMeta);
        infoActions = findViewById(R.id.bsInfoActions);
        infoChips = findViewById(R.id.bsInfoChips);
        infoBar = findViewById(R.id.bsInfoBar);
        infoLogo = findViewById(R.id.bsInfoLogo);
        scrimLeft = findViewById(R.id.bsScrimLeft);
        scrimBottom = findViewById(R.id.bsScrimBottom);
        videoScrim = findViewById(R.id.bsVideoScrim);
        emptyView = findViewById(R.id.bsEmpty);
        loadingView = findViewById(R.id.bsLoading);
        snowView = findViewById(R.id.bsSnow);
        banner = new BigScreenBanner(findViewById(R.id.bsBanner), findViewById(R.id.bsBanner));
        topBar = findViewById(R.id.bsTopBar);
        bottomBar = findViewById(R.id.bsBottomBar);
        menuBtn = findViewById(R.id.bsMenuBtn);
        sizes = new BigScreenSizes(this);
        applySizes();
        if (menuBtn != null) { menuBtn.setOnClickListener(v -> { BigScreenSound.open(); openMainMenu(); }); }
        applyEffectLevel();
    }

    /**
     * 性能档落地（spec §6.3 / §S10.6）：
     * high = 全部特效；medium ≈ 常态；low = 关掉入场动画/强化动效/模糊/PV。
     * 雪花氛围层只有 high 才显示（它是持续的 GPU 开销）。
     */
    private void applyEffectLevel() {
        if (snowView == null) { return; }
        if ("high".equals(prefs.effectLevel()) && prefs.snowEnabled()) {
            snowView.setVisibility(View.VISIBLE);
            snowView.setThemeColors(
                    ContextCompat.getColor(this, R.color.bs_bg),
                    ContextCompat.getColor(this, R.color.bs_bg2),
                    ContextCompat.getColor(this, R.color.bs_focus),
                    ContextCompat.getColor(this, R.color.bs_primary),
                    0xFF8A6BFF);
        } else {
            snowView.setVisibility(View.GONE);
        }
    }

    private void setupRail() {
        rail.setListener(this);
        // M14：侧栏展开时让信息浮层让位（否则 250dp 的展开宽度会压住游戏名）
        rail.setExpandListener(expanded -> {
            railExpandedUi = expanded;
            applyInfoBarAlpha(expanded ? 0f : 1f, expanded ? dp(40) : 0f,
                    expanded ? 150L : 220L);
        });
        applySizes();   // 侧栏度量（按屏幕高度算，横屏才不会把下面几个分类裁掉）
        rail.setPinned(prefs.railExpanded());
    }

    private void setupOverlays() {
        detailsLayer = new BigScreenDetailsLayer(this, metaLoader, this);
        detailsLayer.attachTo(overlayLayer);
        // 顺序有讲究：详情层 → 设置层 → 菜单面板，后者永远盖在前者之上
        // （设置里的"选择器"就是复用菜单面板，所以它必须在设置之上）
        detailsLayer.setKeyStyle(prefs.keyStyle());
        detailsLayer.setNsfwBlurEnabled(prefs.nsfwBlur());
        settings = new BigScreenSettings(this, overlayLayer, new BigScreenSettings.Listener() {
            @Override public void onShown() { updateHints(); }
            @Override public void onHidden() { updateHints(); }
            @Override public void onChanged() { applyAllSettings(); }
        });
        if (sizes != null) {
            // 设置面板也按屏幕高度压扁，否则横屏手机上 5 个分区会超出
            settings.setRowHeights(
                    Math.max(32, Math.round(sizes.hDp * 0.11f)),
                    Math.max(34, Math.round(sizes.hDp * 0.115f)));
        }
        panel = new BigScreenPanel(overlayLayer, this);
        // M16：两个浮层都建好了，这里补一次度量（宽度/内边距按屏幕缩放）
        applyLayerMetrics();
        applyTouchUi();
    }

    /** 设置变化后的统一落地（音效 / 性能档 / 按键风格 / 卡片外观 / 提示条） */
    private void applyAllSettings() {
        keys = new BigScreenKeys(prefs.keyStyle());
        // M17：提示条时长 / 焦点缩放幅度 / 入场动画自定义，改了立刻生效
        if (banner != null) { banner.setHoldMs(prefs.bannerHoldMs()); }
        GamepadFocus.setScalePercent(prefs.focusScale());
        // M18：大屏自身的焦点视觉也跟随（否则这个设置在大屏里是"假的"）
        if (detailsLayer != null) { detailsLayer.setFocusScalePercent(prefs.focusScale()); }
        if (intro != null) {
            intro.setVideoUri(prefs.introVideoUri());
        }
        if (sound != null) {
            sound.setEnabled(prefs.soundEnabled());
            sound.setVolume(prefs.soundVolume());
        }
        if (trailerPlayer != null) {
            trailerPlayer.setMuted(prefs.trailerMuted());
            trailerPlayer.setFitMode(prefs.pvFit());
        }
        // 遮罩设置改了要立刻生效（PV 正在播就用播放中状态重算）
        applyVideoScrim(trailerPlayer != null && trailerPlayer.isPlaying());
        if (rail != null) { rail.setPinned(prefs.railExpanded()); }
        if (detailsLayer != null) {
            detailsLayer.setKeyStyle(prefs.keyStyle());
            detailsLayer.setNsfwBlurEnabled(prefs.nsfwBlur());
            detailsLayer.refreshCurrent();
        }
        applyEffectLevel();
        applyCardPrefs();
        updateHints();
        updateInfoBar(focusedGame());
        updateHintVisibility();
    }

    private void applyCardPrefs() {
        for (Shelf s : shelves) {
            if (s.adapter == null) { continue; }
            s.adapter.setCardScale(prefs.cardScale());
            s.adapter.setShowTitles(prefs.showTitles());
            s.adapter.setKeys(keys);
            s.adapter.setNsfwBlurEnabled(prefs.nsfwBlur());
            // M18：焦点缩放幅度同步到大屏卡片
            s.adapter.setFocusScalePercent(prefs.focusScale());
            s.adapter.setGapPx(dp(sizes != null ? Math.max(sizes.gap, 9) : 12));
            s.adapter.notifyDataSetChanged();
        }
        // M18-8：**行高和信息层必须跟着重算**。
        // 之前这里只改适配器 → 卡片变大了、行高还是旧值（封面被裁），信息层也不动，
        // 用户在设置里改"卡片大小"看起来就像"改了跟没改一样"。
        applyRowHeights();
        applySizes();
    }

    /**
     * M18-8：按当前卡片档位重算**每一行的行高/内边距**。
     *
     * <p>行高 = 放大后的卡片高 + 18（原来写死基准高，选"更大"时封面底部会被裁）；
     * 内边距下边收成 4dp —— 用户要求"底部就一行 + 一点点边距"。
     */
    private void applyRowHeights() {
        if (shelves == null || sizes == null) { return; }
        final int rowCardH = scaledCardHDp();
        final int overflowPad = Math.max(16, Math.min(48, Math.round(sizes.cardW * 0.35f)));
        for (Shelf s : shelves) {
            if (s == null || s.rv == null) { continue; }
            ViewGroup.LayoutParams lp = s.rv.getLayoutParams();
            if (lp != null) {
                lp.height = dp(rowCardH + 18);
                s.rv.setLayoutParams(lp);
            }
            s.rv.setPadding(0, dp(8), dp(overflowPad), dp(4));
            s.rv.requestLayout();
        }
    }

    /**
     * 按屏幕尺寸铺排（**横屏优先**）。
     * 之前尺寸全写死 dp，横屏手机上必然溢出：卡片被裁、7 个侧栏图标只露得出 4 个。
     */
    private void applySizes() {
        if (sizes == null) { return; }
        setViewHeight(topBar, sizes.topBarH);
        // M14-1：底栏按键提示字号 —— 之前写死在布局里的 12.5sp，被只有 12~20dp 的底栏裁掉半截
        if (hintView != null) {
            hintView.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizes.hintSp);
            hintView.setSingleLine(false);
            hintView.setMaxLines(2);
            hintView.setEllipsize(android.text.TextUtils.TruncateAt.END);
            hintView.setLineSpacing(0f, 1.06f);
        }
        setViewHeight(bottomBar, sizes.bottomBarH);
        if (rail != null) {
            rail.setMetrics(sizes.railCollapsedW, sizes.railExpandedW,
                    sizes.railItemH, sizes.railIconBox);
        }
        applyLayerMetrics();
        applyInfoMetrics();
        // M18-12：**手柄模式刚进大屏那一下的轻微错位**（"动一下就正常了"）——
        // 根因是滚动位置要在首帧之后才准（见 rebuildShelves 里的 post 对齐）。
        // 这里再补一次"内容层快速淡入"：即使首帧真差了几像素，也在 220ms 内抹平，
        // 不会留下"信息层压着卡片"的静态观感；触摸模式本来就没这问题，看到的效果一样。
        if (shelfContainer != null) {
            shelfContainer.setAlpha(0f);
            shelfContainer.animate().alpha(1f).setDuration(220L).start();
        }
        if (shelfScroll != null) {
            shelfScroll.post(() -> {
                if (focusEngine != null) { scrollVerticalToRow(focusEngine.row()); }
                // M18-15：行布局完成 + 底栏高度已定 → 用**实测值**重算信息层位置。
                // 这是最终生效的一次（首帧之前那几次调用用的是估算值，会被这次覆盖）。
                applyInfoMetrics();
            });
        }
    }

    /**
     * M16：把两个浮层的尺寸纳入按屏幕缩放（原来硬编码，在小屏横屏上显得越来越宽）。
     * settings 可能是延迟创建的，所以这里都要判空。
     */
    private void applyLayerMetrics() {
        if (sizes == null) { return; }
        if (panel != null) {
            panel.setPanelMetrics(dp(sizes.panelW), dp(sizes.panelRightMargin));
        }
        if (settings != null) {
            settings.setMetrics(dp(sizes.setPadH), dp(sizes.setLeftColW));
        }
    }

    /** M16：触摸专用 UI（关闭按钮）只在触摸模式显示 —— 手柄玩家看着它反而乱 */
    private void applyTouchUi() {
        boolean touchUi = touchMode || inputRouter == null || !inputRouter.isGamepadConnected();
        if (panel != null) { panel.setTouchUi(touchUi); }
        if (settings != null) { settings.setTouchUi(touchUi); }
        // M18-5：详情页按钮焦点也跟随（触摸模式下不预选"游玩"）
        if (detailsLayer != null) { detailsLayer.setTouchUi(touchUi); }
    }

    /**
     * 信息浮层的字号与位置（M6 整体缩放）。
     * 之前标题写死 30sp：在 360dp 高的横屏手机上等于"半屏都是字"，必须按屏幕短边算。
     */
    /** M18-15：底栏的**真实**高度（dp）—— 触摸模式下底栏高度是 0（卡片行贴屏幕下沿），
     *  之前锚点永远扣 `sizes.bottomBarH`，触摸模式就白让了一整条底栏 → "空隙有点大"。 */
    private int bottomBarDp() {
        if (bottomBar != null && bottomBar.getHeight() > 0) {
            return Math.round(bottomBar.getHeight() / getResources().getDisplayMetrics().density);
        }
        return sizes != null ? sizes.bottomBarH : 0;
    }

    private void applyInfoMetrics() {
        if (sizes == null || infoBar == null) { return; }
        infoTitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizes.infoTitleSp);
        infoMetaView.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizes.infoMetaSp);
        ViewGroup.LayoutParams lp = infoBar.getLayoutParams();
        if (lp instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) lp;
            // M18-7：**整行高度全部还给卡片行**，信息层只在其上方留一点点间隙。
            // （M18-6 我写了"只吸收一半溢出"，结果信息层被挤到顶上、按钮正好压住卡片行的标题 —— 已修）
            // M18-15：底栏高度必须是**真实值**（触摸模式 = 0），否则会白留一条底栏的空隙。
            // M18-16：实测行高的余量也收到 2dp —— 之前是 +4，和 INFO_GAP_DP 叠起来会显得空。
            final int rowBand = Math.max(scaledRowHDp(), measuredRowBandDp() + 2);
            int below = rowBand + bottomBarDp() + INFO_GAP_DP;
            mlp.topMargin = 0;
            mlp.bottomMargin = dp(below);
            if (lp instanceof FrameLayout.LayoutParams) {
                ((FrameLayout.LayoutParams) lp).gravity = Gravity.START | Gravity.BOTTOM;
            }
            infoBar.setLayoutParams(mlp);
            // 卡片放大后总高可能超出屏幕：把信息层等比缩小并锚在底边（而不是让它顶到顶栏上）
            applyInfoFitScale(rowBand);
        }
        // 标题/副行的最大宽度按屏幕宽度给，避免长日文标题顶出屏幕
        int maxW = dp(Math.max(200, sizes.wDp - sizes.railCollapsedW - 56));
        infoTitleView.setMaxWidth(maxW);
        infoMetaView.setMaxWidth(maxW);
        if (infoLogo != null) {
            infoLogo.setMaxWidth(maxW);
            infoLogo.setMaxHeight(dp(Math.round(sizes.hDp * 0.16f)));
        }
        if (infoChips != null) { infoChips.setMinimumWidth(0); }
    }

    /**
     * M18-7：卡片越大、卡片行占的竖向空间越多 → 信息层可用的高度就越少。
     * 这里把信息层**等比缩小并锚在底边**（pivot 在左下角）：
     * 缩的是"画出来的大小"，不改变它的位置（底边仍然紧贴卡片行上方一点点），
     * 所以不会顶到顶栏上、也不会压住卡片行。1.0 = 不缩。
     */
    private void applyInfoFitScale(int rowBandDp) {
        if (infoBar == null || sizes == null) { return; }
        int naturalPx = infoBar.getHeight() > 0 ? infoBar.getHeight() : dp(sizes.infoReserveH);
        int availPx = dp(sizes.hDp - sizes.topBarH - bottomBarDp() - rowBandDp - INFO_GAP_DP - 6);
        float fit = Math.min(1f, availPx / (float) Math.max(1, naturalPx));
        fit = Math.max(0.65f, fit);   // 别缩到看不清
        infoBar.setPivotX(0f);
        infoBar.setPivotY(naturalPx);
        infoBar.setScaleX(fit);
        infoBar.setScaleY(fit);
        // 第一次量到真实高度后，把锚点校正到底边（否则刚进界面那一下会往上跳）
        infoBar.post(new Runnable() {
            @Override
            public void run() {
                if (infoBar != null && infoBar.getHeight() > 0) {
                    infoBar.setPivotY(infoBar.getHeight());
                    infoBar.invalidate();
                }
            }
        });
    }

    private void setViewHeight(View v, int heightDp) {
        if (v == null) { return; }
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (lp == null) { return; }
        lp.height = dp(heightDp);
        v.setLayoutParams(lp);
    }

    /**
     * 沉浸式 + 挖孔屏铺满：
     * ① 隐藏状态栏 / 导航栏（BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE：边缘滑动可临时唤出）
     * ② `shortEdges` 让内容延伸到挖孔区（横屏时挖孔在左右两侧）
     * ③ 把挖孔 inset 变成内容内边距 —— 背景仍铺满，但文字/图标不会被挖孔挡住
     */
    private void applyImmersive() {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowManager.LayoutParams attrs = getWindow().getAttributes();
            attrs.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(attrs);
        }
        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        if (controller != null) {
            controller.hide(WindowInsetsCompat.Type.systemBars());
            controller.setSystemBarsBehavior(
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
        final View content = findViewById(R.id.bsContentRoot);
        if (content != null) {
            ViewCompat.setOnApplyWindowInsetsListener(content, (v, insets) -> {
                Insets cut = insets.getInsets(WindowInsetsCompat.Type.displayCutout());
                v.setPadding(cut.left, cut.top, cut.right, cut.bottom);
                return insets;
            });
            content.requestApplyInsets();
        }
    }

    private void showLoading(boolean show) {
        loadingView.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) {
            shelfScroll.setVisibility(View.GONE);
            emptyView.setVisibility(View.GONE);
        }
    }
// ================= PV视频（M2.5 / spec §S10）=================

    /** 取消 PV 并恢复静态背景（所有"不该播"的分支都走这里，避免视频停了背景还是空的） */
    private void cancelTrailer() {
        if (trailerPlayer != null) { trailerPlayer.cancel(); }
        setBgStillVisible(true);
        applyVideoScrim(false);
        if (detailsLayer != null) { detailsLayer.setVideoPlaying(false); }
    }

    /**
     * PV 遮罩（M11）：播放时按设置压暗视频。
     * <ul>
     *   <li>关闭遮罩 → 视频层之上的黑色遮罩不出现，界面渐变遮罩也收到最轻</li>
     *   <li>强度 0~100% → 直接决定黑色遮罩 alpha（最高 0.62）</li>
     * </ul>
     * 顺带把"信息浮层自动收起"整套去掉了 —— 用户反馈那样太怪。
     */
    private void applyVideoScrim(boolean playing) {
        float pct = prefs.pvScrim() ? (prefs.pvScrimPercent() / 100f) : 0f;
        if (videoScrim != null) {
            boolean on = playing && pct > 0.01f;
            videoScrim.animate().cancel();
            videoScrim.setVisibility(on ? View.VISIBLE : View.GONE);
            videoScrim.setAlpha(pct * 0.62f);
        }
        // 播放时给界面用的两块渐变遮罩留一点（保证文字可读），但明显比静止时轻
        float base = playing ? (0.30f + pct * 0.70f) : 1f;
        if (scrimLeft != null) { scrimLeft.setAlpha(base); }
        if (scrimBottom != null) { scrimBottom.setAlpha(base); }
    }

    /**
     * 静态背景静图的显隐（M8）。
     * PV 播放时背景图必须让位，否则视频层之下还是那张封面（用户："放 pv 还能显示背景封面"）。
     */
    private void setBgStillVisible(boolean visible) {
        if (bgA == null || bgB == null) { return; }
        ImageView front = "A".equals(bgFront) ? bgA : bgB;
        ImageView back = "A".equals(bgFront) ? bgB : bgA;
        front.animate().alpha(visible ? 0.95f : 0f).setDuration(visible ? 360L : 240L).start();
        back.animate().alpha(0f).setDuration(240L).start();
    }


    private void setupTrailer() {
        trailerManager = new TrailerManager(this);
        trailerPlayer = new TrailerPlayer(this, new TrailerPlayer.Listener() {
            @Override
            public void onTrailerStarted(String path) {
                // M8：PV 开始播放 → 把静态背景图淡出（否则视频上会透出封面，看着像视频半透明）
                setBgStillVisible(false);
                // M11：不再自动收起信息层（用户反馈"太怪了"）；只按设置给视频加可调遮罩
                applyVideoScrim(true);
                if (detailsLayer != null && detailsLayer.isVisible()) {
                    detailsLayer.setVideoPlaying(true);
                }
            }

            @Override
            public void onTrailerFailed(String path, String reason) {
                if ("missing".equals(reason)) {
                    // 失效自愈：文件被删了 → 静默解除绑定，回退静态封面
                    selfHealMissingTrailer(path);
                }
            }
        });
        trailerPlayer.setMuted(prefs.trailerMuted());
        trailerPlayer.setFitMode(prefs.pvFit());

        // 文件选择器必须在 STARTED 之前注册
        trailerPickerLauncher = registerForActivityResult(
                new ActivityResultContracts.GetContent(), uri -> {
                    if (uri != null) { onTrailerPicked(uri); }
                    pendingTrailerGame = null;
                });
        // 自定义标题图 / 背景图选择器（M10）
        artManager = new BigScreenArt(this);
        artPickerLauncher = registerForActivityResult(
                new ActivityResultContracts.GetContent(), uri -> {
                    if (uri != null) { onArtPicked(uri); }
                    pendingArtGame = null;
                    pendingArtKind = null;
                });
    }

    /** S4 菜单：请求给某游戏绑定PV视频 */
    private void requestTrailerPick(Game game) {
        if (game == null) { return; }
        pendingTrailerGame = game;
        try {
            trailerPickerLauncher.launch("video/*");
        } catch (Throwable t) {
            toast("无法打开文件选择器：" + t.getMessage());
        }
    }

    /** PV 来源选择（M11）：本地视频 / 网络直链 */
    private void openTrailerSourceMenu(Game game) {
        if (game == null) { return; }
        List<BigScreenPanel.Item> items = new ArrayList<>();
        items.add(new BigScreenPanel.Item("本地视频文件", "从文件里挑一个", R.drawable.bs_ic_play,
                () -> { panel.hide(); requestTrailerPick(game); }));
        items.add(new BigScreenPanel.Item("视频链接（URL）", "http(s) 直链",
                R.drawable.bs_ic_chip, () -> { panel.hide(); promptTrailerUrl(game); }));
        if (!TextUtils.isEmpty(game.trailerPath)) {
            items.add(new BigScreenPanel.Item("移除当前PV", null, 0,
                    () -> { panel.hide(); removeTrailer(game); }));
        }
        panel.show("PV视频来源", items);
    }

    /** 让用户填一个 http(s) 视频直链（M11） */
    private void promptTrailerUrl(final Game game) {
        final EditText input = new EditText(this);
        input.setHint("https://example.com/pv.mp4");
        input.setSingleLine(true);
        input.setInputType(android.text.InputType.TYPE_TEXT_VARIATION_URI);
        input.setTextColor(ContextCompat.getColor(this, R.color.bs_text));
        input.setHintTextColor(ContextCompat.getColor(this, R.color.bs_text_muted));
        if (TrailerPlayer.isRemote(game.trailerPath)) { input.setText(game.trailerPath); }
        new AlertDialog.Builder(this)
                .setTitle("PV 视频链接")
                .setMessage("支持 http / https 直链（mp4 最佳；能不能播取决于系统解码器）")
                .setView(input)
                .setPositiveButton("保存", (d, w) -> {
                    String url = input.getText() == null ? "" : input.getText().toString().trim();
                    if (!TrailerPlayer.isRemote(url)) {
                        toast("请填写 http:// 或 https:// 开头的链接");
                        return;
                    }
                    game.trailerPath = url;
                    try { repository.update(game); } catch (Throwable ignored) { }
                    updateInfoBar(focusedGame());
                    if (focusedGame() == game && !detailsLayer.isVisible()) { syncTrailer(game); }
                    toast("PV 链接已设置");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 链接太长时截断显示（菜单副标题用） */
    private String shortUrl(String url) {
        if (url == null) { return ""; }
        String s = url;
        int slash = s.lastIndexOf('/');
        if (slash >= 0 && slash < s.length() - 1) { s = s.substring(slash + 1); }
        return s.length() > 28 ? s.substring(0, 28) + "…" : s;
    }

    // ================= 自定义标题图 / 背景图（M10）=================

    private void requestArtPick(Game game, String kind) {
        if (game == null || artPickerLauncher == null) { return; }
        pendingArtGame = game;
        pendingArtKind = kind;
        try {
            artPickerLauncher.launch("image/*");
        } catch (Throwable t) {
            toast("无法打开图片选择器：" + t.getMessage());
        }
    }

    private void onArtPicked(final Uri uri) {
        final Game game = pendingArtGame;
        final String kind = pendingArtKind;
        if (game == null || uri == null || kind == null) { return; }
        toast("正在导入图片…");
        bgExecutor.execute(() -> {
            final File copied = artManager.copyToInternal(uri, game.id, kind);
            uiHandler.post(() -> {
                if (copied == null) {
                    toast("导入失败（图片无法读取）");
                    return;
                }
                if (BigScreenArt.KIND_LOGO.equals(kind)) {
                    String old = game.logoPath;
                    game.logoPath = copied.getAbsolutePath();
                    if (!TextUtils.isEmpty(old)) { artManager.delete(old); }
                } else {
                    String old = game.bgPath;
                    game.bgPath = copied.getAbsolutePath();
                    if (!TextUtils.isEmpty(old)) { artManager.delete(old); }
                }
                try { repository.update(game); } catch (Throwable ignored) { }
                applyInfoLogo(game);
                if (BigScreenArt.KIND_BG.equals(kind)) {
                    setBackground(bgUriOf(game), game.nsfw && prefs.nsfwBlur());
                }
                toast(BigScreenArt.KIND_LOGO.equals(kind) ? "标题图已设置" : "背景图已设置");
            });
        });
    }

    private void clearArt(Game game, String kind) {
        if (game == null) { return; }
        if (BigScreenArt.KIND_LOGO.equals(kind)) {
            if (!TextUtils.isEmpty(game.logoPath)) { artManager.delete(game.logoPath); }
            game.logoPath = null;
        } else {
            if (!TextUtils.isEmpty(game.bgPath)) { artManager.delete(game.bgPath); }
            game.bgPath = null;
        }
        try { repository.update(game); } catch (Throwable ignored) { }
        applyInfoLogo(game);
        if (BigScreenArt.KIND_BG.equals(kind)) {
            setBackground(bgUriOf(game), game.nsfw && prefs.nsfwBlur());
        }
        toast("已清除");
    }

    /**
     * 信息浮层大标题：设了自定义标题图就用**图片替代文字**（Steam 式 logo）；
     * 解码失败自动回退文字，不会留空白。
     */
    private void applyInfoLogo(Game game) {
        if (infoLogo == null || infoTitleView == null) { return; }
        String logo = game == null ? null : game.logoPath;
        if (!TextUtils.isEmpty(logo) && new File(logo).exists()) {
            try {
                Bitmap bmp = BitmapFactory.decodeFile(logo);
                if (bmp != null) {
                    infoLogo.setImageBitmap(bmp);
                    infoLogo.setVisibility(View.VISIBLE);
                    infoTitleView.setVisibility(View.GONE);
                    return;
                }
            } catch (Throwable ignored) { }
        }
        infoLogo.setImageDrawable(null);
        infoLogo.setVisibility(View.GONE);
        infoTitleView.setVisibility(View.VISIBLE);
    }

    /** 背景优先用自定义背景图（M10），否则退回封面 */
    private static String bgUriOf(Game g) {
        if (g == null) { return null; }
        if (!TextUtils.isEmpty(g.bgPath) && new File(g.bgPath).exists()) {
            return Uri.fromFile(new File(g.bgPath)).toString();
        }
        return coverUriOf(g);
    }

    private void onTrailerPicked(final Uri uri) {
        final Game game = pendingTrailerGame;
        if (game == null) { return; }
        toast("正在检查视频…");
        bgExecutor.execute(() -> {
            final TrailerManager.Probe probe = trailerManager.probe(uri);
            uiHandler.post(() -> evaluateTrailer(game, uri, probe));
        });
    }

    /** 校验（spec §S10.3）：>500MB 拒绝 · >100MB 确认 · >120s 提示 */
    private void evaluateTrailer(final Game game, final Uri uri, final TrailerManager.Probe probe) {
        if (probe.sizeBytes > TrailerManager.REJECT_BYTES) {
            toast("视频超过 500MB（" + TrailerManager.formatSize(probe.sizeBytes)
                    + "），请先裁剪再绑定");
            return;
        }
        if (probe.durationMs > TrailerManager.WARN_DURATION_MS) {
            toast("提示：视频约 " + (probe.durationMs / 1000) + " 秒，PV建议 60 秒内");
        }
        if (probe.sizeBytes > TrailerManager.WARN_BYTES) {
            new AlertDialog.Builder(this)
                    .setTitle("视频较大")
                    .setMessage("文件约 " + TrailerManager.formatSize(probe.sizeBytes)
                            + "，将复制进应用内部存储（会占用手机空间）。继续吗？")
                    .setPositiveButton("继续", (d, w) -> importTrailer(game, uri))
                    .setNegativeButton("取消", null)
                    .show();
        } else {
            importTrailer(game, uri);
        }
    }

    private void importTrailer(final Game game, final Uri uri) {
        toast("正在复制视频…");
        bgExecutor.execute(() -> {
            final File copied = trailerManager.copyToInternal(uri, game.id);
            uiHandler.post(() -> {
                if (copied == null) {
                    toast("复制失败：空间不足或来源不可读");
                    return;
                }
                // 换绑时删掉旧文件，避免残留占空间
                trailerManager.delete(game.trailerPath);
                game.trailerPath = copied.getAbsolutePath();
                try { repository.update(game); } catch (Throwable ignored) { }
                toast("PV已绑定 · " + TrailerManager.formatSize(copied.length()));
                updateInfoBar(game);
                syncTrailer(game);
            });
        });
    }

    private void removeTrailer(Game game) {
        if (game == null) { return; }
        cancelTrailer();
        trailerManager.delete(game.trailerPath);
        game.trailerPath = null;
        try { repository.update(game); } catch (Throwable ignored) { }
        toast("已移除PV视频");
        updateInfoBar(game);
    }

    /** 文件不在了：解除绑定（不打扰用户，只在首次提示一次） */
    private void selfHealMissingTrailer(String path) {
        if (path == null) { return; }
        boolean healed = false;
        for (Game g : allGames) {
            if (path.equals(g.trailerPath)) {
                g.trailerPath = null;
                try { repository.update(g); } catch (Throwable ignored) { }
                healed = true;
                break;
            }
        }
        if (healed) { toast("PV文件已不存在，已自动解除绑定"); }
    }

    /** 目标（主背景 / 详情层背景）+ 焦点游戏 → 统一驱动PV播放 */
    private void syncTrailer(Game game) {
        if (trailerPlayer == null || detailsLayer == null) { return; }
        // M17：入场动画没播完 → 不起播、也不开始计延迟（等 onIntroEnded 再统一来一次）
        if (intro != null && intro.isPlaying()) {
            trailerWaitIntro = true;
            return;
        }
        final boolean detailsVisible = detailsLayer.isVisible();
        trailerPlayer.attach(detailsVisible ? detailsLayer.videoTarget() : bgVideo);
        if (game == null) {
            game = detailsVisible ? detailsLayer.current() : focusedGame();
        }
        updateTrailerForFocus(game, detailsVisible);
    }

    private void updateTrailerForFocus(Game game) {
        updateTrailerForFocus(game, detailsLayer != null && detailsLayer.isVisible());
    }

    /**
     * 播放决策（spec §S10.4 / §S10.6）：
     * 低性能档禁用；medium 仅详情层；未绑定不播；文件失效自愈。
     */
    private void updateTrailerForFocus(Game game, boolean detailsVisible) {
        if (trailerPlayer == null || trailerManager == null) { return; }
        // NSFW 且开了封面模糊：PV视频不播（视频内容没法模糊，给个静默的判定最安全）
        if (game != null && game.nsfw && prefs.nsfwBlur()) {
            cancelTrailer();
            return;
        }
        if (!prefs.trailerEnabled() || prefs.lowEndDevice()) {
            cancelTrailer();
            return;
        }
        if (game == null || TextUtils.isEmpty(game.trailerPath)) {
            cancelTrailer();
            return;
        }
        if (prefs.trailerDetailsOnly() && !detailsVisible) {
            cancelTrailer();
            return;
        }
        if (!trailerManager.exists(game.trailerPath)) {
            cancelTrailer();
            selfHealMissingTrailer(game.trailerPath);
            return;
        }
        trailerPlayer.setMuted(prefs.trailerMuted());
        trailerPlayer.setFitMode(prefs.pvFit());
        // 悬停 1.2s（可配）后才真正起播；焦点一动就会 cancel 掉重新计时
        trailerPlayer.request(game.trailerPath, prefs.trailerDelayMs());
    }

    // ================= 设置面板（M4 / spec §S6）=================

    private void openSettings() {
        if (settings == null) { return; }
        settings.setSections(buildSettingsSections());
        settings.show();
        updateHints();
    }

    private List<BigScreenSettings.Section> buildSettingsSections() {
        List<BigScreenSettings.Section> list = new ArrayList<>();

        // ① 常规
        list.add(new BigScreenSettings.Section("常规", java.util.Arrays.asList(
                new BigScreenSettings.Item("性能档", this::effectLevelLabel, this::chooseEffectLevel),
                new BigScreenSettings.Item("入场动画", () -> yesNo(prefs.introEnabled()),
                        () -> { prefs.setIntroEnabled(!prefs.introEnabled()); settings.refreshItems(); }),
                new BigScreenSettings.Item("开机直接进大屏", this::startupLabel, () -> {
                    boolean on = !"bigscreen".equals(currentStartupPage());
                    prefs.setAutoEnter(on);
                    getSharedPreferences(BigScreenPrefs.PREFS, MODE_PRIVATE).edit()
                            .putString("startup_page", on ? "bigscreen" : "home").apply();
                    settings.refreshItems();
                }),
                new BigScreenSettings.Item("记住筛选", () -> yesNo(prefs.rememberFilter()),
                        () -> { prefs.setRememberFilter(!prefs.rememberFilter()); settings.refreshItems(); })
        )));

        // ② 视觉
        list.add(new BigScreenSettings.Section("视觉", java.util.Arrays.asList(
                new BigScreenSettings.Item("卡片大小", this::cardScaleLabel, this::chooseCardScale),
                // ===== M18-2：入场动画（用户要的：可换视频；原来的"风格/速度/文字"三项已删） =====
                new BigScreenSettings.Item("入场动画", this::introSourceLabel, this::chooseIntroSource),
                // ===== M17：观感细节 =====
                new BigScreenSettings.Item("提示条时长", this::bannerHoldLabel, this::chooseBannerHold),
                new BigScreenSettings.Item("焦点缩放", this::focusScaleLabel, this::chooseFocusScale),
                new BigScreenSettings.Item("显示卡片标题", () -> yesNo(prefs.showTitles()),
                        () -> { prefs.setShowTitles(!prefs.showTitles()); settings.refreshItems(); }),
                new BigScreenSettings.Item("背景氛围（雪花）", () -> yesNo(prefs.snowEnabled()),
                        () -> { prefs.setSnowEnabled(!prefs.snowEnabled()); settings.refreshItems(); }),
                new BigScreenSettings.Item("NSFW 封面模糊", () -> yesNo(prefs.nsfwBlur()),
                        () -> { prefs.setNsfwBlur(!prefs.nsfwBlur()); settings.refreshItems(); }),
                new BigScreenSettings.Item("PV只在详情层播放", () -> yesNo(prefs.trailerDetailsOnly()),
                        () -> { prefs.setTrailerDetailsOnly(!prefs.trailerDetailsOnly()); settings.refreshItems(); }),
                new BigScreenSettings.Item("PV显示方式", () -> prefs.pvFit() ? "原比例" : "铺满",
                        () -> {
                            List<BigScreenPanel.Item> items = new ArrayList<>();
                            String cur = prefs.pvFit() ? "fit" : "fill";
                            items.add(selectorItem("pvfit", "铺满（裁切）", "fill", cur));
                            items.add(selectorItem("pvfit", "原比例（留黑边）", "fit", cur));
                            panel.show("PV显示方式", items);
                        }),
                new BigScreenSettings.Item("PV遮罩", () -> yesNo(prefs.pvScrim()),
                        () -> { prefs.setPvScrim(!prefs.pvScrim()); settings.refreshItems(); }),
                new BigScreenSettings.Item("PV遮罩强度", () -> prefs.pvScrimPercent() + "%",
                        () -> {
                            List<BigScreenPanel.Item> items = new ArrayList<>();
                            String cur = String.valueOf(prefs.pvScrimPercent());
                            for (int v : new int[]{0, 20, 35, 45, 60, 80}) {
                                items.add(selectorItem("pvscrim", v + "%", String.valueOf(v), cur));
                            }
                            panel.show("PV遮罩强度", items);
                        })
        )));

        // ③ 音频
        list.add(new BigScreenSettings.Section("音频", java.util.Arrays.asList(
                new BigScreenSettings.Item("界面音效", () -> yesNo(prefs.soundEnabled()),
                        () -> { prefs.setSoundEnabled(!prefs.soundEnabled()); settings.refreshItems(); }),
                new BigScreenSettings.Item("音效音量", () -> prefs.soundVolume() + "%", this::chooseVolume),
                new BigScreenSettings.Item("焦点音", () -> yesNo(prefs.focusTick()),
                        () -> { prefs.setFocusTick(!prefs.focusTick()); settings.refreshItems(); }),
                new BigScreenSettings.Item("PV静音", () -> yesNo(prefs.trailerMuted()),
                        () -> { prefs.setTrailerMuted(!prefs.trailerMuted()); settings.refreshItems(); })
        )));

        // ④ 布局
        list.add(new BigScreenSettings.Section("布局", java.util.Arrays.asList(
                new BigScreenSettings.Item("侧栏钉住展开", () -> yesNo(prefs.railExpanded()),
                        () -> { prefs.setRailExpanded(!prefs.railExpanded()); settings.refreshItems(); }),
                new BigScreenSettings.Item("按键提示条", this::hintModeLabel, this::chooseHintMode),
                new BigScreenSettings.Item("PV播放延迟", () -> (prefs.trailerDelayMs() / 1000f) + " 秒",
                        this::chooseDelay),
                new BigScreenSettings.Item("PV占用与清理", this::trailerStorageLabel, this::openTrailerStorage)
        )));

        // ⑤ 菜单
        list.add(new BigScreenSettings.Section("菜单", java.util.Arrays.asList(
                new BigScreenSettings.Item("按键图标风格", () -> keys.styleName(), this::chooseKeyStyle),
                new BigScreenSettings.Item("清除筛选记忆", () -> prefs.lastFilter(),
                        () -> { prefs.setLastFilter("ALL"); settings.refreshItems(); toast("已清除筛选记忆"); }),
                new BigScreenSettings.Item("恢复默认设置", () -> "", this::resetBigScreenSettings)
        )));

        // ⑥ 兼容（M13：大屏改为原地启动后，KR 存档兜底由这里手动控制）
        list.add(new BigScreenSettings.Section("兼容", java.util.Arrays.asList(
                new BigScreenSettings.Item("KR存档兜底", () -> yesNo(prefs.krSafFallback()),
                        () -> {
                            prefs.setKrSafFallback(!prefs.krSafFallback());
                            settings.refreshItems();
                            toast(prefs.krSafFallback()
                                    ? "已开启：KR 游戏将通过 SAF 读写存档"
                                    : "已关闭");
                        })
        )));

        return list;
    }

    // ===== 值文案 =====
    private String yesNo(boolean v) { return v ? "开启" : "关闭"; }

    private String effectLevelLabel() {
        switch (prefs.effectLevel()) {
            case "low": return "低（最省电）";
            case "medium": return "中";
            default: return "高（全部特效）";
        }
    }

    private String cardScaleLabel() {
        int p = Math.round(prefs.cardScale() * 100);
        // M18-5：小=原来的"标准"，大=原来的"大"（现在默认），更大=新加的一档
        if (p <= 105) { return "小"; }
        if (p <= 118) { return "大"; }
        return "更大";
    }

    private String hintModeLabel() {
        switch (prefs.hintMode()) {
            case "off": return "隐藏";
            case "always": return "常显";
            default: return "自动淡出";
        }
    }

    private String startupLabel() { return "bigscreen".equals(currentStartupPage()) ? "开启" : "关闭"; }

    private String currentStartupPage() {
        try {
            return getSharedPreferences(BigScreenPrefs.PREFS, MODE_PRIVATE)
                    .getString("startup_page", "home");
        } catch (Throwable t) {
            return "home";
        }
    }

    private String trailerStorageLabel() {
        if (trailerManager == null) { return ""; }
        int count = trailerManager.fileCount();
        if (count == 0) { return "空"; }
        return count + " 个 · " + TrailerManager.formatSize(trailerManager.totalSize());
    }

    // ===== S8 选择器（复用 BigScreenPanel）=====

    private void chooseEffectLevel() {
        List<BigScreenPanel.Item> items = new ArrayList<>();
        items.add(selectorItem("effect", "高（全部特效）", "high", prefs.effectLevel()));
        items.add(selectorItem("effect", "中", "medium", prefs.effectLevel()));
        items.add(selectorItem("effect", "低（最省电）", "low", prefs.effectLevel()));
        panel.show("性能档", items);
    }

    private void chooseCardScale() {
        List<BigScreenPanel.Item> items = new ArrayList<>();
        String cur = String.valueOf(Math.round(prefs.cardScale() * 100));
        // M18-5：小 = 原来的"标准"；大 = 原来的"大"（现在默认）；更大 = 新加一档（有人反馈还是偏小）
        items.add(selectorItem("card", "小", "100", cur));
        items.add(selectorItem("card", "大（默认）", "112", cur));
        items.add(selectorItem("card", "更大", "128", cur));
        panel.show("卡片大小", items);
    }

    // ==================== M18-2：入场动画（可替换视频） ====================

    /** 入场动画来源：内置动画 / 用户选的视频文件名 */
    private String introSourceLabel() {
        String uri = prefs.introVideoUri();
        if (uri.isEmpty()) { return "内置动画"; }
        String name = uri;
        try {
            name = android.net.Uri.decode(uri);
            int slash = name.lastIndexOf('/');
            if (slash >= 0) { name = name.substring(slash + 1); }
            int colon = name.lastIndexOf(':');
            if (colon >= 0) { name = name.substring(colon + 1); }
        } catch (Throwable ignored) { }
        return name.isEmpty() ? "已选择视频" : name;
    }

    private void chooseIntroSource() {
        List<BigScreenPanel.Item> items = new ArrayList<>();
        items.add(new BigScreenPanel.Item("选择视频…", "挑一个视频文件当开场", 0, this::pickIntroVideo));
        if (!prefs.introVideoUri().isEmpty()) {
            items.add(new BigScreenPanel.Item("清除", "恢复内置开场动画", 0, () -> {
                prefs.setIntroVideoUri("");
                applyAllSettings();
                if (settings != null) { settings.refreshItems(); }
                toast("已恢复内置开场动画");
            }));
        }
        panel.show("入场动画", items);
    }

    private static final int REQ_INTRO_VIDEO = 0x8B21;

    /** M18-2：用系统文件选择器挑入场视频（SAF，权限持久化，重启后仍可读） */
    private void pickIntroVideo() {
        try {
            android.content.Intent it = new android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT);
            it.addCategory(android.content.Intent.CATEGORY_OPENABLE);
            it.setType("video/*");
            it.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | android.content.Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            startActivityForResult(it, REQ_INTRO_VIDEO);
        } catch (Throwable t) {
            toast("打不开文件选择器");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_INTRO_VIDEO) { return; }
        if (resultCode != RESULT_OK || data == null || data.getData() == null) { return; }
        final android.net.Uri uri = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Throwable ignored) { }
        prefs.setIntroVideoUri(uri.toString());
        applyAllSettings();
        if (settings != null) { settings.refreshItems(); }
        toast("已设为开场视频（下次进入大屏生效）");
    }

    private String bannerHoldLabel() {
        int ms = prefs.bannerHoldMs();
        if (ms <= 1400) { return "短（1.2s）"; }
        if (ms <= 2200) { return "标准（2s）"; }
        if (ms <= 3200) { return "长（3s）"; }
        return "很长（4.5s）";
    }

    private void chooseBannerHold() {
        List<BigScreenPanel.Item> items = new ArrayList<>();
        String cur = String.valueOf(prefs.bannerHoldMs());
        int[] levels = {1200, 2000, 3000, 4500};
        String[] labels = {"短（1.2s）", "标准（2s）", "长（3s）", "很长（4.5s）"};
        for (int i = 0; i < levels.length; i++) {
            items.add(selectorItem("bannerhold", labels[i], String.valueOf(levels[i]), cur));
        }
        panel.show("提示条时长", items);
    }

    private String focusScaleLabel() {
        int v = prefs.focusScale();
        if (v == 0) { return "不缩放（只描边）"; }
        if (v <= 60) { return "轻微"; }
        if (v >= 140) { return "夸张"; }
        return "标准";
    }

    private void chooseFocusScale() {
        List<BigScreenPanel.Item> items = new ArrayList<>();
        String cur = String.valueOf(prefs.focusScale());
        items.add(selectorItem("focusscale", "不缩放（只描边）", "0", cur));
        items.add(selectorItem("focusscale", "轻微", "50", cur));
        items.add(selectorItem("focusscale", "标准", "100", cur));
        items.add(selectorItem("focusscale", "夸张", "150", cur));
        panel.show("焦点缩放", items);
    }

    private void chooseVolume() {
        List<BigScreenPanel.Item> items = new ArrayList<>();
        int[] levels = {0, 25, 50, 75, 100};
        String cur = String.valueOf(prefs.soundVolume());
        for (int v : levels) {
            items.add(selectorItem("volume", v + "%", String.valueOf(v), cur));
        }
        panel.show("音效音量", items);
    }

    private void chooseDelay() {
        List<BigScreenPanel.Item> items = new ArrayList<>();
        int[] delays = {500, 1200, 2000, 3000};
        String cur = String.valueOf(prefs.trailerDelayMs());
        for (int v : delays) {
            items.add(selectorItem("delay", (v / 1000f) + " 秒", String.valueOf(v), cur));
        }
        panel.show("PV播放延迟", items);
    }

    private void chooseKeyStyle() {
        List<BigScreenPanel.Item> items = new ArrayList<>();
        items.add(selectorItem("keys", "Xbox（Ⓐ Ⓑ Ⓧ Ⓨ）", BigScreenKeys.STYLE_XBOX, prefs.keyStyle()));
        items.add(selectorItem("keys", "PlayStation（✕ ○ □ △）", BigScreenKeys.STYLE_PS, prefs.keyStyle()));
        panel.show("按键图标风格", items);
    }

    private void chooseHintMode() {
        List<BigScreenPanel.Item> items = new ArrayList<>();
        items.add(selectorItem("hint", "自动淡出（4 秒）", "auto", prefs.hintMode()));
        items.add(selectorItem("hint", "常显", "always", prefs.hintMode()));
        items.add(selectorItem("hint", "隐藏", "off", prefs.hintMode()));
        panel.show("按键提示条", items);
    }

    /** 生成一个选择项：命中的那个带 ✓ 前缀。kind 用来明确"改哪一项设置"——
     *  不能靠值去猜（音量 100% 和卡片大小 100 会撞车）。 */
    private BigScreenPanel.Item selectorItem(String kind, String label, final String value, String current) {
        final boolean selected = value.equals(current);
        return new BigScreenPanel.Item(
                (selected ? "✓ " : "　") + label,
                null, 0,
                () -> {
                    panel.hide();
                    applySettingChoice(kind, value);
                    if (settings != null) { settings.refreshItems(); }
                });
    }

    /** 选择器的落地点：按 kind 分派，避免值域歧义 */
    private void applySettingChoice(String kind, String value) {
        try {
            switch (kind) {
                case "effect": prefs.setEffectLevel(value); break;
                case "card":   prefs.setCardScale(Integer.parseInt(value)); break;
                case "hint":   prefs.setHintMode(value); break;
                case "keys":   prefs.setKeyStyle(value); break;
                case "delay":  prefs.setTrailerDelayMs(Integer.parseInt(value)); break;
                case "volume": prefs.setSoundVolume(Integer.parseInt(value)); break;
                case "pvfit": prefs.setPvFit("fit".equals(value)); break;
                case "pvscrim": prefs.setPvScrimPercent(Integer.parseInt(value)); break;
                // M18-2：入场"风格/速度"已删除（改成可替换视频，走 chooseIntroSource）
                case "bannerhold":  prefs.setBannerHoldMs(Integer.parseInt(value)); break;
                case "focusscale":  prefs.setFocusScale(Integer.parseInt(value)); break;
                default: break;
            }
        } catch (Throwable ignored) { }
        applyAllSettings();
        // 延迟变化后如果正停着，重新计时
        if (trailerPlayer != null) { syncTrailer(null); }
    }

    // ===== PV占用清理（M4-1 / §S10.5）=====

    private void openTrailerStorage() {
        if (trailerManager == null) { return; }
        final int count = trailerManager.fileCount();
        final long size = trailerManager.totalSize();
        if (count == 0) { toast("PV目录是空的"); return; }
        new AlertDialog.Builder(this)
                .setTitle("PV视频占用")
                .setMessage("共 " + count + " 个文件，" + TrailerManager.formatSize(size)
                        + "。\n清理后会解除所有游戏的PV绑定（**不会动你原来的视频文件**）。")
                .setPositiveButton("清理", (d, w) -> {
                    int removed = trailerManager.deleteAll();
                    for (Game g : allGames) {
                        if (!TextUtils.isEmpty(g.trailerPath)) {
                            g.trailerPath = null;
                            try { repository.update(g); } catch (Throwable ignored) { }
                        }
                    }
                    cancelTrailer();
                    toast("已清理 " + removed + " 个PV文件 · 释放 " + TrailerManager.formatSize(size));
                    if (settings != null) { settings.refreshItems(); }
                    updateInfoBar(focusedGame());
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ===== 恢复默认（M4-1）=====

    private void resetBigScreenSettings() {
        new AlertDialog.Builder(this)
                .setTitle("恢复默认设置")
                .setMessage("会把大屏模式的全部设置恢复为默认值（不会动游戏库与PV文件）。")
                .setPositiveButton("恢复", (d, w) -> {
                    android.content.SharedPreferences sp =
                            getSharedPreferences(BigScreenPrefs.PREFS, MODE_PRIVATE);
                    android.content.SharedPreferences.Editor e = sp.edit();
                    for (String k : new String[]{
                            BigScreenPrefs.KEY_INTRO, BigScreenPrefs.KEY_SOUND,
                            BigScreenPrefs.KEY_SOUND_VOLUME, BigScreenPrefs.KEY_FOCUS_TICK,
                            BigScreenPrefs.KEY_KEY_STYLE, BigScreenPrefs.KEY_EFFECT_LEVEL,
                            BigScreenPrefs.KEY_RAIL_EXPANDED, BigScreenPrefs.KEY_GRID_COLUMNS,
                            BigScreenPrefs.KEY_SHOW_TITLES, BigScreenPrefs.KEY_LAST_FILTER,
                            BigScreenPrefs.KEY_AUTO_ENTER, BigScreenPrefs.KEY_REMEMBER_FILTER,
                            BigScreenPrefs.KEY_TRAILER, BigScreenPrefs.KEY_TRAILER_DELAY,
                            BigScreenPrefs.KEY_TRAILER_MUTED, BigScreenPrefs.KEY_TRAILER_DETAILS_ONLY,
                            BigScreenPrefs.KEY_HINT_MODE, BigScreenPrefs.KEY_CARD_SCALE,
                            BigScreenPrefs.KEY_SNOW}) {
                        e.remove(k);
                    }
                    e.apply();
                    prefs = new BigScreenPrefs(this);
                    keys = new BigScreenKeys(prefs.keyStyle());
                    applyAllSettings();
                    if (settings != null) { settings.refreshItems(); }
                    toast("已恢复默认设置");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ================= 入场动画与音效（M3）=================

    private void setupSound() {
        sound = new BigScreenSound(this);
        sound.setEnabled(prefs.soundEnabled());
        sound.setVolume(prefs.soundVolume());
    }

    /** 按意图类型播对应音效（统一入口，菜单/详情层内的操作也能覆盖到） */
    private void playSoundFor(InputRouter.Intent intent) {
        if (sound == null) { return; }
        switch (intent) {
            case UP: case DOWN: case LEFT: case RIGHT: case PAGE_L: case PAGE_R:
                if (prefs.focusTick()) { sound.play(BigScreenSound.Sfx.FOCUS); }
                break;
            case CONFIRM:
            case FAVORITE:
                sound.play(BigScreenSound.Sfx.CONFIRM);
                break;
            case BACK:
            case MENU:
            case DETAILS:
                sound.play(BigScreenSound.Sfx.OPEN);
                break;
            default:
                break;
        }
    }

    private void setupIntro() {
        if (intro == null) {
            final View introRoot = findViewById(R.id.bsIntroRoot);
            final View logoBox = findViewById(R.id.bsIntroLogoBox);
            final View band = findViewById(R.id.bsIntroBand);
            final View contentRoot = findViewById(R.id.bsContentRoot);
            intro = new BigScreenIntro(this, introRoot, logoBox, band, contentRoot,
                    !prefs.lowEndDevice(), () -> {
                        // 入场结束：把焦点态刷新一遍（入场期间焦点可能已移动）
                        updateHints();
                        onIntroEnded();
                    });
            // M18-2：入场视频（用户要的：可替换视频开场）
            intro.setVideoUri(prefs.introVideoUri());
            // M17：入场期间不显示任何提示条（"手柄已连接"不该盖在启动动画上）
            if (banner != null) { banner.setSuppressed(true); }
            // 触摸也能跳过
            introRoot.setOnClickListener(v -> intro.skip());
        }
        if (prefs.introEnabled()) {
            intro.start();
        } else {
            // 关掉入场动画：直接把主界面亮出来
            findViewById(R.id.bsIntroRoot).setVisibility(View.GONE);
        }
    }

    /** 入场进行中：任意输入 = 跳过（并吃掉这次输入，避免误操作） */
    private boolean consumeIfIntroPlaying() {
        if (intro != null && intro.isPlaying()) {
            intro.skip();
            return true;
        }
        return false;
    }

    // ================= 数据 =================

    private void loadGames() {
        allGames.clear();
        try {
            allGames.addAll(repository.getAll());
        } catch (Throwable t) {
            toast("读取游戏库失败：" + t.getMessage());
        }

        if (prefs.rememberFilter()) {
            String saved = prefs.lastFilter();
            if (saved != null && !saved.isEmpty()) { filter = saved; }
        }

        buildRailEntries();
        buildShelves();
        setRailZone(false);
        updateHeader();
    }

    private void buildRailEntries() {
        List<BigScreenRailView.Entry> entries = new ArrayList<>();
        entries.add(new BigScreenRailView.Entry(F_ALL, "全部游戏", R.drawable.bs_ic_all));
        entries.add(new BigScreenRailView.Entry(F_FAV, "收藏", R.drawable.bs_ic_star));
        entries.add(new BigScreenRailView.Entry(F_RECENT, "最近游玩", R.drawable.bs_ic_clock));
        entries.add(new BigScreenRailView.Entry(F_PLAYING, "游玩中", R.drawable.bs_ic_play));
        entries.add(new BigScreenRailView.Entry(F_DONE, "已完成", R.drawable.bs_ic_check));
        entries.add(new BigScreenRailView.Entry(F_TODO, "未游玩", R.drawable.bs_ic_circle));

        // 引擎分组（M8 起**不再放进侧栏**）：用户反馈"什么引擎不重要"，侧栏只保留状态/收藏类
        // 过滤逻辑 filterGames 仍然支持 ENGINE:xxx（以后要加回只需在这里 add 一行）

        for (BigScreenRailView.Entry e : entries) {
            e.count = filterGames(e.id).size();
            e.active = e.id.equals(filter);
        }
        rail.setEntries(entries);
    }

    private String engineLabel(Game game) {
        if (game == null || game.engine == null) { return "其他"; }
        switch (game.engine) {
            case KIRIKIRI: return "KIRIKIRI";
            case ONS:      return "ONS";
            case TYRANO:   return "TYRANO";
            case ARTEMIS:  return "ARTEMIS";
            case WINLATOR: return "WINLATOR";
            case GAMEHUB:  return "GAMEHUB";
            case PSP:      return "PSP";
            case ANDROID:  return "Android";
            default:       return "其他";
        }
    }

    /** 按筛选 id 过滤（隐藏的游戏一律不出现） */
    private List<Game> filterGames(String id) {
        List<Game> out = new ArrayList<>();
        if (id == null) { id = F_ALL; }
        for (Game g : allGames) {
            if (g == null || g.hidden) { continue; }
            if (F_ALL.equals(id)) { out.add(g); continue; }
            switch (id) {
                case F_FAV:     if (g.favorite) { out.add(g); } break;
                case F_RECENT:  if (g.totalPlayTime > 0) { out.add(g); } break;
                case F_PLAYING: if ("playing".equals(g.playStatus)) { out.add(g); } break;
                case F_DONE:    if ("completed".equals(g.playStatus)) { out.add(g); } break;
                case F_TODO:    if ("unplayed".equals(g.playStatus)) { out.add(g); } break;
                default:
                    if (id.startsWith(ENGINE_PREFIX) && g.engine != null
                            && id.substring(ENGINE_PREFIX.length()).equals(g.engine.name())) {
                        out.add(g);
                    }
                    break;
            }
        }
        return out;
    }

    private String filterLabel(String id) {
        if (id == null) { return "全部游戏"; }
        switch (id) {
            case F_ALL:     return "全部游戏";
            case F_FAV:     return "收藏";
            case F_RECENT:  return "最近游玩";
            case F_PLAYING: return "游玩中";
            case F_DONE:    return "已完成";
            case F_TODO:    return "未游玩";
            default:
                return id.startsWith(ENGINE_PREFIX) ? id.substring(ENGINE_PREFIX.length()) : "游戏";
        }
    }

    // ================= shelf 构建 =================

    private void buildShelves() {
        shelfContainer.removeAllViews();
        shelves.clear();

        final int[] accents = {
                ContextCompat.getColor(this, R.color.bs_focus),
                ContextCompat.getColor(this, R.color.bs_primary),
                ContextCompat.getColor(this, R.color.bs_success),
                ContextCompat.getColor(this, R.color.bs_warning)
        };
List<Shelf> defs = new ArrayList<>();
        List<Game> filtered = filterGames(filter);
        // ===== 单排设计（对齐 Playnite 主题的 PART_ListGameItems）=====
        // 分类由**左侧图标栏**负责切换；主区永远只有一排（按当前分类排序后的游戏）。
        // 之前按"继续游玩 / 最近加入 / 收藏 / 全部"堆好几排是错的：左侧栏就成了摆设，
        // 且多排会把本来就有限的横屏高度挤没。
        {
            List<Game> one = new ArrayList<>(filtered);
            applySort(one);
            addShelf(defs, filterLabel(filter), one);
        }


        int[] rowSizes = new int[defs.size()];
        for (int i = 0; i < defs.size(); i++) {
            Shelf s = defs.get(i);
            s.accentColor = accents[i % accents.length];
            rowSizes[i] = s.games.size();
            createShelfView(s);
            shelves.add(s);
        }

        boolean empty = defs.isEmpty();
        emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
        shelfScroll.setVisibility(empty ? View.GONE : View.VISIBLE);

        if (empty) {
            focusEngine.setRowSizes(new int[]{0});
        } else {
            focusEngine.setRowSizes(rowSizes);
            focusEngine.setMemoryKey(filter);
        }
        applyContentFocus();
        // M18-12：行是刚填进 ScrollView 的，**首帧之后滚动位置才准** ——
        // 手柄模式刚进大屏时那点轻微错位（"动一下就正常"）就出在这里：
        // 布局完成后按当前行再对齐一次即可。
        if (shelfScroll != null) {
            shelfScroll.post(() -> {
                if (focusEngine != null) { scrollVerticalToRow(focusEngine.row()); }
                // M18-13：行已布局完 → 用**实测行高**重算信息层位置（否则会压住行标题）
                applyInfoMetrics();
            });
        }
    }

    private void applySort(List<Game> list) {
        switch (sortMode) {
            case "newest":
                Collections.sort(list, (a, b) -> Long.compare(b.createdAt, a.createdAt));
                break;
            case "name":
                Collections.sort(list, (a, b) -> {
                    String x = a.title == null ? "" : a.title;
                    String y = b.title == null ? "" : b.title;
                    return x.compareToIgnoreCase(y);
                });
                break;
            default:
                Collections.sort(list, (a, b) -> Long.compare(b.lastPlayedAt, a.lastPlayedAt));
                break;
        }
    }

    private void addShelf(List<Shelf> defs, String title, List<Game> games) {
        if (games == null || games.isEmpty()) { return; }
        Shelf s = new Shelf();
        s.title = title;
        s.games = games.size() > SHELF_MAX ? new ArrayList<>(games.subList(0, SHELF_MAX)) : games;
        defs.add(s);
    }

    /** M18-6：放大后的卡片高（dp）—— 行高必须用它算，否则选"更大"时封面底部会被裁 */
    private int scaledCardHDp() {
        int base = sizes != null ? sizes.cardH : 224;
        try {
            return Math.max(88, Math.round(base * prefs.cardScale()));
        } catch (Throwable t) {
            return base;
        }
    }

    // M18-13：**实测行高（含行标题 + 2dp 行下边距）** ——
    // 公式算的"行带"比真实行矮（行标题实际高度 + 行容器下边距没算进去），
    // 于是信息层的底边会压住行标题（用户截图："全部游戏 33"被操作按钮压住）。
    // 这里直接量（子级高度 - 行容器下边距），去掉 2dp 避免把行容器的下边距重复计一遍。
    private int measuredRowBandDp() {
        if (shelfContainer == null || shelfContainer.getChildCount() == 0) { return 0; }
        View last = shelfContainer.getChildAt(shelfContainer.getChildCount() - 1);
        if (last == null || last.getHeight() <= 0) { return 0; }
        int h = last.getHeight();
        ViewGroup.LayoutParams lp = last.getLayoutParams();
        if (lp instanceof ViewGroup.MarginLayoutParams) {
            h -= ((ViewGroup.MarginLayoutParams) lp).bottomMargin;
        }
        return Math.round(h / getResources().getDisplayMetrics().density);
    }

    /** M18-6：整行高（dp）= 放大后的卡片高 + 行标题/间距那部分（其余原样保留） */
    private int scaledRowHDp() {
        int baseRow = sizes != null ? sizes.rowH : 178;
        int baseCard = sizes != null ? sizes.cardH : 144;
        return scaledCardHDp() + Math.max(0, baseRow - baseCard);
    }

    private void createShelfView(Shelf shelf) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams wrapLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        // M18-8：卡片行贴底 —— 行下方空白收到 2dp（用户在意的"底部不要空一大块"）
        wrapLp.bottomMargin = dp(2);
        wrap.setLayoutParams(wrapLp);
        wrap.setClipChildren(false);
        wrap.setClipToPadding(false);

        // 标题行：彩色点阵 + 标题 + 计数
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams headerLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        headerLp.leftMargin = dp(6);
        headerLp.bottomMargin = dp(5);
        header.setLayoutParams(headerLp);

        for (int i = 0; i < 3; i++) {
            View dot = new View(this);
            LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(5), dp(5));
            dotLp.rightMargin = dp(3);
            dot.setLayoutParams(dotLp);
            dot.setBackgroundResource(R.drawable.bs_dot);
            dot.getBackground().setTint(shelf.accentColor);
            header.addView(dot);
        }

        TextView title = new TextView(this);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleLp.leftMargin = dp(6);
        title.setLayoutParams(titleLp);
        title.setText(shelf.title);
        title.setTextSize(16f);
        title.setTextColor(ContextCompat.getColor(this, R.color.bs_text));
        title.getPaint().setFakeBoldText(true);
        header.addView(title);

        TextView count = new TextView(this);
        LinearLayout.LayoutParams countLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        countLp.leftMargin = dp(8);
        count.setLayoutParams(countLp);
        count.setText(String.valueOf(shelf.games.size()));
        count.setTextSize(12f);
        count.setTextColor(ContextCompat.getColor(this, R.color.bs_text_muted));
        header.addView(count);
        wrap.addView(header);

        RecyclerView rv = new RecyclerView(this);
        rv.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        rv.setClipChildren(false);
        rv.setClipToPadding(false);
        // 行高按屏幕算（原来写死 dp(224+18)，横屏手机上直接把卡片裁掉了）
        // M18-6：**行高必须跟着 cardScale 一起放大** ——
        // 之前行高固定 cardH+18，而卡片又被 setCardScale 放大，选"更大(1.28)"时
        // 封面底部就超出这一行、被 RecyclerView 裁掉（用户截图里就是这个问题）。
        final int rowCardH = scaledCardHDp();
        final int overflowPad = sizes != null
                ? Math.max(16, Math.min(48, Math.round(sizes.cardW * 0.35f))) : 48;
        // M18-8：下内边距 8→4：行下方的空白少一点；留出的 6dp 仍然够焦点放大（1.045 倍）用
        rv.setPadding(0, dp(8), dp(overflowPad), dp(4));
        rv.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(rowCardH + 18)));

        BigScreenShelfAdapter adapter = new BigScreenShelfAdapter();
        adapter.setListener(this);
        adapter.setRichEffects(!prefs.lowEndDevice());
        adapter.setCardScale(prefs.cardScale());
        adapter.setShowTitles(prefs.showTitles());
        adapter.setKeys(keys);
        adapter.setNsfwBlurEnabled(prefs.nsfwBlur());
        adapter.setGapPx(dp(sizes != null ? Math.max(sizes.gap, 9) : 12));
        if (sizes != null) {
            adapter.setCardSize(dp(sizes.cardW), dp(sizes.cardH));
        }
        adapter.submit(shelf.games);
        rv.setAdapter(adapter);

        shelf.adapter = adapter;
        shelf.rv = rv;
        wrap.addView(rv);
        shelfContainer.addView(wrap);
    }

    // ================= 焦点 =================

    @Override
    public void onFocusChanged(int index, int row, int col) {
        if (railZone || detailsLayer.isVisible() || panel.isVisible()) { return; }
        applyContentFocus();
    }

    @Override
    public void onBoundary(int direction) {
        if (railZone || detailsLayer.isVisible() || panel.isVisible()) { return; }
        if (direction == FocusEngine.DIR_LEFT && focusEngine.col() == 0) {
            setRailZone(true);
        }
    }

    private void applyContentFocus() {
        int row = focusEngine.row();
        int col = focusEngine.col();
        for (int i = 0; i < shelves.size(); i++) {
            Shelf s = shelves.get(i);
            if (s.adapter == null) { continue; }
            s.adapter.setFocusedPosition(i == row ? col : -1);
            // M14：原来用 smoothScrollToPosition —— 它每次调用都会**重新启动**一个减速滚动动画，
            // 摇杆 80ms 连发时前一个动画还没结束就被下一个打断，看起来就是"一顿一顿"。
            // 改成自己算偏移做定量平滑滚动（把焦点卡尽量摆在同一位置），连发时天然连续。
            if (i == row && s.rv != null) { centerOn(s.rv, Math.max(0, col)); }
        }
        scrollVerticalToRow(row);
        Game g = focusedGame();
        updateInfoBar(g);
        if (g != null) { setBackground(bgUriOf(g), g.nsfw && prefs.nsfwBlur()); }
        // PV：焦点停够时间才播，切换卡片会重置
        if (!detailsLayer.isVisible()) { syncTrailer(g); }
    }

    /**
     * 把指定位置的卡片平滑移到"锚点列"（M14）。
     *
     * <p>只在需要时用 {@code smoothScrollBy} 做**定量**滚动 —— 相比
     * {@code smoothScrollToPosition}（每次都重启一个不定长动画），连发时不会互相打断。
     * 目标卡还没上屏（离屏太远）时退化为 {@code scrollToPosition} 直接跳过去再微调。
     */
    private void centerOn(RecyclerView rv, int position) {
        RecyclerView.LayoutManager lm = rv.getLayoutManager();
        if (lm == null) { return; }
        View child = lm.findViewByPosition(position);
        if (child == null) {
            rv.scrollToPosition(position);
            return;
        }
        // 锚点：距左边缘 1/4 屏宽处（Steam / Playnite 的观感），焦点卡左沿对齐到它
        int anchor = Math.round(rv.getWidth() * 0.22f);
        int dx = child.getLeft() - anchor;
        if (Math.abs(dx) < dp(2)) { return; }
        rv.smoothScrollBy(dx, 0, null, 220);
    }

    private Game focusedGame() {
        int row = focusEngine.row();
        int col = focusEngine.col();
        if (row < 0 || row >= shelves.size()) { return null; }
        Shelf s = shelves.get(row);
        if (col < 0 || col >= s.games.size()) { return null; }
        return s.games.get(col);
    }

    private List<Game> currentShelfGames() {
        int row = focusEngine.row();
        if (row < 0 || row >= shelves.size()) { return new ArrayList<>(); }
        return shelves.get(row).games;
    }

    private void scrollVerticalToRow(int row) {
        if (row < 0 || row >= shelves.size()) { return; }
        Shelf s = shelves.get(row);
        if (s.rv == null) { return; }
        shelfScroll.smoothScrollTo(0, Math.max(0, s.rv.getTop() - dp(6)));
    }

    private void setRailZone(boolean on) {
        railZone = on;
        if (on) { setInfoZone(false); }   // M14：两个区互斥
        rail.setZoneFocused(on);
        focusEngine.setZone(on ? FocusEngine.ZONE_RAIL : FocusEngine.ZONE_CONTENT);

        if (on) {
            if (rail.focusedIndex() < 0) { rail.setFocusedIndex(0); }
            rail.moveFocus(0);
        } else {
            applyContentFocus();
        }
        updateHints();
    }

    // ================= 侧栏回调 =================

    @Override
    public void onEntryFocused(BigScreenRailView.Entry entry) {
        if (entry == null) { return; }
        if (railZone) {
            infoTitleView.setText(entry.label);
            infoMetaView.setText("该分类共 " + entry.count + " 款游戏");
        }
        // 焦点移动即生效筛选（Steam 式）；只有真变了才重建
        if (!entry.id.equals(filter)) {
            filter = entry.id;
            if (prefs.rememberFilter()) { prefs.setLastFilter(filter); }
            for (BigScreenRailView.Entry e : rail.entries()) { e.active = e.id.equals(filter); }
            rail.refreshStates();
            buildShelves();
        }
    }

    @Override
    public void onEntrySelected(BigScreenRailView.Entry entry) {
        setRailZone(false);
    }

    // ================= 卡片回调 =================
    @Override
    public void onCardFocused(int position, Game game) {
        // 触摸点卡片：必须把焦点引擎也移过去 —— 否则信息层/背景/提示都还停在旧卡上，
        // 表现就是"点了别的卡片只是亮了，游戏名不变，而且老卡亮着不退"。
        if (game == null || shelves.isEmpty()) { return; }
        for (int r = 0; r < shelves.size(); r++) {
            Shelf s = shelves.get(r);
            if (s.games == null) { continue; }
            int idx = s.games.indexOf(game);
            if (idx >= 0) {
                focusEngine.setPosition(r, idx);
                break;
            }
        }
        applyContentFocus();
        updateHints();
    }

    @Override
    public void onCardConfirmed(Game game) { launch(game); }

    /** 长按卡片（触摸用户）= 打开详情层 */
    @Override
    public void onCardDetails(Game game) { openDetails(); }

    // ================= 信息层 =================

    private void updateInfoBar(Game game) {
        infoActions.removeAllViews();
        if (infoChips != null) { infoChips.removeAllViews(); }
        if (game == null) {
            infoTitleView.setText("");
            infoMetaView.setText("");
            return;
        }
        infoTitleView.setText(TextUtils.isEmpty(game.title) ? "未命名" : game.title);
        applyInfoLogo(game);
        // 信息浮层入场动效（M9）：换游戏时轻微上浮淡入
        // M14-1：**必须先看让位状态** —— 侧栏展开时保持隐藏，否则"往下按一个分类标题又露出来"
        if (infoBar != null && game.id != lastInfoGameId) {
            lastInfoGameId = game.id;
            if (infoBarSuppressed()) {
                infoBar.animate().cancel();
                infoBar.setAlpha(0f);
                infoBar.setTranslationY(0f);
            } else {
                infoBar.animate().cancel();
                infoBar.setAlpha(0.35f);
                infoBar.setTranslationY(dp(8));
                infoBar.animate().alpha(1f).translationY(0f).setDuration(180L)
                        .setInterpolator(new DecelerateInterpolator()).start();
            }
        } else if (infoBar != null && infoBarSuppressed()) {
            // 同一款游戏但侧栏展开了（例如只是移动焦点）→ 也要保证隐藏
            infoBar.animate().cancel();
            infoBar.setAlpha(0f);
        }
        // 先用本地信息渲染，元数据（开发商/年份/标签）异步补齐
        renderInfoMeta(game, null);

        addInfoAction("▶ 启动", true, () -> launch(game));
        addInfoAction(keys.third() + (game.favorite ? " 已收藏" : " 收藏"), false, () -> toggleFavorite(game));
        // M14-1：「详情」= Ⓨ（fourth，与 onIntent 的 DETAILS 一致）；
        // 「更多」**不标按键** —— 它没有独立按键，靠 ↑ 进入按钮排后 ←→ 选择。
        addInfoAction(keys.fourth() + " 详情", false, this::openDetails);
        addInfoAction("更多", false, () -> openGameMenu(game));

        // 元数据：切卡才请求（BigScreenMeta 命中缓存时同步回调，很快）
        if (metaLoader != null && game.id != lastInfoMetaGameId) {
            lastInfoMetaGameId = game.id;
            final long gid = game.id;
            metaLoader.load(gid, (id, data) -> {
                Game cur = focusedGame();
                if (cur == null || cur.id != gid || data == null) { return; }
                renderInfoMeta(cur, data);
            });
        }
    }

    /**
     * 信息浮层的副行 + 分类 chips（对齐图3：开发商 · 年份 / 分类标签）。
     */
    private void renderInfoMeta(Game game, BigScreenMeta.Data data) {
        List<String> parts = new ArrayList<>();
        if (data != null && !TextUtils.isEmpty(data.developer)) { parts.add(data.developer); }
        if (data != null && !TextUtils.isEmpty(data.released)) { parts.add(shortDate(data.released)); }
        // 引擎不再放进主信息行（用户反馈"什么引擎不重要"），只在没有厂商/年份时兜底
        if (parts.isEmpty()) { parts.add(engineLabel(game)); }
        parts.add(formatHours(game.totalPlayTime));
        parts.add(statusLabel(game.playStatus));
        if (game.favorite) { parts.add("★ 已收藏"); }
        if (!TextUtils.isEmpty(game.trailerPath)) {
            parts.add(trailerPlayer != null && trailerPlayer.isPlaying() ? "🎬 PV播放中" : "🎬 有PV");
        }
        infoMetaView.setText(join("　·　", parts));

        if (infoChips == null) { return; }
        infoChips.removeAllViews();
        // 标签：元数据优先，退回本地 tags 字段；最多 3 个（M8，改用统一规范化）
        List<String> tags = BigScreenMeta.normalizeTags(
                data != null ? data.tags : null, game.tags, 3);
        for (String t : tags) { addInfoChip(t); }
        if (game.nsfw) { addInfoChip("R18"); }
    }

    /** 分类标签 chip */
    private void addInfoChip(String text) {
        TextView chip = new TextView(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(7);
        chip.setLayoutParams(lp);
        chip.setText(text);
        chip.setTextSize(sizes != null ? sizes.infoChipSp : 11f);
        int padH = dp(10);
        int padV = dp(3);
        chip.setPadding(padH, padV, padH, padV);
        chip.setBackgroundResource(R.drawable.bg_bs_chip);
        chip.setTextColor(ContextCompat.getColor(this, R.color.bs_text));
        infoChips.addView(chip);
    }

    /** "2019-04-26" → "2019"（图3 只显示年份） */
    private String shortDate(String raw) {
        if (raw == null) { return ""; }
        String s = raw.trim();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{4})").matcher(s);
        return m.find() ? m.group(1) : s;
    }

    /**
     * 信息层按钮排（启动/收藏/详情/更多）的焦点区（M14）。
     *
     * <p>之前这排按钮只能触摸点，手柄按 ↑ 完全没反应 —— 现在把它做成一个独立焦点区：
     * 卡片排 ↑ 进入、↓ 或 Ⓑ 退出、← → 切按钮、Ⓐ 执行。
     */
    private void setInfoZone(boolean on) {
        if (infoZone == on) { return; }
        infoZone = on;
        if (on) {
            infoFocusIndex = 0;
            // 让卡片排看起来"失焦"，避免两处同时高亮造成混乱
            for (Shelf s : shelves) {
                if (s.adapter != null) { s.adapter.setFocusedPosition(-1); }
            }
        } else {
            applyContentFocus();
        }
        applyInfoZoneVisual();
        updateHints();
    }

    /**
     * 信息浮层可见性的**唯一入口**（M14-1）。
     *
     * <p>之前侧栏展开只在回调里把 infoBar 淡出，但 {@code updateInfoBar()} 里那句
     * "换游戏淡入"又会把 alpha 拉回 1 —— 于是侧栏里往下按一个分类，游戏名又露出来
     * （因为切分类会触发 buildShelves → applyContentFocus → updateInfoBar）。
     * 现在所有改 infoBar 透明度的地方都必须经过这里，并且先判断 {@link #railExpandedUi}。
     */
    private void applyInfoBarAlpha(float targetAlpha, float shiftX, long durationMs) {
        if (infoBar == null) { return; }
        infoBar.animate().cancel();
        infoBar.setTranslationX(shiftX);
        infoBar.animate()
                .alpha(targetAlpha)
                .setDuration(durationMs)
                .setInterpolator(new DecelerateInterpolator())
                .start();
    }

    /** 侧栏展开状态下，信息浮层应当保持隐藏（true = 现在该隐藏） */
    private boolean infoBarSuppressed() {
        return railExpandedUi;
    }

    /** 在按钮排里左右移动 */
    private void moveInfoFocus(int delta) {
        if (infoActions == null) { return; }
        int count = infoActions.getChildCount();
        if (count <= 0) { return; }
        infoFocusIndex = (infoFocusIndex + delta + count) % count;
        BigScreenSound.tick();
        applyInfoZoneVisual();
    }

    /** 执行当前聚焦的按钮 */
    private void runInfoAction() {
        if (infoActions == null) { return; }
        if (infoFocusIndex < 0 || infoFocusIndex >= infoActions.getChildCount()) { return; }
        infoActions.getChildAt(infoFocusIndex).performClick();
    }

    /** 按钮排焦点视觉：聚焦项加描边 + 轻微放大 */
    private void applyInfoZoneVisual() {
        if (infoActions == null) { return; }
        for (int i = 0; i < infoActions.getChildCount(); i++) {
            View v = infoActions.getChildAt(i);
            boolean focused = infoZone && i == infoFocusIndex;
            v.animate().cancel();
            v.animate().scaleX(focused ? 1.08f : 1f).scaleY(focused ? 1.08f : 1f)
                    .setDuration(140L).setInterpolator(new DecelerateInterpolator()).start();
            v.setZ(focused ? 8f : 0f);
            v.setAlpha(infoZone && !focused ? 0.65f : 1f);
        }
    }

    private void addInfoAction(String label, boolean primary, Runnable action) {
        TextView btn = new TextView(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(sizes != null ? sizes.actionBtnH : 38));
        lp.leftMargin = dp(8);
        btn.setLayoutParams(lp);
        btn.setText(label);
        btn.setTextSize(sizes != null ? sizes.actionSp : 12f);
        btn.setGravity(Gravity.CENTER);
        int padH = dp(14);
        btn.setPadding(padH, 0, padH, 0);
        // 主按钮**不再单独配色**（M14-1：用户反馈"单独给启动设一个颜色不好看"）
        // 四个按钮统一玻璃底；焦点态由 infoZone 的描边前景表达
        btn.setBackgroundResource(R.drawable.bg_bs_glass);
        btn.setTextColor(ContextCompat.getColor(this,
                primary ? R.color.bs_text : R.color.bs_text_muted));
        btn.setClickable(true);
        btn.setOnClickListener(v -> { BigScreenSound.confirm(); action.run(); });
        infoActions.addView(btn);
    }

    // ================= 收藏 / 启动 =================

    private void toggleFavorite(Game game) {
        if (game == null) { return; }
        game.favorite = !game.favorite;
        try {
            repository.update(game);
        } catch (Throwable t) {
            toast("收藏失败：" + t.getMessage());
        }
        for (Shelf s : shelves) {
            if (s.adapter != null) { s.adapter.notifyDataSetChanged(); }
        }
        buildRailEntries();
        updateInfoBar(game);
        toast(game.favorite ? "已收藏 · " + game.title : "已取消收藏 · " + game.title);
    }

    /** 启动游戏：复用既有 Intent 通路（spec §6.2，零重构） */
    private void launch(Game game) {
        if (game == null) { return; }
        // 起播前先放掉PV解码器，避免和游戏抢资源
        if (trailerPlayer != null) { trailerPlayer.release(); }
        prefs.setLastGameId(game.id);
        // M13：**原地启动**，不再跳去游戏库（旧实现会让游戏库闪一下、并且销毁大屏实例，
        // 导致退出游戏后落在游戏库里，体验割裂）。无法原地启动的情况会自动回退到老路径。
        BigScreenLauncher.Result r = BigScreenLauncher.launch(this, game, repository, prefs);
        if (r.inPlace) {
            pendingLaunchSessionId = r.sessionId;
            pendingLaunchAt = r.startedAt;
        }
    }

    private void jumpToTouchMode(String reason) {
        toast(reason);
        Intent intent = new Intent(this, com.yuki.yukihub.MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
    }

    // ================= 详情层回调 =================

    private void openDetails() {
        Game g = focusedGame();
        if (g == null) { return; }
        detailsLayer.show(currentShelfGames(), focusEngine.col());
        // 详情层有独立的视频输出目标，切换过去
        syncTrailer(g);
    }

    @Override
    public void onGameChanged(Game game) {
        if (game == null) { return; }
        updateInfoBar(game);
        setBackground(bgUriOf(game), game.nsfw && prefs.nsfwBlur());
        syncTrailer(game);
    }

    @Override
    public void onRequestLaunch(Game game) { launch(game); }
    @Override
    public void onRequestFavorite(Game game) { toggleFavorite(game); }

    @Override
    public void onRequestGameMenu(Game game) { openGameMenu(game); }

    @Override
    public void onRequestWatchTrailer(Game game) { enterTrailerFullscreen(game); }

    // ================= 全屏 PV 预览（M15）=================

    /**
     * 全屏观看 PV：隐藏所有界面元素，让 PV 铺满屏幕；**任意按键 / 任意触摸**即恢复。
     *
     * <p>实现取巧但干净：PV 本来就播在背景层 {@code bgVideo}（铺满全屏），
     * 平时被侧栏/卡片/信息层/底栏盖住 —— 所以"全屏预览"= 把这些内容层淡出即可，
     * 不需要新建播放器、也不需要切换输出目标（避免重新 prepare 导致黑屏）。
     */
    private void enterTrailerFullscreen(Game game) {
        if (game == null) { return; }
        if (TextUtils.isEmpty(game.trailerPath)) { toast("这款游戏还没有绑定 PV"); return; }
        trailerFullscreen = true;
        trailerFullscreenWasDetails = detailsLayer != null && detailsLayer.isVisible();
        if (trailerFullscreenWasDetails) { detailsLayer.hide(); }
        setContentLayersVisible(false);
        if (trailerPlayer != null) {
            trailerPlayer.attach(bgVideo);
            trailerPlayer.setFitMode(prefs.pvFit());
            trailerPlayer.request(game.trailerPath, 0L);   // 立刻起播（不等延迟）
        }
        if (banner != null) { banner.show("全屏预览中 · 按任意键返回"); }
    }

    /** 退出全屏预览并恢复界面 */
    private void exitTrailerFullscreen() {
        if (!trailerFullscreen) { return; }
        trailerFullscreen = false;
        setContentLayersVisible(true);
        if (trailerFullscreenWasDetails && detailsLayer != null) {
            detailsLayer.show(currentShelfGames(), focusEngine.col());
        }
        trailerFullscreenWasDetails = false;
        // 回到常规的"焦点驱动 PV"逻辑
        syncTrailer(focusedGame());
    }

    /** 全屏预览时：把除背景视频以外的内容层全部淡出 */
    private void setContentLayersVisible(boolean visible) {
        final float alpha = visible ? 1f : 0f;
        final long dur = visible ? 220L : 150L;
        View[] layers = new View[]{topBar, rail, shelfContainer, shelfScroll, infoBar, bottomBar};
        for (View v : layers) {
            if (v == null) { continue; }
            v.animate().cancel();
            v.setClickable(visible);
            v.animate().alpha(alpha).setDuration(dur)
                    .setInterpolator(new DecelerateInterpolator()).start();
        }
    }

    // ================= 菜单 =================

    private void openGameMenu(Game game) {
        if (game == null) { return; }
        List<BigScreenPanel.Item> items = new ArrayList<>();
        items.add(new BigScreenPanel.Item("启动游戏", null, R.drawable.bs_ic_play, () -> {
            panel.hide();
            launch(game);
        }));
        items.add(new BigScreenPanel.Item(game.favorite ? "取消收藏" : "加入收藏",
                game.favorite ? "已收藏" : null, R.drawable.bs_ic_star, () -> {
            panel.hide();
            toggleFavorite(game);
        }));
        items.add(new BigScreenPanel.Item("游玩状态：" + statusLabel(game.playStatus), null,
                R.drawable.bs_ic_check, () -> {
            panel.hide();
            cycleStatus(game);
        }));
        // 自定义标题图 / 背景图（M10：Steam 式 logo + 自定义背景）
        if (TextUtils.isEmpty(game.logoPath)) {
            items.add(new BigScreenPanel.Item("设置标题图", "用图片替代游戏名",
                    R.drawable.bs_ic_star, () -> { panel.hide(); requestArtPick(game, BigScreenArt.KIND_LOGO); }));
        } else {
            items.add(new BigScreenPanel.Item("更换标题图", "已设置", R.drawable.bs_ic_star,
                    () -> { panel.hide(); requestArtPick(game, BigScreenArt.KIND_LOGO); }));
            items.add(new BigScreenPanel.Item("清除标题图", null, R.drawable.bs_ic_circle,
                    () -> { panel.hide(); clearArt(game, BigScreenArt.KIND_LOGO); }));
        }
        if (TextUtils.isEmpty(game.bgPath)) {
            items.add(new BigScreenPanel.Item("设置背景图", "替代封面做背景",
                    R.drawable.bs_ic_all, () -> { panel.hide(); requestArtPick(game, BigScreenArt.KIND_BG); }));
        } else {
            items.add(new BigScreenPanel.Item("更换背景图", "已设置", R.drawable.bs_ic_all,
                    () -> { panel.hide(); requestArtPick(game, BigScreenArt.KIND_BG); }));
            items.add(new BigScreenPanel.Item("清除背景图", null, R.drawable.bs_ic_circle,
                    () -> { panel.hide(); clearArt(game, BigScreenArt.KIND_BG); }));
        }

        // PV视频（M2.5 / spec §S10）
        if (TextUtils.isEmpty(game.trailerPath)) {
            items.add(new BigScreenPanel.Item("设置PV视频", "本地视频 / 链接", R.drawable.bs_ic_play,
                    () -> { panel.hide(); openTrailerSourceMenu(game); }));
        } else {
            boolean remote = TrailerPlayer.isRemote(game.trailerPath);
            String sub;
            if (remote) {
                sub = "链接：" + shortUrl(game.trailerPath);
            } else {
                long size = 0;
                try { size = new File(game.trailerPath).length(); } catch (Throwable ignored) { }
                sub = TrailerManager.formatSize(size);
            }
            items.add(new BigScreenPanel.Item(remote ? "更换PV链接" : "更换PV视频", sub,
                    R.drawable.bs_ic_play,
                    () -> { panel.hide(); openTrailerSourceMenu(game); }));
            items.add(new BigScreenPanel.Item("移除PV视频", null, 0,
                    () -> { panel.hide(); removeTrailer(game); }));
        }
        items.add(BigScreenPanel.Item.sep());
        items.add(new BigScreenPanel.Item(game.hidden ? "取消隐藏" : "在库中隐藏", null, 0, () -> {
            panel.hide();
            game.hidden = !game.hidden;
            try { repository.update(game); } catch (Throwable ignored) { }
            buildRailEntries();
            buildShelves();
            toast(game.hidden ? "已隐藏 · " + game.title : "已取消隐藏");
        }));
        items.add(new BigScreenPanel.Item("编辑信息", "触摸模式", 0,
                () -> { panel.hide(); jumpToTouchMode("编辑信息 → 跳转触摸模式"); }));
        items.add(new BigScreenPanel.Item("打开游戏目录", "触摸模式", 0,
                () -> { panel.hide(); jumpToTouchMode("打开目录 → 跳转触摸模式"); }));
        items.add(new BigScreenPanel.Item("从库中移除", "需二次确认", 0,
                () -> { panel.hide(); jumpToTouchMode("移除需在触摸模式二次确认（只删库记录，不删文件）"); }));
        panel.show("游戏操作 · " + game.title, items);
    }

    private void openMainMenu() {
        List<BigScreenPanel.Item> items = new ArrayList<>();
        items.add(new BigScreenPanel.Item("设置", null, 0, () -> {
            panel.hide();
            openSettings();
        }));
        items.add(new BigScreenPanel.Item("重新扫描游戏库", "触摸模式", 0,
                () -> { panel.hide(); jumpToTouchMode("重新扫描 → 跳转触摸模式执行"); }));
        items.add(new BigScreenPanel.Item("随机选一款", null, 0, () -> {
            panel.hide();
            pickRandom();
        }));
        items.add(new BigScreenPanel.Item("切换排序方式", sortLabel(), 0, () -> {
            panel.hide();
            sortMode = "recent".equals(sortMode) ? "newest"
                    : ("newest".equals(sortMode) ? "name" : "recent");
            buildShelves();
            toast("排序：" + sortLabel());
        }));
        items.add(new BigScreenPanel.Item("回到触摸模式", null, 0,
                () -> { panel.hide(); jumpToTouchMode("已回到触摸模式"); }));
        items.add(new BigScreenPanel.Item("快捷键说明", null, 0, () -> {
            panel.hide();
            toast("方向键 移动　Ⓐ 启动　Ⓑ 返回　Ⓧ 收藏　Ⓨ 详情　← 进筛选栏　LB/RB 翻页　☰ 菜单");
        }));
        items.add(BigScreenPanel.Item.sep());
        items.add(new BigScreenPanel.Item("退出 YukiHub", null, 0, () -> {
            panel.hide();
            finishAffinity();
        }));
        panel.show("主菜单", items);
    }

    private String sortLabel() {
        switch (sortMode) {
            case "newest": return "最新添加";
            case "name":   return "名称";
            default:       return "最近游玩";
        }
    }

    private void pickRandom() {
        if (shelves.isEmpty()) { return; }
        Shelf s = shelves.get(random.nextInt(shelves.size()));
        if (s.games.isEmpty()) { return; }
        // 用临时变量定位，避免 Random 命中空行
        int row = shelves.indexOf(s);
        int col = random.nextInt(s.games.size());
        focusEngine.setIndex(focusEngine.indexOf(row, col));
        applyContentFocus();
        scrollVerticalToRow(row);
        Game g = focusedGame();
        if (g != null) { toast("随机选中 · " + g.title); }
    }

    private void cycleStatus(Game game) {
        game.playStatus = "unplayed".equals(game.playStatus) ? "playing"
                : ("playing".equals(game.playStatus) ? "completed" : "unplayed");
        try { repository.update(game); } catch (Throwable ignored) { }
        updateInfoBar(game);
        for (Shelf s : shelves) {
            if (s.adapter != null) { s.adapter.notifyDataSetChanged(); }
        }
        toast("游玩状态：" + statusLabel(game.playStatus));
    }

    // ================= 面板回调 =================

    @Override
    public void onPanelShown() { updateHints(); }

    @Override
    public void onPanelHidden() { updateHints(); }

    // ================= 意图 =================

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        // M15：全屏 PV 预览中，**任意触摸**即恢复界面
        if (ev != null && ev.getActionMasked() == MotionEvent.ACTION_DOWN && trailerFullscreen) {
            exitTrailerFullscreen();
            return true;
        }
        // 手机用户：一旦有触摸，提示文案切成触摸版（手柄/键盘一动又切回来）
        if (ev != null && ev.getActionMasked() == MotionEvent.ACTION_DOWN && !touchMode) {
            touchMode = true;
            updateHints();
            if (gamepadView != null) { gamepadView.setText("触摸操作"); }
        }
        // M15：**点浮层外的空白处＝关闭浮层**。
        // M16 起由各浮层自己的"遮罩层 / 根容器"消费点击（panel 用 scrim，settings 用 root），
        //  这里就不再需要 hitInside 几何判断了（之前对 settings 永远成立，等于没生效）。
        return super.dispatchTouchEvent(ev);
    }

    @Override
    public void onIntent(InputRouter.Intent intent) {
        if (intent == null || intent == InputRouter.Intent.NONE) { return; }
        // M15：全屏 PV 预览中 —— 任意按键都退出（不执行原动作，避免误触）
        if (trailerFullscreen) { exitTrailerFullscreen(); resetHintFade(); return; }
        // 0) 入场动画进行中：任意输入 = 跳过
        if (consumeIfIntroPlaying()) { return; }

        // 手柄/键盘一动，就从触摸提示切回按键提示
        if (touchMode) {
            touchMode = false;
            updateHints();
            updateHeader();
        }
        playSoundFor(intent);
        resetHintFade();

        // 1) 菜单优先
        if (panel.isVisible()) { panel.handleIntent(intent); return; }
        // 2) 设置面板
        if (settings != null && settings.isVisible()) { settings.handleIntent(intent); return; }
        // 3) 详情层
        if (detailsLayer.isVisible()) {
            detailsLayer.handleIntent(intent);
            // 关闭 / 切换后会改变视频目标与当前游戏，延迟一次统一同步
            uiHandler.postDelayed(() -> syncTrailer(null), 240L);
            return;
        }

        // 3) 主界面
        switch (intent) {
            case UP:
                if (railZone) { rail.moveFocus(-1); }
                // M14：从卡片排按 ↑ 进入"启动/收藏/详情/更多"按钮排（之前上方向键完全没用）
                else if (!infoZone && infoActions != null && infoActions.getChildCount() > 0) {
                    setInfoZone(true);
                } else { focusEngine.move(FocusEngine.DIR_UP); }
                break;
            case DOWN:
                if (railZone) { rail.moveFocus(1); }
                // 在按钮排里按 ↓ 回到卡片排
                else if (infoZone) { setInfoZone(false); }
                else { focusEngine.move(FocusEngine.DIR_DOWN); }
                break;
            case LEFT:
                // 按钮排里左右移动就是切按钮
                if (infoZone) { moveInfoFocus(-1); }
                else if (!railZone) {
                    if (focusEngine.col() == 0) { setRailZone(true); }
                    else { focusEngine.move(FocusEngine.DIR_LEFT); }
                }
                break;
            case RIGHT:
                if (infoZone) { moveInfoFocus(1); }
                else if (railZone) { setRailZone(false); }
                else { focusEngine.move(FocusEngine.DIR_RIGHT); }
                break;
            case CONFIRM:
                if (railZone) { setRailZone(false); }
                else if (infoZone) { runInfoAction(); }
                else { launch(focusedGame()); }
                break;
            case BACK:
                if (railZone) { setRailZone(false); }
                else if (infoZone) { setInfoZone(false); }
                else { finish(); }
                break;
            case PAGE_L:
                // M12：单排设计下 focusEngine.page() 已经没有"上一行"了（rowCount==1），
                // LB/R1 改为**切换分类**（Steam / Playnite 的惯例），并给一句提示
                rail.moveFocus(-1);
                announceCategory();
                break;
            case PAGE_R:
                rail.moveFocus(1);
                announceCategory();
                break;
            case FAVORITE:
                if (!railZone) { toggleFavorite(focusedGame()); }
                break;
            case DETAILS:
                if (railZone) {
                    boolean pinned = !rail.isPinned();
                    rail.setPinned(pinned);
                    prefs.setRailExpanded(pinned);
                    toast(pinned ? "侧栏已钉住展开" : "侧栏已取消钉住");
                } else {
                    openDetails();
                }
                break;
            case MENU:
                // M14-1：☰(START) **恢复打开大屏主菜单**（里面有"设置"）——
                // 上一轮我把它改绑游戏操作菜单，等于把设置入口弄丢了。
                // 「更多」（游戏操作菜单）改走内容区 ↑ 进入操作按钮排后选择（见 infoZone）。
                openMainMenu();
                break;
            default:
                break;
        }
    }

    @Override
    public void onGamepadStateChanged(boolean connected) {
        // M12：插拔后立刻刷新顶栏 + 底栏提示
        updateHeader();
        updateHints();
        // M18-2：提示里带**手柄图标**（用户要的是消息里的图标，不是顶栏常驻图标）
        final String notice = connected ? "手柄已连接" : "手柄已断开";
        if (intro != null && intro.isPlaying()) {
            pendingIntroNotice = notice;   // M17：入场动画期间先攒着，动画播完再提示
        } else {
            showGamepadNotice(notice);
        }
        if (connected) { BigScreenSound.confirm(); } else { BigScreenSound.open(); }
    }

    /** LB/RB 切分类后的提示（M12）：告诉用户"现在在哪个分类" */
    private void announceCategory() {
        if (banner == null) { return; }
        BigScreenRailView.Entry e = rail.focusedEntry();
        if (e == null) { return; }
        banner.show(e.label + " · " + e.count + " 款");
    }

    // ================= 输入入口 =================

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (inputRouter != null && inputRouter.handleKeyEvent(event)) { return true; }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        if (inputRouter != null && inputRouter.handleGenericMotion(event)) { return true; }
        return super.onGenericMotionEvent(event);
    }

    @Override
    public void onBackPressed() {
        if (panel.isVisible()) { panel.hide(); return; }
        if (settings != null && settings.isVisible()) { settings.hide(); return; }
        if (detailsLayer.isVisible()) { detailsLayer.hide(); return; }
        if (railZone) { setRailZone(false); return; }
        super.onBackPressed();
    }

    // ================= 手柄插拔 =================

    @Override
    public void onInputDeviceAdded(int deviceId) {
        if (inputRouter != null) { inputRouter.onDeviceAdded(deviceId); }
    }

    @Override
    public void onInputDeviceRemoved(int deviceId) {
        if (inputRouter != null) { inputRouter.onDeviceRemoved(deviceId); }
    }

    @Override
    public void onInputDeviceChanged(int deviceId) {
        if (inputRouter != null) { inputRouter.refreshGamepadState(); }
    }

    // ================= 顶栏 / 背景 / 提示 =================

    private final Runnable clockTask = new Runnable() {
        @Override public void run() {
            tickClock();
            uiHandler.postDelayed(this, 20000);
        }
    };

    /** 无操作 4s 后按键提示淡出，避免长时间占用画面（spec §S2-5） */
    private final Runnable hintFadeTask = new Runnable() {
        @Override public void run() {
            hintView.animate().alpha(0.28f).setDuration(400L).start();
        }
    };

    private void resetHintFade() {
        if (hintView == null) { return; }
        String mode = prefs.hintMode();
        if ("off".equals(mode)) {
            hintView.setVisibility(View.GONE);
            return;
        }
        hintView.setVisibility(View.VISIBLE);
        hintView.animate().alpha(1f).setDuration(140L).start();
        uiHandler.removeCallbacks(hintFadeTask);
        if ("auto".equals(mode)) {
            uiHandler.postDelayed(hintFadeTask, HINT_FADE_DELAY_MS);
        }
    }

    /** 提示条模式的即时落地（M4-1） */
    private void updateHintVisibility() {
        if (hintView == null) { return; }
        if ("off".equals(prefs.hintMode())) {
            uiHandler.removeCallbacks(hintFadeTask);
            hintView.setVisibility(View.GONE);
        } else {
            hintView.setVisibility(View.VISIBLE);
            resetHintFade();
        }
    }

    private void tickClock() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        clockView.setText(String.format(Locale.getDefault(), "%02d:%02d",
                c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE)));
    }

    private void updateBattery() {
        try {
            Intent intent = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (intent == null) { return; }
            int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            if (level < 0 || scale <= 0) { return; }

            final int pct = Math.round(level * 100f / scale);
            final int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS,
                    BatteryManager.BATTERY_STATUS_UNKNOWN);
            final boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                    || status == BatteryManager.BATTERY_STATUS_FULL;

            if (batteryView == null) { return; }
            // M14-3：之前只有裸 "86%"，用户看不出这是手机电量 —— 加"电池图标 + 百分比 + 按电量染色"
            // （图标在布局里用 drawableStart 指定）
            batteryView.setText(charging ? pct + "%+" : pct + "%");
            batteryView.setContentDescription("手机电量 " + pct + "%" + (charging ? "，充电中" : ""));

            int color;
            if (charging) {
                color = 0xFF7BE39A;              // 充电：绿
            } else if (pct <= 15) {
                color = 0xFFFF6B6B;              // 低电量：红
            } else if (pct <= 35) {
                color = 0xFFFFC15E;              // 偏低：黄
            } else {
                color = 0xFFD5DCF0;              // 正常：浅白（与顶栏文字一致）
            }
            batteryView.setTextColor(color);
            android.graphics.drawable.Drawable[] ds = batteryView.getCompoundDrawablesRelative();
            if (ds != null && ds.length > 0 && ds[0] != null) { ds[0].setTint(color); }
        } catch (Throwable ignored) { }
    }

    private void updateHeader() {
        boolean connected = inputRouter != null && inputRouter.isGamepadConnected();
        if (!connected && touchMode) {
            // 手机用户没有手柄：显示"触摸操作"比"手柄未连接"有用得多
            gamepadView.setText("触摸操作");
            gamepadView.setTextColor(ContextCompat.getColor(this, R.color.bs_text_muted));
        } else {
            gamepadView.setText(connected ? "手柄已连接" : "手柄未连接");
            gamepadView.setTextColor(ContextCompat.getColor(this,
                    connected ? R.color.bs_success : R.color.bs_text_muted));
        }
        netView.setText("WLAN");
    }

    private void updateHints() {
        if (hintView == null) { return; }
        // 底栏只留紧凑一行，而且**只在识别到手柄时才显示按键提示**（触摸用户看着那行没用）
        boolean connected = inputRouter != null && inputRouter.isGamepadConnected();
        if (bottomBar != null) {
            // 没手柄时底栏高度归零：卡片排直接贴到屏幕最下沿，不留空行
            setViewHeight(bottomBar, connected ? (sizes != null ? sizes.bottomBarH : 18) : 0);
            // M18-14：**底栏高度会随手柄连接状态变化（触摸模式=0）**，而信息层的锚点要按它算 ——
            // 之前锚点永远扣掉 bottomBarH，触摸模式就白留了一整条底栏的空隙（用户：空隙有点大）。
            // 这里在高度变化后立刻重算信息层，锚点按真实高度走，两边都不会多留空隙。
            if (sizes != null) { applyInfoMetrics(); }
        }
        if (!connected) {
            hintView.setText("");
            hintView.setAlpha(0f);
            // M16：注意这里**先**同步触摸 UI —— 纯触摸用户走的就是这条提前 return 的分支，
            // 关闭按钮正是给他们看的（放在后面就永远不会执行）。
            applyTouchUi();
            return;
        }
        applyTouchUi();
        hintView.setAlpha(1f);
        if (panel.isVisible()) {
            hintView.setText("↑↓ 选择　" + keys.confirm() + " 确认　" + keys.back() + " 关闭");
        } else if (settings != null && settings.isVisible()) {
            hintView.setText("← → 分区　↑↓ 条目　" + keys.confirm() + " 修改　" + keys.back() + " 关闭");
        } else if (detailsLayer.isVisible()) {
            hintView.setText("← → 切换　" + keys.confirm() + " 启动　" + keys.third() + " 收藏　"
                    + keys.fourth() + " 菜单　" + keys.back() + " 关闭");
        } else if (railZone) {
            hintView.setText("↑↓ 选分类　" + keys.confirm() + "/→ 回游戏　" + keys.fourth() + " 展开　"
                    + keys.lb() + "/" + keys.rb() + " 切分类　" + keys.menu() + " 主菜单");
        } else if (infoZone) {
            hintView.setText("← → 选择　" + keys.confirm() + " 执行　↓/" + keys.back() + " 回到游戏");
        } else {
            // M14-1：文案与实际绑定一一对齐 —— LB/RB 才是切分类；☰ 是主菜单；「更多」没独立按键
            hintView.setText("方向键 移动　↑ 操作按钮　" + keys.confirm() + " 启动　"
                    + keys.third() + " 收藏　" + keys.fourth() + " 详情　"
                    + keys.lb() + "/" + keys.rb() + " 切分类　" + keys.menu() + " 主菜单");
        }
    }

    private static String coverUriOf(Game g) {
        if (g == null) { return null; }
        return !TextUtils.isEmpty(g.coverPersistUri) ? g.coverPersistUri : g.coverUri;
    }

    /** 背景层：交叉淡入 600ms + Ken Burns 缓速缩放（低性能档跳过模糊） */
    private void setBackground(String uriStr, boolean forceBlur) {
        if (TextUtils.isEmpty(uriStr) || uriStr.equals(bgUri)) { return; }
        bgUri = uriStr;

        final ImageView incoming = "A".equals(bgFront) ? bgB : bgA;
        final ImageView outgoing = "A".equals(bgFront) ? bgA : bgB;

        try {
            incoming.setImageURI(Uri.parse(uriStr));
        } catch (Throwable ignored) {
            return;
        }
        incoming.setAlpha(0f);
        // 背景=当前焦点游戏封面，**保持清晰**（用户要求：和图2一样清晰，只在标题处有阴影）
        incoming.animate().alpha(0.95f).setDuration(600L).start();
        outgoing.animate().alpha(0f).setDuration(600L).start();
        bgFront = "A".equals(bgFront) ? "B" : "A";
        startKenBurns(incoming);
        // 只有 NSFW（需要遮挡）才走模糊；普通情况保持原图清晰
        if (!forceBlur) { return; }

        final String requested = uriStr;
        final Uri uri = Uri.parse(uriStr);
        bgExecutor.execute(() -> {
            Bitmap blurred = null;
            try {
                blurred = BlurUtils.blurUri(BigScreenActivity.this, uri, 22f);
            } catch (Throwable ignored) { }
            if (blurred == null) { return; }
            final Bitmap result = blurred;
            uiHandler.post(() -> {
                if (requested.equals(bgUri)) {
                    incoming.setImageBitmap(result);
                    incoming.setAlpha(0.92f);
                }
            });
        });
    }

    /** 背景 Ken Burns：1.04 → 1.13 缓慢往复（单程 20s） */
    private void startKenBurns(View target) {
        if (kenBurns != null) { kenBurns.cancel(); }
        target.setScaleX(1.04f);
        target.setScaleY(1.04f);
        kenBurns = ValueAnimator.ofFloat(1.04f, 1.13f);
        kenBurns.setDuration(20000L);
        kenBurns.setRepeatCount(ValueAnimator.INFINITE);
        kenBurns.setRepeatMode(ValueAnimator.REVERSE);
        kenBurns.addUpdateListener(a -> {
            float v = (float) a.getAnimatedValue();
            target.setScaleX(v);
            target.setScaleY(v);
        });
        kenBurns.start();
    }

    // ================= 生命周期收尾 =================

    @Override
    protected void onResume() {
        super.onResume();
        if (inputManager != null) { inputManager.registerInputDeviceListener(this, null); }
        if (inputRouter != null) { inputRouter.refreshGamepadState(); }
        updateBattery();
        // M13：刚从游戏里回来 → 结束游玩会话（本地时长统计）并刷新列表
        // （"最近游玩 / 游玩中"分类和卡片副行都依赖时长数据）
        if (pendingLaunchSessionId > 0L) {
            BigScreenLauncher.finishSession(this, repository, pendingLaunchSessionId, pendingLaunchAt);
            pendingLaunchSessionId = 0L;
            pendingLaunchAt = 0L;
            buildRailEntries();
            buildShelves();
        }
        updateHeader();
        updateHints();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (inputManager != null) {
            try { inputManager.unregisterInputDeviceListener(this); } catch (Throwable ignored) { }
        }
        if (inputRouter != null) { inputRouter.release(); }
        cancelTrailer();
        if (kenBurns != null) { kenBurns.pause(); }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        uiHandler.removeCallbacksAndMessages(null);
        if (inputRouter != null) { inputRouter.release(); }
        if (focusEngine != null && prefs != null) {
            focusEngine.rememberSelection();
            if (prefs.rememberFilter()) { prefs.setLastFilter(filter); }
        }
        if (detailsLayer != null) { detailsLayer.shutdown(); }
        if (metaLoader != null) { metaLoader.shutdown(); }
        if (trailerPlayer != null) { trailerPlayer.release(); }
        if (intro != null) { intro.cancel(); }
        if (sound != null) { sound.release(); }
        if (banner != null) { banner.cancel(); }
        if (kenBurns != null) { kenBurns.cancel(); }
        bgExecutor.shutdownNow();
    }

    // ================= 工具 =================

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

    private static String join(String sep, List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) { sb.append(sep); }
            sb.append(parts.get(i));
        }
        return sb.toString();
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /** 应用内提示条（S7）：比系统 Toast 更贴合大屏气质 */
    private void toast(String msg) {
        if (banner != null) {
            banner.show(msg);
        } else {
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
        }
    }
}