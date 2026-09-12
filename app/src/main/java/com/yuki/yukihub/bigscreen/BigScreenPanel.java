package com.yuki.yukihub.bigscreen;

import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.yuki.yukihub.R;

import java.util.ArrayList;
import java.util.List;

/**
 * 通用浮层菜单（对应 spec §S4 游戏操作菜单 / §S5 主菜单 / §S8 选择器）。
 *
 * <p>面板从右侧滑入，自带焦点（↑↓ 移动、Ⓐ 确认、Ⓑ 关闭），
 * 支持分隔线与右侧附加文案。所有列表项都是 {@link Item}，动作由调用方以 Runnable 提供。
 */
public class BigScreenPanel {

    /** 菜单项 */
    public static class Item {
        public final String label;
        public final String sub;
        public final int iconRes;
        public final Runnable action;
        public final boolean separator;

        public Item(String label, String sub, int iconRes, Runnable action) {
            this.label = label;
            this.sub = sub;
            this.iconRes = iconRes;
            this.action = action;
            this.separator = false;
        }

        private Item() {
            this.label = null; this.sub = null; this.iconRes = 0;
            this.action = null; this.separator = true;
        }

        /** 分隔线 */
        public static Item sep() { return new Item(); }
    }

    public interface Listener {
        void onPanelShown();
        void onPanelHidden();
    }

    private final FrameLayout container;
    private final Listener listener;
    private final LinearLayout panel;
    private final TextView titleView;
    private final LinearLayout listView;
    /** 列表外层滚动容器（M6：菜单条目多了会超出屏幕，必须能滑） */
    private final ScrollView scroller;

    private final List<Item> items = new ArrayList<>();
    private final List<View> itemViews = new ArrayList<>();
    private int index = 0;
    private boolean visible = false;

    /** M16：面板外的遮罩层 —— 让面板有"压暗背景"的层次感，同时**拦截触摸**（否则会点到下面的游戏卡片） */
    private final View scrim;
    /** M16：关闭按钮 —— **仅触摸模式显示**（手柄用户按 Ⓑ 即可，看到它反而乱） */
    private final TextView closeBtn;
    /** M16：面板宽度（按屏幕短边缩放，不再硬编码 404dp） */
    private int panelWidthPx = -1;
    private int panelRightMarginPx = -1;

    public BigScreenPanel(FrameLayout container, Listener listener) {
        this.container = container;
        this.listener = listener;
        // 遮罩：先加 = 在面板下面
        scrim = new View(container.getContext());
        scrim.setBackgroundColor(0x99000000);
        scrim.setClickable(true);
        scrim.setOnClickListener(v -> hide());   // 点遮罩外区域 = 关闭
        scrim.setVisibility(View.GONE);
        // M14-1 的"点空白关闭"就是走这里
        FrameLayout.LayoutParams scrimLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        scrim.setLayoutParams(scrimLp);
        container.addView(scrim);
        panel = new LinearLayout(container.getContext());
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundResource(R.drawable.bg_bs_panel);
        panel.setPadding(0, dp(16), 0, dp(16));
        panel.setVisibility(View.GONE);
        panel.setClipToOutline(true);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                panelWidthPx > 0 ? panelWidthPx : dp(404),
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END | Gravity.CENTER_VERTICAL);
        lp.rightMargin = panelRightMarginPx > 0 ? panelRightMarginPx : dp(56);
        panel.setLayoutParams(lp);

        titleView = new TextView(container.getContext());
        titleView.setPadding(dp(24), 0, dp(24), dp(10));
        titleView.setTextSize(12f);
        titleView.setTextColor(Color.parseColor("#6E7BA0"));
        titleView.setLetterSpacing(0.18f);
        panel.addView(titleView);

        listView = new LinearLayout(container.getContext());
        listView.setOrientation(LinearLayout.VERTICAL);
        // 包一层 ScrollView：游戏操作菜单条目多（7+），矮屏上原来的固定面板会把底部条目吃掉
        scroller = new ScrollView(container.getContext());
        scroller.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        scroller.setFillViewport(false);
        scroller.setClipToPadding(false);
        scroller.setScrollbarFadingEnabled(false);
        scroller.addView(listView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        panel.addView(scroller);
        // M16：底部"关闭"按钮 —— 仅触摸模式显示
        closeBtn = new TextView(container.getContext());
        closeBtn.setText("关闭");
        closeBtn.setGravity(Gravity.CENTER);
        closeBtn.setTextSize(14f);
        closeBtn.setPadding(0, dp(12), 0, dp(12));
        closeBtn.setTextColor(0xFFD5DCF0);
        closeBtn.setClickable(true);
        closeBtn.setOnClickListener(v -> hide());
        closeBtn.setVisibility(View.GONE);
        closeBtn.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        panel.addView(closeBtn);
        container.addView(panel);
    }

