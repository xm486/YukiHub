package com.yuki.yukihub.bigscreen;

import java.util.HashMap;
import java.util.Map;

/**
 * 二维焦点引擎（对应 bigscreen_spec.md §4.3）。
 *
 * <p>本类只负责「行列模型 + 移动规则 + 焦点记忆」，**不碰任何视图**；
 * 滚动跟随、焦点动效、音效由上层通过 {@link Listener} 回调实现。
 *
 * <p>支持两种布局模型：
 * <ul>
 *   <li><b>网格模式</b>：{@link #setColumns(int)}，所有行列数相同</li>
 *   <li><b>Shelf 模式</b>：{@link #setRowSizes(int[])}，每行长度可以不同
 *       （大屏主页的「继续游玩 / 最近加入 / 收藏」每行张数都不一样）</li>
 * </ul>
 *
 * <p>移动规则（与 pv/bigscreen-prototype.html 实测过的逻辑一致）：
 * <ul>
 *   <li>上下：跨行移动，行号夹紧到 [0, maxRow]，列号夹紧到目标行的实际长度</li>
 *   <li>左右：在当前行内移动，到行首/行尾即停（不跳到别的行）</li>
 *   <li>到边缘：{@link #move(int)} 返回 false 并回调 {@link Listener#onBoundary(int)}，
 *       由调用方决定是否跨区（例：最左列再向左 → 进入侧栏）</li>
 *   <li>焦点记忆：按 memoryKey（筛选 id）分别记住每个分类的焦点位置</li>
 * </ul>
 */
public class FocusEngine {

    public static final int DIR_UP = 0;
    public static final int DIR_DOWN = 1;
    public static final int DIR_LEFT = 2;
    public static final int DIR_RIGHT = 3;

    /** 焦点区域：左侧筛选栏 / 主区内容 */
    public static final int ZONE_RAIL = 0;
    public static final int ZONE_CONTENT = 1;

    public interface Listener {
        /** 焦点变化（index 为条目下标，row/col 为二维坐标） */
        void onFocusChanged(int index, int row, int col);

        /** 焦点到达边缘，direction 为 {@link #DIR_UP} 等 */
        void onBoundary(int direction);
    }

    private Listener listener;

    /** 网格模式：每行列数 */
    private int columns = 6;
    /** Shelf 模式：每行长度（非 null 时优先于 columns） */
    private int[] rowSizes = null;
    /** 每行长度的前缀和（Shelf 模式用来做 row/col 反查） */
    private int[] rowPrefix = null;

    private int count = 0;
    private int index = 0;
    private int zone = ZONE_CONTENT;

    private final Map<String, Integer> memory = new HashMap<>();
    private String memoryKey = "ALL";

    public void setListener(Listener listener) { this.listener = listener; }

    // ================= 布局模型 =================

    /** 网格模式：设置每行列数 */
    public void setColumns(int columns) {
        this.columns = columns <= 0 ? 1 : columns;
        this.rowSizes = null;
        this.rowPrefix = null;
    }

    public int columns() { return columns; }

    /** Shelf 模式：设置每行长度（允许为 0，表示该行没有内容） */
    public void setRowSizes(int[] sizes) {
        if (sizes == null || sizes.length == 0) {
            this.rowSizes = null;
            this.rowPrefix = null;
            return;
        }
        this.rowSizes = sizes.clone();
        this.rowPrefix = new int[sizes.length];
        int acc = 0;
        for (int i = 0; i < sizes.length; i++) {
            acc += Math.max(0, sizes[i]);
            rowPrefix[i] = acc;
        }
        setCount(acc);
    }

    public boolean isShelfMode() { return rowSizes != null; }

    public int rowCount() {
        if (rowSizes != null) { return rowSizes.length; }
        return columns > 0 ? (count + columns - 1) / columns : 0;
    }

    /** 指定行的条目数 */
    public int rowLength(int row) {
        if (rowSizes != null) {
            if (row < 0 || row >= rowSizes.length) { return 0; }
            return Math.max(0, rowSizes[row]);
        }
        if (row < 0) { return 0; }
        int start = row * columns;
        if (start >= count) { return 0; }
        return Math.min(columns, count - start);
    }

    /** 指定行列对应的全局 index；非法返回 -1 */
    public int indexOf(int row, int col) {
        if (rowSizes != null) {
            if (row < 0 || row >= rowSizes.length) { return -1; }
            int len = rowSizes[row];
            if (col < 0 || col >= len) { return -1; }
            return (row == 0 ? 0 : rowPrefix[row - 1]) + col;
        }
        if (row < 0 || col < 0 || col >= columns) { return -1; }
        int i = row * columns + col;
        return i < count ? i : -1;
    }

    // ================= 计数与坐标 =================

    /** 设置当前分类下的条目总数 */
    public void setCount(int count) {
        this.count = Math.max(0, count);
        if (index >= this.count) { index = Math.max(0, this.count - 1); }
    }

