package com.yuki.yukihub.ai;

import android.Manifest;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.media.MediaScannerConnection;
import android.provider.MediaStore;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import com.yuki.yukihub.R;
import com.yuki.yukihub.data.GameRepository;
import com.yuki.yukihub.data.GameRepository.PlayActivity;
import com.yuki.yukihub.data.MetadataRepository;
import com.yuki.yukihub.metadata.BangumiClient;
import com.yuki.yukihub.metadata.VndbClient;
import com.yuki.yukihub.metadata.VnMetadata;
import com.yuki.yukihub.metadata.YmgalClient;
import com.yuki.yukihub.model.Game;
import com.yuki.yukihub.util.AppExecutors;
import com.yuki.yukihub.util.TimeFormatUtil;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * AI 周点评功能控制器，从 MainActivity 拆分而来。
 * 负责点评生成、设置、历史、图片模板绘制与分享。
 */
public class AiReviewController {

    private static final String TAG = "YukiHub";

    private final AppCompatActivity activity;
    private final Delegate delegate;

    public AiReviewController(AppCompatActivity activity, Delegate delegate) {
        this.activity = activity;
        this.delegate = delegate;
    }

    private boolean isActivityAlive() {
        return activity != null && !activity.isDestroyed() && !activity.isFinishing();
    }

    /** 由 MainActivity 实现的回调接口，提供控制器所需的数据和工具方法。 */
    public interface Delegate {
        GameRepository gameRepository();
        MetadataRepository metadataRepository();
        List<Game> allGames();
        SharedPreferences prefs();

        String visibleMetadataSource(long gameId);
        VnMetadata metadataForSource(long gameId, String source);
        VnMetadata anyCachedMetadata(long gameId);
        boolean usingYmgal();
        boolean usingBangumi();
        boolean usingBangumiMirror();
        String bangumiToken();
        String buildMetadataSearchKeyword(String title);
        boolean isConfidentMatch(String localTitle, VnMetadata meta);

        int dp(int value);
        int getColorCompat(int id);
        Button krButton(String text);
        Spinner krSpinner(String[] values, String selected);
        CheckBox krCheckBox(String text, boolean checked);
        void styleAlertDialogDark(AlertDialog dialog);
        void applyImmersiveToWindow(Window window);
        void playUiSound(int type);
        String emptyText(String s, String fallback);
        String normalizePlayStatus(String status);
        String safeCoverUri(Game g);
        String initials(String title);
    }

    // ---- Delegate 转发方法 ----

    private int dp(int v) { return delegate.dp(v); }
    private int getColorCompat(int id) { return delegate.getColorCompat(id); }
    private Button krButton(String t) { return delegate.krButton(t); }
    private Spinner krSpinner(String[] v, String s) { return delegate.krSpinner(v, s); }
    private CheckBox krCheckBox(String t, boolean c) { return delegate.krCheckBox(t, c); }
    private void styleAlertDialogDark(AlertDialog d) { delegate.styleAlertDialogDark(d); }
    private void applyImmersiveToWindow(Window w) { delegate.applyImmersiveToWindow(w); }
    private void playUiSound(int t) { delegate.playUiSound(t); }
    private String emptyText(String s, String f) { return delegate.emptyText(s, f); }
    private String normalizePlayStatus(String s) { return delegate.normalizePlayStatus(s); }
    private String safeCoverUri(Game g) { return delegate.safeCoverUri(g); }
    private String initials(String t) { return delegate.initials(t); }

    // ======================== 公开入口 ========================

    public void showAiReviewDialog() { showAiReviewDialogImpl(); }
    public void showAiReviewSettingsDialog() { showAiReviewSettingsDialogImpl(); }
    public void showAiReviewHistoryDialog() { showAiReviewHistoryDialogImpl(); }
    public WeeklyPlayStats buildWeeklyPlayStats() { return buildWeeklyPlayStatsImpl(AiReviewSettings.load(activity), false); }
    public WeeklyPlayStats buildWeeklyPlayStats(AiReviewSettings s, boolean o) { return buildWeeklyPlayStatsImpl(s, o); }

private void showAiReviewDialogImpl() {
    WeeklyPlayStats stats = buildWeeklyPlayStats();
    AiReviewSettings settings = AiReviewSettings.load(activity);

    LinearLayout root = new LinearLayout(activity);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setBackgroundResource(R.drawable.bg_dialog);
    int pad = dp(16);
    root.setPadding(pad, dp(14), pad, dp(10));

    TextView privacy = new TextView(activity);
    privacy.setText(settings.metadataEnhance ? "只会发送最近 7 天的游戏名、时长、次数、时段分布，以及已缓存的作品标签/开发商等资料；严格防剧透时不发送简介。不发送本地路径、存档路径或账号信息。" : "只会发送最近 7 天的游戏名、时长、次数和时段分布，不发送本地路径、TF/SD 卡路径、存档路径或账号信息。");
    privacy.setTextColor(getColorCompat(R.color.yh_text_muted));
    privacy.setTextSize(11);
    privacy.setLineSpacing(dp(1), 1.0f);
    privacy.setPadding(0, 0, 0, dp(8));
    root.addView(privacy);

    LinearLayout summary = aiReviewSummaryView(stats, settings);
    root.addView(summary, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

    LinearLayout cardContainer = new LinearLayout(activity);
    cardContainer.setOrientation(LinearLayout.VERTICAL);
    LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    cardLp.setMargins(0, dp(10), 0, dp(8));
    root.addView(cardContainer, cardLp);

    TextView placeholder = new TextView(activity);
    placeholder.setText(stats.isEmpty() ? "最近 7 天还没有有效游玩记录。欧尼酱先去玩一会儿，再来接受 AI 周点评审判吧~" : "点击“生成点评”，让「" + AiReviewSettings.personaLabel(settings.personaPreset) + "」检查欧尼酱这周到底有没有认真清坑。\n\n当前接口：" + AiReviewSettings.providerLabel(settings.provider) + " · " + settings.model + " · " + AiReviewSettings.personaLabel(settings.personaPreset) + " · 防剧透" + AiReviewSettings.spoilerLabel(settings.spoilerLevel) + (settings.metadataEnhance ? " · 资料增强" : ""));
    placeholder.setTextColor(getColorCompat(R.color.yh_text_muted));
    placeholder.setTextSize(12);
    placeholder.setLineSpacing(dp(2), 1.0f);
    placeholder.setBackgroundResource(R.drawable.bg_input);
    placeholder.setPadding(dp(12), dp(10), dp(12), dp(10));
    cardContainer.addView(placeholder);

    LinearLayout actions = new LinearLayout(activity);
    actions.setOrientation(LinearLayout.HORIZONTAL);
    Button generate = krButton("生成点评");
    Button historyBtn = krButton("历史");
    Button settingsBtn = krButton("AI 设置");
    generate.setTextColor(getColorCompat(R.color.yh_primary));
    historyBtn.setTextColor(getColorCompat(R.color.yh_primary));
    settingsBtn.setTextColor(getColorCompat(R.color.yh_primary));
    actions.addView(generate, new LinearLayout.LayoutParams(0, dp(42), 1));
    LinearLayout.LayoutParams historyLp = new LinearLayout.LayoutParams(0, dp(42), 1);
    historyLp.setMargins(dp(8), 0, 0, 0);
    actions.addView(historyBtn, historyLp);
    LinearLayout.LayoutParams settingsLp = new LinearLayout.LayoutParams(0, dp(42), 1);
    settingsLp.setMargins(dp(8), 0, 0, 0);
    actions.addView(settingsBtn, settingsLp);
    root.addView(actions);

    ScrollView scroll = new ScrollView(activity);
    scroll.setBackgroundResource(R.drawable.bg_dialog);
    scroll.addView(root, new ScrollView.LayoutParams(ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));

    AlertDialog dialog = new AlertDialog.Builder(activity)
            .setTitle("AI 周点评")
            .setView(scroll)
            .setNegativeButton("关闭", null)
            .show();
    styleAlertDialogDark(dialog);
    if (dialog.getWindow() != null) {
        dialog.getWindow().setLayout((int) (activity.getResources().getDisplayMetrics().widthPixels * 0.66f), (int) (activity.getResources().getDisplayMetrics().heightPixels * 0.82f));
    }

    settingsBtn.setOnClickListener(v -> showAiReviewSettingsDialog());
    historyBtn.setOnClickListener(v -> showAiReviewHistoryDialog());
    generate.setOnClickListener(v -> {
        AiReviewSettings current = AiReviewSettings.load(activity);
        if (current.apiKey == null || current.apiKey.trim().isEmpty()) {
            Toast.makeText(activity, "请先在 AI 设置里填写 API Key", Toast.LENGTH_LONG).show();
            showAiReviewSettingsDialog();
            return;
        }
        if (stats.isEmpty()) {
            Toast.makeText(activity, "最近 7 天暂无有效游玩记录", Toast.LENGTH_SHORT).show();
            return;
        }
        generate.setEnabled(false);
        generate.setText("生成中...");
        cardContainer.removeAllViews();
        TextView loading = new TextView(activity);
        loading.setText("「" + AiReviewSettings.personaLabel(current.personaPreset) + "」正在翻欧尼酱的游玩账本...\n如果模型比较慢，请稍等一下。");
        loading.setTextColor(getColorCompat(R.color.yh_text_muted));
        loading.setTextSize(12);
        loading.setLineSpacing(dp(2), 1.0f);
        loading.setBackgroundResource(R.drawable.bg_input);
        loading.setPadding(dp(12), dp(10), dp(12), dp(10));
        cardContainer.addView(loading);
        AppExecutors.runOnIo(() -> {
            try {
                WeeklyPlayStats requestStats = buildWeeklyPlayStats(current, current.metadataOnlineLookup);
                String content = new AiReviewClient().requestReview(current, requestStats);
                AiReviewResult result = AiReviewResult.fromContent(content);
                AiReviewHistoryStore.save(activity, requestStats, current, result);
                if (isActivityAlive()) {
                    activity.runOnUiThread(() -> {
                        generate.setEnabled(true);
                        generate.setText("重新生成");
                        renderAiReviewResult(cardContainer, result);
                    });
                }
            } catch (Throwable t) {
                Log.w("YukiHub", "AI review failed", t);
                if (isActivityAlive()) {
                    activity.runOnUiThread(() -> {
                        generate.setEnabled(true);
                        generate.setText("重新生成");
                        cardContainer.removeAllViews();
                        TextView error = new TextView(activity);
                        error.setText("AI 点评失败：" + emptyText(t.getMessage(), t.getClass().getSimpleName()) + "\n\n请检查 API Key、Base URL、模型名和网络。DeepSeek 默认 Base URL 是 https://api.deepseek.com/v1");
                        error.setTextColor(getColorCompat(R.color.yh_warning));
                        error.setTextSize(12);
                        error.setLineSpacing(dp(2), 1.0f);
                        error.setBackgroundResource(R.drawable.bg_input);
                        error.setPadding(dp(12), dp(10), dp(12), dp(10));
                        cardContainer.addView(error);
                    });
                }
            }
        });
    });
}

private LinearLayout aiReviewSummaryView(WeeklyPlayStats stats, AiReviewSettings settings) {
    LinearLayout box = new LinearLayout(activity);
    box.setOrientation(LinearLayout.VERTICAL);
    box.setBackgroundResource(R.drawable.bg_input);
    box.setPadding(dp(12), dp(10), dp(12), dp(10));
    TextView title = new TextView(activity);
    title.setText("最近 7 天 · " + TimeFormatUtil.playTime(stats.totalDuration) + " · " + stats.gameCount() + " 款游戏 · " + stats.sessionCount + " 次启动");
    title.setTextColor(getColorCompat(R.color.yh_text));
    title.setTextSize(14);
    title.setTypeface(null, Typeface.BOLD);
    box.addView(title);
    TextView sub = new TextView(activity);
    sub.setText("活跃 " + stats.activeDays + " 天 · 玩过 " + stats.completedGameCount + " 款 · 在玩 " + stats.playingGameCount + " 款 · 深夜 " + stats.nightCount + " 次 · " + AiReviewSettings.providerLabel(settings.provider) + "/" + settings.model);
    sub.setTextColor(getColorCompat(R.color.yh_text_muted));
    sub.setTextSize(11);
    sub.setPadding(0, dp(4), 0, 0);
    box.addView(sub);
    return box;
}

private void showAiReviewSettingsDialogImpl() {
    AiReviewSettings settings = AiReviewSettings.load(activity);
    LinearLayout root = new LinearLayout(activity);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setPadding(dp(16), dp(12), dp(16), dp(4));

    root.addView(profileLabel("服务商"));
    Spinner provider = krSpinner(new String[]{"DeepSeek", "OpenAI", "自定义"}, AiReviewSettings.providerLabel(settings.provider));
    root.addView(provider, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(42)));

    root.addView(profileLabel("API 地址 / Base URL"));
    EditText baseUrl = profileEdit(settings.baseUrl, "https://api.deepseek.com/v1");
    root.addView(baseUrl, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(42)));
    CheckBox fullEndpoint = krCheckBox("这是完整接口地址（不自动补 /chat/completions）", settings.fullEndpointUrl);
    root.addView(fullEndpoint);
    TextView endpointHint = new TextView(activity);
    endpointHint.setText("默认填写根地址，例如 https://api.deepseek.com/v1，软件会自动请求 /chat/completions。\n如果服务商给的是完整 URL，或带 ?api-version=... 的地址，请勾选上面的完整接口地址。");
    endpointHint.setTextColor(getColorCompat(R.color.yh_text_muted));
    endpointHint.setTextSize(10);
    endpointHint.setLineSpacing(dp(1), 1.0f);
    endpointHint.setPadding(0, dp(4), 0, dp(8));
    root.addView(endpointHint);

