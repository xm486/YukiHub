package com.yuki.yukihub.bigscreen;

import android.graphics.Color;
import android.net.Uri;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.yuki.yukihub.R;
import com.yuki.yukihub.model.Game;

import java.util.ArrayList;
import java.util.List;

/**
 * 大屏 shelf 行的卡片适配器（spec §S2-3）。
 *
 * <p>与主界面 {@code GameAdapter} 的区别：这张卡是**为焦点而生**的 ——
 * 焦点态用品牌粉描边 + 1.06 缩放（与 rail / 菜单统一），不做多选、不做长按菜单
 * （那些是触摸模式的职责）。
 */
public class BigScreenShelfAdapter extends RecyclerView.Adapter<BigScreenShelfAdapter.Holder> {

    public interface Listener {
        /** 焦点落在某张卡上（用于刷新背景层 / 信息条） */
        void onCardFocused(int position, Game game);

        /** 按下确认（启动游戏） */
        void onCardConfirmed(Game game);

        /** 长按卡片 = 打开详情层（触摸用户的 Ⓨ） */
        void onCardDetails(Game game);
    }

    private final List<Game> games = new ArrayList<>();
    private Listener listener;
    private int focusedPosition = -1;
    /** 是否播放强化动效（闪光 / 描边生长）；低性能档由 Activity 置 false */
    private boolean richEffects = true;
    /** 卡片大小倍率（M4-3） */
    private float cardScale = 1f;
    /** 由 BigScreenSizes 按屏幕算出的卡片像素尺寸（-1 = 用内置默认 × cardScale） */
    private int cardWPx = -1;
    private int cardHPx = -1;

    public void setCardSize(int wPx, int hPx) { this.cardWPx = wPx; this.cardHPx = hPx; }
    /** 是否显示卡片标题 */
    private boolean showTitles = true;
    /** 按键图标风格（M4-2） */
    private BigScreenKeys keys = new BigScreenKeys(BigScreenKeys.STYLE_XBOX);
    /** NSFW 封面模糊（与主库共用 nsfw_blur_enabled） */
    private boolean nsfwBlur = true;
    /** M18：焦点缩放幅度（0=不缩放，1=默认），由设置下发 —— 否则"焦点缩放"在大屏卡片上是假的 */
    private float focusScaleFactor = 1f;

    /** M18：设置下发焦点缩放幅度（百分比） */
    public void setFocusScalePercent(int percent) {
        focusScaleFactor = Math.max(0f, Math.min(1.5f, percent / 100f));
    }
    /** 卡片之间的水平间距（像素）；0 = 紧贴（那是之前的 bug） */
    private int gapPx = 0;
    /** 整排入场动效的时间戳（M9） */
    private long entranceAt = 0L;

    public void setGapPx(int gapPx) { this.gapPx = Math.max(0, gapPx); }

    public void setNsfwBlurEnabled(boolean enabled) { this.nsfwBlur = enabled; }

    public void setRichEffects(boolean richEffects) { this.richEffects = richEffects; }

    public void setCardScale(float cardScale) { this.cardScale = cardScale <= 0f ? 1f : cardScale; }

    public void setShowTitles(boolean showTitles) { this.showTitles = showTitles; }

    public void setKeys(BigScreenKeys keys) { this.keys = keys == null ? new BigScreenKeys(BigScreenKeys.STYLE_XBOX) : keys; }

    public void setListener(Listener listener) { this.listener = listener; }

    public void submit(List<Game> list) {
        games.clear();
        if (list != null) { games.addAll(list); }
        // 入场动效（M9）：换分类/首次进入时整排卡片错峰浮入
        entranceAt = System.currentTimeMillis();
        notifyDataSetChanged();
    }

    public Game getItem(int position) {
        if (position < 0 || position >= games.size()) { return null; }
        return games.get(position);
    }

    public int size() { return games.size(); }

    /** 设置焦点卡片（-1 = 本行无焦点） */
    /** 焦点变化的局部刷新标记（M14）：只更新焦点视觉，不重建整张卡片（不重新解码封面） */
    private static final Object PAYLOAD_FOCUS = new Object();

    public void setFocusedPosition(int position) {
        if (focusedPosition == position) { return; }
        int previous = focusedPosition;
        focusedPosition = position;
        // M14：卡顿真因之一 —— 之前用无 payload 的 notifyItemChanged，
        // 会走完整 onBindViewHolder（重新读封面、重设 LayoutParams），
        // 摇杆 80ms 连发时等于每 80ms 重建两张卡 → 明显卡顿。
        if (previous >= 0) { notifyItemChanged(previous, PAYLOAD_FOCUS); }
        if (focusedPosition >= 0) { notifyItemChanged(focusedPosition, PAYLOAD_FOCUS); }
    }

