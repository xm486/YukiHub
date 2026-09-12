package com.yuki.yukihub.bigscreen;

import android.content.Context;
import android.content.res.Configuration;

/**
 * 大屏模式的尺寸预算（**横屏优先，全部按屏幕高度算**）。
 *
 * <p>为什么要有这个类：之前所有尺寸都写死 dp（卡片 224dp、侧栏条目 52dp×7=364dp），
 * 在横屏手机上可用高度只有 ~350dp —— 结果卡片被裁、侧栏只露得出 4 个图标。
 * 竖屏思路定尺寸，横屏上必然溢出。
 *
 * <p>规则（横屏 h = 短边，即屏幕高度）：
 * <ul>
 *   <li>顶栏 {@link #topBarH} / 底栏 {@link #bottomBarH}：按高度百分比，夹在可读下限</li>
 *   <li>信息层 {@link #infoBarH}：标题 + 元信息 + 按钮排</li>
 *   <li>剩余高度 = 总高 − 顶 − 底 − 信息</li>
 *   <li>shelf 行高 {@link #rowH}：剩余 ÷ 1.35（**露出下一行的一角**，提示"还能往下滚"）</li>
 *   <li>卡片 {@link #cardH} = 行高 − 行标题 − 间距，夹在 88~200dp；宽 = 高 × 0.75（3:4 封面）</li>
 *   <li>侧栏条目 {@link #railItemH} = 剩余 ÷ 7（7 个分类刚好铺满，不再被裁）</li>
 * </ul>
 */
public class BigScreenSizes {

    /** 侧栏分类数（用来摊分高度） */
    // 侧栏条目数（M8：去掉引擎分组后是 6 个 —— 全部/收藏/最近/游玩中/已完成/未游玩）
    private static final int RAIL_ENTRY_COUNT = 6;

    public final int wDp;
    public final int hDp;

    public final int topBarH;
    public final int bottomBarH;
    /** 底栏按键提示字号（M14-1：随屏幕缩放，避免文字被底栏裁掉） */
    public final float hintSp;
    /** M16：游戏操作菜单面板宽度（原来硬编码 404dp，比短边还宽，显得"偷偷变宽"了） */
    public final int panelW;
    public final int panelRightMargin;
    /** M16：设置面板左右内边距 / 左侧分区列宽（原来硬编码 52dp / 228dp） */
    public final int setPadH;
    public final int setLeftColW;
    public final int infoBarH;

    public final int railCollapsedW;
    public final int railExpandedW;
    public final int railItemH;
    public final int railIconBox;

    public final int cardW;
    public final int cardH;
    public final int rowH;
    public final int headerH;
    public final int gap;

    /** 信息层操作按钮高度 */
    public final int actionBtnH;

    // ===== M6：整体缩放（手机上写死 30sp 标题实在太夸张）=====
    /** 信息浮层大标题字号（sp） */
    public final float infoTitleSp;
    /** 分类 chips 字号（sp） */
    public final float infoChipSp;
    /** 副行（开发商 · 年份…）字号（sp） */
    public final float infoMetaSp;
    /** 操作按钮字号（sp） */
    public final float actionSp;
    /** 信息浮层距屏幕顶部（顶栏之下） */
    public final int infoTopOffset;
    /** 信息浮层需要预留的高度（它压着背景，不能盖住卡片排） */
    public final int infoReserveH;

