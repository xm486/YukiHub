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
    public String content;
    public String msgType;    // "text"
    public String createdAt;
    public boolean recalled;  // 被撤回标记
    public boolean deleted;   // 被删除标记（客户端应跳过显示并从缓存移除）
    public boolean isMine;

    public GroupMessage() {}
}