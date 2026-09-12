package com.yuki.yukihub.bigscreen;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Color;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AnimationUtils;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.yuki.yukihub.R;

import java.util.ArrayList;
import java.util.List;

/**
 * 大屏左侧筛选栏 —— 「图标窄栏 ⇄ 展开栏」带动画的可切换形态（spec §S2-2）。
 *
 * <p>行为：
 * <ul>
 *   <li>默认 <b>72dp 纯图标</b></li>
 *   <li>焦点进入侧栏 → <b>整栏动画展开到 250dp</b>（图标 + 文字 + 计数）</li>
 *   <li>焦点离开侧栏 → <b>动画收回 72dp</b>（先收字、后收框，避免文字被压扁）</li>
 *   <li>可"钉住"展开（{@link #setPinned(boolean)}），钉住后焦点离开也保持展开</li>
 * </ul>
 *
 * <p>动画参数（spec §S2-2）：
 * 宽度 220ms FastOutSlowIn；文字 alpha/位移 180ms（延迟 60ms）；
 * 收起时文字 140ms 先走，宽度 200ms 延后 80ms 跟。
 */
public class BigScreenRailView extends LinearLayout {

    /** 侧栏条目 */
    public static class Entry {
        public final String id;       // ALL / FAV / RECENT / PLAYING / DONE / TODO / ENGINE:xxx
        public final String label;
        public final int iconRes;
        public int count;
        public boolean active;        // 当前生效的筛选
        public boolean selectable = true;

        public Entry(String id, String label, int iconRes) {
            this.id = id;
            this.label = label;
            this.iconRes = iconRes;
        }
    }

    public interface Listener {
        /** 焦点在侧栏内移动（用于预览 / 状态显示） */
        void onEntryFocused(Entry entry);

        /** 按下确认（应用筛选） */
        void onEntrySelected(Entry entry);
    }

    // ===== 尺寸（默认值；运行时由 BigScreenSizes 按屏幕高度覆盖，见 setMetrics）=====
    private static final float COLLAPSED_WIDTH_DP = 72f;
    private static final float EXPANDED_WIDTH_DP = 250f;
    private static final float ITEM_HEIGHT_DP = 50f;
    private static final float ICON_BOX_DP = 46f;
    private static final float LABEL_SHIFT_DP = 6f;

    private float collapsedWidthDp = COLLAPSED_WIDTH_DP;
    private float expandedWidthDp = EXPANDED_WIDTH_DP;
    private float itemHeightDp = ITEM_HEIGHT_DP;
    private float iconBoxDp = ICON_BOX_DP;

    // ===== 动画时长 =====
    private static final long WIDTH_EXPAND_MS = 220L;
    private static final long WIDTH_COLLAPSE_MS = 200L;
    private static final long TEXT_EXPAND_MS = 180L;
    private static final long TEXT_COLLAPSE_MS = 140L;
    private static final long TEXT_EXPAND_DELAY_MS = 60L;
    private static final long WIDTH_COLLAPSE_DELAY_MS = 80L;

    private final List<Entry> entries = new ArrayList<>();
    private final List<View> itemViews = new ArrayList<>();
    private final List<TextView> labelViews = new ArrayList<>();
    private final List<TextView> countViews = new ArrayList<>();

    private Listener listener;
    private int focusedIndex = -1;
    private boolean expanded = false;
    private boolean pinned = false;
    private boolean zoneFocused = false;

    private ValueAnimator widthAnimator;
    private final Interpolator fastOutSlowIn =
            AnimationUtils.loadInterpolator(getContextSafe(), android.R.interpolator.fast_out_slow_in);

    public BigScreenRailView(Context context) { this(context, null); }

