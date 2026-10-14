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
    /**
     * 图标边长占 iconBox 的比例 —— **按展开状态区分**。
     *
     * <p>收起态用 0.75：此时条目里只剩图标，图标应当撑满、与条目框视觉匹配。
     * （初版写死 0.44 是只为展开态考虑的，导致收起时「图标小小的，底框却很大」，
     * 实测框宽是图标的 4.5 倍。）
     *
     * <p>展开态用 0.62：iconBox 右侧要接 label + count，图标让位、留足文字宽度。
     *
     * <p>两者配合 {@code BigScreenSizes.railCollapsedW}（= iconBox + 10）后，
     * 框/图标比在各档屏幕上约 1.5~2.0 倍，是常见的图标按钮内边距比例。
     */
    private static final float ICON_RATIO_COLLAPSED = 0.75f;
    private static final float ICON_RATIO_EXPANDED = 0.62f;

    /** 当前展开状态下应使用的图标比例 */
    private float iconRatio() {
        return expanded ? ICON_RATIO_EXPANDED : ICON_RATIO_COLLAPSED;
    }

    /**
     * 按当前展开状态重算图标尺寸。
     * 收起态图标大（0.75）、展开态图标小（0.62），切换状态时要跟着变 ——
     * 否则收起后图标还停留在展开态的小尺寸，框大图标小的问题依旧。
     */
    private void applyIconSize() {
        final int iconSize = dp(Math.round(iconBoxDp * iconRatio()));
        for (View item : itemViews) {
            if (!(item instanceof ViewGroup)) { continue; }
            ViewGroup g = (ViewGroup) item;
            if (g.getChildCount() == 0) { continue; }
            View iconBox = g.getChildAt(0);
            if (!(iconBox instanceof ViewGroup)) { continue; }
            ViewGroup box = (ViewGroup) iconBox;
            if (box.getChildCount() == 0) { continue; }
            View icon = box.getChildAt(0);
            ViewGroup.LayoutParams lp = icon.getLayoutParams();
            if (lp != null) {
                lp.width = iconSize;
                lp.height = iconSize;
                icon.setLayoutParams(lp);
            }
        }
    }

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

    /**
     * 展开/收起的"文字动画代次"。
     *
     * <p>为什么需要：expand()/collapse() 都要 {@code postDelayed} 延迟 60/80ms 再动文字，
     * 而这两个延迟任务不会被自动取消 —— 用户快速「展开→收起→再展开」时，
     * 第一轮那个还没执行的任务会在**第二轮**里跑起来，把刚淡入的文字又按 alpha 0
     * 处理一遍（表现为文字闪一下、或干脆不出现）。
     *
     * <p>做法：每次切换状态都把代次 +1，延迟任务执行前先比对自己捕获的代次，
     * 不匹配就直接丢弃。比 removeCallbacks 更精确 —— 后者会把同一次切换里
     * 另一个合法的延迟任务也一起干掉。
     */
    private int textAnimGeneration = 0;

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
        // 尺寸重算后对齐也跟着当前展开状态刷新
        applyIconBoxGravity(expanded);
    }

    /**
     * 切换 iconBox 在条目里的水平对齐：展开态靠左（右侧让给文字），收起态居中。
     *
     * <p>为什么需要这个：条目本身是 MATCH_PARENT（= 侧栏全宽），收起时
     * 条目里只剩 iconBox 一个可见子项。若 iconBox 一直靠左，条目框就会明显
     * 偏在图标右边 —— 实机观感是「选中框比图标宽出一大截」。居中后框与图标同心，
     * 视觉上才是「一个按钮」。
     */
    private void applyIconBoxGravity(boolean expandState) {
        for (View item : itemViews) {
            if (!(item instanceof ViewGroup)) { continue; }
            ViewGroup g = (ViewGroup) item;
            if (g.getChildCount() == 0) { continue; }
            View iconBox = g.getChildAt(0);
            ViewGroup.LayoutParams lp = iconBox.getLayoutParams();
            if (lp instanceof LinearLayout.LayoutParams) {
                ((LinearLayout.LayoutParams) lp).gravity =
                        expandState ? Gravity.START : Gravity.CENTER_HORIZONTAL;
                iconBox.setLayoutParams(lp);
            }
        }
    }

    private void applyMetricsToChildren() {
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
        }
        // 图标尺寸统一由 applyIconSize 负责（它按当前展开状态算比例），
        // 避免这里再写一遍缩放公式、两处将来不一致。
        applyIconSize();
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
            // **必须把焦点态一起算进来**：这里原来只按 e.active 刷背景，
            // 而 rebuild() 是先 applyFocusVisual() 再 refreshStates() ——
            // 后者的无脑覆盖会把刚画好的焦点描边擦掉，表现是「切分类后
            // 手柄焦点高亮莫名消失」。判据与 applyFocusVisual 保持一致。
            boolean focused = zoneFocused && i == focusedIndex;
            boolean highlight = focused || e.active;
            item.setBackgroundResource(highlight
                    ? R.drawable.bs_rail_item_active : R.drawable.bs_rail_item);
            if (i < labelViews.size() && labelViews.get(i) != null) {
                labelViews.get(i).setTextColor(ContextCompat.getColor(getContext(),
                        highlight ? R.color.bs_text : R.color.bs_text_muted));
            }
            // 图标 tint 也要跟着刷：rebuild() 里按 entry.active 定了颜色，
            // 但焦点移动 / 筛选变化后要重算 —— 否则手柄焦点移上去图标不亮，
            // 或者切了分类图标还留着旧色。
            applyIconTint(i, highlight);
        }
    }

    /**
     * 刷新某条目的图标着色。焦点/选中 → bs_focus（粉），否则 bs_text_muted。
     *
     * <p>抽出来是因为有两处需要（refreshStates / applyFocusVisual），
     * 而 rebuild() 建视图时也用的是同一套判据 —— 三处必须一致。
     */
    private void applyIconTint(int index, boolean highlight) {
        if (index < 0 || index >= itemViews.size()) { return; }
        View item = itemViews.get(index);
        if (!(item instanceof ViewGroup)) { return; }
        ViewGroup g = (ViewGroup) item;
        if (g.getChildCount() == 0) { return; }
        View iconBox = g.getChildAt(0);
        if (!(iconBox instanceof ViewGroup)) { return; }
        ViewGroup box = (ViewGroup) iconBox;
        if (box.getChildCount() == 0) { return; }
        View icon = box.getChildAt(0);
        if (icon instanceof ImageView) {
            ((ImageView) icon).setColorFilter(ContextCompat.getColor(getContext(),
                    highlight ? R.color.bs_focus : R.color.bs_text_muted));
        }
    }

    private void rebuild() {
        removeAllViews();
        itemViews.clear();
        labelViews.clear();
        countViews.clear();

        // 条目数可能变了（如隐藏分类后重建），把焦点夹进新范围 ——
        // 否则 focusedIndex 会指向不存在的下标，applyFocusVisual 里
        // entries.get(i) 不会越界（它是按 itemViews 循环的），
        // 但 focusedEntry() 会返回 null，后续按 Ⓐ 无响应。
        if (!entries.isEmpty()) {
            focusedIndex = Math.max(0, Math.min(focusedIndex, entries.size() - 1));
        } else {
            focusedIndex = -1;
        }

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
                BigScreenSound.hapticKey(v);    // M22：触摸按钮统一补触感
                focusedIndex = index;
                applyFocusVisual();
                // 触摸点击 = 手柄的「焦点移过去 + Ⓐ」，所以两个回调都发
                if (listener != null) {
                    listener.onEntryFocused(entry);
                    listener.onEntrySelected(entry);
                }
            });

            // 图标（收起态居中；展开态靠左，给右侧文字让位）
            // 宽度固定（iconBoxDp）保证收起/展开时图标横向不跳；
            // 但水平位置要跟状态走，否则收起时条目框（= 侧栏全宽）会明显偏在图标右边。
            FrameLayout iconBox = new FrameLayout(getContext());
            LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(iconBoxDp), LayoutParams.MATCH_PARENT);
            iconLp.gravity = expanded ? Gravity.START : Gravity.CENTER_HORIZONTAL;
            iconBox.setLayoutParams(iconLp);
            ImageView icon = new ImageView(getContext());
            final int iconSize = Math.round(iconBoxDp * iconRatio());
            FrameLayout.LayoutParams iconInner =
                    new FrameLayout.LayoutParams(dp(iconSize), dp(iconSize), Gravity.CENTER);
            icon.setLayoutParams(iconInner);
            icon.setImageResource(entry.iconRes);
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
            // scaleX 也要跟展开状态对齐：展开态是 1.0（正常大小），收起态 0.86。
            // 漏掉的话，在展开状态下重建（如切分类触发 setEntries）文字会停在 1.0 之外的
            // 上一次的值，或收起态下没设过而残留。
            label.setScaleX(expanded ? 1f : 0.86f);
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
        applyIconBoxGravity(expanded);
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
            // 焦点态与选中态共用同一个背景资源（bs_rail_item_active 的粉描边），
            // 差别只在有无缩放动效 —— 手柄能看出"焦点在哪"，同时当前生效的分类
            // 也保持描边（失焦时不清掉 active，与 refreshStates 判据一致）。
            boolean highlight = focused || e.active;
            item.setBackgroundResource(highlight
                    ? R.drawable.bs_rail_item_active : R.drawable.bs_rail_item);
            applyIconTint(i, highlight);
            item.animate().cancel();
            if (focused) {
                item.animate().scaleX(1.03f).scaleY(1.03f).setDuration(160L)
                        .setInterpolator(new DecelerateInterpolator()).start();
            } else {
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

    /**
     * 展开：先宽度开框 + 文字让位，再文字淡入。
     *
     * <p>动效（用户要求「加一下动效」，在原有 220ms 宽度动画之上）：
     * <ul>
     *   <li>宽度：220ms fastOutSlowIn（原有）</li>
     *   <li>文字：淡入 + 右移到位 + 从 0.86 放大到 1.0 —— 加缩放是为了让
     *       「文字滑出来」更有实体感，纯 alpha 会显得飘</li>
     * </ul>
     *
     * <p>所有延迟任务都用 {@link #textAnimGeneration} 做代次守卫，防止快速连按时
     * 上一轮的延迟任务在新一轮里过期触发。
     */
    public void expand() {
        if (expanded) { return; }
        expanded = true;
        final int gen = ++textAnimGeneration;

        // 1) 先让文字可见（透明）→ 宽度先开框
        // iconBox 从「居中」切回「靠左」：展开态右侧要塞文字，图标必须让位到左边。
        // 跟宽度动画同步切换即可 —— 期间宽度还在变，视觉上不会跳。
        applyIconBoxGravity(true);
        applyIconSize();
        for (int i = 0; i < labelViews.size(); i++) {
            TextView label = labelViews.get(i);
            label.setVisibility(VISIBLE);
            label.setAlpha(0f);
            label.setScaleX(0.86f);
            label.setTranslationX(-dp(LABEL_SHIFT_DP));
            TextView count = countViews.get(i);
            count.setVisibility(VISIBLE);
            count.setAlpha(0f);
        }
        animateWidth(expandedWidthPx(), WIDTH_EXPAND_MS, 0L, null);

        // 2) 延迟 60ms 后文字淡入并右移到位（先开框、后显字）
        postDelayed(() -> {
            if (gen != textAnimGeneration || !expanded) { return; }
            for (int i = 0; i < labelViews.size(); i++) {
                TextView label = labelViews.get(i);
                label.animate().cancel();
                label.animate().alpha(1f).translationX(0f).scaleX(1f)
                        .setDuration(TEXT_EXPAND_MS)
                        .setInterpolator(new DecelerateInterpolator()).start();
                TextView count = countViews.get(i);
                count.animate().cancel();
                count.animate().alpha(1f).setDuration(TEXT_EXPAND_MS).start();
            }
        }, TEXT_EXPAND_DELAY_MS);
    }

    /**
     * 收起：文字先淡出左移并缩小，再收框。
     *
     * <p>动效与 {@link #expand()} 对称：文字淡出 + 左移 + 缩到 0.86（"收回去"的实体感）。
     */
    public void collapse() {
        if (!expanded) { return; }
        expanded = false;
        final int gen = ++textAnimGeneration;

        // 1) 文字先淡出左移缩小（先收字）
        for (int i = 0; i < labelViews.size(); i++) {
            TextView label = labelViews.get(i);
            label.animate().cancel();
            label.animate().alpha(0f).translationX(-dp(LABEL_SHIFT_DP)).scaleX(0.86f)
                    .setDuration(TEXT_COLLAPSE_MS).start();
            TextView count = countViews.get(i);
            count.animate().cancel();
            count.animate().alpha(0f).setDuration(TEXT_COLLAPSE_MS).start();
        }

        // 2) 80ms 后收框，收完把文字彻底 GONE（不占位）
        postDelayed(() -> {
            if (gen != textAnimGeneration || expanded) { return; }
            // 文字收完后图标回居中：收起态下条目里只剩图标，居中才与框视觉匹配
            applyIconBoxGravity(false);
            applyIconSize();
            animateWidth(collapsedWidthPx(), WIDTH_COLLAPSE_MS, 0L, () -> {
                if (gen != textAnimGeneration || expanded) { return; }
                for (TextView label : labelViews) { label.setVisibility(GONE); }
                for (TextView count : countViews) { count.setVisibility(GONE); }
            });
        }, WIDTH_COLLAPSE_DELAY_MS);
    }

    private void animateWidth(final int target, long duration, long startDelay, final Runnable onEnd) {
        if (widthAnimator != null) {
            // **先把旧监听摘掉再 cancel**：ValueAnimator.cancel() 会照常回调
            // onAnimationEnd（这是它和"自然结束"共用的出口）。若不摘，正在跑的
            // 收起动画的 onEnd（把文字设为 GONE）会在这个动画刚启动时误触发 ——
            // 表现是「快速收起→展开后，框展开了但文字全没了」。
            widthAnimator.removeAllListeners();
            widthAnimator.cancel();
        }

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
            /** 被打断（cancel）时不落到终值、也不跑回调 —— 那是新动画的地盘 */
            private boolean cancelled = false;

            @Override public void onAnimationCancel(Animator animation) { cancelled = true; }

            @Override public void onAnimationEnd(Animator animation) {
                if (cancelled) { return; }
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

    /**
     * 脱离窗口时清掉未执行的延迟任务与正在跑的动画。
     *
     * <p>大屏 Activity 销毁 / 重建（切主题、旋屏等）时本 View 会被 detach，
     * 若还有 postDelayed 排在队列里，它会持有 View 引用执行 —— 既泄漏又可能
     * 操作已经不在窗口上的树。代次守卫只解决"过期触发"，这个负责"根本别执行"。
     */
    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        // 让所有在途的延迟任务失效。
        // 注意不能用 removeCallbacks(null) —— View.removeCallbacks 要求非空 Runnable，
        // 传 null 会抛 NPE。只递增代次即可：延迟任务执行时会发现自己已过期而直接 return。
        textAnimGeneration++;
        if (widthAnimator != null) {
            widthAnimator.removeAllListeners();
            widthAnimator.cancel();
            widthAnimator = null;
        }
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}