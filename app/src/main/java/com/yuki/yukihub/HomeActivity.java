package com.yuki.yukihub;

import android.content.Intent;
import android.content.ComponentName;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import android.util.Log;
import androidx.appcompat.app.AppCompatActivity;

import com.yuki.yukihub.data.GameRepository;
import com.yuki.yukihub.model.Game;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * YukiHub 启动首页。
 * 只负责首页展示与导航；完整游戏库功能继续由 MainActivity 承担。
 */
public class HomeActivity extends AppCompatActivity {

    private static final String PREFS_NAME = "yukihub_prefs";
    private static final String KEY_PROFILE_NAME = "profile_name";
    private static final String KEY_PROFILE_AVATAR = "profile_avatar";
    private static final String KEY_AUTH_ACCESS_TOKEN = "auth_access_token";
    private static final String KEY_AUTH_NICKNAME = "auth_nickname";
    private static final String KEY_AUTH_AVATAR = "auth_avatar";
    private static final String KEY_APP_ICON = "app_icon"; // new=新图标(默认) / classic=经典图标
    private static final String APP_ICON_NEW = "new";
    private static final String APP_ICON_CLASSIC = "classic";
    private static final String ALIAS_ICON_NEW = "com.yuki.yukihub.YukiIcon";
    private static final String ALIAS_ICON_CLASSIC = "com.yuki.yukihub.YukiIconClassic";