    root.addView(profileLabel("API Key"));
    EditText apiKey = profileEdit(settings.apiKey, "sk-...");
    apiKey.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
    root.addView(apiKey, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(42)));

    root.addView(profileLabel("模型"));
    EditText model = profileEdit(settings.model, "deepseek-chat / gpt-4o-mini");
    root.addView(model, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(42)));
    provider.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
        private boolean first = true;
        @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
            if (first) { first = false; return; }
            String p = providerValue(String.valueOf(provider.getSelectedItem()));
            if (!AiReviewSettings.PROVIDER_CUSTOM.equals(p)) {
                baseUrl.setText(AiReviewSettings.defaultBaseUrl(p));
                model.setText(AiReviewSettings.defaultModel(p));
                fullEndpoint.setChecked(false);
            }
        }
        @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
    });

    root.addView(profileLabel("点评人格"));
    Spinner persona = krSpinner(new String[]{"小恶魔妹妹", "温柔学姐", "冷面鉴赏家", "自定义"}, AiReviewSettings.personaLabel(settings.personaPreset));
    root.addView(persona, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(42)));

    root.addView(profileLabel("防剧透等级"));
    Spinner spoiler = krSpinner(new String[]{"严格", "适中", "开放"}, AiReviewSettings.spoilerLabel(settings.spoilerLevel));
    root.addView(spoiler, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(42)));

    CheckBox metadataEnhance = krCheckBox("作品资料增强（使用已缓存 VNDB/Bangumi/月幕 Gal 标签、开发商等）", settings.metadataEnhance);
    CheckBox metadataOnline = krCheckBox("生成前联网补齐 Top 游戏资料（可能较慢，仍遵守防剧透）", settings.metadataOnlineLookup);
    root.addView(metadataEnhance);
    root.addView(metadataOnline);

    root.addView(profileLabel("温度 temperature（0~2）"));
    EditText temperature = profileEdit(String.valueOf(settings.temperature), "0.85");
    temperature.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
    root.addView(temperature, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(42)));

    TextView promptLabel = profileLabel("默认人设提示词");
    promptLabel.setPadding(0, dp(10), 0, dp(4));
    root.addView(promptLabel);
    EditText prompt = profileEdit(settings.systemPrompt, AiReviewSettings.defaultSystemPrompt());
    persona.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
        private boolean first = true;
        @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
            if (first) { first = false; return; }
            String p = AiReviewSettings.personaValue(String.valueOf(persona.getSelectedItem()));
            if (!AiReviewSettings.PERSONA_CUSTOM.equals(p)) prompt.setText(AiReviewSettings.promptForPersona(p));
        }
        @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
    });
    prompt.setSingleLine(false);
    prompt.setMinLines(4);
    prompt.setGravity(Gravity.TOP | Gravity.START);
    root.addView(prompt, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(116)));

    Button testConnection = krButton("测试连接");
    testConnection.setTextColor(getColorCompat(R.color.yh_primary));
    LinearLayout.LayoutParams testLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(40));
    testLp.setMargins(0, dp(10), 0, 0);
    root.addView(testConnection, testLp);

    TextView hint = new TextView(activity);
    hint.setText("DeepSeek 默认：https://api.deepseek.com/v1 / deepseek-chat\nOpenAI 默认：https://api.openai.com/v1 / gpt-4o-mini\n默认会自动补 /chat/completions；完整接口地址模式则完全按填写地址请求。\nAPI Key 仅保存在本机应用数据中。自定义接口需兼容 OpenAI Chat Completions。");
    hint.setTextColor(getColorCompat(R.color.yh_text_muted));
    hint.setTextSize(10);
    hint.setLineSpacing(dp(1), 1.0f);
    hint.setPadding(0, dp(8), 0, 0);
    root.addView(hint);

    ScrollView scroll = new ScrollView(activity);
    scroll.addView(root, new ScrollView.LayoutParams(ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));

    AlertDialog dialog = new AlertDialog.Builder(activity)
            .setTitle("AI 点评设置")
            .setView(scroll)
            .setPositiveButton("保存", null)
            .setNeutralButton("重置人设", null)
            .setNegativeButton("关闭", null)
            .show();
    styleAlertDialogDark(dialog);
    if (dialog.getWindow() != null) {
        dialog.getWindow().setLayout((int) (activity.getResources().getDisplayMetrics().widthPixels * 0.62f), (int) (activity.getResources().getDisplayMetrics().heightPixels * 0.82f));
    }
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
        AiReviewSettings out = collectAiReviewSettingsFromForm(provider, baseUrl, fullEndpoint, apiKey, model, persona, spoiler, metadataEnhance, metadataOnline, temperature, prompt);
        out.save(activity);
        Toast.makeText(activity, "AI 设置已保存", Toast.LENGTH_SHORT).show();
        dialog.dismiss();
    });
    testConnection.setOnClickListener(v -> {
        AiReviewSettings out = collectAiReviewSettingsFromForm(provider, baseUrl, fullEndpoint, apiKey, model, persona, spoiler, metadataEnhance, metadataOnline, temperature, prompt);
        testAiReviewConnection(out, testConnection);
    });
    dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
        String p = AiReviewSettings.personaValue(String.valueOf(persona.getSelectedItem()));
        prompt.setText(AiReviewSettings.promptForPersona(p));
    });
}

private AiReviewSettings collectAiReviewSettingsFromForm(Spinner provider, EditText baseUrl, CheckBox fullEndpoint, EditText apiKey, EditText model, Spinner persona, Spinner spoiler, CheckBox metadataEnhance, CheckBox metadataOnline, EditText temperature, EditText prompt) {
    AiReviewSettings out = new AiReviewSettings();
    out.provider = providerValue(String.valueOf(provider.getSelectedItem()));
    out.baseUrl = textOf(baseUrl);
    if (out.baseUrl.isEmpty()) out.baseUrl = AiReviewSettings.defaultBaseUrl(out.provider);
    out.fullEndpointUrl = fullEndpoint != null && fullEndpoint.isChecked();
    out.apiKey = textOf(apiKey);
    out.model = textOf(model);
    if (out.model.isEmpty()) out.model = AiReviewSettings.defaultModel(out.provider);
    out.personaPreset = AiReviewSettings.personaValue(String.valueOf(persona.getSelectedItem()));
    out.spoilerLevel = spoilerValue(String.valueOf(spoiler.getSelectedItem()));
    out.metadataEnhance = metadataEnhance == null || metadataEnhance.isChecked();
    out.metadataOnlineLookup = metadataOnline != null && metadataOnline.isChecked();
    try { out.temperature = Float.parseFloat(textOf(temperature)); } catch (Throwable ignored) { out.temperature = 0.85f; }
    out.systemPrompt = textOf(prompt);
    if (out.systemPrompt.isEmpty()) out.systemPrompt = AiReviewSettings.promptForPersona(out.personaPreset);
    out.normalize();
    return out;
}

private void testAiReviewConnection(AiReviewSettings settings, Button button) {
    if (settings == null) return;
    if (settings.apiKey == null || settings.apiKey.trim().isEmpty()) {
        Toast.makeText(activity, "请先填写 API Key", Toast.LENGTH_LONG).show();
        return;
    }
    final String endpoint = settings.endpointUrl();
    if (button != null) {
        button.setEnabled(false);
        button.setText("测试中...");
    }
    Toast.makeText(activity, "正在测试 AI 接口...", Toast.LENGTH_SHORT).show();
    AppExecutors.runOnIo(() -> {
        try {
            String reply = new AiReviewClient().testConnection(settings);
            if (isActivityAlive()) {
                    activity.runOnUiThread(() -> {
                        if (button != null) {
                            button.setEnabled(true);
                            button.setText("测试连接");
                        }
                        AlertDialog d = new AlertDialog.Builder(activity)
                                .setTitle("AI 连接成功")
                                .setMessage("实际请求地址：\n" + endpoint + "\n\n模型：" + settings.model + "\n返回：" + emptyText(reply, "OK"))
                                .setPositiveButton("好", null)
                                .show();
                        styleAlertDialogDark(d);
                    });
                }
        } catch (Throwable t) {
            Log.w("YukiHub", "AI test connection failed", t);
            if (isActivityAlive()) {
                    activity.runOnUiThread(() -> {
                        if (button != null) {
                            button.setEnabled(true);
                            button.setText("测试连接");
                        }
                        AlertDialog d = new AlertDialog.Builder(activity)
                                .setTitle("AI 连接失败")
                                .setMessage("实际请求地址：\n" + endpoint + "\n\n错误：\n" + emptyText(t.getMessage(), t.getClass().getSimpleName()))
                                .setPositiveButton("知道了", null)
                                .show();
                        styleAlertDialogDark(d);
                    });
                }
        }
    });
}

