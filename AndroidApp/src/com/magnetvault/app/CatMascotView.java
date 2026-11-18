package com.magnetvault.app;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * 动静结合的「暗夜异瞳黑猫」交互组件：
 * - 静：冷冽几何切面轮廓、残耳刻痕、暗夜星轨与月弧背景；
 * - 动：冰青 + 琥珀异色竖瞳自然眨眼、随列表滚动/手指触控凝视追踪、耳尖微动、尾尖律动与多选狩猎缩瞳。
 */
public final class CatMascotView extends View {

    public static final int MODE_AVATAR = 0;
    public static final int MODE_STAGE = 1;

    public static final int STATE_ALOOF = 0;
    public static final int STATE_PROWL = 1;
    public static final int STATE_HUNT = 2;

    private final int mode;
    private int state = STATE_ALOOF;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();

    private final int colStageBg;
    private final int colStageBorder;
    private final int colFur;
    private final int colFurEdge;
    private final int colEarInner;
    private final int colEyeLeft;
    private final int colEyeRight;

    private ValueAnimator loop;
    private float phase;
    private float targetLookX;
    private float targetLookY;
    private float lookX;
    private float lookY;
    private float earTwitch;
    private long lastBlinkMs;
    private long nextBlinkDelayMs = 3800;
    private long pokeUntilMs;
    private Runnable onPoke;

    public CatMascotView(Context context, int mode) {
        super(context);
        this.mode = mode;
        colStageBg = context.getColor(R.color.c_cat_stage_bg);
        colStageBorder = context.getColor(R.color.c_cat_stage_border);
        colFur = context.getColor(R.color.c_cat_fur);
        colFurEdge = context.getColor(R.color.c_cat_fur_edge);
        colEarInner = context.getColor(R.color.c_cat_ear_inner);
        colEyeLeft = context.getColor(R.color.c_cat_eye_left);
        colEyeRight = context.getColor(R.color.c_cat_eye_right);
        lastBlinkMs = System.currentTimeMillis();
        setClickable(true);
        setFocusable(true);
    }

    public void setOnPokeListener(Runnable listener) {
        this.onPoke = listener;
    }

    public void setCatState(int newState) {
        if (this.state != newState) {
            this.state = newState;
            invalidate();
        }
    }

    public int getCatState() {
        return state;
    }

    /** 列表滚动时联动黑猫视线：竖瞳随滚动方向冷酷审视。 */
    public void trackScroll(float deltaX, float deltaY) {
        targetLookX = clamp(deltaX * 0.18f, -1f, 1f);
        targetLookY = clamp(deltaY * 0.22f, -1f, 1f);
        if (!ValueAnimator.areAnimatorsEnabled()) {
            lookX = targetLookX;
            lookY = targetLookY;
        }
        invalidate();
    }

    /** 轻点逗猫：触发缩瞳、耳尖抖动与回弹动效。 */
    public void poke() {
        pokeUntilMs = System.currentTimeMillis() + 650;
        earTwitch = 1f;
        lastBlinkMs = System.currentTimeMillis() - 90;
        if (getParent() != null) {
            Design.popBounce(this);
        }
        invalidate();
        if (onPoke != null) {
            onPoke.run();
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
            float w = Math.max(1f, getWidth());
            float h = Math.max(1f, getHeight());
            targetLookX = clamp((event.getX() - w * 0.5f) / (w * 0.4f), -1f, 1f);
            targetLookY = clamp((event.getY() - h * 0.5f) / (h * 0.4f), -1f, 1f);
            invalidate();
        }
        if (action == MotionEvent.ACTION_UP) {
            performClick();
        }
        return super.onTouchEvent(event);
    }

    @Override
    public boolean performClick() {
        super.performClick();
        poke();
        return true;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        updateLoopState();
    }

    @Override
    protected void onDetachedFromWindow() {
        stopLoop();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        updateLoopState();
    }

    private void updateLoopState() {
        if (isAttachedToWindow()
                && getWindowVisibility() == VISIBLE
                && ValueAnimator.areAnimatorsEnabled()) {
            startLoop();
        } else {
            stopLoop();
        }
    }

    private void startLoop() {
        if (loop != null && loop.isRunning()) return;
        loop = ValueAnimator.ofFloat(0f, (float) (Math.PI * 2.0));
        loop.setDuration(4200);
        loop.setRepeatCount(ValueAnimator.INFINITE);
        loop.setInterpolator(new LinearInterpolator());
        loop.addUpdateListener(
                anim -> {
                    phase = (float) anim.getAnimatedValue();
                    // 视线平滑归位与插值
                    lookX += (targetLookX - lookX) * 0.14f;
                    lookY += (targetLookY - lookY) * 0.14f;
                    targetLookX *= 0.94f;
                    targetLookY *= 0.94f;
                    earTwitch *= 0.86f;
                    invalidate();
                });
        loop.start();
    }

