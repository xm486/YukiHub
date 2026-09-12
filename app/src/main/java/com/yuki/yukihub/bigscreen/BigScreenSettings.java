package com.yuki.yukihub.bigscreen;

import android.app.Activity;
import android.graphics.Color;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.yuki.yukihub.R;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 大屏设置面板（spec §S6）—— 五分区 × 若干条目。
 *
 * <p>本类只负责**界面与焦点**；每个条目的"当前值"和"按下做什么"由调用方
 * （{@code BigScreenActivity}）以 {@link Item} 传进来 —— 设置逻辑集中在一处，方便审。
 *
 * <p>交互：← → 切换分区 ｜ ↑↓ 选择条目 ｜ Ⓐ 修改（数值项会弹出 S8 选择器）｜ Ⓑ 关闭
 */
public class BigScreenSettings {

    /** 一个设置条目：label + 当前值（可空）+ 按下动作 */
    public static class Item {
        public final String label;
        public final Supplier<String> value;
        public final Runnable action;

        public Item(String label, Supplier<String> value, Runnable action) {
            this.label = label;
            this.value = value;
            this.action = action;
        }
    }

    /** 一个分区 */
    public static class Section {
        public final String name;
        public final List<Item> items;

        public Section(String name, List<Item> items) {
            this.name = name;
            this.items = items;
        }
    }

    public interface Listener {
        void onShown();

        void onHidden();

        /** 值发生变化（用于即时应用设置：音效 / 性能档 / 提示条…） */
        void onChanged();
    }

    private static final int COL_SECTION = 0;
    private static final int COL_ITEM = 1;

    private final Activity activity;
    private final FrameLayout root;
    private final LinearLayout sectionsBox;
    private final LinearLayout itemsBox;
    /** M14：条目滚动容器 —— 手柄焦点移到屏幕外时要跟着滚 */
    private final android.widget.ScrollView itemsScroll;
    private final TextView sectionTitle;
    private final TextView hintView;
    /** M16：关闭按钮（仅触摸模式显示） */
    private final TextView closeBtn;
    /** M16：左侧分区列（宽度按屏幕缩放） */
    private final View leftCol;
    private final Listener listener;

    private final List<Section> sections = new ArrayList<>();
    private final List<View> sectionViews = new ArrayList<>();
    private final List<View> itemViews = new ArrayList<>();

    private int column = COL_SECTION;
    private int sectionIndex = 0;
    private int itemIndex = 0;

    // 行高（默认竖屏尺寸；横屏手机上由 setRowHeights 压扁，否则 5 个分区放不下）
    private int sectionRowH = 52;
    private int itemRowH = 54;

    /** 按屏幕高度注入行高（M4 横屏适配） */
    public void setRowHeights(int sectionH, int itemH) {
        this.sectionRowH = Math.max(30, sectionH);
        this.itemRowH = Math.max(32, itemH);
        rebuildSections();
        rebuildItems();
        applyFocus();
    }

    public BigScreenSettings(Activity activity, FrameLayout container, Listener listener) {
        this.activity = activity;
        this.listener = listener;
        root = (FrameLayout) LayoutInflater.from(activity).inflate(R.layout.view_bs_settings, null);
        sectionsBox = root.findViewById(R.id.bsSetSections);
        itemsBox = root.findViewById(R.id.bsSetItems);
        itemsScroll = root.findViewById(R.id.bsSetItemsScroll);
        // M16：点面板外空白 = 关闭。
        // root 是**铺满全屏**的容器，它自己不可点击时触摸会**穿透到下面的游戏卡片**
        // （用户反馈"和着你就只是做了层透明布？"）—— 这里让它消费点击。
        root.setClickable(true);
        root.setOnClickListener(v -> { if (isVisible()) { BigScreenSound.open(); hide(); } });
        // M16：右上角"关闭"按钮 —— 仅触摸模式显示（手柄按 Ⓑ 即可）
        closeBtn = new TextView(activity);
        closeBtn.setText("关闭");
        closeBtn.setTextSize(14f);
        closeBtn.setTextColor(0xFFD5DCF0);
        closeBtn.setPadding(dp(18), dp(10), dp(18), dp(10));
        closeBtn.setBackgroundResource(R.drawable.bg_bs_glass);
        closeBtn.setClickable(true);
        closeBtn.setOnClickListener(v -> { BigScreenSound.open(); hide(); });
        closeBtn.setVisibility(View.GONE);
        FrameLayout.LayoutParams cbLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cbLp.gravity = Gravity.END | Gravity.TOP;
        cbLp.topMargin = dp(18);
        cbLp.rightMargin = dp(24);
        closeBtn.setLayoutParams(cbLp);
        root.addView(closeBtn);
        sectionTitle = root.findViewById(R.id.bsSetSectionTitle);
        hintView = root.findViewById(R.id.bsSetHint);
        leftCol = root.findViewById(R.id.bsSetLeftCol);
        container.addView(root);
    }

