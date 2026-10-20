package com.clean.home;

import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.service.dreams.DreamService;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextClock;

import java.util.Random;

/** Screensaver: a dim clock on black that moves to a new spot every minute to avoid burn-in. */
public class ClockDream extends DreamService {
    private static final long MOVE_EVERY_MS = 60_000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private FrameLayout root;
    private LinearLayout block;

    private final Runnable move = new Runnable() {
        @Override
        public void run() {
            block.animate().alpha(0f).setDuration(800).withEndAction(() -> {
                place();
                block.animate().alpha(1f).setDuration(800).start();
            }).start();
            handler.postDelayed(this, MOVE_EVERY_MS);
        }
    };

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);

        Typeface thin = font(150), regular = font(350);
        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);

        block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setGravity(Gravity.CENTER_HORIZONTAL);
        block.addView(clock("h:mm", "H:mm", thin, 120, 0x8CFFFFFF));
        block.addView(clock("EEEE, d MMMM", "EEEE, d MMMM", regular, 22, 0x66FFFFFF));
        root.addView(block, new FrameLayout.LayoutParams(-2, -2));
        setContentView(root);
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        root.post(this::place);
        handler.postDelayed(move, MOVE_EVERY_MS);
    }

    @Override
    public void onDreamingStopped() {
        handler.removeCallbacks(move);
        super.onDreamingStopped();
    }

    private void place() {
        int w = Math.max(0, root.getWidth() - block.getWidth());
        int h = Math.max(0, root.getHeight() - block.getHeight());
        block.setTranslationX(w == 0 ? 0 : random.nextInt(w));
        block.setTranslationY(h == 0 ? 0 : random.nextInt(h));
    }

    private TextClock clock(String f12, String f24, Typeface face, float sp, int color) {
        TextClock c = new TextClock(this);
        c.setFormat12Hour(f12);
        c.setFormat24Hour(f24);
        c.setTypeface(face);
        c.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        c.setTextColor(color);
        c.setIncludeFontPadding(false);
        return c;
    }

    private Typeface font(int weight) {
        try {
            return new Typeface.Builder(getAssets(), "Outfit.ttf")
                    .setFontVariationSettings("'wght' " + weight).build();
        } catch (RuntimeException ex) {
            return Typeface.create("sans-serif-thin", Typeface.NORMAL);
        }
    }
}
