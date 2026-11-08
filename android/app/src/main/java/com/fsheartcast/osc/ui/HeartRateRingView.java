package com.fsheartcast.osc.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;

import com.fsheartcast.osc.R;

/**
 * 专业心率仪表盘：
 * 1. 外圈 60 格精密刻度圈（根据心率进度动态点亮）
 * 2. 心率区间智能动态渐变（静息/正常/燃脂/极限）
 * 3. 弧线末端高光光斑（Glowing Bead）
 * 4. 逐拍心跳扩散脉冲与中心柔光
 * 5. 下方集成动态平滑心电律动波形（ECG Rhythm Wave）
 */
public final class HeartRateRingView extends View {

    private static final int MAX_BPM = 220;
    private static final int NUM_TICKS = 60;

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint progressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pulsePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint headPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ecgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF arcRect = new RectF();
    private final Path ecgPath = new Path();

    private int trackColor;
    private int accentColor;
    private int heartColor;
    private int activeZoneColor;
    private float strokeWidth;
    private float tickLength;

    private int currentBpm = 0;
    private float fraction;        // 当前绘制进度 0..1
    private float targetFraction;  // 目标进度 0..1
    private float pulse;           // 0..1，心跳脉冲进度
    private boolean pulseRunning;
    private float ecgPhase = 0f;

    private ValueAnimator sweepAnimator;
    private ValueAnimator pulseAnimator;
    private ValueAnimator ecgAnimator;

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
        activeZoneColor = accentColor;

        strokeWidth = dp(10);
        tickLength = dp(5.5f);

        trackPaint.setStyle(Paint.Style.STROKE);
        trackPaint.setStrokeWidth(strokeWidth);
        trackPaint.setColor(trackColor);

        progressPaint.setStyle(Paint.Style.STROKE);
        progressPaint.setStrokeWidth(strokeWidth);
        progressPaint.setStrokeCap(Paint.Cap.ROUND);

        tickPaint.setStyle(Paint.Style.STROKE);
        tickPaint.setStrokeWidth(dp(1.8f));
        tickPaint.setStrokeCap(Paint.Cap.ROUND);

        pulsePaint.setStyle(Paint.Style.STROKE);
        pulsePaint.setStrokeCap(Paint.Cap.ROUND);

        glowPaint.setStyle(Paint.Style.FILL);

        headPaint.setStyle(Paint.Style.FILL);

        ecgPaint.setStyle(Paint.Style.STROKE);
        ecgPaint.setStrokeWidth(dp(2.0f));
        ecgPaint.setStrokeCap(Paint.Cap.ROUND);
        ecgPaint.setStrokeJoin(Paint.Join.ROUND);

        startEcgLoop();
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

    public void refreshThemeColors() {
        accentColor = resolveThemeColor(getContext(), R.attr.accent);
        heartColor = resolveThemeColor(getContext(), R.attr.heartRate);
        trackColor = resolveThemeColor(getContext(), R.attr.hairline);
        trackPaint.setColor(trackColor);
        progressPaint.setShader(null);
        glowPaint.setShader(null);
        updateZoneColors();
        invalidate();
    }

    /**
     * 更新心率数值并触发平滑扫弧过渡
     */
    public void setBpm(int bpm, boolean animate) {
        currentBpm = bpm;
        float target = bpm <= 0 ? 0f : Math.min(1f, bpm / (float) MAX_BPM);
        targetFraction = target;
        updateZoneColors();

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

    private void updateZoneColors() {
        if (currentBpm <= 0) {
            activeZoneColor = accentColor;
        } else if (currentBpm < 60) {
            activeZoneColor = 0xFF22D3EE; // 蓝青色 (Resting)
        } else if (currentBpm <= 100) {
            activeZoneColor = 0xFF10B981; // 翡翠绿 (Normal)
        } else if (currentBpm <= 140) {
            activeZoneColor = 0xFFF59E0B; // 琥珀橙 (Aerobic)
        } else {
            activeZoneColor = 0xFFF43F5E; // 霓虹红 (Intense)
        }
        updateGradient();
    }

    /**
     * 每次心跳打一下光环扩散脉冲
     */
    public void pulse() {
        if (pulseRunning) {
            return;
        }
        pulseRunning = true;
        if (pulseAnimator != null) {
            pulseAnimator.cancel();
        }
        pulseAnimator = ValueAnimator.ofFloat(0f, 1f);
        pulseAnimator.setDuration(720);
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

    private void startEcgLoop() {
        ecgAnimator = ValueAnimator.ofFloat(0f, 1f);
        ecgAnimator.setDuration(1600);
        ecgAnimator.setRepeatCount(ValueAnimator.INFINITE);
        ecgAnimator.setInterpolator(new LinearInterpolator());
        ecgAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                ecgPhase = (Float) animation.getAnimatedValue();
                invalidate();
            }
        });
        ecgAnimator.start();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        float inset = strokeWidth / 2f + tickLength + dp(8);
        arcRect.set(inset, inset, w - inset, h - inset);

        float cx = w / 2f;
        float cy = h / 2f;
        float radius = Math.min(w, h) / 2f;

        glowPaint.setShader(new RadialGradient(cx, cy, radius * 0.72f,
                new int[] { withAlpha(activeZoneColor, 36), 0x00000000 },
                new float[] { 0f, 1f }, Shader.TileMode.CLAMP));

