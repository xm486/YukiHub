package com.yuki.yukihub.social;

/**
 * 聊天消息数据模型
 */
public class ChatMessage {
    public int id;
    public String senderId;
    public String receiverId;
    public String content;
    public String msgType;   // "text" | "emoji" | "image"
    public String createdAt;
    public boolean isMine;
    /** 被回复的消息 id（0 = 不是回复）。纯文本引用方案下仅用于服务端发通知，客户端不额外渲染引用条 */
    public int replyToId;

    public ChatMessage() {}
}
