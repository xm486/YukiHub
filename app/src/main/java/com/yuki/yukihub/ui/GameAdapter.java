package com.yuki.yukihub.ui;

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

    public void setOnGameClickListener(OnGameClickListener listener) { this.listener = listener; }
public void setOnUiFeedbackListener(OnUiFeedbackListener listener) { this.feedbackListener = listener; }
private void emitFeedback(int type) { if (feedbackListener != null) feedbackListener.onUiFeedback(type); }
public void setSelectedGameId(long id) { selectedGameId = id; notifyDataSetChanged(); }
    public void submit(List<Game> newGames) { games.clear(); games.addAll(newGames); notifyDataSetChanged(); }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_game_card, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        Game g = games.get(position);
        h.itemView.setSelected(g != null && g.id == selectedGameId);
        h.itemView.setBackgroundResource(h.itemView.isSelected() ? R.drawable.bg_game_card_selected : R.drawable.bg_game_card);
        if (h.cardGlow != null) h.cardGlow.setVisibility(h.itemView.isSelected() ? View.VISIBLE : View.GONE);
        h.title.setText(g.title);
        if (h.favoriteBadge != null) h.favoriteBadge.setVisibility(g.favorite ? View.VISIBLE : View.GONE);
        h.engineCover.setText(g.engine.getDisplayName());
        h.engineTitle.setText(g.engine.getDisplayName());
        boolean engineOnCover = "cover".equals(getEngineLabelPosition(h.itemView));
        h.engineCover.setVisibility(engineOnCover ? View.VISIBLE : View.GONE);
        h.engineTitle.setVisibility(engineOnCover ? View.GONE : View.VISIBLE);
        h.playTime.setText("总时长 " + TimeFormatUtil.playTime(g.totalPlayTime));
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
            }
        } else {
            h.cover.setImageDrawable(null);
            h.cover.setVisibility(View.GONE);
            h.placeholder.setVisibility(View.VISIBLE);
            h.placeholder.setText(initials(g.title));
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
        if ("completed".equals(s)) {
            badge.setText("🏆玩过");
            badge.setBackgroundResource(R.drawable.bg_status_completed);
            badge.setTextColor(0xFFFFF4C2);
        } else if ("playing".equals(s)) {
            badge.setText("🎮在玩");
            badge.setBackgroundResource(R.drawable.bg_status_playing);
            badge.setTextColor(0xFFEAF7FF);
        } else {
            badge.setText("☆未玩");
            badge.setBackgroundResource(R.drawable.bg_status_unplayed);
            badge.setTextColor(0xFFEAF0FF);
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


    static class Holder extends RecyclerView.ViewHolder {
        ImageView cover;
        TextView placeholder, title, favoriteBadge, engineCover, engineTitle, playTime, statusBadge;
        CardGlowView cardGlow;
        Holder(@NonNull View itemView) {
            super(itemView);
            cardGlow = itemView.findViewById(R.id.cardGlow);
            cover = itemView.findViewById(R.id.ivCover);
            placeholder = itemView.findViewById(R.id.tvCoverPlaceholder);
            title = itemView.findViewById(R.id.tvGameTitle);
            favoriteBadge = itemView.findViewById(R.id.tvFavoriteBadge);
            engineCover = itemView.findViewById(R.id.tvEngineCover);
            engineTitle = itemView.findViewById(R.id.tvEngineTitle);
            playTime = itemView.findViewById(R.id.tvPlayTime);
            statusBadge = itemView.findViewById(R.id.tvStatusBadge);
        }
    }
}