    public BigScreenRailView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setOrientation(VERTICAL);
        setClipChildren(false);
        setPadding(0, dp(6), 0, dp(6));
        setLayoutParams(new ViewGroup.LayoutParams(dp(collapsedWidthDp), ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private Context getContextSafe() { return getContext(); }

    public void setListener(Listener listener) { this.listener = listener; }

    /**
     * 按屏幕尺寸注入度量（M4 适配：横屏手机上条目高会被压到 ~30dp，7 个分类才铺得下）。
     * 已建好的条目会就地更新，不需要重建（避免焦点闪烁）。
     */
    public void setMetrics(int collapsedW, int expandedW, int itemH, int iconBox) {
        this.collapsedWidthDp = collapsedW;
        this.expandedWidthDp = expandedW;
        this.itemHeightDp = itemH;
        this.iconBoxDp = iconBox;
        applyMetricsToChildren();
        applyWidth(expanded ? expandedWidthPx() : collapsedWidthPx(), false);
    }

    private void applyMetricsToChildren() {
        final int iconSize = Math.round(iconBoxDp * 0.44f);
        for (View item : itemViews) {
            ViewGroup.LayoutParams lp = item.getLayoutParams();
            if (lp != null) {
                lp.height = dp(itemHeightDp);
                item.setLayoutParams(lp);
            }
            if (!(item instanceof ViewGroup)) { continue; }
            ViewGroup itemGroup = (ViewGroup) item;
            if (itemGroup.getChildCount() == 0) { continue; }
            View iconBox = itemGroup.getChildAt(0);
            ViewGroup.LayoutParams ilp = iconBox.getLayoutParams();
            if (ilp != null) {
                ilp.width = dp(iconBoxDp);
                iconBox.setLayoutParams(ilp);
            }
            if (iconBox instanceof ViewGroup && ((ViewGroup) iconBox).getChildCount() > 0) {
                View icon = ((ViewGroup) iconBox).getChildAt(0);
                ViewGroup.LayoutParams ip = icon.getLayoutParams();
                if (ip instanceof FrameLayout.LayoutParams) {
                    ip.width = dp(iconSize);
                    ip.height = dp(iconSize);
                    icon.setLayoutParams(ip);
                }
            }
        }
    }

    public List<Entry> entries() { return entries; }

    // ================= 构建 =================

    /** 设置条目并重建视图（保留当前焦点/选中态） */
    public void setEntries(List<Entry> newEntries) {
        entries.clear();
        if (newEntries != null) { entries.addAll(newEntries); }
        rebuild();
    }

    /** 仅刷新计数与选中态（不重建视图，避免焦点闪烁） */
    public void refreshStates() {
        for (int i = 0; i < entries.size() && i < itemViews.size(); i++) {
            Entry e = entries.get(i);
            View item = itemViews.get(i);
            TextView count = countViews.get(i);
            if (count != null) {
                count.setText(e.count > 0 ? String.valueOf(e.count) : "");
            }
            item.setBackgroundResource(e.active ? R.drawable.bs_rail_item_active : R.drawable.bs_rail_item);
            if (i < labelViews.size() && labelViews.get(i) != null) {
                labelViews.get(i).setTextColor(ContextCompat.getColor(getContext(),
                        e.active ? R.color.bs_text : R.color.bs_text_muted));
            }
        }
    }

    private void rebuild() {
        removeAllViews();
        itemViews.clear();
        labelViews.clear();
        countViews.clear();

        for (int i = 0; i < entries.size(); i++) {
            final Entry entry = entries.get(i);
            final int index = i;

            LinearLayout item = new LinearLayout(getContext());
            item.setOrientation(HORIZONTAL);
            item.setGravity(Gravity.CENTER_VERTICAL);
            LayoutParams lp = new LayoutParams(LayoutParams.MATCH_PARENT, dp(itemHeightDp));
            lp.bottomMargin = dp(2);
            item.setLayoutParams(lp);
            item.setBackgroundResource(entry.active
                    ? R.drawable.bs_rail_item_active : R.drawable.bs_rail_item);
            item.setClickable(true);
            // 触摸：点一下就应用该筛选（手柄则靠方向键 + Ⓐ）
            item.setOnClickListener(v -> {
                BigScreenSound.confirm();       // M12：触摸点侧栏也要发声
                focusedIndex = index;
                applyFocusVisual();
                // 触摸点击 = 手柄的「焦点移过去 + Ⓐ」，所以两个回调都发
                if (listener != null) {
                    listener.onEntryFocused(entry);
                    listener.onEntrySelected(entry);
                }
            });

            // 图标（固定宽度，保证收起时图标位置不跳）
            FrameLayout iconBox = new FrameLayout(getContext());
            LayoutParams iconLp = new LayoutParams(dp(iconBoxDp), LayoutParams.MATCH_PARENT);
            iconBox.setLayoutParams(iconLp);
            ImageView icon = new ImageView(getContext());
            final int iconSize = Math.round(iconBoxDp * 0.44f);
            FrameLayout.LayoutParams iconInner =
                    new FrameLayout.LayoutParams(dp(iconSize), dp(iconSize), Gravity.CENTER);
            icon.setLayoutParams(iconInner);
            icon.setImageResource(entry.iconRes);
            icon.setColorFilter(ContextCompat.getColor(getContext(),
                    entry.active ? R.color.bs_focus : R.color.bs_text_muted));
            iconBox.addView(icon);
            item.addView(iconBox);

            // 文字（收起时 alpha 0，不占可见空间）
            TextView label = new TextView(getContext());
            LayoutParams labelLp = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
            label.setLayoutParams(labelLp);
            label.setSingleLine(true);
            label.setText(entry.label);
            label.setTextSize(Math.max(11f, itemHeightDp * 0.30f));
            label.setTextColor(ContextCompat.getColor(getContext(),
                    entry.active ? R.color.bs_text : R.color.bs_text_muted));
            label.setAlpha(expanded ? 1f : 0f);
            label.setTranslationX(expanded ? 0f : -dp(LABEL_SHIFT_DP));
            if (!expanded) { label.setVisibility(GONE); }
            item.addView(label);

            // 计数
            TextView count = new TextView(getContext());
            LayoutParams countLp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
            countLp.rightMargin = dp(10);
            count.setLayoutParams(countLp);
            count.setText(entry.count > 0 ? String.valueOf(entry.count) : "");
            count.setTextSize(12f);
            count.setTextColor(Color.parseColor("#68749A"));
            count.setAlpha(expanded ? 1f : 0f);
            if (!expanded) { count.setVisibility(GONE); }
            item.addView(count);

            itemViews.add(item);
            labelViews.add(label);
            countViews.add(count);
            addView(item);
        }

        applyWidth(expanded ? expandedWidthPx() : collapsedWidthPx(), false);
        applyFocusVisual();
        refreshStates();
    }

    // ================= 焦点 =================

    public int focusedIndex() { return focusedIndex; }

    public Entry focusedEntry() {
        if (focusedIndex < 0 || focusedIndex >= entries.size()) { return null; }
        return entries.get(focusedIndex);
    }

    /** 设置侧栏内的焦点条目 */
    public void setFocusedIndex(int index) {
        if (entries.isEmpty()) { return; }
        focusedIndex = Math.max(0, Math.min(index, entries.size() - 1));
        applyFocusVisual();
        if (listener != null && focusedEntry() != null) {
            listener.onEntryFocused(focusedEntry());
        }
    }

    /** 上下移动焦点（循环） */
    public void moveFocus(int delta) {
        if (entries.isEmpty()) { return; }
        int size = entries.size();
        int next = focusedIndex;
        for (int k = 0; k < size; k++) {
            next = (next + delta + size) % size;
            if (entries.get(next).selectable) { break; }
        }
        setFocusedIndex(next);
    }

    private void applyFocusVisual() {
        for (int i = 0; i < itemViews.size(); i++) {
            View item = itemViews.get(i);
            Entry e = entries.get(i);
            boolean focused = zoneFocused && i == focusedIndex;
            // 焦点态：粉描边；选中态（active）：同样有描边但更含蓄
            if (focused) {
                item.setBackgroundResource(R.drawable.bs_rail_item_active);
                item.animate().scaleX(1.03f).scaleY(1.03f).setDuration(160L)
                        .setInterpolator(new DecelerateInterpolator()).start();
            } else {
                item.setBackgroundResource(e.active ? R.drawable.bs_rail_item_active : R.drawable.bs_rail_item);
                item.animate().scaleX(1f).scaleY(1f).setDuration(160L)
                        .setInterpolator(new DecelerateInterpolator()).start();
            }
        }
    }

    // ================= 展开 / 收起 =================

    public boolean isExpanded() { return expanded; }

    public void setPinned(boolean pinned) {
        this.pinned = pinned;
        if (!pinned && !zoneFocused) { collapse(); } else if (pinned) { expand(); }
        // M14：钉住 / 取消钉住也要通知让位
        if (expandListener != null) { expandListener.onRailExpandChanged(pinned || zoneFocused); }
    }

    public boolean isPinned() { return pinned; }

    /** 焦点进入 / 离开侧栏（spec §S2-2 的核心交互） */
    public void setZoneFocused(boolean focused) {
        this.zoneFocused = focused;
        applyFocusVisual();
        if (focused) {
            expand();
        } else if (!pinned) {
            collapse();
        }
        // M14：展开会把宽度从 72dp 拉到 250dp，直接压住信息浮层里的游戏名 ——
        // 通知外部让位（淡出信息层），避免"文字叠文字"的混乱观感。
        if (expandListener != null) { expandListener.onRailExpandChanged(focused || pinned); }
    }

    /** 侧栏展开状态回调（M14） */
    public interface ExpandListener {
        void onRailExpandChanged(boolean expanded);
    }

    private ExpandListener expandListener;

    public void setExpandListener(ExpandListener l) { this.expandListener = l; }

    public void expand() {
        if (expanded) { return; }
        expanded = true;

        // 1) 先让文字可见（透明）→ 宽度先开框
        for (int i = 0; i < labelViews.size(); i++) {
            TextView label = labelViews.get(i);
            label.setVisibility(VISIBLE);
            label.setAlpha(0f);
            label.setTranslationX(-dp(LABEL_SHIFT_DP));
            TextView count = countViews.get(i);
            count.setVisibility(VISIBLE);
            count.setAlpha(0f);
        }
        animateWidth(expandedWidthPx(), WIDTH_EXPAND_MS, 0L, null);

        // 2) 延迟 60ms 后文字淡入并右移到位（先开框、后显字）
        postDelayed(() -> {
            if (!expanded) { return; }
            for (int i = 0; i < labelViews.size(); i++) {
                TextView label = labelViews.get(i);
                label.animate().alpha(1f).translationX(0f).setDuration(TEXT_EXPAND_MS)
                        .setInterpolator(new DecelerateInterpolator()).start();
                countViews.get(i).animate().alpha(1f).setDuration(TEXT_EXPAND_MS).start();
            }
        }, TEXT_EXPAND_DELAY_MS);
    }

    public void collapse() {
        if (!expanded) { return; }
        expanded = false;

        // 1) 文字先淡出左移（先收字）
        for (int i = 0; i < labelViews.size(); i++) {
            TextView label = labelViews.get(i);
            label.animate().alpha(0f).translationX(-dp(LABEL_SHIFT_DP))
                    .setDuration(TEXT_COLLAPSE_MS).start();
            countViews.get(i).animate().alpha(0f).setDuration(TEXT_COLLAPSE_MS).start();
        }

        // 2) 80ms 后收框，收完把文字彻底 GONE（不占位）
        postDelayed(() -> {
            if (expanded) { return; }
            animateWidth(collapsedWidthPx(), WIDTH_COLLAPSE_MS, 0L, () -> {
                if (expanded) { return; }
                for (TextView label : labelViews) { label.setVisibility(GONE); }
                for (TextView count : countViews) { count.setVisibility(GONE); }
            });
        }, WIDTH_COLLAPSE_DELAY_MS);
    }

    private void animateWidth(final int target, long duration, long startDelay, final Runnable onEnd) {
        if (widthAnimator != null) { widthAnimator.cancel(); }

        final ViewGroup.LayoutParams lp = getLayoutParams();
        if (lp == null) { return; }
        final int start = lp.width > 0 ? lp.width : collapsedWidthPx();

        widthAnimator = ValueAnimator.ofInt(start, target);
        widthAnimator.setDuration(duration);
        widthAnimator.setStartDelay(startDelay);
        widthAnimator.setInterpolator(fastOutSlowIn);
        widthAnimator.addUpdateListener(a -> {
            lp.width = (int) a.getAnimatedValue();
            setLayoutParams(lp);
        });
        widthAnimator.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                lp.width = target;
                setLayoutParams(lp);
                if (onEnd != null) { onEnd.run(); }
            }
        });
        widthAnimator.start();
    }

    private void applyWidth(int width, boolean animate) {
        ViewGroup.LayoutParams lp = getLayoutParams();
        if (lp == null) { return; }
        lp.width = width;
        setLayoutParams(lp);
    }

    public int collapsedWidthPx() { return dp(collapsedWidthDp); }
    public int expandedWidthPx() { return dp(expandedWidthDp); }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}