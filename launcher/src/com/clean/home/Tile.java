package com.clean.home;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

/** An app banner with its name, which appears only while the tile is focused. */
final class Tile extends FrameLayout {
    static final float FOCUS_SCALE = 1.1f;

    final App app;
    final ImageView banner;
    private final TextView label;
    private final int bannerHeight;
    private TextView name;

    Tile(Context c, App app, int w, int h, float dp, Typeface face) {
        super(c);
        this.app = app;
        this.bannerHeight = h;
        setClipChildren(false);
        setClipToPadding(false);

        banner = new ImageView(c);
        banner.setImageDrawable(app.banner);
        // A plate behind every banner: some (Vzy) are transparent and vanish on black.
        banner.setBackground(new ColorDrawable(darken(app.color)));
        if (app.isBanner) {
            banner.setScaleType(ImageView.ScaleType.CENTER_CROP);
        } else {
            banner.setScaleType(ImageView.ScaleType.FIT_CENTER);
            banner.setPadding((int) (20 * dp), (int) (16 * dp), (int) (20 * dp), (int) (40 * dp));
        }
        float radius = 12 * dp;
        banner.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
            }
        });
        banner.setClipToOutline(true);
        addView(banner, new LayoutParams(w, h));
        if (!app.isBanner) {
            // Icon-only apps get their name printed on the plate, like a banner would.
            TextView name = new TextView(c);
            name.setText(app.label);
            name.setTypeface(face);
            name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            name.setTextColor(0xF2FFFFFF);
            name.setGravity(Gravity.CENTER);
            name.setSingleLine();
            name.setEllipsize(TextUtils.TruncateAt.END);
            name.setPadding((int) (10 * dp), 0, (int) (10 * dp), 0);
            LayoutParams np = new LayoutParams(w, (int) (32 * dp));
            np.topMargin = h - (int) (38 * dp);
            addView(name, np);
            this.name = name;
        }

        label = new TextView(c);
        label.setText(app.label);
        label.setTypeface(face);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        label.setTextColor(0xEBFFFFFF);
        label.setGravity(Gravity.CENTER_HORIZONTAL);
        label.setSingleLine();
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setAlpha(0f);
        LayoutParams lp = new LayoutParams(w, LayoutParams.WRAP_CONTENT);
        lp.topMargin = h + (int) (8 * dp);
        addView(label, lp);
    }

    void setFocused(boolean focused) {
        float s = focused ? FOCUS_SCALE : 1f;
        DecelerateInterpolator ease = new DecelerateInterpolator(2f);
        banner.animate().scaleX(s).scaleY(s).setDuration(240).setInterpolator(ease).start();
        if (name != null) {
            // Scale with the plate around the plate's centre, not the name's own.
            float shift = (name.getTop() + name.getHeight() / 2f - bannerHeight / 2f) * (s - 1);
            name.animate().scaleX(s).scaleY(s).translationY(focused ? shift : 0)
                    .setDuration(240).setInterpolator(ease).start();
        }
        label.animate().alpha(focused ? 1f : 0f)
                .translationY(focused ? bannerHeight * (FOCUS_SCALE - 1) / 2 : 0)
                .setDuration(240).setInterpolator(ease).start();
    }

    private static int darken(int color) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[1] = Math.min(hsv[1], 0.55f);
        hsv[2] = 0.28f;
        return Color.HSVToColor(hsv);
    }
}
