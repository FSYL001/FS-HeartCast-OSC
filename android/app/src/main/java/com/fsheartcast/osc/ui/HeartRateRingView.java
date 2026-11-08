package com.fsheartcast.osc.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import com.fsheartcast.osc.R;

/**
 * 心率环：底环 + 渐变进度弧 + 中心柔光 + 每次心跳的扩散脉冲。
 * 纯 Canvas 绘制，无第三方库。
 */
public final class HeartRateRingView extends View {

    private static final int MAX_BPM = 200;

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint progressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pulsePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arcRect = new RectF();

    private int trackColor;
    private int accentColor;
    private int heartColor;
    private float strokeWidth;

    private float fraction;        // 当前绘制进度 0..1
    private float targetFraction;  // 目标进度 0..1
    private float pulse;           // 0..1，心跳脉冲进度
    private boolean pulseRunning;

    private ValueAnimator sweepAnimator;
    private ValueAnimator pulseAnimator;

    public HeartRateRingView(Context context) {
        this(context, null);
    }

    public HeartRateRingView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public HeartRateRingView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setWillNotDraw(false);

        accentColor = resolveThemeColor(context, R.attr.accent);
        heartColor = resolveThemeColor(context, R.attr.heartRate);
        trackColor = resolveThemeColor(context, R.attr.hairline);
        strokeWidth = dp(11);

        trackPaint.setStyle(Paint.Style.STROKE);
        trackPaint.setStrokeWidth(strokeWidth);
        trackPaint.setColor(trackColor);

        progressPaint.setStyle(Paint.Style.STROKE);
        progressPaint.setStrokeWidth(strokeWidth);
        progressPaint.setStrokeCap(Paint.Cap.ROUND);

        pulsePaint.setStyle(Paint.Style.STROKE);
        pulsePaint.setStrokeCap(Paint.Cap.ROUND);

        glowPaint.setStyle(Paint.Style.FILL);
    }

    private static int resolveThemeColor(Context context, int attr) {
        TypedValue value = new TypedValue();
        if (context.getTheme().resolveAttribute(attr, value, true)) {
            return value.data;
        }
        return 0xFF888888;
    }

    private float dp(float value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics());
    }

    /** 主题切换后需要重新取色（Activity 会重建，这里主要给保险用）。 */
    public void refreshThemeColors() {
        accentColor = resolveThemeColor(getContext(), R.attr.accent);
        heartColor = resolveThemeColor(getContext(), R.attr.heartRate);
        trackColor = resolveThemeColor(getContext(), R.attr.hairline);
        trackPaint.setColor(trackColor);
        progressPaint.setShader(null);
        glowPaint.setShader(null);
        invalidate();
    }

    /**
     * 更新心率。
     *
     * @param bpm      0 表示无数据
     * @param animate  是否用动画过渡
     */
    public void setBpm(int bpm, boolean animate) {
        float target = bpm <= 0 ? 0f : Math.min(1f, bpm / (float) MAX_BPM);
        targetFraction = target;

        if (sweepAnimator != null) {
            sweepAnimator.cancel();
        }

        if (!animate) {
            fraction = target;
            invalidate();
            return;
        }

        sweepAnimator = ValueAnimator.ofFloat(fraction, target);
        sweepAnimator.setDuration(560);
        sweepAnimator.setInterpolator(new DecelerateInterpolator(1.6f));
        sweepAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                fraction = (Float) animation.getAnimatedValue();
                invalidate();
            }
        });
        sweepAnimator.start();
    }

    /** 每次收到心跳时打一下脉冲。 */
    public void pulse() {
        if (pulseRunning) {
            return;
        }
        pulseRunning = true;
        if (pulseAnimator != null) {
            pulseAnimator.cancel();
        }
        pulseAnimator = ValueAnimator.ofFloat(0f, 1f);
        pulseAnimator.setDuration(700);
        pulseAnimator.setInterpolator(new DecelerateInterpolator(1.2f));
        pulseAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                pulse = (Float) animation.getAnimatedValue();
                invalidate();
            }
        });
        pulseAnimator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                pulseRunning = false;
                pulse = 0f;
                invalidate();
            }
        });
        pulseAnimator.start();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w <= 0 || h <= 0) {
            return;
        }
        float inset = strokeWidth / 2f + dp(6);
        arcRect.set(inset, inset, w - inset, h - inset);

        float cx = w / 2f;
        float cy = h / 2f;
        float radius = Math.min(w, h) / 2f;

        // 中心柔光（半径必须严格 > 0）
        float glowRadius = radius * 0.72f;
        if (glowRadius > 0.01f) {
            glowPaint.setShader(new RadialGradient(cx, cy, glowRadius,
                    new int[] { withAlpha(accentColor, 26), 0x00000000 },
                    new float[] { 0f, 1f }, Shader.TileMode.CLAMP));
        }

        updateGradient();
    }

    private void updateGradient() {
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        SweepGradient gradient = new SweepGradient(cx, cy,
                new int[] { accentColor, heartColor, accentColor },
                new float[] { 0f, 0.45f, 1f });
        android.graphics.Matrix matrix = new android.graphics.Matrix();
        matrix.setRotate(-90f, cx, cy);
        gradient.setLocalMatrix(matrix);
        progressPaint.setShader(gradient);
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;

        // 中心柔光
        if (glowPaint.getShader() != null) {
            canvas.drawCircle(cx, cy, Math.min(getWidth(), getHeight()) / 2f, glowPaint);
        }

        // 底环
        canvas.drawArc(arcRect, 0f, 360f, false, trackPaint);

        // 进度弧
        if (fraction > 0.001f) {
            canvas.drawArc(arcRect, -90f, 360f * fraction, false, progressPaint);
        }

        // 心跳脉冲：向外扩散并淡出的一圈
        if (pulse > 0f && fraction > 0.001f) {
            float grow = dp(2) + dp(9) * pulse;
            RectF pulseRect = new RectF(arcRect);
            pulseRect.inset(-grow, -grow);
            pulsePaint.setStrokeWidth(dp(2.4f));
            int alpha = (int) (110 * (1f - pulse));
            pulsePaint.setColor(withAlpha(heartColor, Math.max(0, alpha)));
            canvas.drawArc(pulseRect, -90f, 360f * fraction, false, pulsePaint);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        if (sweepAnimator != null) {
            sweepAnimator.cancel();
        }
        if (pulseAnimator != null) {
            pulseAnimator.cancel();
        }
        super.onDetachedFromWindow();
    }
}