private String providerValue(String label) {
    if ("OpenAI".equalsIgnoreCase(label)) return AiReviewSettings.PROVIDER_OPENAI;
    if ("自定义".equals(label)) return AiReviewSettings.PROVIDER_CUSTOM;
    return AiReviewSettings.PROVIDER_DEEPSEEK;
}

private String spoilerValue(String label) {
    if ("开放".equals(label)) return "open";
    if ("适中".equals(label)) return "mild";
    return "strict";
}

private String textOf(EditText e) {
    return e == null || e.getText() == null ? "" : e.getText().toString().trim();
}

private String aiPlayStatusLabel(String status) {
    String s = normalizePlayStatus(status);
    if ("completed".equals(s)) return "🏆 玩过（等同通关/已完成）";
    if ("playing".equals(s)) return "🎮 在玩（尚未通关）";
    return "☆ 未玩（未开始/未通关）";
}


private WeeklyPlayStats buildWeeklyPlayStatsImpl(AiReviewSettings aiSettings, boolean allowOnlineLookup) {
    WeeklyPlayStats stats = new WeeklyPlayStats();
    long end = System.currentTimeMillis();
    Calendar startCal = Calendar.getInstance();
    startCal.setTimeInMillis(end);
    startCal.add(Calendar.DAY_OF_YEAR, -7);
    long start = startCal.getTimeInMillis();
    stats.startTime = start;
    stats.endTime = end;
    if (delegate.gameRepository() == null) return stats;
    Map<String, Long> durations = delegate.gameRepository().getPlayDurationsBetween(start, end);
    List<Map.Entry<String, Long>> entries = new ArrayList<>(durations.entrySet());
    Collections.sort(entries, (a, b) -> Long.compare(b.getValue() == null ? 0L : b.getValue(), a.getValue() == null ? 0L : a.getValue()));
    stats.totalGameCount = durations.size();
    for (Map.Entry<String, Long> e : entries) {
        long duration = e.getValue() == null ? 0L : e.getValue();
        if (duration <= 0) continue;
        stats.totalDuration += duration;
        if (stats.topGames.size() < 8) stats.topGames.put(e.getKey(), duration);
    }
    List<PlayActivity> sessions = delegate.gameRepository().getPlayActivitiesBetween(start, end, 1000);
    java.util.Set<String> days = new java.util.HashSet<>();
    Calendar c = Calendar.getInstance();
    for (PlayActivity a : sessions) {
        if (a == null) continue;
        stats.sessionCount++;
        if (stats.recentSessions.size() < 8) stats.recentSessions.add(a);
        String title = a.gameTitle == null || a.gameTitle.trim().isEmpty() ? "未命名游戏" : a.gameTitle;
        Integer old = stats.gameSessionCounts.get(title);
        stats.gameSessionCounts.put(title, old == null ? 1 : old + 1);
        if (!stats.gameStatuses.containsKey(title)) {
            String status = normalizePlayStatus(a.playStatus);
            stats.gameStatuses.put(title, aiPlayStatusLabel(status));
            if ("completed".equals(status)) stats.completedGameCount++;
            else if ("playing".equals(status)) stats.playingGameCount++;
            else stats.unplayedGameCount++;
        }
        if (a.duration > stats.longestSessionDuration) {
            stats.longestSessionDuration = a.duration;
            stats.longestSessionGame = title;
        }
        c.setTimeInMillis(a.endTime > 0 ? a.endTime : a.startTime);
        days.add(c.get(Calendar.YEAR) + "-" + c.get(Calendar.DAY_OF_YEAR));
        int hour = c.get(Calendar.HOUR_OF_DAY);
        if (hour >= 22 || hour < 4) stats.nightCount++;
        else if (hour >= 13 && hour < 19) stats.afternoonCount++;
        else if (hour >= 8 && hour < 12) stats.morningCount++;
        else stats.otherTimeCount++;
        int dow = c.get(Calendar.DAY_OF_WEEK);
        if (dow == Calendar.SATURDAY || dow == Calendar.SUNDAY) stats.weekendCount++; else stats.weekdayCount++;
    }
    stats.activeDays = days.size();
    stats.averageSessionDuration = stats.sessionCount > 0 ? stats.totalDuration / stats.sessionCount : 0L;
    fillAiReviewMetadata(stats, aiSettings, allowOnlineLookup);
    return stats;
}

private void fillAiReviewMetadata(WeeklyPlayStats stats, AiReviewSettings settings, boolean allowOnlineLookup) {
    if (stats == null || settings == null || !settings.metadataEnhance || stats.topGames.isEmpty()) return;
    int count = 0;
    for (String title : stats.topGames.keySet()) {
        if (count >= 5) break;
        Game game = findGameByTitleForAi(title);
        if (game == null) continue;
        VnMetadata meta = cachedAiReviewMetadataForGame(game);
        if (meta == null && allowOnlineLookup) meta = lookupAiReviewMetadataOnline(game);
        String line = buildAiMetadataLine(game, meta, settings.spoilerLevel);
        if (line != null && !line.trim().isEmpty()) {
            stats.gameMetadata.put(title, line);
            count++;
        }
    }
}

private VnMetadata cachedAiReviewMetadataForGame(Game game) {
    if (game == null || delegate.metadataRepository() == null || game.id <= 0) return null;
    try {
        String visibleSource = delegate.visibleMetadataSource(game.id);
        VnMetadata meta = visibleSource.isEmpty() ? null : delegate.metadataForSource(game.id, visibleSource);
        if (meta == null) meta = delegate.anyCachedMetadata(game.id);
        return meta;
    } catch (Throwable ignored) {
        return null;
    }
}

private VnMetadata lookupAiReviewMetadataOnline(Game game) {
    if (game == null || game.id <= 0 || game.title == null || game.title.trim().isEmpty() || delegate.metadataRepository() == null) return null;
    String keyword = delegate.buildMetadataSearchKeyword(game.title);
    try {
        if (delegate.usingYmgal()) {
            List<VnMetadata> list = YmgalClient.searchCandidates(keyword, 3);
            VnMetadata chosen = chooseAiMetadataCandidate(game.title, list);
            if (chosen != null) chosen = YmgalClient.getGame(chosen.id, chosen);
            if (chosen != null) delegate.metadataRepository().saveYmgal(game.id, chosen);
            return chosen;
        } else if (delegate.usingBangumi()) {
            String token = delegate.bangumiToken();
            if (token == null || token.trim().isEmpty()) return null;
            List<VnMetadata> list = BangumiClient.searchCandidates(keyword, token, 3, delegate.usingBangumiMirror());
            VnMetadata chosen = chooseAiMetadataCandidate(game.title, list);
            if (chosen != null) delegate.metadataRepository().saveBangumi(game.id, chosen);
            return chosen;
        } else {
            List<VnMetadata> list = VndbClient.searchCandidates(keyword, 3);
            VnMetadata chosen = chooseAiMetadataCandidate(game.title, list);
            if (chosen != null) delegate.metadataRepository().saveVndb(game.id, chosen);
            return chosen;
        }
    } catch (Throwable t) {
        Log.w("YukiHub", "AI metadata lookup failed for " + game.title, t);
        return null;
    }
}

private VnMetadata chooseAiMetadataCandidate(String title, List<VnMetadata> list) {
    if (list == null || list.isEmpty()) return null;
    if (list.size() == 1) return list.get(0);
    for (VnMetadata m : list) if (delegate.isConfidentMatch(title, m)) return m;
    return null;
}

private String buildAiMetadataLine(Game game, VnMetadata meta, String spoilerLevel) {
    StringBuilder sb = new StringBuilder();
    if (meta != null) {
        String title = emptyText(meta.chineseTitle, emptyText(meta.originalTitle, meta.romanTitle));
        if (!title.isEmpty() && game != null && game.title != null && !title.equals(game.title)) sb.append("资料标题：").append(compactAiText(title, 40)).append("；");
        appendAiMeta(sb, "开发商", meta.developer, 50);
        appendAiMeta(sb, "标签", meta.tagsText, 80);
        appendAiMeta(sb, "长度", meta.lengthText, 40);
        appendAiMeta(sb, "评分", meta.ratingText, 40);
        if (!"strict".equals(spoilerLevel)) {
            String desc = emptyText(meta.translatedDescription, meta.description);
            appendAiMeta(sb, "简介摘要", desc, "open".equals(spoilerLevel) ? 220 : 150);
        }
    }
    if (game != null) {
        if (sb.length() == 0) appendAiMeta(sb, "本地标签", game.tags, 80);
        if (!"strict".equals(spoilerLevel) && sb.indexOf("简介摘要") < 0) appendAiMeta(sb, "本地简介摘要", game.description, "open".equals(spoilerLevel) ? 180 : 120);
    }
    return sb.toString();
}

private void appendAiMeta(StringBuilder sb, String label, String value, int max) {
    String s = compactAiText(value, max);
    if (s == null || s.isEmpty()) return;
    sb.append(label).append("：").append(s).append("；");
}

private String compactAiText(String text, int max) {
    if (text == null) return "";
    String s = text.replace('\n', ' ').replace('\r', ' ').replaceAll("\\s+", " ").trim();
    if (s.isEmpty() || "-".equals(s)) return "";
    if (max > 0 && s.length() > max) return s.substring(0, max) + "…";
    return s;
}

