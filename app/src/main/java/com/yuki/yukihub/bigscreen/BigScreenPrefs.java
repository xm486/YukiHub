package com.yuki.yukihub.bigscreen;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 大屏模式设置读写。
 *
 * <p>复用主设置文件 {@code yukihub_prefs}（与 MainActivity / HomeActivity 同源），
 * 键名规范见 bigscreen_spec.md §6.3。所有取值都带安全默认值，读不到了就退回默认，
 * 不会因为设置缺失影响启动。
 */
public class BigScreenPrefs {

    /** 与现有页面共用的设置文件名 */
    public static final String PREFS = "yukihub_prefs";

    // ===== 键名（spec §6.3）=====
    public static final String KEY_INTRO          = "bigscreen_intro_enabled";
    /** M17：入场动画风格（band 光带 / fade 淡入 / zoom 缩放）—— M18-2 起废弃 */
    public static final String KEY_INTRO_STYLE  = "bigscreen_intro_style";
    /** M17：入场动画速度 —— M18-2 起废弃 */
    public static final String KEY_INTRO_SPEED  = "bigscreen_intro_speed";
    /** M17：入场标题与副标题 —— M18-2 起废弃 */
    public static final String KEY_INTRO_TITLE  = "bigscreen_intro_title";
    public static final String KEY_INTRO_SUB    = "bigscreen_intro_sub";
    /** M17：顶部提示条停留时长（ms） */
    public static final String KEY_BANNER_HOLD  = "bigscreen_banner_hold";
    /** M17：手柄焦点缩放幅度（百分比，0 = 不缩放） */
    public static final String KEY_FOCUS_SCALE  = "bigscreen_focus_scale";
    /** M18-2：入场视频（SAF uri 字符串，空 = 用内置开场动画） */
    public static final String KEY_INTRO_VIDEO  = "bigscreen_intro_video";
    public static final String KEY_SOUND          = "bigscreen_sound_enabled";
    public static final String KEY_SOUND_VOLUME   = "bigscreen_sound_volume";
    /** M13：KR 存档兜底（SAF）—— 大屏原地启动时手动控制 */
    public static final String KEY_KR_SAF_FALLBACK = "bigscreen_kr_saf_fallback";
    public static final String KEY_FOCUS_TICK     = "bigscreen_focus_tick";
    public static final String KEY_KEY_STYLE      = "bigscreen_key_style";
    public static final String KEY_EFFECT_LEVEL   = "bigscreen_effect_level";
    public static final String KEY_RAIL_EXPANDED  = "bigscreen_rail_expanded";
    public static final String KEY_GRID_COLUMNS   = "bigscreen_grid_columns";
    public static final String KEY_SHOW_TITLES    = "bigscreen_show_titles";
    public static final String KEY_LAST_FILTER    = "bigscreen_last_filter";
    public static final String KEY_LAST_GAME      = "bigscreen_last_game_id";
    public static final String KEY_AUTO_ENTER     = "bigscreen_auto_enter";
    public static final String KEY_REMEMBER_FILTER= "bigscreen_remember_filter";
    public static final String KEY_VIEW_MODE      = "bigscreen_view_mode";
    public static final String KEY_TRAILER        = "bigscreen_trailer_enabled";
    public static final String KEY_TRAILER_DELAY  = "bigscreen_trailer_delay";
    public static final String KEY_TRAILER_MUTED  = "bigscreen_trailer_muted";
    /** PV 显示方式（M9）：true = 原比例完整显示，false = 铺满裁切 */
    public static final String KEY_PV_FIT         = "bigscreen_pv_fit";
    /** PV 遮罩开关与强度（M11）：播放时压暗视频，避免亮片影响文字可读性 */
    public static final String KEY_PV_SCRIM       = "bigscreen_pv_scrim";
    public static final String KEY_PV_SCRIM_PCT   = "bigscreen_pv_scrim_pct";
    /** 只在详情层播放PV（中等性能档默认行为，spec §S10.6） */
    public static final String KEY_TRAILER_DETAILS_ONLY = "bigscreen_trailer_details_only";
    /** 按键提示条模式：auto（4s 淡出）/ always（常显）/ off（隐藏） */
    public static final String KEY_HINT_MODE = "bigscreen_hint_mode";
    /** 卡片大小倍率（存 x100 的整数，避免 float 精度问题） */
    public static final String KEY_CARD_SCALE = "bigscreen_card_scale";
    /** 背景氛围层（雪花/极光）：开启且 high 档才显示 */
    public static final String KEY_SNOW = "bigscreen_snow_enabled";

    // ===== 默认值 =====
    public static final String DEF_KEY_STYLE   = "xbox";   // xbox / ps
    public static final String DEF_EFFECT      = "high";   // high / medium / low
    public static final String DEF_VIEW_MODE   = "shelf";  // shelf / grid
    public static final int    DEF_TRAILER_MS  = 2000;

