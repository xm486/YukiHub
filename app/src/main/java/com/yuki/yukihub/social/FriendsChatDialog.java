package com.yuki.yukihub.social;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.yuki.yukihub.R;
import com.yuki.yukihub.util.AppExecutors;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 好友列表 / 聊天全屏弹窗。
 * 提供好友列表、聊天界面、添加好友、好友请求等功能。
 * 可在 HomeActivity 和 MainActivity 中统一调用。
 */
public class FriendsChatDialog {

    private static final long POLL_INTERVAL_MS = 10_000;
    /** 打开会话时首屏渲染的缓存消息上限（其余靠翻历史加载，避免一次性渲染过多） */
    private static final int RENDER_LIMIT = 200;
    private static final String PREFS_NAME = "yukihub_prefs";
    private static final String KEY_AUTH_ACCESS_TOKEN = "auth_access_token";
    private static final String KEY_AUTH_NICKNAME = "auth_nickname";
    private static final String KEY_AUTH_AVATAR = "auth_avatar";
    private static final String KEY_AUTH_UID = "auth_uid";

    // 头像缓存（避免重复加载）
    private static final int AVATAR_CACHE_SIZE = 64;
    private static final android.util.LruCache<String, android.graphics.Bitmap> avatarCache =
            new android.util.LruCache<>(AVATAR_CACHE_SIZE);

    // 表情包缓存（key = emoji URL）
    private static final int EMOJI_CACHE_SIZE = 64;
    private static final android.util.LruCache<String, android.graphics.Bitmap> emojiCache =
            new android.util.LruCache<>(EMOJI_CACHE_SIZE);

    private final Activity activity;
    private final Context appContext;
    private final SocialApiClient apiClient;
    private final ChatCacheHelper chatCache;
    private final android.os.Handler uiHandler = new android.os.Handler(android.os.Looper.getMainLooper());

    private Dialog dialog;
    private Dialog optionDialog;
    private Dialog emojiDialog;
    private java.util.Map<String, String> emojiUrlMap;  // emojiName → URL
    private LinearLayout contentHost;
    private LinearLayout contentContainer;
    private TextView titleBar;
    private TextView backButton;
    private ScheduledFuture<?> pollFuture;

    private List<FriendInfo> friends = new ArrayList<>();

    // 聊天状态
    private FriendInfo chatFriend;
    private int maxMessageId = 0;
    private LinearLayout chatMessageList;
    private EditText chatInput;
    private ScheduledFuture<?> chatPollFuture;
    private boolean hasMoreHistory = true;
    private int historyOffset = 0;
    /** 当前已渲染的最老一条消息 id（翻历史时用于查本地缓存） */
    private int oldestLoadedMessageId = 0;
    /** 当前正在打开的好友会话 id（异步回调校验，防止切换会话后旧回调污染新会话） */
    private String openingFriendId = null;
    /** 私聊：回到底部悬浮按钮 + 未读定位按钮 */
    private ScrollView chatScrollView;
    private View chatJumpBottomBtn;
    private View chatUnreadJumpBtn;
    /** 私聊：本次进入会话前的已读锚点（用于"跳到未读起点"） */
    private int chatUnreadAnchorId = 0;
    /**
     * 私聊已读锚点闸门。
     * 首屏渲染会调 scrollToBottom()，进而触发滚动监听里的 markChatRead()，
     * 若不加闸门会把刚读出的锚点立刻冲到最新，"N 条新消息"就永远不显示。
     * 只有用户真实交互过（上翻/点跳转/发消息）才开闸；离开会话走 forceMarkChatRead。
     */
    private boolean chatReadGateOpen = false;
    /** 私聊：待回复的消息（长按→回复时设置） */
    private ChatMessage pendingReplyChat;
    private View chatReplyBar;

    // 群组状态
    private List<GroupInfo> groupList = new ArrayList<>();
    private GroupInfo chatGroup;
    private int groupMaxMessageId = 0;
    private LinearLayout groupMessageList;
    private EditText groupChatInput;
    private ScheduledFuture<?> groupPollFuture;
    private boolean groupHasMoreHistory = true;
    private int groupHistoryOffset = 0;
    /** 当前已渲染的最老一条群消息 id（翻历史时用于查本地缓存） */
    private int groupOldestLoadedMessageId = 0;
    /** 当前正在打开的群会话 id（异步回调校验，防止切换会话后旧回调污染新会话） */
    private int openingGroupId = -1;
    /** 群聊：回到底部悬浮按钮 + 未读定位按钮 */
    private ScrollView groupScrollView;
    private View groupJumpBottomBtn;
    private View groupUnreadJumpBtn;
    /** 群聊：本次进入会话前的已读锚点 */
    private int groupUnreadAnchorId = 0;
    /** 群聊已读锚点闸门，语义同 chatReadGateOpen */
    private boolean groupReadGateOpen = false;
    /**
     * 群聊成员等级表（senderId -> level）。
     * 等级不进 SQLite 缓存（会过期），改用这张内存表：
     * 只要本次会话里见过某人的等级，他所有气泡（包括从缓存渲染的）都能立刻显示。
     */
    private final java.util.Map<String, Integer> groupLevelMap = new java.util.HashMap<>();

    /** 群聊：待回复的消息 */
    private GroupMessage pendingReplyGroup;
    private View groupReplyBar;

    // 请求列表
    private JSONArray incomingRequests = new JSONArray();
    private JSONArray outgoingRequests = new JSONArray();

    // 返回栈：当前所在页面
    private static final int VIEW_FRIEND_LIST = 0;
    private static final int VIEW_CHAT = 1;
    private static final int VIEW_GROUP_CHAT = 2;
    private static final int VIEW_PROFILE = 3;
    private static final int VIEW_ADD_FRIEND = 4;
    private static final int VIEW_REQUESTS = 5;
    private int currentView = VIEW_FRIEND_LIST;
    private int viewBeforeProfile = VIEW_FRIEND_LIST;

    /** 通知点击直达：待打开的私聊好友 id（show() 后消费一次） */
    private String pendingOpenFriendId = null;
    /** 通知点击直达：待打开的群 id（>0 有效） */
    private int pendingOpenGroupId = 0;

    public FriendsChatDialog(Activity activity) {
        this.activity = activity;
        this.appContext = activity.getApplicationContext();
        this.apiClient = new SocialApiClient(appContext);
        this.chatCache = new ChatCacheHelper(appContext);
    }

    /**
     * 打开弹窗并直达指定私聊会话（通知点击用）。
     * @param friendId 好友 user id
     */
    public void showAndOpenFriendChat(String friendId) {
        pendingOpenFriendId = friendId;
        pendingOpenGroupId = 0;
        show();
    }

    /**
     * 打开弹窗并直达指定群聊（通知点击用）。
     * @param groupId 群 id
     */
    public void showAndOpenGroupChat(int groupId) {
        pendingOpenGroupId = groupId;
        pendingOpenFriendId = null;
        show();
    }

    public void show() {
        if (!isLoggedIn()) {
            Toast.makeText(activity, "请先登录后再使用好友/聊天功能", Toast.LENGTH_SHORT).show();
            return;
        }

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.bg_social_panel);
        root.setPadding(dp(14), dp(10), dp(14), dp(10));

        // 标题栏
        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        backButton = new TextView(activity);
        backButton.setText("← 返回");
        backButton.setTextColor(0xFF8AB4FF);
        backButton.setTextSize(14);
        backButton.setVisibility(View.GONE);
        backButton.setOnClickListener(v -> {
            switch (currentView) {
                case VIEW_PROFILE:
                    // 从资料页返回到进入资料页之前的页面
                    if (viewBeforeProfile == VIEW_CHAT && chatFriend != null) {
                        showChatView(chatFriend);
                    } else if (viewBeforeProfile == VIEW_GROUP_CHAT && chatGroup != null) {
                        showGroupChatView(chatGroup);
                    } else {
                        showFriendList();
                    }
                    break;
                case VIEW_CHAT:
                case VIEW_GROUP_CHAT:
                case VIEW_ADD_FRIEND:
                case VIEW_REQUESTS:
                    showFriendList();
                    break;
                default:
                    showFriendList();
                    break;
            }
        });
        header.addView(backButton);

        titleBar = new TextView(activity);
        titleBar.setText("好友 / 聊天");
        titleBar.setTextColor(0xFFF5F7FF);
        titleBar.setTextSize(16);
        titleBar.setTypeface(null, android.graphics.Typeface.BOLD);
        titleBar.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
        header.addView(titleBar);

        // 关闭按钮
        TextView closeBtn = new TextView(activity);
        closeBtn.setText("✕");
        closeBtn.setTextColor(0xFF8AB4FF);
        closeBtn.setTextSize(18);
        closeBtn.setPadding(dp(6), 0, dp(2), 0);
        closeBtn.setOnClickListener(v -> dialog.dismiss());
        header.addView(closeBtn);

        root.addView(header);

        // 内容宿主：列表页可滚动，聊天页自行布局
        contentHost = new LinearLayout(activity);
        contentHost.setOrientation(LinearLayout.VERTICAL);
        contentHost.setLayoutParams(new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(contentHost);
        resetContent(true);

        dialog = new Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        dialog.setContentView(root);
        dialog.setCancelable(true);
        dialog.setOnDismissListener(d -> {
            forceMarkChatRead();
            ChatNotifier.clearActiveConversation();
            stopAllPolling();
        });

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(R.drawable.bg_social_panel);
            dialog.getWindow().setLayout(
                    (int)(activity.getResources().getDisplayMetrics().widthPixels * 0.88f),
                    (int)(activity.getResources().getDisplayMetrics().heightPixels * 0.82f));
            dialog.getWindow().setSoftInputMode(
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
                    | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        dialog.show();
        // 待直达的会话在 renderFriendList 末尾消费，
        // 避免列表渲染的异步回调把已打开的会话页重绘掉（并发 IO 池顺序不确定）
        showFriendList();
    }

    /**
     * 消费"直达会话"请求：好友列表加载完成后自动进入目标会话。
     * 好友对象需要从服务器列表里取（要昵称、在线状态等），因此走 IO 线程。
     */
    private void consumePendingOpen(List<FriendInfo> loadedFriends, List<GroupInfo> loadedGroups) {
        final String targetFriendId = pendingOpenFriendId;
        final int targetGroupId = pendingOpenGroupId;
        pendingOpenFriendId = null;
        pendingOpenGroupId = 0;
        if ((targetFriendId == null || targetFriendId.isEmpty()) && targetGroupId <= 0) return;
        if (dialog == null || !dialog.isShowing()) return;

        // 复用列表页刚拉到的数据，不再额外发请求，从根上消除两个异步任务抢渲染的竞态
        if (targetFriendId != null && !targetFriendId.isEmpty()) {
            FriendInfo hit = null;
            if (loadedFriends != null) {
                for (FriendInfo f : loadedFriends) {
                    if (f != null && targetFriendId.equals(f.id)) { hit = f; break; }
                }
            }
            if (hit != null) {
                showChatView(hit);
            } else {
                Toast.makeText(activity, "找不到该好友，可能已被删除", Toast.LENGTH_SHORT).show();
            }
            return;
        }

        GroupInfo ghit = null;
        if (loadedGroups != null) {
            for (GroupInfo g : loadedGroups) {
                if (g != null && g.id == targetGroupId) { ghit = g; break; }
            }
        }
        if (ghit != null) {
            showGroupChatView(ghit);
        } else {
            Toast.makeText(activity, "找不到该群聊，可能你已退群", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 重建内容区。
     * @param scrollable true=包一层 ScrollView（好友列表/搜索/请求/资料）；false=聊天页自管滚动
     */
    private void resetContent(boolean scrollable) {
        if (contentHost == null) return;
        contentHost.removeAllViews();
        contentContainer = new LinearLayout(activity);
        contentContainer.setOrientation(LinearLayout.VERTICAL);
        if (scrollable) {
            ScrollView scrollView = new ScrollView(activity);
            scrollView.setFillViewport(true);
            scrollView.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
            scrollView.setLayoutParams(new LinearLayout.LayoutParams(-1, -1));
            contentContainer.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
            scrollView.addView(contentContainer);
            contentHost.addView(scrollView);
        } else {
            contentContainer.setLayoutParams(new LinearLayout.LayoutParams(-1, -1));
            contentHost.addView(contentContainer);
        }
    }

    // ==================== 好友列表 ====================

    private void showFriendList() {
        // 离开会话：强制落盘已读锚点 + 解除通知抑制
        forceMarkChatRead();
        ChatNotifier.clearActiveConversation();
        titleBar.setText("好友 / 聊天");
        backButton.setVisibility(View.GONE);
        chatFriend = null;
        chatGroup = null;
        currentView = VIEW_FRIEND_LIST;
        resetContent(true);
        stopAllPolling();
        contentContainer.addView(loadingLabel("正在加载..."));

        AppExecutors.runOnIo(() -> {
            try {
                List<FriendInfo> loaded = apiClient.getFriendsList();
                int pendingCount = apiClient.getPendingRequestsCount();
                List<GroupInfo> groups = apiClient.getGroupsList();
                uiHandler.post(() -> renderFriendList(loaded, pendingCount, groups));
            } catch (Throwable t) {
                uiHandler.post(() -> showError("加载失败：" + t.getMessage(), () -> showFriendList()));
            }
        });
    }

    private void renderFriendList(List<FriendInfo> loaded, int pendingCount, List<GroupInfo> groups) {
        friends = loaded;
        groupList = groups;
        resetContent(true);

        // 操作栏
        LinearLayout bar = new LinearLayout(activity);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        Button addBtn = socialButton("+ 添加好友", v -> showAddFriendView());
        addBtn.setLayoutParams(new LinearLayout.LayoutParams(0, dp(36), 1));
        bar.addView(addBtn);

        View sp = new View(activity);
        sp.setLayoutParams(new LinearLayout.LayoutParams(dp(8), 0));
        bar.addView(sp);

        String reqText = pendingCount > 0 ? "好友请求 (" + pendingCount + ")" : "好友请求";
        Button reqBtn = socialButton(reqText, v -> showRequestsView());
        reqBtn.setLayoutParams(new LinearLayout.LayoutParams(0, dp(36), 1));
        bar.addView(reqBtn);
        contentContainer.addView(bar);

        contentContainer.addView(divider());

        // ====== 群组分区 ======
        if (groupList != null && !groupList.isEmpty()) {
            contentContainer.addView(sectionLabel("群组 — " + groupList.size()));
            for (GroupInfo g : groupList) {
                contentContainer.addView(buildGroupItem(g));
            }
            contentContainer.addView(divider());
        }

        // ====== 好友分区 ======
        if (friends.isEmpty()) {
            contentContainer.addView(emptyLabel("还没有好友\n点击上方「添加好友」搜索其他用户"));
        } else {
            List<FriendInfo> playing = new ArrayList<>();
            List<FriendInfo> online = new ArrayList<>();
            List<FriendInfo> offline = new ArrayList<>();
            for (FriendInfo f : friends) {
                if (f.isOffline() || f.isAway()) {
                    offline.add(f);
                } else if (f.isOnline() && f.activity != null && !f.activity.trim().isEmpty()) {
                    playing.add(f);
                } else {
                    online.add(f);
                }
            }
            // Steam 风格：正在玩游戏的排在最前
            if (!playing.isEmpty()) {
                contentContainer.addView(sectionLabel("正在游戏 — " + playing.size()));
                for (FriendInfo f : playing) contentContainer.addView(buildFriendItem(f));
                contentContainer.addView(divider());
            }
            if (!online.isEmpty()) {
                contentContainer.addView(sectionLabel("在线 — " + online.size()));
                for (FriendInfo f : online) contentContainer.addView(buildFriendItem(f));
                contentContainer.addView(divider());
            }
            if (!offline.isEmpty()) {
                contentContainer.addView(sectionLabel("离线 — " + offline.size()));
                for (FriendInfo f : offline) contentContainer.addView(buildFriendItem(f));
            }
        }
        startPolling();
        // 列表渲染完成，此时才安全地进入通知指定的会话
        consumePendingOpen(loaded, groups);
    }

    private View buildFriendItem(FriendInfo friend) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(6), dp(8), dp(6));
        row.setBackgroundResource(R.drawable.bg_friend_item);
        row.setClickable(true);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
        rlp.setMargins(0, dp(2), 0, dp(2));
        row.setLayoutParams(rlp);

        // 圆形头像占位（首字母）
        int avatarSize = 36;
        String initial = friend.nickname != null && !friend.nickname.isEmpty()
                ? friend.nickname.substring(0, 1) : "?";
        int bgColor = avatarBgColor(friend.nickname);
        TextView avatar = createCircleTextAvatar(avatarSize, initial, bgColor);
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(dp(avatarSize), dp(avatarSize));
        al.setMargins(0, 0, dp(10), 0);
        row.addView(avatar, al);

        // 圆形头像图片（覆盖在首字母上面）
        if (friend.avatarUrl != null && !friend.avatarUrl.isEmpty()) {
            ImageView avatarImg = new ImageView(activity);
            avatarImg.setScaleType(ImageView.ScaleType.CENTER_CROP);
            LinearLayout.LayoutParams imLp = new LinearLayout.LayoutParams(dp(avatarSize), dp(avatarSize));
            imLp.setMargins(0, 0, dp(10), 0);
            avatarImg.setLayoutParams(imLp);
            avatarImg.setVisibility(View.GONE);
            row.addView(avatarImg, 0);
            loadAvatarInto(friend.avatarUrl, avatarImg, avatar);
        }

        // 信息列
        LinearLayout col = new LinearLayout(activity);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));

        LinearLayout nameRow = new LinearLayout(activity);
        nameRow.setOrientation(LinearLayout.HORIZONTAL);
        nameRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = new TextView(activity);
        name.setText(friend.displayName());
        name.setTextColor(0xFFF5F7FF);
        name.setTextSize(14);
        name.setTypeface(null, android.graphics.Typeface.BOLD);
        nameRow.addView(name);

        // 如果有备注，在昵称后面显示小字原名
        if (friend.note != null && !friend.note.trim().isEmpty()) {
            TextView origName = new TextView(activity);
            origName.setText(" (" + friend.nickname + ")");
            origName.setTextColor(0xFF7A8599);
            origName.setTextSize(11);
            nameRow.addView(origName);
        }

        View dot = new View(activity);
        int d = dp(7);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(d, d);
        dl.setMargins(dp(6), 0, 0, 0);
        dot.setBackgroundResource(presenceDotRes(friend.status));
        nameRow.addView(dot, dl);
        col.addView(nameRow);

        // Steam 风格：仅 online + 有 activity 时显示绿色「正在玩」
        boolean showPlaying = friend.isOnline()
                && friend.activity != null
                && !friend.activity.trim().isEmpty();
        if (showPlaying) {
            TextView st = new TextView(activity);
            st.setText(friend.activity.trim());
            st.setTextColor(0xFF90BA3C); // Steam 在玩绿
            st.setTextSize(11);
            st.setMaxLines(1);
            st.setEllipsize(android.text.TextUtils.TruncateAt.END);
            col.addView(st);
        } else if (friend.isOffline() && friend.lastHeartbeat != null && !friend.lastHeartbeat.isEmpty()) {
            // Steam 风格：离线好友显示最后在线时间
            String lastSeen = formatHeartbeatRelative(friend.lastHeartbeat);
            if (lastSeen != null && !lastSeen.isEmpty()) {
                TextView st = new TextView(activity);
                st.setText("最后在线 " + lastSeen);
                st.setTextColor(0xFF7A8599);
                st.setTextSize(11);
                st.setMaxLines(1);
                st.setEllipsize(android.text.TextUtils.TruncateAt.END);
                col.addView(st);
            } else if (friend.signature != null && !friend.signature.isEmpty()) {
                TextView st = new TextView(activity);
                st.setText(friend.signature);
                st.setTextColor(0xFF9AA4BF);
                st.setTextSize(11);
                st.setMaxLines(1);
                st.setEllipsize(android.text.TextUtils.TruncateAt.END);
                col.addView(st);
            }
        } else if (friend.signature != null && !friend.signature.isEmpty()) {
            TextView st = new TextView(activity);
            st.setText(friend.signature);
            st.setTextColor(0xFF9AA4BF);
            st.setTextSize(11);
            st.setMaxLines(1);
            st.setEllipsize(android.text.TextUtils.TruncateAt.END);
            col.addView(st);
        }

        row.addView(col);

        // 未读红点
        if (friend.unreadCount > 0) {
            TextView badge = new TextView(activity);
            badge.setText(String.valueOf(friend.unreadCount));
            badge.setTextColor(0xFFFFFFFF);
            badge.setTextSize(10);
            badge.setGravity(Gravity.CENTER);
            badge.setBackgroundResource(R.drawable.bg_unread_badge);
            int bs = friend.unreadCount > 9 ? dp(20) : dp(18);
            badge.setLayoutParams(new LinearLayout.LayoutParams(bs, bs));
            row.addView(badge);
        }

        row.setOnClickListener(v -> showChatView(friend));
        // 长按弹出选项菜单（查看资料/备注/删除）
        row.setOnLongClickListener(v -> {
            showFriendOptions(friend);
            return true;
        });
        return row;
    }

