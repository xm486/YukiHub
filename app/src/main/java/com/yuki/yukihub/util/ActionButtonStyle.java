package com.yuki.yukihub.util;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.View;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;
import androidx.core.view.ViewCompat;

import com.yuki.yukihub.R;
import com.yuki.yukihub.ui.DynamicTheme;
import com.yuki.yukihub.ui.ThemeColorExtractor;

/** 工具、选中态、主操作分层；默认色与网页预览一致，自定义主题沿用其色相。 */
public final class ActionButtonStyle {
    public static final int TOOL = 0;
    public static final int FILTER = 1;
    public static final int PRIMARY = 2;
    // 仅给编辑弹窗的按钮用普通 tag；不覆盖筛选 chip 原有的业务 tag。
    public static final String TAG_TOOL = "yh.action.tool";
    public static final String TAG_FILTER = "yh.action.filter";
    public static final String TAG_PRIMARY = "yh.action.primary";

    private ActionButtonStyle() { }

    public static ThemeColorExtractor.ThemeColors activeColors() {
        DynamicTheme theme = DynamicTheme.getInstance();
        return theme.isEnabled() ? theme.getColors() : null;
    }

    public static int foreground(Context context, int role) {
        return foreground(context, role, activeColors());
    }

    public static int foreground(Context context, int role, ThemeColorExtractor.ThemeColors colors) {
        if (colors != null && role == FILTER) {
            // 接近白色的主题色：亮/暗自定义颜色都不会产生深色文字。
            return ColorUtils.blendARGB(0xFFF5F4FF, opaque(colors.primary), 0.12f);
        }
        return ContextCompat.getColor(context, role == PRIMARY ? R.color.yh_action_primary_text
                : role == FILTER ? R.color.yh_action_selected_text : R.color.yh_action_tool_text);
    }

    public static void apply(View view, int role) {
        apply(view, role, activeColors());
    }

    public static void apply(View view, int role, ThemeColorExtractor.ThemeColors colors) {
        if (view == null) return;
        // AppCompatButton 的主题 tint 会覆盖显式 drawable，必须一起清掉。
        ViewCompat.setBackgroundTintList(view, null);
        view.setBackground(background(view.getContext(), role, colors));
        if (view instanceof TextView) {
            ((TextView) view).setTextColor(foreground(view.getContext(), role, colors));
        }
    }

    public static Drawable background(Context context, int role, ThemeColorExtractor.ThemeColors colors) {
        if (colors == null) {
            return ContextCompat.getDrawable(context, role == PRIMARY ? R.drawable.bg_action_primary
                    : role == FILTER ? R.drawable.bg_filter_selected : R.drawable.bg_action_tool);
        }
        int accent = opaque(colors.primary);
        int fill;
        int edge;
        if (role == PRIMARY) {
            fill = readablePrimary(accent, colors.bg);
            edge = ColorUtils.blendARGB(fill, Color.WHITE, 0.30f);
        } else if (role == FILTER) {
            fill = withAlpha(accent, 0x45);
            edge = withAlpha(accent, 0x80);
        } else {
            fill = withAlpha(ColorUtils.blendARGB(opaque(colors.bg), opaque(colors.card2), 0.55f), 0xCC);
            edge = withAlpha(accent, 0x52);
        }
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{-android.R.attr.state_enabled}, shape(context, role, fill,
                withAlpha(edge, 0x38), colors, false));
        states.addState(new int[]{android.R.attr.state_pressed}, shape(context, role,
                feedbackFill(role, fill, true), edge, colors, true));
        states.addState(new int[]{android.R.attr.state_focused}, shape(context, role,
                feedbackFill(role, fill, false), ColorUtils.blendARGB(edge, Color.WHITE, 0.20f), colors, false));
        states.addState(new int[]{}, shape(context, role, fill, edge, colors, false));
        return states;
    }

    private static GradientDrawable shape(Context context, int role, int fill, int edge,
                                           ThemeColorExtractor.ThemeColors colors, boolean pressed) {
        GradientDrawable drawable;
        if (role == PRIMARY && colors.isGradient) {
            int second = readablePrimary(opaque(colors.secondary), colors.bg2);
            if (pressed) second = ColorUtils.blendARGB(second, Color.BLACK, 0.14f);
            drawable = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{fill, second});
        } else {
            drawable = new GradientDrawable();
            drawable.setColor(fill);
        }
        float density = context.getResources().getDisplayMetrics().density;
        drawable.setCornerRadius(8f * density);
        drawable.setStroke(Math.max(1, Math.round(density)), edge);
        // 不定义内边距，避免挤压 28dp 高的按钮和 ImageSpan。
        return drawable;
    }

    private static int feedbackFill(int role, int fill, boolean pressed) {
        if (role == FILTER) return withAlpha(fill, pressed ? 0x70 : 0x60);
        if (role == PRIMARY) {
            // 高亮最多轻微变亮，不破坏白字可读性。
            int changed = ColorUtils.blendARGB(fill, pressed ? Color.BLACK : Color.WHITE, pressed ? 0.14f : 0.04f);
            return ColorUtils.calculateContrast(0xFFF5F4FF, changed) >= 4.5 ? changed : fill;
        }
        return withAlpha(ColorUtils.blendARGB(opaque(fill), Color.WHITE, pressed ? 0.08f : 0.05f), 0xEE);
    }

    private static int readablePrimary(int accent, int bg) {
        int result = ColorUtils.blendARGB(opaque(bg), accent, 0.68f);
        // 自定义色可能是亮黄/白色，收低明度而不改成黑字；保留主操作白色图标。
        for (int i = 0; i < 20 && ColorUtils.calculateContrast(0xFFF5F4FF, result) < 4.5; i++) {
            result = ColorUtils.blendARGB(result, Color.BLACK, 0.08f);
        }
        return result;
    }

    private static int opaque(int color) { return color | 0xFF000000; }
    private static int withAlpha(int color, int alpha) { return (alpha << 24) | (color & 0x00FFFFFF); }
}