    private final SharedPreferences sp;

    public BigScreenPrefs(Context context) {
        this.sp = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ===== 布尔项 =====
    public boolean introEnabled()   { return sp.getBoolean(KEY_INTRO, true); }

    // ===== M18-2：入场视频 + 观感自定义项 =====
    /** 入场视频（SAF uri 字符串，空 = 用内置开场动画）。
     *  M17 的"入场风格/速度/标题文字"三项已按用户要求**删除**。 */
    public String introVideoUri() { String s = sp.getString(KEY_INTRO_VIDEO, ""); return s == null ? "" : s; }
    public void setIntroVideoUri(String v) { sp.edit().putString(KEY_INTRO_VIDEO, v == null ? "" : v).apply(); }
    /** 顶部提示条停留时长 */
    public int bannerHoldMs() { return clamp(sp.getInt(KEY_BANNER_HOLD, 2000), 800, 6000); }
    public void setBannerHoldMs(int v) { sp.edit().putInt(KEY_BANNER_HOLD, clamp(v, 800, 6000)).apply(); }
    /** 手柄焦点缩放幅度（百分比，0 = 只描边不缩放） */
    public int focusScale() { return clamp(sp.getInt(KEY_FOCUS_SCALE, 100), 0, 150); }
    public void setFocusScale(int v) { sp.edit().putInt(KEY_FOCUS_SCALE, clamp(v, 0, 150)).apply(); }
    public boolean soundEnabled()   { return sp.getBoolean(KEY_SOUND, true); }
    public boolean focusTick()      { return sp.getBoolean(KEY_FOCUS_TICK, true); }
    /** 卡片上是否再显示一遍标题（默认关：标题已经在信息浮层上了，重复显示没意义） */
    public boolean showTitles()     { return sp.getBoolean(KEY_SHOW_TITLES, false); }
    public boolean railExpanded()   { return sp.getBoolean(KEY_RAIL_EXPANDED, false); }
    public boolean autoEnter()      { return sp.getBoolean(KEY_AUTO_ENTER, false); }
    public boolean rememberFilter() { return sp.getBoolean(KEY_REMEMBER_FILTER, true); }
    public boolean trailerEnabled() { return sp.getBoolean(KEY_TRAILER, true); }
    /** 游戏 PV 是否静音（默认**不静音**：用户反馈"PV 没有声音"就是这里默认 true） */
    public boolean trailerMuted()   { return sp.getBoolean(KEY_TRAILER_MUTED, false); }

    /** PV 显示方式：true = 原比例完整显示（留黑边），false = 铺满裁切（默认） */
    public boolean pvFit()          { return sp.getBoolean(KEY_PV_FIT, false); }

    public void setPvFit(boolean v) { putBool(KEY_PV_FIT, v); }

    // ===== M13：启动相关 =====
    /**
     * KR 存档兜底（SAF）：游戏库启动前会做一次存储探测自动决定，大屏不做探测，
     * 改成手动开关 —— 只有当某款 KR 游戏"能启动但存不上档"时才需要打开。
     */
    public boolean krSafFallback()          { return sp.getBoolean(KEY_KR_SAF_FALLBACK, false); }
    public void setKrSafFallback(boolean v) { putBool(KEY_KR_SAF_FALLBACK, v); }

    /** PV 遮罩：开启后播放时压暗视频（默认开，强度 45%） */
    public boolean pvScrim()        { return sp.getBoolean(KEY_PV_SCRIM, true); }
    public void setPvScrim(boolean v) { putBool(KEY_PV_SCRIM, v); }
    public int pvScrimPercent()     { return clamp(sp.getInt(KEY_PV_SCRIM_PCT, 45), 0, 100); }
    public void setPvScrimPercent(int v) { sp.edit().putInt(KEY_PV_SCRIM_PCT, clamp(v, 0, 100)).apply(); }
    public boolean trailerDetailsOnly() { return sp.getBoolean(KEY_TRAILER_DETAILS_ONLY, false); }

    // ===== 提示条 / 卡片大小（M4）=====
    /** auto / always / off */
    public String hintMode() {
        String s = sp.getString(KEY_HINT_MODE, "auto");
        return s == null ? "auto" : s;
    }

    public void setHintMode(String v) { sp.edit().putString(KEY_HINT_MODE, v).apply(); }

    /** 卡片大小倍率（M18-5 起：小 1.0 / 大 1.12（默认）/ 更大 1.28） */
    public float cardScale() {
        int v = sp.getInt(KEY_CARD_SCALE, 112);   // 默认改成"大"（有人反馈原来标准还是偏小）
        if (v < 80) { v = 80; }
        if (v > 140) { v = 140; }
        return v / 100f;
    }

    public void setCardScale(int percent) {
        int v = Math.max(80, Math.min(130, percent));
        sp.edit().putInt(KEY_CARD_SCALE, v).apply();
    }

    /** 背景氛围层（雪花/极光）开关 */
    public boolean snowEnabled() { return sp.getBoolean(KEY_SNOW, true); }

    public void setSnowEnabled(boolean v) { putBool(KEY_SNOW, v); }

    /**
     * NSFW 封面模糊（**与主库共用一个键** {@code nsfw_blur_enabled}）。
     * 主库设置里关掉之后，大屏同步关掉，避免"这边遮那边不遮"。
     */
    public boolean nsfwBlur() { return sp.getBoolean("nsfw_blur_enabled", true); }

    public void setNsfwBlur(boolean v) { sp.edit().putBoolean("nsfw_blur_enabled", v).apply(); }

    public void setIntroEnabled(boolean v)   { putBool(KEY_INTRO, v); }
    public void setSoundEnabled(boolean v)   { putBool(KEY_SOUND, v); }
    public void setFocusTick(boolean v)      { putBool(KEY_FOCUS_TICK, v); }
    public void setShowTitles(boolean v)     { putBool(KEY_SHOW_TITLES, v); }
    public void setRailExpanded(boolean v)   { putBool(KEY_RAIL_EXPANDED, v); }
    public void setAutoEnter(boolean v)      { putBool(KEY_AUTO_ENTER, v); }
    public void setRememberFilter(boolean v) { putBool(KEY_REMEMBER_FILTER, v); }
    public void setTrailerEnabled(boolean v) { putBool(KEY_TRAILER, v); }
    public void setTrailerMuted(boolean v)   { putBool(KEY_TRAILER_MUTED, v); }
    public void setTrailerDetailsOnly(boolean v) { putBool(KEY_TRAILER_DETAILS_ONLY, v); }

    // ===== 数值项 =====
    /** 界面音效音量（0~100） */
    public int soundVolume()            { return clamp(sp.getInt(KEY_SOUND_VOLUME, 65), 0, 100); }
    public void setSoundVolume(int v)   { sp.edit().putInt(KEY_SOUND_VOLUME, clamp(v, 0, 100)).apply(); }

    /** 网格列数，0 = 自动（按屏幕宽度算） */
    public int gridColumns()            { return sp.getInt(KEY_GRID_COLUMNS, 0); }
    public void setGridColumns(int v)   { sp.edit().putInt(KEY_GRID_COLUMNS, Math.max(0, v)).apply(); }

    /** PV悬停播放延迟（毫秒） */
    public int trailerDelayMs()         { return clamp(sp.getInt(KEY_TRAILER_DELAY, DEF_TRAILER_MS), 300, 5000); }
    public void setTrailerDelayMs(int v){ sp.edit().putInt(KEY_TRAILER_DELAY, clamp(v, 300, 5000)).apply(); }

    // ===== 字符串项 =====
    /** 按键图标风格：xbox / ps */
    public String keyStyle()          { String s = sp.getString(KEY_KEY_STYLE, DEF_KEY_STYLE); return s == null ? DEF_KEY_STYLE : s; }
    public void setKeyStyle(String v) { sp.edit().putString(KEY_KEY_STYLE, v).apply(); }

    /** 性能档：high / medium / low（low 会自动关掉模糊、粒子、PV视频） */
    public String effectLevel()          { String s = sp.getString(KEY_EFFECT_LEVEL, DEF_EFFECT); return s == null ? DEF_EFFECT : s; }
    public void setEffectLevel(String v) { sp.edit().putString(KEY_EFFECT_LEVEL, v).apply(); }
    public boolean lowEndDevice()        { return "low".equals(effectLevel()); }
    public boolean mediumEndDevice()     { return "medium".equals(effectLevel()); }

    /** 主区视图：shelf（默认）/ grid */
    public String viewMode()          { String s = sp.getString(KEY_VIEW_MODE, DEF_VIEW_MODE); return s == null ? DEF_VIEW_MODE : s; }
    public void setViewMode(String v) { sp.edit().putString(KEY_VIEW_MODE, v).apply(); }

    /** 上次筛选（分类 id，如 ALL / FAV / PLAYING …） */
    public String lastFilter()          { String s = sp.getString(KEY_LAST_FILTER, "ALL"); return s == null ? "ALL" : s; }
    public void setLastFilter(String v) { sp.edit().putString(KEY_LAST_FILTER, v).apply(); }

    // ===== 长整型 =====
    /** 上次选中的游戏 id，-1 = 无 */
    public long lastGameId()          { return sp.getLong(KEY_LAST_GAME, -1L); }
    public void setLastGameId(long id) { sp.edit().putLong(KEY_LAST_GAME, id).apply(); }

    // ===== 内部工具 =====
    private void putBool(String k, boolean v) { sp.edit().putBoolean(k, v).apply(); }

    private static int clamp(int v, int min, int max) {
        return v < min ? min : (v > max ? max : v);
    }
}