        updateGradient();
    }

    private void updateGradient() {
        if (getWidth() <= 0 || getHeight() <= 0) {
            return;
        }
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        SweepGradient gradient = new SweepGradient(cx, cy,
                new int[] { accentColor, activeZoneColor, heartColor, activeZoneColor },
                new float[] { 0f, 0.40f, 0.85f, 1f });
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

        float w = getWidth();
        float h = getHeight();
        float cx = w / 2f;
        float cy = h / 2f;
        float outerRadius = Math.min(w, h) / 2f - dp(2);

        // 1. 中心柔光呼吸光晕
        if (glowPaint.getShader() != null && fraction > 0.001f) {
            canvas.drawCircle(cx, cy, outerRadius * 0.75f, glowPaint);
        }

        // 2. 外圈精密仪表刻度
        for (int i = 0; i < NUM_TICKS; i++) {
            float angleDeg = -90f + (360f / NUM_TICKS) * i;
            double angleRad = Math.toRadians(angleDeg);
            float currentTickFraction = i / (float) NUM_TICKS;

            float rOuter = outerRadius;
            float rInner = outerRadius - tickLength;

            float x1 = (float) (cx + rOuter * Math.cos(angleRad));
            float y1 = (float) (cy + rOuter * Math.sin(angleRad));
            float x2 = (float) (cx + rInner * Math.cos(angleRad));
            float y2 = (float) (cy + rInner * Math.sin(angleRad));

            if (fraction > 0.001f && currentTickFraction <= fraction) {
                tickPaint.setColor(withAlpha(activeZoneColor, 220));
            } else {
                tickPaint.setColor(withAlpha(trackColor, 80));
            }
            canvas.drawLine(x1, y1, x2, y2, tickPaint);
        }

        // 3. 底环轨道
        canvas.drawArc(arcRect, 0f, 360f, false, trackPaint);

        // 4. 动态渐变进度弧
        float sweepAngle = 360f * fraction;
        if (fraction > 0.001f) {
            canvas.drawArc(arcRect, -90f, sweepAngle, false, progressPaint);

            // 5. 弧线末端高光光斑 (Glowing Tip)
            double endAngleRad = Math.toRadians(-90f + sweepAngle);
            float radius = (arcRect.width()) / 2f;
            float tipX = (float) (cx + radius * Math.cos(endAngleRad));
            float tipY = (float) (cy + radius * Math.sin(endAngleRad));

            // 外发光
            headPaint.setColor(withAlpha(activeZoneColor, 90));
            canvas.drawCircle(tipX, tipY, strokeWidth * 0.95f, headPaint);
            // 实体亮核
            headPaint.setColor(Color.WHITE);
            canvas.drawCircle(tipX, tipY, strokeWidth * 0.38f, headPaint);
        }

        // 6. 心跳逐拍向外扩散的涟漪脉冲
        if (pulse > 0f && fraction > 0.001f) {
            float grow = dp(3) + dp(12) * pulse;
            RectF pulseRect = new RectF(arcRect);
            pulseRect.inset(-grow, -grow);
            pulsePaint.setStrokeWidth(dp(2.4f));
            int alpha = (int) (140 * (1f - pulse));
            pulsePaint.setColor(withAlpha(activeZoneColor, Math.max(0, alpha)));
            canvas.drawArc(pulseRect, -90f, sweepAngle, false, pulsePaint);
        }

        // 7. 内置微型平滑 ECG 心电图波形
        drawEcgWave(canvas, cx, cy + dp(46));
    }

    private void drawEcgWave(Canvas canvas, float cx, float baselineY) {
        float waveHalfWidth = dp(36);
        float startX = cx - waveHalfWidth;
        float endX = cx + waveHalfWidth;

        ecgPath.reset();
        ecgPath.moveTo(startX, baselineY);

        if (fraction <= 0.001f) {
            // 静息直线
            ecgPath.lineTo(endX, baselineY);
            ecgPaint.setColor(withAlpha(trackColor, 120));
            canvas.drawPath(ecgPath, ecgPaint);
            return;
        }

        // 随心率活跃的平滑 ECG 波形 (P-Q-R-S-T)
        float peakY = dp(11);
        float qY = dp(4);
        float sY = dp(5);

        float p1 = startX + waveHalfWidth * 0.35f;
        float pQ = startX + waveHalfWidth * 0.70f;
        float pR = startX + waveHalfWidth * 0.95f;
        float pS = startX + waveHalfWidth * 1.15f;
        float pT = startX + waveHalfWidth * 1.50f;

        ecgPath.lineTo(p1 - dp(5), baselineY);
        // P 波
        ecgPath.quadTo(p1, baselineY - dp(3), p1 + dp(5), baselineY);
        ecgPath.lineTo(pQ - dp(2), baselineY);
        // Q 谷
        ecgPath.lineTo(pQ, baselineY + qY);
        // R 峰 (高耸跳动脉冲)
        ecgPath.lineTo(pR, baselineY - peakY);
        // S 谷
        ecgPath.lineTo(pS, baselineY + sY);
        ecgPath.lineTo(pS + dp(4), baselineY);
        // T 波
        ecgPath.quadTo(pT, baselineY - dp(4.5f), pT + dp(8), baselineY);
        ecgPath.lineTo(endX, baselineY);

        ecgPaint.setColor(withAlpha(activeZoneColor, 190));
        canvas.drawPath(ecgPath, ecgPaint);
    }

    @Override
    protected void onDetachedFromWindow() {
        if (sweepAnimator != null) {
            sweepAnimator.cancel();
        }
        if (pulseAnimator != null) {
            pulseAnimator.cancel();
        }
        if (ecgAnimator != null) {
            ecgAnimator.cancel();
        }
        super.onDetachedFromWindow();
    }
}
