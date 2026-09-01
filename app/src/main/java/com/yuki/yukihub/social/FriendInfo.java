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
    public String note;        // 我对该好友的备注
    public String status;      // online / away / busy / offline
    public String activity;    // "正在玩：Clannad"
    public int unreadCount;    // 未读消息数
    public String lastHeartbeat; // 最后心跳时间（ISO 格式，用于离线时显示"最后在线"）
    public String friendSince;   // 好友关系建立时间（YYYY-MM-DD HH:MM:SS）
    /** 昵称颜色 hex（形如 "#f48fb1"），空串=未装备，昵称走默认色 */
    public String nameColor;
    /** 头像框（null = 没戴或未知，渲染时跳过） */
    public AvatarFrame frame;

    public FriendInfo() {}

    /** 显示名：有备注返备注，否则返昵称 */
    public String displayName() {
        return note != null && !note.trim().isEmpty() ? note.trim() : nickname;
    }

    public boolean isOnline()  { return "online".equals(status); }
    public boolean isAway()    { return "away".equals(status); }
    public boolean isBusy()    { return "busy".equals(status); }
    public boolean isOffline() { return "offline".equals(status); }
}
