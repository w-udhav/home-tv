package com.clean.home;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * The firmware launches com.nes.hometv on Home no matter which app holds the HOME role.
 * This takes the Home key first, and bounces any stock-launcher window the system opens
 * on its own (boot, backing out of an app) to our home screen.
 */
public class HomeKeyService extends AccessibilityService {
    static final String STOCK = "com.nes.hometv";

    /** Set when the user opens the stock launcher on purpose, cleared on the next Home press. */
    static volatile boolean allowStock;

    @Override
    protected boolean onKeyEvent(KeyEvent e) {
        if (e.getKeyCode() != KeyEvent.KEYCODE_HOME) return false;
        if (e.getAction() == KeyEvent.ACTION_UP && !e.isCanceled()) {
            allowStock = false;
            goHome();
        }
        return true;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (!allowStock && STOCK.contentEquals(e.getPackageName() == null ? "" : e.getPackageName())) goHome();
    }

    @Override
    public void onInterrupt() {
    }

    private void goHome() {
        startActivity(new Intent(this, HomeActivity.class).setAction(Intent.ACTION_MAIN)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
    }
}
