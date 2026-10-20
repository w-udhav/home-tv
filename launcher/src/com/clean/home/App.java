package com.clean.home;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;

/** A launchable app: label, banner (or icon fallback) and the colour sampled from it. */
final class App {
    final String pkg;
    final String label;
    final Drawable banner;
    final boolean isBanner;
    final int color;
    final Intent launch;

    App(PackageManager pm, ResolveInfo ri, Intent launch) {
        pkg = ri.activityInfo.packageName;
        label = ri.loadLabel(pm).toString();
        Drawable b = ri.activityInfo.loadBanner(pm);
        isBanner = b != null;
        banner = isBanner ? b : ri.loadIcon(pm);
        color = sample(banner);
        this.launch = launch;
    }

    /** Averages a tiny render of the drawable, weighting saturated pixels so a logo beats its white plate. */
    private static int sample(Drawable d) {
        Bitmap bmp = Bitmap.createBitmap(32, 18, Bitmap.Config.ARGB_8888);
        Drawable copy = d.getConstantState() != null ? d.getConstantState().newDrawable().mutate() : d;
        copy.setBounds(0, 0, 32, 18);
        copy.draw(new Canvas(bmp));
        float r = 0, g = 0, b = 0, total = 0;
        float[] hsv = new float[3];
        for (int y = 0; y < 18; y++) {
            for (int x = 0; x < 32; x++) {
                int p = bmp.getPixel(x, y);
                if (Color.alpha(p) < 128) continue;
                Color.colorToHSV(p, hsv);
                float w = hsv[1] * hsv[2] + 0.02f;
                r += Color.red(p) * w;
                g += Color.green(p) * w;
                b += Color.blue(p) * w;
                total += w;
            }
        }
        bmp.recycle();
        if (total == 0) return 0xFF8E8E93;
        Color.colorToHSV(Color.rgb((int) (r / total), (int) (g / total), (int) (b / total)), hsv);
        hsv[1] = Math.min(1f, hsv[1] * 1.2f);
        hsv[2] = Math.max(hsv[2], 0.85f);
        return Color.HSVToColor(hsv);
    }
}
