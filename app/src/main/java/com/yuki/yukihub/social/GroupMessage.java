package com.yuki.yukihub.social;

/**
 * 群组消息数据模型
 */
public class GroupMessage {
    public int id;
    public String senderId;
    public String senderNickname;
    public String senderAvatar;
    public int senderUid;
    public boolean senderIsAdmin;
    /** 发送者等级（0 = 未知，此时不显示徽章，避免乐观 UI 闪错值） */
    public int senderLevel;
    /**
     * 发送者昵称颜色，形如 "#f48fb1"；空串表示未装备，昵称走默认色。
     *
     * 与 senderLevel 同理不入本地缓存：用户随时能在萌萌点商店换色，
     * 缓存值必然过期，一律用服务端实时值。
     */
    public String senderNameColor;
    public String content;
    public String msgType;    // "text" | "emoji" | "image"
    public String createdAt;
    public boolean recalled;  // 被撤回标记
    public boolean deleted;   // 被删除标记（客户端应跳过显示并从缓存移除）
    public boolean isMine;
    /** 被回复的消息 id（0 = 不是回复）。群聊只有「被回复」才推送通知 */
    public int replyToId;

    public GroupMessage() {}
}