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
        name.setText(friend.nickname);
        name.setTextColor(0xFFF5F7FF);
        name.setTextSize(14);
        name.setTypeface(null, android.graphics.Typeface.BOLD);
        nameRow.addView(name);

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
        // 长按查看用户资料
        row.setOnLongClickListener(v -> {
            showUserProfile(friend.uid);
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

        // 头像
        FrameLayout avatarBox = new FrameLayout(activity);
        avatarBox.setBackgroundResource(R.drawable.bg_input);
        LinearLayout.LayoutParams abl = new LinearLayout.LayoutParams(dp(64), dp(64));
        abl.setMargins(0, 0, 0, dp(8));
        abl.gravity = Gravity.CENTER_HORIZONTAL;
        avatarBox.setLayoutParams(abl);

        TextView avatarText = new TextView(activity);
        avatarText.setText(nickname.isEmpty() ? "?" : nickname.substring(0, 1).toUpperCase());
        avatarText.setTextColor(0xFFF5F7FF);
        avatarText.setTextSize(24);
        avatarText.setGravity(Gravity.CENTER);
        avatarBox.addView(avatarText, new FrameLayout.LayoutParams(-1, -1));

        // 加载头像
        if (!avatarUrl.isEmpty()) {
            try {
                ImageView avatarImg = new ImageView(activity);
                avatarImg.setScaleType(ImageView.ScaleType.CENTER_CROP);
                avatarBox.addView(avatarImg, new FrameLayout.LayoutParams(-1, -1));
                loadAvatarInto(avatarUrl, avatarImg, avatarText);
            } catch (Throwable ignored) {}
        }
        contentContainer.addView(avatarBox);

        // 昵称
        TextView nameView = new TextView(activity);
        nameView.setText(nickname);
        nameView.setTextColor(0xFFF5F7FF);
        nameView.setTextSize(20);
        nameView.setTypeface(null, android.graphics.Typeface.BOLD);
        nameView.setGravity(Gravity.CENTER);
        contentContainer.addView(nameView);

        // UID 和状态
        TextView uidView = new TextView(activity);
        uidView.setText("UID " + uid + "  ·  " + statusLabel(status));
        uidView.setTextColor(0xFF9AA4BF);
        uidView.setTextSize(14);
        uidView.setGravity(Gravity.CENTER);
        uidView.setPadding(0, dp(4), 0, dp(8));
        contentContainer.addView(uidView);

        // 签名
        if (!signature.isEmpty()) {
            TextView sigView = new TextView(activity);
            sigView.setText(signature);
            sigView.setTextColor(0xFF9AA4BF);
            sigView.setTextSize(13);
            sigView.setGravity(Gravity.CENTER);
            sigView.setPadding(0, 0, 0, dp(8));
            contentContainer.addView(sigView);
        }

        // 分割线
        contentContainer.addView(divider());

        // 活动（Steam 风格：在线时绿色显示）
        if (!currentAct.isEmpty() && "online".equals(status)) {
            TextView actView = new TextView(activity);
            actView.setText(currentAct);
            actView.setTextColor(0xFF90BA3C);
            actView.setTextSize(13);
            actView.setGravity(Gravity.CENTER);
            actView.setPadding(0, dp(8), 0, dp(4));
            contentContainer.addView(actView);
        }

        // 游戏统计
        LinearLayout statsRow = new LinearLayout(activity);
        statsRow.setOrientation(LinearLayout.HORIZONTAL);
        statsRow.setPadding(0, dp(8), 0, 0);

        int h = dp(56);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(0, h, 1);
        sl.setMargins(0, 0, dp(6), 0);

        TextView gamesStat = new TextView(activity);
        gamesStat.setText(totalGames + "\n🎮 游戏数");
        gamesStat.setGravity(Gravity.CENTER);
        gamesStat.setTextColor(0xFFF5F7FF);
        gamesStat.setTextSize(13);
        gamesStat.setTypeface(null, android.graphics.Typeface.BOLD);
        gamesStat.setBackgroundResource(R.drawable.bg_input);
        statsRow.addView(gamesStat, sl);

        TextView playStat = new TextView(activity);
        int hours = totalPlayTime / 3600;
        int mins = (totalPlayTime % 3600) / 60;
        playStat.setText((hours > 0 ? hours + "h " : "") + mins + "m\n◷ 游玩时长");
        playStat.setGravity(Gravity.CENTER);
        playStat.setTextColor(0xFFF5F7FF);
        playStat.setTextSize(13);
        playStat.setTypeface(null, android.graphics.Typeface.BOLD);
        playStat.setBackgroundResource(R.drawable.bg_input);
        statsRow.addView(playStat, new LinearLayout.LayoutParams(0, h, 1));

        contentContainer.addView(statsRow);
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