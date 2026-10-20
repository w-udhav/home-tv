package com.clean.home;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class HomeActivity extends Activity {
    private static final int TOP = 0, DOCK = 1, GRID = 2, MENU = 3;
    /** Dock slots and grid columns. */
    private static final int COLS = 5;

    // Layout in dp on a 960x540dp screen.
    private static final int SIDE = 64, GAP = 24;
    private static final int TILE_W = (960 - 2 * SIDE - (COLS - 1) * GAP) / COLS, TILE_H = TILE_W * 9 / 16;
    private static final int BAND_H = TILE_H + 70, BAND_TOP = 506 - BAND_H, DOCK_Y = BAND_TOP + 22;
    private static final int GRID_TOP = 560, ROW_H = TILE_H + 56, GRID_VIEW_Y = 48;

    private float dp;
    private Typeface thin, light, regular, medium;
    private FrameLayout content, tileLayer;
    private Glass glass;
    private TextView greeting, dockTitle, wifiPill;
    private View glow;
    private GradientDrawable glowShape;
    private LinearLayout pillBar, menu;
    private final List<TextView> pills = new ArrayList<>();
    private final List<TextView> menuItems = new ArrayList<>();
    private final List<Runnable> menuActions = new ArrayList<>();
    private final List<Tile> dockTiles = new ArrayList<>(), gridTiles = new ArrayList<>();

    private int zone = DOCK, topIdx, dockIdx, gridIdx, menuIdx;
    private Tile focused;
    private boolean appsDirty, resumed;
    private SharedPreferences prefs;
    private ConnectivityManager cm;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        dp = getResources().getDisplayMetrics().density;
        thin = font(200);
        light = font(300);
        regular = font(400);
        medium = font(500);
        prefs = getSharedPreferences("focus", MODE_PRIVATE);
        zone = prefs.getInt("zone", DOCK);
        if (zone == MENU) zone = TOP;
        topIdx = prefs.getInt("top", 0);
        dockIdx = prefs.getInt("dock", 0);
        gridIdx = prefs.getInt("grid", 0);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        root.setClipChildren(false);
        addWallpaper(root);
        content = new FrameLayout(this);
        content.setClipChildren(false);
        root.addView(content, new FrameLayout.LayoutParams(-1, px(540)));
        buildChrome();
        tileLayer = new FrameLayout(this);
        tileLayer.setClipChildren(false);
        content.addView(tileLayer, new FrameLayout.LayoutParams(-1, -1));
        buildMenu();
        glass = new Glass(this, dp);
        content.addView(glass, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);

        loadApps();
        root.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                root.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                restoreFocus(false);
            }
        });

        IntentFilter pkgs = new IntentFilter();
        pkgs.addAction(Intent.ACTION_PACKAGE_ADDED);
        pkgs.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pkgs.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pkgs.addDataScheme("package");
        registerReceiver(packageReceiver, pkgs);
        IntentFilter other = new IntentFilter(Intent.ACTION_TIME_TICK);
        other.addAction(Intent.ACTION_PACKAGES_SUSPENDED);
        other.addAction(Intent.ACTION_PACKAGES_UNSUSPENDED);
        registerReceiver(tickReceiver, other);

        cm = getSystemService(ConnectivityManager.class);
        cm.registerDefaultNetworkCallback(netCallback);
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(packageReceiver);
        unregisterReceiver(tickReceiver);
        cm.unregisterNetworkCallback(netCallback);
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        updateGreeting();
        if (appsDirty) reloadApps();
    }

    private void reloadApps() {
        appsDirty = false;
        if (zone == MENU) closeMenu();
        loadApps();
        content.post(() -> restoreFocus(false));
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        prefs.edit().putInt("zone", zone == MENU ? TOP : zone).putInt("top", topIdx)
                .putInt("dock", dockIdx).putInt("grid", gridIdx).apply();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home pressed while already home: return to the dock.
        if (zone == MENU) closeMenu();
        if (!dockTiles.isEmpty()) focusDock(dockIdx);
    }

    // ---------------------------------------------------------------- layout

    private void buildChrome() {
        GradientDrawable band = new GradientDrawable();
        band.setColor(0xFF111113);
        band.setStroke(px(1), 0x14FFFFFF);
        band.setCornerRadius(px(30));
        View bandView = new View(this);
        bandView.setBackground(band);
        content.addView(bandView, at(SIDE - 24, BAND_TOP, TILE_W * COLS + GAP * (COLS - 1) + 48, BAND_H));

        glowShape = new GradientDrawable();
        glowShape.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        // The view is far larger than the radius so the gradient fades out before any edge.
        glowShape.setGradientRadius(px(330));
        glowShape.setGradientCenter(0.35f, 0.45f);
        glow = new View(this);
        glow.setBackground(glowShape);
        content.addView(glow, at(-240, -40, 1300, 760));

        dockTitle = text(light, 40, 0xF2FFFFFF);
        content.addView(dockTitle, at(SIDE, BAND_TOP - 68, 800, -2));

        greeting = text(regular, 17, 0xFF8E8E93);
        content.addView(greeting, at(SIDE, 38, 500, -2));
        TextClock clock = new TextClock(this);
        clock.setFormat12Hour("h:mm");
        clock.setFormat24Hour("H:mm");
        clock.setTypeface(thin);
        clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 68);
        clock.setTextColor(0xFFFFFFFF);
        clock.setIncludeFontPadding(false);
        content.addView(clock, at(SIDE - 3, 62, 500, -2));
        TextClock date = new TextClock(this);
        date.setFormat12Hour("EEEE, d MMMM");
        date.setFormat24Hour("EEEE, d MMMM");
        date.setTypeface(regular);
        date.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        date.setTextColor(0xFFA1A1A6);
        content.addView(date, at(SIDE, 146, 500, -2));

        pillBar = new LinearLayout(this);
        pillBar.setOrientation(LinearLayout.HORIZONTAL);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, px(40), Gravity.TOP | Gravity.END);
        lp.topMargin = px(44);
        lp.rightMargin = px(SIDE);
        content.addView(pillBar, lp);
        pills.add(pill("Inputs", null));
        wifiPill = pill("Wi-Fi", new WifiIcon(dp));
        pills.add(wifiPill);
        pills.add(pill("Settings", null));
        pillBar.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (zone == TOP) focusTop(topIdx, false);
        });
    }

    private TextView pill(String label, Drawable icon) {
        TextView t = text(medium, 15, 0xEBFFFFFF);
        t.setText(label);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(px(18), 0, px(18), 0);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x1FFFFFFF);
        bg.setStroke(px(1), 0x24FFFFFF);
        bg.setCornerRadius(px(20));
        t.setBackground(bg);
        if (icon != null) {
            t.setCompoundDrawables(icon, null, null, null);
            t.setCompoundDrawablePadding(px(8));
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -1);
        if (!pills.isEmpty()) lp.leftMargin = px(10);
        pillBar.addView(t, lp);
        return t;
    }

    private void buildMenu() {
        menu = new LinearLayout(this);
        menu.setOrientation(LinearLayout.VERTICAL);
        menu.setPadding(px(8), px(8), px(8), px(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF51C1C1E);
        bg.setStroke(px(1), 0x2EFFFFFF);
        bg.setCornerRadius(px(20));
        menu.setBackground(bg);
        menu.setVisibility(View.GONE);
        content.addView(menu, at(0, 94, 280, -2));
    }

    private void addWallpaper(FrameLayout root) {
        File f = new File(getExternalFilesDir(null), "wallpaper.jpg");
        if (!f.exists()) return;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        o.inSampleSize = Math.max(1, Math.min(o.outWidth / 1920, o.outHeight / 1080));
        o.inJustDecodeBounds = false;
        Bitmap bmp = BitmapFactory.decodeFile(f.getPath(), o);
        if (bmp == null) return;
        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setImageBitmap(bmp);
        root.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        View scrim = new View(this);
        int dim = getResources().getInteger(R.integer.wallpaper_dim_percent);
        scrim.setBackgroundColor((Math.round(dim * 2.55f) << 24));
        root.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
    }

    // ------------------------------------------------------------------ apps

    private void loadApps() {
        PackageManager pm = getPackageManager();
        Set<String> hidden = new HashSet<>(Arrays.asList(getResources().getStringArray(R.array.hidden_apps)));
        hidden.add(getPackageName());
        Map<String, App> apps = new HashMap<>();
        collect(pm, Intent.CATEGORY_LEANBACK_LAUNCHER, hidden, apps);
        collect(pm, Intent.CATEGORY_LAUNCHER, hidden, apps);

        List<App> dock = new ArrayList<>();
        for (String pkg : getResources().getStringArray(R.array.dock_apps)) {
            App a = apps.remove(pkg);
            if (a != null && dock.size() < COLS) dock.add(a);
        }
        List<App> grid = new ArrayList<>(apps.values());
        grid.sort((a, b) -> a.label.compareToIgnoreCase(b.label));

        tileLayer.removeAllViews();
        dockTiles.clear();
        gridTiles.clear();
        focused = null;
        for (int i = 0; i < dock.size(); i++) {
            Tile t = new Tile(this, dock.get(i), px(TILE_W), px(TILE_H), dp, regular);
            tileLayer.addView(t, at(SIDE + i * (TILE_W + GAP), DOCK_Y, TILE_W, TILE_H + 40));
            dockTiles.add(t);
        }
        for (int i = 0; i < grid.size(); i++) {
            Tile t = new Tile(this, grid.get(i), px(TILE_W), px(TILE_H), dp, regular);
            tileLayer.addView(t, at(SIDE + (i % COLS) * (TILE_W + GAP), GRID_TOP + (i / COLS) * ROW_H,
                    TILE_W, TILE_H + 40));
            gridTiles.add(t);
        }
        int rows = (grid.size() + COLS - 1) / COLS;
        content.getLayoutParams().height = px(Math.max(540, GRID_TOP + rows * ROW_H + 540));
        content.requestLayout();
    }

    private void collect(PackageManager pm, String category, Set<String> hidden, Map<String, App> out) {
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(category);
        for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
            String pkg = ri.activityInfo.packageName;
            ApplicationInfo ai = ri.activityInfo.applicationInfo;
            if (out.containsKey(pkg) || hidden.contains(pkg) || !ai.enabled
                    || (ai.flags & ApplicationInfo.FLAG_SUSPENDED) != 0) continue;
            Intent launch = new Intent(Intent.ACTION_MAIN).addCategory(category)
                    .setClassName(pkg, ri.activityInfo.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            out.put(pkg, new App(pm, ri, launch));
        }
    }

    // ------------------------------------------------------------ navigation

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        boolean nav = code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN
                || code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
                || code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER || code == KeyEvent.KEYCODE_BACK;
        if (!nav) return super.dispatchKeyEvent(e);
        if (e.getAction() == KeyEvent.ACTION_DOWN) onKey(code);
        return true;
    }

    private void onKey(int code) {
        boolean enter = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
        switch (zone) {
            case MENU:
                if (code == KeyEvent.KEYCODE_DPAD_UP && menuIdx > 0) focusMenu(menuIdx - 1);
                else if (code == KeyEvent.KEYCODE_DPAD_DOWN && menuIdx < menuItems.size() - 1) focusMenu(menuIdx + 1);
                else if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_DPAD_LEFT
                        || code == KeyEvent.KEYCODE_DPAD_RIGHT) closeMenu();
                else if (enter && !menuActions.isEmpty()) {
                    Runnable r = menuActions.get(menuIdx);
                    closeMenu();
                    r.run();
                }
                break;
            case TOP:
                if (code == KeyEvent.KEYCODE_DPAD_LEFT && topIdx > 0) focusTop(topIdx - 1, true);
                else if (code == KeyEvent.KEYCODE_DPAD_RIGHT && topIdx < pills.size() - 1) focusTop(topIdx + 1, true);
                else if ((code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_BACK) && !dockTiles.isEmpty())
                    focusDock(dockIdx);
                else if (code == KeyEvent.KEYCODE_DPAD_DOWN && !gridTiles.isEmpty()) focusGrid(gridIdx);
                else if (enter) activatePill(topIdx);
                break;
            case DOCK:
                if (code == KeyEvent.KEYCODE_DPAD_LEFT && dockIdx > 0) focusDock(dockIdx - 1);
                else if (code == KeyEvent.KEYCODE_DPAD_RIGHT && dockIdx < dockTiles.size() - 1) focusDock(dockIdx + 1);
                else if (code == KeyEvent.KEYCODE_DPAD_UP) focusTop(topIdx, true);
                else if (code == KeyEvent.KEYCODE_DPAD_DOWN && !gridTiles.isEmpty())
                    focusGrid(Math.min(dockIdx, gridTiles.size() - 1));
                else if (enter) launch(dockTiles.get(dockIdx).app);
                break;
            case GRID:
                int col = gridIdx % COLS, n = gridTiles.size();
                if (code == KeyEvent.KEYCODE_DPAD_LEFT && col > 0) focusGrid(gridIdx - 1);
                else if (code == KeyEvent.KEYCODE_DPAD_RIGHT && col < COLS - 1 && gridIdx + 1 < n) focusGrid(gridIdx + 1);
                else if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_BACK) {
                    if (gridIdx >= COLS && code == KeyEvent.KEYCODE_DPAD_UP) focusGrid(gridIdx - COLS);
                    else if (!dockTiles.isEmpty()) focusDock(Math.min(col, dockTiles.size() - 1));
                    else focusTop(topIdx, true);
                } else if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
                    int nextRowStart = (gridIdx / COLS + 1) * COLS;
                    if (nextRowStart < n) focusGrid(Math.min(gridIdx + COLS, n - 1));
                } else if (enter) launch(gridTiles.get(gridIdx).app);
                break;
        }
    }

    private void restoreFocus(boolean animate) {
        if (zone == GRID && !gridTiles.isEmpty()) focusGrid(Math.min(gridIdx, gridTiles.size() - 1), animate);
        else if (zone != TOP && !dockTiles.isEmpty()) focusDock(Math.min(dockIdx, dockTiles.size() - 1), animate);
        else focusTop(Math.min(topIdx, pills.size() - 1), animate);
    }

    private void focusTop(int i, boolean animate) {
        zone = TOP;
        topIdx = i;
        setFocusedTile(null);
        scrollTo(0, animate);
        glow.animate().alpha(0.35f).setDuration(300).start();
        TextView p = pills.get(i);
        RectF r = rectOf(p);
        r.inset(-px(3), -px(3));
        glass.moveTo(r, r.height() / 2, animate);
    }

    private void focusDock(int i) {
        focusDock(i, true);
    }

    private void focusDock(int i, boolean animate) {
        zone = DOCK;
        dockIdx = i;
        Tile t = dockTiles.get(i);
        setFocusedTile(t);
        scrollTo(0, animate);
        App a = t.app;
        if (!a.label.contentEquals(dockTitle.getText())) {
            dockTitle.setText(a.label);
            dockTitle.setShadowLayer(px(14), 0, 0, (a.color & 0x00FFFFFF) | 0x99000000);
            glowShape.setColors(new int[]{(a.color & 0x00FFFFFF) | 0x38000000, 0x00000000});
            if (animate) {
                dockTitle.setAlpha(0.3f);
                dockTitle.animate().alpha(1f).setDuration(260).start();
            }
        }
        glow.animate().alpha(1f).setDuration(300).start();
        glass.moveTo(tileRect(t), px(15), animate);
    }

    private void focusGrid(int i) {
        focusGrid(i, true);
    }

    private void focusGrid(int i, boolean animate) {
        zone = GRID;
        gridIdx = i;
        Tile t = gridTiles.get(i);
        setFocusedTile(t);
        // The first two rows scroll as one so the row above stays visible as context.
        int row = i / COLS;
        scrollTo(GRID_TOP - GRID_VIEW_Y + Math.max(0, row - 1) * ROW_H, animate);
        glow.animate().alpha(0f).setDuration(300).start();
        glass.moveTo(tileRect(t), px(15), animate);
    }

    private void setFocusedTile(Tile t) {
        if (focused == t) return;
        if (focused != null) focused.setFocused(false);
        focused = t;
        if (t != null) t.setFocused(true);
    }

    private void scrollTo(int yDp, boolean animate) {
        float target = -px(yDp);
        if (content.getTranslationY() == target) return;
        if (animate) {
            content.animate().translationY(target).setDuration(420)
                    .setInterpolator(new DecelerateInterpolator(2.2f)).start();
        } else {
            content.setTranslationY(target);
        }
    }

    private RectF tileRect(Tile t) {
        RectF r = rectOf(t.banner);
        float grow = (Tile.FOCUS_SCALE - 1) / 2;
        r.inset(-r.width() * grow - px(5), -r.height() * grow - px(5));
        return r;
    }

    /** Bounds of a view in content coordinates; positions are known from layout, not from animation state. */
    private RectF rectOf(View v) {
        float x = 0, y = 0;
        View cur = v;
        while (cur != content) {
            x += cur.getLeft();
            y += cur.getTop();
            cur = (View) cur.getParent();
        }
        return new RectF(x, y, x + v.getWidth(), y + v.getHeight());
    }

    // --------------------------------------------------------------- actions

    private void activatePill(int i) {
        if (i == 0) openMenu();
        else if (i == 1) start(new Intent(Settings.ACTION_WIFI_SETTINGS));
        else start(new Intent(Settings.ACTION_SETTINGS));
    }

    private void launch(App a) {
        start(a.launch);
    }

    private void start(Intent i) {
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException ex) {
            Toast.makeText(this, "Can't open that", Toast.LENGTH_SHORT).show();
        }
    }

    private void openMenu() {
        menu.removeAllViews();
        menuItems.clear();
        menuActions.clear();
        TvInputManager tim = getSystemService(TvInputManager.class);
        if (tim != null) {
            for (TvInputInfo info : tim.getTvInputList()) {
                if (!info.isPassthroughInput() || info.isHidden(this)) continue;
                String id = info.getId();
                addMenuItem(info.loadLabel(this).toString(), () -> start(
                        new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(id))));
            }
        }
        PackageManager pm = getPackageManager();
        for (String pkg : getResources().getStringArray(R.array.live_tv_apps)) {
            Intent launch = pm.getLeanbackLaunchIntentForPackage(pkg);
            if (launch == null) launch = pm.getLaunchIntentForPackage(pkg);
            if (launch == null) continue;
            String label;
            try {
                label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
            } catch (PackageManager.NameNotFoundException ex) {
                continue;
            }
            Intent l = launch;
            addMenuItem(label, () -> start(l));
        }
        Intent stock = pm.getLaunchIntentForPackage(HomeKeyService.STOCK);
        if (stock == null) stock = new Intent(Intent.ACTION_MAIN).setPackage(HomeKeyService.STOCK);
        Intent s = stock;
        addMenuItem("Fastway Home", () -> {
            HomeKeyService.allowStock = true;
            start(s);
        });

        TextView inputs = pills.get(0);
        RectF anchor = rectOf(inputs);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) menu.getLayoutParams();
        lp.leftMargin = (int) Math.min(anchor.left, px(960 - SIDE - 280));
        lp.topMargin = (int) anchor.bottom + px(10);
        menu.setLayoutParams(lp);
        menu.setVisibility(View.VISIBLE);
        menu.setAlpha(0f);
        menu.setTranslationY(-px(8));
        menu.animate().alpha(1f).translationY(0).setDuration(200).start();
        zone = MENU;
        menu.post(() -> focusMenu(0));
    }

    private void addMenuItem(String label, Runnable action) {
        TextView t = text(regular, 16, action == null ? 0xFF8E8E93 : 0xF2FFFFFF);
        t.setText(label);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(px(16), 0, px(16), 0);
        t.setSingleLine();
        menu.addView(t, new LinearLayout.LayoutParams(-1, px(46)));
        menuItems.add(t);
        if (action != null) menuActions.add(action);
    }

    private void focusMenu(int i) {
        if (zone != MENU) return;
        menuIdx = i;
        glass.moveTo(rectOf(menuItems.get(i)), px(12), true);
    }

    private void closeMenu() {
        menu.setVisibility(View.GONE);
        focusTop(0, true);
    }

    // --------------------------------------------------------------- status

    private void updateGreeting() {
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        String g = h >= 5 && h < 12 ? "Good morning" : h < 17 && h >= 12 ? "Good afternoon"
                : h >= 17 && h < 22 ? "Good evening" : "Good night";
        String name = getString(R.string.greeting_name).trim();
        greeting.setText(name.isEmpty() ? g : g + ", " + name);
    }

    private void setNetworkLabel(String s) {
        if (!s.contentEquals(wifiPill.getText())) wifiPill.setText(s);
    }

    private final ConnectivityManager.NetworkCallback netCallback =
            new ConnectivityManager.NetworkCallback(
                    Build.VERSION.SDK_INT >= 31 ? ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO : 0) {
                @Override
                public void onCapabilitiesChanged(Network n, NetworkCapabilities caps) {
                    String label;
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                        label = "Wi-Fi";
                        if (caps.getTransportInfo() instanceof WifiInfo) {
                            String ssid = ((WifiInfo) caps.getTransportInfo()).getSSID();
                            if (ssid != null && !ssid.contains("unknown ssid")) label = ssid.replace("\"", "");
                        }
                    } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                        label = "Ethernet";
                    } else {
                        label = "Connected";
                    }
                    String l = label;
                    runOnUiThread(() -> setNetworkLabel(l));
                }

                @Override
                public void onLost(Network n) {
                    runOnUiThread(() -> setNetworkLabel("Offline"));
                }
            };

    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            appsDirty = true;
            if (resumed) reloadApps();
        }
    };

    private final BroadcastReceiver tickReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (Intent.ACTION_TIME_TICK.equals(i.getAction())) updateGreeting();
            else packageReceiver.onReceive(c, i);
        }
    };

    // --------------------------------------------------------------- helpers

    private Typeface font(int weight) {
        try {
            return new Typeface.Builder(getAssets(), "Outfit.ttf")
                    .setFontVariationSettings("'wght' " + weight).build();
        } catch (RuntimeException ex) {
            return Typeface.create("sans-serif-light", Typeface.NORMAL);
        }
    }

    private TextView text(Typeface face, float sp, int color) {
        TextView t = new TextView(this);
        t.setTypeface(face);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        return t;
    }

    private int px(float dps) {
        return Math.round(dps * dp);
    }

    /** Absolute position in dp; a negative size means wrap_content. */
    private FrameLayout.LayoutParams at(int x, int y, int w, int h) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                w < 0 ? ViewGroup.LayoutParams.WRAP_CONTENT : px(w),
                h < 0 ? ViewGroup.LayoutParams.WRAP_CONTENT : px(h));
        lp.leftMargin = px(x);
        lp.topMargin = px(y);
        return lp;
    }

    /** Three arcs and a dot. */
    private static final class WifiIcon extends Drawable {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float dp;

        WifiIcon(float dp) {
            this.dp = dp;
            p.setColor(0xEBFFFFFF);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeWidth(1.8f * dp);
            setBounds(0, 0, Math.round(16 * dp), Math.round(14 * dp));
        }

        @Override
        public void draw(Canvas c) {
            float cx = getBounds().width() / 2f, cy = getBounds().height() - 1.5f * dp;
            for (int i = 1; i <= 3; i++) {
                float r = i * 4f * dp;
                c.drawArc(cx - r, cy - r, cx + r, cy + r, 225, 90, false, p);
            }
            p.setStyle(Paint.Style.FILL);
            c.drawCircle(cx, cy, 1.4f * dp, p);
            p.setStyle(Paint.Style.STROKE);
        }

        @Override public void setAlpha(int a) { }
        @Override public void setColorFilter(ColorFilter f) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