    public int focusedPosition() { return focusedPosition; }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_bs_card, parent, false);
        return new Holder(v);
    }

    /**
     * 只更新焦点视觉（M14）：焦点在卡片之间移动时走这条 —— 不重新解码封面、不动 LayoutParams。
     */
    @Override
    public void onBindViewHolder(@NonNull Holder h, int position, @NonNull List<Object> payloads) {
        if (!payloads.isEmpty() && payloads.contains(PAYLOAD_FOCUS)) {
            applyFocusVisual(h, position == focusedPosition);
            return;
        }
        super.onBindViewHolder(h, position, payloads);
    }

    /** 焦点视觉：压暗遮罩 + 描边背景 + 缩放 +（强化档）闪光与描边环 */
    private void applyFocusVisual(Holder h, boolean focused) {
        // 压暗（M9）：改成"盖一层深色遮罩"，而不是给 itemView 设 alpha（那会让整张卡片半透明）
        h.dim.animate().cancel();
        h.dim.animate().alpha(focused ? 0f : 0.34f).setDuration(140L).start();

        // 焦点态：粉描边背景 + 缩放（M14：1.06→1.045，配合更短的时长，摇杆快划时更稳）
        h.itemView.setBackgroundResource(focused
                ? R.drawable.bg_bs_cell_focused : R.drawable.bg_bs_cell);
        h.itemView.animate().cancel();
        h.itemView.animate()
                .scaleX(focused ? 1f + (1.045f - 1f) * focusScaleFactor : 1f)
                .scaleY(focused ? 1f + (1.045f - 1f) * focusScaleFactor : 1f)
                .setDuration(140L)
                .setInterpolator(new DecelerateInterpolator())
                .start();
        h.itemView.setZ(focused ? 6f : 0f);

        // 强化动效（M3）：闪光一下 + 描边环"生长"出来
        if (richEffects) {
            if (focused) {
                h.flash.setAlpha(0.30f);
                h.flash.animate().alpha(0f).setDuration(220L).start();
                h.ring.setAlpha(0f);
                h.ring.setScaleX(0.94f);
                h.ring.setScaleY(0.94f);
                h.ring.animate().alpha(1f).scaleX(1f).scaleY(1f)
                        .setDuration(200L)
                        .setInterpolator(new DecelerateInterpolator())
                        .start();
            } else {
                h.ring.animate().alpha(0f).setDuration(120L).start();
                h.flash.setAlpha(0f);
            }
        } else {
            h.flash.setAlpha(0f);
            h.ring.setAlpha(0f);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        final Game game = games.get(position);
        final boolean focused = position == focusedPosition;

        // 卡片大小：优先用 BigScreenSizes 按屏幕算出的尺寸，再乘用户设置的倍率
        ViewGroup.LayoutParams lp = h.itemView.getLayoutParams();
        int baseW = cardWPx > 0 ? cardWPx : dp(h.itemView, CARD_W_DP);
        int baseH = cardHPx > 0 ? cardHPx : dp(h.itemView, CARD_H_DP);
        int w = Math.round(baseW * cardScale);
        int ht = Math.round(baseH * cardScale);
        if (lp != null && (lp.width != w || lp.height != ht)) {
            lp.width = w;
            lp.height = ht;
            h.itemView.setLayoutParams(lp);
        }
        // 卡片间距：之前完全没设 margin，所以卡与卡是紧贴的
        if (lp instanceof RecyclerView.LayoutParams) {
            RecyclerView.LayoutParams rlp = (RecyclerView.LayoutParams) lp;
            if (rlp.rightMargin != gapPx) {
                rlp.rightMargin = gapPx;
                h.itemView.setLayoutParams(rlp);
            }
        }

        h.title.setVisibility(showTitles ? View.VISIBLE : View.GONE);
        h.title.setText(TextUtils.isEmpty(game.title) ? "未命名" : game.title);

        // 封面：优先 coverPersistUri（持久化授权），退回 coverUri
        String uriStr = !TextUtils.isEmpty(game.coverPersistUri) ? game.coverPersistUri : game.coverUri;
        boolean hasCover = false;
        final boolean needBlur = game.nsfw && nsfwBlur;
        if (!TextUtils.isEmpty(uriStr) && needBlur) {
            // NSFW：只显示 🔞 占位，模糊图算好再换上（算不出来就一直是 🔞，绝不露原图）
            final String cacheKey = NsfwBlur.cacheKey("bs_nsfw", game.id, uriStr);
            h.cover.setTag(R.id.tag_remote_image_request, cacheKey);
            h.cover.setImageDrawable(null);
            h.cover.setVisibility(View.GONE);
            h.placeholder.setVisibility(View.VISIBLE);
            h.placeholder.setText("🔞");
            NsfwBlur.load(h.itemView, uriStr, cacheKey, 4, 22f, bmp -> {
                if (bmp == null) { return; }
                // tag 校验：ViewHolder 可能已被复用给别的卡片
                if (!cacheKey.equals(h.cover.getTag(R.id.tag_remote_image_request))) { return; }
                h.cover.setImageBitmap(bmp);
                h.cover.setVisibility(View.VISIBLE);
                h.placeholder.setVisibility(View.GONE);
            });
            hasCover = false;   // 占位态在下面统一处理，避免把 🔞 覆盖成首字
        } else if (!TextUtils.isEmpty(uriStr)) {
            h.cover.setTag(R.id.tag_remote_image_request, "bs:" + game.id + ":" + System.nanoTime());
            try {
                h.cover.setImageURI(Uri.parse(uriStr));
                hasCover = h.cover.getDrawable() != null;
            } catch (Throwable ignored) { }
        }
        if (!needBlur) {
            h.cover.setVisibility(hasCover ? View.VISIBLE : View.GONE);
            h.placeholder.setVisibility(hasCover ? View.GONE : View.VISIBLE);
            if (!hasCover) {
                String t = TextUtils.isEmpty(game.title) ? "?" : game.title.substring(0, 1);
                h.placeholder.setText(t);
            }
        }

        // 状态点颜色（游玩中=绿 / 已完成=蓝 / 未游玩=灰）
        int dotColor;
        String status = game.playStatus == null ? "unplayed" : game.playStatus;
        switch (status) {
            case "playing":   dotColor = ContextCompat.getColor(h.itemView.getContext(), R.color.bs_success); break;
            case "completed": dotColor = ContextCompat.getColor(h.itemView.getContext(), R.color.bs_primary); break;
            default:          dotColor = Color.parseColor("#5A6485"); break;
        }
        h.dot.getBackground().setTint(dotColor);

        h.fav.setVisibility(game.favorite ? View.VISIBLE : View.GONE);
        if (game.favorite) {
            h.fav.setColorFilter(ContextCompat.getColor(h.itemView.getContext(), R.color.bs_focus));
        }
        h.nsfw.setVisibility(game.nsfw ? View.VISIBLE : View.GONE);
        // 焦点卡浮出按键徽章 —— **M8 起不再显示**（用户明确要求：封面上不要出现"启动"按钮）
        h.badge.setVisibility(View.GONE);
        h.scrim.setVisibility(showTitles ? View.VISIBLE : View.GONE);
        applyFocusVisual(h, focused);
        // 入场动效（M9）：整排卡片错峰浮入（换分类/首次进入时会看到）
        long sinceEntrance = System.currentTimeMillis() - entranceAt;
        if (entranceAt > 0 && sinceEntrance < 800L && position < 14) {
            h.itemView.setAlpha(0f);
            h.itemView.setTranslationY(dp(h.itemView, 20));
            h.itemView.animate().alpha(1f).translationY(0f)
                    .setStartDelay(position * 42L)
                    .setDuration(300L)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
        } else {
            h.itemView.setAlpha(1f);
            h.itemView.setTranslationY(0f);
        }


        /** 触摸/鼠标单击卡片：未选中则先选中；已选中则启动（手柄 Ⓐ 的行为） */
        final int pos = h.getLayoutPosition();
        h.itemView.setOnClickListener(v -> {
            if (listener == null || pos == RecyclerView.NO_POSITION) { return; }
            if (focusedPosition != pos) {
                // 注意：这里**不要**自己改 focusedPosition / notifyItemChanged ——
                // Activity 收到回调后会走 FocusEngine → setFocusedPosition(同一个值)，
                // 于是 early-return，旧卡的焦点视觉永远清不掉（就是"选中状态永久存在"）。
                BigScreenSound.tick();          // M12：触摸也要有音效
                listener.onCardFocused(pos, game);
            } else {
                BigScreenSound.confirm();
                listener.onCardConfirmed(game);
            }
        });
        // 长按卡片 = 打开详情层（触摸用户的 Ⓨ）
        h.itemView.setOnLongClickListener(v -> {
            if (listener == null || pos == RecyclerView.NO_POSITION) { return true; }
            BigScreenSound.open();              // M12
            if (focusedPosition != pos) {
                listener.onCardFocused(pos, game);   // 同上，焦点统一交给 Activity 驱动
            }
            listener.onCardDetails(game);
            return true;
        });
    }

    @Override
    public int getItemCount() { return games.size(); }

    private static int dp(View v, float value) {
        return Math.round(value * v.getResources().getDisplayMetrics().density);
    }

    private static final float CARD_W_DP = 168f;
    private static final float CARD_H_DP = 224f;

    static class Holder extends RecyclerView.ViewHolder {
        final ImageView cover;
        final TextView placeholder;
        final TextView title;
        final View dot;
        final ImageView fav;
        final TextView nsfw;
        final TextView badge;
        final View scrim;
        final View dim;
        final View flash;
        final View ring;

        Holder(@NonNull View itemView) {
            super(itemView);
            cover = itemView.findViewById(R.id.bsCardCover);
            placeholder = itemView.findViewById(R.id.bsCardPlaceholder);
            title = itemView.findViewById(R.id.bsCardTitle);
            dot = itemView.findViewById(R.id.bsCardDot);
            fav = itemView.findViewById(R.id.bsCardFav);
            nsfw = itemView.findViewById(R.id.bsCardNsfw);
            badge = itemView.findViewById(R.id.bsCardBadge);
            scrim = itemView.findViewById(R.id.bsCardScrim);
            dim = itemView.findViewById(R.id.bsCardDim);
            flash = itemView.findViewById(R.id.bsCardFlash);
            ring = itemView.findViewById(R.id.bsCardRing);
        }
    }
}