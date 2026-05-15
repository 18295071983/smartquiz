package com.oilquiz.app.ui.animation;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;

import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.RecyclerView;

/**
 * RecyclerView 项目动画工具类
 * 提供多种动画效果：淡入、滑入、缩放、旋转、组合动画
 */
public class RecyclerViewAnimations {

    // ==================== 动画持续时间 ====================
    public static final long DURATION_SHORT = 150;
    public static final long DURATION_MEDIUM = 300;
    public static final long DURATION_LONG = 500;

    // ==================== 默认 ItemAnimator ====================
    
    /**
     * 获取默认的淡入动画
     */
    public static RecyclerView.ItemAnimator getFadeInAnimator() {
        return new FadeInAnimator();
    }
    
    /**
     * 获取滑入动画
     */
    public static RecyclerView.ItemAnimator getSlideInAnimator() {
        return new SlideInAnimator();
    }
    
    /**
     * 获取缩放动画
     */
    public static RecyclerView.ItemAnimator getScaleAnimator() {
        return new ScaleInAnimator();
    }
    
    /**
     * 获取组合动画（淡入+缩放+滑入）
     */
    public static RecyclerView.ItemAnimator getComboAnimator() {
        return new ComboAnimator();
    }

    // ==================== 淡入动画 ====================
    
    public static class FadeInAnimator extends DefaultItemAnimator {
        
        @Override
        public boolean animateAdd(RecyclerView.ViewHolder holder) {
            holder.itemView.setAlpha(0f);
            holder.itemView.animate()
                .alpha(1f)
                .setDuration(getAddDuration())
                .setInterpolator(new DecelerateInterpolator())
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        dispatchAddFinished(holder);
                    }
                    
