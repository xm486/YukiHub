package com.yuki.yukihub.ui;

import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.yuki.yukihub.R;
import com.yuki.yukihub.model.Game;
import com.yuki.yukihub.util.TimeFormatUtil;


import java.util.ArrayList;
import java.util.List;

public class GameAdapter extends RecyclerView.Adapter<GameAdapter.Holder> {
    public interface OnGameClickListener { void onGameClick(Game game); void onGameDoubleClick(Game game); void onGameLongClick(Game game); void onStatusClick(Game game); }
    public interface OnUiFeedbackListener { void onUiFeedback(int type); }
    public static final int FEEDBACK_CLICK = 0;
    public static final int FEEDBACK_CONFIRM = 1;
    public static final int FEEDBACK_SWITCH = 2;
    private final List<Game> games = new ArrayList<>();
    private OnGameClickListener listener;
    private OnUiFeedbackListener feedbackListener;
    private long selectedGameId = -1;
    private long lastClickTime = 0L;
    private long lastClickGameId = -1L;
    private int themePrimaryColor = 0xFF8AB4FF;
    private int themeSecondaryColor = 0xFFFF8AB3;
    private int themeCardColor = 0xFF171E33;
    private int themeCard2Color = 0xFF222B49;
    private int themeBgColor = 0xFF0B1020;
    private boolean themeActive = false;

    public void setOnGameClickListener(OnGameClickListener listener) { this.listener = listener; }
public void setOnUiFeedbackListener(OnUiFeedbackListener listener) { this.feedbackListener = listener; }
private void emitFeedback(int type) { if (feedbackListener != null) feedbackListener.onUiFeedback(type); }
public void setSelectedGameId(long id) { selectedGameId = id; notifyDataSetChanged(); }
    public void submit(List<Game> newGames) { games.clear(); games.addAll(newGames); notifyDataSetChanged(); }
    public void setThemeColors(int primary, int secondary) { themePrimaryColor = primary; themeSecondaryColor = secondary; }