    public BigScreenSizes(Context context) {
        Configuration cfg = context.getResources().getConfiguration();
        int sw = Math.max(320, cfg.screenWidthDp);
        int sh = Math.max(320, cfg.screenHeightDp);
        // 横屏优先：短边当高度用（万一进来时还是竖屏也不会算出离谱的值）
        wDp = Math.max(sw, sh);
        hDp = Math.min(sw, sh);

        topBarH = clamp(Math.round(hDp * 0.075f), 36, 54);
        // 底栏：无手柄时高度归零（卡片排贴到屏幕最下沿），有手柄时才占一小行
        // M14-1：原来 12~20dp —— 底栏里是 12.5sp 的文字（约需 20dp+），必然被裁成半截。
        // 现在按屏幕短边给到 24~30dp，并把字号一起缩小（见 hintSp）。
        // M18-8：用户要求"**就那一行 + 一点点边距**就够了" → 收到 20~26dp
        //   （文字 10.5~12.5sp ≈ 14~17dp，20dp 仍有 3dp 上下边距，不会裁）
        bottomBarH = clamp(Math.round(hDp * 0.042f), 20, 26);
        hintSp = clampF(hDp * 0.021f, 10.5f, 12.5f);
        // M16：两个浮层的尺寸也纳入"按屏幕短边缩放"体系。
        //   之前 panel 宽度写死 404dp、设置面板内边距写死 52dp —— 而其它元素都按 hDp 缩了，
        //   结果在小屏横屏上浮层**相对**越来越大（用户："你是不是偷偷给两个设置页都改宽了"）。
        panelW = clamp(Math.round(hDp * 0.92f), 260, 380);
        panelRightMargin = clamp(Math.round(hDp * 0.10f), 20, 56);
        setPadH = clamp(Math.round(hDp * 0.105f), 24, 52);
        setLeftColW = clamp(Math.round(hDp * 0.55f), 150, 228);
        // 信息层已改为"浮在背景上"，不再占用纵向布局高度（字段保留兼容调用方）
        infoBarH = 0;
        // 但浮层仍然要占掉中间那块视觉空间，否则卡片排会被标题压住
        infoReserveH = clamp(Math.round(hDp * 0.34f), 100, 150);

        int content = Math.max(120, hDp - topBarH - bottomBarH - infoReserveH);

        // 行高：露出 1/3 行提示可滚动
        int rowTotal = Math.max(96, Math.round(content / 1.35f));
        headerH = clamp(Math.round(rowTotal * 0.20f), 20, 34);
        gap = clamp(Math.round(hDp * 0.018f), 4, 10);
        int cardHCalc = rowTotal - headerH - gap * 2;
        cardH = clamp(cardHCalc, 88, 200);
        cardW = Math.min(Math.round(cardH * 0.75f), Math.round(wDp * 0.17f));
        rowH = cardH + headerH + gap * 2;

        railItemH = clamp((content - 12) / RAIL_ENTRY_COUNT, 28, 52);
        railIconBox = clamp(Math.round(railItemH * 0.92f), 24, 46);
        railCollapsedW = clamp(railItemH + 14, 50, 78);
        railExpandedW = clamp(Math.round(wDp * 0.24f), 168, 260);

        actionBtnH = clamp(Math.round(hDp * 0.085f), 28, 40);

        // ===== 文字：全部按屏幕短边缩放（M6 起再压一档，实机反馈"还是太大"）=====
        infoTitleSp = clampF(hDp * 0.052f, 16.5f, 26f);
        infoChipSp = clampF(hDp * 0.027f, 9f, 11f);
        infoMetaSp = clampF(hDp * 0.030f, 9.5f, 12f);
        actionSp = clampF(hDp * 0.030f, 9.5f, 11.5f);
        infoTopOffset = topBarH + clamp(Math.round(hDp * 0.030f), 8, 18);
    }

    private static float clampF(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** 供调试 / 文档核对 */
    public String describe() {
        return String.format(java.util.Locale.US,
                "screen=%dx%ddp top=%d bottom=%d info=%d | row=%d card=%dx%d header=%d gap=%d"
                        + " | rail w%d/%d item=%d icon=%d | btn=%d",
                wDp, hDp, topBarH, bottomBarH, infoBarH, rowH, cardW, cardH, headerH, gap,
                railCollapsedW, railExpandedW, railItemH, railIconBox, actionBtnH);
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}