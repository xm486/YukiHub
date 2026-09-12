package com.yuki.yukihub.bigscreen;

/**
 * 按键图标文案（spec §M4-2）：Xbox / PlayStation 两套风格。
 *
 * <p>用字符字形而不是图片：ⒶⓍ 与 ✕○△□ 都是通用符号，字体里就有，
 * 既不用出图、也能随字号缩放、还能跟着文字基线对齐 —— 比切图更稳。
 *
 * <p>所有提示文案都从这里取，切换风格后底栏 / 信息层 / 卡片徽章会立刻跟着变。
 */
public class BigScreenKeys {

    public static final String STYLE_XBOX = "xbox";
    public static final String STYLE_PS = "ps";

    private final String style;

    public BigScreenKeys(String style) {
        this.style = STYLE_PS.equals(style) ? STYLE_PS : STYLE_XBOX;
    }

    public boolean isPs() { return STYLE_PS.equals(style); }

    public String styleName() { return isPs() ? "PlayStation" : "Xbox"; }

    /** 确认 / 启动 */
    public String confirm() { return isPs() ? "✕" : "Ⓐ"; }

    /** 返回 */
    public String back() { return isPs() ? "○" : "Ⓑ"; }

    /** 第三键（收藏） */
    public String third() { return isPs() ? "□" : "Ⓧ"; }

    /** 第四键（详情 / 菜单） */
    public String fourth() { return isPs() ? "△" : "Ⓨ"; }

    /** 左肩键 */
    public String lb() { return isPs() ? "L1" : "LB"; }

    /** 右肩键 */
    public String rb() { return isPs() ? "R1" : "RB"; }

    /** 菜单键 */
    public String menu() { return isPs() ? "Options" : "☰"; }

    /** 切换风格 */
    public static String nextStyle(String current) {
        return STYLE_PS.equals(current) ? STYLE_XBOX : STYLE_PS;
    }
}