private void renderAiReviewResult(LinearLayout container, AiReviewResult result) {
    if (container == null || result == null) return;
    container.removeAllViews();
    LinearLayout card = new LinearLayout(activity);
    card.setOrientation(LinearLayout.VERTICAL);
    card.setPadding(dp(14), dp(12), dp(14), dp(12));
    card.setBackground(aiReviewCardBackground());

    TextView badge = new TextView(activity);
    badge.setText("✦ AI 周点评 ✦");
    badge.setTextColor(getColorCompat(R.color.yh_secondary));
    badge.setTextSize(11);
    badge.setTypeface(null, Typeface.BOLD);
    card.addView(badge);

    TextView title = new TextView(activity);
    title.setText(result.title);
    title.setTextColor(getColorCompat(R.color.yh_text));
    title.setTextSize(20);
    title.setTypeface(null, Typeface.BOLD);
    title.setPadding(0, dp(4), 0, 0);
    card.addView(title);

    TextView subtitle = new TextView(activity);
    subtitle.setText(result.subtitle);
    subtitle.setTextColor(getColorCompat(R.color.yh_text_muted));
    subtitle.setTextSize(12);
    subtitle.setPadding(0, dp(4), 0, dp(8));
    card.addView(subtitle);

    TextView score = new TextView(activity);
    score.setText(result.scoreName + "  " + result.score + "/100");
    score.setTextColor(getColorCompat(R.color.yh_primary));
    score.setTextSize(13);
    score.setTypeface(null, Typeface.BOLD);
    card.addView(score);

    LinearLayout bar = new LinearLayout(activity);
    bar.setOrientation(LinearLayout.HORIZONTAL);
    bar.setBackground(aiRoundBg(0x442D3658, dp(8), 0));
    LinearLayout fill = new LinearLayout(activity);
    fill.setBackground(aiGradientBg(0xFFFF8AB3, 0xFF8AB4FF, dp(8)));
    int progress = Math.max(0, Math.min(100, result.score));
    bar.addView(fill, new LinearLayout.LayoutParams(0, dp(8), Math.max(1, progress)));
    View rest = new View(activity);
    bar.addView(rest, new LinearLayout.LayoutParams(0, dp(8), Math.max(1, 100 - progress)));
    LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(8));
    barLp.setMargins(0, dp(6), 0, dp(10));
    card.addView(bar, barLp);

    TextView roast = new TextView(activity);
    roast.setText(result.roast);
    roast.setTextColor(getColorCompat(R.color.yh_text));
    roast.setTextSize(14);
    roast.setLineSpacing(dp(2), 1.05f);
    roast.setPadding(dp(10), dp(8), dp(10), dp(8));
    roast.setBackground(aiRoundBg(0x33111A36, dp(10), 0x338AB4FF));
    card.addView(roast, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

    if (!result.highlights.isEmpty()) {
        card.addView(aiSectionTitle("本周抓包"));
        for (String h : result.highlights) card.addView(aiChip("• " + h));
    }
    if (!result.topGamesComment.isEmpty()) {
        card.addView(aiSectionTitle("重点游戏吐槽"));
        for (AiReviewResult.GameComment gc : result.topGamesComment) {
            String text = (gc.game == null || gc.game.isEmpty() ? "游戏" : "《" + gc.game + "》") + "：" + gc.comment;
            card.addView(aiChip(text));
        }
    }
    if (!result.advice.isEmpty()) {
        card.addView(aiSectionTitle("下周处方"));
        for (String a : result.advice) card.addView(aiChip("✧ " + a));
    }
    if (result.oneLine != null && !result.oneLine.trim().isEmpty()) {
        TextView one = new TextView(activity);
        one.setText(result.oneLine);
        one.setTextColor(getColorCompat(R.color.yh_secondary));
        one.setTextSize(12);
        one.setTypeface(null, Typeface.BOLD);
        one.setPadding(0, dp(10), 0, 0);
        card.addView(one);
    }

    LinearLayout shareRow = new LinearLayout(activity);
    shareRow.setOrientation(LinearLayout.HORIZONTAL);
    Button copy = krButton("复制点评");
    Button share = krButton("分享文本");
    copy.setTextColor(getColorCompat(R.color.yh_primary));
    share.setTextColor(getColorCompat(R.color.yh_primary));
    copy.setOnClickListener(v -> copyAiReviewText(result));
    share.setOnClickListener(v -> shareAiReviewText(result));
    shareRow.addView(copy, new LinearLayout.LayoutParams(0, dp(40), 1));
    LinearLayout.LayoutParams shareLp = new LinearLayout.LayoutParams(0, dp(40), 1);
    shareLp.setMargins(dp(8), 0, 0, 0);
    shareRow.addView(share, shareLp);
    LinearLayout.LayoutParams copyLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(40));
    copyLp.setMargins(0, dp(10), 0, 0);
    card.addView(shareRow, copyLp);

    Button imageShare = krButton("分享模板长图");
    imageShare.setTextColor(getColorCompat(R.color.yh_secondary));
    imageShare.setOnClickListener(v -> showAiReviewImageTemplateDialog(result));
    LinearLayout.LayoutParams imageShareLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(40));
    imageShareLp.setMargins(0, dp(8), 0, 0);
    card.addView(imageShare, imageShareLp);

    container.addView(card, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
}

private TextView aiSectionTitle(String text) {
    TextView v = new TextView(activity);
    v.setText(text);
    v.setTextColor(getColorCompat(R.color.yh_text));
    v.setTextSize(13);
    v.setTypeface(null, Typeface.BOLD);
    v.setPadding(0, dp(12), 0, dp(5));
    return v;
}

private TextView aiChip(String text) {
    TextView v = new TextView(activity);
    v.setText(text);
    v.setTextColor(getColorCompat(R.color.yh_text_muted));
    v.setTextSize(12);
    v.setLineSpacing(dp(1), 1.0f);
    v.setPadding(dp(10), dp(7), dp(10), dp(7));
    v.setBackground(aiRoundBg(0x221A2444, dp(9), 0x223A4C80));
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    lp.setMargins(0, 0, 0, dp(5));
    v.setLayoutParams(lp);
    return v;
}

private Drawable aiReviewCardBackground() {
    GradientDrawable g = new GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR, new int[]{0xEE171E33, 0xEE211B3A, 0xEE102544});
    g.setCornerRadius(dp(16));
    g.setStroke(dp(1), 0x668AB4FF);
    return g;
}

private Drawable aiGradientBg(int start, int end, int radius) {
    GradientDrawable g = new GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT, new int[]{start, end});
    g.setCornerRadius(radius);
    return g;
}

private Drawable aiRoundBg(int color, int radius, int strokeColor) {
    GradientDrawable g = new GradientDrawable();
    g.setColor(color);
    g.setCornerRadius(radius);
    if (strokeColor != 0) g.setStroke(dp(1), strokeColor);
    return g;
}

private void copyAiReviewText(AiReviewResult result) {
    try {
        Object service = activity.getSystemService(Context.CLIPBOARD_SERVICE);
        if (service instanceof android.content.ClipboardManager) {
            ((android.content.ClipboardManager) service).setPrimaryClip(android.content.ClipData.newPlainText("YukiHub AI Review", result.toShareText()));
            Toast.makeText(activity, "点评已复制", Toast.LENGTH_SHORT).show();
        }
    } catch (Throwable t) {
        Toast.makeText(activity, "复制失败：" + t.getClass().getSimpleName(), Toast.LENGTH_SHORT).show();
    }
}

private void shareAiReviewText(AiReviewResult result) {
    if (result == null) return;
    try {
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_SUBJECT, "YukiHub AI 周点评");
        send.putExtra(Intent.EXTRA_TEXT, result.toShareText());
        activity.startActivity(Intent.createChooser(send, "分享 AI 周点评"));
    } catch (Throwable t) {
        Toast.makeText(activity, "分享失败：" + t.getClass().getSimpleName(), Toast.LENGTH_SHORT).show();
    }
}

private void showAiReviewImageTemplateDialog(AiReviewResult result) {
    if (result == null) return;
    final String[] labels = new String[]{"霓虹周报", "手账风报告", "极简主义报告"};
    LinearLayout root = new LinearLayout(activity);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setPadding(dp(14), dp(10), dp(14), dp(6));
    TextView hint = new TextView(activity);
    hint.setText("选择一套导出长图模板。手账风更接近 LunaBox 的报告预览，霓虹周报适合深色二次元风格。");
    hint.setTextColor(getColorCompat(R.color.yh_text_muted));
    hint.setTextSize(12);
    hint.setLineSpacing(dp(1), 1.0f);
    hint.setPadding(0, 0, 0, dp(8));
    root.addView(hint);
    final AlertDialog[] ref = new AlertDialog[1];
    for (int i = 0; i < labels.length; i++) {
        final int style = i;
        TextView row = new TextView(activity);
        row.setText((i == 0 ? "✦ " : i == 1 ? "✎ " : "◇ ") + labels[i]);
        row.setTextColor(getColorCompat(R.color.yh_text));
        row.setTextSize(14);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.bg_input);
        row.setPadding(dp(14), 0, dp(14), 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46));
        lp.setMargins(0, dp(5), 0, dp(5));
        root.addView(row, lp);
        row.setOnClickListener(v -> {
            if (ref[0] != null) ref[0].dismiss();
            prepareAiReviewImagePreview(result, style, labels[style]);
        });
    }
    AlertDialog dialog = new AlertDialog.Builder(activity)
            .setTitle("选择导出模板")
            .setView(root)
            .setNegativeButton("取消", null)
            .show();
    ref[0] = dialog;
    styleAlertDialogDark(dialog);
}

private void prepareAiReviewImagePreview(AiReviewResult result, int templateStyle, String templateLabel) {
    if (result == null) return;
    Toast.makeText(activity, "正在生成 AI 周点评预览...", Toast.LENGTH_SHORT).show();
    AppExecutors.runOnIo(() -> {
        try {
            Bitmap bitmap = buildAiReviewShareBitmap(result, templateStyle);
            int imageW = bitmap.getWidth();
            int imageH = bitmap.getHeight();
            File dir = new File(activity.getCacheDir(), "ai_review_share");
            if (!dir.exists()) dir.mkdirs();
            cleanupAiReviewShareCache(dir);
            File out = new File(dir, "yukihub_ai_review_" + templateStyle + "_" + System.currentTimeMillis() + ".png");
            try (FileOutputStream fos = new FileOutputStream(out)) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
            }
            bitmap.recycle();
            Uri uri = FileProvider.getUriForFile(activity, activity.getPackageName() + ".fileprovider", out);
            if (isActivityAlive()) {
                    activity.runOnUiThread(() -> showAiReviewImagePreview(uri, templateLabel, imageW, imageH));
                }
        } catch (Throwable t) {
            Log.w("YukiHub", "prepare AI review image preview failed", t);
            if (isActivityAlive()) {
                    activity.runOnUiThread(() -> Toast.makeText(activity, "生成预览失败：" + emptyText(t.getMessage(), t.getClass().getSimpleName()), Toast.LENGTH_LONG).show());
                }
        }
    });
}

private void cleanupAiReviewShareCache(File dir) {
    try {
        if (dir == null || !dir.isDirectory()) return;
        File[] files = dir.listFiles();
        if (files == null || files.length == 0) return;
        long now = System.currentTimeMillis();
        long expire = 24L * 60L * 60L * 1000L;
        for (File f : files) {
            if (f == null || !f.isFile()) continue;
            if (now - f.lastModified() > expire) {
                try { f.delete(); } catch (Throwable ignored) { }
            }
        }
        files = dir.listFiles();
        if (files == null || files.length <= 12) return;
        Arrays.sort(files, (a, b) -> Long.compare(b == null ? 0L : b.lastModified(), a == null ? 0L : a.lastModified()));
        for (int i = 12; i < files.length; i++) {
            File f = files[i];
            if (f != null && f.isFile()) {
                try { f.delete(); } catch (Throwable ignored) { }
            }
        }
    } catch (Throwable ignored) { }
}