    /**
     * M16：面板度量 —— 左右内边距 / 左侧分区列宽按屏幕短边缩放。
     *
     * <p>之前是硬编码（52dp / 228dp），从来没跟着 M6 的"整体按屏幕缩放"走，
     * 在小屏横屏上就显得越来越宽。
     */
    public void setMetrics(int padH, int colW) {
        final View card = root != null && root.getChildCount() > 0 ? root.getChildAt(0) : null;
        if (card != null) {
            card.setPadding(padH, card.getPaddingTop(), padH, card.getPaddingBottom());
        }
        if (leftCol != null && leftCol.getLayoutParams() != null) {
            ViewGroup.LayoutParams lp = leftCol.getLayoutParams();
            lp.width = colW;
            leftCol.setLayoutParams(lp);
        }
    }

    /** M16：是否显示触摸专用 UI（关闭按钮） */
    public void setTouchUi(boolean touch) {
        if (closeBtn != null) { closeBtn.setVisibility(touch ? View.VISIBLE : View.GONE); }
    }

    public void setSections(List<Section> newSections) {
        sections.clear();
        if (newSections != null) { sections.addAll(newSections); }
        sectionIndex = Math.min(sectionIndex, Math.max(0, sections.size() - 1));
        rebuildSections();
        rebuildItems();
    }

    public boolean isVisible() { return root.getVisibility() == View.VISIBLE; }

    /**
     * 面板内容区域（M15）。
     *
     * <p>注意：{@code root}（bsSetRoot）是**铺满全屏**的容器，拿它做判断会永远"在面板内"。
     * 真正的内容卡片是它的第一个子 View，用那个来判断"点空白关闭"。
     */
    public View contentView() {
        if (root == null || root.getChildCount() == 0) { return null; }
        return root.getChildAt(0);
    }

    public void show() {
        root.setVisibility(View.VISIBLE);
        root.setAlpha(0f);
        root.animate().alpha(1f).setDuration(200L)
                .setInterpolator(new DecelerateInterpolator()).start();
        column = COL_SECTION;
        rebuildSections();
        rebuildItems();
        applyFocus();
        if (listener != null) { listener.onShown(); }
    }

    public void hide() {
        root.animate().alpha(0f).setDuration(160L)
                .withEndAction(() -> root.setVisibility(View.GONE)).start();
        if (listener != null) { listener.onHidden(); }
    }

    /** 值变化后刷新右侧数值显示（保持焦点位置） */
    public void refreshItems() {
        rebuildItems();
        applyFocus();
    }

    // ================= 输入 =================

    /** @return true 表示已消费 */
    public boolean handleIntent(InputRouter.Intent intent) {
        if (!isVisible() || intent == null) { return false; }
        switch (intent) {
            case UP:
                move(-1);
                return true;
            case DOWN:
                move(1);
                return true;
            case LEFT:
                if (column != COL_SECTION) {
                    column = COL_SECTION;
                    applyFocus();
                }
                return true;
            case RIGHT:
                if (column != COL_ITEM && !currentItems().isEmpty()) {
                    column = COL_ITEM;
                    applyFocus();
                }
                return true;
            case CONFIRM:
                if (column == COL_SECTION) {
                    if (!currentItems().isEmpty()) {
                        column = COL_ITEM;
                        applyFocus();
                    }
                } else {
                    runCurrent();
                }
                return true;
            case BACK:
                hide();
                return true;
            default:
                return true;
        }
    }

    private void move(int delta) {
        if (column == COL_SECTION) {
            if (sections.isEmpty()) { return; }
            sectionIndex = (sectionIndex + delta + sections.size()) % sections.size();
            itemIndex = 0;
            rebuildItems();
        } else {
            List<Item> items = currentItems();
            if (items.isEmpty()) { return; }
            itemIndex = (itemIndex + delta + items.size()) % items.size();
        }
        applyFocus();
    }

    private void runCurrent() {
        List<Item> items = currentItems();
        if (itemIndex < 0 || itemIndex >= items.size()) { return; }
        Item item = items.get(itemIndex);
        // 先把焦点画到这一行，再执行动作（动作可能弹选择器 / 改值）
        applyFocus();
        if (item.action != null) { item.action.run(); }
        if (listener != null) { listener.onChanged(); }
    }

    private List<Item> currentItems() {
        if (sectionIndex < 0 || sectionIndex >= sections.size()) { return new ArrayList<>(); }
        return sections.get(sectionIndex).items;
    }

    // ================= 构建 =================

