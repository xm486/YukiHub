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

    public FriendsChatDialog(Activity activity) {
        this.activity = activity;
        this.appContext = activity.getApplicationContext();
        this.apiClient = new SocialApiClient(appContext);
        this.chatCache = new ChatCacheHelper(appContext);
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
        dialog.setOnDismissListener(d -> stopAllPolling());

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
        showFriendList();
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
        scrollView.setLayoutParams(new LinearLayout.LayoutParams(-1, 0, 1));

        chatMessageList = new LinearLayout(activity);
        chatMessageList.setOrientation(LinearLayout.VERTICAL);
        chatMessageList.setPadding(dp(4), dp(6), dp(4), dp(6));
        scrollView.addView(chatMessageList);

        scrollView.getViewTreeObserver().addOnScrollChangedListener(() -> {
            if (scrollView.getScrollY() == 0 && hasMoreHistory && chatMessagesCount() >= 20) {
                loadMoreHistory();
            }
        });
        contentContainer.addView(scrollView);

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
        elp.setMargins(0, 0, dp(4), 0);
        emojiBtn.setLayoutParams(elp);
        inputRow.addView(emojiBtn);

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
        AppExecutors.runOnIo(() -> {
            // 1. 读本地缓存渲染（先乐观允许翻历史，服务器同步后再修正）
            List<ChatMessage> cached = chatCache.getFriendMessages(chatFriend.id, RENDER_LIMIT);
            uiHandler.post(() -> {
                renderCachedFriendMessages(cached, true);
                scrollToBottom();
            });
            // 2. 后台同步服务器最近 20 条增量（失败时保留缓存，离线也能用）
            try {
                boolean[] serverHasMore = new boolean[]{true};
                boolean refreshed = syncFriendHistoryFromServer(chatFriend.id, serverHasMore);
                if (refreshed) {
                    List<ChatMessage> all = chatCache.getFriendMessages(chatFriend.id, RENDER_LIMIT);
                    uiHandler.post(() -> {
                        renderCachedFriendMessages(all, serverHasMore[0]);
                        scrollToBottom();
                    });
                } else {
                    // 没有新消息也要用服务器结果修正 hasMoreHistory（服务器到底时关闭翻页）
                    uiHandler.post(() -> {
                        boolean localMore = oldestLoadedMessageId > 0
                                && chatCache.countFriendMessagesBefore(chatFriend.id, oldestLoadedMessageId) > 0;
                        hasMoreHistory = localMore || serverHasMore[0];
                    });
                }
                startChatPolling();
            } catch (Throwable t) {
                if (cached.isEmpty()) {
                    uiHandler.post(() -> showError("加载消息失败", () -> openFriendChat()));
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
            boolean localMore = chatCache.countFriendMessagesBefore(chatFriend.id, oldestLoadedMessageId) > 0;
            hasMoreHistory = localMore || serverHasMore;
        }
        historyOffset = msgs == null ? 0 : msgs.size();
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
        int beforeId = oldestLoadedMessageId;
        int offset = historyOffset;
        AppExecutors.runOnIo(() -> {
            // 1. 本地缓存优先
            List<ChatMessage> older = chatCache.getFriendMessagesBefore(chatFriend.id, beforeId, 50);
            if (!older.isEmpty()) {
                uiHandler.post(() -> {
                    for (int i = older.size() - 1; i >= 0; i--) {
                        chatMessageList.addView(buildMessageBubble(older.get(i)), 0);
                    }
                    oldestLoadedMessageId = older.get(0).id;
                    historyOffset = offset + older.size();
                    hasMoreHistory = chatCache.countFriendMessagesBefore(chatFriend.id, oldestLoadedMessageId) > 0;
                });
                return;
            }
            // 2. 本地缓存到底 → 请求服务器
            try {
                List<ChatMessage> server = apiClient.getChatHistory(chatFriend.id, offset, 50);
                chatCache.upsertFriendMessages(chatFriend.id, server);
                chatCache.pruneFriendMessages(chatFriend.id);
                uiHandler.post(() -> {
                    for (int i = server.size() - 1; i >= 0; i--) {
                        chatMessageList.addView(buildMessageBubble(server.get(i)), 0);
                    }
                    if (!server.isEmpty()) oldestLoadedMessageId = server.get(0).id;
                    historyOffset = offset + server.size();
                    hasMoreHistory = server.size() >= 50;
                });
            } catch (Throwable t) {
                hasMoreHistory = true;
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
        }

        TextView time = new TextView(activity);
        time.setText(formatTime(msg.createdAt));
        time.setTextColor(0x889AA4BF);
        time.setTextSize(9);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-2, -2);
        tl.gravity = msg.isMine ? Gravity.END : Gravity.START;
        wrapper.addView(time, tl);
        return wrapper;
    }

    private void sendMessage() {
        if (chatFriend == null || chatInput == null) return;
        String content = chatInput.getText().toString().trim();
        if (content.isEmpty()) return;
        chatInput.setText("");
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
                ChatMessage sent = apiClient.sendMessage(chatFriend.id, msgContent);
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
        scrollView.setLayoutParams(new LinearLayout.LayoutParams(-1, 0, 1));

        groupMessageList = new LinearLayout(activity);
        groupMessageList.setOrientation(LinearLayout.VERTICAL);
        groupMessageList.setPadding(dp(4), dp(6), dp(4), dp(6));
        scrollView.addView(groupMessageList);

        scrollView.getViewTreeObserver().addOnScrollChangedListener(() -> {
            if (scrollView.getScrollY() == 0 && groupHasMoreHistory && groupMessagesCount() >= 20) {
                loadMoreGroupHistory();
            }
        });
        contentContainer.addView(scrollView);

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
        elp.setMargins(0, 0, dp(4), 0);
        emojiBtn.setLayoutParams(elp);
        inputRow.addView(emojiBtn);

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
        AppExecutors.runOnIo(() -> {
            // 1. 读本地缓存渲染（先乐观允许翻历史，服务器同步后再修正）
            List<GroupMessage> cached = chatCache.getGroupMessages(chatGroup.id, RENDER_LIMIT);
            uiHandler.post(() -> {
                renderCachedGroupMessages(cached, true);
                scrollGroupToBottom();
            });
            // 2. 后台同步服务器最近 20 条增量（失败时保留缓存，离线也能用）
            try {
                int[] online = new int[]{0};
                boolean[] serverHasMore = new boolean[]{true};
                boolean refreshed = syncGroupHistoryFromServer(chatGroup.id, online, serverHasMore);
                if (refreshed) {
                    List<GroupMessage> all = chatCache.getGroupMessages(chatGroup.id, RENDER_LIMIT);
                    uiHandler.post(() -> {
                        renderCachedGroupMessages(all, serverHasMore[0]);
                        scrollGroupToBottom();
                    });
                } else {
                    // 没有新消息也要用服务器结果修正 hasMoreHistory（服务器到底时关闭翻页）
                    uiHandler.post(() -> {
                        boolean localMore = groupOldestLoadedMessageId > 0
                                && chatCache.countGroupMessagesBefore(chatGroup.id, groupOldestLoadedMessageId) > 0;
                        groupHasMoreHistory = localMore || serverHasMore[0];
                    });
                }
                uiHandler.post(() -> updateOnlineCount(online[0]));
                startGroupPolling();
            } catch (Throwable t) {
                if (cached.isEmpty()) {
                    String err = t.getMessage() != null ? t.getMessage() : "未知错误";
                    uiHandler.post(() -> showError("加载消息失败：" + err, () -> openGroupChat()));
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
            boolean localMore = chatCache.countGroupMessagesBefore(chatGroup.id, groupOldestLoadedMessageId) > 0;
            groupHasMoreHistory = localMore || serverHasMore;
        }
        groupHistoryOffset = msgs == null ? 0 : msgs.size();
    }

    /**
     * 从服务器拉最近 20 条群消息合并进缓存。
     * 若本地缓存与服务器最新消息之间存在断档，自动多翻几页直到衔接。
     * @param serverHasMoreOut [0]：服务器是否可能还有更早的消息（false = 已到底）
     * @return 是否有新消息（需要刷新 UI）
     */
    private boolean syncGroupHistoryFromServer(int groupId, int[] onlineCountOut, boolean[] serverHasMoreOut) throws Exception {
        int cachedMaxId = chatCache.getGroupMaxId(groupId);
        List<GroupMessage> fresh = new ArrayList<>();
        int offset = 0;
        boolean serverHasMore = true;
        while (true) {
            SocialApiClient.GroupHistoryResult result = apiClient.getGroupMessages(groupId, offset, 20);
            if (onlineCountOut != null) onlineCountOut[0] = result.onlineCount;
            chatCache.upsertGroupMessages(groupId, result.messages);
            for (GroupMessage m : result.messages) {
                if (cachedMaxId == 0 || m.id > cachedMaxId) fresh.add(m);
            }
            int pageMinId = result.messages.isEmpty() ? 0 : result.messages.get(0).id;
            boolean connected = result.messages.isEmpty() || cachedMaxId == 0 || pageMinId <= cachedMaxId;
            serverHasMore = result.messages.size() >= 20;
            if (connected || result.messages.size() < 20) break;
            offset += 20;
            if (offset >= 200) break; // 安全上限：最多拉 10 页
        }
        chatCache.pruneGroupMessages(groupId);
        if (serverHasMoreOut != null) serverHasMoreOut[0] = serverHasMore;
        return !fresh.isEmpty();
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
        int beforeId = groupOldestLoadedMessageId;
        int offset = groupHistoryOffset;
        AppExecutors.runOnIo(() -> {
            // 1. 本地缓存优先
            List<GroupMessage> older = chatCache.getGroupMessagesBefore(chatGroup.id, beforeId, 50);
            if (!older.isEmpty()) {
                uiHandler.post(() -> {
                    for (int i = older.size() - 1; i >= 0; i--) {
                        groupMessageList.addView(buildGroupMessageBubble(older.get(i)), 0);
                    }
                    groupOldestLoadedMessageId = older.get(0).id;
                    groupHistoryOffset = offset + older.size();
                    groupHasMoreHistory = chatCache.countGroupMessagesBefore(chatGroup.id, groupOldestLoadedMessageId) > 0;
                });
                return;
            }
            // 2. 本地缓存到底 → 请求服务器
            try {
                SocialApiClient.GroupHistoryResult result = apiClient.getGroupMessages(chatGroup.id, offset, 50);
                List<GroupMessage> server = result.messages;
                chatCache.upsertGroupMessages(chatGroup.id, server);
                chatCache.pruneGroupMessages(chatGroup.id);
                uiHandler.post(() -> {
                    for (int i = server.size() - 1; i >= 0; i--) {
                        groupMessageList.addView(buildGroupMessageBubble(server.get(i)), 0);
                    }
                    if (!server.isEmpty()) groupOldestLoadedMessageId = server.get(0).id;
                    groupHistoryOffset = offset + server.size();
                    groupHasMoreHistory = server.size() >= 50;
                    updateOnlineCount(result.onlineCount);
                });
            } catch (Throwable t) {
                groupHasMoreHistory = true;
            }
        });
    }

    /** 递归查找 wrapper 中带 "group_bubble" tag 的 TextView 并绑定长按事件 */
    private void bindLongClickToBubble(View container, GroupMessage msg) {
        if (container instanceof TextView) {
            Object tag = container.getTag();
            if ("group_bubble".equals(tag)) {
                container.setOnLongClickListener(v -> {
                    showGroupMsgManageOptions(msg);
                    return true;
                });
            }
        }
        if (container instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) container;
            for (int i = 0; i < group.getChildCount(); i++) {
                bindLongClickToBubble(group.getChildAt(i), msg);
            }
        }
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
                loadAvatarInto(msg.senderAvatar, avatarImg, avatarText);
            }
            // 点击头像查看资料（文字头像兜底）
            if (msg.senderUid > 0) {
                final int sUid = msg.senderUid;
                avatarText.setOnClickListener(v -> showUserProfile(sUid));
            }

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

            if (msg.senderIsAdmin) {
                nickRow.addView(buildAdminBadge());
            }
            rightCol.addView(nickRow);

            if ("emoji".equals(msg.msgType)) {
                // 表情包：只显示图片
                View emojiView = buildEmojiContentView(msg.content);
                rightCol.addView(emojiView);
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

        // 管理员长按：撤回/删除（绑定到气泡而非整个 wrapper，避免和头像点击冲突）
        if (chatGroup != null && chatGroup.isAdmin() && msg.id > 0 && !msg.recalled) {
            final GroupMessage fMsg = msg;
            // 找到 wrapper 内的 bubble 并绑定长按
            bindLongClickToBubble(wrapper, fMsg);
        }

        // 管理员模式下让气泡可长按
        if (chatGroup != null && chatGroup.isAdmin()) {
            wrapper.setLongClickable(false); // wrapper 本身不拦截长按
        }

        return wrapper;
    }

    private void showGroupMsgManageOptions(GroupMessage msg) {
        LinearLayout menuRoot = new LinearLayout(activity);
        menuRoot.setOrientation(LinearLayout.VERTICAL);
        menuRoot.setPadding(dp(4), dp(4), dp(4), dp(4));

        Button recallBtn = menuButton("撤回消息", v -> {
            dismissOptionDialog();
            doManageGroupMessage(msg, "recall");
        });
        menuRoot.addView(recallBtn);

        Button deleteBtn = new Button(activity);
        deleteBtn.setText("删除消息");
        deleteBtn.setTextColor(0xFFFF6B6B);
        deleteBtn.setTextSize(13);
        deleteBtn.setBackgroundResource(R.drawable.bg_input);
        deleteBtn.setPadding(dp(10), dp(8), dp(10), dp(8));
        deleteBtn.setOnClickListener(v -> {
            dismissOptionDialog();
            doManageGroupMessage(msg, "delete");
        });
        menuRoot.addView(deleteBtn);

        showOptionDialog("消息管理", menuRoot);
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
                GroupMessage sent = apiClient.sendGroupMessage(chatGroup.id, msgContent);
                // 发送成功写入本地缓存
                chatCache.upsertGroupMessages(chatGroup.id, java.util.Collections.singletonList(sent));
                chatCache.pruneGroupMessages(chatGroup.id);
                uiHandler.post(() -> {
                    if (sent.id > groupMaxMessageId) groupMaxMessageId = sent.id;
                    // 用服务器返回的过滤后内容替换乐观气泡（敏感词修正）
                    if (!msgContent.equals(sent.content)) {
                        updateBubbleContent(bubbleView, sent.content);
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
                if (!newMsgs.isEmpty()) {
                    // 轮询到的新消息写入本地缓存（含撤回状态同步）
                    chatCache.upsertGroupMessages(chatGroup.id, newMsgs);
                    chatCache.pruneGroupMessages(chatGroup.id);
                }
                uiHandler.post(() -> {
                    if (!newMsgs.isEmpty()) {
                        for (GroupMessage msg : newMsgs) {
                            if (msg.id > groupMaxMessageId) {
                                // 跳过自己发的（乐观 UI 已展示）
                                if (!msg.isMine) {
                                    groupMessageList.addView(buildGroupMessageBubble(msg));
                                }
                                groupMaxMessageId = msg.id;
                            }
                        }
                        scrollGroupToBottom();
                    }
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
        if (groupMessageList == null) return;
        groupMessageList.post(() -> {
            View parent = (View) groupMessageList.getParent();
            if (parent != null) parent.scrollTo(0, groupMessageList.getHeight());
        });
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

        // 昵称
        TextView nameView = new TextView(activity);
        nameView.setText(nickname);
        nameView.setTextColor(0xFFF5F7FF);
        nameView.setTextSize(19);
        nameView.setTypeface(null, android.graphics.Typeface.BOLD);
        nameView.setMaxLines(1);
        nameView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        infoCol.addView(nameView);

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
        if (chatMessageList == null) return;
        chatMessageList.post(() -> {
            View parent = (View) chatMessageList.getParent();
            if (parent != null) parent.scrollTo(0, chatMessageList.getHeight());
        });
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

    /** 显示选项弹窗 */
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

        root.addView(content);

        optionDialog = new Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        optionDialog.setContentView(root);
        optionDialog.setCancelable(true);
        if (optionDialog.getWindow() != null) {
            optionDialog.getWindow().setBackgroundDrawableResource(R.drawable.bg_social_panel);
            optionDialog.getWindow().setLayout(
                    (int)(activity.getResources().getDisplayMetrics().widthPixels * 0.65f),
                    -2);
        }
        optionDialog.show();
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
}