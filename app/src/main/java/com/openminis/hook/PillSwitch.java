package com.openminis.hook;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;

/**
 * 自己画的药丸开关。
 *
 * <p>系统 {@code Switch} 在 HyperOS 上用自带的浅色 track + 白 thumb，不吃
 * {@code setTrackTintList}，Monet 配色体现不出来；这里按 Material 3 的形状直接画：
 * 开启 = 实心强调色 track + 白色 thumb，关闭 = 浅灰 track + 中性 thumb，切换时颜色一起渐变。</p>
 *
 * <p>无障碍上仍按 {@code android.widget.Switch} 暴露，保留 checkable / checked 语义。</p>
 */
final class PillSwitch extends View {

    interface OnCheckedChangeListener {
        void onCheckedChange(PillSwitch view, boolean checked);
    }

    private static final int WIDTH_DP = 52;
    private static final int HEIGHT_DP = 32;
    private static final int INSET_DP = 4;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private final Interpolator interpolator = new PathInterpolator(0.2f, 0f, 0f, 1f);

    private int colorTrackOn = 0xFF176684;
    private int colorTrackOff = 0xFFDFE3E7;
    private int colorThumbOn = Color.WHITE;
    private int colorThumbOff = 0xFF76777D;

    private boolean checked;
    /** 0 = 关，1 = 开；动画中间值用来做颜色和位置的插值。 */
    private float fraction;
    private ValueAnimator animator;
    private OnCheckedChangeListener listener;
    private final int touchSlop;
    private float downX;
    private float downY;
    private boolean gestureCancelled;

    PillSwitch(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        setClickable(true);
        setFocusable(true);
    }

    void setColors(int trackOn, int trackOff, int thumbOn, int thumbOff) {
        colorTrackOn = trackOn;
        colorTrackOff = trackOff;
        colorThumbOn = thumbOn;
        colorThumbOff = thumbOff;
        invalidate();
    }

    void setOnCheckedChangeListener(OnCheckedChangeListener listener) {
        this.listener = listener;
    }

    boolean isChecked() {
        return checked;
    }

    /** 程序化设置：不回调监听，避免把初始化当成用户操作再存一次盘。 */
    void setChecked(boolean value) {
        setChecked(value, false);
    }

    void toggle() {
        setChecked(!checked, true);
    }

    private void setChecked(boolean value, boolean notify) {
        if (checked == value) {
            return;
        }
        checked = value;
        animateTo(value ? 1f : 0f);
        invalidate();
        if (notify && listener != null) {
            listener.onCheckedChange(this, checked);
        }
    }

    private void animateTo(float target) {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
        if (!isAttachedToWindow() || fraction == target) {
            fraction = target;
            return;
        }
        animator = ValueAnimator.ofFloat(fraction, target);
        animator.setDuration(170);
        animator.setInterpolator(interpolator);
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                fraction = (float) animation.getAnimatedValue();
                invalidate();
            }
        });
        animator.start();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(
                resolveSize(dp(WIDTH_DP), widthMeasureSpec),
                resolveSize(dp(HEIGHT_DP), heightMeasureSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        float radius = h / 2f;
        int alpha = Math.round(255 * (isEnabled() ? 1f : 0.38f));

        paint.setColor(blend(colorTrackOff, colorTrackOn, fraction));
        paint.setAlpha(alpha);
        canvas.drawRoundRect(0f, 0f, w, h, radius, radius, paint);

        float thumbRadius = radius - dp(INSET_DP);
        float from = radius;
        float to = w - radius;
        paint.setColor(blend(colorThumbOff, colorThumbOn, fraction));
        paint.setAlpha(alpha);
        canvas.drawCircle(from + (to - from) * fraction, h / 2f, thumbRadius, paint);
    }

    @Override
    protected void drawableStateChanged() {
        super.drawableStateChanged();
        // 总开关关掉后整组开关会被 setEnabled(false)，这里保证禁用态真的重画
        invalidate();
    }

    /** 视觉只有 52×32dp，但点击区补到 48dp 高，别让用户戳不到。 */
    @Override
    public void getHitRect(Rect outRect) {
        super.getHitRect(outRect);
        int extra = (dp(48) - outRect.height()) / 2;
        if (extra > 0) {
            outRect.top -= extra;
            outRect.bottom += extra;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()) {
            return false;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                setPressed(true);
                gestureCancelled = false;
                downX = event.getX();
                downY = event.getY();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (isPressed() && !gestureCancelled
                        && (Math.abs(event.getX() - downX) > touchSlop
                        || Math.abs(event.getY() - downY) > touchSlop)) {
                    // 手指划出去了（比如在滚列表），这次点击作废
                    gestureCancelled = true;
                    setPressed(false);
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (isPressed() && !gestureCancelled) {
                    setPressed(false);
                    performClick();
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                gestureCancelled = true;
                setPressed(false);
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    @Override
    public boolean performClick() {
        super.performClick();
        if (!isEnabled()) {
            return true;
        }
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        setChecked(!checked, true);
        return true;
    }

    @Override
    public CharSequence getAccessibilityClassName() {
        return "android.widget.Switch";
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName("android.widget.Switch");
        info.setCheckable(true);
        info.setChecked(checked);
    }

    private int dp(float value) {
        return (int) (value * density + 0.5f);
    }

    private static int blend(int from, int to, float t) {
        float f = Math.max(0f, Math.min(1f, t));
        int a = Math.round(Color.alpha(from) + (Color.alpha(to) - Color.alpha(from)) * f);
        int r = Math.round(Color.red(from) + (Color.red(to) - Color.red(from)) * f);
        int g = Math.round(Color.green(from) + (Color.green(to) - Color.green(from)) * f);
        int b = Math.round(Color.blue(from) + (Color.blue(to) - Color.blue(from)) * f);
        return Color.argb(a, r, g, b);
    }
}
