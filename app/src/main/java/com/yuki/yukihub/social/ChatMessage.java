package com.yuki.yukihub.social;

/**
 * 聊天消息数据模型
 */
public class ChatMessage {
    public int id;
    public String senderId;
    public String receiverId;
    public String content;
    public String msgType;   // "text"（预留图片/表情）
    public String createdAt;
    public boolean isMine;

    public ChatMessage() {}
}