private void showAiReviewImagePreview(Uri uri, String templateLabel, int imageW, int imageH) {
    if (uri == null) return;
    int screenW = activity.getResources().getDisplayMetrics().widthPixels;
    int screenH = activity.getResources().getDisplayMetrics().heightPixels;
    int maxDialogW = Math.max(320, screenW - dp(64));
    int minDialogW = Math.min(dp(420), maxDialogW);
    int dialogW = Math.min(maxDialogW, Math.max(minDialogW, (int) (screenW * 0.58f)));
    int dialogH = Math.min(Math.max(320, screenH - dp(32)), Math.max(Math.min(dp(360), screenH), (int) (screenH * 0.84f)));
    int availablePreviewW = Math.max(1, dialogW - dp(56));
    int previewW = Math.min(imageW > 0 ? imageW : availablePreviewW, availablePreviewW);
    int previewH = imageW > 0 ? Math.max(1, Math.round(previewW * imageH / (float) imageW)) : dp(520);

    LinearLayout root = new LinearLayout(activity);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setPadding(dp(12), dp(8), dp(12), dp(8));

    TextView info = new TextView(activity);
    info.setText(emptyText(templateLabel, "模板") + " · " + imageW + "×" + imageH + "\n普通预览已按窗口缩放；长图可上下滚动，想看细节点“全屏预览”。");
    info.setTextColor(getColorCompat(R.color.yh_text_muted));
    info.setTextSize(12);
    info.setLineSpacing(dp(1), 1.0f);
    info.setPadding(0, 0, 0, dp(8));
    root.addView(info);

    LinearLayout actionRow = new LinearLayout(activity);
    actionRow.setOrientation(LinearLayout.HORIZONTAL);
    Button full = krButton("全屏预览");
    Button save = krButton("保存到相册");
    full.setTextColor(getColorCompat(R.color.yh_primary));
    save.setTextColor(getColorCompat(R.color.yh_primary));
    full.setOnClickListener(v -> showAiReviewImageFullPreview(uri, templateLabel, imageW, imageH));
    save.setOnClickListener(v -> saveAiReviewImageToGallery(uri, templateLabel));
    actionRow.addView(full, new LinearLayout.LayoutParams(0, dp(40), 1));
    LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(0, dp(40), 1);
    saveLp.setMargins(dp(8), 0, 0, 0);
    actionRow.addView(save, saveLp);
    LinearLayout.LayoutParams actionLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(40));
    actionLp.setMargins(0, 0, 0, dp(8));
    root.addView(actionRow, actionLp);

    ImageView preview = new ImageView(activity);
    preview.setAdjustViewBounds(false);
    preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
    preview.setBackgroundColor(0xFF101827);
    preview.setImageURI(uri);
    LinearLayout.LayoutParams previewLp = new LinearLayout.LayoutParams(previewW, previewH);
    previewLp.gravity = Gravity.CENTER_HORIZONTAL;
    root.addView(preview, previewLp);

    ScrollView scroll = new ScrollView(activity);
    scroll.setFillViewport(false);
    scroll.setBackgroundResource(R.drawable.bg_dialog);
    scroll.addView(root, new ScrollView.LayoutParams(ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));

    AlertDialog dialog = new AlertDialog.Builder(activity)
            .setTitle("预览导出图片")
            .setView(scroll)
            .setPositiveButton("分享图片", null)
            .setNeutralButton("保存相册", null)
            .setNegativeButton("取消", null)
            .show();
    styleAlertDialogDark(dialog);
    if (dialog.getWindow() != null) dialog.getWindow().setLayout(dialogW, dialogH);
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> shareAiReviewImageUri(uri));
    dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> saveAiReviewImageToGallery(uri, templateLabel));
}

private void showAiReviewImageFullPreview(Uri uri, String templateLabel, int imageW, int imageH) {
    if (uri == null) return;
    Dialog d = new Dialog(activity);
    d.requestWindowFeature(Window.FEATURE_NO_TITLE);
    LinearLayout root = new LinearLayout(activity);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setBackgroundColor(0xFF050914);
    root.setPadding(dp(18), dp(12), dp(18), dp(12));

    LinearLayout top = new LinearLayout(activity);
    top.setOrientation(LinearLayout.HORIZONTAL);
    top.setGravity(Gravity.CENTER_VERTICAL);
    TextView title = new TextView(activity);
    title.setText(emptyText(templateLabel, "AI 周点评") + " · 全屏预览");
    title.setTextColor(0xFFFFFFFF);
    title.setTextSize(16);
    title.setTypeface(null, Typeface.BOLD);
    top.addView(title, new LinearLayout.LayoutParams(0, dp(44), 1));
    Button save = krButton("保存");
    Button share = krButton("分享");
    Button close = krButton("关闭");
    save.setTextColor(getColorCompat(R.color.yh_primary));
    share.setTextColor(getColorCompat(R.color.yh_primary));
    close.setTextColor(getColorCompat(R.color.yh_text_muted));
    top.addView(save, new LinearLayout.LayoutParams(dp(92), dp(40)));
    LinearLayout.LayoutParams shareLp = new LinearLayout.LayoutParams(dp(92), dp(40));
    shareLp.setMargins(dp(8), 0, 0, 0);
    top.addView(share, shareLp);
    LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(dp(92), dp(40));
    closeLp.setMargins(dp(8), 0, 0, 0);
    top.addView(close, closeLp);
    root.addView(top, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)));

    int screenW = activity.getResources().getDisplayMetrics().widthPixels;
    int availablePreviewW = Math.max(1, screenW - dp(56));
    int previewW = Math.min(imageW > 0 ? imageW : availablePreviewW, availablePreviewW);
    int previewH = imageW > 0 ? Math.max(1, Math.round(previewW * imageH / (float) imageW)) : dp(720);
    ImageView image = new ImageView(activity);
    image.setAdjustViewBounds(false);
    image.setScaleType(ImageView.ScaleType.FIT_CENTER);
    image.setBackgroundColor(0xFF101827);
    image.setImageURI(uri);
    LinearLayout wrap = new LinearLayout(activity);
    wrap.setGravity(Gravity.CENTER_HORIZONTAL);
    wrap.addView(image, new LinearLayout.LayoutParams(previewW, previewH));
    ScrollView scroll = new ScrollView(activity);
    scroll.setFillViewport(false);
    scroll.addView(wrap, new ScrollView.LayoutParams(ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
    root.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

    save.setOnClickListener(v -> saveAiReviewImageToGallery(uri, templateLabel));
    share.setOnClickListener(v -> shareAiReviewImageUri(uri));
    close.setOnClickListener(v -> d.dismiss());
    d.setContentView(root);
    d.show();
    if (d.getWindow() != null) {
        d.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        d.getWindow().setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
        applyImmersiveToWindow(d.getWindow());
    }
}

private void shareAiReviewImageUri(Uri uri) {
    if (uri == null) return;
    try {
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("image/png");
        send.putExtra(Intent.EXTRA_STREAM, uri);
        send.putExtra(Intent.EXTRA_TEXT, "YukiHub AI 周点评");
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        activity.startActivity(Intent.createChooser(send, "分享 AI 周点评长图"));
    } catch (Throwable t) {
        Toast.makeText(activity, "分享长图失败：" + t.getClass().getSimpleName(), Toast.LENGTH_LONG).show();
    }
}

private void saveAiReviewImageToGallery(Uri sourceUri, String templateLabel) {
    if (sourceUri == null) return;
    if (Build.VERSION.SDK_INT < 29 && Build.VERSION.SDK_INT >= 23 && activity.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
        activity.requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1002);
        Toast.makeText(activity, "请授权存储权限后再点一次保存", Toast.LENGTH_LONG).show();
        return;
    }
    Toast.makeText(activity, "正在保存到相册...", Toast.LENGTH_SHORT).show();
    AppExecutors.runOnIo(() -> {
        try {
            String name = "YukiHub_AI_Review_" + new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(new java.util.Date()) + ".png";
            Uri saved = savePngUriToGallery(sourceUri, name);
            if (isActivityAlive()) {
                    activity.runOnUiThread(() -> Toast.makeText(activity, saved == null ? "保存失败" : "已保存到相册：Pictures/YukiHub", Toast.LENGTH_LONG).show());
                }
        } catch (Throwable t) {
            Log.w("YukiHub", "save AI review image to gallery failed", t);
            if (isActivityAlive()) {
                    activity.runOnUiThread(() -> Toast.makeText(activity, "保存失败：" + emptyText(t.getMessage(), t.getClass().getSimpleName()), Toast.LENGTH_LONG).show());
                }
        }
    });
}

private Uri savePngUriToGallery(Uri sourceUri, String displayName) throws Exception {
    if (Build.VERSION.SDK_INT >= 29) {
        ContentResolver resolver = activity.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, displayName);
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
        values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/YukiHub");
        values.put(MediaStore.Images.Media.IS_PENDING, 1);
        Uri outUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (outUri == null) throw new Exception("MediaStore insert failed");
        try {
            try (InputStream in = resolver.openInputStream(sourceUri); OutputStream out = resolver.openOutputStream(outUri)) {
                if (in == null || out == null) throw new Exception("open gallery stream failed");
                copyStream(in, out);
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.Images.Media.IS_PENDING, 0);
            resolver.update(outUri, done, null, null);
            return outUri;
        } catch (Throwable t) {
            try { resolver.delete(outUri, null, null); } catch (Throwable ignored) { }
            if (t instanceof Exception) throw (Exception) t;
            throw new Exception(t);
        }
    }

    if (Build.VERSION.SDK_INT >= 23 && activity.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
        activity.requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1002);
        throw new Exception("请授权存储权限后再点一次保存");
    }
    File base = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
    File dir = new File(base, "YukiHub");
    if (!dir.exists() && !dir.mkdirs()) throw new Exception("创建相册目录失败");
    File outFile = new File(dir, displayName);
    try (InputStream in = activity.getContentResolver().openInputStream(sourceUri); FileOutputStream out = new FileOutputStream(outFile)) {
        if (in == null) throw new Exception("open source failed");
        copyStream(in, out);
    }
    MediaScannerConnection.scanFile(activity, new String[]{outFile.getAbsolutePath()}, new String[]{"image/png"}, null);
    return Uri.fromFile(outFile);
}

private void copyStream(InputStream in, OutputStream out) throws Exception {
    byte[] buf = new byte[8192];
    int len;
    while ((len = in.read(buf)) != -1) out.write(buf, 0, len);
    out.flush();
}

