package com.yuki.yukihub.social;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
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
    private static final String PREFS_NAME = "yukihub_prefs";
    private static final String KEY_AUTH_ACCESS_TOKEN = "auth_access_token";

    // 头像缓存（避免重复加载）
    private static final int AVATAR_CACHE_SIZE = 64;
    private static final android.util.LruCache<String, android.graphics.Bitmap> avatarCache =
            new android.util.LruCache<>(AVATAR_CACHE_SIZE);

    private final Activity activity;
    private final Context appContext;
    private final SocialApiClient apiClient;
    private final android.os.Handler uiHandler = new android.os.Handler(android.os.Looper.getMainLooper());

    private Dialog dialog;
    private Dialog optionDialog;
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

    // 请求列表
    private JSONArray incomingRequests = new JSONArray();
    private JSONArray outgoingRequests = new JSONArray();

    public FriendsChatDialog(Activity activity) {
        this.activity = activity;
        this.appContext = activity.getApplicationContext();
        this.apiClient = new SocialApiClient(appContext);
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
        backButton.setOnClickListener(v -> showFriendList());
        header.addView(backButton);

        titleBar = new TextView(activity);
        titleBar.setText("好友 / 聊天");
        titleBar.setTextColor(0xFFF5F7FF);
        titleBar.setTextSize(16);
        titleBar.setTypeface(null, android.graphics.Typeface.BOLD);
        titleBar.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
        header.addView(titleBar);

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
        resetContent(true);
        stopAllPolling();
        contentContainer.addView(loadingLabel("正在加载好友列表..."));

        AppExecutors.runOnIo(() -> {
            try {
                List<FriendInfo> loaded = apiClient.getFriendsList();
                int pendingCount = apiClient.getPendingRequestsCount();
                uiHandler.post(() -> renderFriendList(loaded, pendingCount));
            } catch (Throwable t) {
                uiHandler.post(() -> showError("加载失败：" + t.getMessage(), () -> showFriendList()));
            }
        });
    }

    private void renderFriendList(List<FriendInfo> loaded, int pendingCount) {
        friends = loaded;
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

        // 头像占位
        TextView avatar = new TextView(activity);
        avatar.setTextSize(14);
        avatar.setTextColor(0xFFF5F7FF);
        avatar.setGravity(Gravity.CENTER);
        avatar.setBackgroundResource(R.drawable.bg_input);
        avatar.setText(friend.nickname != null && !friend.nickname.isEmpty()
                ? friend.nickname.substring(0, 1).toUpperCase() : "?");
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(dp(36), dp(36));
        al.setMargins(0, 0, dp(8), 0);
        row.addView(avatar, al);

        // 加载头像
        if (friend.avatarUrl != null && !friend.avatarUrl.isEmpty()) {
            ImageView avatarImg = new ImageView(activity);
            avatarImg.setScaleType(ImageView.ScaleType.CENTER_CROP);
            avatarImg.setLayoutParams(new LinearLayout.LayoutParams(dp(36), dp(36)));
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

    // ==================== 聊天界面 ====================

    private void showChatView(FriendInfo friend) {
        chatFriend = friend;
        maxMessageId = 0;
        historyOffset = 0;
        hasMoreHistory = true;
        stopChatPolling();

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
        il.setMargins(0, 0, dp(6), 0);
        inputRow.addView(chatInput, il);

        Button sendBtn = socialButton("发送", v -> sendMessage());
        sendBtn.setLayoutParams(new LinearLayout.LayoutParams(dp(54), dp(38)));
        inputRow.addView(sendBtn);
        contentContainer.addView(inputRow);

        loadHistory(0);
    }

    private int chatMessagesCount() {
        return chatMessageList == null ? 0 : chatMessageList.getChildCount();
    }

    private void loadHistory(int offset) {
        if (chatFriend == null) return;
        AppExecutors.runOnIo(() -> {
            try {
                List<ChatMessage> msgs = apiClient.getChatHistory(chatFriend.id, offset, 50);
                uiHandler.post(() -> renderHistory(msgs, offset));
            } catch (Throwable t) {
                if (offset == 0) uiHandler.post(() -> showError("加载消息失败", () -> loadHistory(0)));
            }
        });
    }

    private void loadMoreHistory() {
        if (chatFriend == null || !hasMoreHistory) return;
        hasMoreHistory = false;
        int offset = historyOffset;
        AppExecutors.runOnIo(() -> {
            try {
                List<ChatMessage> older = apiClient.getChatHistory(chatFriend.id, offset, 50);
                uiHandler.post(() -> {
                    for (int i = older.size() - 1; i >= 0; i--) {
                        chatMessageList.addView(buildMessageBubble(older.get(i)), 0);
                    }
                    historyOffset = offset + 50;
                    hasMoreHistory = older.size() >= 50;
                });
            } catch (Throwable t) {
                hasMoreHistory = true;
            }
        });
    }

    private void renderHistory(List<ChatMessage> msgs, int offset) {
        if (offset == 0) {
            chatMessageList.removeAllViews();
            if (msgs.isEmpty()) chatMessageList.addView(emptyLabel("开始聊天吧"));
        }
        for (ChatMessage msg : msgs) {
            chatMessageList.addView(buildMessageBubble(msg));
            if (msg.id > maxMessageId) maxMessageId = msg.id;
        }
        historyOffset = offset + msgs.size();
        if (msgs.size() < 50) hasMoreHistory = false;
        if (offset == 0) {
            scrollToBottom();
            startChatPolling();
        }
    }

    private View buildMessageBubble(ChatMessage msg) {
        LinearLayout wrapper = new LinearLayout(activity);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.setPadding(dp(4), dp(2), dp(4), dp(2));

        TextView bubble = new TextView(activity);
        bubble.setText(msg.content);
        bubble.setTextColor(0xFFF5F7FF);
        bubble.setTextSize(13);
        bubble.setLineSpacing(dp(2), 1.0f);
        bubble.setPadding(dp(10), dp(6), dp(10), dp(6));
        bubble.setBackgroundResource(msg.isMine ? R.drawable.bg_chat_bubble_self : R.drawable.bg_chat_bubble_friend);

        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-2, -2);
        bl.gravity = msg.isMine ? Gravity.END : Gravity.START;
        wrapper.addView(bubble, bl);

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
        chatMessageList.addView(buildMessageBubble(local));
        scrollToBottom();

        AppExecutors.runOnIo(() -> {
            try {
                ChatMessage sent = apiClient.sendMessage(chatFriend.id, msgContent);
                uiHandler.post(() -> { if (sent.id > maxMessageId) maxMessageId = sent.id; });
            } catch (Throwable t) {
                uiHandler.post(() -> Toast.makeText(activity, "发送失败: " + t.getMessage(), Toast.LENGTH_SHORT).show());
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
            } catch (Throwable ignored) {}
        }, POLL_INTERVAL_MS, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private void stopChatPolling() {
        if (chatPollFuture != null) { chatPollFuture.cancel(false); chatPollFuture = null; }
    }

    // ==================== 添加好友 ====================

    private void showAddFriendView() {
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

                TextView avatar = new TextView(activity);
                avatar.setTextSize(14);
                avatar.setTextColor(0xFFF5F7FF);
                avatar.setGravity(Gravity.CENTER);
                avatar.setBackgroundResource(R.drawable.bg_input);
                avatar.setText(nickname.isEmpty() ? "?" : nickname.substring(0, 1).toUpperCase());
                LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(dp(32), dp(32));
                al.setMargins(0, 0, dp(8), 0);
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
        statusText.setText(statusLabel(status) + "  ·  UID " + uid);
        statusText.setTextColor(0xFF9AA4BF);
        statusText.setTextSize(12);
        statusRow.addView(statusText);
        infoCol.addView(statusRow);
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

    private void loadAvatarInto(String url, ImageView imageView, TextView fallback) {
        if (url == null || url.isEmpty()) return;

        // 检查缓存
        android.graphics.Bitmap cached = avatarCache.get(url);
        if (cached != null) {
            imageView.setImageBitmap(cached);
            imageView.setVisibility(View.VISIBLE);
            fallback.setVisibility(View.GONE);
            return;
        }

        new Thread(() -> {
            try {
                java.net.URL imgUrl = new java.net.URL(url);
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) imgUrl.openConnection();
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                conn.setInstanceFollowRedirects(true);
                android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeStream(conn.getInputStream());
                conn.disconnect();
                if (bmp != null) {
                    avatarCache.put(url, bmp);
                    activity.runOnUiThread(() -> {
                        imageView.setImageBitmap(bmp);
                        imageView.setVisibility(View.VISIBLE);
                        fallback.setVisibility(View.GONE);
                    });
                }
            } catch (Throwable ignored) {}
        }, "YukiHub-Avatar-Load").start();
    }

    private void showRequestsView() {
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
    }

    // ==================== 工具方法 ====================

    private boolean isLoggedIn() {
        SharedPreferences p = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String t = p.getString(KEY_AUTH_ACCESS_TOKEN, "");
        return t != null && !t.trim().isEmpty();
    }

    private int dp(int value) {
        return (int)(value * activity.getResources().getDisplayMetrics().density + 0.5f);
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
}