package com.yuki.yukihub.ons;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 全屏虚拟按键布局编辑器。
 *
 * 做成独立 Activity 而不是对话框，是因为对话框里的缩略预览看不出真实效果：
 * 这里横屏全屏、按钮按真实 dp 尺寸渲染、和游戏里共用 {@link OnsButtonRenderer}，
 * 所以编辑时看到的大小和位置就是进游戏后的样子。
 *
 * 交互：按住按钮拖动改位置，点一下选中（弹出该按钮的操作条），
 * 底部工具条负责添加 / 全局样式 / 保存。
 */
public class OnsButtonLayoutActivity extends Activity {

    private OnsButtonConfig cfg;
    /** 画布：按钮的父容器，尺寸即游戏可视区域。 */
    private FrameLayout canvas;
    /** 底部浮层工具条。 */
    private LinearLayout toolbar;
    /** 当前选中项，null 表示没选中。 */
    private OnsButtonConfig.Item selected;
    /** item.id -> 视图，用于选中态刷新。 */
    private final java.util.Map<String, TextView> views = new java.util.HashMap<>();
    private TextView toggleView;
    /** 选中的是收起按钮时为 true。 */
    private boolean toggleSelected;
    private TextView hintText;
    private boolean dirty;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 和游戏一致：横屏 + 全屏，否则编辑出来的坐标对不上
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        applyImmersive();
        // 游戏里开了刘海区延伸，编辑器也要一致
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams attrs = getWindow().getAttributes();
            attrs.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(attrs);
        }

        cfg = OnsButtonConfig.load(this).copy();

        FrameLayout root = new FrameLayout(this);
        // 深色棋盘感背景，模拟游戏画面又不至于干扰判断
        root.setBackgroundColor(0xFF2B3242);

        canvas = new FrameLayout(this);
        root.addView(canvas, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        root.addView(buildHint(), hintParams());
        toolbar = buildToolbar();
        root.addView(toolbar, toolbarParams());

        setContentView(root);

        // 画布尺寸要等布局完成才知道，之后再建按钮
        canvas.post(this::rebuild);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) applyImmersive();
    }

    private void applyImmersive() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    // ==================== 画布 ====================

    /** 依配置重建所有按钮。画布宽高即真实游戏可视区域。 */
    private void rebuild() {
        canvas.removeAllViews();
        views.clear();
        toggleView = null;

        int pw = canvas.getWidth();
        int ph = canvas.getHeight();
        if (pw <= 0 || ph <= 0) return;

        for (OnsButtonConfig.Item item : cfg.items) {
            TextView v = OnsButtonRenderer.createButton(this, cfg, item, false);
            attachDrag(v, item);
            views.put(item.id, v);
            canvas.addView(v, OnsButtonRenderer.params(
                    this, cfg.size, item.x, item.y, pw, ph));
        }

        if (cfg.toggleEnabled) {
            toggleView = OnsButtonRenderer.createToggle(this, cfg, false);
            attachDrag(toggleView, null);
            canvas.addView(toggleView, OnsButtonRenderer.params(
                    this, cfg.toggleSize, cfg.toggleX, cfg.toggleY, pw, ph));
        }

        applySelectionHighlight();
        updateHint();
    }

    /**
     * 拖动与选中。item 为 null 表示这是收起按钮。
     *
     * 判定逻辑：按下记录偏移，移动超过阈值算拖动，没超过就算点击（选中）。
     * 这样单指既能拖又能选，不需要长按。
     */
    private void attachDrag(final TextView v, final OnsButtonConfig.Item item) {
        final float[] down = new float[2];      // 按下时手指位置
        final int[] origin = new int[2];        // 按下时按钮左上角
        final boolean[] moved = new boolean[1];
        final int slop = OnsButtonRenderer.dp(this, 6);

        v.setOnTouchListener((view, e) -> {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) v.getLayoutParams();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    down[0] = e.getRawX();
                    down[1] = e.getRawY();
                    origin[0] = lp.leftMargin;
                    origin[1] = lp.topMargin;
                    moved[0] = false;
                    v.setAlpha(1f);
                    // 拖动中的按钮浮到最上层，避免被别的按钮遮住
                    v.bringToFront();
                    return true;

                case MotionEvent.ACTION_MOVE: {
                    int dx = Math.round(e.getRawX() - down[0]);
                    int dy = Math.round(e.getRawY() - down[1]);
                    if (!moved[0] && Math.abs(dx) < slop && Math.abs(dy) < slop) return true;
                    moved[0] = true;

                    int pw = canvas.getWidth();
                    int ph = canvas.getHeight();
                    int size = lp.width;
                    lp.leftMargin = clamp(origin[0] + dx, 0, Math.max(0, pw - size));
                    lp.topMargin = clamp(origin[1] + dy, 0, Math.max(0, ph - size));
                    v.setLayoutParams(lp);

                    float px = OnsButtonRenderer.toPercentX(lp.leftMargin, size, pw);
                    float py = OnsButtonRenderer.toPercentY(lp.topMargin, size, ph);
                    if (item == null) {
                        cfg.toggleX = px;
                        cfg.toggleY = py;
                    } else {
                        item.x = px;
                        item.y = py;
                    }
                    dirty = true;
                    updateHint();
                    return true;
                }

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (!moved[0]) {
                        // 没移动就是点击 → 切换选中
                        boolean sameTarget = (item == null)
                                ? toggleSelected
                                : (selected == item);
                        if (sameTarget) {
                            selected = null;
                            toggleSelected = false;
                        } else {
                            selected = item;
                            toggleSelected = (item == null);
                        }
                        applySelectionHighlight();
                        rebuildToolbar();
                    }
                    restoreAlpha(v, item);
                    updateHint();
                    return true;
            }
            return true;
        });
    }

    private void restoreAlpha(TextView v, OnsButtonConfig.Item item) {
        v.setAlpha(item == null
                ? OnsButtonRenderer.toggleAlphaOf(cfg)
                : OnsButtonRenderer.alphaOf(cfg));
    }

    /** 选中项加一圈描边，其他恢复常态。 */
    private void applySelectionHighlight() {
        for (Map.Entry<String, TextView> e : views.entrySet()) {
            boolean on = selected != null && selected.id.equals(e.getKey());
            styleSelected(e.getValue(), on);
        }
        if (toggleView != null) styleSelected(toggleView, toggleSelected);
    }

    private void styleSelected(TextView v, boolean on) {
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        if (on) {
            bg.setColor(Color.argb(210, 74, 158, 255));
            bg.setStroke(OnsButtonRenderer.dp(this, 3), 0xFFFFFFFF);
        } else {
            bg.setColor(Color.argb(140, 10, 12, 18));
        }
        v.setBackground(bg);
    }

    // ==================== 顶部提示 ====================

    private View buildHint() {
        hintText = new TextView(this);
        hintText.setTextSize(11.5f);
        hintText.setTextColor(0xCCFFFFFF);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(OnsButtonRenderer.dp(this, 6));
        bg.setColor(0x99000000);
        hintText.setBackground(bg);
        int p = OnsButtonRenderer.dp(this, 8);
        hintText.setPadding(p, p / 2, p, p / 2);
        return hintText;
    }

    private FrameLayout.LayoutParams hintParams() {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        lp.topMargin = OnsButtonRenderer.dp(this, 10);
        return lp;
    }

    private void updateHint() {
        if (hintText == null) return;
        if (toggleSelected) {
            hintText.setText(String.format(java.util.Locale.ROOT,
                    "收起按钮  (%.0f%%, %.0f%%)  ·  拖动改位置", cfg.toggleX, cfg.toggleY));
        } else if (selected != null) {
            hintText.setText(String.format(java.util.Locale.ROOT,
                    "%s  (%.0f%%, %.0f%%)  ·  拖动改位置",
                    displayName(selected), selected.x, selected.y));
        } else {
            hintText.setText("拖动按钮改位置  ·  点一下选中后可删除 / 改按键");
        }
    }

    private String displayName(OnsButtonConfig.Item item) {
        String label = item.label();
        if (label != null && !label.isEmpty()) return label;
        return item.icon();
    }

    // ==================== 底部工具条 ====================

    private FrameLayout.LayoutParams toolbarParams() {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lp.bottomMargin = OnsButtonRenderer.dp(this, 12);
        return lp;
    }

    private LinearLayout buildToolbar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(OnsButtonRenderer.dp(this, 24));
        bg.setColor(0xE6191B22);
        bar.setBackground(bg);
        int p = OnsButtonRenderer.dp(this, 6);
        bar.setPadding(p * 2, p, p * 2, p);
        fillToolbar(bar);
        return bar;
    }

    private void rebuildToolbar() {
        if (toolbar == null) return;
        toolbar.removeAllViews();
        fillToolbar(toolbar);
    }

    /** 工具条内容随选中状态变化：没选中显示全局操作，选中了显示该按钮的操作。 */
    private void fillToolbar(LinearLayout bar) {
        if (selected != null) {
            addToolButton(bar, "改按键", this::editSelectedKey);
            addToolButton(bar, "删除", this::deleteSelected);
            addToolButton(bar, "取消选中", () -> {
                selected = null;
                toggleSelected = false;
                applySelectionHighlight();
                rebuildToolbar();
                updateHint();
            });
            return;
        }
        if (toggleSelected) {
            addToolButton(bar, "隐藏收起键", () -> {
                cfg.toggleEnabled = false;
                toggleSelected = false;
                dirty = true;
                rebuild();
                rebuildToolbar();
            });
            addToolButton(bar, "取消选中", () -> {
                toggleSelected = false;
                applySelectionHighlight();
                rebuildToolbar();
                updateHint();
            });
            return;
        }

        addToolButton(bar, "+ 按钮", this::showAddDialog);
        addToolButton(bar, "样式", this::showStyleDialog);
        addToolButton(bar, "预设", this::showPresetDialog);
        addToolButton(bar, "保存", this::saveAndFinish);
        addToolButton(bar, "退出", this::confirmExit);
    }

    private void addToolButton(LinearLayout bar, String text, Runnable action) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(12.5f);
        tv.setTextColor(0xFFE8EAF0);
        tv.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(OnsButtonRenderer.dp(this, 18));
        bg.setColor(0xFF2E323C);
        tv.setBackground(bg);
        int ph = OnsButtonRenderer.dp(this, 14);
        int pv = OnsButtonRenderer.dp(this, 9);
        tv.setPadding(ph, pv, ph, pv);
        tv.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        if (bar.getChildCount() > 0) lp.leftMargin = OnsButtonRenderer.dp(this, 6);
        bar.addView(tv, lp);
    }

    // ==================== 选中项操作 ====================

    private void deleteSelected() {
        if (selected == null) return;
        cfg.items.remove(selected);
        selected = null;
        dirty = true;
        rebuild();
        rebuildToolbar();
    }

    /** 内置按钮改交互模式，自定义按钮还能改键位和图标。 */
    private void editSelectedKey() {
        if (selected == null) return;
        final OnsButtonConfig.Item item = selected;

        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        int p = OnsButtonRenderer.dp(this, 16);
        form.setPadding(p, p, p, p);

        final EditText iconEdit;
        final EditText labelEdit;
        final Spinner keySpinner;
        final List<String> keyNames = new ArrayList<>(OnsButtonConfig.KEY_TABLE.keySet());

        if (item.isCustom()) {
            form.addView(formLabel("图标（1~2 字符）"));
            iconEdit = formEdit(item.icon());
            form.addView(iconEdit);

            form.addView(formLabel("标签（可留空）"));
            labelEdit = formEdit(item.label());
            form.addView(labelEdit);

            form.addView(formLabel("键位"));
            int cur = keyNames.indexOf(OnsButtonConfig.keyName(item.keyCode));
            keySpinner = formSpinner(keyNames.toArray(new String[0]), Math.max(0, cur));
            form.addView(keySpinner);
        } else {
            iconEdit = null;
            labelEdit = null;
            keySpinner = null;
            TextView info = new TextView(this);
            info.setText(displayName(item) + " · " + item.desc());
            info.setTextSize(12);
            info.setTextColor(0xFFD8DAE0);
            info.setPadding(0, 0, 0, OnsButtonRenderer.dp(this, 8));
            form.addView(info);
        }

        form.addView(formLabel("交互模式"));
        final String[] modes = {
                OnsButtonConfig.MODE_PRESS,
                OnsButtonConfig.MODE_HOLD,
                OnsButtonConfig.MODE_TOGGLE};
        int modeIdx = indexOf(modes, item.mode());
        final Spinner modeSpinner = formSpinner(
                new String[]{"点按一次", "按住生效", "切换开关"}, modeIdx);
        form.addView(modeSpinner);

        TextView tip = new TextView(this);
        tip.setText("点按：发一次按键。按住：按下持续生效、松手停止（快进）。"
                + "切换：点一下发一次按键并高亮（自动播放）。");
        tip.setTextSize(10.5f);
        tip.setTextColor(0xFF9A9AA0);
        tip.setPadding(0, OnsButtonRenderer.dp(this, 6), 0, 0);
        form.addView(tip);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("按钮设置")
                .setView(wrapScroll(form))
                .setPositiveButton("确定", (d, w) -> {
                    if (item.isCustom()) {
                        String ic = iconEdit.getText().toString().trim();
                        if (ic.isEmpty()) ic = "●";
                        if (ic.length() > 2) ic = ic.substring(0, 2);
                        item.icon = ic;
                        String lb = labelEdit.getText().toString().trim();
                        if (lb.length() > 6) lb = lb.substring(0, 6);
                        item.label = lb;
                        int kp = keySpinner.getSelectedItemPosition();
                        if (kp >= 0 && kp < keyNames.size()) {
                            Integer code = OnsButtonConfig.KEY_TABLE.get(keyNames.get(kp));
                            if (code != null) item.keyCode = code;
                        }
                    }
                    int mp = modeSpinner.getSelectedItemPosition();
                    item.mode = modes[clamp(mp, 0, modes.length - 1)];
                    dirty = true;
                    rebuild();
                })
                .setNegativeButton("取消", null)
                .create();
        showDark(dialog);
    }

    // ==================== 添加按钮 ====================

    private void showAddDialog() {
        final List<String> ids = new ArrayList<>();
        final List<String> names = new ArrayList<>();
        for (Map.Entry<String, OnsButtonConfig.Def> e : OnsButtonConfig.DEFS.entrySet()) {
            if (cfg.hasBuiltin(e.getKey())) continue;
            ids.add(e.getKey());
            OnsButtonConfig.Def d = e.getValue();
            names.add(d.icon + "  " + d.label + " · " + d.desc);
        }
        names.add("＋  自定义按键（自选键位）");

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("添加按钮")
                .setItems(names.toArray(new String[0]), (d, which) -> {
                    if (which < ids.size()) {
                        cfg.addBuiltin(ids.get(which));
                        // 新按钮落在画布中央，方便立刻拖走
                        OnsButtonConfig.Item added = cfg.items.get(cfg.items.size() - 1);
                        added.x = 50f;
                        added.y = 50f;
                        selected = added;
                        toggleSelected = false;
                        dirty = true;
                        rebuild();
                        rebuildToolbar();
                    } else {
                        showCustomKeyDialog();
                    }
                })
                .setNegativeButton("取消", null)
                .create();
        showDark(dialog);
    }

    private void showCustomKeyDialog() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        int p = OnsButtonRenderer.dp(this, 16);
        form.setPadding(p, p, p, p);

        form.addView(formLabel("图标（1~2 字符）"));
        final EditText icon = formEdit("▲");
        form.addView(icon);

        form.addView(formLabel("标签（可留空）"));
        final EditText label = formEdit("");
        form.addView(label);

        form.addView(formLabel("键位"));
        final List<String> keyNames = new ArrayList<>(OnsButtonConfig.KEY_TABLE.keySet());
        final Spinner keySpinner = formSpinner(keyNames.toArray(new String[0]), 0);
        form.addView(keySpinner);

        form.addView(formLabel("交互模式"));
        final String[] modes = {
                OnsButtonConfig.MODE_PRESS,
                OnsButtonConfig.MODE_HOLD,
                OnsButtonConfig.MODE_TOGGLE};
        final Spinner modeSpinner = formSpinner(
                new String[]{"点按一次", "按住生效", "切换开关"}, 0);
        form.addView(modeSpinner);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("自定义按键")
                .setView(wrapScroll(form))
                .setPositiveButton("添加", (d, w) -> {
                    String ic = icon.getText().toString().trim();
                    if (ic.isEmpty()) ic = "●";
                    if (ic.length() > 2) ic = ic.substring(0, 2);
                    String lb = label.getText().toString().trim();
                    if (lb.length() > 6) lb = lb.substring(0, 6);
                    int kp = keySpinner.getSelectedItemPosition();
                    if (kp < 0 || kp >= keyNames.size()) {
                        toast("请选择键位");
                        return;
                    }
                    Integer code = OnsButtonConfig.KEY_TABLE.get(keyNames.get(kp));
                    if (code == null) {
                        toast("键位无效");
                        return;
                    }
                    int mp = modeSpinner.getSelectedItemPosition();
                    OnsButtonConfig.Item added = cfg.addCustom(lb, ic, code,
                            modes[clamp(mp, 0, modes.length - 1)]);
                    added.x = 50f;
                    added.y = 50f;
                    selected = added;
                    toggleSelected = false;
                    dirty = true;
                    rebuild();
                    rebuildToolbar();
                })
                .setNegativeButton("取消", null)
                .create();
        showDark(dialog);
    }

    // ==================== 全局样式 ====================

    private void showStyleDialog() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        int p = OnsButtonRenderer.dp(this, 16);
        form.setPadding(p, p, p, p);

        addSlider(form, "按钮大小", cfg.size, 38, 66, "dp", v -> {
            cfg.size = v; dirty = true; rebuild();
        });
        addSlider(form, "不透明度", cfg.opacity, 20, 100, "%", v -> {
            cfg.opacity = v; dirty = true; rebuild();
        });
        addSlider(form, "收起键大小", cfg.toggleSize, 28, 56, "dp", v -> {
            cfg.toggleSize = v; dirty = true; rebuild();
        });

        // 收起键被隐藏时给个恢复入口
        if (!cfg.toggleEnabled) {
            Button restore = new Button(this);
            restore.setText("显示收起按钮");
            restore.setAllCaps(false);
            restore.setOnClickListener(v -> {
                cfg.toggleEnabled = true;
                dirty = true;
                rebuild();
                toast("收起按钮已显示");
            });
            form.addView(restore);
        }

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("全局样式")
                .setView(wrapScroll(form))
                .setPositiveButton("完成", null)
                .create();
        showDark(dialog);
    }

    private interface IntSetter { void set(int v); }

    private void addSlider(LinearLayout root, String name, int value,
                           final int min, final int max, final String unit,
                           final IntSetter setter) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView lab = new TextView(this);
        lab.setText(name);
        lab.setTextSize(12);
        lab.setTextColor(0xFFE8EAF0);
        lab.setWidth(OnsButtonRenderer.dp(this, 78));

        final TextView val = new TextView(this);
        val.setTextSize(11.5f);
        val.setTextColor(0xFF6BA8FF);
        val.setWidth(OnsButtonRenderer.dp(this, 46));
        val.setGravity(Gravity.END);
        val.setText(value + unit);

        SeekBar bar = new SeekBar(this);
        bar.setMax(max - min);
        bar.setProgress(value - min);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int prog, boolean fromUser) {
                int v = min + prog;
                val.setText(v + unit);
                if (fromUser) setter.set(v);
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { }
            @Override public void onStopTrackingTouch(SeekBar sb) { }
        });

        row.addView(lab);
        row.addView(bar, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        row.addView(val);
        root.addView(row);
    }

    // ==================== 预设 ====================

    private void showPresetDialog() {
        final String[] names = {
                "默认（左三右三）",
                "精简（SKIP / OK / AUTO）",
                "左手模式（左右镜像）"};
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("套用预设")
                .setItems(names, (d, which) -> {
                    OnsButtonConfig preset;
                    if (which == 1) preset = OnsButtonConfig.presetMinimal();
                    else if (which == 2) preset = OnsButtonConfig.presetLeftHand();
                    else preset = OnsButtonConfig.defaults();
                    cfg = preset;
                    selected = null;
                    toggleSelected = false;
                    dirty = true;
                    rebuild();
                    rebuildToolbar();
                })
                .setNegativeButton("取消", null)
                .create();
        showDark(dialog);
    }

    // ==================== 保存与退出 ====================

    private void saveAndFinish() {
        if (cfg.items.isEmpty()) {
            toast("至少保留一个按钮");
            return;
        }
        cfg.save(this);
        toast("已保存，下次进入游戏生效");
        setResult(RESULT_OK);
        finish();
    }

    private void confirmExit() {
        if (!dirty) {
            finish();
            return;
        }
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("放弃修改？")
                .setMessage("有未保存的改动，退出后会丢失。")
                .setPositiveButton("放弃退出", (d, w) -> finish())
                .setNegativeButton("继续编辑", null)
                .create();
        showDark(dialog);
    }

    @Override
    public void onBackPressed() {
        if (selected != null || toggleSelected) {
            selected = null;
            toggleSelected = false;
            applySelectionHighlight();
            rebuildToolbar();
            updateHint();
            return;
        }
        confirmExit();
    }

    // ==================== 小工具 ====================

    private ScrollView wrapScroll(View content) {
        ScrollView sc = new ScrollView(this);
        sc.addView(content);
        return sc;
    }

    private TextView formLabel(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(11);
        t.setTextColor(0xFF9A9AA0);
        t.setPadding(0, OnsButtonRenderer.dp(this, 8), 0, OnsButtonRenderer.dp(this, 3));
        return t;
    }

    private EditText formEdit(String value) {
        EditText e = new EditText(this);
        e.setText(value == null ? "" : value);
        e.setTextSize(13);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT);
        return e;
    }

    private Spinner formSpinner(String[] values, int selectedIndex) {
        Spinner sp = new Spinner(this);
        ArrayAdapter<String> ad = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, values);
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp.setAdapter(ad);
        if (selectedIndex >= 0 && selectedIndex < values.length) sp.setSelection(selectedIndex);
        return sp;
    }

    /** 全屏 Activity 上弹对话框要保持沉浸式，否则状态栏会闪出来。 */
    private void showDark(AlertDialog dialog) {
        Window w = dialog.getWindow();
        if (w != null) {
            w.setFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        }
        dialog.show();
        if (w != null) {
            w.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
            w.getDecorView().setSystemUiVisibility(
                    getWindow().getDecorView().getSystemUiVisibility());
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED);
        }
    }

    private static int indexOf(String[] arr, String v) {
        for (int i = 0; i < arr.length; i++) {
            if (arr[i].equals(v)) return i;
        }
        return 0;
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}