    /**
     * 触摸 / 鼠标点选：把焦点直接放到某行某列（越界自动夹紧，不回调时返回 false）。
     *
     * <p>这条是"点了别的卡片，信息层/背景却不跟着变"的修复关键 ——
     * 触摸必须**走同一个焦点引擎**，否则界面各处的焦点认知会分叉。
     *
     * @return 是否真的发生了移动（移动了才会回调 onFocusChanged）
     */
    public boolean setPosition(int row, int col) {
        int idx = indexOf(row, col);
        if (idx < 0) { return false; }
        if (idx == index) { return false; }
        index = idx;
        if (listener != null) { listener.onFocusChanged(index, row(), col()); }
        return true;
    }

    public int count() { return count; }

    public int index() { return index; }

    public int row() {
        if (rowSizes != null) {
            if (rowPrefix == null) { return 0; }
            for (int r = 0; r < rowPrefix.length; r++) {
                if (index < rowPrefix[r]) { return r; }
            }
            return Math.max(0, rowPrefix.length - 1);
        }
        return columns > 0 ? index / columns : 0;
    }

    public int col() {
        if (rowSizes != null) {
            if (rowPrefix == null) { return 0; }
            for (int r = 0; r < rowPrefix.length; r++) {
                int start = (r == 0) ? 0 : rowPrefix[r - 1];
                if (index < rowPrefix[r]) { return index - start; }
            }
            return 0;
        }
        return columns > 0 ? index % columns : 0;
    }

    public int zone() { return zone; }
    public void setZone(int zone) { this.zone = zone; }

    // ================= 焦点记忆 =================

    /**
     * 切换分类：先保存旧分类的焦点位置，再恢复新分类的位置。
     * 对应 spec §4.3「每个筛选各自记住上次焦点项」。
     */
    public void setMemoryKey(String key) {
        if (key == null) { return; }
        if (!key.equals(memoryKey)) {
            memory.put(memoryKey, index);
            memoryKey = key;
            Integer saved = memory.get(key);
            setIndex(saved == null ? 0 : saved);
        }
    }

    public String memoryKey() { return memoryKey; }

    /** 手动把当前焦点写入记忆（退出大屏前调用） */
    public void rememberSelection() { memory.put(memoryKey, index); }

    // ================= 移动 =================

    /** 直接设置焦点（自动夹紧到合法范围） */
    public void setIndex(int i) {
        int clamped = clampIndex(i);
        if (clamped == index) { return; }
        index = clamped;
        notifyFocus();
    }

    /** 视图重建后强制刷新一次焦点回调（不改索引） */
    public void refresh() { notifyFocus(); }

    /**
     * 按方向移动焦点。
     *
     * @return true = 已移动；false = 已在边缘（会回调 onBoundary）
     */
    public boolean move(int direction) {
        if (count <= 0) { return false; }

        final int row = row();
        final int col = col();
        int next = -1;

        switch (direction) {
            case DIR_UP:
                if (row <= 0) { return boundary(direction); }
                next = indexOf(row - 1, Math.min(col, rowLength(row - 1) - 1));
                break;
            case DIR_DOWN:
                if (row >= rowCount() - 1) { return boundary(direction); }
                next = indexOf(row + 1, Math.min(col, rowLength(row + 1) - 1));
                break;
            case DIR_LEFT:
                if (col <= 0) { return boundary(direction); }
                next = indexOf(row, col - 1);
                break;
            case DIR_RIGHT:
                if (col >= rowLength(row) - 1) { return boundary(direction); }
                next = indexOf(row, col + 1);
                break;
            default:
                return false;
        }

        next = clampIndex(next);
        if (next == index) { return boundary(direction); }
        index = next;
        notifyFocus();
        return true;
    }

    /** 整页翻（L2 / R2）：跳到上/下一行的同列位置 */
    public boolean page(int direction) {
        if (count <= 0) { return false; }
        int targetRow = row() + (direction > 0 ? 1 : -1);
        if (targetRow < 0 || targetRow >= rowCount()) { return false; }
        int target = indexOf(targetRow, Math.min(col(), rowLength(targetRow) - 1));
        target = clampIndex(target);
        if (target == index || target < 0) { return false; }
        index = target;
        notifyFocus();
        return true;
    }

    /** 跳到指定行首 */
    public boolean moveToRow(int row) {
        if (count <= 0) { return false; }
        int maxRow = rowCount() - 1;
        int r = row < 0 ? 0 : (row > maxRow ? maxRow : row);
        int target = clampIndex(indexOf(r, 0));
        if (target == index) { return false; }
        index = target;
        notifyFocus();
        return true;
    }

    // ================= 内部 =================

    private boolean boundary(int direction) {
        if (listener != null) { listener.onBoundary(direction); }
        return false;
    }

    private void notifyFocus() {
        if (listener != null) { listener.onFocusChanged(index, row(), col()); }
    }

    private int clampIndex(int i) {
        if (count <= 0) { return 0; }
        if (i < 0) { return 0; }
        if (i > count - 1) { return count - 1; }
        return i;
    }
}