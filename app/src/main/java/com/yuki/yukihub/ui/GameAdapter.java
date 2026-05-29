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
    public interface OnGameClickListener { void onGameClick(Game game); void onGameLongClick(Game game); void onStatusClick(Game game); }
    private final List<Game> games = new ArrayList<>();
    private OnGameClickListener listener;
    private long selectedGameId = -1;

    public void setOnGameClickListener(OnGameClickListener listener) { this.listener = listener; }
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
        h.engine.setText(g.engine.getDisplayName());
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
        applyCardFeedback(h.itemView);
        h.statusBadge.setOnClickListener(v -> {
            try { v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); } catch (Throwable ignored) { }
            selectedGameId = g.id;
            notifyDataSetChanged();
            if (listener != null) listener.onStatusClick(g);
        });
        h.itemView.setOnClickListener(v -> {
            try { v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); } catch (Throwable ignored) { }
            selectedGameId = g.id;
            notifyDataSetChanged();
            if (listener != null) listener.onGameClick(g);
        });
        h.itemView.setOnLongClickListener(v -> {
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

    static class Holder extends RecyclerView.ViewHolder {
        ImageView cover;
        TextView placeholder, title, engine, playTime, statusBadge;
        CardGlowView cardGlow;
        Holder(@NonNull View itemView) {
            super(itemView);
            cardGlow = itemView.findViewById(R.id.cardGlow);
            cover = itemView.findViewById(R.id.ivCover);
            placeholder = itemView.findViewById(R.id.tvCoverPlaceholder);
            title = itemView.findViewById(R.id.tvGameTitle);
            engine = itemView.findViewById(R.id.tvEngine);
            playTime = itemView.findViewById(R.id.tvPlayTime);
            statusBadge = itemView.findViewById(R.id.tvStatusBadge);
        }
    }
}