private Bitmap buildAiReviewShareBitmap(AiReviewResult result, int templateStyle) {
    if (templateStyle == 1) return buildAiReviewNotebookBitmap(result);
    if (templateStyle == 2) return buildAiReviewMinimalBitmap(result);
    final int w = 1080;
    final int pad = 64;
    Paint titlePaint = aiPaint(58, 0xFFFFFFFF, true);
    Paint subPaint = aiPaint(28, 0xCCDEE8FF, false);
    Paint bodyPaint = aiPaint(34, 0xFFEAF0FF, false);
    Paint smallPaint = aiPaint(26, 0xB8DEE8FF, false);
    Paint accentPaint = aiPaint(30, 0xFFFFB6CE, true);
    List<String> roastLines = wrapText(result.roast, bodyPaint, w - pad * 2 - 48);
    List<String> subtitleLines = wrapText(result.subtitle, subPaint, w - pad * 2);
    List<String> oneLines = wrapText(result.oneLine, accentPaint, w - pad * 2);
    List<String> highlights = result.highlights;
    List<String> advice = result.advice;
    List<Game> coverGames = findAiReviewCoverGames(result, 3);
    int h = 360 + subtitleLines.size() * 38 + roastLines.size() * 46 + oneLines.size() * 40;
    h += coverGames.isEmpty() ? 100 : 360;
    h += Math.max(1, highlights.size()) * 58 + Math.max(1, advice.size()) * 58 + 360;
    h = Math.max(1500, Math.min(2600, h));
    Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
    Canvas c = new Canvas(bitmap);
    Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    p.setShader(new LinearGradient(0, 0, w, h, new int[]{0xFF10182F, 0xFF211C3D, 0xFF102B4A}, null, Shader.TileMode.CLAMP));
    c.drawRect(0, 0, w, h, p);
    p.setShader(null);
    p.setColor(0x33FFFFFF);
    c.drawCircle(w - 120, 120, 220, p);
    p.setColor(0x22FF8AB3);
    c.drawCircle(80, h - 80, 260, p);

    int y = 78;
    drawRoundText(c, "YukiHub · AI 周点评", 64, y, smallPaint, 0x22FFFFFF, 22, 26, 14);
    y += 96;
    c.drawText(emptyText(result.title, "AI 周点评"), pad, y, titlePaint);
    y += 48;
    for (String line : subtitleLines) {
        c.drawText(line, pad, y, subPaint);
        y += 38;
    }
    y += 24;

    RectF scoreCard = new RectF(pad, y, w - pad, y + 132);
    drawGlass(c, scoreCard, 0x33111A36, 0x558AB4FF, 28);
    c.drawText(emptyText(result.scoreName, "沉迷指数"), pad + 30, y + 50, smallPaint);
    Paint scorePaint = aiPaint(46, 0xFFFFFFFF, true);
    c.drawText(Math.max(0, Math.min(100, result.score)) + "/100", w - pad - 210, y + 58, scorePaint);
    RectF barBg = new RectF(pad + 30, y + 86, w - pad - 30, y + 104);
    p.setShader(null); p.setColor(0x442D3658); c.drawRoundRect(barBg, 9, 9, p);
    RectF barFill = new RectF(barBg.left, barBg.top, barBg.left + barBg.width() * Math.max(0, Math.min(100, result.score)) / 100f, barBg.bottom);
    p.setShader(new LinearGradient(barFill.left, 0, barFill.right, 0, 0xFFFF8AB3, 0xFF8AB4FF, Shader.TileMode.CLAMP));
    c.drawRoundRect(barFill, 9, 9, p); p.setShader(null);
    y += 168;

    if (!coverGames.isEmpty()) {
        c.drawText("本周封面抓包", pad, y, accentPaint);
        y += 34;
        int gap = 22;
        int cw = (w - pad * 2 - gap * 2) / 3;
        int ch = 250;
        for (int i = 0; i < coverGames.size() && i < 3; i++) {
            Game g = coverGames.get(i);
            int x = pad + i * (cw + gap);
            drawGameCoverBlock(c, g, x, y, cw, ch);
        }
        y += ch + 88;
    }

    RectF roastCard = new RectF(pad, y, w - pad, y + 72 + roastLines.size() * 46);
    drawGlass(c, roastCard, 0x44111A36, 0x44FF8AB3, 30);
    int ty = y + 50;
    for (String line : roastLines) {
        c.drawText(line, pad + 28, ty, bodyPaint);
        ty += 46;
    }
    y = (int) roastCard.bottom + 54;

    c.drawText("本周抓包", pad, y, accentPaint);
    y += 44;
    if (highlights.isEmpty()) {
        y = drawBulletLine(c, "还没有抓到太多证据，欧尼酱下周多玩点再来挨点评。", pad, y, smallPaint);
    } else {
        for (String item : highlights) y = drawBulletLine(c, item, pad, y, smallPaint);
    }
    y += 28;
    c.drawText("下周处方", pad, y, accentPaint);
    y += 44;
    if (advice.isEmpty()) {
        y = drawBulletLine(c, "保持记录，别让清坑计划又被新坑偷袭。", pad, y, smallPaint);
    } else {
        for (String item : advice) y = drawBulletLine(c, item, pad, y, smallPaint);
    }

    if (!oneLines.isEmpty()) {
        y += 28;
        for (String line : oneLines) {
            c.drawText(line, pad, y, accentPaint);
            y += 40;
        }
    }
    Paint footer = aiPaint(24, 0x88DEE8FF, false);
    c.drawText("Generated by YukiHub", pad, h - 54, footer);
    return bitmap;
}

private Bitmap buildAiReviewNotebookBitmap(AiReviewResult result) {
    final int w = 1080;
    final int pad = 74;
    Paint titlePaint = aiPaint(54, 0xFF6E4034, true);
    Paint subPaint = aiPaint(28, 0xFF8B7468, false);
    Paint bodyPaint = aiPaint(32, 0xFF4D463F, false);
    Paint smallPaint = aiPaint(25, 0xFF7A6B61, false);
    Paint accentPaint = aiPaint(31, 0xFFE06B60, true);
    List<String> roastLines = wrapText(result.roast, bodyPaint, w - pad * 2 - 54);
    List<String> subtitleLines = wrapText(result.subtitle, subPaint, w - pad * 2);
    List<String> oneLines = wrapText(result.oneLine, accentPaint, w - pad * 2);
    List<Game> coverGames = findAiReviewCoverGames(result, 3);
    int h = 900 + subtitleLines.size() * 38 + roastLines.size() * 42 + oneLines.size() * 44;
    h += coverGames.isEmpty() ? 80 : 370;
    h += estimateNotebookBulletSectionHeight(result.highlights, smallPaint, "证据不足，下周多记录一点再来写手账。");
    h += estimateNotebookBulletSectionHeight(result.advice, smallPaint, "保持记录，清坑和回味都值得被好好写下来。");
    h = Math.max(1720, Math.min(5200, h));
    Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
    Canvas c = new Canvas(bitmap);
    Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    c.drawColor(0xFFFFFBF2);
    drawDottedPaper(c, w, h);

    RectF header = new RectF(pad + 90, 82, w - pad - 90, 210);
    drawDashedRoundRect(c, header, 0xFF6E4034, 3f, 28f);
    Paint tape = new Paint(Paint.ANTI_ALIAS_FLAG);
    tape.setColor(0x55F5A29B);
    c.drawRoundRect(new RectF(w / 2f - 96, 62, w / 2f + 96, 92), 5, 5, tape);
    Paint logoPaint = aiPaint(40, 0xFF9A4E42, true);
    c.drawText("🎮 YukiHub 游戏手账", header.left + 54, 160, logoPaint);

    int y = 300;
    c.drawText(emptyText(result.title, "AI 周点评"), pad, y, titlePaint);
    y += 44;
    for (String line : subtitleLines) { c.drawText(line, pad, y, subPaint); y += 38; }
    y += 32;

    WeeklyPlayStats stats = buildWeeklyPlayStats();
    int cardW = 250;
    drawNotebookStatCard(c, pad + 54, y, cardW, 170, "游玩次数", String.valueOf(stats.sessionCount));
    drawNotebookStatCard(c, pad + 54 + cardW + 54, y + 10, cardW, 170, emptyText(result.scoreName, "沉迷指数"), Math.max(0, Math.min(100, result.score)) + "分");
    drawNotebookStatCard(c, pad + 54 + (cardW + 54) * 2, y, cardW, 170, "游戏数", String.valueOf(stats.gameCount()));
    y += 225;

    if (!coverGames.isEmpty()) {
        c.drawText("本周封面贴纸", pad, y, accentPaint);
        y += 34;
        int gap = 22;
        int cw = (w - pad * 2 - gap * 2) / 3;
        int ch = 230;
        for (int i = 0; i < coverGames.size() && i < 3; i++) {
            drawNotebookCoverBlock(c, coverGames.get(i), pad + i * (cw + gap), y, cw, ch);
        }
        y += ch + 78;
    }

    RectF note = new RectF(pad, y, w - pad, y + 70 + roastLines.size() * 42);
    p.setColor(0xFFFFF3D8);
    c.drawRoundRect(note, 22, 22, p);
    drawDashedRoundRect(c, note, 0xCCDF8B7A, 2.5f, 22f);
    int ty = y + 48;
    for (String line : roastLines) { c.drawText(line, pad + 28, ty, bodyPaint); ty += 42; }
    y = (int) note.bottom + 56;

    c.drawText("本周抓包", pad, y, accentPaint); y += 44;
    if (result.highlights.isEmpty()) y = drawNotebookBullet(c, "证据不足，下周多记录一点再来写手账。", pad, y, smallPaint);
    else for (String item : result.highlights) y = drawNotebookBullet(c, item, pad, y, smallPaint);
    y += 28;
    c.drawText("下周处方", pad, y, accentPaint); y += 44;
    if (result.advice.isEmpty()) y = drawNotebookBullet(c, "保持记录，清坑和回味都值得被好好写下来。", pad, y, smallPaint);
    else for (String item : result.advice) y = drawNotebookBullet(c, item, pad, y, smallPaint);
    if (!oneLines.isEmpty()) {
        y += 30;
        for (String line : oneLines) { c.drawText(line, pad, y, accentPaint); y += 40; }
    }
    Paint footer = aiPaint(23, 0x998B7468, false);
    c.drawText("Generated by YukiHub · Notebook Template", pad, h - 54, footer);
    return bitmap;
}

