package com.yuki.yukihub.util;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ImageSpan;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

/**
 * 用 ImageSpan 在文字前内联一个小矢量图标。
 *
 * 为什么不用 TextView 的 drawableStart：
 *  - drawableStart 的图标尺寸在部分构建工具链上无法通过 XML 属性约束，
 *    且与文字的垂直对齐依赖字体基线，小字号下容易偏移、与文字脱节。
 *  - ImageSpan 的尺寸由 setBounds 精确指定，ALIGN_CENTER 保证相对文字垂直居中，
 *    图标与文字作为一个整体参与 TextView 的 gravity，观感稳定。
 *
 * 约定的占位方式：传入的 text 以首字符作为图标承载位（通常给一个空格），
 * 即 " 在玩" 渲染为 [图标]在玩。
 */
public final class IconedText {

    private IconedText() { }

    /** 图标尺寸（dp）：行内小图标统一档位，避免各处随意取值。 */
    public static final float SIZE_INLINE = 9f;
    /** 卡片徽章等极小场景。 */
    public static final float SIZE_BADGE = 8f;
    /** 弹窗选项这类稍大的行内图标。 */
    public static final float SIZE_OPTION = 12f;

    /**
     * 设置「图标 + 文字」。
     *
     * @param tv          目标 TextView
     * @param drawableRes 图标资源
     * @param label       纯文字（不含占位空格），例如 "在玩"
     * @param sizeDp      图标边长（dp）
     * @param tintColor   图标着色；<=0 表示沿用矢量自身颜色
     */
    public static void set(TextView tv, int drawableRes, String label, float sizeDp, int tintColor) {
        if (tv == null) return;
        String text = label == null ? "" : label;
        Drawable icon = drawableRes == 0 ? null : ContextCompat.getDrawable(tv.getContext(), drawableRes);
        if (icon == null) {
            tv.setText(text);
            return;
        }
        int px = (int) (sizeDp * tv.getResources().getDisplayMetrics().density + 0.5f);
        if (px <= 0) px = 1;
        icon = icon.mutate();
        icon.setBounds(0, 0, px, px);
        if (tintColor > 0) {
            icon.setTint(tintColor);
        }
        // 首字符作为图标占位，其后紧跟文字
        SpannableString ss = new SpannableString("\u00A0" + text);
        ss.setSpan(new ImageSpan(icon, ImageSpan.ALIGN_CENTER), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        tv.setText(ss);
    }

    /** 不指定着色（用矢量原色）。 */
    public static void set(TextView tv, int drawableRes, String label, float sizeDp) {
        set(tv, drawableRes, label, sizeDp, 0);
    }

    /**
     * 构建「图标 + 文字」的 CharSequence，供调用方继续与其它文本拼接。
     * 取不到图标时退化为纯文字，绝不影响功能。
     */
    public static CharSequence build(Context ctx, int drawableRes, String label, float sizeDp, int tintColor) {
        String text = label == null ? "" : label;
        if (ctx == null || drawableRes == 0) return text;
        Drawable icon = ContextCompat.getDrawable(ctx, drawableRes);
        if (icon == null) return text;
        int px = (int) (sizeDp * ctx.getResources().getDisplayMetrics().density + 0.5f);
        if (px <= 0) px = 1;
        icon = icon.mutate();
        icon.setBounds(0, 0, px, px);
        if (tintColor > 0) icon.setTint(tintColor);
        SpannableString ss = new SpannableString("\u00A0" + text);
        ss.setSpan(new ImageSpan(icon, ImageSpan.ALIGN_CENTER), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return ss;
    }

    /** 纯文字版游玩状态（不含图标），用于 Toast 等长句拼接场合。 */
    public static String plainLabelForStatus(String status) {
        return labelForStatus(status);
    }

    /**
     * 构建「前缀 + 图标 + 文字」，用于单选圆点（●/○）这类需要打在图标前的场合。
     * prefix 为 null 或空时等价于 {@link #build}。
     */
    public static CharSequence buildWithPrefix(Context ctx, String prefix, int drawableRes,
                                               String label, float sizeDp, int tintColor) {
        CharSequence iconed = build(ctx, drawableRes, label, sizeDp, tintColor);
        if (prefix == null || prefix.isEmpty()) return iconed;
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder(prefix);
        sb.append(iconed);
        return sb;
    }

    /**
     * 按游玩状态取图标资源。与 GameAdapter / MainActivity 里的状态字符串
     * （completed / playing / onhold / dropped / 其余按 unplayed）保持一致。
     */
    public static int drawableForStatus(String status) {
        String s = status == null ? "unplayed" : status;
        if ("completed".equals(s)) return com.yuki.yukihub.R.drawable.ic_st_trophy;
        if ("playing".equals(s)) return com.yuki.yukihub.R.drawable.ic_nav_library;
        if ("onhold".equals(s)) return com.yuki.yukihub.R.drawable.ic_st_pause;
        if ("dropped".equals(s)) return com.yuki.yukihub.R.drawable.ic_st_trash;
        return com.yuki.yukihub.R.drawable.ic_st_star;
    }

    /**
     * 按「游玩状态筛选值」取图标。筛选值来自 STATUS_FILTERS，是大写（PLAYING 等），
     * 空串表示「全部」，返回 0 让调用方退化为纯文字。
     */
    public static int drawableForFilterValue(String filterValue) {
        if (filterValue == null || filterValue.isEmpty()) return 0;
        return drawableForStatus(filterValue.toLowerCase(java.util.Locale.ROOT));
    }

    /** 游玩状态的纯文字（不含图标）。 */
    public static String labelForStatus(String status) {
        String s = status == null ? "unplayed" : status;
        if ("completed".equals(s)) return "玩过";
        if ("playing".equals(s)) return "在玩";
        if ("onhold".equals(s)) return "搁置";
        if ("dropped".equals(s)) return "抛弃";
        return "未玩";
    }
}