    /** 构建群组列表项（风格与好友项一致） */
    private View buildGroupItem(GroupInfo group) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(6), dp(8), dp(6));
        row.setBackgroundResource(R.drawable.bg_friend_item);
        row.setClickable(true);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
        rlp.setMargins(0, dp(2), 0, dp(2));
        row.setLayoutParams(rlp);

        // 图标（emoji 代替头像，不用 bg_input 避免挤压）
        TextView iconView = new TextView(activity);
        iconView.setText(group.icon != null && !group.icon.isEmpty() ? group.icon : "🏛");
        iconView.setTextSize(26);  // 大号 emoji
        iconView.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams ilv = new LinearLayout.LayoutParams(dp(38), dp(38));
        ilv.setMargins(0, 0, dp(8), 0);
        row.addView(iconView, ilv);

        // 信息列
        LinearLayout col = new LinearLayout(activity);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));

        TextView name = new TextView(activity);
        name.setText(group.name);
        name.setTextColor(0xFFF5F7FF);
        name.setTextSize(14);
        name.setTypeface(null, android.graphics.Typeface.BOLD);
        col.addView(name);

        // 类型/描述行
        String desc = group.description != null && !group.description.isEmpty()
                ? group.description : (group.isNotice() ? "仅管理员可发言" : "公开聊天室");
        TextView descView = new TextView(activity);
        descView.setText(desc);
        descView.setTextColor(group.isAdmin() ? 0xFF90BA3C : 0xFF9AA4BF);
        descView.setTextSize(11);
        descView.setMaxLines(1);
        descView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(descView);

        row.addView(col);

        // 公告版小标签
        if (group.isNotice()) {
            TextView badge = new TextView(activity);
            badge.setText("公告");
            badge.setTextColor(0xFFFF9500);
            badge.setTextSize(10);
            badge.setPadding(dp(5), dp(2), dp(5), dp(2));
            badge.setBackgroundResource(R.drawable.bg_social_button);
            row.addView(badge);
        }

        // 未读红点
        if (group.unreadCount > 0) {
            TextView unreadBadge = new TextView(activity);
            unreadBadge.setText(String.valueOf(group.unreadCount));
            unreadBadge.setTextColor(0xFFFFFFFF);
            unreadBadge.setTextSize(10);
            unreadBadge.setGravity(Gravity.CENTER);
            unreadBadge.setBackgroundResource(R.drawable.bg_unread_badge);
            int bs = group.unreadCount > 9 ? dp(20) : dp(18);
            unreadBadge.setLayoutParams(new LinearLayout.LayoutParams(bs, bs));
            row.addView(unreadBadge);
        }

        row.setOnClickListener(v -> showGroupChatView(group));
        return row;
    }

    // ==================== 聊天界面 ====================

    private void showChatView(FriendInfo friend) {
        chatFriend = friend;
        chatGroup = null;
        currentView = VIEW_CHAT;
        pendingReplyChat = null;
        pendingReplyGroup = null;
        chatReadGateOpen = false;
        chatUnreadAnchorId = 0;
        // 已读锚点走 IO 线程读：主线程查 SQLite 有 ANR 风险。读到后回主线程刷未读提示
        if (friend != null) {
            final String fid0 = friend.id;
            AppExecutors.runOnIo(() -> {
                final int anchor = chatCache.getFriendLastReadId(fid0);
                uiHandler.post(() -> {
                    if (!fid0.equals(openingFriendId)) return;
                    chatUnreadAnchorId = anchor;
                    refreshChatUnreadState();
                });
            });
        }
        maxMessageId = 0;
        historyOffset = 0;
        hasMoreHistory = true;
        stopChatPolling();
        stopGroupPolling();

        titleBar.setText(friend.nickname);
        backButton.setVisibility(View.VISIBLE);
        // 聊天页：固定布局（消息区自带 ScrollView + 底部输入栏）
        resetContent(false);

        ScrollView scrollView = new ScrollView(activity);
        scrollView.setFillViewport(true);
        chatScrollView = scrollView;

        chatMessageList = new LinearLayout(activity);
        chatMessageList.setOrientation(LinearLayout.VERTICAL);
        chatMessageList.setPadding(dp(4), dp(6), dp(4), dp(6));
        scrollView.addView(chatMessageList);

        scrollView.getViewTreeObserver().addOnScrollChangedListener(() -> {
            if (scrollView.getScrollY() == 0 && hasMoreHistory && chatMessagesCount() >= 20) {
                loadMoreHistory();
            }
            updateChatFloatingButtons();
        });

        // 消息区外面包一层 FrameLayout，用来叠放"回到底部""跳到未读"两个悬浮按钮
        FrameLayout msgArea = new FrameLayout(activity);
        msgArea.setLayoutParams(new LinearLayout.LayoutParams(-1, 0, 1));
        msgArea.addView(scrollView, new FrameLayout.LayoutParams(-1, -1));

        chatJumpBottomBtn = buildJumpBottomButton(v -> {
            chatReadGateOpen = true;   // 用户明确操作，此后允许推进已读锚点
            scrollToBottom();
            markChatRead();
        });
        FrameLayout.LayoutParams jbLp = new FrameLayout.LayoutParams(dp(38), dp(38));
        jbLp.gravity = Gravity.END | Gravity.BOTTOM;
        jbLp.setMargins(0, 0, dp(10), dp(12));
        msgArea.addView(chatJumpBottomBtn, jbLp);

        chatUnreadJumpBtn = buildUnreadJumpButton(v -> jumpToChatUnread());
        FrameLayout.LayoutParams ujLp = new FrameLayout.LayoutParams(-2, dp(28));
        ujLp.gravity = Gravity.END | Gravity.TOP;
        ujLp.setMargins(0, dp(8), dp(10), 0);
        msgArea.addView(chatUnreadJumpBtn, ujLp);

        contentContainer.addView(msgArea);

        // 回复引用条（选择"回复"后出现在输入栏上方）
        chatReplyBar = buildReplyBar(v -> clearPendingReply());
        contentContainer.addView(chatReplyBar);

        // 输入栏
        LinearLayout inputRow = new LinearLayout(activity);
        inputRow.setOrientation(LinearLayout.HORIZONTAL);
        inputRow.setGravity(Gravity.CENTER_VERTICAL);
        inputRow.setPadding(0, dp(4), 0, 0);

        // 表情按钮（左侧）
        TextView emojiBtn = new TextView(activity);
        emojiBtn.setText("😀");
        emojiBtn.setTextSize(22);
        emojiBtn.setGravity(Gravity.CENTER);
        emojiBtn.setOnClickListener(v -> {
            if (emojiDialog != null && emojiDialog.isShowing()) {
                emojiDialog.dismiss();
            } else {
                showEmojiPicker(false);
            }
        });
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(dp(40), dp(40));
        elp.setMargins(0, 0, dp(2), 0);
        emojiBtn.setLayoutParams(elp);
        inputRow.addView(emojiBtn);

        // 图片按钮
        TextView imgBtn = new TextView(activity);
        imgBtn.setText("🖼");
        imgBtn.setTextSize(20);
        imgBtn.setGravity(Gravity.CENTER);
        imgBtn.setOnClickListener(v -> pickAndSendImage(false));
        LinearLayout.LayoutParams ilp0 = new LinearLayout.LayoutParams(dp(36), dp(40));
        ilp0.setMargins(0, 0, dp(2), 0);
        imgBtn.setLayoutParams(ilp0);
        inputRow.addView(imgBtn);

        chatInput = new EditText(activity);
        chatInput.setHint("输入消息...");
        chatInput.setTextColor(0xFFF5F7FF);
        chatInput.setHintTextColor(0x889AA4BF);
        chatInput.setBackgroundResource(R.drawable.bg_chat_input);
        chatInput.setMaxLines(4);
        chatInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        chatInput.setHorizontallyScrolling(false);
        chatInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND
                    || (event != null && event.getKeyCode() == android.view.KeyEvent.KEYCODE_ENTER
                        && event.getAction() == android.view.KeyEvent.ACTION_DOWN
                        && !event.isShiftPressed())) {
                sendMessage();
                return true;
            }
            return false;
        });
        LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(0, -2, 1);
        il.setMargins(dp(4), 0, dp(4), 0);
        inputRow.addView(chatInput, il);

        Button sendBtn = socialButton("发送", v -> sendMessage());
        sendBtn.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(36)));
        inputRow.addView(sendBtn);
        contentContainer.addView(inputRow);

        openFriendChat();
    }

    private int chatMessagesCount() {
        return chatMessageList == null ? 0 : chatMessageList.getChildCount();
    }

    /**
     * 打开好友聊天：先渲染本地缓存（秒开/离线可看），再从服务器拉最近 20 条增量合并。
     */
    private void openFriendChat() {
        if (chatFriend == null) return;
        final String friendId = chatFriend.id;
        openingFriendId = friendId;
        // 当前会话内不弹通知
        ChatNotifier.setActiveConversation(ChatNotifier.friendKey(friendId));
        AppExecutors.runOnIo(() -> {
            // 1. 读本地缓存渲染（先乐观允许翻历史，服务器同步后再修正）
            List<ChatMessage> cached = chatCache.getFriendMessages(friendId, RENDER_LIMIT);
            uiHandler.post(() -> {
                if (!friendId.equals(openingFriendId)) return; // 会话已切换，丢弃旧回调
                renderCachedFriendMessages(cached, true);
                scrollToBottom();
            });
            // 2. 后台同步服务器最近 20 条增量（失败时保留缓存，离线也能用）
            try {
                boolean[] serverHasMore = new boolean[]{true};
                boolean refreshed = syncFriendHistoryFromServer(friendId, serverHasMore);
                if (refreshed) {
                    List<ChatMessage> all = chatCache.getFriendMessages(friendId, RENDER_LIMIT);
                    uiHandler.post(() -> {
                        if (!friendId.equals(openingFriendId)) return; // 会话已切换，丢弃旧回调
                        renderCachedFriendMessages(all, serverHasMore[0]);
                        scrollToBottom();
                    });
                } else {
                    // 没有新消息也要用服务器结果修正 hasMoreHistory（服务器到底时关闭翻页）。
                    // 已在 IO 线程，这里直接查库，避免 post 到主线程再查（会卡 UI）。
                    final int oldestSnapshot = oldestLoadedMessageId;
                    final boolean localMoreCalc = oldestSnapshot > 0
                            && chatCache.countFriendMessagesBefore(friendId, oldestSnapshot) > 0;
                    uiHandler.post(() -> {
                        if (!friendId.equals(openingFriendId)) return;
                        hasMoreHistory = localMoreCalc || serverHasMore[0];
                    });
                }
                startChatPolling();
            } catch (Throwable t) {
                if (cached.isEmpty()) {
                    uiHandler.post(() -> {
                        if (!friendId.equals(openingFriendId)) return;
                        showError("加载消息失败", () -> openFriendChat());
                    });
                    return;
                }
                startChatPolling();
            }
        });
    }

    /** 用本地缓存渲染好友聊天消息列表（全量重绘） */
    private void renderCachedFriendMessages(List<ChatMessage> msgs, boolean serverHasMore) {
        chatMessageList.removeAllViews();
        if (msgs == null || msgs.isEmpty()) {
            chatMessageList.addView(emptyLabel("开始聊天吧"));
            oldestLoadedMessageId = 0;
            hasMoreHistory = serverHasMore;
        } else {
            for (ChatMessage msg : msgs) {
                chatMessageList.addView(buildMessageBubble(msg));
                if (msg.id > maxMessageId) maxMessageId = msg.id;
            }
            oldestLoadedMessageId = msgs.get(0).id;
            // 先乐观允许翻页，再异步查本地缓存补正（避免主线程查 SQLite 卡 UI）
            hasMoreHistory = true;
            final String fidSnap = chatFriend.id;
            final int oldestSnap = oldestLoadedMessageId;
            final boolean serverMoreSnap = serverHasMore;
            AppExecutors.runOnIo(() -> {
                final boolean localMore = chatCache.countFriendMessagesBefore(fidSnap, oldestSnap) > 0;
                uiHandler.post(() -> {
                    if (!fidSnap.equals(openingFriendId)) return;
                    hasMoreHistory = localMore || serverMoreSnap;
                });
            });
        }
        historyOffset = msgs == null ? 0 : msgs.size();
        refreshChatUnreadState();
    }

    /**
     * 从服务器拉最近 20 条好友消息合并进缓存。
     * 若本地缓存与服务器最新消息之间存在断档（离线积压 > 20 条），自动多翻几页直到衔接。
     * @param serverHasMoreOut [0]：服务器是否可能还有更早的消息（false = 已到底）
     * @return 是否有新消息（需要刷新 UI）
     */
    private boolean syncFriendHistoryFromServer(String friendId, boolean[] serverHasMoreOut) throws Exception {
        int cachedMaxId = chatCache.getFriendMaxId(friendId);
        List<ChatMessage> fresh = new ArrayList<>();
        int offset = 0;
        boolean serverHasMore = true;
        while (true) {
            List<ChatMessage> page = apiClient.getChatHistory(friendId, offset, 20);
            chatCache.upsertFriendMessages(friendId, page);
            for (ChatMessage m : page) {
                if (cachedMaxId == 0 || m.id > cachedMaxId) fresh.add(m);
            }
            int pageMinId = page.isEmpty() ? 0 : page.get(0).id;
            boolean connected = page.isEmpty() || cachedMaxId == 0 || pageMinId <= cachedMaxId;
            serverHasMore = page.size() >= 20;
            if (connected || page.size() < 20) break;
            offset += 20;
            if (offset >= 200) break; // 安全上限：最多拉 10 页
        }
        chatCache.pruneFriendMessages(friendId);
        if (serverHasMoreOut != null) serverHasMoreOut[0] = serverHasMore;
        return !fresh.isEmpty();
    }

    /**
     * 翻历史（滚动到顶触发）：优先读本地缓存，缓存到底才请求服务器。
     */
    private void loadMoreHistory() {
        if (chatFriend == null || !hasMoreHistory) return;
        hasMoreHistory = false;
        final String friendId = chatFriend.id;
        int beforeId = oldestLoadedMessageId;
        int offset = historyOffset;
        AppExecutors.runOnIo(() -> {
            // 1. 本地缓存优先
            List<ChatMessage> older = chatCache.getFriendMessagesBefore(friendId, beforeId, 50);
            if (!older.isEmpty()) {
                // 已在 IO 线程：先把"是否还有更早"算完，再一次性 post 回主线程
                final int nextOldest = older.get(0).id;
                final boolean stillMore = chatCache.countFriendMessagesBefore(friendId, nextOldest) > 0;
                uiHandler.post(() -> {
                    if (!friendId.equals(openingFriendId)) return;
                    for (int i = older.size() - 1; i >= 0; i--) {
                        chatMessageList.addView(buildMessageBubble(older.get(i)), 0);
                    }
                    oldestLoadedMessageId = nextOldest;
                    historyOffset = offset + older.size();
                    hasMoreHistory = stillMore;
                });
                return;
            }
            // 2. 本地缓存到底 → 请求服务器
            try {
                List<ChatMessage> server = apiClient.getChatHistory(friendId, offset, 50);
                chatCache.upsertFriendMessages(friendId, server);
                chatCache.pruneFriendMessages(friendId);
                uiHandler.post(() -> {
                    if (!friendId.equals(openingFriendId)) return;
                    for (int i = server.size() - 1; i >= 0; i--) {
                        chatMessageList.addView(buildMessageBubble(server.get(i)), 0);
                    }
                    if (!server.isEmpty()) oldestLoadedMessageId = server.get(0).id;
                    historyOffset = offset + server.size();
                    hasMoreHistory = server.size() >= 50;
                });
            } catch (Throwable t) {
                uiHandler.post(() -> {
                    if (!friendId.equals(openingFriendId)) return;
                    hasMoreHistory = true;
                });
            }
        });
    }

    private View buildMessageBubble(ChatMessage msg) {
        LinearLayout wrapper = new LinearLayout(activity);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.setPadding(dp(4), dp(2), dp(4), dp(2));

        // 表情包消息：直接显示图片
        if ("emoji".equals(msg.msgType)) {
            View emojiView = buildEmojiContentView(msg.content);
            LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(dp(96), dp(96));
            elp.gravity = msg.isMine ? Gravity.END : Gravity.START;
            wrapper.addView(emojiView, elp);
            emojiView.setOnLongClickListener(v -> { showChatMsgOptions(msg); return true; });
        } else if ("image".equals(msg.msgType)) {
            View imgView = buildImageContentView(msg.content);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(150), dp(150));
            ilp.gravity = msg.isMine ? Gravity.END : Gravity.START;
            wrapper.addView(imgView, ilp);
            imgView.setOnLongClickListener(v -> { showChatMsgOptions(msg); return true; });
        } else {
            TextView bubble = new TextView(activity);
            bubble.setText(msg.content);
            bubble.setTextColor(0xFFF5F7FF);
            bubble.setTextSize(13);
            bubble.setLineSpacing(dp(2), 1.0f);
            bubble.setPadding(dp(10), dp(6), dp(10), dp(6));
            bubble.setBackgroundResource(msg.isMine ? R.drawable.bg_chat_bubble_self : R.drawable.bg_chat_bubble_friend);
            bubble.setTag("chat_bubble");

            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-2, -2);
            bl.gravity = msg.isMine ? Gravity.END : Gravity.START;
            wrapper.addView(bubble, bl);
            bubble.setOnLongClickListener(v -> { showChatMsgOptions(msg); return true; });
        }

        TextView time = new TextView(activity);
        time.setText(formatTime(msg.createdAt));
        time.setTextColor(0x889AA4BF);
        time.setTextSize(9);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-2, -2);
        tl.gravity = msg.isMine ? Gravity.END : Gravity.START;
        wrapper.addView(time, tl);
        // 未读定位需要知道每条气泡对应的消息 id 与归属
        wrapper.setTag("msg:" + msg.id + ":" + (msg.isMine ? 1 : 0));
        return wrapper;
    }

    /**
     * 私聊消息长按菜单（普通用户）：复制 / 回复 / 举报。
     * 与群聊管理员菜单区分开——私聊没有撤回删除权限。
     */
    private void showChatMsgOptions(ChatMessage msg) {
        if (msg == null) return;
        LinearLayout menuRoot = new LinearLayout(activity);
        menuRoot.setOrientation(LinearLayout.VERTICAL);
        menuRoot.setPadding(dp(4), dp(4), dp(4), dp(4));

        boolean isText = msg.msgType == null || "text".equals(msg.msgType);
        if (isText) {
            menuRoot.addView(menuButton("复制", v -> {
                dismissOptionDialog();
                copyToClipboard(msg.content);
            }));
        }

        menuRoot.addView(menuButton("回复", v -> {
            dismissOptionDialog();
            startReplyToChat(msg);
        }));

        // 只能举报别人的消息；乐观 UI 气泡（id=0）还没入库也不能举报
        if (!msg.isMine && msg.id > 0) {
            Button reportBtn = new Button(activity);
            reportBtn.setText("举报");
            reportBtn.setTextColor(0xFFFF6B6B);
            reportBtn.setTextSize(13);
            reportBtn.setBackgroundResource(R.drawable.bg_input);
            reportBtn.setPadding(dp(10), dp(8), dp(10), dp(8));
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
            rlp.setMargins(0, 0, 0, dp(4));
            reportBtn.setLayoutParams(rlp);
            reportBtn.setOnClickListener(v -> {
                dismissOptionDialog();
                showReportReasonDialog("chat", msg.id, 0);
            });
            menuRoot.addView(reportBtn);
        }

        menuRoot.addView(menuButton("取消", v -> dismissOptionDialog()));
        showOptionDialog("消息操作", menuRoot);
    }

    private void sendMessage() {
        if (chatFriend == null || chatInput == null) return;
        String content = chatInput.getText().toString().trim();
        if (content.isEmpty()) return;
        chatInput.setText("");
        chatReadGateOpen = true;

        // 引用回复：把 "> 昵称: 原文" 拼在正文前（纯文本引用方案，对端无需新版本也能看懂）
        final int replyToId = pendingReplyChat == null ? 0 : pendingReplyChat.id;
        if (pendingReplyChat != null) {
            String who = pendingReplyChat.isMine ? getMyNicknameOrDefault() : chatFriend.nickname;
            content = buildQuotePrefix(who, previewOfChat(pendingReplyChat)) + content;
            clearPendingReply();
        }
        final String msgContent = content;

        // 乐观 UI
        ChatMessage local = new ChatMessage();
        local.content = msgContent;
        local.isMine = true;
        final View bubbleView = buildMessageBubble(local);
        chatMessageList.addView(bubbleView);
        scrollToBottom();

        AppExecutors.runOnIo(() -> {
            try {
                ChatMessage sent = apiClient.sendMessage(chatFriend.id, msgContent, "text", replyToId);
                // 发送成功写入本地缓存
                chatCache.upsertFriendMessages(chatFriend.id, java.util.Collections.singletonList(sent));
                chatCache.pruneFriendMessages(chatFriend.id);
                uiHandler.post(() -> {
                    if (sent.id > maxMessageId) maxMessageId = sent.id;
                    // 用服务器返回的过滤后内容替换乐观气泡（敏感词修正）
                    if (!msgContent.equals(sent.content)) {
                        updateBubbleContent(bubbleView, sent.content);
                    }
                });
            } catch (Throwable t) {
                uiHandler.post(() -> Toast.makeText(activity, "发送失败: " + t.getMessage(), Toast.LENGTH_SHORT).show());
                handleApiError(t);
            }
        });
    }

    private void startChatPolling() {
        stopChatPolling();
        chatPollFuture = AppExecutors.scheduled().scheduleAtFixedRate(() -> {
            if (chatFriend == null || dialog == null || !dialog.isShowing()) {
                stopChatPolling();
                return;
            }
            try {
                List<ChatMessage> newMsgs = apiClient.pollNewMessages(maxMessageId, chatFriend.id);
                if (!newMsgs.isEmpty()) {
                    // 轮询到的新消息写入本地缓存
                    chatCache.upsertFriendMessages(chatFriend.id, newMsgs);
                    chatCache.pruneFriendMessages(chatFriend.id);
                    uiHandler.post(() -> {
                        for (ChatMessage msg : newMsgs) {
                            if (msg.id > maxMessageId) {
                                chatMessageList.addView(buildMessageBubble(msg));
                                maxMessageId = msg.id;
                            }
                        }
                        scrollToBottom();
                        markChatRead();
                    });
                }
            } catch (Throwable t) {
                handleApiError(t);
            }
        }, POLL_INTERVAL_MS, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private void stopChatPolling() {
        if (chatPollFuture != null) { chatPollFuture.cancel(false); chatPollFuture = null; }
    }

    // ==================== 群组聊天界面 ====================

    private void showGroupChatView(GroupInfo group) {
        chatGroup = group;
        chatFriend = null;
        currentView = VIEW_GROUP_CHAT;
        pendingReplyChat = null;
        pendingReplyGroup = null;
        groupReadGateOpen = false;
        groupUnreadAnchorId = 0;
        groupLevelMap.clear();   // 换群重新收集，避免串号
        if (group != null) {
            final int gid0 = group.id;
            AppExecutors.runOnIo(() -> {
                final int anchor = chatCache.getGroupLastReadId(gid0);
                uiHandler.post(() -> {
                    if (openingGroupId != gid0) return;
                    groupUnreadAnchorId = anchor;
                    refreshGroupUnreadState();
                });
            });
        }
        groupMaxMessageId = 0;
        groupHistoryOffset = 0;
        groupHasMoreHistory = true;
        stopChatPolling();
        stopGroupPolling();

        String titleIcon = group.icon != null && !group.icon.isEmpty() ? group.icon + " " : "";
        titleBar.setText(titleIcon + group.name);
        backButton.setVisibility(View.VISIBLE);
        resetContent(false);

        ScrollView scrollView = new ScrollView(activity);
        scrollView.setFillViewport(true);
        groupScrollView = scrollView;

        groupMessageList = new LinearLayout(activity);
        groupMessageList.setOrientation(LinearLayout.VERTICAL);
        groupMessageList.setPadding(dp(4), dp(6), dp(4), dp(6));
        scrollView.addView(groupMessageList);

        scrollView.getViewTreeObserver().addOnScrollChangedListener(() -> {
            if (scrollView.getScrollY() == 0 && groupHasMoreHistory && groupMessagesCount() >= 20) {
                loadMoreGroupHistory();
            }
            updateGroupFloatingButtons();
        });

        FrameLayout gMsgArea = new FrameLayout(activity);
        gMsgArea.setLayoutParams(new LinearLayout.LayoutParams(-1, 0, 1));
        gMsgArea.addView(scrollView, new FrameLayout.LayoutParams(-1, -1));

        groupJumpBottomBtn = buildJumpBottomButton(v -> {
            groupReadGateOpen = true;
            scrollGroupToBottom();
            markGroupRead();
        });
        FrameLayout.LayoutParams gjbLp = new FrameLayout.LayoutParams(dp(38), dp(38));
        gjbLp.gravity = Gravity.END | Gravity.BOTTOM;
        gjbLp.setMargins(0, 0, dp(10), dp(12));
        gMsgArea.addView(groupJumpBottomBtn, gjbLp);

        groupUnreadJumpBtn = buildUnreadJumpButton(v -> jumpToGroupUnread());
        FrameLayout.LayoutParams gujLp = new FrameLayout.LayoutParams(-2, dp(28));
        gujLp.gravity = Gravity.END | Gravity.TOP;
        gujLp.setMargins(0, dp(8), dp(10), 0);
        gMsgArea.addView(groupUnreadJumpBtn, gujLp);

        contentContainer.addView(gMsgArea);

        groupReplyBar = buildReplyBar(v -> clearPendingReply());
        contentContainer.addView(groupReplyBar);

        // 输入栏
        LinearLayout inputRow = new LinearLayout(activity);
        inputRow.setOrientation(LinearLayout.HORIZONTAL);
        inputRow.setGravity(Gravity.CENTER_VERTICAL);
        inputRow.setPadding(0, dp(4), 0, 0);

        boolean canSpeak = group.canSpeak();

        // 表情按钮（左侧）
        TextView emojiBtn = new TextView(activity);
        emojiBtn.setText("😀");
        emojiBtn.setTextSize(22);
        emojiBtn.setGravity(Gravity.CENTER);
        emojiBtn.setOnClickListener(v -> {
            if (canSpeak) {
                if (emojiDialog != null && emojiDialog.isShowing()) {
                    emojiDialog.dismiss();
                } else {
                    showEmojiPicker(true);
                }
            } else {
                Toast.makeText(activity, "公告版仅管理员可发言", Toast.LENGTH_SHORT).show();
            }
        });
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(dp(40), dp(40));
        elp.setMargins(0, 0, dp(2), 0);
        emojiBtn.setLayoutParams(elp);
        inputRow.addView(emojiBtn);

        // 图片按钮
        TextView gImgBtn = new TextView(activity);
        gImgBtn.setText("🖼");
        gImgBtn.setTextSize(20);
        gImgBtn.setGravity(Gravity.CENTER);
        gImgBtn.setOnClickListener(v -> {
            if (canSpeak) pickAndSendImage(true);
            else Toast.makeText(activity, "公告版仅管理员可发言", Toast.LENGTH_SHORT).show();
        });
        LinearLayout.LayoutParams gIlp = new LinearLayout.LayoutParams(dp(36), dp(40));
        gIlp.setMargins(0, 0, dp(2), 0);
        gImgBtn.setLayoutParams(gIlp);
        inputRow.addView(gImgBtn);

        groupChatInput = new EditText(activity);
        groupChatInput.setTextColor(0xFFF5F7FF);
        groupChatInput.setHintTextColor(0x889AA4BF);
        groupChatInput.setBackgroundResource(R.drawable.bg_chat_input);
        groupChatInput.setMaxLines(4);
        groupChatInput.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        groupChatInput.setHorizontallyScrolling(false);

        if (canSpeak) {
            groupChatInput.setHint("输入消息...");
            groupChatInput.setEnabled(true);
            groupChatInput.setOnEditorActionListener((v, actionId, event) -> {
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND
                        || (event != null && event.getKeyCode() == android.view.KeyEvent.KEYCODE_ENTER
                            && event.getAction() == android.view.KeyEvent.ACTION_DOWN
                            && !event.isShiftPressed())) {
                    sendGroupMessage();
                    return true;
                }
                return false;
            });
        } else {
            groupChatInput.setHint("仅管理员可发言");
            groupChatInput.setEnabled(false);
            groupChatInput.setAlpha(0.5f);
        }

        LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(0, -2, 1);
        il.setMargins(dp(4), 0, dp(4), 0);
        inputRow.addView(groupChatInput, il);

        Button sendBtn = socialButton("发送", v -> {
            if (canSpeak) sendGroupMessage();
            else Toast.makeText(activity, "公告版仅管理员可发言", Toast.LENGTH_SHORT).show();
        });
        sendBtn.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(36)));
        inputRow.addView(sendBtn);
        contentContainer.addView(inputRow);

        openGroupChat();
    }

    private int groupMessagesCount() {
        return groupMessageList == null ? 0 : groupMessageList.getChildCount();
    }

    /**
     * 打开群聊：先渲染本地缓存（秒开/离线可看），再从服务器拉最近 20 条增量合并。
     */
    private void openGroupChat() {
        if (chatGroup == null) return;
        final int groupId = chatGroup.id;
        openingGroupId = groupId;
        ChatNotifier.setActiveConversation(ChatNotifier.groupKey(groupId));
        AppExecutors.runOnIo(() -> {
            // 1. 读本地缓存渲染（先乐观允许翻历史，服务器同步后再修正）
            List<GroupMessage> cached = chatCache.getGroupMessages(groupId, RENDER_LIMIT);
            uiHandler.post(() -> {
                if (openingGroupId != groupId) return; // 会话已切换，丢弃旧回调
                renderCachedGroupMessages(cached, true);
                scrollGroupToBottom();
            });
            // 2. 后台同步服务器最近 20 条增量（失败时保留缓存，离线也能用）
            try {
                int[] online = new int[]{0};
                boolean[] serverHasMore = new boolean[]{true};
                boolean refreshed = syncGroupHistoryFromServer(groupId, online, serverHasMore);
                if (refreshed) {
                    List<GroupMessage> all = chatCache.getGroupMessages(groupId, RENDER_LIMIT);
                    uiHandler.post(() -> {
                        if (openingGroupId != groupId) return;
                        renderCachedGroupMessages(all, serverHasMore[0]);
                        scrollGroupToBottom();
                    });
                } else {
                    // 没有新消息也要用服务器结果修正 hasMoreHistory（服务器到底时关闭翻页）
                    // 已在 IO 线程，直接查库后再 post（避免主线程 SQLite 查询）
                    final int gOldestSnapshot = groupOldestLoadedMessageId;
                    final boolean gLocalMoreCalc = gOldestSnapshot > 0
                            && chatCache.countGroupMessagesBefore(groupId, gOldestSnapshot) > 0;
                    uiHandler.post(() -> {
                        if (openingGroupId != groupId) return;
                        groupHasMoreHistory = gLocalMoreCalc || serverHasMore[0];
                    });
                }
                uiHandler.post(() -> {
                    if (openingGroupId != groupId) return;
                    updateOnlineCount(online[0]);
                });
                startGroupPolling();
            } catch (Throwable t) {
                if (cached.isEmpty()) {
                    String err = t.getMessage() != null ? t.getMessage() : "未知错误";
                    uiHandler.post(() -> {
                        if (openingGroupId != groupId) return;
                        showError("加载消息失败：" + err, () -> openGroupChat());
                    });
                    return;
                }
                startGroupPolling();
            }
        });
    }

    /** 用本地缓存渲染群聊消息列表（全量重绘） */
    private void renderCachedGroupMessages(List<GroupMessage> msgs, boolean serverHasMore) {
        groupMessageList.removeAllViews();
        if (msgs == null || msgs.isEmpty()) {
            groupMessageList.addView(emptyLabel("还没有消息，来说点什么吧"));
            groupOldestLoadedMessageId = 0;
            groupHasMoreHistory = serverHasMore;
        } else {
            for (GroupMessage msg : msgs) {
                groupMessageList.addView(buildGroupMessageBubble(msg));
                if (msg.id > groupMaxMessageId) groupMaxMessageId = msg.id;
            }
            groupOldestLoadedMessageId = msgs.get(0).id;
            // 先乐观允许翻页，异步补正（同私聊）
            groupHasMoreHistory = true;
            final int gidSnap = chatGroup.id;
            final int gOldestSnap = groupOldestLoadedMessageId;
            final boolean gServerMoreSnap = serverHasMore;
            AppExecutors.runOnIo(() -> {
                final boolean localMore = chatCache.countGroupMessagesBefore(gidSnap, gOldestSnap) > 0;
                uiHandler.post(() -> {
                    if (openingGroupId != gidSnap) return;
                    groupHasMoreHistory = localMore || gServerMoreSnap;
                });
            });
        }
        groupHistoryOffset = msgs == null ? 0 : msgs.size();
        refreshGroupUnreadState();
    }

    /**
     * 从服务器拉最近 20 条群消息合并进缓存。
     * 若本地缓存与服务器最新消息之间存在断档，自动多翻几页直到衔接。
     * @param serverHasMoreOut [0]：服务器是否可能还有更早的消息（false = 已到底）
     * @return 是否有新消息（需要刷新 UI）
     */
    private boolean syncGroupHistoryFromServer(int groupId, int[] onlineCountOut, boolean[] serverHasMoreOut) throws Exception {
        int cachedMaxId = chatCache.getGroupMaxId(groupId);
        // 等级只从服务端来，拿到就立刻记进等级表，
        // 这样接下来渲染缓存消息时也能带上等级（不必等第二次进会话）
        final boolean[] lvChanged = new boolean[]{false};
        List<GroupMessage> fresh = new ArrayList<>();
        boolean changed = false;
        int offset = 0;
        boolean serverHasMore = true;
        while (true) {
            SocialApiClient.GroupHistoryResult result = apiClient.getGroupMessages(groupId, offset, 20);
            if (onlineCountOut != null) onlineCountOut[0] = result.onlineCount;
            chatCache.upsertGroupMessages(groupId, result.messages);
            if (recordGroupLevels(result.messages)) lvChanged[0] = true;
            for (GroupMessage m : result.messages) {
                if (m.deleted) {
                    // 删除状态变化：需重绘（缓存已移除该条）
                    changed = true;
                    continue;
                }
                if (cachedMaxId == 0 || m.id > cachedMaxId) {
                    fresh.add(m);
                    changed = true;
                }
            }
            int pageMinId = result.messages.isEmpty() ? 0 : result.messages.get(0).id;
            boolean connected = result.messages.isEmpty() || cachedMaxId == 0 || pageMinId <= cachedMaxId;
            serverHasMore = result.messages.size() >= 20;
            if (connected || result.messages.size() < 20) break;
            offset += 20;
            if (offset >= 200) break; // 安全上限：最多拉 10 页
        }
        chatCache.pruneGroupMessages(groupId);
        // 等级有更新 → 原地刷新已渲染气泡上的徽章（不必整屏重绘）
        if (lvChanged[0]) {
            final int gidLv = groupId;
            uiHandler.post(() -> {
                if (openingGroupId != gidLv) return;
                refreshRenderedLevelBadges();
            });
        }
        if (serverHasMoreOut != null) serverHasMoreOut[0] = serverHasMore;
        return changed;
    }

    private void updateOnlineCount(int count) {
        if (chatGroup == null) return;
        String titleIcon = chatGroup.icon != null && !chatGroup.icon.isEmpty() ? chatGroup.icon + " " : "";
        titleBar.setText(titleIcon + chatGroup.name + "  ·  🟢" + count + "在线");
    }

    /**
     * 群聊翻历史（滚动到顶触发）：优先读本地缓存，缓存到底才请求服务器。
     */
    private void loadMoreGroupHistory() {
        if (chatGroup == null || !groupHasMoreHistory) return;
        groupHasMoreHistory = false;
        final int groupId = chatGroup.id;
        int beforeId = groupOldestLoadedMessageId;
        int offset = groupHistoryOffset;
        AppExecutors.runOnIo(() -> {
            // 1. 本地缓存优先
            List<GroupMessage> older = chatCache.getGroupMessagesBefore(groupId, beforeId, 50);
            if (!older.isEmpty()) {
                // 已在 IO 线程：先算完再 post
                final int gNextOldest = older.get(0).id;
                final boolean gStillMore = chatCache.countGroupMessagesBefore(groupId, gNextOldest) > 0;
                uiHandler.post(() -> {
                    if (openingGroupId != groupId) return;
                    for (int i = older.size() - 1; i >= 0; i--) {
                        groupMessageList.addView(buildGroupMessageBubble(older.get(i)), 0);
                    }
                    groupOldestLoadedMessageId = gNextOldest;
                    groupHistoryOffset = offset + older.size();
                    groupHasMoreHistory = gStillMore;
                });
                return;
            }
            // 2. 本地缓存到底 → 请求服务器
            try {
                SocialApiClient.GroupHistoryResult result = apiClient.getGroupMessages(groupId, offset, 50);
                List<GroupMessage> server = result.messages;
                chatCache.upsertGroupMessages(groupId, server);
                chatCache.pruneGroupMessages(groupId);
                uiHandler.post(() -> {
                    if (openingGroupId != groupId) return;
                    int oldestServerId = 0;
                    for (int i = server.size() - 1; i >= 0; i--) {
                        if (server.get(i).deleted) continue; // 已删除，跳过显示
                        groupMessageList.addView(buildGroupMessageBubble(server.get(i)), 0);
                        oldestServerId = server.get(i).id;
                    }
                    if (oldestServerId > 0) groupOldestLoadedMessageId = oldestServerId;
                    groupHistoryOffset = offset + server.size();
                    groupHasMoreHistory = server.size() >= 50;
                    updateOnlineCount(result.onlineCount);
                });
            } catch (Throwable t) {
                uiHandler.post(() -> {
                    if (openingGroupId != groupId) return;
                    groupHasMoreHistory = true;
                });
            }
        });
    }

    /** 递归查找 wrapper 中带 group_bubble / group_bubble_media tag 的 View 并绑定长按事件 */
    private void bindLongClickToBubble(View container, GroupMessage msg) {
        Object tag = container.getTag();
        if ("group_bubble".equals(tag) || "group_bubble_media".equals(tag)) {
            container.setOnLongClickListener(v -> {
                showGroupMsgOptions(msg);
                return true;
            });
        }
        if (container instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) container;
            for (int i = 0; i < group.getChildCount(); i++) {
                bindLongClickToBubble(group.getChildAt(i), msg);
            }
        }
    }

    /**
     * 群聊消息长按菜单。普通用户：复制 / 回复 / 举报；管理员额外有撤回 / 删除。
     */
    private void showGroupMsgOptions(GroupMessage msg) {
        if (msg == null) return;
        LinearLayout menuRoot = new LinearLayout(activity);
        menuRoot.setOrientation(LinearLayout.VERTICAL);
        menuRoot.setPadding(dp(4), dp(4), dp(4), dp(4));

        boolean isText = msg.msgType == null || "text".equals(msg.msgType);
        if (isText) {
            menuRoot.addView(menuButton("复制", v -> {
                dismissOptionDialog();
                copyToClipboard(msg.content);
            }));
        }

        menuRoot.addView(menuButton("回复", v -> {
            dismissOptionDialog();
            startReplyToGroup(msg);
        }));

        if (!msg.isMine && msg.id > 0) {
            Button reportBtn = new Button(activity);
            reportBtn.setText("举报");
            reportBtn.setTextColor(0xFFFF6B6B);
            reportBtn.setTextSize(13);
            reportBtn.setBackgroundResource(R.drawable.bg_input);
            reportBtn.setPadding(dp(10), dp(8), dp(10), dp(8));
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
            rlp.setMargins(0, 0, 0, dp(4));
            reportBtn.setLayoutParams(rlp);
            reportBtn.setOnClickListener(v -> {
                dismissOptionDialog();
                showReportReasonDialog("group", msg.id, chatGroup == null ? 0 : chatGroup.id);
            });
            menuRoot.addView(reportBtn);
        }

        // 管理员专属
        if (chatGroup != null && chatGroup.isAdmin() && msg.id > 0) {
            menuRoot.addView(divider());
            menuRoot.addView(menuButton("撤回消息（管理）", v -> {
                dismissOptionDialog();
                doManageGroupMessage(msg, "recall");
            }));
            Button deleteBtn = new Button(activity);
            deleteBtn.setText("删除消息（管理）");
            deleteBtn.setTextColor(0xFFFF6B6B);
            deleteBtn.setTextSize(13);
            deleteBtn.setBackgroundResource(R.drawable.bg_input);
            deleteBtn.setPadding(dp(10), dp(8), dp(10), dp(8));
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-1, -2);
            dlp.setMargins(0, 0, 0, dp(4));
            deleteBtn.setLayoutParams(dlp);
            deleteBtn.setOnClickListener(v -> {
                dismissOptionDialog();
                doManageGroupMessage(msg, "delete");
            });
            menuRoot.addView(deleteBtn);
        }

        menuRoot.addView(menuButton("取消", v -> dismissOptionDialog()));
        showOptionDialog("消息操作", menuRoot);
    }

    /**
     * 记录一批消息里的等级信息。
     * @return true = 有等级发生变化（需要刷新已渲染的气泡）
     */
    private boolean recordGroupLevels(List<GroupMessage> msgs) {
        if (msgs == null || msgs.isEmpty()) return false;
        boolean changed = false;
        for (GroupMessage m : msgs) {
            if (m == null || m.senderLevel <= 0) continue;
            String key = m.senderId == null ? "" : m.senderId;
            if (key.isEmpty()) continue;
            Integer old = groupLevelMap.get(key);
            if (old == null || old != m.senderLevel) {
                groupLevelMap.put(key, m.senderLevel);
                changed = true;
            }
        }
        return changed;
    }

    /** 取某人的等级：消息自带值优先，其次查会话内的等级表 */
    private int levelOf(GroupMessage msg) {
        if (msg == null) return 0;
        if (msg.senderLevel > 0) return msg.senderLevel;
        if (msg.senderId == null || msg.senderId.isEmpty()) return 0;
        Integer v = groupLevelMap.get(msg.senderId);
        return v == null ? 0 : v;
    }

    /**
     * 原地刷新已渲染气泡上的等级徽章，无需整屏重绘、也不用退出重进。
     * 徽章的 tag 存的是 "lvbadge:<senderId>"，据此定位并更新。
     */
    private void refreshRenderedLevelBadges() {
        if (groupMessageList == null) return;
        for (int i = 0; i < groupMessageList.getChildCount(); i++) {
            applyLevelBadgeIn(groupMessageList.getChildAt(i));
        }
    }

    /** 递归查找并更新某个气泡子树里的等级徽章 */
    private void applyLevelBadgeIn(View v) {
        if (v == null) return;
        Object tag = v.getTag();
        if (tag instanceof String && ((String) tag).startsWith("lvbadge:")) {
            String sid = ((String) tag).substring("lvbadge:".length());
            Integer lv = groupLevelMap.get(sid);
            if (v instanceof TextView) {
                TextView badge = (TextView) v;
                if (lv != null && lv > 0) {
                    badge.setText("Lv." + lv);
                    badge.setTextColor(levelColor(lv));
                    badge.setBackgroundResource(levelBadgeBg(lv));
                    badge.setPadding(dp(5), dp(1), dp(5), dp(1));
                    badge.setVisibility(View.VISIBLE);
                } else {
                    badge.setVisibility(View.GONE);
                }
            }
            return;
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                applyLevelBadgeIn(g.getChildAt(i));
            }
        }
    }

    /**
     * 群聊气泡昵称行的等级徽章（比资料页的更小，不带点击）。
     * level <= 0 表示服务端未返回（例如乐观 UI 的本地占位），此时返回 null 不显示，
     * 避免闪出错误的 Lv.1。
     */
    private TextView buildGroupLevelBadge(GroupMessage msg) {
        int level = levelOf(msg);
        TextView badge = new TextView(activity);
        badge.setTextSize(8);
        badge.setTypeface(null, android.graphics.Typeface.BOLD);
        badge.setGravity(Gravity.CENTER);
        badge.setIncludeFontPadding(false);
        badge.setSingleLine(true);
        // 打 tag：等级晚到时能按 senderId 找回来原地更新，不必退出重进
        badge.setTag("lvbadge:" + (msg == null || msg.senderId == null ? "" : msg.senderId));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(15));
        lp.setMargins(dp(5), 0, 0, 0);
        lp.gravity = Gravity.CENTER_VERTICAL;
        badge.setLayoutParams(lp);
        if (level > 0) {
            badge.setText("Lv." + level);
            badge.setTextColor(levelColor(level));
            badge.setBackgroundResource(levelBadgeBg(level));
            badge.setPadding(dp(5), dp(1), dp(5), dp(1));
        } else {
            // 等级还不知道：占位但不显示，等 recordGroupLevels 收到值后原地填上
            badge.setVisibility(View.GONE);
        }
        return badge;
    }

    /** 构建管理员标识小标签 */
    private TextView buildAdminBadge() {
        TextView badge = new TextView(activity);
        badge.setText("管理");
        badge.setTextColor(0xFFF5A623);
        badge.setTextSize(8);
        badge.setTypeface(null, android.graphics.Typeface.BOLD);
        badge.setGravity(Gravity.CENTER);
        badge.setPadding(dp(4), dp(1), dp(4), dp(1));
        badge.setBackgroundResource(R.drawable.bg_input);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMargins(dp(4), 0, 0, 0);
        badge.setLayoutParams(lp);
        return badge;
    }

    private View buildGroupMessageBubble(GroupMessage msg) {
        LinearLayout wrapper = new LinearLayout(activity);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.setPadding(dp(4), dp(4), dp(4), dp(4));

        // 撤回消息
        if (msg.recalled) {
            TextView recalled = new TextView(activity);
            recalled.setText("管理员撤回了一条消息");
            recalled.setTextColor(0x889AA4BF);
            recalled.setTextSize(11);
            recalled.setGravity(Gravity.CENTER);
            recalled.setPadding(dp(8), dp(4), dp(8), dp(4));
            wrapper.addView(recalled);
            wrapper.setTag("msg:" + msg.id + ":" + (msg.isMine ? 1 : 0));
            return wrapper;
        }

        if (msg.isMine) {
            // 自己的消息：右侧布局（头像在最右，类似 QQ）
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.TOP);

            // 左侧：昵称 + 气泡（右对齐）
            LinearLayout leftCol = new LinearLayout(activity);
            leftCol.setOrientation(LinearLayout.VERTICAL);
            leftCol.setGravity(Gravity.END);
            leftCol.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));

            String myNick = msg.senderNickname != null && !msg.senderNickname.isEmpty()
                    ? msg.senderNickname : getMyNickname();
            if (myNick == null || myNick.isEmpty()) myNick = "我";

            // 昵称行（昵称 + 管理员标识）
            LinearLayout myNickRow = new LinearLayout(activity);
            myNickRow.setOrientation(LinearLayout.HORIZONTAL);
            myNickRow.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams nrLp = new LinearLayout.LayoutParams(-2, -2);
            nrLp.setMargins(0, 0, 0, dp(2));
            myNickRow.setLayoutParams(nrLp);

            TextView nickView = new TextView(activity);
            nickView.setText(myNick);
            nickView.setTextColor(0xFF8AB4FF);
            nickView.setTextSize(11);
            myNickRow.addView(nickView);

            // 等级徽章紧跟昵称，管理标识在最后
            myNickRow.addView(buildGroupLevelBadge(msg));
            if (msg.senderIsAdmin) {
                myNickRow.addView(buildAdminBadge());
            }
            leftCol.addView(myNickRow);

            if ("emoji".equals(msg.msgType)) {
                // 表情包：只显示图片
                View emojiView = buildEmojiContentView(msg.content);
                LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(dp(96), dp(96));
                elp.gravity = Gravity.END;
                leftCol.addView(emojiView, elp);
                emojiView.setTag("group_bubble_media");
            } else if ("image".equals(msg.msgType)) {
                View imgView = buildImageContentView(msg.content);
                LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(150), dp(150));
                ilp.gravity = Gravity.END;
                leftCol.addView(imgView, ilp);
                imgView.setTag("group_bubble_media");
            } else {
                TextView bubble = new TextView(activity);
                bubble.setText(msg.content);
                bubble.setTextColor(0xFFF5F7FF);
                bubble.setTextSize(13);
                bubble.setLineSpacing(dp(2), 1.0f);
                bubble.setPadding(dp(10), dp(6), dp(10), dp(6));
                bubble.setBackgroundResource(R.drawable.bg_chat_bubble_self);
                bubble.setTag("group_bubble");
                LinearLayout.LayoutParams bubbleLp = new LinearLayout.LayoutParams(-2, -2);
                bubbleLp.gravity = Gravity.END;
                leftCol.addView(bubble, bubbleLp);
            }

            TextView time = new TextView(activity);
            time.setText(formatTime(msg.createdAt));
            time.setTextColor(0x889AA4BF);
            time.setTextSize(9);
            time.setPadding(0, dp(1), dp(2), 0);
            LinearLayout.LayoutParams timeLp = new LinearLayout.LayoutParams(-2, -2);
            timeLp.gravity = Gravity.END;
            leftCol.addView(time, timeLp);

            row.addView(leftCol);

            // 右侧：圆形头像
            String myAvatar = msg.senderAvatar != null ? msg.senderAvatar : getMyAvatar();
            int selfBg = avatarBgColor(myNick);
            TextView avatarText = createCircleTextAvatar(34, myNick.isEmpty() ? "?" : myNick.substring(0, 1).toUpperCase(), selfBg);
            LinearLayout.LayoutParams abLp = new LinearLayout.LayoutParams(dp(34), dp(34));
            abLp.setMargins(dp(10), 0, 0, 0);
            row.addView(avatarText, abLp);

            // 点击自己的头像也查看资料
            if (msg.senderUid > 0) {
                final int sUid = msg.senderUid;
                avatarText.setOnClickListener(v -> showUserProfile(sUid));
            }

            if (myAvatar != null && !myAvatar.isEmpty()) {
                ImageView avatarImg = new ImageView(activity);
                avatarImg.setScaleType(ImageView.ScaleType.CENTER_CROP);
                avatarImg.setVisibility(View.GONE);
                row.addView(avatarImg, row.indexOfChild(avatarText), abLp);
                // 头像 ImageView 也绑点击
                if (msg.senderUid > 0) {
                    final int sUid = msg.senderUid;
                    avatarImg.setOnClickListener(v -> showUserProfile(sUid));
                }
                loadAvatarInto(myAvatar, avatarImg, avatarText);
            }

            wrapper.addView(row);
        } else {
            // 他人消息：头像 + 昵称 + 气泡
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.TOP);

            // 圆形头像
            String nick = msg.senderNickname != null ? msg.senderNickname : "?";
            int otherBg = avatarBgColor(nick);
            TextView avatarText = createCircleTextAvatar(34, nick.isEmpty() ? "?" : nick.substring(0, 1).toUpperCase(), otherBg);
            LinearLayout.LayoutParams abLp = new LinearLayout.LayoutParams(dp(34), dp(34));
            abLp.setMargins(0, 0, dp(10), 0);
            row.addView(avatarText, abLp);

            if (msg.senderAvatar != null && !msg.senderAvatar.isEmpty()) {
                ImageView avatarImg = new ImageView(activity);
                avatarImg.setScaleType(ImageView.ScaleType.CENTER_CROP);
                avatarImg.setVisibility(View.GONE);
                LinearLayout.LayoutParams imLp = new LinearLayout.LayoutParams(dp(34), dp(34));
                imLp.setMargins(0, 0, dp(10), 0);
                row.addView(avatarImg, 0, imLp); // 插到最前面覆盖文字
                // 头像 ImageView 也绑点击
                if (msg.senderUid > 0) {
                    final int sUid = msg.senderUid;
                    avatarImg.setOnClickListener(v -> showUserProfile(sUid));
                }
                // 长按头像 → @ 对方（QQ 式），只对他人生效
                avatarImg.setOnLongClickListener(v -> { mentionInGroup(msg); return true; });
                loadAvatarInto(msg.senderAvatar, avatarImg, avatarText);
            }
            // 点击头像查看资料（文字头像兜底）
            if (msg.senderUid > 0) {
                final int sUid = msg.senderUid;
                avatarText.setOnClickListener(v -> showUserProfile(sUid));
            }
            avatarText.setOnLongClickListener(v -> { mentionInGroup(msg); return true; });

            // 右侧：昵称 + 气泡
            LinearLayout rightCol = new LinearLayout(activity);
            rightCol.setOrientation(LinearLayout.VERTICAL);
            rightCol.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));

            // 昵称行（昵称 + 管理员标识）
            LinearLayout nickRow = new LinearLayout(activity);
            nickRow.setOrientation(LinearLayout.HORIZONTAL);
            nickRow.setGravity(Gravity.CENTER_VERTICAL);
            nickRow.setPadding(dp(2), 0, 0, dp(2));

            TextView nickView = new TextView(activity);
            nickView.setText(nick);
            nickView.setTextColor(0xFF8AB4FF);
            nickView.setTextSize(11);
            nickRow.addView(nickView);

            // 等级徽章紧跟昵称，管理标识在最后
            nickRow.addView(buildGroupLevelBadge(msg));
            if (msg.senderIsAdmin) {
                nickRow.addView(buildAdminBadge());
            }
            rightCol.addView(nickRow);

            if ("emoji".equals(msg.msgType)) {
                // 表情包：只显示图片
                View emojiView = buildEmojiContentView(msg.content);
                rightCol.addView(emojiView);
                emojiView.setTag("group_bubble_media");
            } else if ("image".equals(msg.msgType)) {
                View imgView = buildImageContentView(msg.content);
                rightCol.addView(imgView, new LinearLayout.LayoutParams(dp(150), dp(150)));
                imgView.setTag("group_bubble_media");
            } else {
                TextView bubble = new TextView(activity);
                bubble.setText(msg.content);
                bubble.setTextColor(0xFFF5F7FF);
                bubble.setTextSize(13);
                bubble.setLineSpacing(dp(2), 1.0f);
                bubble.setPadding(dp(10), dp(6), dp(10), dp(6));
                bubble.setBackgroundResource(R.drawable.bg_chat_bubble_friend);
                bubble.setTag("group_bubble");
                LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-2, -2);
                rightCol.addView(bubble, bl);
            }

            TextView time = new TextView(activity);
            time.setText(formatTime(msg.createdAt));
            time.setTextColor(0x889AA4BF);
            time.setTextSize(9);
            time.setPadding(dp(2), dp(1), 0, 0);
            rightCol.addView(time);

            row.addView(rightCol);
            wrapper.addView(row);
        }

        // 所有人都能长按气泡：普通用户 = 复制/回复/举报，管理员 = 额外撤回/删除。
        // 绑定到气泡而非整个 wrapper，避免和头像点击冲突。
        if (!msg.recalled) {
            bindLongClickToBubble(wrapper, msg);
        }
        wrapper.setLongClickable(false); // wrapper 本身不拦截长按
        wrapper.setTag("msg:" + msg.id + ":" + (msg.isMine ? 1 : 0));

        return wrapper;
    }

    private void doManageGroupMessage(GroupMessage msg, String action) {
        AppExecutors.runOnIo(() -> {
            try {
                boolean ok = apiClient.manageGroupMessage(msg.id, action);
                if (ok && chatGroup != null) {
                    // 同步本地缓存：撤回 → 标记撤回；删除 → 移除
                    if ("recall".equals(action)) {
                        chatCache.markGroupMessageRecalled(chatGroup.id, msg.id);
                    } else {
                        chatCache.deleteGroupMessage(chatGroup.id, msg.id);
                    }
                }
                uiHandler.post(() -> {
                    if (ok) {
                        Toast.makeText(activity,
                                "recall".equals(action) ? "已撤回" : "已删除",
                                Toast.LENGTH_SHORT).show();
                        // 重新加载当前群聊
                        if (chatGroup != null) showGroupChatView(chatGroup);
                    } else {
                        Toast.makeText(activity, "操作失败", Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Throwable t) {
                uiHandler.post(() ->
                        Toast.makeText(activity, "操作失败: " + t.getMessage(), Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void sendGroupMessage() {
        if (chatGroup == null || groupChatInput == null) return;
        String content = groupChatInput.getText().toString().trim();
        if (content.isEmpty()) return;
        groupChatInput.setText("");
        groupReadGateOpen = true;

        // 引用回复：服务端据 replyToId 给被回复者入队提醒（群聊只有被回复才推通知）
        final int replyToId = pendingReplyGroup == null ? 0 : pendingReplyGroup.id;
        if (pendingReplyGroup != null) {
            String who = pendingReplyGroup.senderNickname == null || pendingReplyGroup.senderNickname.isEmpty()
                    ? "对方" : pendingReplyGroup.senderNickname;
            content = buildQuotePrefix(who, previewOfGroup(pendingReplyGroup)) + content;
            clearPendingReply();
        }
        final String msgContent = content;

        // 乐观 UI
        GroupMessage local = new GroupMessage();
        local.content = msgContent;
        local.isMine = true;
        local.senderNickname = getMyNickname();
        local.senderAvatar = getMyAvatar();
        local.senderUid = getMyUid();
        local.senderIsAdmin = chatGroup != null && chatGroup.isAdmin();
        local.createdAt = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(new java.util.Date());
        final View bubbleView = buildGroupMessageBubble(local);
        groupMessageList.addView(bubbleView);
        scrollGroupToBottom();

        AppExecutors.runOnIo(() -> {
            try {
                GroupMessage sent = apiClient.sendGroupMessage(chatGroup.id, msgContent, "text", replyToId);
                // 发送成功写入本地缓存
                chatCache.upsertGroupMessages(chatGroup.id, java.util.Collections.singletonList(sent));
                chatCache.pruneGroupMessages(chatGroup.id);
                uiHandler.post(() -> {
                    if (sent.id > groupMaxMessageId) groupMaxMessageId = sent.id;
                    // 用服务器返回的过滤后内容替换乐观气泡（敏感词修正）
                    if (!msgContent.equals(sent.content)) {
                        updateBubbleContent(bubbleView, sent.content);
                    }
                    // 服务端响应带自己的等级：记下后补上乐观气泡缺的徽章
                    if (recordGroupLevels(java.util.Collections.singletonList(sent))) {
                        refreshRenderedLevelBadges();
                    }
                });
            } catch (Throwable t) {
                uiHandler.post(() ->
                    Toast.makeText(activity, "发送失败: " + t.getMessage(), Toast.LENGTH_SHORT).show());
                handleApiError(t);
            }
        });
    }

    private void startGroupPolling() {
        stopGroupPolling();
        groupPollFuture = AppExecutors.scheduled().scheduleAtFixedRate(() -> {
            if (chatGroup == null || dialog == null || !dialog.isShowing()) {
                stopGroupPolling();
                return;
            }
            try {
                SocialApiClient.GroupPollResult result = apiClient.pollGroupMessages(chatGroup.id, groupMaxMessageId);
                List<GroupMessage> newMsgs = result.messages;
                boolean lvChanged = false;
                if (!newMsgs.isEmpty()) {
                    // 轮询到的新消息写入本地缓存（含撤回状态同步）
                    chatCache.upsertGroupMessages(chatGroup.id, newMsgs);
                    chatCache.pruneGroupMessages(chatGroup.id);
                    // 等级变化（升级 / 首次见到这个人）也在这里同步
                    lvChanged = recordGroupLevels(newMsgs);
                }
                final boolean lvChangedFinal = lvChanged;
                uiHandler.post(() -> {
                    if (!newMsgs.isEmpty()) {
                        for (GroupMessage msg : newMsgs) {
                            if (msg.deleted) {
                                // 已删除：跳过显示（缓存已在 upsert 时移除），但游标照常推进，
                                // 避免下次轮询用旧 afterId 导致这条消息无限重复返回
                                if (msg.id > groupMaxMessageId) groupMaxMessageId = msg.id;
                                continue;
                            }
                            if (msg.id > groupMaxMessageId) {
                                // 跳过自己发的（乐观 UI 已展示）
                                if (!msg.isMine) {
                                    groupMessageList.addView(buildGroupMessageBubble(msg));
                                }
                                groupMaxMessageId = msg.id;
                            }
                        }
                        scrollGroupToBottom();
                        markGroupRead();
                    }
                    // 等级实时刷新：有人升级或首次见到某人的等级，原地更新徽章
                    if (lvChangedFinal) refreshRenderedLevelBadges();
                    // 更新在线人数
                    updateOnlineCount(result.onlineCount);
                });
            } catch (Throwable t) {
                handleApiError(t);
            }
        }, POLL_INTERVAL_MS, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private void stopGroupPolling() {
        if (groupPollFuture != null) { groupPollFuture.cancel(false); groupPollFuture = null; }
    }

    /** 在气泡 View 树中递归查找 TextView 并更新文字（用于敏感词过滤后修正乐观 UI） */
    private void updateBubbleContent(View root, String newContent) {
        if (root instanceof android.widget.TextView) {
            android.widget.TextView tv = (android.widget.TextView) root;
            Object tag = tv.getTag();
            if ("group_bubble".equals(tag) || "chat_bubble".equals(tag)) {
                tv.setText(newContent);
            }
        }
        if (root instanceof android.view.ViewGroup) {
            android.view.ViewGroup vg = (android.view.ViewGroup) root;
            for (int i = 0; i < vg.getChildCount(); i++) {
                updateBubbleContent(vg.getChildAt(i), newContent);
            }
        }
    }

    private void scrollGroupToBottom() {
        scrollToBottomReliably(groupScrollView, groupMessageList);
    }

    // ==================== 添加好友 ====================

    private void showAddFriendView() {
        currentView = VIEW_ADD_FRIEND;
        titleBar.setText("添加好友");
        backButton.setVisibility(View.VISIBLE);
        resetContent(true);
        stopAllPolling();

        TextView hint = new TextView(activity);
        hint.setText("输入对方的 UID（数字）或昵称搜索");
        hint.setTextColor(0xFF9AA4BF);
        hint.setTextSize(12);
        hint.setPadding(0, 0, 0, dp(6));
        contentContainer.addView(hint);

        EditText input = new EditText(activity);
        input.setHint("UID 或昵称");
        input.setTextColor(0xFFF5F7FF);
        input.setHintTextColor(0x889AA4BF);
        input.setBackgroundResource(R.drawable.bg_input);
        input.setPadding(dp(10), 0, dp(10), 0);
        contentContainer.addView(input, new LinearLayout.LayoutParams(-1, dp(42)));
        input.requestFocus();

        Button btn = socialButton("搜索", v -> {
            String q = input.getText().toString().trim();
            if (q.isEmpty()) { Toast.makeText(activity, "请输入搜索关键词", Toast.LENGTH_SHORT).show(); return; }
            doSearch(q);
        });
        contentContainer.addView(btn, new LinearLayout.LayoutParams(-1, dp(38)));
    }

    private void doSearch(String query) {
        resetContent(true);
        contentContainer.addView(loadingLabel("正在搜索..."));
        AppExecutors.runOnIo(() -> {
            try {
                JSONArray results = apiClient.searchUsers(query);
                uiHandler.post(() -> renderSearchResults(results));
            } catch (Throwable t) {
                uiHandler.post(() -> showError("搜索失败：" + t.getMessage(), () -> doSearch(query)));
            }
        });
    }

    private void renderSearchResults(JSONArray results) {
        resetContent(true);
        if (results == null || results.length() == 0) {
            contentContainer.addView(emptyLabel("未找到匹配的用户"));
            return;
        }
        for (int i = 0; i < results.length(); i++) {
            try {
                JSONObject u = results.getJSONObject(i);
                String nickname = u.optString("nickname", "");
                String uid = String.valueOf(u.optInt("uid", 0));
                String signature = u.optString("signature", "");
                String friendStatus = u.optString("friendStatus", "none");
                String targetId = u.optString("id", "");

                LinearLayout row = new LinearLayout(activity);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(8), dp(6), dp(8), dp(6));
                row.setBackgroundResource(R.drawable.bg_friend_item);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
                rlp.setMargins(0, dp(2), 0, dp(2));
                row.setLayoutParams(rlp);

                // 圆形头像
                int sBg = avatarBgColor(nickname);
                TextView avatar = createCircleTextAvatar(32, nickname.isEmpty() ? "?" : nickname.substring(0, 1).toUpperCase(), sBg);
                LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(dp(32), dp(32));
                al.setMargins(0, 0, dp(10), 0);
                row.addView(avatar, al);

                // 加载头像
                String searchAvatarUrl = u.optString("avatarUrl", "");
                if (!searchAvatarUrl.isEmpty()) {
                    ImageView avatarImg = new ImageView(activity);
                    avatarImg.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    avatarImg.setLayoutParams(new LinearLayout.LayoutParams(dp(32), dp(32)));
                    avatarImg.setVisibility(View.GONE);
                    row.addView(avatarImg, 0);
                    loadAvatarInto(searchAvatarUrl, avatarImg, avatar);
                }

                LinearLayout col = new LinearLayout(activity);
                col.setOrientation(LinearLayout.VERTICAL);
                col.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
                TextView name = new TextView(activity);
                name.setText("UID " + uid + " · " + nickname);
                name.setTextColor(0xFFF5F7FF);
                name.setTextSize(13);
                col.addView(name);
                if (!signature.isEmpty()) {
                    TextView sig = new TextView(activity);
                    sig.setText(signature);
                    sig.setTextColor(0xFF9AA4BF);
                    sig.setTextSize(11);
                    col.addView(sig);
                }
                row.addView(col);

                // 点击查看资料
                final int searchUid = u.optInt("uid", 0);
                row.setOnClickListener(v -> showUserProfile(searchUid));

                if ("none".equals(friendStatus)) {
                    Button add = new Button(activity);
                    add.setText("添加");
                    add.setTextColor(0xFFFFFFFF);
                    add.setTextSize(11);
                    add.setBackgroundResource(R.drawable.bg_social_button);
                    add.setPadding(dp(8), dp(4), dp(8), dp(4));
                    final String targetUid = uid;
                    add.setOnClickListener(v -> {
                        add.setEnabled(false); add.setText("发送中...");
                        doSendRequest(targetUid, add);
                    });
                    row.addView(add);
                } else if ("pending".equals(friendStatus)) {
                    TextView st = new TextView(activity);
                    st.setText("已发送请求"); st.setTextColor(0xFF9AA4BF); st.setTextSize(11);
                    row.addView(st);
                } else if ("accepted".equals(friendStatus)) {
                    TextView st = new TextView(activity);
                    st.setText("已是好友"); st.setTextColor(0xFF34C759); st.setTextSize(11);
                    row.addView(st);
                }
                contentContainer.addView(row);
            } catch (Throwable ignored) {}
        }
    }

    private void doSendRequest(String targetId, Button btn) {
        AppExecutors.runOnIo(() -> {
            try {
                boolean success = apiClient.sendFriendRequest(targetId);
                uiHandler.post(() -> {
                    if (success) { btn.setText("已发送"); btn.setEnabled(false); btn.setTextColor(0xFF9AA4BF); }
                    else { btn.setText("添加"); btn.setEnabled(true); }
                });
            } catch (Throwable t) {
                uiHandler.post(() -> { btn.setText("添加"); btn.setEnabled(true);
                    Toast.makeText(activity, "发送失败: " + t.getMessage(), Toast.LENGTH_SHORT).show(); });
            }
        });
    }

    // ==================== 好友操作菜单 ====================

    private void showFriendOptions(FriendInfo friend) {
        LinearLayout menuRoot = new LinearLayout(activity);
        menuRoot.setOrientation(LinearLayout.VERTICAL);
        menuRoot.setPadding(dp(4), dp(4), dp(4), dp(4));

        Button profileBtn = menuButton("查看资料", v -> {
            dismissOptionDialog();
            showUserProfile(friend.uid);
        });
        menuRoot.addView(profileBtn);

        Button noteBtn = menuButton("设置备注", v -> {
            dismissOptionDialog();
            showNoteDialog(friend);
        });
        menuRoot.addView(noteBtn);

        Button deleteBtn = new Button(activity);
        deleteBtn.setText("删除好友");
        deleteBtn.setTextColor(0xFFFF6B6B);
        deleteBtn.setTextSize(13);
        deleteBtn.setBackgroundResource(R.drawable.bg_input);
        deleteBtn.setPadding(dp(10), dp(8), dp(10), dp(8));
        deleteBtn.setOnClickListener(v -> {
            dismissOptionDialog();
            confirmDeleteFriend(friend);
        });
        menuRoot.addView(deleteBtn);

        showOptionDialog("好友操作", menuRoot);
    }

    private void showNoteDialog(FriendInfo friend) {
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(4), dp(6), dp(4), dp(4));

        TextView hint = new TextView(activity);
        hint.setText("为 " + friend.nickname + " 设置备注（最多50字）");
        hint.setTextColor(0xFF9AA4BF);
        hint.setTextSize(12);
        hint.setPadding(0, 0, 0, dp(6));
        root.addView(hint);

        EditText input = new EditText(activity);
        input.setText(friend.note != null ? friend.note : "");
        input.setHint("输入备注名");
        input.setTextColor(0xFFF5F7FF);
        input.setHintTextColor(0x889AA4BF);
        input.setBackgroundResource(R.drawable.bg_input);
        input.setPadding(dp(10), dp(6), dp(10), dp(6));
        root.addView(input, new LinearLayout.LayoutParams(-1, dp(42)));

        Button saveBtn = socialButton("保存", v -> {
            String note = input.getText() == null ? "" : input.getText().toString().trim();
            dismissOptionDialog();
            AppExecutors.runOnIo(() -> {
                try {
                    boolean ok = apiClient.setFriendNote(friend.id, note);
                    uiHandler.post(() -> {
                        if (ok) {
                            friend.note = note;
                            showFriendList();
                            Toast.makeText(activity, "备注已保存", Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(activity, "保存失败", Toast.LENGTH_SHORT).show();
                        }
                    });
                } catch (Throwable t) {
                    uiHandler.post(() -> Toast.makeText(activity, "保存失败: " + t.getMessage(), Toast.LENGTH_SHORT).show());
                }
            });
        });
        root.addView(saveBtn, new LinearLayout.LayoutParams(-1, dp(38)));

        showOptionDialog("设置备注", root);
    }

    private void confirmDeleteFriend(FriendInfo friend) {
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(4), dp(6), dp(4), dp(4));

        TextView msg = new TextView(activity);
        msg.setText("确定要删除好友「" + friend.displayName() + "」吗？\n删除后聊天记录不会丢失，但好友关系将永久解除。");
        msg.setTextColor(0xFFF5F7FF);
        msg.setTextSize(13);
        msg.setLineSpacing(dp(3), 1.0f);
        msg.setPadding(0, 0, 0, dp(8));
        root.addView(msg);

        LinearLayout btnRow = new LinearLayout(activity);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);

        Button cancelBtn = socialButton("取消", v -> dismissOptionDialog());
        cancelBtn.setLayoutParams(new LinearLayout.LayoutParams(0, dp(38), 1));
        btnRow.addView(cancelBtn);

        View sp = new View(activity);
        sp.setLayoutParams(new LinearLayout.LayoutParams(dp(8), 0));
        btnRow.addView(sp);

        Button confirmBtn = new Button(activity);
        confirmBtn.setText("确认删除");
        confirmBtn.setTextColor(0xFFFF6B6B);
        confirmBtn.setTextSize(12);
        confirmBtn.setBackgroundResource(R.drawable.bg_input);
        confirmBtn.setPadding(dp(6), 0, dp(6), 0);
        confirmBtn.setOnClickListener(v -> {
            dismissOptionDialog();
            AppExecutors.runOnIo(() -> {
                try {
                    boolean ok = apiClient.removeFriend(friend.id);
                    uiHandler.post(() -> {
                        if (ok) {
                            Toast.makeText(activity, "已删除好友", Toast.LENGTH_SHORT).show();
                            showFriendList();
                        } else {
                            Toast.makeText(activity, "删除失败", Toast.LENGTH_SHORT).show();
                        }
                    });
                } catch (Throwable t) {
                    uiHandler.post(() -> Toast.makeText(activity, "删除失败: " + t.getMessage(), Toast.LENGTH_SHORT).show());
                }
            });
        });
        confirmBtn.setLayoutParams(new LinearLayout.LayoutParams(0, dp(38), 1));
        btnRow.addView(confirmBtn);

        root.addView(btnRow);
        showOptionDialog("删除好友", root);
    }

    // ==================== 好友请求 ====================

    // ==================== 用户资料 ====================

    private void showUserProfile(int uid) {
        viewBeforeProfile = currentView;
        currentView = VIEW_PROFILE;
        titleBar.setText("用户资料");
        backButton.setVisibility(View.VISIBLE);
        resetContent(true);
        stopAllPolling();
        contentContainer.addView(loadingLabel("正在加载..."));
        AppExecutors.runOnIo(() -> {
            try {
                JSONObject profile = apiClient.getUserProfile(uid);
                uiHandler.post(() -> renderUserProfile(profile));
            } catch (Throwable t) {
                uiHandler.post(() -> showError("加载失败：" + t.getMessage(), () -> showUserProfile(uid)));
            }
        });
    }

    private void renderUserProfile(JSONObject profile) {
        resetContent(true);
        String nickname = profile.optString("nickname", "");
        int uid = profile.optInt("uid", 0);
        String signature = profile.optString("signature", "");
        String avatarUrl = profile.optString("avatarUrl", "");
        String status = profile.optString("status", "offline");
        String currentAct = profile.optString("activity", "");
        int totalGames = profile.optInt("totalGames", 0);
        int totalPlayTime = profile.optInt("totalPlayTime", 0);
        JSONArray recentGames = profile.optJSONArray("recentGames");

        // ====== 顶部 Banner 区（Steam 风格） ======
        // 用一个深色渐变背景块作为头部 banner
        View banner = new View(activity);
        banner.setBackgroundResource(R.drawable.bg_profile_card);
        LinearLayout.LayoutParams bannerLp = new LinearLayout.LayoutParams(-1, dp(6));
        bannerLp.setMargins(0, 0, 0, dp(10));
        contentContainer.addView(banner, bannerLp);

        // ====== 头像 + 基本信息（横向排列） ======
        LinearLayout headerRow = new LinearLayout(activity);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        headerRow.setPadding(dp(4), dp(4), dp(4), dp(8));

        // 头像（带蓝色环形边框，Steam 风格）
        FrameLayout avatarBox = new FrameLayout(activity);
        avatarBox.setBackgroundResource(R.drawable.bg_profile_avatar_ring);
        LinearLayout.LayoutParams avatarLp = new LinearLayout.LayoutParams(dp(72), dp(72));
        avatarLp.setMargins(0, 0, dp(14), 0);
        avatarBox.setLayoutParams(avatarLp);

        TextView avatarText = new TextView(activity);
        avatarText.setText(nickname.isEmpty() ? "?" : nickname.substring(0, 1).toUpperCase());
        avatarText.setTextColor(0xFFF5F7FF);
        avatarText.setTextSize(26);
        avatarText.setGravity(Gravity.CENTER);
        avatarBox.addView(avatarText, new FrameLayout.LayoutParams(-1, -1));

        ImageView avatarImg = null;
        if (!avatarUrl.isEmpty()) {
            avatarImg = new ImageView(activity);
            avatarImg.setScaleType(ImageView.ScaleType.CENTER_CROP);
            avatarImg.setVisibility(View.GONE);
            avatarBox.addView(avatarImg, new FrameLayout.LayoutParams(-1, -1));
            loadAvatarInto(avatarUrl, avatarImg, avatarText);
        }
        headerRow.addView(avatarBox);

        // 右侧信息列
        LinearLayout infoCol = new LinearLayout(activity);
        infoCol.setOrientation(LinearLayout.VERTICAL);
        infoCol.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));

        // 昵称行（昵称 + 等级徽章）
        LinearLayout nameRow = new LinearLayout(activity);
        nameRow.setOrientation(LinearLayout.HORIZONTAL);
        nameRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView nameView = new TextView(activity);
        nameView.setText(nickname);
        nameView.setTextColor(0xFFF5F7FF);
        nameView.setTextSize(19);
        nameView.setTypeface(null, android.graphics.Typeface.BOLD);
        nameView.setMaxLines(1);
        nameView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        // 昵称占满剩余空间但给徽章留位（weight=1 + 徽章 wrap）
        nameRow.addView(nameView, new LinearLayout.LayoutParams(-2, -2));

        // 等级徽章：profile.php 已返回 level / exp
        int userLevel = profile.optInt("level", 1);
        if (userLevel > 0) {
            nameRow.addView(buildLevelBadge(userLevel, profile.optInt("exp", 0)));
        }
        infoCol.addView(nameRow);

        // 封禁标识
        boolean isDisabled = profile.optBoolean("isDisabled", false);
        if (isDisabled) {
            TextView banBadge = new TextView(activity);
            banBadge.setText("⛔ 已封禁");
            banBadge.setTextColor(0xFFFF6B6B);
            banBadge.setTextSize(10);
            banBadge.setTypeface(null, android.graphics.Typeface.BOLD);
            banBadge.setPadding(dp(5), dp(2), dp(5), dp(2));
            banBadge.setBackgroundResource(R.drawable.bg_input);
            LinearLayout.LayoutParams banLp = new LinearLayout.LayoutParams(-2, -2);
            banLp.setMargins(dp(8), 0, 0, 0);
            infoCol.addView(banBadge, banLp);
        }

        // 状态行（在线状态点 + 文字 + UID）
        LinearLayout statusRow = new LinearLayout(activity);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        statusRow.setPadding(0, dp(3), 0, 0);

        View statusDot = new View(activity);
        statusDot.setBackgroundResource(presenceDotRes(status));
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(8), dp(8));
        dotLp.setMargins(0, 0, dp(5), 0);
        statusRow.addView(statusDot, dotLp);

        TextView statusText = new TextView(activity);
        if (isDisabled) {
            statusText.setText("已封禁  ·  UID " + uid);
            statusText.setTextColor(0xFFFF6B6B);
        } else {
            statusText.setText(statusLabel(status) + "  ·  UID " + uid);
            statusText.setTextColor(0xFF9AA4BF);
        }
        statusText.setTextSize(12);
        statusRow.addView(statusText);
        infoCol.addView(statusRow);

        // 成为好友时间（Steam 风格）
        String friendSince = profile.optString("friendSince", "");
        if (friendSince != null && !friendSince.isEmpty()) {
            TextView sinceView = new TextView(activity);
            String sinceStr = formatFriendSince(friendSince);
            sinceView.setText("成为好友 · " + sinceStr);
            sinceView.setTextColor(0xFF6A7485);
            sinceView.setTextSize(11);
            sinceView.setPadding(0, dp(2), 0, 0);
            infoCol.addView(sinceView);
        }

        headerRow.addView(infoCol);
        contentContainer.addView(headerRow);

        // ====== 正在游戏（Steam 风格绿色条幅） ======
        if (!currentAct.isEmpty() && "online".equals(status)) {
            LinearLayout playingBar = new LinearLayout(activity);
            playingBar.setOrientation(LinearLayout.HORIZONTAL);
            playingBar.setGravity(Gravity.CENTER_VERTICAL);
            playingBar.setBackgroundResource(R.drawable.bg_profile_card);
            playingBar.setPadding(dp(12), dp(8), dp(12), dp(8));
            LinearLayout.LayoutParams playingLp = new LinearLayout.LayoutParams(-1, -2);
            playingLp.setMargins(0, dp(4), 0, dp(4));
            playingBar.setLayoutParams(playingLp);

            // 绿色圆点
            View playingDot = new View(activity);
            playingDot.setBackgroundResource(R.drawable.bg_profile_dot_online);
            playingDot.setLayoutParams(new LinearLayout.LayoutParams(dp(8), dp(8)));
            ((LinearLayout.LayoutParams) playingDot.getLayoutParams()).setMargins(0, 0, dp(8), 0);
            playingBar.addView(playingDot);

            TextView playingText = new TextView(activity);
            playingText.setText("正在游戏中");
            playingText.setTextColor(0xFF90BA3C);
            playingText.setTextSize(11);
            playingText.setTypeface(null, android.graphics.Typeface.BOLD);
            playingBar.addView(playingText);

            View sp1 = new View(activity);
            sp1.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1));
            playingBar.addView(sp1);

            TextView actText = new TextView(activity);
            actText.setText(currentAct.trim());
            actText.setTextColor(0xFFC4D49C);
            actText.setTextSize(11);
            actText.setMaxLines(1);
            actText.setEllipsize(android.text.TextUtils.TruncateAt.END);
            playingBar.addView(actText);

            contentContainer.addView(playingBar);
        }

        // ====== 用户简介（Bio）区块 —— Steam 风格 Summary ======
        if (!signature.isEmpty()) {
            contentContainer.addView(divider());
            TextView bioTitle = sectionLabel("个人简介");
            bioTitle.setPadding(0, dp(8), 0, dp(4));
            contentContainer.addView(bioTitle);

            // 简介内容卡片
            LinearLayout bioCard = new LinearLayout(activity);
            bioCard.setOrientation(LinearLayout.VERTICAL);
            bioCard.setBackgroundResource(R.drawable.bg_profile_card);
            bioCard.setPadding(dp(12), dp(10), dp(12), dp(10));
            LinearLayout.LayoutParams bioLp = new LinearLayout.LayoutParams(-1, -2);
            bioLp.setMargins(0, 0, 0, dp(4));
            bioCard.setLayoutParams(bioLp);

            TextView bioText = new TextView(activity);
            bioText.setText(signature);
            bioText.setTextColor(0xFFC0CCDB);
            bioText.setTextSize(13);
            bioText.setLineSpacing(dp(3), 1.0f);
            bioCard.addView(bioText);

            contentContainer.addView(bioCard);
        }

        // ====== 游戏统计（Bento 风格卡片） ======
        TextView statsTitle = sectionLabel("游戏统计");
        statsTitle.setPadding(0, dp(10), 0, dp(4));
        contentContainer.addView(statsTitle);

        LinearLayout statsRow = new LinearLayout(activity);
        statsRow.setOrientation(LinearLayout.HORIZONTAL);
        statsRow.setPadding(0, 0, 0, dp(4));

        // 游戏数卡片
        LinearLayout gamesCard = buildStatCard("游戏库", String.valueOf(totalGames), "款");
        LinearLayout.LayoutParams gcLp = new LinearLayout.LayoutParams(0, -2, 1);
        gcLp.setMargins(0, 0, dp(6), 0);
        statsRow.addView(gamesCard, gcLp);

        // 游玩时长卡片
        int hours = totalPlayTime / 3600;
        int mins = (totalPlayTime % 3600) / 60;
        String playTimeStr;
        if (hours > 0) {
            // 显示如 "12.5h"
            double h = totalPlayTime / 3600.0;
            playTimeStr = String.format(java.util.Locale.getDefault(), "%.1f", h);
        } else {
            playTimeStr = mins + "m";
        }
        LinearLayout playCard = buildStatCard("总时长", playTimeStr, hours > 0 ? "h" : "");
        statsRow.addView(playCard, new LinearLayout.LayoutParams(0, -2, 1));

        contentContainer.addView(statsRow);

        // ====== 最近游玩记录（Steam Recent Activity 风格） ======
        if (recentGames != null && recentGames.length() > 0) {
            contentContainer.addView(divider());
            TextView recentTitle = sectionLabel("最近游玩");
            recentTitle.setPadding(0, dp(8), 0, dp(4));
            contentContainer.addView(recentTitle);

            for (int i = 0; i < recentGames.length(); i++) {
                try {
                    JSONObject g = recentGames.getJSONObject(i);
                    String title = g.optString("title", "未命名游戏");
                    int playTime = g.optInt("playTime", 0);
                    long lastPlayedAt = g.optLong("lastPlayedAt", 0);
                    contentContainer.addView(buildRecentGameItem(title, playTime, lastPlayedAt, i));
                } catch (Throwable ignored) {}
            }
        }

        // ====== 好友操作按钮（如果查看的不是自己） ======
        int myUid = getMyUid();
        if (uid != myUid || (myUid == 0)) {
            String friendStatus = profile.optString("friendStatus", "none");
            String friendDirection = profile.optString("friendDirection", "");

            LinearLayout actionBar = new LinearLayout(activity);
            actionBar.setOrientation(LinearLayout.HORIZONTAL);
            actionBar.setGravity(Gravity.CENTER);
            actionBar.setPadding(dp(4), dp(8), dp(4), dp(4));
            LinearLayout.LayoutParams actLp = new LinearLayout.LayoutParams(-1, -2);
            actLp.setMargins(0, dp(8), 0, 0);
            actionBar.setLayoutParams(actLp);

            if ("accepted".equals(friendStatus)) {
                TextView doneLabel = new TextView(activity);
                doneLabel.setText("✓ 已是好友");
                doneLabel.setTextColor(0xFF34C759);
                doneLabel.setTextSize(13);
                doneLabel.setGravity(Gravity.CENTER);
                doneLabel.setPadding(dp(16), dp(10), dp(16), dp(10));
                doneLabel.setBackgroundResource(R.drawable.bg_input);
                actionBar.addView(doneLabel, new LinearLayout.LayoutParams(-2, -2));
            } else if ("pending".equals(friendStatus)) {
                if ("received".equals(friendDirection)) {
                    // 对方发来的请求，可以接受
                    Button acceptBtn = new Button(activity);
                    acceptBtn.setText("接受好友请求");
                    acceptBtn.setTextColor(0xFFFFFFFF);
                    acceptBtn.setTextSize(13);
                    acceptBtn.setBackgroundResource(R.drawable.bg_social_button);
                    acceptBtn.setPadding(dp(16), dp(10), dp(16), dp(10));
                    acceptBtn.setOnClickListener(v -> {
                        v.setEnabled(false);
                        ((Button) v).setText("处理中...");
                        AppExecutors.runOnIo(() -> {
                            try {
                                boolean ok = apiClient.acceptFriendRequestByUid(uid);
                                uiHandler.post(() -> {
                                    if (ok) {
                                        Toast.makeText(activity, "已添加好友", Toast.LENGTH_SHORT).show();
                                        showUserProfile(uid); // 刷新
                                    } else {
                                        Toast.makeText(activity, "操作失败", Toast.LENGTH_SHORT).show();
                                        v.setEnabled(true);
                                        ((Button) v).setText("接受好友请求");
                                    }
                                });
                            } catch (Throwable t) {
                                uiHandler.post(() -> {
                                    Toast.makeText(activity, "操作失败: " + t.getMessage(), Toast.LENGTH_SHORT).show();
                                    v.setEnabled(true);
                                    ((Button) v).setText("接受好友请求");
                                });
                            }
                        });
                    });
                    actionBar.addView(acceptBtn, new LinearLayout.LayoutParams(-2, -2));
                } else {
                    // 自己发的请求，等待中
                    TextView pendingLabel = new TextView(activity);
                    pendingLabel.setText("⏳ 等待对方确认");
                    pendingLabel.setTextColor(0xFF9AA4BF);
                    pendingLabel.setTextSize(13);
                    pendingLabel.setGravity(Gravity.CENTER);
                    pendingLabel.setPadding(dp(16), dp(10), dp(16), dp(10));
                    pendingLabel.setBackgroundResource(R.drawable.bg_input);
                    actionBar.addView(pendingLabel, new LinearLayout.LayoutParams(-2, -2));
                }
            } else {
                // 陌生人，显示添加好友按钮
                Button addBtn = new Button(activity);
                addBtn.setText("＋ 添加好友");
                addBtn.setTextColor(0xFFFFFFFF);
                addBtn.setTextSize(13);
                addBtn.setBackgroundResource(R.drawable.bg_social_button);
                addBtn.setPadding(dp(16), dp(10), dp(16), dp(10));
                addBtn.setOnClickListener(v -> {
                    v.setEnabled(false);
                    ((Button) v).setText("发送中...");
                    AppExecutors.runOnIo(() -> {
                        try {
                            boolean ok = apiClient.sendFriendRequest(String.valueOf(uid));
                            uiHandler.post(() -> {
                                if (ok) {
                                    Toast.makeText(activity, "好友请求已发送", Toast.LENGTH_SHORT).show();
                                    showUserProfile(uid); // 刷新
                                } else {
                                    Toast.makeText(activity, "发送失败", Toast.LENGTH_SHORT).show();
                                    v.setEnabled(true);
                                    ((Button) v).setText("＋ 添加好友");
                                }
                            });
                        } catch (Throwable t) {
                            uiHandler.post(() -> {
                                Toast.makeText(activity, "发送失败: " + t.getMessage(), Toast.LENGTH_SHORT).show();
                                v.setEnabled(true);
                                ((Button) v).setText("＋ 添加好友");
                            });
                        }
                    });
                });
                actionBar.addView(addBtn, new LinearLayout.LayoutParams(-2, -2));
            }
            contentContainer.addView(actionBar);
        }

        // 底部留白
        View bottomSp = new View(activity);
        bottomSp.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(20)));
        contentContainer.addView(bottomSp);
    }

    /** 构建统计卡片（Bento 风格） */
    private LinearLayout buildStatCard(String label, String value, String unit) {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        card.setBackgroundResource(R.drawable.bg_profile_card);
        card.setPadding(dp(10), dp(14), dp(10), dp(14));

        // 数字 + 单位 行
        LinearLayout valRow = new LinearLayout(activity);
        valRow.setOrientation(LinearLayout.HORIZONTAL);
        valRow.setGravity(Gravity.CENTER_VERTICAL | Gravity.BOTTOM);

        TextView valText = new TextView(activity);
        valText.setText(value);
        valText.setTextColor(0xFFF5F7FF);
        valText.setTextSize(26);
        valText.setTypeface(null, android.graphics.Typeface.BOLD);
        valRow.addView(valText);

        if (unit != null && !unit.isEmpty()) {
            TextView unitText = new TextView(activity);
            unitText.setText(unit);
            unitText.setTextColor(0xFF9AA4BF);
            unitText.setTextSize(12);
            unitText.setPadding(dp(2), 0, 0, dp(2));
            valRow.addView(unitText);
        }
        card.addView(valRow);

        // 标签
        TextView labelText = new TextView(activity);
        labelText.setText(label);
        labelText.setTextColor(0xFF8995B0);
        labelText.setTextSize(11);
        labelText.setPadding(0, dp(2), 0, 0);
        card.addView(labelText);

        return card;
    }

    /** 构建最近游玩游戏项 */
    private View buildRecentGameItem(String title, int playTimeSec, long lastPlayedAt, int index) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.bg_profile_card);
        row.setPadding(dp(10), dp(10), dp(10), dp(10));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
        rlp.setMargins(0, 0, 0, dp(6));
        row.setLayoutParams(rlp);

        // 序号圆形
        TextView numBadge = new TextView(activity);
        numBadge.setText(String.valueOf(index + 1));
        numBadge.setTextColor(0xFF8AB4FF);
        numBadge.setTextSize(11);
        numBadge.setTypeface(null, android.graphics.Typeface.BOLD);
        numBadge.setGravity(Gravity.CENTER);
        numBadge.setBackgroundResource(R.drawable.bg_profile_stat_chip);
        LinearLayout.LayoutParams numLp = new LinearLayout.LayoutParams(dp(24), dp(24));
        numLp.setMargins(0, 0, dp(10), 0);
        row.addView(numBadge, numLp);

        // 左侧色条（渐变效果用不同颜色）
        View accent = new View(activity);
        accent.setBackgroundColor(accentColorForGame(index));
        LinearLayout.LayoutParams accLp = new LinearLayout.LayoutParams(dp(3), dp(28));
        accLp.setMargins(0, 0, dp(10), 0);
        row.addView(accent, accLp);

        // 中间信息列
        LinearLayout col = new LinearLayout(activity);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));

        TextView titleView = new TextView(activity);
        titleView.setText(title);
        titleView.setTextColor(0xFFF5F7FF);
        titleView.setTextSize(14);
        titleView.setMaxLines(1);
        titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(titleView);

        LinearLayout metaRow = new LinearLayout(activity);
        metaRow.setOrientation(LinearLayout.HORIZONTAL);
        metaRow.setGravity(Gravity.CENTER_VERTICAL);

        // 游玩时长
        TextView timeView = new TextView(activity);
        timeView.setText(formatPlayTime(playTimeSec));
        timeView.setTextColor(0xFF9AA4BF);
        timeView.setTextSize(11);
        metaRow.addView(timeView);

        // 分隔点
        if (lastPlayedAt > 0) {
            View dot = new View(activity);
            dot.setBackgroundColor(0xFF5A6A8B);
            dot.setLayoutParams(new LinearLayout.LayoutParams(dp(2), dp(2)));
            ((LinearLayout.LayoutParams) dot.getLayoutParams()).setMargins(dp(6), 0, dp(6), 0);
            metaRow.addView(dot);

            // 最后游玩时间
            TextView lastView = new TextView(activity);
            lastView.setText("最后游玩 " + formatRelativeTime(lastPlayedAt));
            lastView.setTextColor(0xFF8995B0);
            lastView.setTextSize(11);
            metaRow.addView(lastView);
        }
        col.addView(metaRow);
        row.addView(col);

        // 右侧时长标签
        if (playTimeSec > 0) {
            TextView timeBadge = new TextView(activity);
            timeBadge.setText(formatPlayTimeShort(playTimeSec));
            timeBadge.setTextColor(0xFFC4D49C);
            timeBadge.setTextSize(11);
            timeBadge.setTypeface(null, android.graphics.Typeface.BOLD);
            timeBadge.setPadding(dp(8), dp(3), dp(8), dp(3));
            timeBadge.setBackgroundResource(R.drawable.bg_social_button);
            row.addView(timeBadge);
        }

        return row;
    }

    /** 格式化游玩时长（秒 -> "Xh Ym"） */
    private String formatPlayTime(int seconds) {
        if (seconds <= 0) return "未游玩";
        int h = seconds / 3600;
        int m = (seconds % 3600) / 60;
        if (h > 0) return h + " 小时" + (m > 0 ? " " + m + " 分" : "");
        return m + " 分钟";
    }

    /** 格式化游玩时长短格式（秒 -> "Xh" 或 "Xm"） */
    private String formatPlayTimeShort(int seconds) {
        if (seconds <= 0) return "";
        int h = seconds / 3600;
        int m = (seconds % 3600) / 60;
        if (h > 0) return h + "h";
        return m + "m";
    }

    /** 格式化相对时间（时间戳毫秒 -> "X天前" 等） */
    private String formatRelativeTime(long timestampMs) {
        if (timestampMs <= 0) return "";
        long now = System.currentTimeMillis();
        long diff = now - timestampMs;
        if (diff < 0) diff = 0;
        long minutes = diff / (60 * 1000);
        long hours = diff / (60 * 60 * 1000);
        long days = diff / (24 * 60 * 60 * 1000);
        if (days > 30) {
            java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault());
            return fmt.format(new java.util.Date(timestampMs));
        } else if (days >= 1) {
            return days + " 天前";
        } else if (hours >= 1) {
            return hours + " 小时前";
        } else if (minutes >= 1) {
            return minutes + " 分钟前";
        } else {
            return "刚刚";
        }
    }

    /** 将 last_heartbeat 字符串（YYYY-MM-DD HH:MM:SS）转为相对时间 */
    private String formatHeartbeatRelative(String heartbeat) {
        if (heartbeat == null || heartbeat.trim().isEmpty()) return "";
        try {
            java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault());
            java.util.Date date = fmt.parse(heartbeat);
            if (date == null) return "";
            return formatRelativeTime(date.getTime());
        } catch (Throwable t) {
            return "";
        }
    }

    /** 将好友添加时间（YYYY-MM-DD HH:MM:SS）转为友好显示 */
    private String formatFriendSince(String since) {
        if (since == null || since.trim().isEmpty()) return "";
        try {
            java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault());
            java.util.Date date = fmt.parse(since);
            if (date == null) return "";
            long now = System.currentTimeMillis();
            long diff = now - date.getTime();
            if (diff < 0) diff = 0;
            long days = diff / (24 * 60 * 60 * 1000);
            if (days > 365) {
                int years = (int)(days / 365);
                return years + " 年";
            } else if (days > 30) {
                int months = (int)(days / 30);
                return months + " 个月";
            } else if (days >= 1) {
                return (int)days + " 天";
            } else {
                return "今天";
            }
        } catch (Throwable t) {
            return "";
        }
    }

    /** 游戏列表色条颜色 */
    private int accentColorForGame(int index) {
        int[] colors = {0xFF6C8CFF, 0xFFB04AD9, 0xFF34C759, 0xFFFF9500, 0xFF64D2FF, 0xFFFF6B6B};
        return colors[index % colors.length];
    }

    private String statusLabel(String status) {
        switch (status) {
            case "online": return "🟢 在线";
            case "away":   return "🟡 离开";
            case "busy":   return "🔴 忙碌";
            default:       return "⚫ 离线";
        }
    }

    /** Bitmap 圆形裁剪（基于最小边） */
    private android.graphics.Bitmap toRoundBitmap(android.graphics.Bitmap src) {
        if (src == null) return null;
        int size = Math.min(src.getWidth(), src.getHeight());
        int x = (src.getWidth() - size) / 2;
        int y = (src.getHeight() - size) / 2;
        android.graphics.Bitmap output = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(output);
        android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        float r = size / 2f;
        android.graphics.Path path = new android.graphics.Path();
        path.addCircle(r, r, r, android.graphics.Path.Direction.CW);
        canvas.clipPath(path);
        canvas.drawBitmap(src, -x, -y, paint);
        return output;
    }

    private void loadAvatarInto(String url, ImageView imageView, TextView fallback) {
        if (url == null || url.isEmpty()) return;

        // 1. 内存缓存
        android.graphics.Bitmap cached = avatarCache.get(url);
        if (cached != null) {
            imageView.setImageBitmap(cached);
            imageView.setVisibility(View.VISIBLE);
            fallback.setVisibility(View.GONE);
            return;
        }

        // 2. 磁盘缓存
        android.graphics.Bitmap diskBmp = bitmapFromDiskCache(url);
        if (diskBmp != null) {
            android.graphics.Bitmap roundBmp = toRoundBitmap(diskBmp);
            avatarCache.put(url, roundBmp);
            activity.runOnUiThread(() -> {
                imageView.setImageBitmap(roundBmp);
                imageView.setVisibility(View.VISIBLE);
                fallback.setVisibility(View.GONE);
            });
            // 后台检查更新（条件请求，图没变不会下载）
            checkForUpdate(url, diskBmp, bmp -> {
                android.graphics.Bitmap newRound = toRoundBitmap(bmp);
                avatarCache.put(url, newRound);
                saveBitmapToDiskCache(url, bmp);
                activity.runOnUiThread(() -> {
                    imageView.setImageBitmap(newRound);
                });
            });
            return;
        }

        // 3. 网络加载
        new Thread(() -> {
            try {
                android.graphics.Bitmap bmp = downloadBitmapWithCache(url);
                if (bmp != null) {
                    android.graphics.Bitmap roundBmp = toRoundBitmap(bmp);
                    avatarCache.put(url, roundBmp);
                    activity.runOnUiThread(() -> {
                        imageView.setImageBitmap(roundBmp);
                        imageView.setVisibility(View.VISIBLE);
                        fallback.setVisibility(View.GONE);
                    });
                }
            } catch (Throwable ignored) {}
        }, "YukiHub-Avatar-Load").start();
    }

    // ==================== 磁盘缓存 & HTTP 条件请求 ====================

    /** 获取磁盘缓存目录 */
    private java.io.File getDiskCacheDir() {
        java.io.File dir = new java.io.File(appContext.getCacheDir(), "image_cache");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    /** URL → 缓存文件名 */
    private String urlToCacheKey(String url) {
        return "img_" + Math.abs(url.hashCode());
    }

    /** 从磁盘缓存读取 Bitmap */
    private android.graphics.Bitmap bitmapFromDiskCache(String url) {
        java.io.File file = new java.io.File(getDiskCacheDir(), urlToCacheKey(url));
        if (file.exists()) {
            try {
                return android.graphics.BitmapFactory.decodeFile(file.getAbsolutePath());
            } catch (Throwable ignored) {}
        }
        return null;
    }

    /** 保存 Bitmap 到磁盘缓存（WEBP 80% 质量） */
    private void saveBitmapToDiskCache(String url, android.graphics.Bitmap bmp) {
        java.io.File file = new java.io.File(getDiskCacheDir(), urlToCacheKey(url));
        try {
            java.io.FileOutputStream fos = new java.io.FileOutputStream(file);
            bmp.compress(android.graphics.Bitmap.CompressFormat.WEBP, 80, fos);
            fos.close();
        } catch (Throwable ignored) {}
    }

    /** 从磁盘缓存读取 Last-Modified 时间戳 */
    private String getLastModifiedFromDisk(String url) {
        java.io.File metaFile = new java.io.File(getDiskCacheDir(), urlToCacheKey(url) + ".meta");
        if (metaFile.exists()) {
            try {
                java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(metaFile));
                String lm = br.readLine();
                br.close();
                return lm;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    /** 保存 Last-Modified 时间戳到磁盘 */
    private void saveLastModifiedToDisk(String url, String lastModified) {
        if (lastModified == null || lastModified.isEmpty()) return;
        java.io.File metaFile = new java.io.File(getDiskCacheDir(), urlToCacheKey(url) + ".meta");
        try {
            java.io.FileWriter fw = new java.io.FileWriter(metaFile);
            fw.write(lastModified);
            fw.close();
        } catch (Throwable ignored) {}
    }

    /** 下载 Bitmap，带条件请求 + 自动缓存到磁盘 */
    private android.graphics.Bitmap downloadBitmapWithCache(String url) throws Exception {
        java.net.URL imgUrl = new java.net.URL(url);
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) imgUrl.openConnection();
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        conn.setInstanceFollowRedirects(true);

        // 带上条件请求头
        String lm = getLastModifiedFromDisk(url);
        if (lm != null) {
            conn.setRequestProperty("If-Modified-Since", lm);
        }

        int responseCode = conn.getResponseCode();

        // 304 Not Modified — 服务器文件没变，返回 null 表示无需更新
        if (responseCode == java.net.HttpURLConnection.HTTP_NOT_MODIFIED) {
            conn.disconnect();
            return null;
        }

        // 200 OK — 下载新图片
        android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeStream(conn.getInputStream());
        conn.disconnect();

        if (bmp != null) {
            // 保存到磁盘缓存
            saveBitmapToDiskCache(url, bmp);
            // 保存 Last-Modified
            String newLm = conn.getHeaderField("Last-Modified");
            saveLastModifiedToDisk(url, newLm);
        }

        return bmp;
    }

    /** 后台检查图片更新（条件请求，图没变不下载） */
    private void checkForUpdate(String url, android.graphics.Bitmap oldBmp, java.util.function.Consumer<android.graphics.Bitmap> onUpdate) {
        new Thread(() -> {
            try {
                android.graphics.Bitmap newer = downloadBitmapWithCache(url);
                // downloadBitmapWithCache 返回 null 表示 304 无需更新
                if (newer != null) {
                    onUpdate.accept(newer);
                }
            } catch (Throwable ignored) {}
        }, "YukiHub-Image-Update-Check").start();
    }

    /** 根据昵称生成一致的头像背景色 */
    private int avatarBgColor(String name) {
        if (name == null || name.isEmpty()) return 0xFF455A64;
        // 使用 hashCode 的低 15 位生成颜色，保证同一用户颜色一致
        int hash = Math.abs(name.hashCode());
        int r = 40 + (hash & 0x7F);
        int g = 60 + ((hash >> 7) & 0x7F);
        int b = 80 + ((hash >> 14) & 0x7F);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /** 创建圆形 TextView 头像占位（首字母） */
    private TextView createCircleTextAvatar(int size, String firstLetter, int bgColor) {
        TextView tv = new TextView(activity);
        tv.setText(firstLetter.isEmpty() ? "?" : firstLetter.toUpperCase());
        tv.setTextColor(0xFFF5F7FF);
        tv.setTextSize(size * 0.42f); // 字母大小适配
        tv.setGravity(Gravity.CENTER);
        tv.setTypeface(null, android.graphics.Typeface.BOLD);

        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        gd.setColor(bgColor);
        tv.setBackground(gd);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(size), dp(size));
        tv.setLayoutParams(lp);
        return tv;
    }

    private void showRequestsView() {
        currentView = VIEW_REQUESTS;
        titleBar.setText("好友请求");
        backButton.setVisibility(View.VISIBLE);
        resetContent(true);
        stopAllPolling();
        contentContainer.addView(loadingLabel("正在加载..."));
        AppExecutors.runOnIo(() -> {
            try {
                JSONObject resp = apiClient.getFriendRequests();
                incomingRequests = resp.optJSONArray("incoming");
                outgoingRequests = resp.optJSONArray("outgoing");
                uiHandler.post(() -> renderRequests());
            } catch (Throwable t) {
                uiHandler.post(() -> showError("加载失败：" + t.getMessage(), () -> showRequestsView()));
            }
        });
    }

    private void renderRequests() {
        resetContent(true);
        contentContainer.addView(sectionLabel("收到的请求"));
        if (incomingRequests != null && incomingRequests.length() > 0) {
            for (int i = 0; i < incomingRequests.length(); i++) {
                try {
                    JSONObject r = incomingRequests.getJSONObject(i);
                    int fid = r.optInt("friendshipId", 0);
                    String nickname = r.optString("nickname", "");
                    String uid = String.valueOf(r.optInt("fromUid", 0));
                    String sig = r.optString("signature", "");

                    LinearLayout row = new LinearLayout(activity);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setPadding(dp(8), dp(6), dp(8), dp(6));
                    row.setBackgroundResource(R.drawable.bg_friend_item);
                    LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
                    rlp.setMargins(0, dp(2), 0, dp(2));
                    row.setLayoutParams(rlp);

                    TextView av = new TextView(activity);
                    av.setTextSize(14); av.setTextColor(0xFFF5F7FF); av.setGravity(Gravity.CENTER);
                    av.setBackgroundResource(R.drawable.bg_input);
                    av.setText(nickname.isEmpty() ? "?" : nickname.substring(0, 1).toUpperCase());
                    LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(dp(32), dp(32));
                    al.setMargins(0, 0, dp(8), 0);
                    row.addView(av, al);

                    LinearLayout col = new LinearLayout(activity);
                    col.setOrientation(LinearLayout.VERTICAL);
                    col.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
                    TextView name = new TextView(activity);
                    name.setText(nickname + " (UID " + uid + ")");
                    name.setTextColor(0xFFF5F7FF); name.setTextSize(13);
                    col.addView(name);
                    if (!sig.isEmpty()) {
                        TextView st = new TextView(activity);
                        st.setText(sig); st.setTextColor(0xFF9AA4BF); st.setTextSize(11);
                        col.addView(st);
                    }
                    row.addView(col);

                    Button accept = new Button(activity);
                    accept.setText("接受");
                    accept.setTextColor(0xFFFFFFFF);
                    accept.setTextSize(11);
                    accept.setBackgroundResource(R.drawable.bg_social_button);
                    accept.setPadding(dp(6), dp(4), dp(6), dp(4));
                    final int fId = fid;
                    accept.setOnClickListener(v -> { accept.setEnabled(false);
                        doAcceptRequest(fId, accept, row); });
                    row.addView(accept);

                    View sp2 = new View(activity);
                    sp2.setLayoutParams(new LinearLayout.LayoutParams(dp(4), 0));
                    row.addView(sp2);

                    Button reject = new Button(activity);
                    reject.setText("拒绝");
                    reject.setTextColor(0xFF9AA4BF);
                    reject.setTextSize(11);
                    reject.setBackgroundResource(R.drawable.bg_input);
                    reject.setPadding(dp(6), dp(4), dp(6), dp(4));
                    reject.setOnClickListener(v -> { reject.setEnabled(false);
                        doRejectRequest(fId, row); });
                    row.addView(reject);

                    contentContainer.addView(row);
                } catch (Throwable ignored) {}
            }
        } else {
            contentContainer.addView(emptyLabel("暂无收到的请求"));
        }

        contentContainer.addView(divider());
        contentContainer.addView(sectionLabel("已发出的请求"));
        if (outgoingRequests != null && outgoingRequests.length() > 0) {
            for (int i = 0; i < outgoingRequests.length(); i++) {
                try {
                    JSONObject r = outgoingRequests.getJSONObject(i);
                    String nickname = r.optString("nickname", "");
                    String uid = String.valueOf(r.optInt("toUid", 0));
                    LinearLayout row = new LinearLayout(activity);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setPadding(dp(8), dp(6), dp(8), dp(6));
                    row.setBackgroundResource(R.drawable.bg_friend_item);
                    row.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));

                    TextView av = new TextView(activity);
                    av.setTextSize(14); av.setTextColor(0xFFF5F7FF); av.setGravity(Gravity.CENTER);
                    av.setBackgroundResource(R.drawable.bg_input);
                    av.setText(nickname.isEmpty() ? "?" : nickname.substring(0, 1).toUpperCase());
                    row.addView(av, new LinearLayout.LayoutParams(dp(32), dp(32)));

                    TextView name = new TextView(activity);
                    name.setText(nickname + " (UID " + uid + ")");
                    name.setTextColor(0xFF9AA4BF); name.setTextSize(13);
                    row.addView(name);
                    contentContainer.addView(row);
                } catch (Throwable ignored) {}
            }
        } else {
            contentContainer.addView(emptyLabel("暂无已发出的请求"));
        }
    }

    private void doAcceptRequest(int friendshipId, Button btn, View row) {
        AppExecutors.runOnIo(() -> {
            try {
                boolean success = apiClient.acceptFriendRequest(friendshipId);
                uiHandler.post(() -> {
                    if (success) {
                        row.setVisibility(View.GONE);
                        Toast.makeText(activity, "已接受好友请求", Toast.LENGTH_SHORT).show();
                    } else { btn.setEnabled(true); }
                });
            } catch (Throwable t) {
                uiHandler.post(() -> { btn.setEnabled(true);
                    Toast.makeText(activity, "操作失败: " + t.getMessage(), Toast.LENGTH_SHORT).show(); });
            }
        });
    }

    private void doRejectRequest(int friendshipId, View row) {
        AppExecutors.runOnIo(() -> {
            try {
                boolean success = apiClient.rejectFriendRequest(friendshipId);
                uiHandler.post(() -> { if (success) row.setVisibility(View.GONE); });
            } catch (Throwable ignored) {}
        });
    }

    // ==================== 轮询 ====================

    private void startPolling() {
        stopPolling();
        pollFuture = AppExecutors.scheduled().scheduleAtFixedRate(() -> {
            if (dialog == null || !dialog.isShowing()) { stopPolling(); return; }
            try {
                int unread = apiClient.getTotalUnread();
                uiHandler.post(() -> {
                    if (unread > 0) titleBar.setText("好友 / 聊天 (" + unread + ")");
                    else titleBar.setText("好友 / 聊天");
                });
            } catch (Throwable ignored) {}
        }, POLL_INTERVAL_MS, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private void stopPolling() {
        if (pollFuture != null) { pollFuture.cancel(false); pollFuture = null; }
    }

    private void stopAllPolling() {
        stopPolling();
        stopChatPolling();
        stopGroupPolling();
    }

    // ==================== 工具方法 ====================

    private boolean isLoggedIn() {
        SharedPreferences p = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String t = p.getString(KEY_AUTH_ACCESS_TOKEN, "");
        return t != null && !t.trim().isEmpty();
    }

    /** 获取当前登录用户的昵称 */
    private String getMyNickname() {
        SharedPreferences p = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return p.getString(KEY_AUTH_NICKNAME, "");
    }

    /** 获取当前登录用户的头像 URL */
    private String getMyAvatar() {
        SharedPreferences p = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return p.getString(KEY_AUTH_AVATAR, "");
    }

    /** 获取当前登录用户的 UID */
    private int getMyUid() {
        SharedPreferences p = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        try {
            return Integer.parseInt(p.getString(KEY_AUTH_UID, "0"));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private int dp(int value) {
        return (int)(value * activity.getResources().getDisplayMetrics().density + 0.5f);
    }

    /** 统一处理网络异常：如果是账号被禁用，关闭聊天弹窗 */
    private void handleApiError(Throwable t) {
        if (t instanceof SocialApiClient.AccountDisabledException) {
            uiHandler.post(() -> {
                Toast.makeText(activity, "您的账号已被管理员禁用", Toast.LENGTH_LONG).show();
                try { if (dialog != null && dialog.isShowing()) dialog.dismiss(); } catch (Throwable ignored) {}
                try { if (optionDialog != null && optionDialog.isShowing()) optionDialog.dismiss(); } catch (Throwable ignored) {}
            });
        }
    }

    private void scrollToBottom() {
        scrollToBottomReliably(chatScrollView, chatMessageList);
    }

    /**
     * 可靠地滚到列表底部。
     *
     * 原来的写法是 list.post(() -> parent.scrollTo(0, list.getHeight()))，
     * post 只等一帧，而首屏可能一次性塞进 200 条气泡（还有异步加载的图片），
     * 一帧内布局没测量完，getHeight() 偏小，结果就停在聊天记录中间。
     *
     * 这里改成三重保障：
     *   1. fullScroll(FOCUS_DOWN) —— 由 ScrollView 自己算底部，不依赖外部取高度
     *   2. 连续几帧重试 —— 覆盖气泡陆续测量完的过程
     *   3. OnLayoutChangeListener —— 图片等异步内容撑高后再兜一次
     */
    private void scrollToBottomReliably(final ScrollView sv, final LinearLayout list) {
        if (sv == null || list == null) return;

        // 每次调用只保留最新一个兜底监听，避免重复叠加
        final View.OnLayoutChangeListener[] holder = new View.OnLayoutChangeListener[1];
        holder[0] = (v, l, t, r, b, ol, ot, or_, ob) -> {
            if (b != ob) sv.fullScroll(View.FOCUS_DOWN);
        };
        list.addOnLayoutChangeListener(holder[0]);
        // 内容高度稳定后撤掉监听，防止干扰用户手动上翻
        sv.postDelayed(() -> {
            try { list.removeOnLayoutChangeListener(holder[0]); } catch (Throwable ignored) {}
        }, 1200);

        // 连续几帧重试：气泡是逐帧测量完的，单次 post 往往还没到位
        sv.post(() -> sv.fullScroll(View.FOCUS_DOWN));
        sv.postDelayed(() -> sv.fullScroll(View.FOCUS_DOWN), 60);
        sv.postDelayed(() -> sv.fullScroll(View.FOCUS_DOWN), 200);
        sv.postDelayed(() -> sv.fullScroll(View.FOCUS_DOWN), 450);
    }

    private View divider() {
        View v = new View(activity);
        v.setBackgroundColor(0xFF2D3658);
        v.setLayoutParams(new LinearLayout.LayoutParams(-1, 1));
        v.setPadding(0, dp(4), 0, dp(4));
        return v;
    }

    private TextView sectionLabel(String text) {
        TextView tv = new TextView(activity);
        tv.setText(text);
        tv.setTextColor(0xFF9AA4BF);
        tv.setTextSize(12);
        tv.setPadding(0, dp(6), 0, dp(3));
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        return tv;
    }

    private TextView loadingLabel(String text) {
        TextView tv = new TextView(activity);
        tv.setText(text);
        tv.setTextColor(0xFF9AA4BF);
        tv.setTextSize(13);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(0, dp(20), 0, 0);
        return tv;
    }

    private TextView emptyLabel(String text) {
        TextView tv = new TextView(activity);
        tv.setText(text);
        tv.setTextColor(0xFF9AA4BF);
        tv.setTextSize(13);
        tv.setGravity(Gravity.CENTER);
        tv.setLineSpacing(dp(4), 1.0f);
        tv.setPadding(0, dp(30), 0, 0);
        return tv;
    }

    private TextView errorLabel(String text) {
        TextView tv = new TextView(activity);
        tv.setText(text);
        tv.setTextColor(0xFFFF6B6B);
        tv.setTextSize(13);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(0, dp(20), 0, 0);
        return tv;
    }

    private Button retryButton(Runnable action) {
        Button btn = new Button(activity);
        btn.setText("重试");
        btn.setTextColor(0xFF8AB4FF);
        btn.setBackgroundResource(R.drawable.bg_input);
        btn.setOnClickListener(v -> action.run());
        return btn;
    }

    private Button socialButton(String text, View.OnClickListener listener) {
        Button btn = new Button(activity);
        btn.setText(text);
        btn.setTextColor(0xFFFFFFFF);
        btn.setTextSize(12);
        btn.setBackgroundResource(R.drawable.bg_social_button);
        btn.setPadding(dp(6), 0, dp(6), 0);
        btn.setOnClickListener(listener);
        return btn;
    }

    /** 菜单按钮（带深色背景，用于选项弹窗） */
    private Button menuButton(String text, View.OnClickListener listener) {
        Button btn = new Button(activity);
        btn.setText(text);
        btn.setTextColor(0xFFF5F7FF);
        btn.setTextSize(13);
        btn.setBackgroundResource(R.drawable.bg_input);
        btn.setPadding(dp(10), dp(8), dp(10), dp(8));
        btn.setOnClickListener(listener);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, 0, 0, dp(4));
        btn.setLayoutParams(lp);
        return btn;
    }

    /**
     * 显示选项弹窗。
     * 内容区包 ScrollView：横屏或选项较多时（如举报理由 7 项）不会被裁切且能滑动。
     * 弹窗最大高度限制为屏幕 80%，避免顶到状态栏。
     */
    private void showOptionDialog(String title, View content) {
        dismissOptionDialog();
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.bg_social_panel);
        root.setPadding(dp(10), dp(8), dp(10), dp(8));
        root.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));

        TextView titleView = new TextView(activity);
        titleView.setText(title);
        titleView.setTextColor(0xFFF5F7FF);
        titleView.setTextSize(15);
        titleView.setTypeface(null, android.graphics.Typeface.BOLD);
        titleView.setPadding(0, 0, 0, dp(6));
        root.addView(titleView);

        // 内容包一层 ScrollView，超出可滑动
        ScrollView contentScroll = new ScrollView(activity);
        contentScroll.setFillViewport(false);
        contentScroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        contentScroll.addView(content, new FrameLayout.LayoutParams(-1, -2));
        root.addView(contentScroll, new LinearLayout.LayoutParams(-1, -2));

        optionDialog = new Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        optionDialog.setContentView(root);
        optionDialog.setCancelable(true);
        if (optionDialog.getWindow() != null) {
            optionDialog.getWindow().setBackgroundDrawableResource(R.drawable.bg_social_panel);
            int screenW = activity.getResources().getDisplayMetrics().widthPixels;
            int screenH = activity.getResources().getDisplayMetrics().heightPixels;
            // 横屏时 0.65 宽度太窄，按屏幕比例自适应
            float widthRatio = screenW > screenH ? 0.5f : 0.75f;
            optionDialog.getWindow().setLayout((int) (screenW * widthRatio), -2);
        }
        optionDialog.show();

        // 限制最大高度为屏幕 80%（show 之后才能拿到测量结果）
        root.post(() -> {
            int maxH = (int) (activity.getResources().getDisplayMetrics().heightPixels * 0.8f);
            if (root.getHeight() > maxH && optionDialog != null && optionDialog.getWindow() != null) {
                optionDialog.getWindow().setLayout(
                        optionDialog.getWindow().getAttributes().width, maxH);
            }
        });
    }

    /** 关闭选项弹窗 */
    private void dismissOptionDialog() {
        if (optionDialog != null && optionDialog.isShowing()) {
            try { optionDialog.dismiss(); } catch (Throwable ignored) {}
            optionDialog = null;
        }
    }

    private void showError(String msg, Runnable retry) {
        resetContent(true);
        contentContainer.addView(errorLabel(msg));
        contentContainer.addView(retryButton(retry));
    }

    private int presenceDotRes(String status) {
        switch (status) {
            case "online":  return R.drawable.bg_profile_dot_online;
            case "away":    return R.drawable.bg_presence_away;
            case "busy":    return R.drawable.bg_presence_busy;
            default:        return R.drawable.bg_presence_offline;
        }
    }

    private String formatTime(String isoDate) {
        if (isoDate == null || isoDate.isEmpty()) return "";
        try {
            java.text.SimpleDateFormat isoFmt = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault());
            java.util.Date date = isoFmt.parse(isoDate);
            if (date == null) return "";
            java.util.Calendar now = java.util.Calendar.getInstance();
            java.util.Calendar msg = java.util.Calendar.getInstance();
            msg.setTime(date);

            java.text.SimpleDateFormat timeFmt = new java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault());
            String timeStr = timeFmt.format(date);

            if (now.get(java.util.Calendar.YEAR) == msg.get(java.util.Calendar.YEAR)
                    && now.get(java.util.Calendar.DAY_OF_YEAR) == msg.get(java.util.Calendar.DAY_OF_YEAR)) {
                return "今天 " + timeStr;
            }

            now.add(java.util.Calendar.DAY_OF_YEAR, -1);
            if (now.get(java.util.Calendar.YEAR) == msg.get(java.util.Calendar.YEAR)
                    && now.get(java.util.Calendar.DAY_OF_YEAR) == msg.get(java.util.Calendar.DAY_OF_YEAR)) {
                return "昨天 " + timeStr;
            }

            java.text.SimpleDateFormat dateFmt = new java.text.SimpleDateFormat("MM/dd HH:mm", java.util.Locale.getDefault());
            return dateFmt.format(date);
        } catch (Throwable t) { return ""; }
    }

    // ==================== 表情包 ====================

    /** 从 URL 加载表情 Bitmap 到 ImageView（异步，带缓存） */
    private void loadEmojiInto(String url, ImageView imageView, int size) {
        if (url == null || url.isEmpty()) return;

        // 1. 内存缓存
        android.graphics.Bitmap cached = emojiCache.get(url);
        if (cached != null) {
            imageView.setImageBitmap(cached);
            return;
        }

        // 2. 磁盘缓存
        android.graphics.Bitmap diskBmp = bitmapFromDiskCache(url);
        if (diskBmp != null) {
            float ratio = (float) size / Math.max(diskBmp.getWidth(), diskBmp.getHeight());
            int w = Math.round(diskBmp.getWidth() * ratio);
            int h = Math.round(diskBmp.getHeight() * ratio);
            android.graphics.Bitmap scaled = android.graphics.Bitmap.createScaledBitmap(diskBmp, w, h, true);
            emojiCache.put(url, scaled);
            activity.runOnUiThread(() -> imageView.setImageBitmap(scaled));
            // 后台检查更新
            checkForUpdate(url, diskBmp, bmp -> {
                float r2 = (float) size / Math.max(bmp.getWidth(), bmp.getHeight());
                int w2 = Math.round(bmp.getWidth() * r2);
                int h2 = Math.round(bmp.getHeight() * r2);
                android.graphics.Bitmap scaled2 = android.graphics.Bitmap.createScaledBitmap(bmp, w2, h2, true);
                emojiCache.put(url, scaled2);
                saveBitmapToDiskCache(url, bmp);
                activity.runOnUiThread(() -> imageView.setImageBitmap(scaled2));
            });
            return;
        }

        // 3. 网络加载
        new Thread(() -> {
            try {
                android.graphics.Bitmap bmp = downloadBitmapWithCache(url);
                if (bmp != null) {
                    float ratio = (float) size / Math.max(bmp.getWidth(), bmp.getHeight());
                    int w = Math.round(bmp.getWidth() * ratio);
                    int h = Math.round(bmp.getHeight() * ratio);
                    android.graphics.Bitmap scaled = android.graphics.Bitmap.createScaledBitmap(bmp, w, h, true);
                    emojiCache.put(url, scaled);
                    activity.runOnUiThread(() -> imageView.setImageBitmap(scaled));
                }
            } catch (Throwable ignored) {}
        }, "YukiHub-Emoji-Load").start();
    }

    /** 构建表情图片 View（服务端 URL 加载） */
    private View buildEmojiContentView(String emojiName) {
        ImageView emojiView = new ImageView(activity);
        int emojiSize = dp(96);
        emojiView.setLayoutParams(new LinearLayout.LayoutParams(emojiSize, emojiSize));
        // 尝试从 URL 映射获取，否则构造默认 URL
        String url = emojiUrlMap != null ? emojiUrlMap.get(emojiName) : null;
        if (url == null) {
            url = "https://yukihub.zh.kg/uploads/emojis/" + emojiName + ".webp";
        }
        loadEmojiInto(url, emojiView, emojiSize);
        return emojiView;
    }

    /** 表情包选择弹窗（从服务器拉取列表） */
    private void showEmojiPicker(boolean isGroupChat) {
        // 先显示加载中
        Toast.makeText(activity, "加载表情包中...", Toast.LENGTH_SHORT).show();

        // 后台拉取
        AppExecutors.runOnIo(() -> {
            try {
                java.util.List<SocialApiClient.EmojiInfo> serverEmojis = apiClient.getEmojiList();
                if (serverEmojis == null || serverEmojis.isEmpty()) {
                    uiHandler.post(() -> Toast.makeText(activity, "暂无表情包", Toast.LENGTH_SHORT).show());
                    return;
                }

                // 构建 name→URL 映射
                final java.util.Map<String, String> map = new java.util.HashMap<>();
                final java.util.List<String> names = new java.util.ArrayList<>();
                for (SocialApiClient.EmojiInfo e : serverEmojis) {
                    map.put(e.name, e.url);
                    names.add(e.name);
                }
                // 保存到全局映射（供气泡加载用）
                emojiUrlMap = map;

                // 回到 UI 线程构建弹窗
                activity.runOnUiThread(() -> {
                    LinearLayout rootBox = new LinearLayout(activity);
                    rootBox.setOrientation(LinearLayout.VERTICAL);
                    rootBox.setBackgroundResource(R.drawable.bg_social_panel);
                    rootBox.setPadding(dp(10), dp(8), dp(10), dp(10));

                    // 标题行
                    TextView titleBar = new TextView(activity);
                    titleBar.setText("表情包  (点空白处关闭)");
                    titleBar.setTextColor(0xFF8AB4FF);
                    titleBar.setTextSize(13);
                    titleBar.setPadding(0, 0, 0, dp(6));
                    rootBox.addView(titleBar);

                    // 表情网格
                    int cols = 5;
                    android.widget.GridView grid = new android.widget.GridView(activity);
                    grid.setNumColumns(cols);
                    grid.setStretchMode(android.widget.GridView.STRETCH_COLUMN_WIDTH);
                    grid.setHorizontalSpacing(dp(4));
                    grid.setVerticalSpacing(dp(4));
                    grid.setPadding(0, 0, 0, 0);
                    grid.setBackgroundColor(0xFF101522);

                    android.widget.BaseAdapter adapter = new android.widget.BaseAdapter() {
                        @Override public int getCount() { return names.size(); }
                        @Override public Object getItem(int position) { return names.get(position); }
                        @Override public long getItemId(int position) { return position; }
                        @Override public View getView(int position, View convertView, ViewGroup parent) {
                            String name = names.get(position);
                            ImageView iv = (convertView instanceof ImageView) ? (ImageView) convertView : new ImageView(activity);
                            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                            iv.setLayoutParams(new android.widget.AbsListView.LayoutParams(dp(56), dp(56)));
                            String url = map.get(name);
                            if (url != null) loadEmojiInto(url, iv, dp(48));
                            return iv;
                        }
                    };
                    grid.setAdapter(adapter);

                    grid.setOnItemClickListener((parent, view, position, id) -> {
                        String emojiName = names.get(position);
                        if (emojiDialog != null) emojiDialog.dismiss();
                        if (isGroupChat) sendEmojiGroupMessage(emojiName);
                        else sendEmojiMessage(emojiName);
                    });

                    rootBox.addView(grid, new LinearLayout.LayoutParams(-1, dp(280)));

                    // 关闭按钮
                    TextView closeBtn = new TextView(activity);
                    closeBtn.setText("关闭");
                    closeBtn.setTextColor(0xFFF5F7FF);
                    closeBtn.setTextSize(13);
                    closeBtn.setGravity(Gravity.CENTER);
                    closeBtn.setBackgroundResource(R.drawable.bg_input);
                    closeBtn.setPadding(dp(8), dp(8), dp(8), dp(8));
                    LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
                    clp.topMargin = dp(8);
                    rootBox.addView(closeBtn, clp);

                    emojiDialog = new Dialog(activity);
                    emojiDialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
                    emojiDialog.setContentView(rootBox);
                    emojiDialog.setCancelable(true);
                    closeBtn.setOnClickListener(v -> emojiDialog.dismiss());
                    if (emojiDialog.getWindow() != null) {
                        emojiDialog.getWindow().setLayout(
                                (int)(activity.getResources().getDisplayMetrics().widthPixels * 0.92f),
                                ViewGroup.LayoutParams.WRAP_CONTENT);
                        emojiDialog.getWindow().setGravity(Gravity.BOTTOM);
                        emojiDialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
                    }
                    emojiDialog.show();
                });
            } catch (Throwable t) {
                uiHandler.post(() -> Toast.makeText(activity, "表情包加载失败: " + t.getMessage(), Toast.LENGTH_SHORT).show());
            }
        });
    }

    /** 发送表情消息（好友私聊） */
    private void sendEmojiMessage(String emojiName) {
        if (chatFriend == null) return;
        final String content = emojiName;

        // 乐观 UI
        ChatMessage local = new ChatMessage();
        local.content = content;
        local.msgType = "emoji";
        local.isMine = true;
        final View bubbleView = buildMessageBubble(local);
        chatMessageList.addView(bubbleView);
        scrollToBottom();

        AppExecutors.runOnIo(() -> {
            try {
                ChatMessage sent = apiClient.sendMessage(chatFriend.id, content, "emoji");
                // 表情包发送成功写入本地缓存
                chatCache.upsertFriendMessages(chatFriend.id, java.util.Collections.singletonList(sent));
                chatCache.pruneFriendMessages(chatFriend.id);
                uiHandler.post(() -> {
                    if (sent.id > maxMessageId) maxMessageId = sent.id;
                });
            } catch (Throwable t) {
                uiHandler.post(() -> Toast.makeText(activity, "发送失败: " + t.getMessage(), Toast.LENGTH_SHORT).show());
                handleApiError(t);
            }
        });
    }

    /** 发送表情消息（群聊） */
    private void sendEmojiGroupMessage(String emojiName) {
        if (chatGroup == null) return;
        final String content = emojiName;

        // 乐观 UI
        GroupMessage local = new GroupMessage();
        local.content = content;
        local.msgType = "emoji";
        local.isMine = true;
        local.senderNickname = getMyNickname();
        local.senderAvatar = getMyAvatar();
        local.senderUid = getMyUid();
        local.senderIsAdmin = chatGroup != null && chatGroup.isAdmin();
        local.createdAt = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(new java.util.Date());
        final View bubbleView = buildGroupMessageBubble(local);
        groupMessageList.addView(bubbleView);
        scrollGroupToBottom();

        AppExecutors.runOnIo(() -> {
            try {
                GroupMessage sent = apiClient.sendGroupMessage(chatGroup.id, content, "emoji");
                // 表情包发送成功写入本地缓存
                chatCache.upsertGroupMessages(chatGroup.id, java.util.Collections.singletonList(sent));
                chatCache.pruneGroupMessages(chatGroup.id);
                uiHandler.post(() -> {
                    if (sent.id > groupMaxMessageId) groupMaxMessageId = sent.id;
                });
            } catch (Throwable t) {
                uiHandler.post(() -> Toast.makeText(activity, "发送失败: " + t.getMessage(), Toast.LENGTH_SHORT).show());
                handleApiError(t);
            }
        });
    }

    // ==================== 悬浮按钮 / 未读定位 ====================

    /** "回到底部"圆形按钮（默认隐藏，离底部较远时淡入） */
    private View buildJumpBottomButton(View.OnClickListener listener) {
        TextView btn = new TextView(activity);
        btn.setText("↓");
        btn.setTextColor(0xFFF5F7FF);
        btn.setTextSize(17);
        btn.setGravity(Gravity.CENTER);
        btn.setBackgroundResource(R.drawable.bg_social_button);
        btn.setAlpha(0f);
        btn.setVisibility(View.GONE);
        btn.setOnClickListener(listener);
        return btn;
    }

    /** "N 条新消息 ↑"胶囊按钮：点击跳到未读起点 */
    private View buildUnreadJumpButton(View.OnClickListener listener) {
        TextView btn = new TextView(activity);
        btn.setTextColor(0xFFFFFFFF);
        btn.setTextSize(11);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(dp(10), dp(4), dp(10), dp(4));
        btn.setBackgroundResource(R.drawable.bg_social_button);
        btn.setVisibility(View.GONE);
        btn.setOnClickListener(listener);
        return btn;
    }

    /** 回复引用条（输入栏上方，显示"回复 xxx：内容" + 关闭按钮） */
    private View buildReplyBar(View.OnClickListener onClose) {
        LinearLayout bar = new LinearLayout(activity);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundResource(R.drawable.bg_input);
        bar.setPadding(dp(8), dp(5), dp(6), dp(5));
        bar.setVisibility(View.GONE);

        TextView text = new TextView(activity);
        text.setTextColor(0xFF9AA4BF);
        text.setTextSize(11);
        text.setMaxLines(2);
        text.setEllipsize(android.text.TextUtils.TruncateAt.END);
        text.setTag("reply_bar_text");
        bar.addView(text, new LinearLayout.LayoutParams(0, -2, 1));

        TextView close = new TextView(activity);
        close.setText("✕");
        close.setTextColor(0xFF8AB4FF);
        close.setTextSize(13);
        close.setPadding(dp(8), dp(2), dp(4), dp(2));
        close.setOnClickListener(onClose);
        bar.addView(close);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(dp(2), dp(2), dp(2), 0);
        bar.setLayoutParams(lp);
        return bar;
    }

    /** 距底部超过一屏的 1/3 就显示"回到底部" */
    private void updateChatFloatingButtons() {
        if (chatScrollView == null || chatMessageList == null || chatJumpBottomBtn == null) return;
        int contentH = chatMessageList.getHeight();
        int viewH = chatScrollView.getHeight();
        int scrollY = chatScrollView.getScrollY();
        int distanceToBottom = contentH - viewH - scrollY;
        boolean show = distanceToBottom > Math.max(dp(120), viewH / 3);
        setFloatingVisible(chatJumpBottomBtn, show);
        // 用户往上翻过 → 认定为真实交互，此后允许推进已读锚点
        if (show) chatReadGateOpen = true;
        if (distanceToBottom <= dp(24)) markChatRead();
    }

    private void updateGroupFloatingButtons() {
        if (groupScrollView == null || groupMessageList == null || groupJumpBottomBtn == null) return;
        int contentH = groupMessageList.getHeight();
        int viewH = groupScrollView.getHeight();
        int scrollY = groupScrollView.getScrollY();
        int distanceToBottom = contentH - viewH - scrollY;
        boolean show = distanceToBottom > Math.max(dp(120), viewH / 3);
        setFloatingVisible(groupJumpBottomBtn, show);
        if (show) groupReadGateOpen = true;
        if (distanceToBottom <= dp(24)) markGroupRead();
    }

    private void setFloatingVisible(View v, boolean show) {
        if (v == null) return;
        boolean visible = v.getVisibility() == View.VISIBLE && v.getAlpha() > 0.5f;
        if (show == visible) return;
        if (show) {
            v.setVisibility(View.VISIBLE);
            v.animate().alpha(1f).setDuration(150).start();
        } else {
            v.animate().alpha(0f).setDuration(150)
                    .withEndAction(() -> v.setVisibility(View.GONE)).start();
        }
    }

    /**
     * 计算私聊未读条数并决定是否显示"跳到未读"胶囊。
     * 依据是进入会话前落盘的已读锚点，与 QQ 的"N 条新消息"体验一致。
     */
    private void refreshChatUnreadState() {
        if (chatUnreadJumpBtn == null || chatMessageList == null) return;
        int unread = countUnreadInList(chatMessageList, chatUnreadAnchorId);
        if (unread <= 0) {
            chatUnreadJumpBtn.setVisibility(View.GONE);
            return;
        }
        ((TextView) chatUnreadJumpBtn).setText(unread + " 条新消息 ↑");
        chatUnreadJumpBtn.setVisibility(View.VISIBLE);
    }

    private void refreshGroupUnreadState() {
        if (groupUnreadJumpBtn == null || groupMessageList == null) return;
        int unread = countUnreadInList(groupMessageList, groupUnreadAnchorId);
        if (unread <= 0) {
            groupUnreadJumpBtn.setVisibility(View.GONE);
            return;
        }
        ((TextView) groupUnreadJumpBtn).setText(unread + " 条新消息 ↑");
        groupUnreadJumpBtn.setVisibility(View.VISIBLE);
    }

    /**
     * 数一下列表里 id > anchorId 的「别人发的」消息条数。
     * 气泡在 build 时用 setTag(R.id...) 不方便，这里用另一套办法：
     * 渲染时把消息 id 写进 wrapper 的 tag（"msg:<id>:<mine>"），这里解析。
     */
    private int countUnreadInList(LinearLayout list, int anchorId) {
        if (anchorId <= 0) return 0;
        int count = 0;
        for (int i = 0; i < list.getChildCount(); i++) {
            long[] meta = parseBubbleMeta(list.getChildAt(i));
            if (meta == null) continue;
            if (meta[0] > anchorId && meta[1] == 0) count++;
        }
        return count;
    }

    /** @return [messageId, isMine ? 1 : 0]，非消息气泡返回 null */
    private long[] parseBubbleMeta(View v) {
        if (v == null) return null;
        Object tag = v.getTag();
        if (!(tag instanceof String)) return null;
        String t = (String) tag;
        if (!t.startsWith("msg:")) return null;
        try {
            String[] parts = t.split(":");
            return new long[]{Long.parseLong(parts[1]), Long.parseLong(parts[2])};
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 滚到第一条未读消息处（略微上移，让未读分界看得见） */
    private void jumpToChatUnread() {
        if (chatScrollView == null || chatMessageList == null) return;
        View target = findFirstUnreadView(chatMessageList, chatUnreadAnchorId);
        if (target == null) {
            scrollToBottom();
        } else {
            final int y = Math.max(0, target.getTop() - dp(40));
            chatScrollView.post(() -> chatScrollView.smoothScrollTo(0, y));
        }
        chatReadGateOpen = true;   // 已看过未读起点，之后可推进锚点
        if (chatUnreadJumpBtn != null) chatUnreadJumpBtn.setVisibility(View.GONE);
    }

    private void jumpToGroupUnread() {
        if (groupScrollView == null || groupMessageList == null) return;
        View target = findFirstUnreadView(groupMessageList, groupUnreadAnchorId);
        if (target == null) {
            scrollGroupToBottom();
        } else {
            final int y = Math.max(0, target.getTop() - dp(40));
            groupScrollView.post(() -> groupScrollView.smoothScrollTo(0, y));
        }
        groupReadGateOpen = true;
        if (groupUnreadJumpBtn != null) groupUnreadJumpBtn.setVisibility(View.GONE);
    }

    private View findFirstUnreadView(LinearLayout list, int anchorId) {
        if (anchorId <= 0) return null;
        for (int i = 0; i < list.getChildCount(); i++) {
            View child = list.getChildAt(i);
            long[] meta = parseBubbleMeta(child);
            if (meta == null) continue;
            if (meta[0] > anchorId && meta[1] == 0) return child;
        }
        return null;
    }

    /**
     * 落盘私聊已读锚点（推进到当前已知最新消息）。
     * 闸门未开时直接返回——避免首屏自动滚动把未读锚点冲掉。
     */
    private void markChatRead() {
        if (!chatReadGateOpen) return;
        if (chatFriend == null || maxMessageId <= 0) return;
        final String fid = chatFriend.id;
        final int mid = maxMessageId;
        AppExecutors.runOnIo(() -> chatCache.setFriendLastReadId(fid, mid));
    }

    private void markGroupRead() {
        if (!groupReadGateOpen) return;
        if (chatGroup == null || groupMaxMessageId <= 0) return;
        final int gid = chatGroup.id;
        final int mid = groupMaxMessageId;
        AppExecutors.runOnIo(() -> chatCache.setGroupLastReadId(gid, mid));
    }

    /**
     * 离开会话时强制落盘已读锚点（绕过闸门）。
     * 用户可能只是打开看一眼就退出，这时也应该算已读，否则下次进来还提示未读。
     */
    private void forceMarkChatRead() {
        if (chatFriend != null && maxMessageId > 0) {
            final String fid = chatFriend.id;
            final int mid = maxMessageId;
            AppExecutors.runOnIo(() -> chatCache.setFriendLastReadId(fid, mid));
        }
        if (chatGroup != null && groupMaxMessageId > 0) {
            final int gid = chatGroup.id;
            final int mid = groupMaxMessageId;
            AppExecutors.runOnIo(() -> chatCache.setGroupLastReadId(gid, mid));
        }
    }

    // ==================== 复制 / 回复 / 举报 ====================

    private void copyToClipboard(String text) {
        try {
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    activity.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null) return;
            cm.setPrimaryClip(android.content.ClipData.newPlainText("YukiHub", text == null ? "" : text));
            Toast.makeText(activity, "已复制", Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Toast.makeText(activity, "复制失败", Toast.LENGTH_SHORT).show();
        }
    }

    /** 引用前缀：`> 昵称: 摘要\n\n`，服务端 parseQuotedReply 能解析这个格式 */
    private String buildQuotePrefix(String who, String preview) {
        String w = (who == null || who.trim().isEmpty()) ? "对方" : who.trim();
        // 昵称里的冒号会干扰服务端解析，替换掉
        w = w.replace(":", " ").replace("：", " ");
        String p = preview == null ? "" : preview.replace("\n", " ");
        if (p.length() > 40) p = p.substring(0, 40) + "…";
        return "> " + w + ": " + p + "\n\n";
    }

    private String previewOfChat(ChatMessage m) {
        if (m == null) return "";
        if ("image".equals(m.msgType)) return "[图片]";
        if ("emoji".equals(m.msgType)) return "[表情]";
        return stripQuote(m.content);
    }

    private String previewOfGroup(GroupMessage m) {
        if (m == null) return "";
        if ("image".equals(m.msgType)) return "[图片]";
        if ("emoji".equals(m.msgType)) return "[表情]";
        return stripQuote(m.content);
    }

    /** 回复套娃时只取正文，避免引用无限叠加 */
    private String stripQuote(String content) {
        if (content == null) return "";
        String c = content.trim();
        if (!c.startsWith(">")) return c;
        int nl = c.indexOf('\n');
        if (nl < 0) return c;
        String rest = c.substring(nl).trim();
        return rest.isEmpty() ? c : rest;
    }

    private String getMyNicknameOrDefault() {
        String n = getMyNickname();
        return (n == null || n.trim().isEmpty()) ? "我" : n.trim();
    }

    private void startReplyToChat(ChatMessage msg) {
        pendingReplyChat = msg;
        pendingReplyGroup = null;
        String who = msg.isMine ? getMyNicknameOrDefault()
                : (chatFriend == null ? "对方" : chatFriend.nickname);
        showReplyBar(chatReplyBar, who, previewOfChat(msg));
        if (chatInput != null) chatInput.requestFocus();
    }

    private void startReplyToGroup(GroupMessage msg) {
        pendingReplyGroup = msg;
        pendingReplyChat = null;
        String who = (msg.senderNickname == null || msg.senderNickname.isEmpty())
                ? "对方" : msg.senderNickname;
        showReplyBar(groupReplyBar, who, previewOfGroup(msg));
        if (groupChatInput != null) groupChatInput.requestFocus();
    }

    private void showReplyBar(View bar, String who, String preview) {
        if (bar == null) return;
        View tv = bar.findViewWithTag("reply_bar_text");
        if (tv instanceof TextView) {
            String p = preview == null ? "" : preview.replace("\n", " ");
            if (p.length() > 50) p = p.substring(0, 50) + "…";
            ((TextView) tv).setText("回复 " + who + "：" + p);
        }
        bar.setVisibility(View.VISIBLE);
    }

    private void clearPendingReply() {
        pendingReplyChat = null;
        pendingReplyGroup = null;
        if (chatReplyBar != null) chatReplyBar.setVisibility(View.GONE);
        if (groupReplyBar != null) groupReplyBar.setVisibility(View.GONE);
    }

    /** 举报理由选择弹窗 */
    private void showReportReasonDialog(String scene, int messageId, int groupId) {
        final String[] reasons = {"垃圾广告", "色情低俗", "人身攻击", "违法违规", "诈骗欺诈"};
        LinearLayout menuRoot = new LinearLayout(activity);
        menuRoot.setOrientation(LinearLayout.VERTICAL);
        menuRoot.setPadding(dp(4), dp(4), dp(4), dp(4));

        TextView hint = new TextView(activity);
        hint.setText("请选择举报理由，管理员会人工审核");
        hint.setTextColor(0xFF9AA4BF);
        hint.setTextSize(11);
        hint.setPadding(dp(2), 0, dp(2), dp(6));
        menuRoot.addView(hint);

        for (String reason : reasons) {
            menuRoot.addView(menuButton(reason, v -> {
                dismissOptionDialog();
                doReport(scene, messageId, groupId, reason);
            }));
        }

        // 自定义理由：输入框 + 提交按钮
        menuRoot.addView(divider());

        TextView customLabel = new TextView(activity);
        customLabel.setText("其他理由（自行填写）");
        customLabel.setTextColor(0xFF9AA4BF);
        customLabel.setTextSize(11);
        customLabel.setPadding(dp(2), dp(4), dp(2), dp(4));
        menuRoot.addView(customLabel);

        final EditText customInput = new EditText(activity);
        customInput.setHint("简要描述问题（50 字内）");
        customInput.setTextColor(0xFFF5F7FF);
        customInput.setHintTextColor(0x889AA4BF);
        customInput.setTextSize(13);
        customInput.setBackgroundResource(R.drawable.bg_chat_input);
        customInput.setPadding(dp(10), dp(8), dp(10), dp(8));
        customInput.setMaxLines(3);
        customInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        // 服务端 reason 字段上限 50 字符，前端同步限制避免被静默截断
        customInput.setFilters(new android.text.InputFilter[]{
                new android.text.InputFilter.LengthFilter(50)});
        LinearLayout.LayoutParams ciLp = new LinearLayout.LayoutParams(-1, -2);
        ciLp.setMargins(0, 0, 0, dp(6));
        menuRoot.addView(customInput, ciLp);

        menuRoot.addView(menuButton("提交自定义理由", v -> {
            String custom = customInput.getText().toString().trim();
            if (custom.isEmpty()) {
                Toast.makeText(activity, "请先填写举报理由", Toast.LENGTH_SHORT).show();
                return;
            }
            dismissOptionDialog();
            doReport(scene, messageId, groupId, custom);
        }));

        menuRoot.addView(menuButton("取消", v -> dismissOptionDialog()));
        showOptionDialog("举报消息", menuRoot);
    }

    private void doReport(String scene, int messageId, int groupId, String reason) {
        AppExecutors.runOnIo(() -> {
            try {
                String msg = apiClient.reportMessage(scene, messageId, groupId, reason);
                uiHandler.post(() -> Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show());
            } catch (Throwable t) {
                uiHandler.post(() -> Toast.makeText(activity,
                        "举报失败：" + t.getMessage(), Toast.LENGTH_SHORT).show());
                handleApiError(t);
            }
        });
    }

    // ==================== 聊天图片 ====================

    /** 构建图片消息 View（点击看大图） */
    private View buildImageContentView(String relativeUrl) {
        ImageView iv = new ImageView(activity);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setBackgroundResource(R.drawable.bg_input);
        final String full = absoluteChatImageUrl(relativeUrl);
        loadEmojiInto(full, iv, dp(300));  // 复用带缓存的图片加载
        iv.setOnClickListener(v -> showImageViewer(full));
        return iv;
    }

    private String absoluteChatImageUrl(String url) {
        if (url == null || url.isEmpty()) return "";
        if (url.startsWith("http")) return url;
        return "https://yukihub.zh.kg" + url;
    }

    /** 全屏看大图（点击任意处关闭） */
    private void showImageViewer(String url) {
        if (url == null || url.isEmpty()) return;
        FrameLayout root = new FrameLayout(activity);
        root.setBackgroundColor(0xEE0A0E1A);

        ImageView big = new ImageView(activity);
        big.setScaleType(ImageView.ScaleType.FIT_CENTER);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -1);
        lp.setMargins(dp(8), dp(8), dp(8), dp(8));
        root.addView(big, lp);
        loadEmojiInto(url, big, dp(900));

        Dialog viewer = new Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        viewer.setContentView(root);
        viewer.setCancelable(true);
        root.setOnClickListener(v -> viewer.dismiss());
        if (viewer.getWindow() != null) {
            viewer.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
        }
        viewer.show();
    }

    /**
     * 选图 → 压缩 → 上传 → 发送。
     * @param isGroupChat true = 群聊，false = 私聊
     */
    private void pickAndSendImage(boolean isGroupChat) {
        boolean ok = ChatImagePicker.pick(activity, uri -> {
            if (uri == null) return;
            Toast.makeText(activity, "处理图片中...", Toast.LENGTH_SHORT).show();
            AppExecutors.runOnIo(() -> {
                ChatImagePicker.Compressed c = ChatImagePicker.compress(appContext, uri);
                if (c == null) {
                    uiHandler.post(() -> Toast.makeText(activity,
                            "图片处理失败，请换一张（需 500KB 以内可压缩的 jpg/png/webp）",
                            Toast.LENGTH_SHORT).show());
                    return;
                }
                try {
                    String url = apiClient.uploadChatImage(c.data, c.mimeType);
                    if (isGroupChat) sendImageGroupMessage(url);
                    else sendImageMessage(url);
                } catch (Throwable t) {
                    uiHandler.post(() -> Toast.makeText(activity,
                            "上传失败：" + t.getMessage(), Toast.LENGTH_SHORT).show());
                    handleApiError(t);
                }
            });
        });
        if (!ok) {
            Toast.makeText(activity, "当前页面不支持选图，请从首页进入聊天", Toast.LENGTH_SHORT).show();
        }
    }

    /** 发送图片消息（私聊）。在 IO 线程调用 */
    private void sendImageMessage(String url) {
        final FriendInfo friend = chatFriend;
        if (friend == null) return;
        try {
            ChatMessage sent = apiClient.sendMessage(friend.id, url, "image", 0);
            chatCache.upsertFriendMessages(friend.id, java.util.Collections.singletonList(sent));
            chatCache.pruneFriendMessages(friend.id);
            uiHandler.post(() -> {
                if (chatFriend == null || !friend.id.equals(chatFriend.id)) return;
                if (sent.id > maxMessageId) maxMessageId = sent.id;
                chatMessageList.addView(buildMessageBubble(sent));
                scrollToBottom();
            });
        } catch (Throwable t) {
            uiHandler.post(() -> Toast.makeText(activity,
                    "发送失败：" + t.getMessage(), Toast.LENGTH_SHORT).show());
            handleApiError(t);
        }
    }

    /** 发送图片消息（群聊）。在 IO 线程调用 */
    private void sendImageGroupMessage(String url) {
        final GroupInfo group = chatGroup;
        if (group == null) return;
        try {
            GroupMessage sent = apiClient.sendGroupMessage(group.id, url, "image", 0);
            chatCache.upsertGroupMessages(group.id, java.util.Collections.singletonList(sent));
            chatCache.pruneGroupMessages(group.id);
            uiHandler.post(() -> {
                if (chatGroup == null || chatGroup.id != group.id) return;
                if (sent.id > groupMaxMessageId) groupMaxMessageId = sent.id;
                groupMessageList.addView(buildGroupMessageBubble(sent));
                scrollGroupToBottom();
            });
        } catch (Throwable t) {
            uiHandler.post(() -> Toast.makeText(activity,
                    "发送失败：" + t.getMessage(), Toast.LENGTH_SHORT).show());
            handleApiError(t);
        }
    }

    // ==================== 等级徽章 / @提及 ====================

    /**
     * 等级文字配色，色值与网页端 community.css 的 .level-badge.lv-* 逐一对齐：
     * Lv.1-4 灰 #b9bcc7 / 5-9 绿 #7ee2a0 / 10-14 蓝 #7db8ff / 15-19 紫 #c9a0ff
     * 20-24 橙 #ffb37a / 25-29 红 #ff9090 / 30+ 金 #ffd27a
     */
    private int levelColor(int level) {
        if (level >= 30) return 0xFFFFD27A;
        if (level >= 25) return 0xFFFF9090;
        if (level >= 20) return 0xFFFFB37A;
        if (level >= 15) return 0xFFC9A0FF;
        if (level >= 10) return 0xFF7DB8FF;
        if (level >= 5)  return 0xFF7EE2A0;
        return 0xFFB9BCC7;
    }

    /**
     * 等级徽章底图（背景色 + 边框色），与网页端同档同色。
     * 分档规则与 levelColor 完全一致。
     */
    private int levelBadgeBg(int level) {
        if (level >= 30) return R.drawable.bg_level_gold;
        if (level >= 25) return R.drawable.bg_level_red;
        if (level >= 20) return R.drawable.bg_level_orange;
        if (level >= 15) return R.drawable.bg_level_purple;
        if (level >= 10) return R.drawable.bg_level_blue;
        if (level >= 5)  return R.drawable.bg_level_green;
        return R.drawable.bg_level_gray;
    }

    /**
     * 构建等级徽章（资料页昵称右侧）。
     * 点击弹出经验详情，与社区端点击徽章看进度的交互对齐。
     */
    private TextView buildLevelBadge(int level, int exp) {
        TextView badge = new TextView(activity);
        badge.setText("Lv." + level);
        badge.setTextColor(levelColor(level));
        badge.setTextSize(10);
        badge.setTypeface(null, android.graphics.Typeface.BOLD);
        badge.setGravity(Gravity.CENTER);
        badge.setIncludeFontPadding(false);   // 去掉字体自带的上下留白
        badge.setSingleLine(true);
        // 按等级取对应底图（背景+边框与网页端同色）。
        // 不能用 bg_input，它的 shape 自带 14dp/8dp padding 会覆盖 setPadding 把徽章撑大。
        badge.setBackgroundResource(levelBadgeBg(level));
        badge.setPadding(dp(7), dp(1), dp(7), dp(1));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(18));
        lp.setMargins(dp(7), 0, 0, 0);
        lp.gravity = Gravity.CENTER_VERTICAL;
        badge.setLayoutParams(lp);
        badge.setOnClickListener(v -> showLevelDetail(level, exp));
        return badge;
    }

    /** 等级封顶，与服务端 LEVEL_CAP 一致 */
    private static final int LEVEL_CAP = 30;

    /**
     * 等级曲线 V3 定表：与服务端 config.php 的 EXP_LEVEL_NEEDS 完全一致。
     * ⚠️ 改表必须三端同步：server/config.php、server/community/community.js、本文件。
     * 语义：expForLevel(lv) = 从 Lv.lv 升到 Lv.lv+1 所需经验；Lv.30 已封顶返回 0。
     */
    private int expForLevel(int level) {
        final int[] NEEDS = {60, 150, 220, 320, 390, 460, 530, 600, 670,
                             780, 820, 860, 900, 940, 980, 1020, 1060, 1100, 1140,
                             1180, 1220, 1260, 1300, 1340, 1380, 1420, 1460, 1500, 1540};
        int lv = Math.max(1, level);
        if (lv >= LEVEL_CAP) return 0;
        return NEEDS[lv - 1];
    }

    /** 累计到某等级所需总经验 */
    private int totalExpForLevel(int level) {
        int sum = 0;
        for (int i = 1; i < Math.max(1, level); i++) sum += expForLevel(i);
        return sum;
    }

    /** 等级详情弹窗：当前等级 + 本级进度 + 距下一级还需多少 */
    private void showLevelDetail(int level, int exp) {
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(6), dp(4), dp(6), dp(4));

        boolean maxed = level >= LEVEL_CAP;
        // B站式继承制：进度条 = 总经验 相对 升到下一级的累计阈值，
        // 升级时数值连续上涨不清零（与网页端 expBarHtml 口径一致）
        int next = totalExpForLevel(Math.min(LEVEL_CAP, level + 1));
        int remain = maxed ? 0 : Math.max(0, next - exp);

        TextView lv = new TextView(activity);
        lv.setText("Lv." + level);
        lv.setTextColor(levelColor(level));
        lv.setTextSize(22);
        lv.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(lv);

        TextView progress = new TextView(activity);
        progress.setText(maxed ? (exp + " EXP · 已满级") : (exp + " / " + next + " EXP"));
        progress.setTextColor(0xFFF5F7FF);
        progress.setTextSize(13);
        progress.setPadding(0, dp(6), 0, dp(2));
        root.addView(progress);

        // 进度条
        // 轨道用专用 drawable：不能用 bg_input，那个 shape 自带 top/bottom=8dp 的
        // padding，会把 8dp 高的容器内容区挤成 0，填充条画不出来（进度条全空的根因）
        FrameLayout barBg = new FrameLayout(activity);
        barBg.setBackgroundResource(R.drawable.bg_exp_bar_track);
        LinearLayout.LayoutParams bgLp = new LinearLayout.LayoutParams(-1, dp(8));
        bgLp.setMargins(0, dp(4), 0, dp(8));
        barBg.setLayoutParams(bgLp);

        // 填充条：代码里造圆角 shape，圆角要比轨道略小一点才不会溢出边缘
        View barFill = new View(activity);
        android.graphics.drawable.GradientDrawable fillBg = new android.graphics.drawable.GradientDrawable();
        fillBg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        fillBg.setCornerRadius(dp(3));
        fillBg.setColor(levelColor(level));
        barFill.setBackground(fillBg);

        float ratio = maxed ? 1f
                     : (next > 0 ? Math.min(1f, exp / (float) next) : 0f);
        // 高度留 1dp 边距避免压住轨道描边
        FrameLayout.LayoutParams fillLp = new FrameLayout.LayoutParams(0, -1);
        fillLp.setMargins(dp(1), dp(1), dp(1), dp(1));
        barBg.addView(barFill, fillLp);
        root.addView(barBg);
        // 宽度要等父容器测量完才能算。
        // 注意：不能只 post 一次就完事——弹窗首次布局时 getWidth() 可能还是 0，
        // 这里用 OnLayoutChangeListener 在宽度真正确定后再算一次。
        final float ratioFinal = ratio;
        // 用一个共用的计算逻辑，避免两处重复
        final Runnable applyFill = () -> {
            int avail = barBg.getWidth() - dp(2);   // 减掉左右各 1dp margin
            if (avail <= 0) return;
            FrameLayout.LayoutParams fl = (FrameLayout.LayoutParams) barFill.getLayoutParams();
            int want = ratioFinal <= 0f ? 0 : Math.max(dp(2), (int) (avail * ratioFinal));
            if (fl.width != want) {
                fl.width = want;
                barFill.setLayoutParams(fl);
            }
        };
        barBg.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, orr, ob) -> applyFill.run());
        // 首帧也算一次（宽度已知时立即生效，未知时靠上面的监听兜底）
        barBg.post(applyFill);

        TextView remainView = new TextView(activity);
        remainView.setText(maxed ? "🏆 已达满级"
                                 : ("距 Lv." + (level + 1) + " 还需 " + remain + " EXP"));
        remainView.setTextColor(0xFF9AA4BF);
        remainView.setTextSize(11);
        root.addView(remainView);

        root.addView(menuButton("关闭", v -> dismissOptionDialog()));
        showOptionDialog("等级详情", root);
    }

    /**
     * 群聊 @ 某人：把 "@昵称 " 插入输入框光标处。
     * 服务端 sendMentionNotifications 会解析 @昵称 并发通知，所以纯文本方案就够了。
     */
    private void mentionInGroup(GroupMessage msg) {
        if (msg == null || groupChatInput == null) return;
        if (chatGroup != null && !chatGroup.canSpeak()) {
            Toast.makeText(activity, "公告版仅管理员可发言", Toast.LENGTH_SHORT).show();
            return;
        }
        // @自己没意义
        if (msg.isMine) return;

        String nick = msg.senderNickname == null ? "" : msg.senderNickname.trim();
        if (nick.isEmpty()) {
            Toast.makeText(activity, "无法获取对方昵称", Toast.LENGTH_SHORT).show();
            return;
        }

        String mention = "@" + nick + " ";
        String current = groupChatInput.getText().toString();
        // 已经 @ 过就不重复插入
        if (current.contains(mention)) {
            groupChatInput.requestFocus();
            return;
        }

        int start = Math.max(0, groupChatInput.getSelectionStart());
        StringBuilder sb = new StringBuilder(current);
        sb.insert(Math.min(start, sb.length()), mention);
        groupChatInput.setText(sb.toString());
        groupChatInput.setSelection(Math.min(start + mention.length(), sb.length()));
        groupChatInput.requestFocus();

        // 唤起软键盘，省一次点击
        try {
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager)
                            activity.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(groupChatInput,
                    android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
        } catch (Throwable ignored) {}

        Toast.makeText(activity, "已 @" + nick, Toast.LENGTH_SHORT).show();
    }
}