    private void stopLoop() {
        if (loop != null) {
            loop.cancel();
            loop = null;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) return;

        long now = System.currentTimeMillis();
        float eyeOpen = computeEyeOpen(now);
        boolean poked = now < pokeUntilMs;

        if (mode == MODE_STAGE) {
            drawStageMode(canvas, w, h, eyeOpen, poked);
        } else {
            drawAvatarMode(canvas, w, h, eyeOpen, poked);
        }
    }

    private float computeEyeOpen(long now) {
        long elapsed = now - lastBlinkMs;
        if (elapsed > nextBlinkDelayMs) {
            lastBlinkMs = now;
            nextBlinkDelayMs = 3400 + (now % 2400);
            elapsed = 0;
        }
        if (elapsed >= 0 && elapsed < 190) {
            float t = elapsed / 95f;
            float open = Math.abs(1f - t);
            return Math.max(0.08f, open);
        }
        if (state == STATE_HUNT) {
            return 0.78f; // 狩猎/多选态：冷酷半眯凝视
        }
        return 0.92f;
    }

    private void drawAvatarMode(Canvas canvas, float w, float h, float eyeOpen, boolean poked) {
        float s = Math.min(w, h);
        float cx = w * 0.5f;
        float cy = h * 0.5f;
        float pad = s * 0.04f;
        float r = s * 0.30f;

        // 1. 暗夜黑曜底座
        rect.set(pad, pad, w - pad, h - pad);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(colStageBg);
        canvas.drawRoundRect(rect, r, r, paint);

        // 2. 动态呼吸冷光边框（多选狩猎态呈琥珀金，日常呈冰青色）
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth( Math.max(2f, s * 0.036f));
        paint.setColor(state == STATE_HUNT || poked ? colEyeRight : colStageBorder);
        canvas.drawRoundRect(rect, r, r, paint);

        // 3. 绘几何黑猫头部
        float breath = (float) Math.sin(phase) * (s * 0.012f);
        drawCatHead(canvas, cx, cy + s * 0.05f + breath, s * 0.76f, eyeOpen, poked);
    }

    private void drawStageMode(Canvas canvas, float w, float h, float eyeOpen, boolean poked) {
        float density = getResources().getDisplayMetrics().density;

        // 1. 静态建筑感几何背景：新月与星轨环
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1f * density);
        paint.setColor(0x282CE5D8);
        float haloR = h * 0.44f;
        canvas.drawCircle(w * 0.52f, h * 0.52f, haloR, paint);