    /** M16：面板度量（宽度按屏幕短边算，由 Activity 传入） */
    public void setPanelMetrics(int widthPx, int rightMarginPx) {
        this.panelWidthPx = widthPx;
        this.panelRightMarginPx = rightMarginPx;
        if (panel != null && panel.getLayoutParams() instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) panel.getLayoutParams();
            lp.width = widthPx;
            lp.rightMargin = rightMarginPx;
            panel.setLayoutParams(lp);
        }
    }

    /** M16：是否显示触摸专用 UI（关闭按钮）—— 手柄玩家不需要 */
    public void setTouchUi(boolean touch) {
        if (closeBtn != null) { closeBtn.setVisibility(touch ? View.VISIBLE : View.GONE); }
    }

    /**
     * 面板高度自适应：内容超过屏幕就限高（外层 ScrollView 接管滚动）。
     */
    private void applyPanelHeight() {
        if (panel == null || scroller == null) { return; }
        int avail = container.getHeight() > 0
                ? container.getHeight()
                : container.getResources().getDisplayMetrics().heightPixels;
        int maxH = Math.round(avail * 0.86f);
        int widthPx = panelWidthPx > 0 ? panelWidthPx : dp(404);
        int wSpec = View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.AT_MOST);
        int hSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        titleView.measure(wSpec, hSpec);
        listView.measure(wSpec, hSpec);
        int needed = titleView.getMeasuredHeight() + listView.getMeasuredHeight() + dp(32);
        ViewGroup.LayoutParams sp = scroller.getLayoutParams();
        int target = needed > maxH
                ? Math.max(dp(80), maxH - titleView.getMeasuredHeight() - dp(32))
                : ViewGroup.LayoutParams.WRAP_CONTENT;
        if (sp != null && sp.height != target) {
            sp.height = target;
            scroller.setLayoutParams(sp);
        }
    }

    // ================= 显示 / 隐藏 =================

    public boolean isVisible() { return visible; }

    /** 面板内容区域（M15：用于判断"点空白关闭"时手指是否落在面板内） */
    public View contentView() { return panel; }

    public void show(String title, List<Item> newItems) {
        items.clear();
        if (newItems != null) { items.addAll(newItems); }
        index = firstSelectable(0);
        titleView.setText(title == null ? "" : title);
        rebuildList();
        applyPanelHeight();
        if (!visible) {
            visible = true;
            // M16：遮罩一起淡入（压暗背景 + 拦截触摸，避免点到面板下面的游戏卡片）
            scrim.setVisibility(View.VISIBLE);
            scrim.setAlpha(0f);
            scrim.animate().alpha(1f).setDuration(180L).start();
            panel.setVisibility(View.VISIBLE);
            panel.setAlpha(0f);
            panel.setTranslationX(dp(20));
            panel.animate().alpha(1f).translationX(0f)
                    .setDuration(200L).setInterpolator(new DecelerateInterpolator()).start();
            if (listener != null) { listener.onPanelShown(); }
        }
        applyFocus();
    }

    public void hide() {
        if (!visible) { return; }
        visible = false;
        scrim.animate().alpha(0f).setDuration(140L)
                .withEndAction(() -> scrim.setVisibility(View.GONE)).start();
        panel.animate().alpha(0f).translationX(dp(20))
                .setDuration(160L).setInterpolator(new DecelerateInterpolator())
                .withEndAction(() -> panel.setVisibility(View.GONE)).start();
        if (listener != null) { listener.onPanelHidden(); }
    }

    // ================= 列表 =================

    private void rebuildList() {
        listView.removeAllViews();
        itemViews.clear();

        for (int i = 0; i < items.size(); i++) {
            final int idx = i;
            final Item item = items.get(i);

            if (item.separator) {
                View sep = new View(container.getContext());
                LinearLayout.LayoutParams sepLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
                sepLp.setMargins(dp(24), dp(8), dp(24), dp(8));
                sep.setLayoutParams(sepLp);
                sep.setBackgroundColor(Color.parseColor("#B32D3658"));
                listView.addView(sep);
                itemViews.add(sep);
                continue;
            }

            LinearLayout row = new LinearLayout(container.getContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(50));
            rowLp.setMargins(dp(12), 0, dp(12), dp(2));
            row.setLayoutParams(rowLp);
            row.setPadding(dp(14), 0, dp(14), 0);
            row.setClickable(true);
            row.setOnClickListener(v -> {
                BigScreenSound.confirm();   // M12：触摸条目也要有音效
                index = idx;
                applyFocus();
                if (item.action != null) { item.action.run(); }
            });

            if (item.iconRes != 0) {
                ImageView icon = new ImageView(container.getContext());
                LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(18), dp(18));
                iconLp.rightMargin = dp(14);
                icon.setLayoutParams(iconLp);
                icon.setImageResource(item.iconRes);
                icon.setColorFilter(Color.parseColor("#8090B8"));
                row.addView(icon);
            }

            TextView label = new TextView(container.getContext());
            LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            label.setLayoutParams(labelLp);
            label.setText(item.label);
            label.setTextSize(15.5f);
            label.setTextColor(Color.parseColor("#D5DCF0"));
            row.addView(label);

            if (item.sub != null && !item.sub.isEmpty()) {
                TextView sub = new TextView(container.getContext());
                sub.setText(item.sub);
                sub.setTextSize(12.5f);
                sub.setTextColor(Color.parseColor("#6E7BA0"));
                row.addView(sub);
            }

            listView.addView(row);
            itemViews.add(row);
        }
    }

    private void applyFocus() {
        for (int i = 0; i < itemViews.size(); i++) {
            View v = itemViews.get(i);
            Item it = items.get(i);
            if (it.separator) { continue; }
            boolean focused = i == index;
            v.setBackgroundResource(focused ? R.drawable.bg_bs_cell_focused : 0);
            if (focused) {
                v.animate().scaleX(1.02f).scaleY(1.02f).setDuration(150L).start();
            } else {
                v.animate().scaleX(1f).scaleY(1f).setDuration(150L).start();
            }
            if (v instanceof LinearLayout) {
                LinearLayout row = (LinearLayout) v;
                for (int c = 0; c < row.getChildCount(); c++) {
                    View child = row.getChildAt(c);
                    if (child instanceof TextView) {
                        ((TextView) child).setTextColor(Color.parseColor(
                                focused ? "#FFFFFF" : "#D5DCF0"));
                    } else if (child instanceof ImageView) {
                        ((ImageView) child).setColorFilter(ContextCompat.getColor(
                                container.getContext(),
                                focused ? R.color.bs_focus : R.color.bs_text_muted));
                    }
                }
            }
        }
        // 焦点行滚进可视区（菜单条目超屏时靠它保证"看得见选了哪条"）
        if (scroller != null && index >= 0 && index < itemViews.size()
                && itemViews.get(index) != null) {
            final View row = itemViews.get(index);
            scroller.post(() -> scroller.smoothScrollTo(0, Math.max(0, row.getTop() - dp(36))));
        }
    }

    // ================= 输入 =================

    /** @return true 表示事件已被面板消费 */
    public boolean handleIntent(InputRouter.Intent intent) {
        if (!visible || intent == null) { return false; }
        switch (intent) {
            case UP:
                move(-1);
                return true;
            case DOWN:
                move(1);
                return true;
            case CONFIRM: {
                Item it = current();
                if (it != null && it.action != null) { it.action.run(); }
                return true;
            }
            case BACK:
                hide();
                return true;
            default:
                return true; // 面板打开时吞掉其它输入，避免误操作到下层
        }
    }

    private void move(int delta) {
        if (items.isEmpty()) { return; }
        int size = items.size();
        int next = index;
        for (int k = 0; k < size; k++) {
            next = (next + delta + size) % size;
            if (!items.get(next).separator) { break; }
        }
        index = next;
        applyFocus();
    }

    private int firstSelectable(int from) {
        for (int i = Math.max(0, from); i < items.size(); i++) {
            if (!items.get(i).separator) { return i; }
        }
        return 0;
    }

    private Item current() {
        if (index < 0 || index >= items.size()) { return null; }
        Item it = items.get(index);
        return it.separator ? null : it;
    }

    private int dp(float v) {
        return Math.round(v * container.getResources().getDisplayMetrics().density);
    }
}