package com.yuki.yukihub.ons;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import android.view.KeyEvent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ONS 虚拟按键布局配置。
 *
 * 设计要点：
 * 1. 全局一份（存 SharedPreferences("onsyuri") 的 btn_layout），
 *    因为操作习惯跟人不跟游戏，按游戏区分反而增加配置负担。
 * 2. 同一功能只允许一个实例。旧实现里有两个 AUTO 按钮共享同一个
 *    autoMode 标志，各点一次会让「界面显示开启但引擎已关闭」，
 *    这里从数据层就禁掉重复。
 * 3. 支持用户自定义按键：从 {@link #KEY_TABLE} 里挑 keyCode + 自填标签/图标，
 *    id 以 "custom:" 前缀区分，与内置功能互不干扰。
 */
public final class OnsButtonConfig {

    private static final String TAG = "OnsButtonConfig";
    private static final String PREF_NAME = "onsyuri";
    private static final String KEY_LAYOUT = "btn_layout";
    /** 收起状态单独存，不属于布局配置本身。 */
    private static final String KEY_COLLAPSED = "btn_collapsed";

    public static final int VERSION = 2;

    // ==================== 交互模式 ====================

    /** 点一下发一次「按下+抬起」。 */
    public static final String MODE_PRESS = "press";
    /** 按住持续生效（如 SKIP 快进），松手才抬起。 */
    public static final String MODE_HOLD = "hold";
    /** 切换式开关（如 AUTO），点一下发一次按键并翻转本地高亮。 */
    public static final String MODE_TOGGLE = "toggle";

    // ==================== 停靠位（兼容字段，已废弃，仅用于旧配置迁移） ====================

    public static final String[] SIDES = {
            "left-top", "left-mid", "left-bottom",
            "right-top", "right-mid", "right-bottom"
    };

    /**
     * 旧版 side 停靠位 → 中心点百分比坐标（0~100）。
     * 新版本按钮位置完全自由，由玩家在游戏内拖动决定。
     */
    public static float[] sideToXY(String side) {
        switch (side == null ? "right-mid" : side) {
            case "left-top":     return new float[]{6f, 8f};
            case "left-mid":     return new float[]{6f, 50f};
            case "left-bottom":  return new float[]{6f, 92f};
            case "right-top":    return new float[]{94f, 8f};
            case "right-bottom": return new float[]{94f, 92f};
            case "right-mid":
            default:             return new float[]{94f, 50f};
        }
    }

    public static String sideLabel(String side) {
        if (side == null) return "右中";
        switch (side) {
            case "left-top":     return "左上";
            case "left-mid":     return "左中";
            case "left-bottom":  return "左下";
            case "right-top":    return "右上";
            case "right-mid":    return "右中";
            case "right-bottom": return "右下";
            default:             return side;
        }
    }

    public static boolean isLeft(String side) {
        return side != null && side.startsWith("left");
    }

    // ==================== 内置功能表 ====================

    /** 一个可放到界面上的按钮定义。 */
    public static final class Def {
        public final String id;
        public final String icon;
        public final String label;
        public final int keyCode;
        public final String mode;
        public final String desc;

        Def(String id, String icon, String label, int keyCode, String mode, String desc) {
            this.id = id; this.icon = icon; this.label = label;
            this.keyCode = keyCode; this.mode = mode; this.desc = desc;
        }
    }

    /** 内置功能，顺序即「添加」菜单里的顺序。 */
    public static final Map<String, Def> DEFS = new LinkedHashMap<>();
    static {
        def("OK",   "✓", "OK",   KeyEvent.KEYCODE_ENTER,     MODE_PRESS,  "确认 / 推进对话");
        def("NEXT", "›", "NEXT", KeyEvent.KEYCODE_SPACE,     MODE_PRESS,  "推进文本");
        def("SKIP", "»", "SKIP", KeyEvent.KEYCODE_CTRL_LEFT, MODE_HOLD,   "按住快进，松手停止");
        def("AUTO", "▶", "AUTO", KeyEvent.KEYCODE_A,         MODE_TOGGLE, "切换自动播放");
        def("ESC",  "↩", "ESC",  KeyEvent.KEYCODE_ESCAPE,    MODE_PRESS,  "返回 / 取消");
        def("MENU", "☰", "MENU", KeyEvent.KEYCODE_MENU,      MODE_PRESS,  "呼出游戏菜单");
        def("SAVE", "⤓", "SAVE", KeyEvent.KEYCODE_S,         MODE_PRESS,  "快速存档（依脚本支持）");
        def("LOAD", "⤒", "LOAD", KeyEvent.KEYCODE_L,         MODE_PRESS,  "快速读档（依脚本支持）");
        def("LOG",  "≡", "LOG",  KeyEvent.KEYCODE_DPAD_UP,   MODE_PRESS,  "回顾历史文本");
        def("HIDE", "◌", "HIDE", KeyEvent.KEYCODE_H,         MODE_TOGGLE, "隐藏 / 显示文本框");
        def("FULL", "⛶", "FULL", KeyEvent.KEYCODE_F10,       MODE_TOGGLE, "切换拉伸全屏");
    }
    private static void def(String id, String ic, String lb, int kc, String md, String ds) {
        DEFS.put(id, new Def(id, ic, lb, kc, md, ds));
    }

    // ==================== 自定义按键可选键位 ====================

    /** 键位名 → keyCode，供「自定义按键」下拉选择。 */
    public static final Map<String, Integer> KEY_TABLE = new LinkedHashMap<>();
    static {
        KEY_TABLE.put("Enter 回车",      KeyEvent.KEYCODE_ENTER);
        KEY_TABLE.put("Space 空格",      KeyEvent.KEYCODE_SPACE);
        KEY_TABLE.put("Esc 退出",        KeyEvent.KEYCODE_ESCAPE);
        KEY_TABLE.put("Ctrl 快进",       KeyEvent.KEYCODE_CTRL_LEFT);
        KEY_TABLE.put("Menu 菜单",       KeyEvent.KEYCODE_MENU);
        KEY_TABLE.put("↑ 上",            KeyEvent.KEYCODE_DPAD_UP);
        KEY_TABLE.put("↓ 下",            KeyEvent.KEYCODE_DPAD_DOWN);
        KEY_TABLE.put("← 左",            KeyEvent.KEYCODE_DPAD_LEFT);
        KEY_TABLE.put("→ 右",            KeyEvent.KEYCODE_DPAD_RIGHT);
        KEY_TABLE.put("PageUp 上一页",   KeyEvent.KEYCODE_PAGE_UP);
        KEY_TABLE.put("PageDown 下一页", KeyEvent.KEYCODE_PAGE_DOWN);
        KEY_TABLE.put("Tab",             KeyEvent.KEYCODE_TAB);
        KEY_TABLE.put("Shift",           KeyEvent.KEYCODE_SHIFT_LEFT);
        KEY_TABLE.put("Alt",             KeyEvent.KEYCODE_ALT_LEFT);
        for (char c = 'A'; c <= 'Z'; c++) {
            KEY_TABLE.put("按键 " + c, KeyEvent.KEYCODE_A + (c - 'A'));
        }
        for (int i = 0; i <= 9; i++) {
            KEY_TABLE.put("数字 " + i, KeyEvent.KEYCODE_0 + i);
        }
        for (int i = 1; i <= 12; i++) {
            KEY_TABLE.put("F" + i, KeyEvent.KEYCODE_F1 + (i - 1));
        }
    }

    /** 反查 keyCode 对应的显示名，找不到就返回 "keyCode N"。 */
    public static String keyName(int keyCode) {
        for (Map.Entry<String, Integer> e : KEY_TABLE.entrySet()) {
            if (e.getValue() == keyCode) return e.getKey();
        }
        return "keyCode " + keyCode;
    }

    // ==================== 单个按钮实例 ====================

    public static final class Item {
        /** 内置功能 id，或 "custom:xxx"。 */
        public String id;
        /** 中心点水平百分比坐标 0~100，由玩家拖动决定。 */
        public float x = 94f;
        /** 中心点垂直百分比坐标 0~100，由玩家拖动决定。 */
        public float y = 50f;
        /** 旧版停靠位，仅读旧配置时用于推算 x/y。新代码不再使用。 */
        public String side;
        /** 自定义按键专用；内置功能留空时取 DEFS 里的值。 */
        public String icon;
        public String label;
        public int keyCode;
        public String mode = MODE_PRESS;

        public boolean isCustom() {
            return id != null && id.startsWith("custom:");
        }

        /** 取实际显示的图标：自定义用自己的，内置查表。 */
        public String icon() {
            if (isCustom()) return icon == null || icon.isEmpty() ? "●" : icon;
            Def d = DEFS.get(id);
            return d != null ? d.icon : "●";
        }

        public String label() {
            if (isCustom()) return label == null ? "" : label;
            Def d = DEFS.get(id);
            return d != null ? d.label : "";
        }

        public int keyCode() {
            if (isCustom()) return keyCode;
            Def d = DEFS.get(id);
            return d != null ? d.keyCode : 0;
        }

        public String mode() {
            if (isCustom()) return mode == null ? MODE_PRESS : mode;
            Def d = DEFS.get(id);
            return d != null ? d.mode : MODE_PRESS;
        }

        public String desc() {
            if (isCustom()) return keyName(keyCode) + " · 自定义";
            Def d = DEFS.get(id);
            return d != null ? d.desc : "";
        }
    }

    // ==================== 配置本体 ====================

    /** 按钮直径，dp。所有按钮统一大小。 */
    public int size = 52;
    public int gap = 10;
    /** 0~100。 */
    public int opacity = 62;
    public int margin = 14;

    public boolean toggleEnabled = true;
    /** 收起按钮中心点百分比坐标。 */
    public float toggleX = 94f;
    public float toggleY = 8f;
    public int toggleSize = 38;

    public final List<Item> items = new ArrayList<>();

    // ==================== 默认布局 ====================

    /** 默认布局：左三 ESC/SKIP/MENU，右三 OK/NEXT/AUTO。位置可被玩家拖动覆盖。 */
    public static OnsButtonConfig defaults() {
        OnsButtonConfig c = new OnsButtonConfig();
        c.add("ESC",  6f, 35f);
        c.add("SKIP", 6f, 50f);
        c.add("MENU", 6f, 65f);
        c.add("OK",  94f, 42f);
        c.add("NEXT", 94f, 55f);
        c.add("AUTO", 94f, 68f);
        return c;
    }

    /** 精简：只留最常用的三个。 */
    public static OnsButtonConfig presetMinimal() {
        OnsButtonConfig c = new OnsButtonConfig();
        c.add("SKIP", 8f, 60f);
        c.add("OK",  92f, 45f);
        c.add("AUTO", 92f, 62f);
        return c;
    }

    /** 左手模式：默认布局左右镜像。 */
    public static OnsButtonConfig presetLeftHand() {
        OnsButtonConfig c = new OnsButtonConfig();
        c.add("OK",   6f, 42f);
        c.add("NEXT", 6f, 55f);
        c.add("AUTO", 6f, 68f);
        c.add("SKIP", 94f, 50f);
        c.add("ESC",  94f, 35f);
        c.add("MENU", 94f, 65f);
        c.toggleX = 6f; c.toggleY = 8f;
        return c;
    }

    private void add(String id, float x, float y) {
        Item it = new Item();
        it.id = id; it.x = x; it.y = y;
        items.add(it);
    }

    /** 内置功能是否已存在，用于阻止重复添加。 */
    public boolean hasBuiltin(String id) {
        for (Item it : items) {
            if (id.equals(it.id)) return true;
        }
        return false;
    }

    /** 添加内置功能，重复则返回 false。位置默认给在右侧中部，玩家自行拖动。 */
    public boolean addBuiltin(String id) {
        if (!DEFS.containsKey(id) || hasBuiltin(id)) return false;
        add(id, 94f, 50f);
        return true;
    }

    /** 添加自定义按键。自定义允许同一 keyCode 多个（用途可能不同）。 */
    public Item addCustom(String label, String icon, int keyCode, String mode) {
        Item it = new Item();
        it.id = "custom:" + System.currentTimeMillis() + "_" + items.size();
        it.label = label == null ? "" : label.trim();
        it.icon = icon == null || icon.trim().isEmpty() ? "●" : icon.trim();
        it.keyCode = keyCode;
        it.mode = mode == null ? MODE_PRESS : mode;
        it.x = 94f; it.y = 50f;
        items.add(it);
        return it;
    }

    /** 所有按钮（含收起按钮）都给出中心点坐标，位置完全自由。 */
    public List<Item> allItems() {
        return items;
    }

    /**
     * 计算一个中心点坐标是否基本在屏幕内（允许少量越界，因为玩家拖动
     * 时按钮中心可能停在边缘附近）。用于编辑器提示。
     */
    public static boolean inScreen(float x, float y) {
        return x > -5f && x < 105f && y > -5f && y < 105f;
    }

    // ==================== 持久化 ====================

    public static OnsButtonConfig load(Context ctx) {
        try {
            SharedPreferences sp = ctx.getApplicationContext()
                    .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            String json = sp.getString(KEY_LAYOUT, null);
            if (json == null || json.trim().isEmpty()) return defaults();
            OnsButtonConfig c = fromJson(new JSONObject(json));
            // 空布局没有意义，回落到默认，避免用户误删全部后没有任何按键可用。
            return c.items.isEmpty() ? defaults() : c;
        } catch (Throwable t) {
            Log.w(TAG, "load failed, use defaults", t);
            return defaults();
        }
    }

    public void save(Context ctx) {
        try {
            ctx.getApplicationContext()
                    .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_LAYOUT, toJson().toString())
                    .apply();
        } catch (Throwable t) {
            Log.w(TAG, "save failed", t);
        }
    }

    /**
     * 清除旧版本残留的收起状态。
     *
     * 早期版本会持久化收起状态，导致升级后按钮全部不可见，
     * 而唯一的恢复入口是个半透明小图标，实测被误认为功能损坏。
     * 现在收起只在会话内有效，这个方法用于抹掉历史残留。
     */
    public static void clearCollapsed(Context ctx) {
        try {
            ctx.getApplicationContext()
                    .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                    .edit().remove(KEY_COLLAPSED).apply();
        } catch (Throwable t) {
            Log.w(TAG, "clearCollapsed failed", t);
        }
    }

    public JSONObject toJson() throws Exception {
        JSONObject o = new JSONObject();
        o.put("version", VERSION);
        o.put("size", size);
        o.put("gap", gap);
        o.put("opacity", opacity);
        o.put("margin", margin);

        JSONObject tg = new JSONObject();
        tg.put("enabled", toggleEnabled);
        tg.put("x", (double) round1(toggleX));
        tg.put("y", (double) round1(toggleY));
        tg.put("size", toggleSize);
        o.put("toggleBtn", tg);

        JSONArray arr = new JSONArray();
        for (Item it : items) {
            JSONObject j = new JSONObject();
            j.put("id", it.id);
            j.put("x", (double) round1(it.x));
            j.put("y", (double) round1(it.y));
            if (it.isCustom()) {
                j.put("icon", it.icon);
                j.put("label", it.label);
                j.put("keyCode", it.keyCode);
                j.put("mode", it.mode);
            }
            arr.put(j);
        }
        o.put("buttons", arr);
        return o;
    }

    private static float round1(float v) {
        return Math.round(v * 10f) / 10f;
    }

    private static OnsButtonConfig fromJson(JSONObject o) {
        OnsButtonConfig c = new OnsButtonConfig();
        c.size    = clamp(o.optInt("size", 52), 38, 66);
        c.gap     = clamp(o.optInt("gap", 10), 4, 24);
        c.opacity = clamp(o.optInt("opacity", 62), 20, 100);
        c.margin  = clamp(o.optInt("margin", 14), 6, 40);

        JSONObject tg = o.optJSONObject("toggleBtn");
        if (tg != null) {
            c.toggleEnabled = tg.optBoolean("enabled", true);
            // 新版用 x/y；旧版用 side
            c.toggleX = (float) tg.optDouble("x", -1);
            c.toggleY = (float) tg.optDouble("y", -1);
            if (c.toggleX < 0 || c.toggleY < 0) {
                float[] xy = sideToXY(tg.optString("side", "right-top"));
                c.toggleX = xy[0]; c.toggleY = xy[1];
            }
            c.toggleX = clampF(c.toggleX, 0f, 100f);
            c.toggleY = clampF(c.toggleY, 0f, 100f);
            c.toggleSize = clamp(tg.optInt("size", 38), 28, 56);
        }

        JSONArray arr = o.optJSONArray("buttons");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject j = arr.optJSONObject(i);
                if (j == null) continue;
                String id = j.optString("id", "");
                if (id.isEmpty()) continue;

                Item it = new Item();
                it.id = id;
                // 新版：直接读 x/y；旧版：读 side 再换算
                it.x = (float) j.optDouble("x", -1);
                it.y = (float) j.optDouble("y", -1);
                if (it.x < 0 || it.y < 0) {
                    float[] xy = sideToXY(j.optString("side", "right-mid"));
                    it.x = xy[0]; it.y = xy[1];
                }
                it.x = clampF(it.x, 0f, 100f);
                it.y = clampF(it.y, 0f, 100f);

                if (it.isCustom()) {
                    it.icon = j.optString("icon", "●");
                    it.label = j.optString("label", "");
                    it.keyCode = j.optInt("keyCode", 0);
                    it.mode = validMode(j.optString("mode", MODE_PRESS));
                    // keyCode 为 0 的自定义项无意义，丢弃
                    if (it.keyCode == 0) continue;
                } else if (!DEFS.containsKey(id)) {
                    // 未知内置 id（可能来自更新后被移除的功能），跳过
                    continue;
                } else if (c.hasBuiltin(id)) {
                    // 去重：同一内置功能只保留第一个
                    continue;
                }
                c.items.add(it);
            }
        }
        return c;
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
    private static float clampF(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
    private static String validMode(String m) {
        if (MODE_HOLD.equals(m) || MODE_TOGGLE.equals(m)) return m;
        return MODE_PRESS;
    }

    /** 深拷贝，供编辑界面「取消」时回滚。 */
    public OnsButtonConfig copy() {
        OnsButtonConfig c = new OnsButtonConfig();
        c.size = size; c.gap = gap; c.opacity = opacity; c.margin = margin;
        c.toggleEnabled = toggleEnabled;
        c.toggleX = toggleX; c.toggleY = toggleY; c.toggleSize = toggleSize;
        for (Item it : items) {
            Item n = new Item();
            n.id = it.id; n.x = it.x; n.y = it.y; n.icon = it.icon;
            n.label = it.label; n.keyCode = it.keyCode; n.mode = it.mode;
            c.items.add(n);
        }
        return c;
    }
}