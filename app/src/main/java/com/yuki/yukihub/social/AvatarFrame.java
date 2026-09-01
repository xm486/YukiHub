package com.yuki.yukihub.social;

import org.json.JSONObject;

/**
 * 头像框数据模型。
 *
 * 【位置参数的单位】
 * scale / offsetX / offsetY 都是「相对头像边长」的比例，不是 px。管理员在网页后台
 * 可视化调好后存库，一套参数适配所有尺寸的头像，客户端只负责按公式换算：
 *   框边长   = 头像边长 × scale
 *   横向位移 = 头像边长 × offsetX / 100
 *   纵向位移 = 头像边长 × offsetY / 100
 * 这套公式与网页端 community.js 的 frameOverlayHtml、后台 dashboard.php 的
 * feApplyFrameStyle 必须完全一致，改一处要同步另外两处，否则管理员看到的
 * 效果和玩家看到的不是一回事。
 */
public class AvatarFrame {

    /**
     * 「确认没戴框」的哨兵对象。
     *
     * 为什么需要它：服务端每条消息都会下发 senderFrame，没戴框时是 JSON null。
     * 但 Java 里 null 已经被用来表示「未知」（字段缺失 / 本地乐观消息 / 缓存读出），
     * 两者必须区分 —— 否则用户在商店摘下框后，「没戴框」这个事实就无法覆盖
     * 会话表里的旧框，头像会一直挂着已经卸掉的框。
     *
     * 所以：null = 不知道，别动；NO_FRAME = 确认没有，要覆盖。
     */
    public static final AvatarFrame NO_FRAME = new AvatarFrame();

    public String key = "";
    public String name = "";
    public String imageUrl = "";
    public float scale = 1.4f;
    public float offsetX = 0f;
    public float offsetY = 0f;

    public AvatarFrame() {}

    /** 有图才算真框；NO_FRAME 和解析失败的残缺数据都会在这里被判掉 */
    public boolean isValid() {
        return imageUrl != null && !imageUrl.trim().isEmpty();
    }

    /**
     * 从服务端下发的 JSON 解析。
     * @return 解析不出有效框（obj 为 null / JSON null / 没有 imageUrl）时返回 NO_FRAME，
     *         调用方据此当作「确认没戴框」处理
     */
    public static AvatarFrame fromJson(JSONObject obj) {
        if (obj == null) return NO_FRAME;
        AvatarFrame f = new AvatarFrame();
        f.key = obj.optString("key", "");
        f.name = obj.optString("name", "");
        f.imageUrl = obj.optString("imageUrl", "");
        // 范围收敛与服务端保持一致，防止异常值撑出一个糊住整屏的巨框
        f.scale = clamp((float) obj.optDouble("scale", 1.4), 0.5f, 4f);
        f.offsetX = clamp((float) obj.optDouble("offsetX", 0), -100f, 100f);
        f.offsetY = clamp((float) obj.optDouble("offsetY", 0), -100f, 100f);
        return f.isValid() ? f : NO_FRAME;
    }

    /** 序列化回 JSON，供本地缓存使用（无效框返回 null，调用方不要写字段） */
    public JSONObject toJson() {
        if (!isValid()) return null;
        try {
            JSONObject o = new JSONObject();
            o.put("key", key);
            o.put("name", name);
            o.put("imageUrl", imageUrl);
            o.put("scale", scale);
            o.put("offsetX", offsetX);
            o.put("offsetY", offsetY);
            return o;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static float clamp(float v, float lo, float hi) {
        if (Float.isNaN(v)) return lo;
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /** 同一个框（用于判断是否需要重绘） */
    public boolean sameAs(AvatarFrame other) {
        if (other == null) return false;
        if (!isValid() && !other.isValid()) return true;
        if (isValid() != other.isValid()) return false;
        return imageUrl.equals(other.imageUrl)
                && scale == other.scale
                && offsetX == other.offsetX
                && offsetY == other.offsetY;
    }
}