    /** Set full theme colors from dynamic extraction. Pass null to reset to defaults. */
    public void setFullThemeColors(ThemeColorExtractor.ThemeColors colors) {
        if (colors == null) {
            themeActive = false;
            themePrimaryColor = 0xFF8AB4FF;
            themeSecondaryColor = 0xFFFF8AB3;
            themeCardColor = 0xFF171E33;
            themeCard2Color = 0xFF222B49;
            themeBgColor = 0xFF0B1020;
        } else {
            themeActive = true;
            themePrimaryColor = colors.primary;
            themeSecondaryColor = colors.secondary;
            themeCardColor = colors.card;
            themeCard2Color = colors.card2;
            themeBgColor = colors.bg;
        }
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_game_card, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        Game g = games.get(position);
        h.itemView.setSelected(g != null && g.id == selectedGameId);

        // Card background with dynamic colors
        if (themeActive) {
            if (h.itemView.isSelected()) {
                GradientDrawable selectedBg = new GradientDrawable(
                        GradientDrawable.Orientation.TOP_BOTTOM,
                        new int[]{
                                (0xEA << 24) | (themeCardColor & 0x00FFFFFF),
                                (0xE0 << 24) | (themeCardColor & 0x00FFFFFF),
                                (0xDC << 24) | (themeCard2Color & 0x00FFFFFF)
                        });
                selectedBg.setCornerRadius((int) dp(h.itemView, 10));
                selectedBg.setStroke((int) dp(h.itemView, 2), (0x9C << 24) | (themePrimaryColor & 0x00FFFFFF));
                h.itemView.setBackground(selectedBg);
            } else {
                GradientDrawable normalBg = new GradientDrawable(
                        GradientDrawable.Orientation.TOP_BOTTOM,
                        new int[]{
                                (0xE4 << 24) | (themeCardColor & 0x00FFFFFF),
                                (0xD9 << 24) | (themeCardColor & 0x00FFFFFF),
                                (0xD8 << 24) | (themeCard2Color & 0x00FFFFFF)
                        });
                normalBg.setCornerRadius((int) dp(h.itemView, 10));
                normalBg.setStroke((int) dp(h.itemView, 1), (0x46 << 24) | (themePrimaryColor & 0x00FFFFFF));
                h.itemView.setBackground(normalBg);
            }
        } else {
            h.itemView.setBackgroundResource(h.itemView.isSelected() ? R.drawable.bg_game_card_selected : R.drawable.bg_game_card);
        }

        if (h.cardGlow != null) {
            h.cardGlow.setVisibility(h.itemView.isSelected() ? View.VISIBLE : View.GONE);
            h.cardGlow.setThemeColors(themePrimaryColor, themeSecondaryColor);
        }
        h.title.setText(g.title);
        h.title.setTextColor(themeActive ? 0xFFF0F4FA : h.title.getContext().getResources().getColor(R.color.yh_text));
        if (h.favoriteBadge != null) h.favoriteBadge.setVisibility(g.favorite ? View.VISIBLE : View.GONE);
        h.engineCover.setText(g.engine.getDisplayName());
        h.engineTitle.setText(g.engine.getDisplayName());
        if (themeActive) {
            h.engineCover.setTextColor(0xFFE8EDF5);
            h.engineTitle.setTextColor(0xFFE8EDF5);
        } else {
            h.engineCover.setTextColor(h.engineCover.getContext().getResources().getColor(R.color.yh_text_muted));
            h.engineTitle.setTextColor(h.engineTitle.getContext().getResources().getColor(R.color.yh_text_muted));
        }
        boolean engineOnCover = "cover".equals(getEngineLabelPosition(h.itemView));
        h.engineCover.setVisibility(engineOnCover ? View.VISIBLE : View.GONE);
        h.engineTitle.setVisibility(engineOnCover ? View.GONE : View.VISIBLE);

        // Engine chip with dynamic colors
        if (themeActive) {
            GradientDrawable chipBg = new GradientDrawable();
            chipBg.setColor((0xAA << 24) | (themeCard2Color & 0x00FFFFFF));
            chipBg.setCornerRadius((int) dp(h.itemView, 999));
            chipBg.setStroke((int) dp(h.itemView, 1), (0x7A << 24) | (themePrimaryColor & 0x00FFFFFF));
            if (h.engineCover != null) h.engineCover.setBackground(chipBg);
            if (h.engineTitle != null) h.engineTitle.setBackground(chipBg);
        }

        h.playTime.setText("总时长 " + TimeFormatUtil.playTime(g.totalPlayTime));
        h.playTime.setTextColor(themeActive ? 0xFFB0B8C8 : h.playTime.getContext().getResources().getColor(R.color.yh_text_muted));
        bindStatusBadge(h.statusBadge, g.playStatus);
        String coverUri = chooseSafeCoverUri(g);
        if (coverUri != null && !coverUri.isEmpty()) {
            try {
                Uri uri = Uri.parse(coverUri);
                h.cover.setImageURI(uri);
                h.cover.setVisibility(View.VISIBLE);
                h.placeholder.setVisibility(View.GONE);
            } catch (Throwable e) {
                h.cover.setImageDrawable(null);
                h.cover.setVisibility(View.GONE);
                h.placeholder.setVisibility(View.VISIBLE);
                h.placeholder.setText(initials(g.title));
                if (themeActive) h.placeholder.setTextColor(0xFFE8EDF5);
            }
        } else {
            h.cover.setImageDrawable(null);
            h.cover.setVisibility(View.GONE);
            h.placeholder.setVisibility(View.VISIBLE);
            h.placeholder.setText(initials(g.title));
            if (themeActive) h.placeholder.setTextColor(0xFFE8EDF5);
        }
        // Cover placeholder dynamic background
        if (themeActive && h.placeholder != null && h.placeholder.getVisibility() == View.VISIBLE) {
            GradientDrawable phBg = new GradientDrawable(
                    GradientDrawable.Orientation.BL_TR,
                    new int[]{
                            (0xFF << 24) | (themeCardColor & 0x00FFFFFF),
                            (0xFF << 24) | (themeCard2Color & 0x00FFFFFF),
                            (0xFF << 24) | (themeSecondaryColor & 0x00FFFFFF)
                    });
            phBg.setCornerRadius((int) dp(h.itemView, 8));
            phBg.setStroke((int) dp(h.itemView, 1), 0x22FFFFFF);
            h.placeholder.setBackground(phBg);
        }

        // Cover area FrameLayout background (bg_cover_placeholder)
        if (themeActive && h.coverFrame != null) {
            GradientDrawable coverFrameBg = new GradientDrawable(
                    GradientDrawable.Orientation.BL_TR,
                    new int[]{
                            (0xFF << 24) | (themeCardColor & 0x00FFFFFF),
                            (0xFF << 24) | (themeCard2Color & 0x00FFFFFF),
                            (0xFF << 24) | (themeSecondaryColor & 0x00FFFFFF)
                    });
            coverFrameBg.setCornerRadius((int) dp(h.itemView, 8));
            coverFrameBg.setStroke((int) dp(h.itemView, 1), 0x22FFFFFF);
            h.coverFrame.setBackground(coverFrameBg);
        }

        // Favorite badge background
        if (themeActive && h.favoriteBadge != null && h.favoriteBadge.getVisibility() == View.VISIBLE) {
            GradientDrawable favBg = new GradientDrawable();
            favBg.setColor((0xAA << 24) | (themeCard2Color & 0x00FFFFFF));
            favBg.setCornerRadius((int) dp(h.itemView, 999));
            favBg.setStroke((int) dp(h.itemView, 1), (0x7A << 24) | (themePrimaryColor & 0x00FFFFFF));
            h.favoriteBadge.setBackground(favBg);
        }
        try { h.itemView.setSoundEffectsEnabled(false); } catch (Throwable ignored) { }
        try { h.statusBadge.setSoundEffectsEnabled(false); } catch (Throwable ignored) { }
        applyCardFeedback(h.itemView);
        h.statusBadge.setOnClickListener(v -> {
            emitFeedback(FEEDBACK_SWITCH);
            try { v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); } catch (Throwable ignored) { }
            selectedGameId = g.id;
            notifyDataSetChanged();
            if (listener != null) listener.onStatusClick(g);
        });
        h.itemView.setOnClickListener(v -> {
            try { v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); } catch (Throwable ignored) { }
            long now = System.currentTimeMillis();
            boolean isDouble = lastClickGameId == g.id && (now - lastClickTime) <= 350L;
            emitFeedback(isDouble ? FEEDBACK_CONFIRM : FEEDBACK_CLICK);
            lastClickGameId = g.id;
            lastClickTime = now;
            selectedGameId = g.id;
            notifyDataSetChanged();
            if (listener != null) {
                if (isDouble) listener.onGameDoubleClick(g);
                else listener.onGameClick(g);
            }
        });
        h.itemView.setOnLongClickListener(v -> {
            emitFeedback(FEEDBACK_CONFIRM);
            try { v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); } catch (Throwable ignored) { }
            if (listener != null) listener.onGameLongClick(g);
            return true;
        });
    }

    @Override public int getItemCount() { return games.size(); }

    private void applyCardFeedback(View view) {
        if (view == null) return;
        view.setOnTouchListener((v, event) -> {
            if (event == null) return false;
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                v.animate().cancel();
                v.animate().scaleX(0.965f).scaleY(0.965f).alpha(0.82f).setDuration(75L).start();
            } else if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
                v.animate().cancel();
                v.animate().scaleX(1.0f).scaleY(1.0f).alpha(1.0f).setDuration(130L).start();
            }
            return false;
        });
    }

    private void bindStatusBadge(TextView badge, String status) {
        if (badge == null) return;
        String s = status == null ? "unplayed" : status;
        badge.setVisibility(View.VISIBLE);
        if (themeActive) badge.setTextColor(0xFFE8EDF5);
        else badge.setTextColor(badge.getContext().getResources().getColor(R.color.yh_text));
        if ("completed".equals(s)) {
            badge.setText("🏆玩过");
            if (themeActive) {
                GradientDrawable bg = new GradientDrawable();
                bg.setColor((0x5A << 24) | (themeSecondaryColor & 0x00FFFFFF));
                bg.setCornerRadius((int) dp(badge, 999));
                badge.setBackground(bg);
            } else {
                badge.setBackgroundResource(R.drawable.bg_status_completed);
            }
        } else if ("playing".equals(s)) {
            badge.setText("🎮在玩");
            if (themeActive) {
                GradientDrawable bg = new GradientDrawable();
                bg.setColor((0x5A << 24) | (themePrimaryColor & 0x00FFFFFF));
                bg.setCornerRadius((int) dp(badge, 999));
                badge.setBackground(bg);
            } else {
                badge.setBackgroundResource(R.drawable.bg_status_playing);
            }
        } else {
            badge.setText("☆未玩");
            if (themeActive) {
                GradientDrawable bg = new GradientDrawable();
                bg.setColor((0x5A << 24) | (themeCard2Color & 0x00FFFFFF));
                bg.setCornerRadius((int) dp(badge, 999));
                badge.setBackground(bg);
            } else {
                badge.setBackgroundResource(R.drawable.bg_status_unplayed);
            }
        }
    }

    private String chooseSafeCoverUri(Game g) {
        if (g == null) return null;
        if (g.coverPersistUri != null && !g.coverPersistUri.isEmpty()) return g.coverPersistUri;
        if (g.coverUri != null && !g.coverUri.isEmpty()) return g.coverUri;
        return null;
    }
    private String initials(String title) {
        if (title == null || title.trim().isEmpty()) return "YH";
        return title.trim().substring(0, 1).toUpperCase();
    }

    private String getEngineLabelPosition(View view) {
        try {
            return view.getContext().getApplicationContext()
                    .getSharedPreferences("yukihub_prefs", android.content.Context.MODE_PRIVATE)
                    .getString("engine_label_position", "title");
        } catch (Throwable ignored) {
            return "title";
        }
    }

    private static float dp(View view, float v) {
        return v * view.getResources().getDisplayMetrics().density;
    }


    static class Holder extends RecyclerView.ViewHolder {
        ImageView cover;
        View coverFrame;
        TextView placeholder, title, favoriteBadge, engineCover, engineTitle, playTime, statusBadge;
        CardGlowView cardGlow;
        Holder(@NonNull View itemView) {
            super(itemView);
            cardGlow = itemView.findViewById(R.id.cardGlow);
            cover = itemView.findViewById(R.id.ivCover);
            placeholder = itemView.findViewById(R.id.tvCoverPlaceholder);
            // Get cover FrameLayout as parent of placeholder
            if (placeholder != null && placeholder.getParent() instanceof View) {
                coverFrame = (View) placeholder.getParent();
            }
            title = itemView.findViewById(R.id.tvGameTitle);
            favoriteBadge = itemView.findViewById(R.id.tvFavoriteBadge);
            engineCover = itemView.findViewById(R.id.tvEngineCover);
            engineTitle = itemView.findViewById(R.id.tvEngineTitle);
            playTime = itemView.findViewById(R.id.tvPlayTime);
            statusBadge = itemView.findViewById(R.id.tvStatusBadge);
        }
    }
}