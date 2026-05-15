package com.oilquiz.app.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.card.MaterialCardView;
import com.oilquiz.app.R;
import com.oilquiz.app.ai.chat.ModeInfo;

import android.graphics.drawable.GradientDrawable;

public class ChatModeAdapter extends RecyclerView.Adapter<ChatModeAdapter.ModeViewHolder> {

    public interface OnModeSelectedListener {
        void onModeSelected(ModeInfo mode);
    }

    private final java.util.List<ModeInfo> modes;
    private final OnModeSelectedListener listener;
    private int selectedPosition = 0;
    private boolean isAnimating = false;

    public ChatModeAdapter(java.util.List<ModeInfo> modes, OnModeSelectedListener listener) {
        this.modes = modes;
        this.listener = listener;
    }

    @NonNull
    @Override
    public ModeViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
            .inflate(R.layout.item_chat_mode, parent, false);
        return new ModeViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ModeViewHolder holder, int position) {
        ModeInfo mode = modes.get(position);
        holder.bind(mode, position == selectedPosition);

        holder.itemView.setOnClickListener(v -> {
            if (isAnimating || position == selectedPosition) return;

            isAnimating = true;
            int previousSelected = selectedPosition;
            selectedPosition = position;

            holder.animateModeTransition(previousSelected, position, () -> {
                isAnimating = false;
                if (listener != null) {
                    listener.onModeSelected(mode);
                }
            });
        });
    }

    @Override
    public int getItemCount() {
        return modes.size();
    }

    public void setSelectedMode(String modeId) {
        for (int i = 0; i < modes.size(); i++) {
            if (modes.get(i).id.equals(modeId)) {
                selectedPosition = i;
                notifyDataSetChanged();
                break;
            }
        }
    }

    public ModeInfo getSelectedMode() {
        if (selectedPosition >= 0 && selectedPosition < modes.size()) {
            return modes.get(selectedPosition);
        }
        return null;
    }

    static class ModeViewHolder extends RecyclerView.ViewHolder {
        private final MaterialCardView cardView;
        private final TextView modeIcon;
        private final TextView modeName;
        private final View selectedIndicator;

        ModeViewHolder(@NonNull View itemView) {
            super(itemView);
            cardView = itemView.findViewById(R.id.mode_card);
            modeIcon = itemView.findViewById(R.id.mode_icon);
            modeName = itemView.findViewById(R.id.mode_name);
            selectedIndicator = itemView.findViewById(R.id.selected_indicator);
        }

        void bind(ModeInfo mode, boolean isSelected) {
            modeIcon.setText(mode.icon);
            modeName.setText(mode.name);
            updateSelectionUI(isSelected, false);

            itemView.setOnLongClickListener(v -> {
                showModeTooltip(v, mode);
                return true;
            });
        }

        void updateSelectionUI(boolean isSelected, boolean animate) {
            if (isSelected) {
                cardView.setCardBackgroundColor(itemView.getContext()
                    .getColor(R.color.mode_selected_background));
                cardView.setStrokeColor(itemView.getContext()
                    .getColor(R.color.mode_selected_border));
                cardView.setStrokeWidth(2);
                selectedIndicator.setVisibility(View.VISIBLE);

                if (animate) {
                    cardView.animate()
                        .scaleX(1.05f)
                        .scaleY(1.05f)
                        .alpha(1.0f)
                        .setDuration(250)
                        .setInterpolator(new DecelerateInterpolator())
                        .start();

                    selectedIndicator.animate()
                        .alpha(1.0f)
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .setDuration(200)
                        .setInterpolator(new DecelerateInterpolator())
                        .start();
                } else {
                    cardView.setScaleX(1.05f);
                    cardView.setScaleY(1.05f);
                }
            } else {
                cardView.setCardBackgroundColor(itemView.getContext()
                    .getColor(R.color.mode_unselected_background));
                cardView.setStrokeColor(itemView.getContext()
                    .getColor(R.color.mode_unselected_border));
                cardView.setStrokeWidth(1);

                if (animate) {
                    cardView.animate()
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .setDuration(200)
                        .setInterpolator(new DecelerateInterpolator())
                        .start();

                    selectedIndicator.animate()
                        .alpha(0.0f)
                        .scaleX(0.5f)
                        .scaleY(0.5f)
                        .setDuration(150)
                        .withEndAction(() -> selectedIndicator.setVisibility(View.GONE))
                        .start();
                } else {
                    cardView.setScaleX(1.0f);
                    cardView.setScaleY(1.0f);
                    selectedIndicator.setVisibility(View.GONE);
                }
            }
        }

        void animateModeTransition(int fromPosition, int toPosition, Runnable onComplete) {
            updateSelectionUI(fromPosition == getAdapterPosition(), true);

            RecyclerView recyclerView = (RecyclerView) itemView.getParent();
            RecyclerView.Adapter<?> adapter = recyclerView != null ? recyclerView.getAdapter() : null;

            itemView.postDelayed(() -> {
                if (adapter != null) {
                    adapter.notifyItemChanged(fromPosition);
                    adapter.notifyItemChanged(toPosition);
                }

                itemView.postDelayed(() -> {
                    if (onComplete != null) {
                        onComplete.run();
                    }
                }, 100);
            }, 150);
        }

        private void showModeTooltip(View anchor, ModeInfo mode) {
            LinearLayout tooltipLayout = new LinearLayout(anchor.getContext());
            tooltipLayout.setOrientation(LinearLayout.VERTICAL);
            tooltipLayout.setPadding(24, 16, 24, 16);

            GradientDrawable background = new GradientDrawable();
            background.setColor(0xFF2D3748);
            background.setCornerRadius(12);
            tooltipLayout.setBackground(background);

            TextView titleView = new TextView(anchor.getContext());
            titleView.setText(mode.icon + " " + mode.name);
            titleView.setTextSize(16);
            titleView.setTextColor(0xFFFFFFFF);
            titleView.setTypeface(null, android.graphics.Typeface.BOLD);

            TextView descView = new TextView(anchor.getContext());
            descView.setText(mode.description);
            descView.setTextSize(13);
            descView.setTextColor(0xFFE2E8F0);
            descView.setLineSpacing(4, 1.0f);
            descView.setMaxWidth((int) (280 * anchor.getResources().getDisplayMetrics().density));

            tooltipLayout.addView(titleView);
            tooltipLayout.addView(descView);

            PopupWindow popupWindow = new PopupWindow(tooltipLayout,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
            popupWindow.setFocusable(true);
            popupWindow.setOutsideTouchable(true);
            popupWindow.setElevation(10);

            int[] location = new int[2];
            anchor.getLocationOnScreen(location);

            int offsetX = (int) ((anchor.getWidth() - 280 * anchor.getResources().getDisplayMetrics().density) / 2);
            if (offsetX < 0) offsetX = 0;

            popupWindow.showAsDropDown(anchor, offsetX, -(anchor.getHeight() + 20));

            itemView.postDelayed(() -> {
                if (popupWindow.isShowing()) {
                    popupWindow.dismiss();
                }
            }, 3000);
        }
    }
}