private Bitmap buildAiReviewMinimalBitmap(AiReviewResult result) {
    final int w = 1080;
    final int pad = 72;
    Paint titlePaint = aiPaint(56, 0xFF111827, true);
    Paint subPaint = aiPaint(28, 0xFF64748B, false);
    Paint bodyPaint = aiPaint(32, 0xFF1F2937, false);
    Paint smallPaint = aiPaint(25, 0xFF475569, false);
    Paint accentPaint = aiPaint(30, 0xFF2563EB, true);
    List<String> roastLines = wrapText(result.roast, bodyPaint, w - pad * 2);
    List<String> subtitleLines = wrapText(result.subtitle, subPaint, w - pad * 2);
    List<String> oneLines = wrapText(result.oneLine, accentPaint, w - pad * 2);
    List<Game> coverGames = findAiReviewCoverGames(result, 3);
    int h = 760 + subtitleLines.size() * 38 + roastLines.size() * 42 + oneLines.size() * 44;
    h += coverGames.isEmpty() ? 80 : 330;
    h += estimateMinimalBulletSectionHeight(result.highlights, smallPaint, "暂无明显亮点，继续记录后再分析。");
    h += estimateMinimalBulletSectionHeight(result.advice, smallPaint, "保持记录，按自己的节奏清坑。");
    h = Math.max(1480, Math.min(4800, h));
    Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
    Canvas c = new Canvas(bitmap);
    c.drawColor(0xFFF8FAFC);
    Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    p.setColor(0xFF111827);
    c.drawRect(0, 0, w, 18, p);
    p.setColor(0xFF2563EB);
    c.drawRect(pad, 70, pad + 92, 78, p);
    Paint badge = aiPaint(25, 0xFF64748B, false);
    c.drawText("YukiHub / AI WEEKLY REVIEW", pad, 122, badge);
    int y = 205;
    c.drawText(emptyText(result.title, "AI 周点评"), pad, y, titlePaint);
    y += 46;
    for (String line : subtitleLines) { c.drawText(line, pad, y, subPaint); y += 38; }
    y += 36;

    Paint scorePaint = aiPaint(64, 0xFF111827, true);
    c.drawText(String.valueOf(Math.max(0, Math.min(100, result.score))), pad, y + 66, scorePaint);
    c.drawText("/100", pad + 108, y + 62, aiPaint(28, 0xFF64748B, true));
    c.drawText(emptyText(result.scoreName, "指数"), pad + 210, y + 40, accentPaint);
    RectF line = new RectF(pad + 210, y + 66, w - pad, y + 78);
    p.setColor(0xFFE2E8F0); c.drawRoundRect(line, 6, 6, p);
    p.setColor(0xFF2563EB); c.drawRoundRect(new RectF(line.left, line.top, line.left + line.width() * Math.max(0, Math.min(100, result.score)) / 100f, line.bottom), 6, 6, p);
    y += 140;

    if (!coverGames.isEmpty()) {
        int gap = 18;
        int cw = (w - pad * 2 - gap * 2) / 3;
        int ch = 220;
        for (int i = 0; i < coverGames.size() && i < 3; i++) drawMinimalCoverBlock(c, coverGames.get(i), pad + i * (cw + gap), y, cw, ch);
        y += ch + 58;
    }

    c.drawText("COMMENT", pad, y, accentPaint); y += 48;
    for (String l : roastLines) { c.drawText(l, pad, y, bodyPaint); y += 42; }
    y += 40;
    c.drawText("HIGHLIGHTS", pad, y, accentPaint); y += 42;
    if (result.highlights.isEmpty()) y = drawMinimalLine(c, "暂无明显亮点，继续记录后再分析。", pad, y, smallPaint);
    else for (String item : result.highlights) y = drawMinimalLine(c, item, pad, y, smallPaint);
    y += 24;
    c.drawText("NEXT", pad, y, accentPaint); y += 42;
    if (result.advice.isEmpty()) y = drawMinimalLine(c, "保持记录，按自己的节奏清坑。", pad, y, smallPaint);
    else for (String item : result.advice) y = drawMinimalLine(c, item, pad, y, smallPaint);
    if (!oneLines.isEmpty()) { y += 28; for (String l : oneLines) { c.drawText(l, pad, y, accentPaint); y += 40; } }
    c.drawText("Generated by YukiHub", pad, h - 54, aiPaint(23, 0xFF94A3B8, false));
    return bitmap;
}

private Paint aiPaint(float size, int color, boolean bold) {
    Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    p.setColor(color);
    p.setTextSize(size);
    p.setTypeface(Typeface.create(Typeface.DEFAULT, bold ? Typeface.BOLD : Typeface.NORMAL));
    return p;
}

private void drawGlass(Canvas c, RectF rect, int color, int stroke, float radius) {
    Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    p.setColor(color);
    c.drawRoundRect(rect, radius, radius, p);
    p.setStyle(Paint.Style.STROKE);
    p.setStrokeWidth(2f);
    p.setColor(stroke);
    c.drawRoundRect(rect, radius, radius, p);
    p.setStyle(Paint.Style.FILL);
}

private void drawDottedPaper(Canvas c, int w, int h) {
    Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    dot.setColor(0x22BDA89A);
    for (int y = 24; y < h; y += 28) {
        for (int x = 24; x < w; x += 28) {
            c.drawCircle(x, y, 2.2f, dot);
        }
    }
}

private void drawDashedRoundRect(Canvas c, RectF rect, int color, float stroke, float radius) {
    Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    p.setStyle(Paint.Style.STROKE);
    p.setStrokeWidth(stroke);
    p.setColor(color);
    p.setPathEffect(new DashPathEffect(new float[]{12f, 8f}, 0f));
    c.drawRoundRect(rect, radius, radius, p);
    p.setPathEffect(null);
    p.setStyle(Paint.Style.FILL);
}

private void drawNotebookStatCard(Canvas c, int x, int y, int w, int h, String label, String value) {
    RectF r = new RectF(x, y, x + w, y + h);
    Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    p.setColor(0x33000000);
    c.drawRoundRect(new RectF(r.left + 5, r.top + 7, r.right + 5, r.bottom + 7), 12, 12, p);
    p.setColor(0xFFFFFFFF);
    c.drawRoundRect(r, 12, 12, p);
    drawDashedRoundRect(c, r, 0x889A4E42, 2f, 12f);
    Paint valuePaint = aiPaint(42, 0xFFE06B60, true);
    Paint labelPaint = aiPaint(24, 0xFF8B7468, false);
    Rect vb = new Rect();
    valuePaint.getTextBounds(value, 0, value.length(), vb);
    c.drawText(value, x + (w - vb.width()) / 2f, y + 72, valuePaint);
    Rect lb = new Rect();
    labelPaint.getTextBounds(label, 0, label.length(), lb);
    c.drawText(label, x + (w - lb.width()) / 2f, y + 118, labelPaint);
}

private int estimateNotebookBulletSectionHeight(List<String> items, Paint paint, String fallback) {
    int h = 44 + 28;
    if (items == null || items.isEmpty()) return h + estimateWrappedLineHeight(fallback, paint, 1080 - 74 * 2 - 44, 36, 10);
    for (String item : items) h += estimateWrappedLineHeight(item, paint, 1080 - 74 * 2 - 44, 36, 10);
    return h;
}

private int estimateMinimalBulletSectionHeight(List<String> items, Paint paint, String fallback) {
    int h = 42 + 24;
    if (items == null || items.isEmpty()) return h + estimateWrappedLineHeight(fallback, paint, 1080 - 72 * 2 - 34, 34, 8);
    for (String item : items) h += estimateWrappedLineHeight(item, paint, 1080 - 72 * 2 - 34, 34, 8);
    return h;
}

private int estimateWrappedLineHeight(String text, Paint paint, float maxWidth, int lineHeight, int bottomPadding) {
    int lines = Math.max(1, wrapText(text, paint, maxWidth).size());
    return lines * lineHeight + bottomPadding;
}

private int drawNotebookBullet(Canvas c, String text, int x, int y, Paint paint) {
    List<String> lines = wrapText(text, paint, 1080 - x * 2 - 44);
    Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    p.setColor(0xFFE9828A);
    c.drawCircle(x + 12, y - 10, 7, p);
    int ty = y;
    for (String line : lines) {
        c.drawText(line, x + 38, ty, paint);
        ty += 36;
    }
    return ty + 10;
}

private int drawMinimalLine(Canvas c, String text, int x, int y, Paint paint) {
    List<String> lines = wrapText(text, paint, 1080 - x * 2 - 34);
    Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    p.setColor(0xFF2563EB);
    c.drawRect(x, y - 18, x + 14, y - 14, p);
    int ty = y;
    for (String line : lines) {
        c.drawText(line, x + 28, ty, paint);
        ty += 34;
    }
    return ty + 8;
}

private void drawNotebookCoverBlock(Canvas c, Game game, int x, int y, int w, int h) {
    c.save();
    float angle = ((x / Math.max(1, w)) % 2 == 0) ? -2.5f : 2.0f;
    c.rotate(angle, x + w / 2f, y + h / 2f);
    RectF backing = new RectF(x - 8, y - 8, x + w + 8, y + h + 34);
    Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    p.setColor(0x33000000);
    c.drawRoundRect(new RectF(backing.left + 5, backing.top + 7, backing.right + 5, backing.bottom + 7), 18, 18, p);
    p.setColor(0xFFFFFFFF);
    c.drawRoundRect(backing, 18, 18, p);
    Bitmap cover = decodeGameCoverBitmap(game);
    RectF coverRect = new RectF(x, y, x + w, y + h);
    if (cover != null) {
        Path clip = new Path();
        clip.addRoundRect(coverRect, 14, 14, Path.Direction.CW);
        c.save();
        c.clipPath(clip);
        drawCenterCrop(c, cover, coverRect);
        c.restore();
        cover.recycle();
    } else {
        p.setColor(0xFFF6E7D5);
        c.drawRoundRect(coverRect, 14, 14, p);
        Paint ph = aiPaint(52, 0xAA9A4E42, true);
        String initial = initials(game == null ? "YH" : game.title);
        Rect b = new Rect();
        ph.getTextBounds(initial, 0, initial.length(), b);
        c.drawText(initial, x + (w - b.width()) / 2f, y + h / 2f, ph);
    }
    Paint labelPaint = aiPaint(22, 0xFF6E4034, true);
    String title = game == null ? "未命名游戏" : emptyText(game.title, "未命名游戏");
    List<String> lines = wrapText(title, labelPaint, w - 14);
    if (!lines.isEmpty()) c.drawText(lines.get(0), x + 7, y + h + 27, labelPaint);
    c.restore();
}

private void drawMinimalCoverBlock(Canvas c, Game game, int x, int y, int w, int h) {
    RectF rect = new RectF(x, y, x + w, y + h);
    Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    p.setColor(0xFFE2E8F0);
    c.drawRoundRect(rect, 18, 18, p);
    Bitmap cover = decodeGameCoverBitmap(game);
    if (cover != null) {
        Path clip = new Path();
        clip.addRoundRect(rect, 18, 18, Path.Direction.CW);
        c.save();
        c.clipPath(clip);
        drawCenterCrop(c, cover, rect);
        c.restore();
        cover.recycle();
    } else {
        Paint ph = aiPaint(48, 0xFF94A3B8, true);
        String initial = initials(game == null ? "YH" : game.title);
        Rect b = new Rect();
        ph.getTextBounds(initial, 0, initial.length(), b);
        c.drawText(initial, x + (w - b.width()) / 2f, y + h / 2f, ph);
    }
    p.setColor(0xAA111827);
    c.drawRoundRect(new RectF(x, y + h - 56, x + w, y + h), 18, 18, p);
    Paint labelPaint = aiPaint(22, 0xFFFFFFFF, true);
    String title = game == null ? "未命名游戏" : emptyText(game.title, "未命名游戏");
    List<String> lines = wrapText(title, labelPaint, w - 20);
    if (!lines.isEmpty()) c.drawText(lines.get(0), x + 10, y + h - 20, labelPaint);
}

