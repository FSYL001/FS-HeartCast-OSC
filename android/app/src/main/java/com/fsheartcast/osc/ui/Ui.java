package com.fsheartcast.osc.ui;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LayoutAnimationController;
import android.view.animation.AnimationUtils;

import com.fsheartcast.osc.R;

/** 一些小的动效/取色工具，保持各处交互手感一致。 */
public final class Ui {

    private Ui() {
    }

    public static int dp(Context context, float value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics());
    }

    public static int color(Context context, int attr) {
        TypedValue value = new TypedValue();
        if (context.getTheme().resolveAttribute(attr, value, true)) {
            return value.data;
        }
        return 0xFF888888;
    }

    /** 按压回弹：0.97 → 1，和系统触感接近，不夸张。 */
    public static void pressAnimation(final View view) {
        view.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        v.animate().scaleX(0.97f).scaleY(0.97f).setDuration(90).start();
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        v.animate().scaleX(1f).scaleY(1f).setDuration(160)
                                .setInterpolator(new DecelerateInterpolator()).start();
                        break;
                    default:
                        break;
                }
                return false;
            }
        });
    }

    /** 列表子项依次淡入上浮。 */
    public static void staggerChildren(ViewGroup group) {
        LayoutAnimationController controller = new LayoutAnimationController(
                AnimationUtils.loadAnimation(group.getContext(), R.anim.item_in), 0.05f);
        group.setLayoutAnimation(controller);
        group.scheduleLayoutAnimation();
    }

    /** 颜色过渡。 */
    public static void animateColor(int from, int to, final ColorSink sink, long duration) {
        ValueAnimator animator = ValueAnimator.ofObject(new ArgbEvaluator(), from, to);
        animator.setDuration(duration);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                sink.accept(((Integer) animation.getAnimatedValue()).intValue());
            }
        });
        animator.start();
    }

    public interface ColorSink {
        void accept(int color);
    }

    /** 生成一个纯色圆形 drawable（强调色圆点用）。 */
    public static GradientDrawable circle(int color, int sizePx) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.OVAL);
        drawable.setColor(color);
        drawable.setSize(sizePx, sizePx);
        return drawable;
    }

    /** 生成一个圆环 drawable（选中强调色外圈）。 */
    public static GradientDrawable ring(int color, int strokePx) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.OVAL);
        drawable.setColor(0x00000000);
        drawable.setStroke(strokePx, color);
        return drawable;
    }

    /** 生成一个纯色圆角矩形 drawable（标签底块/背景用）。 */
    public static GradientDrawable pill(int color, float radiusPx) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setColor(color);
        drawable.setCornerRadius(radiusPx);
        return drawable;
    }
}