        // 右上冷冽弯月
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x38F5B841);
        canvas.drawCircle(w * 0.80f, h * 0.24f, h * 0.11f, paint);
        paint.setColor(colStageBg);
        canvas.drawCircle(w * 0.80f - h * 0.04f, h * 0.22f, h * 0.095f, paint);

        // 2. 动态摇曳猫尾（正弦曲线律动）
        float tailSwing = (float) Math.sin(phase * 1.5f) * (8f * density);
        path.reset();
        path.moveTo(w * 0.62f, h * 0.88f);
        path.cubicTo(
                w * 0.82f,
                h * 0.86f + tailSwing * 0.4f,
                w * 0.90f,
                h * 0.56f + tailSwing,
                w * 0.78f + tailSwing * 0.35f,
                h * 0.38f);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(5.5f * density);
        paint.setColor(colFur);
        canvas.drawPath(path, paint);
        paint.setStrokeWidth(1.4f * density);
        paint.setColor(colFurEdge);
        canvas.drawPath(path, paint);

        // 3. 盘坐黑猫躯干剪影
        path.reset();
        path.moveTo(w * 0.30f, h * 0.95f);
        path.quadTo(w * 0.31f, h * 0.64f, w * 0.50f, h * 0.62f);
        path.quadTo(w * 0.69f, h * 0.64f, w * 0.70f, h * 0.95f);
        path.close();
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(colFur);
        canvas.drawPath(path, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.3f * density);
        paint.setColor(colFurEdge);
        canvas.drawPath(path, paint);

        // 4. 头部与异色竖瞳
        float breath = (float) Math.sin(phase) * (1.6f * density);
        float headSize = Math.min(w, h) * 0.70f;
        drawCatHead(canvas, w * 0.50f, h * 0.46f + breath, headSize, eyeOpen, poked);
    }

    private void drawCatHead(
            Canvas canvas, float cx, float cy, float size, float eyeOpen, boolean poked) {
        float half = size * 0.48f;
        float top = cy - half * 0.92f;
        float bottom = cy + half * 0.85f;
        float left = cx - half;
        float right = cx + half;
        float twitch = earTwitch * size * 0.045f;

        // 头部多边形轮廓（含左耳战斗刻痕）
        path.reset();
        path.moveTo(left, cy + half * 0.08f);
        path.lineTo(left, top - twitch); // 左耳尖
        path.lineTo(left + half * 0.30f, top + half * 0.22f);
        path.lineTo(left + half * 0.20f, top + half * 0.36f); // 左耳刻痕
        path.lineTo(left + half * 0.38f, top + half * 0.28f);
        path.lineTo(cx - half * 0.32f, top + half * 0.44f);
        path.lineTo(cx + half * 0.32f, top + half * 0.44f);
        path.lineTo(right, top + twitch * 0.6f); // 右耳尖
        path.lineTo(right, cy + half * 0.08f);
        path.cubicTo(
                right,
                bottom - half * 0.12f,
                cx + half * 0.48f,
                bottom,
                cx,
                bottom);
        path.cubicTo(
                cx - half * 0.48f,
                bottom,
                left,
                bottom - half * 0.12f,
                left,
                cy + half * 0.08f);
        path.close();

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(colFur);
        canvas.drawPath(path, paint);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeWidth(Math.max(1.8f, size * 0.036f));
        paint.setColor(colFurEdge);
        canvas.drawPath(path, paint);

        // 耳廓内侧暗影
        path.reset();
        path.moveTo(left + half * 0.14f, cy - half * 0.12f);
        path.lineTo(left + half * 0.14f, top + half * 0.20f);
        path.lineTo(cx - half * 0.42f, top + half * 0.48f);
        path.close();
        path.moveTo(right - half * 0.14f, cy - half * 0.12f);
        path.lineTo(right - half * 0.14f, top + half * 0.20f);
        path.lineTo(cx + half * 0.42f, top + half * 0.48f);
        path.close();
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(colEarInner);
        canvas.drawPath(path, paint);

        // 异色双瞳（左眼冰青 #2CE5D8，右眼琥珀 #F5B841）
        float eyeOffsetX = lookX * (half * 0.10f);
        float eyeOffsetY = lookY * (half * 0.08f);
        float eyeY = cy + half * 0.06f + eyeOffsetY;
        float eyeH = Math.max(half * 0.04f, half * 0.22f * eyeOpen);

        // 左眼：冰青凌厉斜瞳
        float lxOut = cx - half * 0.68f + eyeOffsetX;
        float lxIn = cx - half * 0.18f + eyeOffsetX;
        path.reset();
        path.moveTo(lxOut, eyeY - eyeH * 0.65f);
        path.lineTo(lxIn, eyeY + eyeH * 0.15f);
        path.lineTo(lxIn - half * 0.10f, eyeY + eyeH * 0.95f);
        path.lineTo(lxOut + half * 0.05f, eyeY + eyeH * 0.20f);
        path.close();
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(colEyeLeft);
        canvas.drawPath(path, paint);

        // 右眼：琥珀凌厉斜瞳
        float rxOut = cx + half * 0.68f + eyeOffsetX;
        float rxIn = cx + half * 0.18f + eyeOffsetX;
        path.reset();
        path.moveTo(rxOut, eyeY - eyeH * 0.65f);
        path.lineTo(rxIn, eyeY + eyeH * 0.15f);
        path.lineTo(rxIn + half * 0.10f, eyeY + eyeH * 0.95f);
        path.lineTo(rxOut - half * 0.05f, eyeY + eyeH * 0.20f);
        path.close();
        paint.setColor(colEyeRight);
        canvas.drawPath(path, paint);

        // 竖瞳仁（狩猎态/戳猫态下收缩成刀锋细缝）
        if (eyeOpen > 0.22f) {
            float slitHalfW =
                    (state == STATE_HUNT || poked) ? half * 0.028f : half * 0.052f;
            float pupilShift = lookX * (half * 0.06f);
            float lpx = (lxOut + lxIn) * 0.5f + pupilShift;
            float rpx = (rxOut + rxIn) * 0.5f + pupilShift;

            paint.setColor(colFur);
            path.reset();
            path.moveTo(lpx, eyeY - eyeH * 0.42f);
            path.lineTo(lpx + slitHalfW, eyeY + eyeH * 0.20f);
            path.lineTo(lpx, eyeY + eyeH * 0.82f);
            path.lineTo(lpx - slitHalfW, eyeY + eyeH * 0.20f);
            path.close();
            canvas.drawPath(path, paint);

            path.reset();
            path.moveTo(rpx, eyeY - eyeH * 0.42f);
            path.lineTo(rpx + slitHalfW, eyeY + eyeH * 0.20f);
            path.lineTo(rpx, eyeY + eyeH * 0.82f);
            path.lineTo(rpx - slitHalfW, eyeY + eyeH * 0.20f);
            path.close();
            canvas.drawPath(path, paint);
        }

        // 金属感倒三角鼻尖
        path.reset();
        float ny = cy + half * 0.42f + eyeOffsetY * 0.5f;
        path.moveTo(cx - half * 0.10f, ny);
        path.lineTo(cx + half * 0.10f, ny);
        path.lineTo(cx, ny + half * 0.12f);
        path.close();
        paint.setColor(colFurEdge);
        canvas.drawPath(path, paint);
    }

    private static float clamp(float val, float min, float max) {
        return Math.max(min, Math.min(max, val));
    }
}
