package com.yuki.yukihub.bigscreen;

import android.app.Activity;
import android.content.Context;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.yuki.yukihub.R;
import com.yuki.yukihub.model.Game;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 大屏模式「搜索」浮层（M22）。
 *
 * <p>游戏多了之后光靠左侧分类很难找 —— 这个浮层提供**按标题实时过滤**的结果网格。
 * 入口有两个：顶栏「搜索」按钮（触摸用户）+ 侧栏顶部「搜索」项（手柄用户）。
 *
 * <p>设计要点：
 * <ul>
 *   <li><b>输入</b>：一个 {@link EditText}，触摸直接弹系统输入法（本应用是手机应用，必有输入法）。</li>
 *   <li><b>结果</b>：复用 {@link BigScreenShelfAdapter} + {@code item_bs_card} —— 封面、
 *       NSFW 模糊、焦点视觉、触摸长按等行为全部白嫖，不另造一套卡片。</li>
 *   <li><b>匹配字段</b>：标题 + 原文名 + 罗马音（罗马音仅当该游戏元数据已缓存时可用，best-effort）。</li>
 *   <li><b>焦点</b>：复用 {@link FocusEngine}（网格模式），方向键在结果间移动，Ⓐ 启动。</li>
 * </ul>
 *
 * <p>输入分发：Activity 的 {@code dispatchKeyEvent} 会先经过 {@link InputRouter}，
 * 所以方向键/ABXY 会走 {@link #handleIntent}；而**软键盘打字**走的是 IME 的
 * InputConnection，不产生 KeyEvent，因此不会被 {@code InputRouter} 截走，能正常进输入框。
 */
public class BigScreenSearchLayer implements BigScreenShelfAdapter.Listener {

    public interface Listener {
        void onSearchShown();

        void onSearchHidden();

        /** 查看某款游戏的详情 */
        void onDetails(Game game);

        /** 收藏 / 取消收藏某款游戏 */
        void onToggleFavorite(Game game);

        /**
         * M22：把主界面切到这款游戏上。
         *
         * <p>用户要求「点到指定游戏再退出，外面也展示那个游戏」—— 不是把第一个游戏换掉，
         * 而是**焦点真正跳到那一张卡**：分类切到「全部游戏」、重建列表、焦点定位到它的下标，
         * 于是信息浮层 / 背景大图 / PV 全都跟着切过去。搜索浮层关闭前调用。
         */
        void onJumpToGame(Game game);
    }

    private final Activity activity;
    private final Listener listener;
    private final BigScreenMeta metaLoader;

    private final FrameLayout root;
    private final EditText input;
    private final RecyclerView results;
    private final TextView emptyView;
    private final TextView countView;
    private final TextView closeBtn;
    private final TextView hintView;

    private final BigScreenShelfAdapter adapter = new BigScreenShelfAdapter();
    private final FocusEngine focus = new FocusEngine();

    private final List<Game> allGames = new ArrayList<>();
    private final List<Game> filtered = new ArrayList<>();

    private BigScreenKeys keys = new BigScreenKeys(BigScreenKeys.STYLE_XBOX);
    private boolean visible = false;
    private boolean touchUi = false;
    private String query = "";

    /** 结果网格列数（按可用宽度算） */
    private int columns = 5;
    /** 卡片尺寸 / 间距（由 Activity 按屏幕下发，单位 px） */
    private int cardWPx = 160;
    private int cardHPx = 213;
    private int gapPx = 10;

    public BigScreenSearchLayer(Activity activity, FrameLayout container,
                                BigScreenMeta metaLoader, Listener listener) {
        this.activity = activity;
        this.metaLoader = metaLoader;
        this.listener = listener;

        root = (FrameLayout) LayoutInflater.from(activity).inflate(R.layout.view_bs_search, null);
        input = root.findViewById(R.id.bsSearchInput);
        results = root.findViewById(R.id.bsSearchResults);
        emptyView = root.findViewById(R.id.bsSearchEmpty);
        countView = root.findViewById(R.id.bsSearchCount);
        closeBtn = root.findViewById(R.id.bsSearchClose);
        hintView = root.findViewById(R.id.bsSearchHint);

        // 点卡片外的空白 = 关闭（root 消费落在遮罩上的触摸；卡片自己会吞掉内部触摸）
        root.setClickable(true);
        root.setOnClickListener(v -> hide());
        View card = root.findViewById(R.id.bsSearchCard);
        if (card != null) { card.setOnClickListener(v -> { /* 吞掉，别冒泡到 root */ }); }
        if (closeBtn != null) {
            closeBtn.setOnClickListener(v -> { BigScreenSound.open(); BigScreenSound.hapticKey(v); hide(); });
            closeBtn.setVisibility(View.GONE);   // 由 setTouchUi 决定显隐
        }

        adapter.setListener(this);
        adapter.setShowTitles(true);
        // 实时过滤时每次输入都会重建结果 —— 关掉"错峰浮入"，否则每敲一个字都要闪一下
        adapter.setEntranceEnabled(false);
        results.setLayoutManager(new GridLayoutManager(activity, columns));
        results.setAdapter(adapter);
        // 网格的两行之间留出间距（水平间距由适配器的 rightMargin 给）
        results.addItemDecoration(new RecyclerView.ItemDecoration() {
            @Override public void getItemOffsets(android.graphics.Rect outRect, View view,
                                                 RecyclerView parent, RecyclerView.State state) {
                outRect.bottom = gapPx;
            }
        });
        results.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> layoutResults());

        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) { }
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {
                applyFilter(s == null ? "" : s.toString());
            }
            @Override public void afterTextChanged(Editable e) { }
        });
        // 软键盘上的「搜索」键 = 收起键盘，把焦点交给结果
        input.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH
                    || actionId == EditorInfo.IME_ACTION_DONE
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                hideIme();
                return true;
            }
            return false;
        });

        // 键盘弹出时把卡片抬到键盘上方（immersive 模式下系统不会自动 resize）
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            v.setPadding(ime.left, 0, ime.right, ime.bottom);
            return insets;
        });

        container.addView(root);
        updateHint();
    }

    // ================= 外观下发 =================

    /** 卡片尺寸 / 间距（px）—— 由 Activity 按 BigScreenSizes 下发 */
    public void setMetrics(int cardW, int cardH, int gap) {
        this.cardWPx = Math.max(1, cardW);
        this.cardHPx = Math.max(1, cardH);
        this.gapPx = Math.max(0, gap);
        adapter.setCardSize(cardWPx, cardHPx);
        adapter.setGapPx(gapPx);
        layoutResults();
    }

    public void setKeyStyle(String style) {
        keys = new BigScreenKeys(style);
        adapter.setKeys(keys);
        updateHint();
    }

    public void setNsfwBlur(boolean enabled) {
        adapter.setNsfwBlurEnabled(enabled);
        adapter.notifyDataSetChanged();
    }

    public void setFocusScalePercent(int percent) {
        adapter.setFocusScalePercent(percent);
    }

    /** 是否显示触摸专用 UI（关闭按钮） */
    public void setTouchUi(boolean touch) {
        this.touchUi = touch;
        if (closeBtn != null) { closeBtn.setVisibility(touch ? View.VISIBLE : View.GONE); }
        updateHint();
    }

    // ================= 显示 / 隐藏 =================

    public boolean isVisible() { return visible; }

    /**
     * 打字场景下把按键直接交给输入框，不让 {@link InputRouter} 截走。
     *
     * <p>{@link InputRouter} 把 A/S/D/W/X/Y/Q/E/Enter/Tab 也映射成了方向键与 ABXY（那是
     * "没有手柄时用键盘全流程调试"的兜底），但搜索框已经拿到焦点时，这些键是**文字输入** ——
     * 必须原样放给 EditText，否则打字会变成"移动焦点 / 启动游戏"。
     * 手柄的 DPAD / BUTTON_A 等不是这些键码，所以手柄操作不受影响。
     */
    public boolean shouldBypassRouter(KeyEvent e) {
        if (!visible || e == null) { return false; }
        if (!input.hasFocus()) { return false; }
        final int k = e.getKeyCode();
        if (k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_DEL
                || k == KeyEvent.KEYCODE_FORWARD_DEL || k == KeyEvent.KEYCODE_SPACE) {
            return true;
        }
        if (k >= KeyEvent.KEYCODE_A && k <= KeyEvent.KEYCODE_Z) { return true; }
        if (k >= KeyEvent.KEYCODE_0 && k <= KeyEvent.KEYCODE_9) { return true; }
        return false;
    }

    /** 打开搜索浮层；games 传当前游戏库快照（隐藏游戏会被剔除） */
    public void show(List<Game> games) {
        allGames.clear();
        if (games != null) {
            for (Game g : games) {
                if (g != null && !g.hidden) { allGames.add(g); }
            }
        }
        Collections.sort(allGames, (a, b) -> Long.compare(b.lastPlayedAt, a.lastPlayedAt));

        if (input.getText() != null) { input.setText(""); }
        query = "";

        root.setVisibility(View.VISIBLE);
        root.setAlpha(0f);
        root.animate().alpha(1f).setDuration(180L)
                .setInterpolator(new DecelerateInterpolator()).start();
        visible = true;

        applyFilter("");
        results.post(this::layoutResults);
        if (listener != null) { listener.onSearchShown(); }

        // M22：预热全库元数据 —— 开发商/会社名只存在元数据里，不预热就没法按会社搜。
        // 读完再刷一次结果（这时搜索框可能已经被清空/关闭，applyFilter 里都判了空）。
        if (metaLoader != null) {
            final List<Long> ids = new ArrayList<>();
            for (Game g : allGames) { ids.add(g.id); }
            metaLoader.loadAll(ids, () -> {
                if (!visible) { return; }
                applyFilter(query);
            });
        }

        input.post(() -> { input.requestFocus(); showIme(); });
    }

    public void hide() {
        if (!visible) { return; }
        visible = false;
        hideIme();
        input.clearFocus();
        root.animate().alpha(0f).setDuration(140L)
                .withEndAction(() -> root.setVisibility(View.GONE)).start();
        if (listener != null) { listener.onSearchHidden(); }
    }

    /** 收藏状态变化后刷新结果卡片（星标） */
    public void refreshCurrent() { adapter.notifyDataSetChanged(); }

    /** 当前结果列表（详情层要用它做 ←→ 翻页） */
    public List<Game> resultGames() { return new ArrayList<>(filtered); }

    /** 当前焦点在结果里的下标 */
    public int focusedIndex() { return focus.index(); }

    // ================= 过滤 =================

    private void applyFilter(String q) {
        query = q == null ? "" : q.trim();
        final String lower = query.toLowerCase(Locale.ROOT);

        filtered.clear();
        for (Game g : allGames) {
            if (g == null) { continue; }
            if (lower.isEmpty() || matches(g, lower)) { filtered.add(g); }
        }

        adapter.submit(filtered);
        focus.setColumns(columns);
        focus.setCount(filtered.size());
        focus.setIndex(0);
        adapter.setFocusedPosition(filtered.isEmpty() ? -1 : 0);

        final boolean empty = filtered.isEmpty();
        emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
        results.setVisibility(empty ? View.GONE : View.VISIBLE);
        if (empty) {
            emptyView.setText(query.isEmpty() ? "游戏库是空的" : "没有匹配的游戏");
        }
        countView.setText(query.isEmpty()
                ? (filtered.size() + " 款")
                : (filtered.size() + " 个结果"));
        if (!empty) { results.scrollToPosition(0); }
    }

    private boolean matches(Game g, String lower) {
        if (contains(g.title, lower)) { return true; }
        if (contains(g.originalTitle, lower)) { return true; }
        // M22：开发商 / 会社名（元数据，预热后用 peek 零开销）
        if (contains(metaDeveloper(g), lower)) { return true; }
        // 罗马音：仅当该游戏元数据已缓存时可用（best-effort，不额外发起网络请求）
        if (metaLoader != null) {
            BigScreenMeta.Data d = metaLoader.peek(g.id);
            if (d != null && contains(d.romanTitle, lower)) { return true; }
        }
        return false;
    }

    /** 该游戏的开发商（元数据缓存里有就返回，没有返回空串） */
    private String metaDeveloper(Game g) {
        if (metaLoader == null) { return ""; }
        BigScreenMeta.Data d = metaLoader.peek(g.id);
        return d == null ? "" : d.developer;
    }

    private static boolean contains(String s, String lower) {
        return s != null && !s.isEmpty() && s.toLowerCase(Locale.ROOT).contains(lower);
    }

    /** 按可用宽度算结果网格列数 */
    private void layoutResults() {
        if (results == null) { return; }
        int avail = results.getWidth();
        if (avail <= 0) {
            int screen = activity.getResources().getDisplayMetrics().widthPixels;
            avail = Math.max(1, screen - dp(2 * 44 + 2 * 30 + 12));
        }
        int unit = Math.max(1, cardWPx + gapPx);
        int cols = Math.max(1, (avail + gapPx) / unit);
        if (cols != columns) {
            columns = cols;
            if (results.getLayoutManager() instanceof GridLayoutManager) {
                ((GridLayoutManager) results.getLayoutManager()).setSpanCount(cols);
            }
        }
        focus.setColumns(columns);
        focus.setCount(filtered.size());
    }

    // ================= 输入 =================

    /** @return true 表示已消费 */
    public boolean handleIntent(InputRouter.Intent intent) {
        if (!visible || intent == null) { return false; }
        switch (intent) {
            case UP:      move(FocusEngine.DIR_UP);    return true;
            case DOWN:    move(FocusEngine.DIR_DOWN);  return true;
            case LEFT:    move(FocusEngine.DIR_LEFT);  return true;
            case RIGHT:   move(FocusEngine.DIR_RIGHT); return true;
            // M22：Ⓐ = 跳到这款游戏并关闭（用户要求"点到指定游戏再退出，外面选中它"）
            case CONFIRM: jumpToFocusedAndClose();     return true;
            case DETAILS: detailsFocused();            return true;
            case FAVORITE: favoriteFocused();          return true;
            case BACK:    hide();                      return true;
            default:      return true;   // 打开时吞掉其它输入，避免误操作到下层
        }
    }

    private void move(int dir) {
        if (filtered.isEmpty()) { return; }
        if (focus.move(dir)) { syncFocus(); }
    }

    private void syncFocus() {
        int idx = focus.index();
        adapter.setFocusedPosition(idx);
        results.smoothScrollToPosition(idx);
    }

    private Game focusedGame() {
        int idx = focus.index();
        if (idx < 0 || idx >= filtered.size()) { return null; }
        return filtered.get(idx);
    }

    /** M22：把手柄焦点上的那款游戏"落"到外面的主界面（不关浮层，触摸首次点按时用） */
    private void jumpTo(Game game) {
        if (game != null && listener != null) { listener.onJumpToGame(game); }
    }

    /** M22：Ⓐ = 跳到这款游戏并关闭搜索（用户要的"选中到那个游戏"） */
    private void jumpToFocusedAndClose() {
        Game g = focusedGame();
        if (g == null) { return; }
        jumpTo(g);
        hide();
    }

    private void detailsFocused() {
        Game g = focusedGame();
        if (g != null && listener != null) { listener.onDetails(g); }
    }

    private void favoriteFocused() {
        Game g = focusedGame();
        if (g != null && listener != null) { listener.onToggleFavorite(g); }
    }

    // ================= BigScreenShelfAdapter.Listener（触摸）=================

    @Override
    public void onCardFocused(int position, Game game) {
        if (position < 0 || position >= filtered.size()) { return; }
        focus.setIndex(position);
        adapter.setFocusedPosition(position);
        results.smoothScrollToPosition(position);
        // M22：触摸第一次点按 = 选中，同时外面主界面也跟着切过去（"展示那个游戏"）
        jumpTo(game);
    }

    @Override
    public void onCardConfirmed(Game game) {
        if (game == null) { return; }
        // M22：触摸第二次点按 = 选定这款游戏并退出（与手柄 Ⓐ 一致）
        jumpTo(game);
        hide();
    }

    @Override
    public void onCardDetails(Game game) {
        if (game != null && listener != null) { listener.onDetails(game); }
    }

    // ================= 提示 / IME =================

    private void updateHint() {
        if (hintView == null) { return; }
        if (touchUi) {
            hintView.setText("点按卡片选中（外面同步切换）　再点一次进入　✕ 关闭");
        } else {
            hintView.setText("方向键 选择　" + keys.confirm() + " 选中并返回　" + keys.fourth()
                    + " 详情　" + keys.third() + " 收藏　" + keys.back() + " 关闭");
        }
    }

    private void showIme() {
        InputMethodManager imm = (InputMethodManager)
                activity.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) { imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT); }
    }

    private void hideIme() {
        InputMethodManager imm = (InputMethodManager)
                activity.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) { imm.hideSoftInputFromWindow(input.getWindowToken(), 0); }
    }

    private int dp(float v) {
        return Math.round(v * activity.getResources().getDisplayMetrics().density);
    }
}
