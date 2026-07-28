package com.yuki.yukihub.translate;

import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.yuki.yukihub.R;

import java.util.ArrayList;
import java.util.List;

/**
 * 翻译控制页面。
 *
 * 提供以下功能入口：
 * - 启动/停止悬浮翻译服务
 * - 翻译模式选择（OCR 文本翻译 / 图片翻译）
 * - 源语言/目标语言选择
 * - OCR 引擎选择
 * - 翻译引擎选择
 * - 自动翻译开关和参数设置
 * - 结果显示模式
 * - 结果层外观（字体大小、穿透）
 * - 权限设置入口（悬浮窗、无障碍）
 */
public class TranslateControlActivity extends AppCompatActivity {

    private TranslationPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new TranslationPreferences(this);
        setContentView(R.layout.activity_translate_control);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("翻译设置");
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        buildContent();
    }

    @Override
    protected void onResume() {
        super.onResume();
        buildContent();
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    private void buildContent() {
        ScrollView scroll = findViewById(R.id.translateScrollContainer);
        if (scroll == null) {
            // Fallback: create dynamically if layout not found
            scroll = new ScrollView(this);
            scroll.setFillViewport(true);
            setContentView(scroll);
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setBackgroundColor(0xFF101522);
        root.setPadding(pad, pad, pad, pad);

        // === 服务状态 ===
        addSectionTitle(root, "悬浮翻译服务");
        addStatusRow(root);
        addServiceButtons(root);

        // === 权限 ===
        addSectionTitle(root, "权限设置");
        addPermissionRows(root);

        // === 语言 ===
        addSectionTitle(root, "语言设置");
        addLanguageSelector(root, "源语言", TranslationPreferences.KEY_SOURCE_LANG,
                new String[]{"zh", "en", "ja", "fr", "de", "es", "it", "pt"},
                new String[]{"中文", "英语", "日语", "法语", "德语", "西班牙语", "意大利语", "葡萄牙语"});
        addLanguageSelector(root, "目标语言", TranslationPreferences.KEY_TARGET_LANG,
                new String[]{"zh", "en", "ja", "ko"},
                new String[]{"中文", "英语", "日语", "韩语"});

        // === OCR ===
        addSectionTitle(root, "OCR 设置");
        addOcrEngineSelector(root);
        addOcrMergeModeSelector(root);

        // === 翻译引擎 ===
        addSectionTitle(root, "翻译引擎");
        addTextEngineSelector(root);

        // === 自动翻译 ===
        addSectionTitle(root, "自动翻译");
        addAutoTranslateSettings(root);

        // === 结果显示 ===
        addSectionTitle(root, "结果显示");
        addShowSourceModeSelector(root);
        addResultFontSizeSelector(root);
        addPenetrableSwitch(root);

        if (scroll.getChildCount() > 0) {
            scroll.removeAllViews();
        }
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    // ==================== UI 构建方法 ====================

    private void addSectionTitle(LinearLayout root, String title) {
        TextView tv = new TextView(this);
        tv.setText(title);
        tv.setTextColor(0xFF6750A4);
        tv.setTextSize(15);
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(20);
        lp.bottomMargin = dp(6);
        root.addView(tv, lp);
    }

    private void addStatusRow(LinearLayout root) {
        TextView tv = new TextView(this);
        boolean canOverlay = YukiTranslateService.canDrawOverlays(this);
        boolean accReady = ScreenshotServiceManager.isReady();
        StringBuilder sb = new StringBuilder();
        sb.append("悬浮窗权限：").append(canOverlay ? "✅ 已开启" : "❌ 未开启").append("\n");
        sb.append("无障碍截图服务：").append(accReady ? "✅ 已连接" : "❌ 未连接");
        tv.setText(sb.toString());
        tv.setTextColor(0xFFCCCCCC);
        tv.setTextSize(12);
        tv.setLineSpacing(dp(2), 1.0f);
        root.addView(tv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void addServiceButtons(LinearLayout root) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(8), 0, dp(4));

        Button startBtn = new Button(this);
        startBtn.setText("启动悬浮翻译");
        styleButton(startBtn);
        startBtn.setOnClickListener(v -> {
            if (!YukiTranslateService.canDrawOverlays(this)) {
                Toast.makeText(this, "请先开启悬浮窗权限", Toast.LENGTH_SHORT).show();
                return;
            }
            YukiTranslateService.start(this);
            Toast.makeText(this, "悬浮翻译已启动", Toast.LENGTH_SHORT).show();
        });
        LinearLayout.LayoutParams startLp = new LinearLayout.LayoutParams(0, dp(40), 1);
        startLp.rightMargin = dp(4);
        row.addView(startBtn, startLp);

        Button stopBtn = new Button(this);
        stopBtn.setText("停止");
        styleButton(stopBtn);
        stopBtn.setOnClickListener(v -> {
            YukiTranslateService.stop(this);
            Toast.makeText(this, "悬浮翻译已停止", Toast.LENGTH_SHORT).show();
        });
        LinearLayout.LayoutParams stopLp = new LinearLayout.LayoutParams(0, dp(40), 1);
        stopLp.leftMargin = dp(4);
        row.addView(stopBtn, stopLp);

        root.addView(row);
    }

    private void addPermissionRows(LinearLayout root) {
        Button overlayBtn = new Button(this);
        overlayBtn.setText("悬浮窗权限设置");
        styleButton(overlayBtn);
        overlayBtn.setOnClickListener(v -> {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                try {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
                    intent.setData(android.net.Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                } catch (Throwable t) {
                    Toast.makeText(this, "无法打开设置", Toast.LENGTH_SHORT).show();
                }
            }
        });
        root.addView(overlayBtn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(40)));

        Button accBtn = new Button(this);
        accBtn.setText("无障碍服务设置");
        styleButton(accBtn);
        accBtn.setOnClickListener(v -> {
            try {
                Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
                startActivity(intent);
            } catch (Throwable t) {
                Toast.makeText(this, "无法打开设置", Toast.LENGTH_SHORT).show();
            }
        });
        LinearLayout.LayoutParams accLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(40));
        accLp.topMargin = dp(4);
        root.addView(accBtn, accLp);
    }

    private void addModeSelector(LinearLayout root) {
        String[] values = {"OCR_TEXT", "IMAGE_TRANSLATION"};
        String[] labels = {"OCR 文本翻译", "图片翻译"};
        String current = prefs.getMode();
        for (int i = 0; i < values.length; i++) {
            android.widget.RadioButton rb = new android.widget.RadioButton(this);
            rb.setText(labels[i]);
            rb.setTextColor(0xFFFFFFFF);
            rb.setChecked(values[i].equals(current));
            final String val = values[i];
            rb.setOnCheckedChangeListener((button, checked) -> {
                if (checked) prefs.putString(TranslationPreferences.KEY_MODE, val);
            });
            root.addView(rb);
        }
    }

    private void addLanguageSelector(LinearLayout root, String label, String key,
                                      String[] values, String[] labels) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextColor(0xFFCCCCCC);
        tv.setTextSize(12);
        root.addView(tv);

        android.widget.Spinner spinner = new android.widget.Spinner(this);
android.widget.ArrayAdapter<String> adapter = themedAdapter(labels);
        spinner.setAdapter(adapter);
        String current = prefs.raw().getString(key, values[0]);
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(current)) {
                spinner.setSelection(i);
                break;
            }
        }
        spinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                prefs.putString(key, values[position]);
            }
            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });
        root.addView(spinner, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(40)));
    }

    private void addOcrEngineSelector(LinearLayout root) {
        TextView tv = new TextView(this);
        tv.setText("PaddleOCR（百度开源 PP-OCRv6，离线默认）");
        tv.setTextColor(0xFFFFFFFF);
        tv.setTextSize(14);
        root.addView(tv);
    }

    private void addOcrMergeModeSelector(LinearLayout root) {
        TextView tv = new TextView(this);
        tv.setText("OCR 文本合并模式");
        tv.setTextColor(0xFFB8C7D9);
        tv.setTextSize(12);
        root.addView(tv);

        String[] labels = {"保留原始文本", "按段落换行", "直接合并（推荐）"};
        int current = prefs.getOcrMergeMode();
        android.widget.Spinner spinner = new android.widget.Spinner(this);
        android.widget.ArrayAdapter<String> adapter = themedAdapter(labels);
        spinner.setAdapter(adapter);
        spinner.setSelection(Math.min(current, labels.length - 1));
        spinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                prefs.putInt(TranslationPreferences.KEY_OCR_MERGE_MODE, position);
            }
            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });
        root.addView(spinner, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(40)));
    }

    private void addTextEngineSelector(LinearLayout root) {
        String[] values = {"BING", "NIUTRANS", "UNIAI", "VOLC", "AZURE", "DEEPL", "BAIDU", "TENCENT", "CUSTOM"};
        String[] labels = {"Bing 翻译（免费，无需 Key）", "小牛翻译", "聚合 AI 翻译", "火山引擎", "Azure AI 翻译", "DeepL 翻译", "百度翻译", "腾讯云", "自定义 API"};
        String current = prefs.getTextEngine();
        android.widget.Spinner spinner = new android.widget.Spinner(this);
        android.widget.ArrayAdapter<String> adapter = themedAdapter(labels);
        spinner.setAdapter(adapter);
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(current)) {
                spinner.setSelection(i);
                break;
            }
        }

        // 引导和配置区域容器（根据选择的引擎动态切换内容）
        final LinearLayout configContainer = new LinearLayout(this);
        configContainer.setOrientation(LinearLayout.VERTICAL);
        configContainer.setPadding(0, dp(8), 0, 0);

        spinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                prefs.putString(TranslationPreferences.KEY_TEXT_ENGINE, values[position]);
                configContainer.removeAllViews();
                renderEngineGuide(configContainer, values[position]);
            }
            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });

        root.addView(spinner, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(40)));
        root.addView(configContainer);
        // 首次渲染当前引擎的引导
        configContainer.post(() -> {
            configContainer.removeAllViews();
            renderEngineGuide(configContainer, current);
        });
    }

    private void renderEngineGuide(LinearLayout container, String engine) {
        if (engine == null) return;
        switch (engine) {
            case "BING":
                addGuideText(container, "Bing 翻译\n\n基于必应网页版接口模拟实现，完全免费，无需申请 API Key。\n\n优点：零配置开箱即用。\n缺点：依赖网页结构提取 Token，稳定性较低，高频使用可能被限流。");
                break;
            case "NIUTRANS":
                addGuideText(container, "小牛翻译（NiuTrans）\n\n东北大学自然语言处理实验室出品，对日语翻译效果优秀。\n\n免费额度：每天 20 万字符免费调用。\n需要申请 API-KEY。");
                addConfigButton(container, "❓ 这是什么？", () -> showApiIntroDialog(
                        "小牛翻译",
                        "YukiHub 可以使用小牛翻译的 API 来提供翻译服务，这意味着你需要从小牛翻译平台申请 API。\n\n小牛翻译提供免费额度，每天有 20 万字符的免费调用次数。\n\n小牛翻译由东北大学自然语言处理实验室开发，在日语翻译方面效果较好。",
                        "https://niutrans.com/",
                        "https://www.google.com/search?q=小牛翻译+API+申请教程"
                ));
                boolean hasNiu = !prefs.raw().getString(TranslationPreferences.KEY_NIUTRANS_APIKEY, "").isEmpty();
                addConfigButton(container, hasNiu ? "✅ 已配置 API-KEY（点击修改）" : "⚙️ 配置 API-KEY", () -> showSingleKeyDialog(
                        "小牛翻译 API-KEY", TranslationPreferences.KEY_NIUTRANS_APIKEY, "请输入 API-KEY", "https://niutrans.com/"
                ));
                break;
            case "UNIAI":
                addGuideText(container, "聚合 AI 翻译\n\n支持所有兼容 OpenAI Chat Completions 格式的 API，包括 ChatGPT、通义千问（Qwen）、DeepSeek、Kimi 等。\n\n只要 AI 接口兼容 OpenAI 接口规范，就可以接入。翻译效果优秀，适合长文和上下文连贯场景。");
                addConfigButton(container, "❓ 这是什么？", () -> showApiIntroDialog(
                        "聚合 AI 翻译",
                        "YukiHub 可以使用主流 AI 模型进行翻译（只要兼容 OpenAI API 规范），包括 ChatGPT、通义千问（Qwen）、DeepSeek 等。\n\n这些 API 大多有免费额度，具体取决于各平台的政策。\n\n相比本地翻译，使用云 AI API 的翻译效果通常更好，尤其在上下文连贯性和术语一致性方面表现突出。",
                        "https://platform.openai.com/api-keys",
                        "https://www.google.com/search?q=OpenAI兼容+API+申请教程"
                ));
                boolean hasAi = !prefs.raw().getString(TranslationPreferences.KEY_OPENAI_APIKEY, "").isEmpty();
                addConfigButton(container, hasAi ? "✅ 已配置（点击修改）" : "⚙️ 配置 API", () -> showOpenAiConfigDialog());
                break;
            case "VOLC":
                addGuideText(container, "火山引擎（Volc Engine）\n\n字节跳动旗下翻译 API，支持多语种。\n\n免费额度：每月 200 万字符免费调用。\n需要 Access Key ID 和 Secret Access Key。");
                addConfigButton(container, "❓ 这是什么？", () -> showApiIntroDialog(
                        "火山引擎",
                        "YukiHub 可以使用火山引擎的翻译 API 来提供翻译服务，这意味着你需要从火山引擎平台申请 API。\n\n火山引擎提供免费额度，每月有 200 万字符的免费调用次数。\n\n火山引擎是字节跳动旗下的云服务平台，翻译质量稳定。",
                        "https://www.volcengine.com/",
                        "https://www.google.com/search?q=火山引擎+翻译API+申请教程"
                ));
                boolean hasVolc = !prefs.raw().getString(TranslationPreferences.KEY_VOLC_AK, "").isEmpty();
                addConfigButton(container, hasVolc ? "✅ 已配置（点击修改）" : "⚙️ 配置 Access Key", () -> showDualKeyDialog(
                        "火山引擎", TranslationPreferences.KEY_VOLC_AK, TranslationPreferences.KEY_VOLC_SK,
                        "Access Key ID", "Secret Access Key", "https://www.volcengine.com/"
                ));
                break;
            case "AZURE":
                addGuideText(container, "Azure AI 翻译\n\n微软 Azure AI Translator，支持 100+ 语种。\n\n免费额度：每月 200 万字符免费调用。\n需要申请 KEY。");
                addConfigButton(container, "❓ 这是什么？", () -> showApiIntroDialog(
                        "Azure AI 翻译",
                        "YukiHub 可以使用微软 Azure AI Translator 的 API 来提供翻译服务，这意味着你需要从 Azure 平台申请 API。\n\nAzure AI Translator 每月有 200 万字符的免费调用额度，支持 100 多种语言。\n\nAzure 是微软旗下的云服务平台，翻译质量稳定可靠。",
                        "https://portal.azure.com/",
                        "https://www.google.com/search?q=Azure+AI+Translator+申请教程"
                ));
                boolean hasAzure = !prefs.raw().getString(TranslationPreferences.KEY_AZURE_KEY, "").isEmpty();
                addConfigButton(container, hasAzure ? "✅ 已配置 KEY（点击修改）" : "⚙️ 配置 KEY", () -> showSingleKeyDialog(
                        "Azure AI 翻译 KEY", TranslationPreferences.KEY_AZURE_KEY, "请输入 Subscription Key", "https://portal.azure.com/"
                ));
                break;
            case "DEEPL":
                addGuideText(container, "DeepL 翻译\n\nDeepL 以高翻译质量著称，尤其擅长日语、英语、德语等语种。\n\n免费套餐：每月 50 万字符免费。\n需要申请 Host 和 API Key。");
                addConfigButton(container, "❓ 这是什么？", () -> showApiIntroDialog(
                        "DeepL 翻译",
                        "YukiHub 可以使用 DeepL 的 API 来提供翻译服务，这意味着你需要从 DeepL 平台申请 API Key。\n\nDeepL 提供免费套餐（Free 计划），每月有 50 万字符的免费调用额度。\n\n相比其他翻译 API，DeepL 在文学性和上下文连贯性方面表现更佳。\n\nHost 填写：免费版填 api-free.deepl.com，付费版填 api.deepl.com。",
                        "https://www.deepl.com/pro-api",
                        "https://www.google.com/search?q=DeepL+API+申请教程"
                ));
                boolean hasDeepl = !prefs.raw().getString(TranslationPreferences.KEY_DEEPL_APIKEY, "").isEmpty();
                addConfigButton(container, hasDeepl ? "✅ 已配置（点击修改）" : "⚙️ 配置 Host 和 API Key", () -> showDeepLConfigDialog());
                break;
            case "BAIDU":
                addGuideText(container, "百度翻译 API\n\n标准版免费，QPS=1，每月 100 万字符免费调用额度。\n需要 APP ID 和密钥。");
                addConfigButton(container, "❓ 这是什么？", () -> showApiIntroDialog(
                        "百度翻译 API",
                        "YukiHub 可以使用百度翻译的 API 来提供翻译服务，这意味着你需要从百度翻译开放平台申请 API。\n\n百度翻译标准版免费，QPS 限制为 1 次/秒，每月有 100 万字符的免费调用额度。\n\n对于中文相关的翻译，百度翻译通常表现不错。",
                        "https://fanyi-api.baidu.com/",
                        "https://www.google.com/search?q=百度翻译API+申请教程"
                ));
                boolean hasBaidu = !prefs.raw().getString(TranslationPreferences.KEY_BAIDU_APPID, "").isEmpty();
                addConfigButton(container, hasBaidu ? "✅ 已配置（点击修改）" : "⚙️ 配置 APP ID 和密钥", () -> showDualKeyDialog(
                        "百度翻译", TranslationPreferences.KEY_BAIDU_APPID, TranslationPreferences.KEY_BAIDU_SECRETKEY,
                        "APP ID", "密钥", "https://fanyi-api.baidu.com/"
                ));
                break;
            case "TENCENT":
                addGuideText(container, "腾讯云翻译 API\n\n腾讯云 TMT 服务，支持多语种。\n\n免费额度：每月 500 万字符免费调用。\n需要 SecretId 和 SecretKey。");
                addConfigButton(container, "❓ 这是什么？", () -> showApiIntroDialog(
                        "腾讯云翻译 API",
                        "YukiHub 可以使用腾讯云的翻译 API 来提供翻译服务，这意味着你需要从腾讯云平台申请 API。\n\n腾讯云翻译每月有 500 万字符的免费调用额度，是所有云翻译中免费额度最大的之一。\n\n腾讯云是腾讯旗下的云服务平台，翻译质量稳定。",
                        "https://cloud.tencent.com/",
                        "https://www.google.com/search?q=腾讯云+翻译API+申请教程"
                ));
                boolean hasTencent = !prefs.raw().getString(TranslationPreferences.KEY_TENCENT_SECRETID, "").isEmpty();
                addConfigButton(container, hasTencent ? "✅ 已配置（点击修改）" : "⚙️ 配置 SecretId 和 SecretKey", () -> showDualKeyDialog(
                        "腾讯云", TranslationPreferences.KEY_TENCENT_SECRETID, TranslationPreferences.KEY_TENCENT_SECRETKEY,
                        "SecretId", "SecretKey", "https://cloud.tencent.com/"
                ));
                break;
            case "CUSTOM":
                addGuideText(container, "自定义翻译 API\n\n⚠️ 这是高级功能，需要你具备基本的 API 调用知识。\n\n支持 GET / POST 请求方法，可自定义 URL、请求头、请求体和响应解析路径。所有 API 响应必须是 JSON 格式。");
                addConfigButton(container, "📖 查看详细说明", () -> showCustomApiIntroDialog());
                addConfigButton(container, "配置自定义 API", () -> {
                    showCustomApiConfigDialog();
                });
                break;
            default:
                break;
        }
    }

    /** 单 Key 配置对话框 */
    private void showSingleKeyDialog(String title, String prefKey, String hint, String applyUrl) {
        android.widget.EditText edit = new android.widget.EditText(this);
        edit.setHint(hint);
        edit.setTextColor(0xFFE8F0F8);
        edit.setHintTextColor(0x66FFFFFF);
        edit.setTextSize(13);
        edit.setBackgroundColor(0xFF263246);
        edit.setPadding(dp(10), dp(8), dp(10), dp(8));
        String existing = prefs.raw().getString(prefKey, "");
        if (!existing.isEmpty()) edit.setText(existing);

        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(title)
                .setView(edit)
                .setPositiveButton("保存", (d, w) -> {
                    String value = edit.getText().toString().trim();
                    prefs.putString(prefKey, value);
                    Toast.makeText(this, value.isEmpty() ? "已清除" : "已保存", Toast.LENGTH_SHORT).show();
                    recreate();
                })
                .setNeutralButton("前往申请", (d, w) -> {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(applyUrl)));
                    } catch (Throwable t) {
                        Toast.makeText(this, "无法打开浏览器", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 双 Key 配置对话框（如火山引擎、百度、腾讯云） */
    private void showDualKeyDialog(String title, String key1Pref, String key2Pref,
                                   String hint1, String hint2, String applyUrl) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF101522);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);

        android.widget.EditText edit1 = new android.widget.EditText(this);
        edit1.setHint(hint1);
        edit1.setTextColor(0xFFE8F0F8);
        edit1.setHintTextColor(0x66FFFFFF);
        edit1.setTextSize(13);
        edit1.setBackgroundColor(0xFF263246);
        edit1.setPadding(dp(10), dp(8), dp(10), dp(8));
        edit1.setText(prefs.raw().getString(key1Pref, ""));
        root.addView(edit1, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));

        android.widget.EditText edit2 = new android.widget.EditText(this);
        edit2.setHint(hint2);
        edit2.setTextColor(0xFFE8F0F8);
        edit2.setHintTextColor(0x66FFFFFF);
        edit2.setTextSize(13);
        edit2.setBackgroundColor(0xFF263246);
        edit2.setPadding(dp(10), dp(8), dp(10), dp(8));
        edit2.setText(prefs.raw().getString(key2Pref, ""));
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        lp2.topMargin = dp(8);
        root.addView(edit2, lp2);

        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(title)
                .setView(root)
                .setPositiveButton("保存", (d, w) -> {
                    prefs.putString(key1Pref, edit1.getText().toString().trim());
                    prefs.putString(key2Pref, edit2.getText().toString().trim());
                    Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show();
                    recreate();
                })
                .setNeutralButton("前往申请", (d, w) -> {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(applyUrl)));
                    } catch (Throwable t) {
                        Toast.makeText(this, "无法打开浏览器", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** DeepL 配置对话框（Host + API Key） */
    private void showDeepLConfigDialog() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF101522);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);

        android.widget.EditText editHost = new android.widget.EditText(this);
        editHost.setHint("Host（免费版: api-free.deepl.com）");
        editHost.setTextColor(0xFFE8F0F8);
        editHost.setHintTextColor(0x66FFFFFF);
        editHost.setTextSize(13);
        editHost.setBackgroundColor(0xFF263246);
        editHost.setPadding(dp(10), dp(8), dp(10), dp(8));
        editHost.setText(prefs.raw().getString(TranslationPreferences.KEY_DEEPL_HOST, ""));
        root.addView(editHost, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));

        android.widget.EditText editKey = new android.widget.EditText(this);
        editKey.setHint("API Key");
        editKey.setTextColor(0xFFE8F0F8);
        editKey.setHintTextColor(0x66FFFFFF);
        editKey.setTextSize(13);
        editKey.setBackgroundColor(0xFF263246);
        editKey.setPadding(dp(10), dp(8), dp(10), dp(8));
        editKey.setText(prefs.raw().getString(TranslationPreferences.KEY_DEEPL_APIKEY, ""));
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        lp2.topMargin = dp(8);
        root.addView(editKey, lp2);

        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("DeepL 配置")
                .setView(root)
                .setPositiveButton("保存", (d, w) -> {
                    prefs.putString(TranslationPreferences.KEY_DEEPL_HOST, editHost.getText().toString().trim());
                    prefs.putString(TranslationPreferences.KEY_DEEPL_APIKEY, editKey.getText().toString().trim());
                    Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show();
                    recreate();
                })
                .setNeutralButton("前往申请", (d, w) -> {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.deepl.com/pro-api")));
                    } catch (Throwable t) {
                        Toast.makeText(this, "无法打开浏览器", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 聚合 AI（OpenAI 兼容）配置对话框 */
    private void showOpenAiConfigDialog() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF101522);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);

        android.widget.EditText editUrl = new android.widget.EditText(this);
        editUrl.setHint("API 地址（留空使用默认 OpenAI）");
        editUrl.setTextColor(0xFFE8F0F8);
        editUrl.setHintTextColor(0x66FFFFFF);
        editUrl.setTextSize(13);
        editUrl.setBackgroundColor(0xFF263246);
        editUrl.setPadding(dp(10), dp(8), dp(10), dp(8));
        editUrl.setText(prefs.raw().getString(TranslationPreferences.KEY_OPENAI_BASEURL, ""));
        root.addView(editUrl, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));

        android.widget.EditText editKey = new android.widget.EditText(this);
        editKey.setHint("API Key");
        editKey.setTextColor(0xFFE8F0F8);
        editKey.setHintTextColor(0x66FFFFFF);
        editKey.setTextSize(13);
        editKey.setBackgroundColor(0xFF263246);
        editKey.setPadding(dp(10), dp(8), dp(10), dp(8));
        editKey.setText(prefs.raw().getString(TranslationPreferences.KEY_OPENAI_APIKEY, ""));
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        lp2.topMargin = dp(8);
        root.addView(editKey, lp2);

        android.widget.EditText editModel = new android.widget.EditText(this);
        editModel.setHint("模型名（如 gpt-4o、qwen-plus、deepseek-chat）");
        editModel.setTextColor(0xFFE8F0F8);
        editModel.setHintTextColor(0x66FFFFFF);
        editModel.setTextSize(13);
        editModel.setBackgroundColor(0xFF263246);
        editModel.setPadding(dp(10), dp(8), dp(10), dp(8));
        editModel.setText(prefs.raw().getString(TranslationPreferences.KEY_OPENAI_MODEL, "gpt-3.5-turbo"));
        LinearLayout.LayoutParams lp3 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        lp3.topMargin = dp(8);
        root.addView(editModel, lp3);

        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("聚合 AI 翻译配置")
                .setView(root)
                .setPositiveButton("保存", (d, w) -> {
                    prefs.putString(TranslationPreferences.KEY_OPENAI_BASEURL, editUrl.getText().toString().trim());
                    prefs.putString(TranslationPreferences.KEY_OPENAI_APIKEY, editKey.getText().toString().trim());
                    prefs.putString(TranslationPreferences.KEY_OPENAI_MODEL, editModel.getText().toString().trim());
                    Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show();
                    recreate();
                })
                .setNeutralButton("前往申请", (d, w) -> {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://platform.openai.com/api-keys")));
                    } catch (Throwable t) {
                        Toast.makeText(this, "无法打开浏览器", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showApiIntroDialog(String apiName, String content, String goUrl, String tutorialUrl) {
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("什么是 " + apiName + "？")
                .setMessage(content)
                .setCancelable(false)
                .setNeutralButton("查看教程", (d, w) -> {
                    try {
                        Intent intent = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(tutorialUrl));
                        startActivity(intent);
                    } catch (Throwable t) {
                        Toast.makeText(this, "无法打开浏览器", Toast.LENGTH_SHORT).show();
                    }
                })
                .setPositiveButton("前往申请", (d, w) -> {
                    try {
                        Intent intent = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(goUrl));
                        startActivity(intent);
                    } catch (Throwable t) {
                        Toast.makeText(this, "无法打开浏览器", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showCustomApiIntroDialog() {
        String content = "⚠️ 注意：这是高级功能，需要你具备基本的 API 调用知识。\n\n"
                + "1. 基本说明\n"
                + "此功能允许你配置自己的文本翻译 API，支持 GET 和 POST 请求方法。"
                + "（POST 的 Content-Type 为 application/json; charset=utf-8，无需在请求头中显式添加。）所有 API 响应必须为 JSON 格式。\n\n"
                + "2. 使用场景\n"
                + "通常，大型翻译 API 服务商实现了自己的签名算法，无法通过简单的 GET/POST 请求直接调用。"
                + "因此，自定义文本翻译 API 的实际使用场景是连接你自己搭建的翻译 API 接口。\n\n"
                + "3. 参数说明\n"
                + "① 请求方式：GET 或 POST\n"
                + "② API 地址：可指定 http 或 https\n"
                + "③ 请求头：随请求发送的 Headers\n"
                + "④ 请求体（POST）：JSON 请求内容\n"
                + "⑤ 响应路径：从 API 响应 JSON 中提取翻译结果的路径\n\n"
                + "注意：\n"
                + "① 在请求头和请求体中，使用 \"usesourcetext\" 代表待翻译文本\n"
                + "② 确保在请求体中包含源语言和目标语言代码\n\n"
                + "4. 请求示例\n"
                + "【GET 请求】\n"
                + "URL: https://api.example.com/translate\n"
                + "Query: text=usesourcetext&source=ja&target=zh\n"
                + "Headers: Authorization=my_custom_token\n\n"
                + "【POST 请求】\n"
                + "URL: https://api.example.com/translate\n"
                + "Headers: Authorization=my_custom_token\n"
                + "Body: text=usesourcetext, source=ja, target=zh\n\n"
                + "5. 响应路径示例\n"
                + "示例1: {\"code\":0, \"data\":{\"translation\":\"你好\"}} → 路径: data.translation\n"
                + "示例2: {\"code\":0, \"result\":\"你好\"} → 路径: result\n"
                + "示例3: {\"data\":{\"translations\":[{\"text\":\"你好\"}]}} → 路径: data.translations[0].text\n\n"
                + "语法：用 \".\" 分隔对象属性，用 \"[n]\" 访问数组第 n 个元素（从 0 开始）。"
                + "可自由组合，如 data.list[0].items[2].text\n\n"
                + "6. 使用前建议\n"
                + "可以先使用 Postman 或 Reqable 等工具测试 API 响应，测试成功后再将参数填入此功能。";

        ScrollView scroll = new ScrollView(this);
        TextView tv = new TextView(this);
        tv.setText(content);
        tv.setTextColor(0xFFE8F0F8);
        tv.setTextSize(12);
        tv.setLineSpacing(dp(2), 1.0f);
        tv.setBackgroundColor(0xFF101522);
        int pad = dp(16);
        tv.setPadding(pad, pad, pad, pad);
        scroll.addView(tv);

        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("自定义文本翻译 API 功能说明")
                .setView(scroll)
                .setPositiveButton("知道了", null)
                .show();
    }

    private void addGuideText(LinearLayout container, String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(0xFFB8C7D9);
        tv.setTextSize(11);
        tv.setLineSpacing(dp(2), 1.0f);
        tv.setBackgroundColor(0xFF1A2333);
        tv.setPadding(dp(12), dp(10), dp(12), dp(10));
        container.addView(tv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void addConfigButton(LinearLayout container, String label, Runnable action) {
        Button btn = new Button(this);
        btn.setText(label);
        styleButton(btn);
        btn.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        lp.topMargin = dp(8);
        container.addView(btn, lp);
    }

    private void showCustomApiConfigDialog() {
        // 自定义 API 配置对话框：编辑 URL、Method、Headers、Body、JSON Path
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF101522);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);

        // Method 选择
        TextView methodLabel = new TextView(this);
        methodLabel.setText("请求方式");
        methodLabel.setTextColor(0xFFB8C7D9);
        methodLabel.setTextSize(12);
        root.addView(methodLabel);

        String[] methods = {"GET", "POST"};
        android.widget.Spinner methodSpinner = new android.widget.Spinner(this);
        methodSpinner.setAdapter(themedAdapter(methods));
        root.addView(methodSpinner, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(40)));

        // URL
        TextView urlLabel = new TextView(this);
        urlLabel.setText("API 地址");
        urlLabel.setTextColor(0xFFB8C7D9);
        urlLabel.setTextSize(12);
        urlLabel.setPadding(0, dp(8), 0, 0);
        root.addView(urlLabel);

        android.widget.EditText editUrl = new android.widget.EditText(this);
        editUrl.setHint("https://api.example.com/translate");
        editUrl.setTextColor(0xFFE8F0F8);
        editUrl.setHintTextColor(0x66FFFFFF);
        editUrl.setTextSize(13);
        editUrl.setBackgroundColor(0xFF263246);
        editUrl.setPadding(dp(10), dp(8), dp(10), dp(8));
        root.addView(editUrl, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));

        // Headers
        TextView headersLabel = new TextView(this);
        headersLabel.setText("请求头（每行 key=value）");
        headersLabel.setTextColor(0xFFB8C7D9);
        headersLabel.setTextSize(12);
        headersLabel.setPadding(0, dp(8), 0, 0);
        root.addView(headersLabel);

        android.widget.EditText editHeaders = new android.widget.EditText(this);
        editHeaders.setHint("Authorization=Bearer sk-xxx\nContent-Type=application/json");
        editHeaders.setTextColor(0xFFE8F0F8);
        editHeaders.setHintTextColor(0x66FFFFFF);
        editHeaders.setTextSize(12);
        editHeaders.setBackgroundColor(0xFF263246);
        editHeaders.setPadding(dp(10), dp(8), dp(10), dp(8));
        editHeaders.setMinLines(2);
        editHeaders.setGravity(android.view.Gravity.TOP);
        root.addView(editHeaders, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(80)));

        // Body（POST 时使用）
        TextView bodyLabel = new TextView(this);
        bodyLabel.setText("请求体（POST，每行 key=value，usesourcetext 会被替换为待翻译文本）");
        bodyLabel.setTextColor(0xFFB8C7D9);
        bodyLabel.setTextSize(12);
        bodyLabel.setPadding(0, dp(8), 0, 0);
        root.addView(bodyLabel);

        android.widget.EditText editBody = new android.widget.EditText(this);
        editBody.setHint("model=gpt-4o\nmessages=[{\"role\":\"user\",\"content\":\"usesourcetext\"}]");
        editBody.setTextColor(0xFFE8F0F8);
        editBody.setHintTextColor(0x66FFFFFF);
        editBody.setTextSize(12);
        editBody.setBackgroundColor(0xFF263246);
        editBody.setPadding(dp(10), dp(8), dp(10), dp(8));
        editBody.setMinLines(3);
        editBody.setGravity(android.view.Gravity.TOP);
        root.addView(editBody, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(100)));

        // JSON Path
        TextView pathLabel = new TextView(this);
        pathLabel.setText("响应 JSON 路径（如 choices[0].message.content）");
        pathLabel.setTextColor(0xFFB8C7D9);
        pathLabel.setTextSize(12);
        pathLabel.setPadding(0, dp(8), 0, 0);
        root.addView(pathLabel);

        android.widget.EditText editPath = new android.widget.EditText(this);
        editPath.setHint("choices[0].message.content");
        editPath.setTextColor(0xFFE8F0F8);
        editPath.setHintTextColor(0x66FFFFFF);
        editPath.setTextSize(13);
        editPath.setBackgroundColor(0xFF263246);
        editPath.setPadding(dp(10), dp(8), dp(10), dp(8));
        root.addView(editPath, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));

        // 加载已有配置
        int slot = prefs.getCustomTextApiSlot();
        CustomApiConfig.TextConfig existing = CustomApiConfig.loadTextConfig(prefs.raw(), slot);
        if (existing != null) {
            methodSpinner.setSelection("POST".equals(existing.method) ? 1 : 0);
            if (existing.baseUrl != null) editUrl.setText(existing.baseUrl);
            StringBuilder hb = new StringBuilder();
            if (existing.headers != null) for (CustomApiConfig.KeyValuePair p : existing.headers) {
                if (hb.length() > 0) hb.append("\n");
                hb.append(p.key).append("=").append(p.value);
            }
            editHeaders.setText(hb.toString());
            StringBuilder bb = new StringBuilder();
            if (existing.jsonBody != null) for (CustomApiConfig.KeyValuePair p : existing.jsonBody) {
                if (bb.length() > 0) bb.append("\n");
                bb.append(p.key).append("=").append(p.value);
            }
            editBody.setText(bb.toString());
            if (existing.jsonResponsePath != null) editPath.setText(existing.jsonResponsePath);
        }

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("自定义翻译 API 配置")
                .setView(scroll)
                .setPositiveButton("保存", (d, w) -> {
                    String method = (String) methodSpinner.getSelectedItem();
                    String url = editUrl.getText().toString().trim();
                    String path = editPath.getText().toString().trim();
                    if (url.isEmpty()) {
                        Toast.makeText(this, "请填写 API 地址", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (path.isEmpty()) {
                        Toast.makeText(this, "请填写响应路径", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    List<CustomApiConfig.KeyValuePair> headers = parsePairs(editHeaders.getText().toString());
                    List<CustomApiConfig.KeyValuePair> body = "POST".equals(method) ? parsePairs(editBody.getText().toString()) : new ArrayList<>();
                    CustomApiConfig.TextConfig config = new CustomApiConfig.TextConfig(
                            method, url, new ArrayList<>(), headers, body, path);
                    CustomApiConfig.saveTextConfig(prefs.raw(), config, slot);
                    Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private List<CustomApiConfig.KeyValuePair> parsePairs(String text) {
        List<CustomApiConfig.KeyValuePair> list = new ArrayList<>();
        if (text == null || text.trim().isEmpty()) return list;
        String[] lines = text.split("\n");
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;
            int idx = line.indexOf('=');
            if (idx > 0) {
                list.add(new CustomApiConfig.KeyValuePair(
                        line.substring(0, idx).trim(),
                        line.substring(idx + 1).trim()));
            }
        }
        return list;
    }

    private void addPicEngineSelector(LinearLayout root) {
        String[] values = {"CUSTOM", "BAIDU", "TENCENT"};
        String[] labels = {"自定义 API", "百度图片翻译", "腾讯图片翻译"};
        String current = prefs.getPicEngine();
        android.widget.Spinner spinner = new android.widget.Spinner(this);
        android.widget.ArrayAdapter<String> adapter = themedAdapter(labels);
        spinner.setAdapter(adapter);
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(current)) {
                spinner.setSelection(i);
                break;
            }
        }
        spinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                prefs.putString(TranslationPreferences.KEY_PIC_ENGINE, values[position]);
            }
            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });
        root.addView(spinner, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(40)));

        TextView hint = new TextView(this);
        hint.setText("图片翻译模式直接上传截图给翻译 API，跳过 OCR 步骤。具体引擎实现待后续接入。");
        hint.setTextColor(0x88FFFFFF);
        hint.setTextSize(10);
        root.addView(hint);
    }

    private void addAutoTranslateSettings(LinearLayout root) {
        // 自动翻译开关
        Switch autoSwitch = new Switch(this);
        autoSwitch.setText("开启自动翻译");
        autoSwitch.setTextColor(0xFFFFFFFF);
        autoSwitch.setChecked(prefs.isAutoEnabled());
        autoSwitch.setOnCheckedChangeListener((button, checked) ->
                prefs.putBoolean(TranslationPreferences.KEY_AUTO_ENABLED, checked));
        root.addView(autoSwitch);

        // 间隔
        TextView intervalLabel = new TextView(this);
        long interval = prefs.getAutoInterval();
        intervalLabel.setText("截图间隔：" + interval + "ms");
        intervalLabel.setTextColor(0xFFCCCCCC);
        intervalLabel.setTextSize(12);
        intervalLabel.setPadding(0, dp(8), 0, dp(4));
        root.addView(intervalLabel);

        SeekBar intervalSeek = new SeekBar(this);
        intervalSeek.setMax(27); // 1500ms ~ 15000ms, step 500ms
        intervalSeek.setProgress((int) ((interval - 1500) / 500));
        intervalSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                long val = 1500 + progress * 500L;
                intervalLabel.setText("截图间隔：" + val + "ms");
            }
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                long val = 1500 + seekBar.getProgress() * 500L;
                prefs.putLong(TranslationPreferences.KEY_AUTO_INTERVAL, val);
            }
        });
        root.addView(intervalSeek);

        // 短文本阈值
        TextView lengthLabel = new TextView(this);
        int lengthThreshold = prefs.getAutoLengthThreshold();
        lengthLabel.setText("短文本阈值：" + lengthThreshold + " 字符（低于此值直接翻译）");
        lengthLabel.setTextColor(0xFFCCCCCC);
        lengthLabel.setTextSize(12);
        lengthLabel.setPadding(0, dp(8), 0, dp(4));
        root.addView(lengthLabel);

        SeekBar lengthSeek = new SeekBar(this);
        lengthSeek.setMax(50);
        lengthSeek.setProgress(lengthThreshold);
        lengthSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                lengthLabel.setText("短文本阈值：" + progress + " 字符（低于此值直接翻译）");
            }
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                prefs.putInt(TranslationPreferences.KEY_AUTO_LENGTH_THRESHOLD, lengthSeek.getProgress());
            }
        });
        root.addView(lengthSeek);

        // 相似度
        TextView simLabel = new TextView(this);
        float sim = prefs.getAutoSimilarityThreshold();
        simLabel.setText("相似度阈值：" + String.format("%.1f", sim) + "（高于此值不翻译）");
        simLabel.setTextColor(0xFFCCCCCC);
        simLabel.setTextSize(12);
        simLabel.setPadding(0, dp(8), 0, dp(4));
        root.addView(simLabel);

        SeekBar simSeek = new SeekBar(this);
        simSeek.setMax(20); // 0.0 ~ 1.0, step 0.05
        simSeek.setProgress(Math.round(sim * 20));
        simSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float val = progress / 20f;
                simLabel.setText("相似度阈值：" + String.format("%.2f", val) + "（高于此值不翻译）");
            }
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                float val = simSeek.getProgress() / 20f;
                prefs.putFloat(TranslationPreferences.KEY_AUTO_SIMILARITY, val);
            }
        });
        root.addView(simSeek);
    }

    private void addShowSourceModeSelector(LinearLayout root) {
        TextView tv = new TextView(this);
        tv.setText("显示模式");
        tv.setTextColor(0xFFCCCCCC);
        tv.setTextSize(12);
        root.addView(tv);

        String[] labels = {"仅译文", "原文 + 译文", "译文 + 原文"};
        int current = prefs.getShowSourceMode();
        android.widget.Spinner spinner = new android.widget.Spinner(this);
        android.widget.ArrayAdapter<String> adapter = themedAdapter(labels);
        spinner.setAdapter(adapter);
        spinner.setSelection(Math.min(current, labels.length - 1));
        spinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                prefs.putInt(TranslationPreferences.KEY_SHOW_SOURCE_MODE, position);
            }
            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });
        root.addView(spinner, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(40)));
    }

    private void addResultFontSizeSelector(LinearLayout root) {
        TextView label = new TextView(this);
        float size = prefs.getResultFontSize();
        label.setText("结果字体大小：" + String.format("%.0f", size) + "sp");
        label.setTextColor(0xFFCCCCCC);
        label.setTextSize(12);
        label.setPadding(0, dp(8), 0, dp(4));
        root.addView(label);

        SeekBar seek = new SeekBar(this);
        seek.setMax(30); // 8sp ~ 38sp
        seek.setProgress(Math.round(size - 8));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float val = 8 + progress;
                label.setText("结果字体大小：" + String.format("%.0f", val) + "sp");
            }
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                prefs.putFloat(TranslationPreferences.KEY_RESULT_FONT_SIZE, 8 + seek.getProgress());
            }
        });
        root.addView(seek);
    }

    private void addPenetrableSwitch(LinearLayout root) {
        Switch sw = new Switch(this);
        sw.setText("结果层点击穿透");
        sw.setTextColor(0xFFFFFFFF);
        sw.setChecked(prefs.isResultPenetrable());
        sw.setOnCheckedChangeListener((button, checked) ->
                prefs.putBoolean(TranslationPreferences.KEY_RESULT_PENETRABLE, checked));
        root.addView(sw);
    }

    private android.widget.ArrayAdapter<String> themedAdapter(String[] labels) {
        return new android.widget.ArrayAdapter<String>(
                this, android.R.layout.simple_spinner_item, labels) {
            private void style(TextView view) {
                view.setTextColor(0xFFE8F0F8);
                view.setTextSize(13);
                view.setGravity(android.view.Gravity.CENTER_VERTICAL);
                view.setPadding(dp(12), 0, dp(12), 0);
                view.setBackgroundColor(0xFF263246);
            }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView view = (TextView) super.getView(position, convertView, parent);
                style(view);
                return view;
            }

            @Override
            public View getDropDownView(int position, View convertView, ViewGroup parent) {
                TextView view = (TextView) super.getDropDownView(position, convertView, parent);
                style(view);
                view.setMinHeight(dp(48));
                return view;
            }
        };
    }

    private void styleButton(Button button) {
        button.setTextColor(0xFFE8F0F8);
        button.setTextSize(13);
        button.setMinHeight(dp(48));
        button.setAllCaps(false);
        button.setBackgroundColor(0xFF263246);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}