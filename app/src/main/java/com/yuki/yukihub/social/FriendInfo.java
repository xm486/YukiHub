package com.yuki.yukihub.social;

/**
 * 好友数据模型
 */
public class FriendInfo {
    public String id;          // users 表的主键 id（user_xxx）
    public int uid;            // 短数字 UID（用于搜索/添加好友）
    public String nickname;
    public String avatarUrl;
    public String signature;
    public String status;      // online / away / busy / offline
    public String activity;    // "正在玩：Clannad"
    public int unreadCount;    // 未读消息数

    public FriendInfo() {}

    public boolean isOnline()  { return "online".equals(status); }
    public boolean isAway()    { return "away".equals(status); }
    public boolean isBusy()    { return "busy".equals(status); }
    public boolean isOffline() { return "offline".equals(status); }
}