                    @Override
                    public void onAnimationCancel(Animator animation) {
                        holder.itemView.setAlpha(1f);
                    }
                })
                .start();
            return true;
        }
        
        @Override
        public boolean animateRemove(RecyclerView.ViewHolder holder) {
            holder.itemView.animate()
                .alpha(0f)
                .setDuration(getRemoveDuration())
                .setInterpolator(new AccelerateDecelerateInterpolator())
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        dispatchRemoveFinished(holder);
                        holder.itemView.setAlpha(1f);
                    }
                    
                    @Override
                    public void onAnimationCancel(Animator animation) {
                        holder.itemView.setAlpha(1f);
                    }
                })
                .start();
            return true;
        }
    }

    // ==================== 滑入动画 ====================
    
    public static class SlideInAnimator extends DefaultItemAnimator {
        
        @Override
        public boolean animateAdd(RecyclerView.ViewHolder holder) {
            holder.itemView.setTranslationX(-holder.itemView.getWidth());
            holder.itemView.setAlpha(0f);
            holder.itemView.animate()
                .translationX(0)
                .alpha(1f)
                .setDuration(getAddDuration())
                .setInterpolator(new DecelerateInterpolator())
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        dispatchAddFinished(holder);
                    }
                    
                    @Override
                    public void onAnimationCancel(Animator animation) {
                        holder.itemView.setTranslationX(0);
                        holder.itemView.setAlpha(1f);
                    }
                })
                .start();
            return true;
        }
        
        @Override
        public boolean animateRemove(RecyclerView.ViewHolder holder) {
            holder.itemView.animate()
                .translationX(holder.itemView.getWidth())
                .alpha(0f)
                .setDuration(getRemoveDuration())
                .setInterpolator(new AccelerateDecelerateInterpolator())
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        dispatchRemoveFinished(holder);
                        holder.itemView.setTranslationX(0);
                        holder.itemView.setAlpha(1f);
                    }
                    
                    @Override
                    public void onAnimationCancel(Animator animation) {
                        holder.itemView.setTranslationX(0);
                        holder.itemView.setAlpha(1f);
                    }
                })
                .start();
            return true;
        }
    }

    // ==================== 缩放动画 ====================
    
    public static class ScaleInAnimator extends DefaultItemAnimator {
        
        @Override
        public boolean animateAdd(RecyclerView.ViewHolder holder) {
            holder.itemView.setScaleX(0.8f);
            holder.itemView.setScaleY(0.8f);
            holder.itemView.setAlpha(0f);
            
            AnimatorSet set = new AnimatorSet();
            ObjectAnimator scaleX = ObjectAnimator.ofFloat(holder.itemView, "scaleX", 0.8f, 1f);
            ObjectAnimator scaleY = ObjectAnimator.ofFloat(holder.itemView, "scaleY", 0.8f, 1f);
            ObjectAnimator alpha = ObjectAnimator.ofFloat(holder.itemView, "alpha", 0f, 1f);
            
            set.playTogether(scaleX, scaleY, alpha);
            set.setDuration(getAddDuration());
            set.setInterpolator(new OvershootInterpolator(1.2f));
            set.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    dispatchAddFinished(holder);
                }
                
                @Override
                public void onAnimationCancel(Animator animation) {
                    holder.itemView.setScaleX(1f);
                    holder.itemView.setScaleY(1f);
                    holder.itemView.setAlpha(1f);
                }
            });
            set.start();
            return true;
        }
        
        @Override
        public boolean animateRemove(RecyclerView.ViewHolder holder) {
            holder.itemView.animate()
                .scaleX(0.8f)
                .scaleY(0.8f)
                .alpha(0f)
                .setDuration(getRemoveDuration())
                .setInterpolator(new AccelerateDecelerateInterpolator())
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        dispatchRemoveFinished(holder);
                        holder.itemView.setScaleX(1f);
                        holder.itemView.setScaleY(1f);
                        holder.itemView.setAlpha(1f);
                    }
                    
                    @Override
                    public void onAnimationCancel(Animator animation) {
                        holder.itemView.setScaleX(1f);
                        holder.itemView.setScaleY(1f);
                        holder.itemView.setAlpha(1f);
                    }
                })
                .start();
            return true;
        }
    }

    // ==================== 组合动画 ====================
    
    public static class ComboAnimator extends DefaultItemAnimator {
        
        @Override
        public boolean animateAdd(RecyclerView.ViewHolder holder) {
            holder.itemView.setTranslationX(-holder.itemView.getWidth());
            holder.itemView.setTranslationY(50f);
            holder.itemView.setScaleX(0.9f);
            holder.itemView.setScaleY(0.9f);
            holder.itemView.setAlpha(0f);
            
            AnimatorSet set = new AnimatorSet();
            ObjectAnimator transX = ObjectAnimator.ofFloat(holder.itemView, "translationX", -holder.itemView.getWidth(), 0);
            ObjectAnimator transY = ObjectAnimator.ofFloat(holder.itemView, "translationY", 50f, 0);
            ObjectAnimator scaleX = ObjectAnimator.ofFloat(holder.itemView, "scaleX", 0.9f, 1f);
            ObjectAnimator scaleY = ObjectAnimator.ofFloat(holder.itemView, "scaleY", 0.9f, 1f);
            ObjectAnimator alpha = ObjectAnimator.ofFloat(holder.itemView, "alpha", 0f, 1f);
            
            set.playTogether(transX, transY, scaleX, scaleY, alpha);
            set.setDuration(getAddDuration());
            set.setInterpolator(new DecelerateInterpolator());
            set.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    dispatchAddFinished(holder);
                }
                
                @Override
                public void onAnimationCancel(Animator animation) {
                    clearAnimations(holder.itemView);
                }
            });
            set.start();
            return true;
        }
        
        @Override
        public boolean animateRemove(RecyclerView.ViewHolder holder) {
            AnimatorSet set = new AnimatorSet();
            ObjectAnimator transX = ObjectAnimator.ofFloat(holder.itemView, "translationX", 0, holder.itemView.getWidth());
            ObjectAnimator transY = ObjectAnimator.ofFloat(holder.itemView, "translationY", 0, -50f);
            ObjectAnimator scaleX = ObjectAnimator.ofFloat(holder.itemView, "scaleX", 1f, 0.9f);
            ObjectAnimator scaleY = ObjectAnimator.ofFloat(holder.itemView, "scaleY", 1f, 0.9f);
            ObjectAnimator alpha = ObjectAnimator.ofFloat(holder.itemView, "alpha", 1f, 0f);
            
            set.playTogether(transX, transY, scaleX, scaleY, alpha);
            set.setDuration(getRemoveDuration());
            set.setInterpolator(new AccelerateDecelerateInterpolator());
            set.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    dispatchRemoveFinished(holder);
                    clearAnimations(holder.itemView);
                }
                
                @Override
                public void onAnimationCancel(Animator animation) {
                    clearAnimations(holder.itemView);
                }
            });
            set.start();
            return true;
        }
        
        private void clearAnimations(View view) {
            view.setTranslationX(0);
            view.setTranslationY(0);
            view.setScaleX(1f);
            view.setScaleY(1f);
            view.setAlpha(1f);
        }
    }

    // ==================== 静态动画方法 ====================
    
    /**
     * 为视图添加淡入动画
     */
    public static void fadeIn(View view, long duration, Runnable onEnd) {
        view.setAlpha(0f);
        view.setVisibility(View.VISIBLE);
        view.animate()
            .alpha(1f)
            .setDuration(duration)
            .setInterpolator(new DecelerateInterpolator())
            .withEndAction(onEnd != null ? onEnd : () -> {})
            .start();
    }
    
    /**
     * 为视图添加淡出动画
     */
    public static void fadeOut(View view, long duration, Runnable onEnd) {
        view.animate()
            .alpha(0f)
            .setDuration(duration)
            .setInterpolator(new AccelerateDecelerateInterpolator())
            .withEndAction(() -> {
                view.setVisibility(View.GONE);
                if (onEnd != null) onEnd.run();
            })
            .start();
    }
    
    /**
     * 为视图添加缩放弹跳动画
     */
    public static void bounce(View view) {
        view.setScaleX(0.8f);
        view.setScaleY(0.8f);
        
        AnimatorSet set = new AnimatorSet();
        ObjectAnimator scaleX = ObjectAnimator.ofFloat(view, "scaleX", 0.8f, 1.1f, 1f);
        ObjectAnimator scaleY = ObjectAnimator.ofFloat(view, "scaleY", 0.8f, 1.1f, 1f);
        
        set.playTogether(scaleX, scaleY);
        set.setDuration(DURATION_MEDIUM);
        set.setInterpolator(new OvershootInterpolator());
        set.start();
    }
    
    /**
     * 为视图添加滑入动画（从底部）
     */
    public static void slideUp(View view, long duration) {
        view.setTranslationY(view.getHeight());
        view.setAlpha(0f);
        view.setVisibility(View.VISIBLE);
        
        view.animate()
            .translationY(0)
            .alpha(1f)
            .setDuration(duration)
            .setInterpolator(new DecelerateInterpolator())
            .start();
    }
    
    /**
     * 为视图添加点击反馈动画
     */
    public static void clickFeedback(View view) {
        AnimatorSet set = new AnimatorSet();
        ObjectAnimator scaleDownX = ObjectAnimator.ofFloat(view, "scaleX", 1f, 0.95f);
        ObjectAnimator scaleDownY = ObjectAnimator.ofFloat(view, "scaleY", 1f, 0.95f);
        ObjectAnimator scaleUpX = ObjectAnimator.ofFloat(view, "scaleX", 0.95f, 1f);
        ObjectAnimator scaleUpY = ObjectAnimator.ofFloat(view, "scaleY", 0.95f, 1f);
        
        scaleDownX.setDuration(100);
        scaleDownY.setDuration(100);
        scaleUpX.setDuration(100);
        scaleUpY.setDuration(100);
        
        set.play(scaleDownX).with(scaleDownY);
        set.play(scaleUpX).with(scaleUpY).after(scaleDownX);
        set.start();
    }
}
