package com.yuki.yukihub.social;

/**
 * 群组数据模型
 */
public class GroupInfo {
    public int id;
    public String name;
    public String icon;        // emoji 图标
    public String type;        // "chat"=聊天室, "notice"=公告版
    public String description;
    public String memberRole;  // "admin" / "member"
    public int unreadCount;
    public String lastMessage;
    public String lastMessageTime;

    public GroupInfo() {}

    /** 是否公告版（全体禁言） */
    public boolean isNotice() {
        return "notice".equals(type);
    }

    /** 当前用户是否为管理员 */
    public boolean isAdmin() {
        return "admin".equals(memberRole);
    }

    /** 当前用户是否可以发言 */
    public boolean canSpeak() {
        return isAdmin() || !isNotice();
    }
}