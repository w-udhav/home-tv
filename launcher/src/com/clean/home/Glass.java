package com.clean.home;

import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

/** The single translucent highlight that springs from one focus target to the next. */
final class Glass extends View {
    /** Damped spring: overshoots ~6% and settles. */
    static final TimeInterpolator SPRING =
            t -> t >= 1f ? 1f : (float) (1 - Math.exp(-7.5 * t) * Math.cos(8.5 * t));

    private final RectF cur = new RectF(), from = new RectF(), to = new RectF();
    private float radius, fromRadius, toRadius;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix sheenMatrix = new Matrix();
    private final LinearGradient sheenShader =
            new LinearGradient(0, 0, 0, 1, 0x30FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
    private ValueAnimator anim;

    Glass(Context c, float dp) {
        super(c);
        fill.setColor(0x1CFFFFFF);
        sheen.setShader(sheenShader);
    }

    /** Fades out; the next moveTo brings it back from where it was. */
    void hide() {
        animate().alpha(0f).setDuration(160).start();
    }

    void moveTo(RectF target, float r, boolean animate) {
        if (getAlpha() < 1f) animate().alpha(1f).setDuration(200).start();
        if (anim != null) anim.cancel();
        if (!animate || cur.isEmpty()) {
            cur.set(target);
            radius = r;
            invalidate();
            return;
        }
        from.set(cur);
        fromRadius = radius;
        to.set(target);
        toRadius = r;
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(460);
        anim.setInterpolator(SPRING);
        anim.addUpdateListener(a -> {
            float f = (float) a.getAnimatedValue();
            cur.set(lerp(from.left, to.left, f), lerp(from.top, to.top, f),
                    lerp(from.right, to.right, f), lerp(from.bottom, to.bottom, f));
            radius = Math.max(0, lerp(fromRadius, toRadius, f));
            invalidate();
        });
        anim.start();
    }

    private static float lerp(float a, float b, float f) {
        return a + (b - a) * f;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (cur.isEmpty()) return;
        canvas.drawRoundRect(cur, radius, radius, fill);
        sheenMatrix.setScale(1f, cur.height() * 0.55f);
        sheenMatrix.postTranslate(0, cur.top);
        sheenShader.setLocalMatrix(sheenMatrix);
        canvas.drawRoundRect(cur, radius, radius, sheen);
    }
}