private void drawRoundText(Canvas c, String text, int x, int baseline, Paint textPaint, int bgColor, int radius, int hp, int vp) {
    Rect bounds = new Rect();
    textPaint.getTextBounds(text, 0, text.length(), bounds);
    RectF r = new RectF(x, baseline - bounds.height() - vp, x + bounds.width() + hp * 2, baseline + vp);
    Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    p.setColor(bgColor);
    c.drawRoundRect(r, radius, radius, p);
    c.drawText(text, x + hp, baseline, textPaint);
}

private int drawBulletLine(Canvas c, String text, int x, int y, Paint paint) {
    List<String> lines = wrapText(text, paint, 1080 - x * 2 - 36);
    Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    dot.setColor(0xFFFF8AB3);
    c.drawCircle(x + 10, y - 9, 6, dot);
    int ty = y;
    for (int i = 0; i < lines.size(); i++) {
        c.drawText(lines.get(i), x + 34, ty, paint);
        ty += 36;
    }
    return ty + 10;
}

private List<String> wrapText(String text, Paint paint, float maxWidth) {
    List<String> lines = new ArrayList<>();
    String s = text == null ? "" : text.trim();
    if (s.isEmpty()) return lines;
    StringBuilder line = new StringBuilder();
    for (int i = 0; i < s.length(); i++) {
        char ch = s.charAt(i);
        if (ch == '\n') {
            if (line.length() > 0) { lines.add(line.toString()); line.setLength(0); }
            continue;
        }
        line.append(ch);
        if (paint.measureText(line.toString()) > maxWidth) {
            line.deleteCharAt(line.length() - 1);
            if (line.length() > 0) lines.add(line.toString());
            line.setLength(0);
            line.append(ch);
        }
    }
    if (line.length() > 0) lines.add(line.toString());
    return lines;
}

private List<Game> findAiReviewCoverGames(AiReviewResult result, int max) {
    List<Game> list = new ArrayList<>();
    if (result != null) {
        for (AiReviewResult.GameComment gc : result.topGamesComment) {
            if (gc == null || gc.game == null || gc.game.trim().isEmpty()) continue;
            Game g = findGameByTitleForAi(gc.game);
            if (g != null && !list.contains(g)) list.add(g);
            if (list.size() >= max) return list;
        }
    }
    WeeklyPlayStats stats = buildWeeklyPlayStats();
    for (String title : stats.topGames.keySet()) {
        Game g = findGameByTitleForAi(title);
        if (g != null && !list.contains(g)) list.add(g);
        if (list.size() >= max) break;
    }
    return list;
}

private Game findGameByTitleForAi(String title) {
    if (title == null) return null;
    String q = title.trim();
    if (q.isEmpty()) return null;
    for (Game g : delegate.allGames()) {
        if (g == null || g.title == null) continue;
        if (g.title.equals(q)) return g;
    }
    String lower = q.toLowerCase(Locale.ROOT);
    for (Game g : delegate.allGames()) {
        if (g == null || g.title == null) continue;
        String t = g.title.toLowerCase(Locale.ROOT);
        if (t.contains(lower) || lower.contains(t)) return g;
    }
    return null;
}

private void drawGameCoverBlock(Canvas c, Game game, int x, int y, int w, int h) {
    RectF rect = new RectF(x, y, x + w, y + h);
    Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    p.setColor(0x33111A36);
    c.drawRoundRect(rect, 26, 26, p);
    Bitmap cover = decodeGameCoverBitmap(game);
    if (cover != null) {
        Path clip = new Path();
        clip.addRoundRect(rect, 26, 26, Path.Direction.CW);
        c.save();
        c.clipPath(clip);
        drawCenterCrop(c, cover, rect);
        c.restore();
        cover.recycle();
        p.setShader(new LinearGradient(0, y + h * 0.55f, 0, y + h, 0x0010182F, 0xDD10182F, Shader.TileMode.CLAMP));
        c.drawRoundRect(rect, 26, 26, p);
        p.setShader(null);
    } else {
        Paint ph = aiPaint(58, 0x99FFFFFF, true);
        String initial = initials(game == null ? "YH" : game.title);
        Rect b = new Rect();
        ph.getTextBounds(initial, 0, initial.length(), b);
        c.drawText(initial, x + (w - b.width()) / 2f, y + h / 2f, ph);
    }
    Paint namePaint = aiPaint(24, 0xFFFFFFFF, true);
    String title = game == null ? "未命名游戏" : emptyText(game.title, "未命名游戏");
    List<String> lines = wrapText(title, namePaint, w - 24);
    int ty = y + h - 46;
    for (int i = Math.max(0, lines.size() - 2); i < lines.size(); i++) {
        c.drawText(lines.get(i), x + 12, ty, namePaint);
        ty += 28;
    }
}

private Bitmap decodeGameCoverBitmap(Game game) {
    try {
        String uri = safeCoverUri(game);
        if (uri == null || uri.trim().isEmpty()) return null;
        if (uri.startsWith("http://") || uri.startsWith("https://")) return null;
        Uri u = Uri.parse(uri);
        if ("file".equalsIgnoreCase(u.getScheme())) {
            return BitmapFactory.decodeFile(u.getPath());
        }
        try (InputStream in = activity.getContentResolver().openInputStream(u)) {
            return BitmapFactory.decodeStream(in);
        }
    } catch (Throwable t) {
        return null;
    }
}

private void drawCenterCrop(Canvas c, Bitmap bitmap, RectF dst) {
    if (bitmap == null) return;
    float scale = Math.max(dst.width() / bitmap.getWidth(), dst.height() / bitmap.getHeight());
    float sw = dst.width() / scale;
    float sh = dst.height() / scale;
    float sx = (bitmap.getWidth() - sw) / 2f;
    float sy = (bitmap.getHeight() - sh) / 2f;
    Rect src = new Rect(Math.max(0, (int) sx), Math.max(0, (int) sy), Math.min(bitmap.getWidth(), (int) (sx + sw)), Math.min(bitmap.getHeight(), (int) (sy + sh)));
    c.drawBitmap(bitmap, src, dst, new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG));
}

private void showAiReviewHistoryDialogImpl() {
    List<AiReviewHistoryStore.Entry> entries = AiReviewHistoryStore.load(activity);
    LinearLayout root = new LinearLayout(activity);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setPadding(dp(16), dp(12), dp(16), dp(4));
    if (entries.isEmpty()) {
        TextView empty = new TextView(activity);
        empty.setText("还没有 AI 周点评历史。生成一次后，这里会保存最近 20 条记录。");
        empty.setTextColor(getColorCompat(R.color.yh_text_muted));
        empty.setTextSize(12);
        empty.setLineSpacing(dp(2), 1.0f);
        empty.setBackgroundResource(R.drawable.bg_input);
        empty.setPadding(dp(12), dp(10), dp(12), dp(10));
        root.addView(empty, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    } else {
        for (AiReviewHistoryStore.Entry e : entries) {
            TextView item = new TextView(activity);
            item.setText(e.displayTitle() + "\n" + e.displaySummary() + "\n" + (e.result == null ? "" : e.result.subtitle));
            item.setTextColor(getColorCompat(R.color.yh_text));
            item.setTextSize(12);
            item.setLineSpacing(dp(2), 1.0f);
            item.setBackgroundResource(R.drawable.bg_input);
            item.setPadding(dp(12), dp(9), dp(12), dp(9));
            item.setOnClickListener(v -> { playUiSound(0); showAiReviewHistoryDetail(e); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 0, 0, dp(7));
            root.addView(item, lp);
        }
    }
    ScrollView scroll = new ScrollView(activity);
    scroll.addView(root, new ScrollView.LayoutParams(ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
    AlertDialog dialog = new AlertDialog.Builder(activity)
            .setTitle("AI 周点评历史")
            .setView(scroll)
            .setNeutralButton("清空", null)
            .setNegativeButton("关闭", null)
            .show();
    styleAlertDialogDark(dialog);
    if (dialog.getWindow() != null) {
        dialog.getWindow().setLayout((int) (activity.getResources().getDisplayMetrics().widthPixels * 0.62f), (int) (activity.getResources().getDisplayMetrics().heightPixels * 0.78f));
    }
    dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
        new AlertDialog.Builder(activity)
                .setTitle("清空历史")
                .setMessage("确定要清空所有 AI 周点评历史吗？")
                .setPositiveButton("清空", (d, w) -> {
                    AiReviewHistoryStore.clear(activity);
                    dialog.dismiss();
                    Toast.makeText(activity, "AI 周点评历史已清空", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    });
}

private void showAiReviewHistoryDetail(AiReviewHistoryStore.Entry entry) {
    if (entry == null || entry.result == null) return;
    LinearLayout root = new LinearLayout(activity);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setPadding(dp(16), dp(12), dp(16), dp(8));
    TextView meta = new TextView(activity);
    meta.setText(entry.displaySummary());
    meta.setTextColor(getColorCompat(R.color.yh_text_muted));
    meta.setTextSize(11);
    meta.setPadding(0, 0, 0, dp(8));
    root.addView(meta);
    LinearLayout cardContainer = new LinearLayout(activity);
    cardContainer.setOrientation(LinearLayout.VERTICAL);
    renderAiReviewResult(cardContainer, entry.result);
    root.addView(cardContainer, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    ScrollView scroll = new ScrollView(activity);
    scroll.addView(root, new ScrollView.LayoutParams(ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
    AlertDialog dialog = new AlertDialog.Builder(activity)
            .setTitle(entry.displayTitle())
            .setView(scroll)
            .setNegativeButton("关闭", null)
            .show();
    styleAlertDialogDark(dialog);
    if (dialog.getWindow() != null) {
        dialog.getWindow().setLayout((int) (activity.getResources().getDisplayMetrics().widthPixels * 0.66f), (int) (activity.getResources().getDisplayMetrics().heightPixels * 0.82f));
    }
}

private TextView profileLabel(String text) {
    TextView v = new TextView(activity);
    v.setText(text);
    v.setTextColor(delegate.getColorCompat(R.color.yh_text));
    v.setTextSize(13);
    v.setTypeface(null, android.graphics.Typeface.BOLD);
    v.setPadding(0, 0, 0, delegate.dp(4));
    return v;
}

private EditText profileEdit(String value, String hint) {
    EditText v = new EditText(activity);
    v.setText(value == null ? "" : value);
    v.setHint(hint);
    v.setTextColor(delegate.getColorCompat(R.color.yh_text));
    v.setHintTextColor(delegate.getColorCompat(R.color.yh_text_muted));
    v.setTextSize(13);
    v.setSingleLine(true);
    v.setBackgroundResource(R.drawable.bg_input);
    v.setPadding(delegate.dp(10), 0, delegate.dp(10), 0);
    return v;
}
}