    private void rebuildSections() {
        sectionsBox.removeAllViews();
        sectionViews.clear();
        for (int i = 0; i < sections.size(); i++) {
            final int idx = i;
            TextView tv = new TextView(activity);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(sectionRowH));
            lp.bottomMargin = dp(2);
            tv.setLayoutParams(lp);
            tv.setGravity(Gravity.CENTER_VERTICAL);
            tv.setPadding(dp(14), 0, dp(14), 0);
            tv.setText(sections.get(i).name);
            tv.setTextSize(16f);
            tv.setClickable(true);
            tv.setOnClickListener(v -> {
                BigScreenSound.confirm();   // M12：触摸分区也要有音效
                sectionIndex = idx;
                column = COL_ITEM;
                rebuildItems();
                applyFocus();
            });
            sectionsBox.addView(tv);
            sectionViews.add(tv);
        }
    }

    private void rebuildItems() {
        itemsBox.removeAllViews();
        itemViews.clear();
        if (sectionIndex >= 0 && sectionIndex < sections.size()) {
            sectionTitle.setText(sections.get(sectionIndex).name);
        }
        List<Item> items = currentItems();
        for (int i = 0; i < items.size(); i++) {
            final int idx = i;
            final Item item = items.get(i);

            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(itemRowH));
            lp.bottomMargin = dp(3);
            row.setLayoutParams(lp);
            row.setPadding(dp(14), 0, dp(14), 0);
            row.setClickable(true);
            row.setOnClickListener(v -> {
                BigScreenSound.confirm();   // M12：触摸条目也要有音效
                column = COL_ITEM;
                itemIndex = idx;
                applyFocus();
                if (item.action != null) { item.action.run(); }
                if (listener != null) { listener.onChanged(); }
            });

            TextView label = new TextView(activity);
            LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            label.setLayoutParams(labelLp);
            label.setText(item.label);
            label.setTextSize(15.5f);
            label.setTextColor(Color.parseColor("#D5DCF0"));
            row.addView(label);

            TextView value = new TextView(activity);
            value.setText(item.value == null ? "" : String.valueOf(item.value.get()));
            value.setTextSize(13.5f);
            value.setTextColor(ContextCompat.getColor(activity, R.color.bs_text_muted));
            row.addView(value);

            itemsBox.addView(row);
            itemViews.add(row);
        }
    }

    private void applyFocus() {
        for (int i = 0; i < sectionViews.size(); i++) {
            View v = sectionViews.get(i);
            boolean focused = column == COL_SECTION && i == sectionIndex;
            boolean active = i == sectionIndex;
            v.setBackgroundResource(focused ? R.drawable.bg_bs_cell_focused : 0);
            TextView tv = (TextView) v;
            tv.setTextColor(ContextCompat.getColor(activity,
                    focused ? R.color.bs_text
                            : (active ? R.color.bs_focus : R.color.bs_text_muted)));
            tv.animate().translationX(focused ? dp(4) : 0f).setDuration(150L).start();
        }
        for (int i = 0; i < itemViews.size(); i++) {
            View v = itemViews.get(i);
            boolean focused = column == COL_ITEM && i == itemIndex;
            v.setBackgroundResource(focused ? R.drawable.bg_bs_cell_focused : 0);
            LinearLayout row = (LinearLayout) v;
            for (int c = 0; c < row.getChildCount(); c++) {
                View child = row.getChildAt(c);
                if (!(child instanceof TextView)) { continue; }
                TextView tv = (TextView) child;
                if (c == 0) {
                    tv.setTextColor(Color.parseColor(focused ? "#FFFFFF" : "#D5DCF0"));
                } else {
                    tv.setTextColor(ContextCompat.getColor(activity,
                            focused ? R.color.bs_focus : R.color.bs_text_muted));
                }
            }
        }
        if (hintView != null) {
            hintView.setText("← → 切换分区　↑↓ 选择条目　Ⓐ 修改　Ⓑ 关闭");
        }
        scrollFocusIntoView();
    }

    /**
     * 把当前聚焦的条目滚进可视区（M14）。
     *
     * <p>之前 {@code applyFocus()} 只改颜色，**完全没有滚动** —— 焦点移到屏幕下方
     * （比如「视觉」分区的 PV 相关项）时画面不动，条目被截在屏幕外看不见。
     */
    private void scrollFocusIntoView() {
        if (column != COL_ITEM) { return; }
        if (itemIndex < 0 || itemIndex >= itemViews.size()) { return; }
        final View target = itemViews.get(itemIndex);
        if (target == null || itemsScroll == null) { return; }
        target.post(() -> {
            if (itemsScroll == null) { return; }
            int top = target.getTop();
            int bottom = top + target.getHeight();
            int viewTop = itemsScroll.getScrollY();
            int viewBottom = viewTop + itemsScroll.getHeight();
            int pad = dp(28);   // 上下各留一点余量，别贴边
            int dy = 0;
            if (bottom + pad > viewBottom) { dy = bottom + pad - viewBottom; }
            else if (top - pad < viewTop) { dy = top - pad - viewTop; }
            if (dy != 0) { itemsScroll.smoothScrollBy(0, dy); }
        });
    }

    private int dp(float value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}