    private GameRepository repository;
    private SharedPreferences prefs;
    private com.yuki.yukihub.social.PresenceManager presenceManager;
    private boolean homeHeartbeatHeld = false;
    private LinearLayout quickGames;
    private LinearLayout heroDots;
    private ImageView homeAvatar;
    private TextView homeAvatarInitial;
    private TextView homeGreeting;
    private View homeProfileStatusDot;
    private ImageView heroCover;
    private TextView heroTitle;
    private TextView heroSubtitle;
    private TextView playTime;
    private TextView gameCount;
    private TextView completedCount;
    private TextView playingCount;
    private ImageView homeNewsBanner;
    private TextView homeNewsTitle;
    private TextView homeNewsLoading;
    private LinearLayout homeNewsDots;
    private TextView homeNewsRefresh;
    private final List<Game> carouselGames = new ArrayList<>();
    private final Handler carouselHandler = new Handler(Looper.getMainLooper());
    private int carouselIndex = 0;
    private String heroImageRequest = "";
    private String avatarImageRequest = "";
    private final Runnable carouselRunnable = new Runnable() {
        @Override public void run() {
            if (carouselGames.size() > 1) {
                carouselIndex = (carouselIndex + 1) % carouselGames.size();
                showCarouselGame(carouselIndex, true);
                carouselHandler.postDelayed(this, 5000L);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        applyImmersive();
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        syncAppIconState(); // 校验应用图标 prefs 与系统 alias 状态一致

        // 从 MainActivity 导航栏回来时强制显示首页
        boolean forceHome = getIntent().getBooleanExtra("force_home", false);

        // 启动页选择（非强制回家时生效）
        if (!forceHome) {
            String startupPage = prefs.getString("startup_page", "home");
            if ("library".equals(startupPage)) {
                startActivity(new Intent(this, MainActivity.class));
                finish();
                return;
            }
        }

        setContentView(R.layout.activity_home);
        // 聊天选图 launcher 必须在 STARTED 之前注册（供 FriendsChatDialog 借用）
        com.yuki.yukihub.social.ChatImagePicker.register(this);
        // 窗口背景铺成首页同款渐变，避免内容延伸/挖孔区域露出默认浅色背景（白边）
        try { getWindow().setBackgroundDrawableResource(R.drawable.bg_home_gradient); } catch (Throwable ignored) { }
        repository = new GameRepository(this);
        bindViews();
        bindActions();
        refreshHome();
        handleFriendsTargetIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleFriendsTargetIntent(intent);
    }

    private void handleFriendsTargetIntent(Intent intent) {
        if (intent == null) return;
        String target = intent.getStringExtra("home_target");
        if (!"friends".equals(target)) return;
        // 通知点击可能带上要直达的会话
        final String chatFriendId = intent.getStringExtra("chat_friend_id");
        final int chatGroupId = intent.getIntExtra("chat_group_id", 0);
        intent.removeExtra("home_target");
        intent.removeExtra("chat_friend_id");
        intent.removeExtra("chat_group_id");
        getWindow().getDecorView().post(() -> {
            if (isFinishing()) return;
            try {
                com.yuki.yukihub.social.FriendsChatDialog d =
                        new com.yuki.yukihub.social.FriendsChatDialog(this);
                if (chatFriendId != null && !chatFriendId.isEmpty()) {
                    d.showAndOpenFriendChat(chatFriendId);
                } else if (chatGroupId > 0) {
                    d.showAndOpenGroupChat(chatGroupId);
                } else {
                    d.show();
                }
            } catch (Throwable ignored) {}
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyImmersive();
        refreshProfileHeader();
        if (repository != null) {
            finishStalePlaySessionsIfAny();
            refreshHome();
        }
        // 启动在线心跳
        if (presenceManager == null) presenceManager = com.yuki.yukihub.social.PresenceManager.get(this);
        if (!homeHeartbeatHeld) {
            presenceManager.retainHeartbeat();
            homeHeartbeatHeld = true;
        }
        try { com.yuki.yukihub.social.PresenceService.sync(this); } catch (Throwable ignored) {}
    }

    @Override
    protected void onPause() {
        carouselHandler.removeCallbacks(carouselRunnable);
        newsCarouselHandler.removeCallbacks(newsCarouselRunnable);
        // 释放本页心跳持有；前台服务若在跑会继续保活
        if (presenceManager != null && homeHeartbeatHeld) {
            presenceManager.releaseHeartbeat();
            homeHeartbeatHeld = false;
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        carouselHandler.removeCallbacksAndMessages(null);
        newsCarouselHandler.removeCallbacksAndMessages(null);
        if (presenceManager != null && homeHeartbeatHeld) {
            presenceManager.releaseHeartbeat();
            homeHeartbeatHeld = false;
        }
        super.onDestroy();
    }

    private void bindViews() {
        quickGames = findViewById(R.id.homeQuickGames);
        heroDots = findViewById(R.id.homeHeroDots);
        homeAvatar = findViewById(R.id.homeAvatar);
        homeAvatarInitial = findViewById(R.id.homeAvatarInitial);
        homeGreeting = findViewById(R.id.homeGreeting);
        homeProfileStatusDot = findViewById(R.id.homeProfileStatusDot);
        heroCover = findViewById(R.id.homeHeroCover);
        heroTitle = findViewById(R.id.homeHeroTitle);
        heroSubtitle = findViewById(R.id.homeHeroSubtitle);
        playTime = findViewById(R.id.homePlayTime);
        gameCount = findViewById(R.id.homeGameCount);
        completedCount = findViewById(R.id.homeCompletedCount);
        playingCount = findViewById(R.id.homePlayingCount);
        homeNewsBanner = findViewById(R.id.homeNewsBanner);
        homeNewsTitle = findViewById(R.id.homeNewsTitle);
        homeNewsLoading = findViewById(R.id.homeNewsLoading);
        homeNewsDots = findViewById(R.id.homeNewsDots);
        homeNewsRefresh = findViewById(R.id.homeNewsRefresh);

        // 题图区四角圆角裁切（画框式）。遮罩用无圆角版 bg_home_news_overlay：
        // 自带圆角的遮罩会在图片裁切角留下一小瓣未遮亮的月牙，直角版罩满裁切区
        android.view.View newsMedia = findViewById(R.id.homeNewsMedia);
        newsMedia.setClipToOutline(true);
        newsMedia.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(android.view.View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(12));
            }
        });
        setupNewsSwipe(newsMedia);

        refreshProfileHeader();
    }

    private void bindActions() {
        View.OnClickListener openLibrary = v -> {
            touch(v);
            startActivity(new Intent(this, MainActivity.class));
        };
        findViewById(R.id.homeNavLibrary).setOnClickListener(openLibrary);
        findViewById(R.id.homeHeroAction).setOnClickListener(v -> {
            touch(v);
            launchCurrentCarouselGame();
        });
        setupHeroSwipe(findViewById(R.id.homeHeroCard));

        findViewById(R.id.homeProfileEntry).setOnClickListener(v -> {
            touch(v);
            showProfileDialog();
        });
        findViewById(R.id.homeNavBigScreen).setOnClickListener(v -> {
            touch(v);
            Toast.makeText(this, "大屏模式正在开发中，入口已为欧尼酱预留。", Toast.LENGTH_SHORT).show();
        });
        findViewById(R.id.homeNavChat).setOnClickListener(v -> {
            touch(v);
            new com.yuki.yukihub.social.FriendsChatDialog(this).show();
        });
        findViewById(R.id.homeNavSettings).setOnClickListener(v -> {
            touch(v);
            showSettingsDialog();
        });
        findViewById(R.id.homeNavTranslate).setOnClickListener(v -> {
            touch(v);
            startActivity(new Intent(this, com.yuki.yukihub.translate.TranslateControlActivity.class));
        });
        findViewById(R.id.homeCommunity).setOnClickListener(v -> {
            touch(v);
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://yukihub.zh.kg/community")));
            } catch (Throwable t) {
                Toast.makeText(this, "无法打开社区，请检查网络", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void showProfileDialog() {
        final String currentName = displayProfileName();
        final String localName = prefs == null ? "Yuki" : prefs.getString(KEY_PROFILE_NAME, "Yuki");
        final String currentSignature = prefs == null ? "" : prefs.getString("profile_signature", "");

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.bg_dialog);
        int pad = dp(16);
        root.setPadding(pad, dp(14), pad, dp(10));

        TextView nameLabel = new TextView(this);
        nameLabel.setText("昵称");
        nameLabel.setTextColor(0xFFFFFFFF);
        nameLabel.setTextSize(14);
        nameLabel.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(nameLabel);

        EditText nameInput = new EditText(this);
        nameInput.setText(localName);
        nameInput.setHint("输入昵称");
        nameInput.setTextColor(0xFFFFFFFF);
        nameInput.setHintTextColor(0x88FFFFFF);
        nameInput.setBackgroundResource(R.drawable.bg_input);
        nameInput.setPadding(dp(10), 0, dp(10), 0);
        root.addView(nameInput, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(42)));

        TextView uidLabel = new TextView(this);
        uidLabel.setText("UID");
        uidLabel.setTextColor(0xFF9AA4BF);
        uidLabel.setTextSize(12);
        uidLabel.setPadding(0, dp(6), 0, dp(2));
        root.addView(uidLabel);

        TextView uidValue = new TextView(this);
        String uid = prefs == null ? "" : prefs.getString("auth_uid", "");
        uidValue.setText(uid.isEmpty() ? "-" : uid);
        uidValue.setTextColor(0xFFFFFFFF);
        uidValue.setTextSize(14);
        uidValue.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(uidValue, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(36)));

        TextView signLabel = new TextView(this);
        signLabel.setText("个人签名");
        signLabel.setTextColor(0xFFFFFFFF);
        signLabel.setTextSize(14);
        signLabel.setTypeface(null, android.graphics.Typeface.BOLD);
        signLabel.setPadding(0, dp(10), 0, dp(4));
        root.addView(signLabel);

        EditText signatureInput = new EditText(this);
        signatureInput.setText(currentSignature);
        signatureInput.setHint("写点什么，比如：今天也要认真补完一部作品");
        signatureInput.setSingleLine(false);
        signatureInput.setMinLines(2);
        signatureInput.setTextColor(0xFFFFFFFF);
        signatureInput.setHintTextColor(0x88FFFFFF);
        signatureInput.setBackgroundResource(R.drawable.bg_input);
        signatureInput.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        signatureInput.setPadding(dp(10), dp(6), dp(10), dp(6));
        root.addView(signatureInput, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(62)));

        TextView hint = new TextView(this);
        hint.setText("头像和昵称也显示在首页左侧。\n完整资料显示、云同步、账号管理等请进入游戏库设置。");
        hint.setTextColor(0xAAFFFFFF);
        hint.setTextSize(10);
        hint.setPadding(0, dp(8), 0, 0);
        root.addView(hint);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        scroll.addView(root, new ScrollView.LayoutParams(ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));

        androidx.appcompat.app.AlertDialog dialog = new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("个人资料")
                .setView(scroll)
                .setPositiveButton("保存", null)
                .setNegativeButton("关闭", null)
                .show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(R.drawable.bg_dialog);
            dialog.getWindow().setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.72f), (int) (getResources().getDisplayMetrics().heightPixels * 0.72f));
        }
        styleDialogDark(dialog);
        dialog.setOnDismissListener(d -> applyImmersive());
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String name = nameInput.getText() == null ? "" : nameInput.getText().toString().trim();
            String sign = signatureInput.getText() == null ? "" : signatureInput.getText().toString().trim();
            if (name.isEmpty()) {
                Toast.makeText(this, "昵称不能为空", Toast.LENGTH_SHORT).show();
                return;
            }
            if (prefs != null) {
                prefs.edit().putString(KEY_PROFILE_NAME, name).putString("profile_signature", sign).apply();
            }
            refreshProfileHeader();
            Toast.makeText(this, "个人资料已保存", Toast.LENGTH_SHORT).show();
            dialog.dismiss();
        });
    }

    private void showSettingsDialog() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.bg_dialog);
        int pad = dp(16);
        root.setPadding(pad, dp(14), pad, dp(10));

        // 字体大小
        TextView fontTitle = new TextView(this);
        fontTitle.setText("整体字体大小");
        fontTitle.setTextColor(0xFFFFFFFF);
        fontTitle.setTextSize(14);
        fontTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(fontTitle);

        float savedFontScale = prefs == null ? 1.0f : prefs.getFloat("ui_font_scale", 1.0f);
        TextView fontInfo = new TextView(this);
        fontInfo.setText("当前：" + Math.round(savedFontScale * 100) + "%（默认 100%）");
        fontInfo.setTextColor(0xAAFFFFFF);
        fontInfo.setTextSize(11);
        fontInfo.setPadding(0, dp(4), 0, dp(6));
        root.addView(fontInfo);

       SeekBar fontSeek = new SeekBar(this);
        fontSeek.setMax(60); // 70%-130%
        fontSeek.setProgress(Math.round((savedFontScale - 0.7f) * 100f));
        root.addView(fontSeek);
        final float[] fontScaleValue = {savedFontScale};
        fontSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float scale = 0.7f + progress / 100f;
                fontScaleValue[0] = scale;
                fontInfo.setText("当前：" + Math.round(scale * 100) + "%（默认 100%）");
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });

        // 界面缩放
        TextView scaleTitle = new TextView(this);
        scaleTitle.setText("\n界面整体缩放");
        scaleTitle.setTextColor(0xFFFFFFFF);
        scaleTitle.setTextSize(14);
        scaleTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(scaleTitle);

        float savedUiScale = prefs == null ? 1.0f : prefs.getFloat("ui_scale", 1.0f);
        TextView scaleInfo = new TextView(this);
        scaleInfo.setText("当前：" + Math.round(savedUiScale * 100) + "%（默认 100%）· 平板建议 120-150%");
        scaleInfo.setTextColor(0xAAFFFFFF);
        scaleInfo.setTextSize(11);
        scaleInfo.setPadding(0, dp(4), 0, dp(6));
        root.addView(scaleInfo);

        SeekBar uiScaleSeek = new SeekBar(this);
        uiScaleSeek.setMax(60); // 70%-130%
        uiScaleSeek.setProgress(Math.round((savedUiScale - 0.7f) * 100f));
        root.addView(uiScaleSeek);
        final float[] uiScaleValue = {savedUiScale};
        uiScaleSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float scale = 0.7f + progress / 100f;
                uiScaleValue[0] = scale;
                scaleInfo.setText("当前：" + Math.round(scale * 100) + "%（默认 100%）· 平板建议 120-150%");
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });

        // 刘海/挖孔区域（主界面可选，默认开启）
        TextView cutoutTitle = new TextView(this);
        cutoutTitle.setText("\n刘海/挖孔区域");
        cutoutTitle.setTextColor(0xFFFFFFFF);
        cutoutTitle.setTextSize(14);
        cutoutTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(cutoutTitle);
        android.widget.CheckBox cutoutCheck = new android.widget.CheckBox(this);
        cutoutCheck.setText("允许绘制到刘海/挖孔区域（游戏库/首页背景铺满）");
        cutoutCheck.setTextColor(0xFFFFFFFF);
        cutoutCheck.setTextSize(13);
        cutoutCheck.setPadding(dp(4), dp(4), 0, dp(4));
        cutoutCheck.setChecked(prefs != null && prefs.getBoolean(MainActivity.KEY_MAIN_DRAW_CUTOUT, true));
        root.addView(cutoutCheck);

        // NSFW 封面模糊
        android.widget.CheckBox nsfwBlurCheck = new android.widget.CheckBox(this);
        nsfwBlurCheck.setText("🔞 NSFW 封面模糊（识别为 R18 的游戏封面自动模糊）");
        nsfwBlurCheck.setTextColor(0xFFFFFFFF);
        nsfwBlurCheck.setTextSize(13);
        nsfwBlurCheck.setPadding(dp(4), dp(4), 0, dp(4));
        nsfwBlurCheck.setChecked(prefs != null && prefs.getBoolean(MainActivity.KEY_NSFW_BLUR, true));
        root.addView(nsfwBlurCheck);

        // 启动页选择
        TextView startupTitle = new TextView(this);
        startupTitle.setText("\n启动页");
        startupTitle.setTextColor(0xFFFFFFFF);
        startupTitle.setTextSize(14);
        startupTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(startupTitle);

        String savedStartup = prefs == null ? "home" : prefs.getString("startup_page", "home");
        final String[] startupOptions = {"home", "library", "bigscreen"};
        String[] startupLabels = {"🏠 首页（默认）", "🎮 游戏库", "🖥 大屏模式（敬请期待）"};
        final int[] startupChoice = {0};
        for (int i = 0; i < startupOptions.length; i++) {
            if (startupOptions[i].equals(savedStartup)) { startupChoice[0] = i; break; }
        }

        android.widget.RadioGroup startupGroup = new android.widget.RadioGroup(this);
        startupGroup.setOrientation(android.widget.RadioGroup.VERTICAL);
        final android.widget.RadioButton[] startupRadios = new android.widget.RadioButton[startupOptions.length];
        for (int i = 0; i < startupOptions.length; i++) {
            startupRadios[i] = new android.widget.RadioButton(this);
            startupRadios[i].setText(startupLabels[i]);
            startupRadios[i].setTextColor(0xFFFFFFFF);
            startupRadios[i].setPadding(dp(4), dp(4), 0, dp(4));
            if (i == startupChoice[0]) startupRadios[i].setChecked(true);
            final int idx = i;
            startupRadios[i].setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (isChecked) {
                    for (int j = 0; j < startupRadios.length; j++) {
                        if (j != idx) startupRadios[j].setChecked(false);
                    }
                    startupChoice[0] = idx;
                }
            });
            startupGroup.addView(startupRadios[i]);
        }
        root.addView(startupGroup);

        // 应用图标选择
        TextView appIconTitle = new TextView(this);
        appIconTitle.setText("\n应用图标");
        appIconTitle.setTextColor(0xFFFFFFFF);
        appIconTitle.setTextSize(14);
        appIconTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(appIconTitle);

        LinearLayout appIconRow = new LinearLayout(this);
        appIconRow.setOrientation(LinearLayout.HORIZONTAL);
        appIconRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        appIconRow.setPadding(0, dp(8), 0, dp(4));

        ImageView appIconPreview = new ImageView(this);
        appIconPreview.setImageResource(currentIconRes());
        appIconPreview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        appIconRow.addView(appIconPreview, new LinearLayout.LayoutParams(dp(40), dp(40)));

        String savedIcon = prefs == null ? APP_ICON_NEW : prefs.getString(KEY_APP_ICON, APP_ICON_NEW);
        TextView appIconLabel = new TextView(this);
        appIconLabel.setText("当前：" + (APP_ICON_NEW.equals(savedIcon) ? "新图标" : "经典图标"));
        appIconLabel.setTextColor(0xAAFFFFFF);
        appIconLabel.setTextSize(13);
        appIconLabel.setGravity(android.view.Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        labelLp.setMargins(dp(10), 0, 0, 0);
        appIconRow.addView(appIconLabel, labelLp);

        Button appIconBtn = new Button(this);
        appIconBtn.setText("更改");
        appIconBtn.setTextColor(0xFFFFFFFF);
        appIconBtn.setTextSize(12);
        android.graphics.drawable.GradientDrawable iconBtnBg = new android.graphics.drawable.GradientDrawable();
        iconBtnBg.setColor(0x3310183A);
        iconBtnBg.setStroke(dp(1), 0x554A6A9A);
        iconBtnBg.setCornerRadius(dp(8));
        appIconBtn.setBackground(iconBtnBg);
        appIconBtn.setOnClickListener(v -> showAppIconDialog());
        appIconRow.addView(appIconBtn, new LinearLayout.LayoutParams(dp(72), dp(34)));
        root.addView(appIconRow);

        // 高级设置入口
        TextView advancedHint = new TextView(this);
        advancedHint.setText("\n扫描目录、引擎配置、背景、WebDAV 同步、账号管理等高级设置请进入游戏库设置页面。");
        advancedHint.setTextColor(0xAAFFFFFF);
        advancedHint.setTextSize(10);
        advancedHint.setLineSpacing(dp(2), 1.0f);
        advancedHint.setPadding(0, dp(12), 0, dp(8));
        root.addView(advancedHint);

        Button openFullSettings = new Button(this);
        openFullSettings.setText("打开完整设置 →");
        openFullSettings.setTextColor(0xFFFFFFFF);
        openFullSettings.setBackgroundResource(R.drawable.bg_home_glass);
        openFullSettings.setOnClickListener(v -> {
            openMainTarget("settings");
        });
        root.addView(openFullSettings, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(42)));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        scroll.addView(root, new ScrollView.LayoutParams(ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));

        androidx.appcompat.app.AlertDialog dialog = new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("设置")
                .setView(scroll)
                .setPositiveButton("保存", null)
                .setNegativeButton("关闭", null)
                .show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(R.drawable.bg_dialog);
            dialog.getWindow().setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.72f), (int) (getResources().getDisplayMetrics().heightPixels * 0.72f));
        }
        styleDialogDark(dialog);
        dialog.setOnDismissListener(d -> applyImmersive());
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (prefs != null) {
                // 大屏模式预留：选中了也不存（仍用 home），保持预留状态
                String selectedStartup = startupOptions[startupChoice[0]];
                if ("bigscreen".equals(selectedStartup)) {
                    Toast.makeText(this, "大屏模式正在开发中，敬请期待", Toast.LENGTH_SHORT).show();
                    selectedStartup = "home";
                }
                prefs.edit()
                        .putFloat("ui_font_scale", fontScaleValue[0])
                        .putFloat("ui_scale", uiScaleValue[0])
                        .putString("startup_page", selectedStartup)
                        .putBoolean(MainActivity.KEY_MAIN_DRAW_CUTOUT, cutoutCheck.isChecked())
                        .putBoolean(MainActivity.KEY_NSFW_BLUR, nsfwBlurCheck.isChecked())
                        .apply();
            }
            Toast.makeText(this, "设置已保存", Toast.LENGTH_SHORT).show();
            dialog.dismiss();
        });
    }

    // ========== 应用图标切换（activity-alias） ==========

    private int currentIconRes() {
        String id = prefs == null ? APP_ICON_NEW : prefs.getString(KEY_APP_ICON, APP_ICON_NEW);
        return APP_ICON_NEW.equals(id) ? R.mipmap.ic_launcher_new : R.mipmap.ic_launcher;
    }

    private void showAppIconDialog() {
        String current = prefs == null ? APP_ICON_NEW : prefs.getString(KEY_APP_ICON, APP_ICON_NEW);

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setBackgroundResource(R.drawable.bg_dialog);
        list.setPadding(dp(16), dp(8), dp(16), dp(8));

        androidx.appcompat.app.AlertDialog dialog = new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("选择应用图标")
                .setView(list)
                .setNegativeButton("取消", null)
                .create();

        addAppIconOption(list, "新图标", R.mipmap.ic_launcher_new, APP_ICON_NEW, current, dialog);
        addAppIconOption(list, "经典图标", R.mipmap.ic_launcher, APP_ICON_CLASSIC, current, dialog);

        dialog.show();
        // 窗口背景统一为深色，避免标题栏/按钮栏露出系统浅色
        if (dialog.getWindow() != null) {
            android.graphics.drawable.GradientDrawable winBg = new android.graphics.drawable.GradientDrawable();
            winBg.setColor(0xFF10172A);
            winBg.setCornerRadius(dp(14));
            dialog.getWindow().setBackgroundDrawable(winBg);
        }
    }

    private void addAppIconOption(LinearLayout list, String name, int iconRes, String id, String current, androidx.appcompat.app.AlertDialog dialog) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        row.setClickable(true);

        // 选中项高亮，非选中项轻微深色底
        boolean selected = id.equals(current);
        android.graphics.drawable.GradientDrawable rowBg = new android.graphics.drawable.GradientDrawable();
        rowBg.setColor(selected ? 0x334A6A9A : 0x11000000);
        if (selected) rowBg.setStroke(dp(1), 0x664A6A9A);
        rowBg.setCornerRadius(dp(8));
        row.setBackground(rowBg);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowLp.setMargins(0, dp(4), 0, dp(4));
        list.addView(row, rowLp);

        ImageView iv = new ImageView(this);
        iv.setImageResource(iconRes);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        row.addView(iv, new LinearLayout.LayoutParams(dp(48), dp(48)));

        TextView tv = new TextView(this);
        tv.setText(selected ? "✓ " + name + "（当前）" : name);
        tv.setTextColor(selected ? 0xFFFFFFFF : 0xBFFFFFFF);
        tv.setTextSize(15);
        LinearLayout.LayoutParams tvLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        tvLp.setMargins(dp(14), 0, 0, 0);
        row.addView(tv, tvLp);

        row.setOnClickListener(v -> {
            prefs.edit().putString(KEY_APP_ICON, id).apply();
            switchAppIcon(id);
            Toast.makeText(this, "图标已切换，桌面图标可能需要稍后刷新", Toast.LENGTH_SHORT).show();
            if (dialog != null) dialog.dismiss();
        });
    }

    private void switchAppIcon(String id) {
        boolean useNew = APP_ICON_NEW.equals(id);
        try {
            PackageManager pm = getPackageManager();
            pm.setComponentEnabledSetting(
                    new ComponentName(this, ALIAS_ICON_NEW),
                    useNew ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP);
            pm.setComponentEnabledSetting(
                    new ComponentName(this, ALIAS_ICON_CLASSIC),
                    useNew ? PackageManager.COMPONENT_ENABLED_STATE_DISABLED : PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP);
        } catch (Throwable t) {
            Log.w("YukiHub", "switch app icon failed", t);
            Toast.makeText(this, "图标切换失败：" + (t.getMessage() == null ? "请稍后重试" : t.getMessage()), Toast.LENGTH_LONG).show();
        }
    }

    /** 启动时校验 prefs 与系统组件状态一致，防止清除数据/重装后错乱 */
    private void syncAppIconState() {
        if (prefs == null) return;
        try {
            String saved = prefs.getString(KEY_APP_ICON, APP_ICON_NEW);
            PackageManager pm = getPackageManager();
            int st = pm.getComponentEnabledSetting(new ComponentName(this, ALIAS_ICON_NEW));
            boolean actualNew = (st == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    || st == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT);
            boolean wantNew = APP_ICON_NEW.equals(saved);
            if (actualNew != wantNew) {
                switchAppIcon(saved);
            }
        } catch (Throwable t) {
            Log.w("YukiHub", "sync app icon state failed", t);
        }
    }

    private void openMainTarget(String target) {
        Intent intent = new Intent(this, MainActivity.class);
        intent.putExtra("home_target", target);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
    }

    private void launchCurrentCarouselGame() {
        if (carouselIndex < 0 || carouselIndex >= carouselGames.size()) {
            Toast.makeText(this, "当前没有可启动的游戏", Toast.LENGTH_SHORT).show();
            return;
        }
        launchGameFromHome(carouselGames.get(carouselIndex));
    }

    private void launchGameFromHome(Game game) {
        if (game == null || game.id <= 0L) {
            Toast.makeText(this, "无法识别要启动的游戏", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(this, MainActivity.class);
        intent.putExtra("home_target", "launch_game");
        intent.putExtra("home_game_id", game.id);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
    }

    private void setupHeroSwipe(View heroCard) {
        if (heroCard == null) return;
        heroCard.setClickable(true);
        heroCard.setOnClickListener(view -> {
            touch(view);
            launchCurrentCarouselGame();
        });
        final float[] downX = {0f};
        final float[] downY = {0f};
        final boolean[] moved = {false};
        heroCard.setOnTouchListener((view, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX[0] = event.getX();
                    downY[0] = event.getY();
                    moved[0] = false;
                    carouselHandler.removeCallbacks(carouselRunnable);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float moveX = event.getX() - downX[0];
                    float moveY = event.getY() - downY[0];
                    if (Math.abs(moveX) > dp(8) || Math.abs(moveY) > dp(8)) moved[0] = true;
                    if (Math.abs(moveX) > Math.abs(moveY)) {
                        view.setTranslationX(Math.max(-dp(24), Math.min(dp(24), moveX * 0.12f)));
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    float deltaX = event.getX() - downX[0];
                    float deltaY = event.getY() - downY[0];
                    view.animate().translationX(0f).setDuration(140).start();
                    boolean horizontalSwipe = carouselGames.size() > 1
                            && Math.abs(deltaX) >= dp(42)
                            && Math.abs(deltaX) > Math.abs(deltaY) * 1.2f;
                    if (horizontalSwipe) {
                        switchCarousel(deltaX < 0 ? 1 : -1);
                        try { view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); } catch (Throwable ignored) { }
                    } else if (!moved[0] && Math.abs(deltaX) < dp(12) && Math.abs(deltaY) < dp(12)) {
                        view.performClick();
                    }
                    restartCarouselTimer();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    view.animate().translationX(0f).setDuration(140).start();
                    restartCarouselTimer();
                    return true;
                default:
                    return true;
            }
        });
    }

    private void switchCarousel(int direction) {
        if (carouselGames.size() <= 1) return;
        carouselIndex = (carouselIndex + direction + carouselGames.size()) % carouselGames.size();
        showCarouselGame(carouselIndex, true);
    }

    /** 资讯轮播手势：与 setupHeroSwipe 同一套手感（视差/慢滑/触觉反馈/按下暂停），单击改为打开详情。 */
    private void setupNewsSwipe(View newsCard) {
        if (newsCard == null) return;
        newsCard.setClickable(true);
        newsCard.setOnClickListener(view -> {
            touch(view);
            if (newsIndex >= 0 && newsIndex < newsItems.size()) openNewsItem(newsItems.get(newsIndex));
        });
        final float[] downX = {0f};
        final float[] downY = {0f};
        final boolean[] moved = {false};
        newsCard.setOnTouchListener((view, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX[0] = event.getX();
                    downY[0] = event.getY();
                    moved[0] = false;
                    newsCarouselHandler.removeCallbacks(newsCarouselRunnable);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float moveX = event.getX() - downX[0];
                    float moveY = event.getY() - downY[0];
                    if (Math.abs(moveX) > dp(8) || Math.abs(moveY) > dp(8)) moved[0] = true;
                    if (Math.abs(moveX) > Math.abs(moveY)) {
                        view.setTranslationX(Math.max(-dp(24), Math.min(dp(24), moveX * 0.12f)));
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    float deltaX = event.getX() - downX[0];
                    float deltaY = event.getY() - downY[0];
                    view.animate().translationX(0f).setDuration(140).start();
                    boolean horizontalSwipe = newsItems.size() > 1
                            && Math.abs(deltaX) >= dp(42)
                            && Math.abs(deltaX) > Math.abs(deltaY) * 1.2f;
                    if (horizontalSwipe) {
                        int direction = deltaX < 0 ? 1 : -1;
                        showNewsItem((newsIndex + direction + newsItems.size()) % newsItems.size(), true);
                        try { view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); } catch (Throwable ignored) { }
                    } else if (!moved[0] && Math.abs(deltaX) < dp(12) && Math.abs(deltaY) < dp(12)) {
                        view.performClick();
                    }
                    startNewsCarousel();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    view.animate().translationX(0f).setDuration(140).start();
                    startNewsCarousel();
                    return true;
                default:
                    return true;
            }
        });
    }

    private void restartCarouselTimer() {
        carouselHandler.removeCallbacks(carouselRunnable);
        if (carouselGames.size() > 1) carouselHandler.postDelayed(carouselRunnable, 5000L);
    }

    private void refreshProfileHeader() {
        String name = displayProfileName();
        int hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        String period = hour < 5 ? "夜深了" : hour < 11 ? "早上好" : hour < 14 ? "中午好" : hour < 18 ? "下午好" : "晚上好";
        String tip = greetingTip(hour);
        if (homeGreeting != null) homeGreeting.setText(period + "，" + name + "  ›  " + tip);

        if (homeAvatarInitial != null) {
            homeAvatarInitial.setText(profileInitial(name));
            homeAvatarInitial.setVisibility(View.VISIBLE);
        }
        if (homeProfileStatusDot != null) {
            homeProfileStatusDot.setBackgroundResource(isLoggedIn()
                    ? R.drawable.bg_profile_dot_online
                    : R.drawable.bg_profile_dot_local);
        }
        loadProfileAvatar();
        updateChatBadge();
    }

    private void updateChatBadge() {
        if (!isLoggedIn()) return;
        TextView badge = findViewById(R.id.homeNavChatBadge);
        if (badge == null) return;
        com.yuki.yukihub.util.AppExecutors.runOnIo(() -> {
            try {
                com.yuki.yukihub.social.SocialApiClient client = new com.yuki.yukihub.social.SocialApiClient(this);
                int unread = client.getTotalUnread();
                client.getFriendsList();
                int pending = client.getPendingRequestsCount();
                int total = unread + pending;
                runOnUiThread(() -> {
                    if (total > 0) {
                        badge.setText(String.valueOf(total));
                        badge.setVisibility(View.VISIBLE);
                    } else {
                        badge.setVisibility(View.GONE);
                    }
                });
            } catch (Throwable ignored) { }
        });
    }

    private boolean isLoggedIn() {
        if (prefs == null) return false;
        String token = prefs.getString(KEY_AUTH_ACCESS_TOKEN, "");
        return token != null && !token.trim().isEmpty();
    }

    private String displayProfileName() {
        if (prefs == null) return "Yuki";
        if (isLoggedIn()) {
            String cloudName = prefs.getString(KEY_AUTH_NICKNAME, "");
            if (cloudName != null && !cloudName.trim().isEmpty()) return cloudName.trim();
        }
        String localName = prefs.getString(KEY_PROFILE_NAME, "Yuki");
        return localName == null || localName.trim().isEmpty() ? "Yuki" : localName.trim();
    }

    private String profileInitial(String name) {
        String value = name == null ? "" : name.trim();
        if (value.isEmpty()) return "Y";
        try {
            int end = value.offsetByCodePoints(0, 1);
            return value.substring(0, end).toUpperCase(Locale.getDefault());
        } catch (Throwable ignored) {
            return "Y";
        }
    }

    private String greetingTip(int hour) {
        String[] tips;
        if (hour < 5) {
            tips = new String[]{"别忘了保存进度", "夜深了，也要注意休息", "这一章结束就休息吧"};
        } else if (hour < 11) {
            tips = new String[]{"新的一天，从喜欢的故事开始", "今天也整理一下游戏库吧", "愿今天遇见好故事"};
        } else if (hour < 18) {
            tips = new String[]{"要继续上次的故事吗？", "游戏库已经准备好了", "给自己留一点游玩时间吧"};
        } else {
            tips = new String[]{"今晚想继续哪段故事？", "欢迎回来，存档还在等你", "挑一款喜欢的游戏放松一下吧"};
        }
        java.util.Calendar calendar = java.util.Calendar.getInstance();
        int stableIndex = Math.abs(calendar.get(java.util.Calendar.DAY_OF_YEAR) + hour / 5) % tips.length;
        return tips[stableIndex];
    }

    private void loadProfileAvatar() {
        if (homeAvatar == null) return;
        // 始终使用 profile_avatar（本地 file:// 路径），与 MainActivity 保持一致
        String value = prefs == null ? "" : prefs.getString(KEY_PROFILE_AVATAR, "");
        value = value == null ? "" : value.trim();
        avatarImageRequest = value;
        homeAvatar.setVisibility(View.GONE);
        if (value.isEmpty()) return;
        try {
            Uri uri = value.contains("://") ? Uri.parse(value) : Uri.fromFile(new File(value));
            homeAvatar.setImageURI(uri);
            if (homeAvatar.getDrawable() != null) {
                homeAvatar.setVisibility(View.VISIBLE);
                if (homeAvatarInitial != null) homeAvatarInitial.setVisibility(View.GONE);
            }
        } catch (Throwable ignored) {
            homeAvatar.setVisibility(View.GONE);
        }
    }

    private void loadRemoteProfileAvatar(String url) {
        final String request = url;
        new Thread(() -> {
            Bitmap bitmap = null;
            try {
                File dir = new File(getCacheDir(), "home_profile");
                if (!dir.exists()) dir.mkdirs();
                File file = new File(dir, "avatar_" + Integer.toHexString(url.hashCode()));
                if (file.exists() && file.length() > 0) bitmap = BitmapFactory.decodeFile(file.getAbsolutePath());
                if (bitmap == null) {
                    HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
                    connection.setConnectTimeout(6000);
                    connection.setReadTimeout(9000);
                    connection.setInstanceFollowRedirects(true);
                    connection.setRequestProperty("User-Agent", "YukiHub/1.0");
                    try (InputStream input = connection.getInputStream(); FileOutputStream output = new FileOutputStream(file)) {
                        byte[] buffer = new byte[8192];
                        int count;
                        while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                    } finally {
                        connection.disconnect();
                    }
                    bitmap = BitmapFactory.decodeFile(file.getAbsolutePath());
                }
            } catch (Throwable ignored) { }
            final Bitmap result = bitmap;
            runOnUiThread(() -> {
                if (!request.equals(avatarImageRequest) || result == null || homeAvatar == null) return;
                homeAvatar.setImageBitmap(result);
                homeAvatar.setVisibility(View.VISIBLE);
                if (homeAvatarInitial != null) homeAvatarInitial.setVisibility(View.GONE);
            });
        }).start();
    }

    private void refreshHome() {
        List<Game> games;
        try {
            games = repository.getAll();
        } catch (Throwable t) {
            Toast.makeText(this, "首页数据读取失败", Toast.LENGTH_SHORT).show();
            return;
        }

        int completed = 0;
        int playing = 0;
        for (Game game : games) {
            if (game == null) continue;
            if ("completed".equals(game.playStatus)) completed++;
            else if ("playing".equals(game.playStatus)) playing++;
        }
        bindTodayPlayTime();
        gameCount.setText("🎮 游戏库\n" + games.size() + " 款");
        completedCount.setText("🏆 已玩过\n" + completed + " 个");
        playingCount.setText("♡ 正在玩\n" + playing + " 个");

        setupCarousel(games);
        bindQuickGames(games);
        bindRecentActivity();
        loadGalgameNews();
    }

    private static final long MIN_PLAY_SESSION_MS = 0L;
    private static final long MAX_PLAY_SESSION_MS = 12L * 60L * 60L * 1000L;
    /**
     * 秒退阈值：与 MainActivity 保持一致。
     * 未完成且起始时间距今短于此值的记录直接丢弃，不询问用户。
     * 正常手动退出的游戏由 MainActivity.finishCurrentPlaySessionIfAny 结算，不受影响。
     */
    private static final long DISCARD_OPEN_SESSION_MS = 10L * 1000L;
    private boolean staleSessionDialogShowing = false;

    private void finishStalePlaySessionsIfAny() {
        if (repository == null || staleSessionDialogShowing) return;
        // 与 MainActivity 共用进程级标记，避免在两个界面间切换时重复弹同一个模态窗
        if (GameRepository.isStaleSessionHandled()) return;

        // 先静默丢弃启动失败产生的秒退垃圾记录（游戏启动失败会连续产生多条）
        int discarded = repository.discardShortOpenPlaySessions(DISCARD_OPEN_SESSION_MS);
        if (discarded > 0) {
            android.util.Log.i("YukiHub", "discarded " + discarded + " short open play sessions");
        }

        GameRepository.PlayActivity open = repository.findLatestOpenPlaySession();
        if (open == null) {
            GameRepository.markStaleSessionHandled();
            return;
        }
        GameRepository.markStaleSessionHandled();
        staleSessionDialogShowing = true;
        int total = repository.countOpenPlaySessions();
        long now = System.currentTimeMillis();
        long rawDuration = Math.max(0L, now - open.startTime);
        long duration = Math.min(rawDuration, MAX_PLAY_SESSION_MS);
        String message = "检测到最近一次游玩未正常结束。\n\n"
                + "游戏：" + empty(open.gameTitle, "未命名游戏") + "\n"
                + "开始时间：" + new SimpleDateFormat("MM月dd日 HH:mm", Locale.getDefault()).format(new Date(open.startTime)) + "\n"
                + "可补记时长：" + formatPlayDuration(duration) + "\n\n"
                + (total > 1
                    ? "另有 " + (total - 1) + " 条更早的未完成记录，将一并处理。\n\n"
                    : "")
                + "如果这段时间确实在游玩，可选择补记；如果只是测试启动、闪退或误操作，请选择忽略。";
        androidx.appcompat.app.AlertDialog dialog = new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("发现未完成的游玩记录")
                .setMessage(message)
                .setPositiveButton("补记", (d, w) -> {
                    // 一次性结算全部未完成记录，避免下次进来又弹
                    repository.finishUnfinishedPlaySessions(
                            System.currentTimeMillis(), MIN_PLAY_SESSION_MS, MAX_PLAY_SESSION_MS);
                    refreshHome();
                    Toast.makeText(this, "已补记上次游玩时长", Toast.LENGTH_SHORT).show();
                    staleSessionDialogShowing = false;
                })
                .setNegativeButton("忽略", (d, w) -> {
                    // 同样一次性清掉全部，而非只删这一条
                    repository.deleteOpenPlaySessions();
                    refreshHome();
                    Toast.makeText(this, "已忽略未完成记录", Toast.LENGTH_SHORT).show();
                    staleSessionDialogShowing = false;
                })
                .setCancelable(false)
                .setOnDismissListener(d -> { staleSessionDialogShowing = false; applyImmersive(); })
                .show();
        styleDialogDark(dialog);
    }

    private void styleDialogDark(androidx.appcompat.app.AlertDialog dialog) {
        if (dialog == null) return;
        try {
            android.view.Window w = dialog.getWindow();
            if (w != null) w.setBackgroundDrawableResource(R.drawable.bg_dialog);
            int text = getColorCompat(R.color.yh_text);
            int muted = getColorCompat(R.color.yh_text_muted);
            int primary = getColorCompat(R.color.yh_primary);
            int secondary = getColorCompat(R.color.yh_secondary);
            int titleId = getResources().getIdentifier("alertTitle", "id", "android");
            // androidx.appcompat 的 alertTitle ID 可能不同，两个都找
            if (titleId == 0) {
                titleId = getResources().getIdentifier("alertTitle", "id", getPackageName());
            }
            TextView title = titleId != 0 ? dialog.findViewById(titleId) : null;
            // 如果找不到 alertTitle，遍历 dialog 的 decor view 找带标题的 TextView
            if (title == null && w != null) {
                title = findTitleTextView(w.getDecorView());
            }
            if (title != null) title.setTextColor(text);
            TextView msg = dialog.findViewById(android.R.id.message);
            if (msg != null) msg.setTextColor(muted);
            android.widget.Button p = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE);
            android.widget.Button n = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE);
            if (p != null) p.setTextColor(primary);
            if (n != null) n.setTextColor(secondary);
            applyImmersiveToWindow(w);
            applyImmersive();
        } catch (Throwable ignored) { }
    }

    @SuppressWarnings("deprecation")
    private int getColorCompat(int id) {
        if (android.os.Build.VERSION.SDK_INT >= 23) return getColor(id);
        return getResources().getColor(id);
    }

    /** 遍历 dialog 的 decor view，找标题样式的 TextView */
    private TextView findTitleTextView(View root) {
        if (root instanceof TextView) {
            TextView tv = (TextView) root;
            // 标题通常 textSize >= 18sp 且不是按钮
            if (tv.getText() != null && tv.getText().length() > 0
                    && tv.getTextSize() / getResources().getDisplayMetrics().scaledDensity >= 16f
                    && !(root instanceof android.widget.Button)
                    && tv.getId() != android.R.id.message
                    && tv.getId() != android.R.id.button1
                    && tv.getId() != android.R.id.button2
                    && tv.getId() != android.R.id.button3) {
                return tv;
            }
        }
        if (root instanceof android.view.ViewGroup) {
            android.view.ViewGroup vg = (android.view.ViewGroup) root;
            for (int i = 0; i < vg.getChildCount(); i++) {
                TextView found = findTitleTextView(vg.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private void applyImmersiveToWindow(android.view.Window window) {
        if (window == null) return;
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN);
        // 主界面可选：是否绘制到刘海/挖孔区域（首页设置里开关，默认开启）
        com.yuki.yukihub.util.CutoutCompat.setCutoutMode(window,
                prefs != null && prefs.getBoolean(MainActivity.KEY_MAIN_DRAW_CUTOUT, true));
        View decor = window.getDecorView();
        if (decor == null) return;
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = decor.getWindowInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        }
        decor.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    /** 弹窗用的正式时长格式：<1分钟显示秒，>=1分钟显示X小时Y分钟 */
    private String formatPlayDuration(long millis) {
        long totalSeconds = Math.max(0L, millis) / 1000L;
        if (totalSeconds < 60L) return totalSeconds + "秒";
        long totalMinutes = totalSeconds / 60L;
        long hours = totalMinutes / 60L;
        long minutes = totalMinutes % 60L;
        StringBuilder sb = new StringBuilder();
        if (hours > 0L) sb.append(hours).append("小时");
        sb.append(minutes).append("分钟");
        return sb.toString();
    }

    private void bindTodayPlayTime() {
        if (playTime == null || repository == null) return;
        try {
            java.util.Calendar now = java.util.Calendar.getInstance();
            java.util.Calendar todayStart = (java.util.Calendar) now.clone();
            todayStart.set(java.util.Calendar.HOUR_OF_DAY, 0);
            todayStart.set(java.util.Calendar.MINUTE, 0);
            todayStart.set(java.util.Calendar.SECOND, 0);
            todayStart.set(java.util.Calendar.MILLISECOND, 0);

            java.util.Calendar yesterdayStart = (java.util.Calendar) todayStart.clone();
            yesterdayStart.add(java.util.Calendar.DAY_OF_MONTH, -1);

            // 今天 0:00 ~ 现在
            long today = sumDurations(repository.getPlayDurationsBetween(
                    todayStart.getTimeInMillis(), now.getTimeInMillis() + 1L));
            // 昨天 0:00 ~ 昨天 24:00（整天）
            long yesterday = sumDurations(repository.getPlayDurationsBetween(
                    yesterdayStart.getTimeInMillis(), todayStart.getTimeInMillis() + 1L));
            long difference = today - yesterday;

            SpannableString styled;
            if (today <= 0L && yesterday <= 0L) {
                styled = new SpannableString("◷ 今日游玩\n0m\n今天还没开始玩哦");
            } else if (yesterday <= 0L) {
                styled = new SpannableString("◷ 今日游玩\n" + formatDuration(today) + "\n昨日 0m · 开始积累吧");
            } else {
                String icon = difference > 0 ? "▲" : difference < 0 ? "▼" : "—";
                String trend = icon + " " + formatCompactDuration(Math.abs(difference)) + " 较昨日";
                String content = "◷ 今日游玩\n" + formatDuration(today) + "\n" + trend;
                styled = new SpannableString(content);
                int start = content.lastIndexOf(trend);
                int color = difference > 0 ? 0xFF44FF66 : difference < 0 ? 0xFFFF4444 : 0xFFAAAAAA;
                if (start >= 0) styled.setSpan(new ForegroundColorSpan(color), start, content.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            playTime.setText(styled);
        } catch (Throwable ignored) {
            playTime.setText("◷ 今日游玩\n0m\n— 暂无对比");
        }
    }

    private long sumDurations(java.util.Map<String, Long> durations) {
        long total = 0L;
        if (durations == null) return total;
        for (Long value : durations.values()) total += value == null ? 0L : Math.max(0L, value);
        return total;
    }

    private String formatCompactDuration(long millis) {
        long minutes = Math.max(0L, millis) / 60000L;
        if (minutes < 1L && millis > 0L) return "<1m";
        long hours = minutes / 60L;
        long remain = minutes % 60L;
        if (hours <= 0L) return remain + "m";
        if (remain <= 0L) return hours + "h";
        return hours + "h " + remain + "m";
    }

    private void setupCarousel(List<Game> games) {
        carouselHandler.removeCallbacks(carouselRunnable);
        carouselGames.clear();
        if (games != null) {
            // repository.getAll() 已按最近游玩、创建时间排序。
            for (Game game : games) {
                if (game == null) continue;
                carouselGames.add(game);
                if (carouselGames.size() >= 5) break;
            }
        }
        carouselIndex = 0;
        buildCarouselDots();
        if (carouselGames.isEmpty()) {
            heroImageRequest = "";
            heroCover.setImageResource(R.drawable.ic_launcher_foreground);
            heroTitle.setText("开始你的游戏旅程");
            heroSubtitle.setText("从游戏库添加或选择一个游戏");
            View action = findViewById(R.id.homeHeroAction);
            if (action != null) action.setEnabled(false);
            return;
        }
        View action = findViewById(R.id.homeHeroAction);
        if (action != null) action.setEnabled(true);
        showCarouselGame(0, false);
        if (carouselGames.size() > 1) carouselHandler.postDelayed(carouselRunnable, 5000L);
    }

    private void buildCarouselDots() {
        if (heroDots == null) return;
        heroDots.removeAllViews();
        for (int i = 0; i < carouselGames.size(); i++) {
            final int index = i;
            View dot = new View(this);
            dot.setBackground(dotDrawable(i == carouselIndex));
            dot.setOnClickListener(v -> {
                carouselIndex = index;
                showCarouselGame(index, true);
                carouselHandler.removeCallbacks(carouselRunnable);
                if (carouselGames.size() > 1) carouselHandler.postDelayed(carouselRunnable, 5000L);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(i == carouselIndex ? 14 : 6), dp(5));
            lp.setMargins(dp(2), 0, dp(2), 0);
            heroDots.addView(dot, lp);
        }
    }

    private GradientDrawable dotDrawable(boolean active) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setCornerRadius(dp(3));
        drawable.setColor(active ? 0xFFFFFFFF : 0x55FFFFFF);
        return drawable;
    }

    private void showCarouselGame(int index, boolean animate) {
        if (index < 0 || index >= carouselGames.size()) return;
        Game game = carouselGames.get(index);
        carouselIndex = index;
        if (animate) {
            heroCover.animate().alpha(0.25f).setDuration(120).withEndAction(() -> {
                bindCarouselContent(game);
                heroCover.animate().alpha(1f).setDuration(220).start();
            }).start();
        } else {
            bindCarouselContent(game);
            heroCover.setAlpha(1f);
        }
        buildCarouselDots();
    }

    private void bindCarouselContent(Game game) {
        heroTitle.setText(empty(game.title, "未命名游戏"));
        heroSubtitle.setText(game.lastPlayedAt > 0
                ? "上次游玩：" + new SimpleDateFormat("MM月dd日 HH:mm", Locale.getDefault()).format(new Date(game.lastPlayedAt))
                : "已收录到游戏库");
        loadHeroCover(game);
    }

    private void loadHeroCover(Game game) {
        String value = firstNonEmpty(game == null ? null : game.coverPersistUri, game == null ? null : game.coverUri);
        final String request = String.valueOf(game == null ? -1L : game.id) + ":" + value;
        heroImageRequest = request;
        heroCover.setImageResource(R.drawable.ic_launcher_foreground);
        if (value.isEmpty()) return;
        if (value.startsWith("http://") || value.startsWith("https://")) {
            loadRemoteHeroCover(value, request);
            return;
        }
        try {
            Uri uri = value.contains("://") ? Uri.parse(value) : Uri.fromFile(new File(value));
            heroCover.setImageURI(uri);
            if (heroCover.getDrawable() == null) heroCover.setImageResource(R.drawable.ic_launcher_foreground);
        } catch (Throwable ignored) {
            heroCover.setImageResource(R.drawable.ic_launcher_foreground);
        }
    }

    private void loadRemoteHeroCover(String url, String request) {
        new Thread(() -> {
            Bitmap bitmap = null;
            try {
                File dir = new File(getCacheDir(), "home_carousel");
                if (!dir.exists()) dir.mkdirs();
                File file = new File(dir, "cover_" + Integer.toHexString(url.hashCode()));
                if (file.exists() && file.length() > 0) bitmap = BitmapFactory.decodeFile(file.getAbsolutePath());
                if (bitmap == null) {
                    HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
                    connection.setConnectTimeout(6000);
                    connection.setReadTimeout(9000);
                    connection.setInstanceFollowRedirects(true);
                    connection.setRequestProperty("User-Agent", "YukiHub/1.0");
                    try (InputStream input = connection.getInputStream(); FileOutputStream output = new FileOutputStream(file)) {
                        byte[] buffer = new byte[8192];
                        int count;
                        while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                    } finally {
                        connection.disconnect();
                    }
                    bitmap = BitmapFactory.decodeFile(file.getAbsolutePath());
                }
            } catch (Throwable ignored) { }
            final Bitmap result = bitmap;
            runOnUiThread(() -> {
                if (!request.equals(heroImageRequest)) return;
                if (result != null) heroCover.setImageBitmap(result);
            });
        }).start();
    }

    private void bindQuickGames(List<Game> games) {
        quickGames.removeAllViews();
        int shown = 0;
        if (games != null) {
            for (Game game : games) {
                if (game == null || shown >= 4) break;
                quickGames.addView(createQuickGameCard(game, shown));
                shown++;
            }
        }
        quickGames.addView(createAddGameCard(shown));
    }

    private View createQuickGameCard(Game game, int index) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(4), dp(4), dp(4), dp(4));
        card.setBackgroundResource(R.drawable.bg_home_glass);
        card.setClipToOutline(true);
        card.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(16));
            }
        });
        card.setClickable(true);
        card.setFocusable(true);
        card.setContentDescription("进入游戏库查看" + empty(game.title, "游戏"));

        ImageView cover = new ImageView(this);
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        cover.setBackgroundColor(0x33273A75);
        cover.setClipToOutline(true);
        cover.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(12));
            }
        });
        if (!loadLocalCover(cover, game)) cover.setImageResource(R.drawable.ic_launcher_foreground);
        card.addView(cover, new LinearLayout.LayoutParams(dp(62), dp(42)));

        TextView title = new TextView(this);
        title.setText(empty(game.title, "未命名游戏"));
        title.setTextColor(Color.WHITE);
        title.setTextSize(8);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setPadding(1, dp(1), 1, 0);
        card.addView(title, new LinearLayout.LayoutParams(dp(62), dp(14)));

        TextView button = new TextView(this);
        button.setText("▶ 启动");
        button.setGravity(android.view.Gravity.CENTER);
        button.setTextColor(Color.WHITE);
        button.setTextSize(7);
        button.setTypeface(null, android.graphics.Typeface.BOLD);
        button.setBackgroundResource(R.drawable.bg_home_primary_pill);
        card.addView(button, new LinearLayout.LayoutParams(dp(62), dp(16)));

        card.setOnClickListener(v -> {
            touch(v);
            launchGameFromHome(game);
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(70), dp(80));
        lp.setMargins(index == 0 ? 0 : dp(5), 0, 0, 0);
        card.setLayoutParams(lp);
        return card;
    }

    private View createAddGameCard(int index) {
        TextView add = new TextView(this);
        add.setText("＋\n添加游戏");
        add.setGravity(android.view.Gravity.CENTER);
        add.setTextColor(Color.WHITE);
        add.setTextSize(9);
        add.setTypeface(null, android.graphics.Typeface.BOLD);
        add.setBackgroundResource(R.drawable.bg_home_glass);
        add.setClickable(true);
        add.setFocusable(true);
        add.setContentDescription("进入游戏库添加游戏");
        add.setOnClickListener(v -> {
            touch(v);
            startActivity(new Intent(this, MainActivity.class));
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(62), dp(80));
        lp.setMargins(index == 0 ? 0 : dp(5), 0, 0, 0);
        add.setLayoutParams(lp);
        return add;
    }

    // ======================== Galgame 资讯（NextMoe /v2/news，免密钥，客户端直连） ========================

    private static final String NEWS_API_URL = "https://api.nextmoe.dev/v2/news";
    private static final String NEWS_CACHE_FILE = "home_news_cache.json";
    private static final long NEWS_CACHE_TTL_MS = 2L * 60L * 60L * 1000L; // 2 小时
    private static final int NEWS_ITEM_COUNT = 6;
    private static final long NEWS_CAROUSEL_INTERVAL_MS = 5000L;
    private boolean newsLoadInFlight = false;
    private final List<NewsItem> newsItems = new ArrayList<>();
    private int newsIndex = 0;
    private String newsImageRequest = "";
    private final Handler newsCarouselHandler = new Handler(Looper.getMainLooper());
    private final Runnable newsCarouselRunnable = new Runnable() {
        @Override public void run() {
            if (newsItems.size() > 1) {
                showNewsItem((newsIndex + 1) % newsItems.size(), true);
                newsCarouselHandler.postDelayed(this, NEWS_CAROUSEL_INTERVAL_MS);
            }
        }
    };

    /** 一条首页资讯。banner 为题图（当前源覆盖率 100%，仍做空值兜底）。 */
    private static class NewsItem {
        String title = "";
        String summary = "";
        String bannerUrl = "";
        String sourceUrl = "";
        String attribution = "";
        String publishedAt = "";
    }

    private void bindRecentActivity() {
        homeNewsRefresh.setOnClickListener(v -> {
            touch(v);
            loadGalgameNews();
        });
        // 图/标题的点击统一走容器 GestureDetector（见 bindViews），此处不再单独绑定
        // 冷启动优先用新鲜缓存（2h TTL）：不发请求，省匿名配额；过期/缺失才联网刷新
        List<NewsItem> cached = readNewsCache(true);
        if (cached != null && !cached.isEmpty()) {
            renderNews(cached);
            return;
        }
        homeNewsLoading.setVisibility(View.VISIBLE);
        loadGalgameNews();
    }

    private void startNewsCarousel() {
        newsCarouselHandler.removeCallbacks(newsCarouselRunnable);
        if (newsItems.size() > 1) newsCarouselHandler.postDelayed(newsCarouselRunnable, NEWS_CAROUSEL_INTERVAL_MS);
    }

    private void loadGalgameNews() {
        if (newsLoadInFlight) return;
        newsLoadInFlight = true;
        homeNewsRefresh.setText("…");
        new Thread(() -> {
            List<NewsItem> items = null;
            String error = null;
            try {
                items = fetchNewsFromApi();
            } catch (Throwable t) {
                error = t.getMessage() == null ? "网络异常" : t.getMessage();
            }
            final List<NewsItem> fetched = items;
            final boolean timedOut = "TIMED_OUT".equals(error);
            runOnUiThread(() -> {
                newsLoadInFlight = false;
                homeNewsRefresh.setText("↻");
                if (fetched != null && !fetched.isEmpty()) {
                    writeNewsCache(fetched);
                    renderNews(fetched);
                } else {
                    List<NewsItem> stale = readNewsCache(false);
                    if (stale != null && !stale.isEmpty()) {
                        renderNews(stale);
                    } else {
                        homeNewsLoading.setVisibility(View.VISIBLE);
                        homeNewsLoading.setText(timedOut ? "资讯加载较慢，稍后再试" : "资讯获取失败，点 ↻ 重试");
                        homeNewsBanner.setVisibility(View.GONE);
                        homeNewsTitle.setText("");
                        buildNewsDots();
                    }
                }
            });
        }, "yukihub-news").start();
    }

    /** 匿名直连 NextMoe。任何错误（429/限流/断网）都不该打爆首页，超时单独识别。 */
    private List<NewsItem> fetchNewsFromApi() throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(
                NEWS_API_URL + "?limit=" + NEWS_ITEM_COUNT + "&nsfw=true").openConnection();
        try {
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(9000);
            conn.setRequestProperty("Accept", "application/json");
            int code = conn.getResponseCode();
            if (code != 200) throw new RuntimeException("HTTP " + code);
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            try (InputStream in = conn.getInputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            }
            return parseNewsJson(bos.toString("UTF-8"));
        } catch (java.net.SocketTimeoutException e) {
            throw new RuntimeException("TIMED_OUT");
        } finally {
            try { conn.disconnect(); } catch (Throwable ignored) { }
        }
    }

    /** 无信封集合：{ object:"list", items:[{ id, title, summary, source:{attribution}, source_url, published_at }] }。 */
    private List<NewsItem> parseNewsJson(String json) throws Exception {
        List<NewsItem> out = new ArrayList<>();
        org.json.JSONObject root = new org.json.JSONObject(json);
        org.json.JSONArray items = root.optJSONArray("items");
        if (items == null) return out;
        for (int i = 0; i < items.length() && out.size() < NEWS_ITEM_COUNT; i++) {
            org.json.JSONObject o = items.optJSONObject(i);
            if (o == null) continue;
            NewsItem item = new NewsItem();
            item.title = o.optString("title", "").trim();
            if (item.title.isEmpty()) continue; // 没标题的没法点，跳过
            item.summary = o.optString("summary", "").trim();
            org.json.JSONObject banner = o.optJSONObject("banner");
            if (banner != null) item.bannerUrl = banner.optString("url", "").trim();
            item.sourceUrl = o.optString("source_url", "").trim();
            org.json.JSONObject source = o.optJSONObject("source");
            if (source != null) item.attribution = source.optString("attribution", "").trim();
            item.publishedAt = o.optString("published_at", "").trim();
            out.add(item);
        }
        return out;
    }

    private void renderNews(List<NewsItem> items) {
        newsItems.clear();
        for (int i = 0; i < items.size() && i < NEWS_ITEM_COUNT; i++) newsItems.add(items.get(i));
        homeNewsLoading.setVisibility(View.GONE);
        newsIndex = 0;
        showNewsItem(0, false);
        startNewsCarousel();
    }

    private void showNewsItem(int index, boolean animate) {
        if (index < 0 || index >= newsItems.size()) return;
        NewsItem item = newsItems.get(index);
        newsIndex = index;
        buildNewsDots();
        if (animate) {
            homeNewsBanner.animate().alpha(0.25f).setDuration(120).withEndAction(() -> {
                bindNewsContent(item);
                homeNewsBanner.animate().alpha(1f).setDuration(220).start();
            }).start();
        } else {
            bindNewsContent(item);
            homeNewsBanner.setAlpha(1f);
        }
    }

    private void bindNewsContent(NewsItem item) {
        homeNewsTitle.setText(item.title);
        if (item.bannerUrl == null || item.bannerUrl.isEmpty()) {
            // 无题图兜底：隐藏图片层，遮罩下的深色底仍在，标题照常可读
            newsImageRequest = "";
            homeNewsBanner.setVisibility(View.GONE);
            return;
        }
        loadNewsBanner(item.bannerUrl);
    }

    private void buildNewsDots() {
        homeNewsDots.removeAllViews();
        for (int i = 0; i < newsItems.size(); i++) {
            final int index = i;
            View dot = new View(this);
            dot.setBackground(dotDrawable(i == newsIndex));
            dot.setOnClickListener(v -> {
                touch(v);
                showNewsItem(index, true);
                startNewsCarousel();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(i == newsIndex ? 12 : 5), dp(4));
            lp.setMargins(dp(2), 0, dp(2), 0);
            homeNewsDots.addView(dot, lp);
        }
    }

    /** 复用 hero 轮播的磁盘缓存模式（同目录，news_ 前缀区分）。 */
    private void loadNewsBanner(String url) {
        final String request = url;
        newsImageRequest = request;
        homeNewsBanner.setVisibility(View.VISIBLE);
        new Thread(() -> {
            Bitmap bitmap = null;
            try {
                File dir = new File(getCacheDir(), "home_carousel");
                if (!dir.exists()) dir.mkdirs();
                File file = new File(dir, "news_" + Integer.toHexString(url.hashCode()));
                if (file.exists() && file.length() > 0) bitmap = BitmapFactory.decodeFile(file.getAbsolutePath());
                if (bitmap == null) {
                    HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
                    connection.setConnectTimeout(6000);
                    connection.setReadTimeout(9000);
                    connection.setInstanceFollowRedirects(true);
                    connection.setRequestProperty("User-Agent", "YukiHub/1.0");
                    try (InputStream input = connection.getInputStream(); FileOutputStream output = new FileOutputStream(file)) {
                        byte[] buffer = new byte[8192];
                        int count;
                        while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                    } finally {
                        connection.disconnect();
                    }
                    bitmap = BitmapFactory.decodeFile(file.getAbsolutePath());
                }
            } catch (Throwable ignored) { }
            final Bitmap result = bitmap;
            runOnUiThread(() -> {
                if (!request.equals(newsImageRequest)) return;
                if (result != null) {
                    homeNewsBanner.setImageBitmap(result);
                    homeNewsBanner.setVisibility(View.VISIBLE);
                    // 揭示动画：106% 缩放 + 全透明起步，减速曲线落定（缓存命中同样播放，节奏一致）
                    homeNewsBanner.setAlpha(0f);
                    homeNewsBanner.setScaleX(1.06f);
                    homeNewsBanner.setScaleY(1.06f);
                    homeNewsBanner.animate().alpha(1f).scaleX(1f).scaleY(1f)
                            .setDuration(320L)
                            .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f))
                            .start();
                } else {
                    homeNewsBanner.setVisibility(View.GONE);
                }
            });
        }, "yukihub-news-banner").start();
    }

    /** 详情弹窗：标题 + 摘要 + 日期/署名（NextMoe 要求引用资讯须标注来源）+ 阅读原文。 */
    private void openNewsItem(NewsItem item) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        box.setPadding(pad, dp(4), pad, dp(2));

        TextView summary = new TextView(this);
        summary.setText(item.summary.isEmpty() ? "暂无摘要。" : item.summary);
        summary.setTextSize(13f);
        summary.setTextColor(0xE8FFFFFF);
        summary.setLineSpacing(dp(2), 1.0f);
        box.addView(summary);

        StringBuilder metaText = new StringBuilder();
        if (item.publishedAt != null && item.publishedAt.length() >= 10) {
            metaText.append("发布于 ").append(item.publishedAt.substring(0, 10));
        }
        if (item.attribution != null && !item.attribution.isEmpty()) {
            if (metaText.length() > 0) metaText.append('\n');
            metaText.append(item.attribution);
        }
        if (metaText.length() == 0) metaText.append("via NextMoe·未萌");
        TextView meta = new TextView(this);
        meta.setText(metaText.toString());
        meta.setTextSize(10f);
        meta.setTextColor(0x8CFFFFFF);
        meta.setPadding(0, dp(12), 0, 0);
        box.addView(meta, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        androidx.appcompat.app.AlertDialog.Builder builder = new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(item.title)
                .setView(box)
                .setPositiveButton("关闭", null);
        if (item.sourceUrl != null && !item.sourceUrl.isEmpty()) {
            builder.setNegativeButton("阅读原文", (d, w) -> {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(item.sourceUrl)));
                } catch (Throwable t) {
                    Toast.makeText(this, "无法打开资讯原文，请检查网络", Toast.LENGTH_SHORT).show();
                }
            });
        }
        styleDialogDark(builder.show());
    }

    private File newsCacheFile() {
        return new File(getCacheDir(), NEWS_CACHE_FILE);
    }

    private void writeNewsCache(List<NewsItem> items) {
        try {
            org.json.JSONObject root = new org.json.JSONObject();
            root.put("fetched_at", System.currentTimeMillis());
            org.json.JSONArray arr = new org.json.JSONArray();
            for (NewsItem item : items) {
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("title", item.title);
                o.put("summary", item.summary);
                o.put("bannerUrl", item.bannerUrl);
                o.put("source_url", item.sourceUrl);
                o.put("attribution", item.attribution);
                o.put("published_at", item.publishedAt);
                arr.put(o);
            }
            root.put("items", arr);
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(newsCacheFile())) {
                fos.write(root.toString().getBytes("UTF-8"));
            }
        } catch (Throwable ignored) { }
    }

    /** freshOnly=true 时过期返回 null；false 时过期缓存也回（离线兜底）。 */
    private List<NewsItem> readNewsCache(boolean freshOnly) {
        try {
            File file = newsCacheFile();
            if (!file.exists()) return null;
            byte[] data = new byte[(int) file.length()];
            try (java.io.FileInputStream fis = new java.io.FileInputStream(file)) {
                int off = 0;
                while (off < data.length) {
                    int n = fis.read(data, off, data.length - off);
                    if (n < 0) break;
                    off += n;
                }
            }
            org.json.JSONObject root = new org.json.JSONObject(new String(data, "UTF-8"));
            if (freshOnly && System.currentTimeMillis() - root.optLong("fetched_at", 0) > NEWS_CACHE_TTL_MS) return null;
            org.json.JSONArray arr = root.optJSONArray("items");
            if (arr == null) return null;
            List<NewsItem> out = new ArrayList<>();
            for (int i = 0; i < arr.length() && out.size() < NEWS_ITEM_COUNT; i++) {
                org.json.JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                NewsItem item = new NewsItem();
                item.title = o.optString("title", "");
                item.summary = o.optString("summary", "");
                item.bannerUrl = o.optString("bannerUrl", "");
                item.sourceUrl = o.optString("source_url", "");
                item.attribution = o.optString("attribution", "");
                item.publishedAt = o.optString("published_at", "");
                if (!item.title.isEmpty()) out.add(item);
            }
            return out;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private boolean loadLocalCover(ImageView target, Game game) {
        if (target == null || game == null) return false;
        String value = firstNonEmpty(game.coverPersistUri, game.coverUri);
        if (value.isEmpty() || value.startsWith("http://") || value.startsWith("https://")) return false;
        try {
            Uri uri = Uri.parse(value);
            if ("file".equalsIgnoreCase(uri.getScheme())) {
                File file = new File(uri.getPath() == null ? "" : uri.getPath());
                if (!file.exists()) return false;
            }
            target.setImageURI(uri);
            return target.getDrawable() != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private String formatDuration(long millis) {
        long minutes = Math.max(0L, millis) / 60000L;
        if (minutes < 1L && millis > 0L) return "<1m";
        long hours = minutes / 60L;
        long remain = minutes % 60L;
        if (hours <= 0L) return remain + "m";
        if (remain <= 0L) return hours + "h";
        return hours + "h " + remain + "m";
    }

    private String empty(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private String firstNonEmpty(String a, String b) {
        if (a != null && !a.trim().isEmpty()) return a.trim();
        if (b != null && !b.trim().isEmpty()) return b.trim();
        return "";
    }

    private void touch(View view) {
        try { view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); } catch (Throwable ignored) { }
        view.animate().scaleX(0.96f).scaleY(0.96f).setDuration(80).withEndAction(() -> view.animate().scaleX(1f).scaleY(1f).setDuration(120).start()).start();
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void applyImmersive() {
        try {
            // 主界面可选：是否绘制到刘海/挖孔区域（首页设置里开关，默认开启）
            com.yuki.yukihub.util.CutoutCompat.setCutoutMode(getWindow(),
                    prefs != null && prefs.getBoolean(MainActivity.KEY_MAIN_DRAW_CUTOUT, true));
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                // 内容延伸到状态栏/刘海区域，避免安全区外露出窗口背景（白边）
                getWindow().setDecorFitsSystemWindows(false);
                WindowInsetsController controller = getWindow().getInsetsController();
                if (controller != null) {
                    controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                    controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                }
            } else {
                getWindow().getDecorView().setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
            }
        } catch (Throwable ignored) { }
    }
}