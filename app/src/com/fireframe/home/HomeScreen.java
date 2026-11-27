package com.fireframe.home;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewTreeObserver;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.view.WindowManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

import org.xmlpull.v1.XmlPullParser;

// The whole home screen as one view. MainActivity shows it as an app; HomeService shows the same view
// as an overlay the instant Amazon's home screen opens, because Fire OS holds app launches back for
// 5 seconds after the Home button.
public class HomeScreen extends FrameLayout {
    // The dock when nothing is saved yet: these, in order, if installed, topped up to six with other apps.
    static final String[] DEFAULT_DOCK = {
        "com.netflix.ninja",
        "com.amazon.firetv.youtube", "com.google.android.youtube.tv",
        "com.disney.disneyplus",
        "com.hulu.plus", "com.hulu.livingroomplus",
        "com.crunchyroll.crunchyroid",
        "com.amazon.firebat", "com.amazon.amazonvideo.livingroom",
        "com.wbd.stream", "com.hbo.hbonow",
        "com.apple.atve.amazon.appletv", "com.apple.atve.androidtv.appletv",
        "com.cbs.ott", "com.plexapp.android", "com.spotify.tv.android",
    };

    // Other home screens, and Fire OS internals that list themselves as apps, never show up.
    static final Set<String> HIDDEN = new HashSet<>(Arrays.asList(
        HomeService.AMAZON_HOME, "com.google.android.tvlauncher", "com.google.android.apps.tv.launcherx",
        "com.google.android.leanbacklauncher",
        "com.amazon.ftv.profilepicker", "com.amazon.ftv.screensaver", "com.amazon.ssm",
        "com.amazon.vizzini.ftvcds", "com.android.providers.downloads.ui"));

    // Bundled wallpapers (assets/wallpapers): dark gradients made for FireFrame, dark enough behind the
    // corners' white text on every one. The name shows when one is picked.
    static final String[][] WALLPAPERS = {
        {"midnight", "Midnight"},
        {"royal", "Royal blue"},
        {"ocean", "Ocean"},
        {"arctic", "Arctic"},
        {"forest", "Forest"},
        {"emerald", "Emerald"},
        {"graphite", "Graphite"},
        {"aubergine", "Aubergine"},
        {"nebula", "Nebula"},
        {"dusk", "Dusk"},
        {"ember", "Ember"},
        {"eclipse", "Eclipse"},
    };
    static final String CUSTOM = "custom";

    final DecelerateInterpolator ease = new DecelerateInterpolator(2f);
    final List<ViewGroup> layers = new ArrayList<>();

    SharedPreferences prefs;
    PackageManager pm;
    int screenW, screenH;
    float s;
    Typeface medium, semibold;
    FrameLayout root, base;
    ImageView wallView, wallFade;
    GlassView glass;
    DockScroller dockScroller;
    LinearLayout dockRow;
    TextView caption, clock, date, weatherTemp, weatherWord, weatherMore;
    LinearLayout weatherBox;
    TextView weatherCity;
    WeatherMark weatherMark;

    // TextClock stops ticking in an overlay window, so the time and date are kept here.
    // The next run is the top of the next minute, which is also midnight's rollover.
    // Clock and date follow the saved city, not the stick's zone. "Tue, Nov 24, 2026".
    final Runnable tick = new Runnable() {
        @Override public void run() {
            Date now = new Date();
            TimeZone tz = Weather.zone(prefs);
            SimpleDateFormat clockFmt = new SimpleDateFormat(
                    android.text.format.DateFormat.is24HourFormat(ctx) ? "H:mm" : "h:mm", Locale.getDefault());
            clockFmt.setTimeZone(tz);
            clock.setText(clockFmt.format(now));
            SimpleDateFormat dateFmt = new SimpleDateFormat("EEE, MMM d, yyyy", Locale.getDefault());
            dateFmt.setTimeZone(tz);
            date.setText(dateFmt.format(now));
            removeCallbacks(this);
            postDelayed(this, 60000 - System.currentTimeMillis() % 60000);
        }
    };
    final Runnable weatherTick = new Runnable() {
        @Override public void run() {
            Weather.refresh(ctx);
            removeCallbacks(this);
            postDelayed(this, Weather.EVERY);
        }
    };
    Bitmap blur;
    List<String> dock = new ArrayList<>();
    boolean appsChanged;
    String shownWallpaper;
    static int dockVersion;
    static int lastDock; // the dock app last focused, in either copy
    int seenDockVersion = -1;
    static int photoVersion; // bumped when fireframe.sh wallpaper sends a new photo
    int seenPhotoVersion;

    // The wallpaper and its blur, shared by the app and the overlay so they're decoded once.
    // wallLock covers the check-and-decode: both screens start together and used to each
    // keep their own 1080p copy (8 MB of pixels, and another 8 MB once uploaded to the GPU).
    static final Object wallLock = new Object();
    static String cachedKey;
    static Bitmap cachedPhoto, cachedBlur;

    final BroadcastReceiver timeChanged = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { tick.run(); }
    };

    final BroadcastReceiver packagesChanged = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            appsChanged = true;
            if (hasWindowFocus() && layers.isEmpty()) buildDock();
        }
    };

    final Context ctx;

    // One bitmap per app, at the tile's pixel size, shared by the dock, the drawer and both
    // screens (the app and the overlay). Launcher drawables stay full size for as long as a view
    // holds them, which is where the extra graphics memory came from.
    static final Map<String, Bitmap> iconCache = new HashMap<>();
    static final List<HomeScreen> alive = new ArrayList<>();
    final Paint iconPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    Set<String> drawerIcons;
    ViewGroup drawerLayer;
    final ComponentCallbacks2 trimmer = new ComponentCallbacks2() {
        @Override public void onTrimMemory(int level) { trimIcons(); }
        @Override public void onConfigurationChanged(Configuration c) {}
        @Override public void onLowMemory() { trimIcons(); }
    };

    HomeScreen(Context c) {
        super(c);
        alive.add(this);
        ctx = c;
        prefs = ctx.getSharedPreferences("fireframe", Context.MODE_PRIVATE);
        pm = ctx.getPackageManager();
        DisplayMetrics dm = new DisplayMetrics();
        ((WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(dm);
        screenW = Math.max(dm.widthPixels, dm.heightPixels);
        screenH = Math.min(dm.widthPixels, dm.heightPixels);
        s = screenW / 1920f; // laid out at 1920x1080, scaled to the real screen
        medium = Typeface.createFromAsset(ctx.getAssets(), "fonts/ChakraPetch-Medium.ttf");
        semibold = Typeface.createFromAsset(ctx.getAssets(), "fonts/ChakraPetch-SemiBold.ttf");

        root = this;
        root.setClipChildren(false); // focused cards scale past the dock bar
        root.setBackgroundColor(0xFF000000);
        wallView = new ImageView(ctx);
        wallView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        root.addView(wallView, new FrameLayout.LayoutParams(-1, -1));
        // The outgoing wallpaper, laid over the new one and faded out, so a change dissolves instead of cutting.
        wallFade = new ImageView(ctx);
        wallFade.setScaleType(ImageView.ScaleType.CENTER_CROP);
        wallFade.setVisibility(GONE);
        root.addView(wallFade, new FrameLayout.LayoutParams(-1, -1));
        // Scrims keep white text readable on any wallpaper: one under the clock, date and weather,
        // and one rising from y=600 to 60% black at the bottom, darkest under the dock.
        View scrim = new View(ctx);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] {0x66000000, 0}));
        root.addView(scrim, new FrameLayout.LayoutParams(-1, px(400)));
        View low = new View(ctx);
        low.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {0, 0x59000000, 0x99000000}));
        root.addView(low, new FrameLayout.LayoutParams(-1, px(480), Gravity.BOTTOM));

        base = new FrameLayout(ctx);
        base.setClipChildren(false);
        root.addView(base, new FrameLayout.LayoutParams(-1, -1));

        // Top right: the time, with the date under it, right-aligned above the icon row. Its right edge
        // lines up with the settings button's and the dock's right edge.
        LinearLayout when = new LinearLayout(ctx);
        when.setOrientation(LinearLayout.VERTICAL);
        when.setGravity(Gravity.RIGHT);
        when.setClipChildren(false);
        clock = new TextView(ctx);
        style(clock, BIG_SIZE, medium); // the weather temperature's size
        clock.setShadowLayer(px(8), 0, px(2), 0x99000000);
        when.addView(clock, new LinearLayout.LayoutParams(-2, -2));
        date = new TextView(ctx);
        style(date, DATE_SIZE, medium);
        date.setShadowLayer(px(8), 0, px(1), 0x99000000);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-2, -2);
        dlp.topMargin = px(6);
        when.addView(date, dlp);
        FrameLayout.LayoutParams whenLp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.RIGHT);
        whenLp.topMargin = px(TOP_Y);
        whenLp.rightMargin = px(SIDE_X);
        base.addView(when, whenLp);

        // Top left: only the weather. The city (from Settings > Location) on top, then the condition
        // icon and temperature, then the condition and today's high and low, small. No box: it sits on
        // the top scrim. Hidden until a reading arrives.
        weatherBox = new LinearLayout(ctx);
        weatherBox.setOrientation(LinearLayout.VERTICAL);
        weatherBox.setVisibility(GONE);
        weatherCity = new TextView(ctx);
        style(weatherCity, CITY_SIZE, medium);
        weatherCity.setShadowLayer(px(8), 0, px(1), 0x99000000);
        weatherCity.setSingleLine();
        weatherCity.setEllipsize(TextUtils.TruncateAt.END);
        weatherCity.setMaxWidth(px(520));
        weatherBox.addView(weatherCity, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout wrow = new LinearLayout(ctx);
        wrow.setOrientation(LinearLayout.HORIZONTAL);
        wrow.setGravity(Gravity.CENTER_VERTICAL);
        weatherMark = new WeatherMark(ctx);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(px(54), px(54));
        ilp.leftMargin = -px(4); // the icon's own margin, so its shape lines up with the city's left edge
        wrow.addView(weatherMark, ilp);
        weatherTemp = new TextView(ctx);
        style(weatherTemp, BIG_SIZE, semibold);
        weatherTemp.setShadowLayer(px(8), 0, px(1), 0x99000000);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-2, -2);
        tlp.leftMargin = px(10);
        wrow.addView(weatherTemp, tlp);
        LinearLayout.LayoutParams rlp2 = new LinearLayout.LayoutParams(-2, -2);
        rlp2.topMargin = px(8);
        weatherBox.addView(wrow, rlp2);
        weatherWord = new TextView(ctx);
        // The condition and the high/low lines match the date on the right exactly.
        style(weatherWord, DATE_SIZE, medium);
        weatherWord.setShadowLayer(px(8), 0, px(1), 0x99000000);
        weatherWord.setSingleLine();
        weatherWord.setEllipsize(TextUtils.TruncateAt.END);
        weatherWord.setMaxWidth(px(520));
        LinearLayout.LayoutParams wwp = new LinearLayout.LayoutParams(-2, -2);
        wwp.topMargin = px(4);
        weatherBox.addView(weatherWord, wwp);
        weatherMore = new TextView(ctx);
        style(weatherMore, DATE_SIZE, medium);
        weatherMore.setShadowLayer(px(8), 0, px(1), 0x99000000);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(-2, -2);
        mlp.topMargin = px(4);
        weatherBox.addView(weatherMore, mlp);
        base.addView(weatherBox, at(SIDE_X, TOP_Y, -2, -2));

        // Top right, under the time and date: wallpaper, all apps, settings.
        View first = addIcon("image", 1920 - SIDE_X - 76 * 3 - 24 * 2, new View.OnClickListener() {
            @Override public void onClick(View v) { nextWallpaper(); }
        });
        addIcon("grid_view", 1920 - SIDE_X - 76 * 2 - 24, new View.OnClickListener() {
            @Override public void onClick(View v) { openDrawer(v); }
        });
        View last = addIcon("settings", 1920 - SIDE_X - 76, new View.OnClickListener() {
            @Override public void onClick(View v) { openSettingsMenu(v); }
        });
        // Left and Right stay in this row instead of dropping down to the dock.
        first.setNextFocusLeftId(first.getId());
        last.setNextFocusRightId(last.getId());

        caption = new TextView(ctx);
        style(caption, 22, medium);
        caption.setAlpha(0);
        caption.setShadowLayer(px(10), 0, px(1), 0x80000000);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.RIGHT);
        clp.topMargin = px(ICON_Y + 76 + 20);
        clp.rightMargin = px(SIDE_X);
        root.addView(caption, clp); // in root, not base, so it shows over the drawer and menus too

        // The dock: a "liquid glass" panel with square corners, inset 60px from each side like the top
        // corners. Inside: the wallpaper's blur (the same pre-blurred copy the menus use, so it costs no
        // live blurring), a 72% #1C1C1E tint, a faint top-to-bottom sheen and a thin light rim, brighter
        // along the top. No labels. The panel fits the cards tightly: six 276x155 cards with 24px gaps
        // and 12px all round (6 x 276 + 5 x 24 + 2 x 12 = 1800). Dock cards don't scale on focus, and
        // their 8px outline fits inside the 12px. The panel clips the scrolling row and ends on the
        // bottom 5% line (y=1026); the cards sit at y=859-1014.
        glass = new GlassView(ctx, DOCK_RADIUS, 0xB81C1C1E, 255);
        glass.liquid = true;
        rounded(glass, DOCK_RADIUS);
        dockScroller = new DockScroller(ctx);
        dockScroller.setHorizontalScrollBarEnabled(false);
        dockScroller.setOverScrollMode(View.OVER_SCROLL_NEVER);
        // Room on every side for a focused card's 1.1x scale, outline and shadow.
        dockScroller.setPadding(px(DOCK_PAD_X), px(DOCK_PAD_Y), px(DOCK_PAD_X), px(DOCK_PAD_Y));
        dockScroller.setClipToPadding(false);
        dockScroller.setClipChildren(false);
        dockRow = new LinearLayout(ctx);
        dockRow.setClipChildren(false); // focused tiles grow a little past the row
        dockScroller.addView(dockRow, new FrameLayout.LayoutParams(-2, -1));
        glass.addView(dockScroller, new FrameLayout.LayoutParams(-1, -1));
        base.addView(glass, at(SIDE_X, DOCK_Y, 1920 - 2 * SIDE_X, DOCK_TILE_H + 2 * DOCK_PAD_Y));

        showWallpaper(wallpaperKey());
        buildDock();
        // Focus asked for before the view is on screen can be lost, and Android then picks the first
        // focusable view it finds: the wallpaper button. So ask again once the first layout is done.
        getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override public void onGlobalLayout() {
                getViewTreeObserver().removeOnGlobalLayoutListener(this);
                focusDock();
            }
        });
    }

    @Override
    public void onWindowFocusChanged(boolean has) {
        super.onWindowFocusChanged(has);
        if (has && findFocus() == null) focusDock();
    }

    // The dock app last focused (the first one on launch), now and again after the next layout.
    void focusDock() {
        if (!layers.isEmpty() || dockRow.getChildCount() == 0) return;
        dockRow.getChildAt(Math.min(lastDock, dockRow.getChildCount() - 1)).requestFocus();
        post(() -> {
            if (!layers.isEmpty() || dockRow.getChildCount() == 0) return;
            View f = findFocus();
            if (f == null || f.getParent() == dockRow) dockRow.getChildAt(Math.min(lastDock, dockRow.getChildCount() - 1)).requestFocus();
        });
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        ctx.registerReceiver(packagesChanged, f);
        IntentFilter time = new IntentFilter(Intent.ACTION_TIME_CHANGED); // the TV syncs its clock after boot
        time.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        time.addAction(Intent.ACTION_DATE_CHANGED); // midnight, in case the minute tick was deferred
        ctx.registerReceiver(timeChanged, time);
        ctx.registerComponentCallbacks(trimmer);
        tick.run();
        weatherTick.run();
    }

    @Override
    protected void onDetachedFromWindow() {
        alive.remove(this);
        ctx.unregisterReceiver(packagesChanged);
        ctx.unregisterReceiver(timeChanged);
        ctx.unregisterComponentCallbacks(trimmer);
        removeCallbacks(tick);
        removeCallbacks(weatherTick);
        super.onDetachedFromWindow();
    }

    // fireframe.sh wallpaper starts the app with --es wallpaper custom (or a bundled name), and
    // --es from <photo in /data/local/tmp>: since Android 11 adb can't write into the app's folder.
    void applyExtras(Intent intent) {
        String w = intent == null ? null : intent.getStringExtra("wallpaper");
        String from = intent == null ? null : intent.getStringExtra("from");
        if (from != null && customWallpaper() != null) {
            try (InputStream in = new FileInputStream(from); OutputStream out = new FileOutputStream(customWallpaper())) {
                byte[] buf = new byte[65536];
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            } catch (Exception e) {
                return;
            }
        }
        if (w == null || !choices().contains(w)) return;
        if (CUSTOM.equals(w)) { cachedKey = null; photoVersion++; } // a new photo under the same name
        prefs.edit().putString("wallpaper", w).apply();
    }

    // Called whenever this screen comes to the front: catch up on changes made by the other copy
    // (the app or the overlay) or by installs and uninstalls. reset: Home was pressed, so close any
    // menus and go back to the dock.
    void onShown(boolean reset) {
        Updater.check(ctx, () -> showCaption("FireFrame " + Updater.available(ctx) + " is out. Update it from Settings, top right."));
        Weather.refresh(ctx);
        if (reset) while (!layers.isEmpty()) closeLayer();
        if (!wallpaperKey().equals(shownWallpaper) || seenPhotoVersion != photoVersion) showWallpaper(wallpaperKey());
        if ((appsChanged || seenDockVersion != dockVersion) && layers.isEmpty()) buildDock();
        if (!layers.isEmpty() || dockRow.getChildCount() == 0) return;
        View f = findFocus();
        // Both copies keep focus on the same dock app, so swapping one for the other can't be seen.
        if (reset || f == null || f.getParent() == dockRow) focusDock();
    }

    String wallpaperKey() {
        List<String> c = choices();
        String w = prefs.getString("wallpaper", c.get(0));
        return c.contains(w) ? w : c.get(0);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (e.getAction() == KeyEvent.ACTION_UP && !layers.isEmpty()) closeLayer();
            return true; // home screen: nothing behind it
        }
        return super.dispatchKeyEvent(e);
    }

    // ---- Apps ----

    // Every app that can be opened, by name.
    Map<String, String> installed() {
        final Map<String, String> labels = new HashMap<>();
        for (String cat : new String[] {Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            for (ResolveInfo r : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(cat), 0)) {
                String p = r.activityInfo.packageName;
                if (!p.equals(ctx.getPackageName()) && !HIDDEN.contains(p) && !labels.containsKey(p))
                    labels.put(p, r.activityInfo.applicationInfo.loadLabel(pm).toString());
            }
        }
        List<String> names = new ArrayList<>(labels.keySet());
        Collections.sort(names, (a, b) -> labels.get(a).compareToIgnoreCase(labels.get(b)));
        Map<String, String> sorted = new LinkedHashMap<>();
        for (String p : names) sorted.put(p, labels.get(p));
        return sorted;
    }

    List<String> dockApps(Map<String, String> apps) {
        List<String> out = new ArrayList<>();
        String saved = prefs.getString("dock", null);
        if (saved != null) {
            for (String p : saved.split(",")) if (apps.containsKey(p)) out.add(p);
            return out;
        }
        for (String p : DEFAULT_DOCK) if (apps.containsKey(p) && !out.contains(p)) out.add(p);
        for (String p : apps.keySet()) {
            if (out.size() >= 6) break;
            if (!out.contains(p) && !isSystem(p)) out.add(p);
        }
        return out;
    }

    boolean isSystem(String p) {
        try {
            return (pm.getApplicationInfo(p, 0).flags & ApplicationInfo.FLAG_SYSTEM) != 0;
        } catch (PackageManager.NameNotFoundException e) {
            return true;
        }
    }

    void saveDock() {
        prefs.edit().putString("dock", TextUtils.join(",", dock)).apply();
        dockVersion++;
    }

    void buildDock() {
        appsChanged = false;
        seenDockVersion = dockVersion;
        Map<String, String> apps = installed();
        View focused = findFocus();
        int keep = focused != null && focused.getParent() == dockRow ? dockRow.indexOfChild(focused) : 0;
        dock = dockApps(apps);
        dockRow.removeAllViews();
        for (String p : dock) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(DOCK_W), px(DOCK_TILE_H));
            if (dockRow.getChildCount() > 0) lp.leftMargin = px(DOCK_GAP);
            dockRow.addView(tile(p, apps.get(p), DOCK_W, DOCK_TILE_H, true), lp);
        }
        if (dock.isEmpty()) {
            View add = textTile("Add apps", DOCK_W, DOCK_W * 9 / 16);
            add.setOnClickListener(this::openDrawer);
            dockRow.addView(add, new LinearLayout.LayoutParams(px(DOCK_W), px(DOCK_W * 9 / 16)));
        }
        glass.invalidate(); // the edge fades depend on how many apps there are
        // Left on the first app and Right on the last stay in the dock instead of jumping to the top row.
        View firstTile = dockRow.getChildAt(0), lastTile = dockRow.getChildAt(dockRow.getChildCount() - 1);
        firstTile.setNextFocusLeftId(firstTile.getId());
        lastTile.setNextFocusRightId(lastTile.getId());
        if (Build.VERSION.SDK_INT >= 26) firstTile.setFocusedByDefault(true);
        if (layers.isEmpty()) dockRow.getChildAt(Math.min(keep, dockRow.getChildCount() - 1)).requestFocus();
        trimIcons();
    }

    void launch(final String p, String label) {
        // The Appstore's screens are Amazon's launcher on Fire OS 7: leave them alone until Home.
        if (p.equals(HomeService.APPSTORE)) {
            HomeService.pause(false);
            startApp(p);
            return;
        }
        // On the overlay, in the 5 seconds after Home, only Amazon's launcher can open apps right away.
        if (ctx instanceof HomeService) ((HomeService) ctx).launchThroughAmazon(label, () -> startApp(p), 0);
        else startApp(p);
    }

    void startApp(String p) {
        Intent i = pm.getLeanbackLaunchIntentForPackage(p);
        if (i == null) i = pm.getLaunchIntentForPackage(p);
        try {
            ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            showCaption("Couldn't open that app");
        }
    }

    // ---- Tiles ----

    // Every app tile, in the dock and the drawer: a 16:9 card with its name centred under it.
    // Dock tiles: 276x155 cards with no label, 24px apart. (The drawer's are in openDrawer.)
    static final int LABEL_GAP = 8;
    static final int DOCK_W = 276, DOCK_TILE_H = 155, DOCK_GAP = 24, DOCK_Y = 847, DOCK_RADIUS = 0;
    // The top corners: weather on the left, time, date and icons on the right, 60px from the sides
    // like the dock's cards.
    static final int SIDE_X = 60, TOP_Y = 52, ICON_Y = 196;
    // Top text: the time and temperature match, and so do the date, condition and high/low.
    static final int BIG_SIZE = 54, CITY_SIZE = 38, DATE_SIZE = 36;
    // The row scrolls a whole card at a time, so six cards always sit fully inside the bar. The 22px
    // between the bar's padding and the next card shows a sliver of it, faded out, as the "more" hint.
    static final int DOCK_PAD_X = 12, DOCK_PAD_Y = 12, DOCK_FADE = 22;

    View tile(final String p, String label, int w, int h, final boolean inDock) {
        FrameLayout t = focusable();
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setClipChildren(false); // the glow reaches past the card
        int cw = px(w), ch = px(w * 9 / 16);
        FrameLayout card = new FrameLayout(ctx);
        card.setClipChildren(false);
        ImageView art = new ImageView(ctx);
        Bitmap picture = cardBitmap(p, cw, ch);
        if (picture != null) art.setImageBitmap(picture);
        art.setScaleType(ImageView.ScaleType.FIT_XY);
        View[] ring = ringAround(card, art, cw, ch, 16);
        col.addView(card, new LinearLayout.LayoutParams(cw, ch));
        TextView name = new TextView(ctx);
        style(name, 32, medium);
        name.setShadowLayer(px(6), 0, px(1), 0x99000000);
        name.setSingleLine();
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setText(label);
        name.setGravity(Gravity.CENTER);
        // Full tile width, so every label is centred the same way and shares one baseline.
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(-1, -2);
        nlp.topMargin = px(LABEL_GAP);
        col.addView(name, nlp);
        t.addView(col, new FrameLayout.LayoutParams(-1, -1));
        t.setPivotY(ch / 2f); // grow around the card, not the card plus its label
        // The dock has no labels; the drawer always shows them.
        if (inDock) {
            name.setVisibility(GONE);
            focusLook(t, ring[0], ring[1], null, TILE_REST, art);
        }
        else focusLook(t, ring[0], ring[1], null, TILE_REST, art, name);

        t.setOnClickListener(v -> { press(v); launch(p, label); });
        t.setOnLongClickListener(v -> { appMenu(v, p, label, inDock); return true; });
        t.setOnKeyListener((v, k, e) -> {
            if (k != KeyEvent.KEYCODE_MENU) return false;
            if (e.getAction() == KeyEvent.ACTION_UP) appMenu(v, p, label, inDock);
            return true;
        });
        return t;
    }

    // One 16:9 card per app, decoded at the size it is drawn (256x144 at 1080p).
    Bitmap cardBitmap(String pkg, int w, int h) {
        Bitmap b = iconCache.get(pkg);
        if (b != null && !b.isRecycled() && b.getWidth() == w && b.getHeight() == h) return b;
        b = makeCard(pkg, w, h, Math.max(1, px(16)));
        if (b == null) return null;
        b.prepareToDraw();
        iconCache.put(pkg, b);
        return b;
    }

    // Apps whose card is their own banner, which already shows the app's name.
    static final Set<String> bannerCards = new HashSet<>();

    Bitmap makeCard(String pkg, int w, int h, int rad) {
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        Path clip = new Path();
        clip.addRoundRect(new RectF(0, 0, w, h), rad, rad, Path.Direction.CW);
        canvas.clipPath(clip);
        try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            Resources res = pm.getResourcesForApplication(ai);
            int bannerId = 0;
            for (ResolveInfo r : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER).setPackage(pkg), 0)) {
                if (r.activityInfo != null && r.activityInfo.banner != 0) { bannerId = r.activityInfo.banner; break; }
            }
            if (bannerId == 0) bannerId = ai.banner;
            // 1. The app's own TV banner.
            bannerCards.remove(pkg);
            if (bannerId != 0 && drawBanner(canvas, res, bannerId, w, h)) {
                bannerCards.add(pkg);
                return out;
            }
            // 2. No banner: build one from the icon. Adaptive icons give their logo and background apart.
            Bitmap icon = ai.icon != 0 ? decodeSampled(res, ai.icon, Math.max(1, Math.round(h * 0.8f))) : null;
            if (icon == null && ai.icon != 0 && adaptiveCard(canvas, res, ai.icon, w, h)) return out;
            Drawable drawn = null;
            if (icon == null) {
                drawn = pm.getApplicationIcon(pkg);
                int is = Math.max(1, Math.round(h * 0.8f));
                icon = Bitmap.createBitmap(is, is, Bitmap.Config.ARGB_8888);
                Canvas ic = new Canvas(icon);
                drawn.setBounds(0, 0, is, is);
                drawn.draw(ic);
            }
            // 3a. A box icon (a filled square or rounded square with the logo on it): its rounded corners
            // are cropped off and its own edge pixels are stretched out to the card's edges, so the card
            // is one seamless surface whatever colour or gradient the box has.
            Rect box = solidBox(icon);
            if (box != null) {
                int cropX = Math.max(1, Math.round(box.width() * 0.03f));
                int cropY = Math.max(1, Math.round(box.height() * 0.14f));
                Bitmap inner = Bitmap.createBitmap(icon, box.left + cropX, box.top + cropY,
                        Math.max(1, box.width() - 2 * cropX), Math.max(1, box.height() - 2 * cropY));
                float k = h * 0.8f / inner.getHeight();
                Matrix m = new Matrix();
                m.setScale(k, k);
                m.postTranslate((w - inner.getWidth() * k) / 2f, (h - inner.getHeight() * k) / 2f);
                BitmapShader edge = new BitmapShader(inner, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                edge.setLocalMatrix(m);
                Paint fillCard = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
                fillCard.setShader(edge);
                canvas.drawRect(0, 0, w, h, fillCard);
                if (inner != icon && !inner.isRecycled()) inner.recycle();
            } else {
                // 3b. A free-standing logo, centred on a soft gradient of its main colour.
                int[] tint = cardTint(icon);
                Paint bg = new Paint();
                bg.setShader(new LinearGradient(0, 0, 0, h, tint[0], tint[1], Shader.TileMode.CLAMP));
                canvas.drawRect(0, 0, w, h, bg);
                int is = Math.max(1, Math.round(h * 0.6f));
                canvas.drawBitmap(icon, new Rect(0, 0, icon.getWidth(), icon.getHeight()),
                        new RectF((w - is) / 2f, (h - is) / 2f, (w + is) / 2f, (h + is) / 2f), iconPaint);
            }
            if (icon != null && !icon.isRecycled()) icon.recycle();
            if (drawn != null) freeDrawable(drawn);
        } catch (Exception e) {
            canvas.drawColor(0xFF2A2A2E);
        }
        return out;
    }

    // A banner fills the card when it is (nearly) 16:9. Any other shape is shown whole on its own edge
    // colour: banners are never stretched, and never cropped by more than a sliver.
    static final float BANNER_SLACK = 0.06f;

    static boolean nearlyCard(float iw, float ih, int w, int h) {
        return Math.abs((iw / ih) / (w / (float) h) - 1) <= BANNER_SLACK;
    }

    boolean drawBanner(Canvas canvas, Resources res, int id, int w, int h) {
        canvas.drawColor(0xFF1E1E22); // under any see-through parts of the banner
        Bitmap sampled = decodeSampled(res, id, Math.max(w, h));
        if (sampled != null) {
            if (nearlyCard(sampled.getWidth(), sampled.getHeight(), w, h)) drawCover(canvas, sampled, w, h);
            else {
                int edge = edgeColor(sampled);
                canvas.drawColor(edge != 0 ? edge : dominant(sampled));
                float scale = Math.min(w / (float) sampled.getWidth(), h / (float) sampled.getHeight());
                float dw = sampled.getWidth() * scale, dh = sampled.getHeight() * scale;
                canvas.drawBitmap(sampled, new Rect(0, 0, sampled.getWidth(), sampled.getHeight()),
                        new RectF((w - dw) / 2f, (h - dh) / 2f, (w + dw) / 2f, (h + dh) / 2f), iconPaint);
            }
            if (!sampled.isRecycled()) sampled.recycle();
            return true;
        }
        try {
            Drawable d = res.getDrawable(id, null);
            if (d == null) return false;
            int iw = d.getIntrinsicWidth(), ih = d.getIntrinsicHeight();
            if (iw <= 0 || ih <= 0) d.setBounds(0, 0, w, h);
            else {
                float scale = nearlyCard(iw, ih, w, h) ? Math.max(w / (float) iw, h / (float) ih)
                        : Math.min(w / (float) iw, h / (float) ih);
                int dw = Math.round(iw * scale), dh = Math.round(ih * scale);
                int x = (w - dw) / 2, y = (h - dh) / 2;
                d.setBounds(x, y, x + dw, y + dh);
            }
            d.draw(canvas);
            freeDrawable(d);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    void drawCover(Canvas canvas, Bitmap src, int w, int h) {
        float scale = Math.max(w / (float) src.getWidth(), h / (float) src.getHeight());
        float dw = src.getWidth() * scale, dh = src.getHeight() * scale;
        canvas.drawBitmap(src, new Rect(0, 0, src.getWidth(), src.getHeight()),
                new RectF((w - dw) / 2f, (h - dh) / 2f, (w + dw) / 2f, (h + dh) / 2f), iconPaint);
    }

    // Fallback card colours {top, bottom} for an icon. A box-shaped icon with an even edge gets that
    // edge colour, flat, so the box melts into the card. A free-standing logo gets a soft gradient of
    // its main colour, darkened under light logos and lightened under dark ones so it stays readable.
    // Returns {top, bottom} for the card behind a free-standing logo.
    static int[] cardTint(Bitmap icon) {
        int d = dominant(icon);
        int base = luma(d) < 0.22f ? mix(d, 0xFFE6E6EA, 0.75f) : mix(d, 0xFF141418, 0.62f);
        return new int[] {mix(base, 0xFFFFFFFF, 0.08f), mix(base, 0xFF000000, 0.14f)};
    }

    // The bounds of an icon's solid body if that body is a filled box (at least 88% of its bounding
    // rectangle opaque: squares and rounded squares, not circles or free-standing logos), else null.
    static Rect solidBox(Bitmap b) {
        int bw = b.getWidth(), bh = b.getHeight();
        if (bw < 8 || bh < 8) return null;
        int[] all = new int[bw * bh];
        b.getPixels(all, 0, bw, 0, 0, bw, bh);
        int l = bw, t = bh, r = -1, bot = -1;
        for (int y = 0; y < bh; y++) for (int x = 0; x < bw; x++) {
            if ((all[y * bw + x] >>> 24) < 200) continue;
            if (x < l) l = x;
            if (x > r) r = x;
            if (y < t) t = y;
            if (y > bot) bot = y;
        }
        if (r < 0) return null;
        int w = r - l + 1, h = bot - t + 1;
        if (w < bw / 2 || h < bh / 2 || w * 10 < h * 8 || h * 10 < w * 8) return null; // roughly square
        int solid = 0;
        for (int y = t; y <= bot; y++) for (int x = l; x <= r; x++) if ((all[y * bw + x] >>> 24) >= 200) solid++;
        return solid >= w * h * 0.88f ? new Rect(l, t, r + 1, bot + 1) : null;
    }

    // The colour along the edges of a bitmap's solid body, or 0 if it has none, or its edges are uneven
    // (a photo, a gradient). Icons often sit in a see-through margin, so the body is found first. Only
    // the middle 60% of each side is sampled, so rounded corners don't count.
    static int edgeColor(Bitmap b) {
        int bw = b.getWidth(), bh = b.getHeight();
        if (bw < 8 || bh < 8) return 0;
        int[] all = new int[bw * bh];
        b.getPixels(all, 0, bw, 0, 0, bw, bh);
        int l = bw, t = bh, r0 = -1, b0 = -1;
        for (int y = 0; y < bh; y++) for (int x = 0; x < bw; x++) {
            if ((all[y * bw + x] >>> 24) < 200) continue;
            if (x < l) l = x;
            if (x > r0) r0 = x;
            if (y < t) t = y;
            if (y > b0) b0 = y;
        }
        int w = r0 - l + 1, h = b0 - t + 1;
        if (r0 < 0 || w < bw / 2 || h < bh / 2) return 0;
        int in = Math.max(1, Math.round(Math.min(w, h) * 0.05f));
        int[] px = new int[48];
        for (int i = 0; i < 12; i++) {
            float f = 0.2f + 0.6f * i / 11f;
            int x = l + Math.round(f * (w - 1)), y = t + Math.round(f * (h - 1));
            px[i * 4] = all[(t + in) * bw + x];
            px[i * 4 + 1] = all[(b0 - in) * bw + x];
            px[i * 4 + 2] = all[y * bw + l + in];
            px[i * 4 + 3] = all[y * bw + r0 - in];
        }
        long r = 0, g = 0, bl = 0;
        for (int p : px) {
            if ((p >>> 24) < 230) return 0;
            r += (p >> 16) & 255;
            g += (p >> 8) & 255;
            bl += p & 255;
        }
        int ar = (int) (r / px.length), ag = (int) (g / px.length), ab = (int) (bl / px.length);
        for (int p : px) {
            if (Math.abs(((p >> 16) & 255) - ar) > 36 || Math.abs(((p >> 8) & 255) - ag) > 36
                    || Math.abs((p & 255) - ab) > 36) return 0;
        }
        return 0xFF000000 | (ar << 16) | (ag << 8) | ab;
    }

    static float luma(int c) {
        return (0.2126f * ((c >> 16) & 255) + 0.7152f * ((c >> 8) & 255) + 0.0722f * (c & 255)) / 255f;
    }

    // a moved toward b by t (0..1), opaque.
    static int mix(int a, int b, float t) {
        int r = Math.round(((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
        int g = Math.round(((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
        int bl = Math.round((a & 255) * (1 - t) + (b & 255) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    // An adaptive icon as a card: its background layer's colour fills the card and its foreground logo
    // sits in the middle, unmasked. The logo's safe zone (the middle two thirds of the layer) is 60% of
    // the card's height, like the plain icons. False if this isn't an adaptive icon.
    boolean adaptiveCard(Canvas canvas, Resources res, int id, int w, int h) {
        if (Build.VERSION.SDK_INT < 26) return false;
        XmlResourceParser xml = null;
        List<Bitmap> owned = new ArrayList<>();
        try {
            xml = res.getXml(id);
            int type = xml.next();
            while (type != XmlPullParser.END_DOCUMENT && type != XmlPullParser.START_TAG) type = xml.next();
            if (type != XmlPullParser.START_TAG || !"adaptive-icon".equals(xml.getName())) return false;
            int fgSize = Math.max(1, Math.round(h * 0.9f));
            Drawable bg = null, fg = null;
            while ((type = xml.next()) != XmlPullParser.END_DOCUMENT) {
                if (type != XmlPullParser.START_TAG) continue;
                String name = xml.getName();
                if ("background".equals(name)) bg = loadLayer(res, xml, 48, owned);
                else if ("foreground".equals(name)) fg = loadLayer(res, xml, fgSize, owned);
            }
            if (fg == null) return false;
            int fill = 0xFF2A2A2E;
            if (bg instanceof ColorDrawable) fill = ((ColorDrawable) bg).getColor() | 0xFF000000;
            else if (bg != null) {
                Bitmap small = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888);
                owned.add(small);
                bg.setBounds(0, 0, 48, 48);
                bg.draw(new Canvas(small));
                fill = dominant(small);
            }
            canvas.drawColor(fill);
            fg.setBounds((w - fgSize) / 2, (h - fgSize) / 2, (w + fgSize) / 2, (h + fgSize) / 2);
            fg.draw(canvas);
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            if (xml != null) xml.close();
            for (int i = 0; i < owned.size(); i++) {
                Bitmap b = owned.get(i);
                if (b != null && !b.isRecycled()) b.recycle();
            }
        }
    }

    static int dominant(Bitmap b) {
        int sw = b.getWidth(), sh = b.getHeight();
        if (sw <= 0 || sh <= 0) return 0xFF2A2A2E;
        int cols = Math.min(12, sw), rows = Math.min(12, sh);
        long red = 0, green = 0, blue = 0, n = 0;
        for (int y = 0; y < rows; y++) for (int x = 0; x < cols; x++) {
            int px = b.getPixel(x * sw / cols, y * sh / rows);
            if ((px >>> 24) < 128) continue;
            red += (px >> 16) & 255;
            green += (px >> 8) & 255;
            blue += px & 255;
            n++;
        }
        if (n == 0) return 0xFF2A2A2E;
        return 0xFF000000 | ((int) (red / n) << 16) | ((int) (green / n) << 8) | (int) (blue / n);
    }

    Bitmap rasterIcon(String pkg, int size) {
        List<Bitmap> owned = new ArrayList<>();
        Bitmap keep = null;
        try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            if (ai.icon == 0) {
                keep = pack(frameDrawable(pm.getApplicationIcon(pkg), size));
                return keep;
            }
            Resources res = pm.getResourcesForApplication(ai);
            Bitmap direct = decodeSampled(res, ai.icon, size);
            if (direct != null) {
                keep = pack(frameBitmap(direct, size));
                return keep;
            }
            // Adaptive icons are XML. Inflating them loads every layer at full size, so the
            // layers are sampled down first and only then masked like the system drawable.
            if (Build.VERSION.SDK_INT >= 26) {
                Bitmap masked = adaptive(res, ai.icon, size, owned);
                if (masked != null) {
                    keep = masked;
                    return keep;
                }
            }
            Drawable d = res.getDrawable(ai.icon, null);
            if (d == null) return null;
            keep = pack(frameDrawable(d, size));
            freeDrawable(d); // the small copy is what we keep; the full asset is not
            return keep;
        } catch (Exception e) {
            return null;
        } finally {
            for (int i = 0; i < owned.size(); i++) {
                Bitmap b = owned.get(i);
                if (b != null && b != keep && !b.isRecycled()) b.recycle();
            }
        }
    }

    // Largest power-of-two sample that still leaves at least `size` pixels on both sides.
    static Bitmap decodeSampled(Resources res, int id, int size) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        try (InputStream in = res.openRawResource(id)) { BitmapFactory.decodeStream(in, null, o); }
        catch (Exception e) { return null; }
        if (o.outWidth <= 0 || o.outHeight <= 0) return null;
        o.inSampleSize = 1;
        while (o.outWidth / (o.inSampleSize * 2) >= size && o.outHeight / (o.inSampleSize * 2) >= size)
            o.inSampleSize *= 2;
        o.inJustDecodeBounds = false;
        o.inScaled = false;
        o.inPreferredConfig = Bitmap.Config.ARGB_8888;
        try (InputStream in = res.openRawResource(id)) { return BitmapFactory.decodeStream(in, null, o); }
        catch (Exception e) { return null; }
    }

    static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";

    // Only called on API 26+, where AdaptiveIconDrawable exists. Returns null if this resource
    // isn't an adaptive icon, so the caller can draw it as a normal drawable.
    Bitmap adaptive(Resources res, int id, int size, List<Bitmap> owned) {
        XmlResourceParser xml = null;
        try {
            xml = res.getXml(id);
            int type = xml.next();
            while (type != XmlPullParser.END_DOCUMENT && type != XmlPullParser.START_TAG) type = xml.next();
            if (type != XmlPullParser.START_TAG || !"adaptive-icon".equals(xml.getName())) return null;
            // Layers are drawn at 1.5x the viewport, then the system mask crops them to `size`.
            int layer = Math.max(size, Math.round(size * 1.5f));
            Drawable bg = null, fg = null;
            while ((type = xml.next()) != XmlPullParser.END_DOCUMENT) {
                if (type != XmlPullParser.START_TAG) continue;
                String name = xml.getName();
                if (!"background".equals(name) && !"foreground".equals(name)) continue;
                Drawable d = loadLayer(res, xml, layer, owned);
                if ("background".equals(name)) bg = d; else fg = d;
            }
            if (bg == null && fg == null) return null;
            Drawable icon = new AdaptiveIconDrawable(bg, fg);
            icon.setBounds(0, 0, size, size);
            return pack(blit(icon, size));
        } catch (Exception e) {
            return null;
        } finally {
            if (xml != null) xml.close();
        }
    }

    Drawable loadLayer(Resources res, XmlResourceParser xml, int px, List<Bitmap> owned) {
        int ref = xml.getAttributeResourceValue(ANDROID_NS, "drawable", 0);
        if (ref == 0) {
            String v = xml.getAttributeValue(ANDROID_NS, "drawable");
            if (v != null && v.startsWith("#")) {
                try { return new ColorDrawable(Color.parseColor(v)); } catch (Exception e) { return null; }
            }
            return null;
        }
        Bitmap sampled = decodeSampled(res, ref, px);
        if (sampled != null) return bitmapLayer(res, sampled, owned);
        try {
            Drawable d = res.getDrawable(ref, null);
            if (d instanceof ColorDrawable || d == null) return d;
            Bitmap rendered = fill(d, px);
            freeDrawable(d);
            return bitmapLayer(res, rendered, owned);
        } catch (Exception e) {
            return null;
        }
    }

    Drawable bitmapLayer(Resources res, Bitmap b, List<Bitmap> owned) {
        owned.add(b);
        b.setDensity(Bitmap.DENSITY_NONE);
        BitmapDrawable d = new BitmapDrawable(res, b);
        d.setTargetDensity(Bitmap.DENSITY_NONE);
        d.setFilterBitmap(true);
        return d;
    }

    Bitmap fill(Drawable d, int size) {
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        d.setBounds(0, 0, size, size);
        d.draw(c);
        return out;
    }

    Bitmap frameBitmap(Bitmap src, int size) {
        float scale = Math.max(size / (float) src.getWidth(), size / (float) src.getHeight());
        int w = Math.max(1, Math.round(src.getWidth() * scale));
        int h = Math.max(1, Math.round(src.getHeight() * scale));
        Bitmap scaled = (w == src.getWidth() && h == src.getHeight()) ? src : Bitmap.createScaledBitmap(src, w, h, true);
        Bitmap out = scaled;
        if (scaled.getWidth() != size || scaled.getHeight() != size) {
            out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            new Canvas(out).drawBitmap(scaled, (size - scaled.getWidth()) / 2f, (size - scaled.getHeight()) / 2f, iconPaint);
        }
        if (scaled != src && scaled != out && !scaled.isRecycled()) scaled.recycle();
        if (src != out && !src.isRecycled()) src.recycle();
        return out;
    }

    Bitmap blit(Drawable d, int size) {
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        d.draw(new Canvas(out));
        return out;
    }

    Bitmap frameDrawable(Drawable d, int size) {
        Drawable src = d.mutate();
        if (src instanceof BitmapDrawable) ((BitmapDrawable) src).setFilterBitmap(true);
        int iw = src.getIntrinsicWidth(), ih = src.getIntrinsicHeight();
        if (iw <= 0 || ih <= 0) src.setBounds(0, 0, size, size);
        else {
            float scale = Math.max(size / (float) iw, size / (float) ih);
            int w = Math.max(1, Math.round(iw * scale)), h = Math.max(1, Math.round(ih * scale));
            int x = (size - w) / 2, y = (size - h) / 2;
            src.setBounds(x, y, x + w, y + h);
        }
        return blit(src, size);
    }

    // Fully opaque icons don't need an alpha channel. Masked ones (the rounded system shape) do,
    // so the corners outside the mask stay transparent and the tile looks the same.
    Bitmap pack(Bitmap argb) {
        if (!opaque(argb)) {
            argb.setDensity(Bitmap.DENSITY_NONE);
            return argb;
        }
        Bitmap rgb = Bitmap.createBitmap(argb.getWidth(), argb.getHeight(), Bitmap.Config.RGB_565);
        new Canvas(rgb).drawBitmap(argb, 0, 0, iconPaint);
        argb.recycle();
        rgb.setDensity(Bitmap.DENSITY_NONE);
        return rgb;
    }

    void freeDrawable(Drawable d) {
        if (Build.VERSION.SDK_INT >= 26) freeAdaptive(d);
        else freeBitmaps(d);
    }

    // AdaptiveIconDrawable is API 26. Kept in its own method so older Fire OS never loads the class.
    void freeAdaptive(Drawable d) {
        if (d instanceof AdaptiveIconDrawable) {
            AdaptiveIconDrawable a = (AdaptiveIconDrawable) d;
            freeBitmaps(a.getBackground());
            freeBitmaps(a.getForeground());
        } else freeBitmaps(d);
    }

    static void freeBitmaps(Drawable d) {
        if (d instanceof BitmapDrawable) {
            Bitmap b = ((BitmapDrawable) d).getBitmap();
            if (b != null && !b.isRecycled()) b.recycle();
        } else if (d instanceof LayerDrawable) {
            LayerDrawable layer = (LayerDrawable) d;
            for (int i = 0; i < layer.getNumberOfLayers(); i++) freeBitmaps(layer.getDrawable(i));
        }
    }

    static boolean opaque(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight(), n = w * h;
        int[] px = new int[n];
        b.getPixels(px, 0, w, 0, 0, w, h);
        for (int i = 0; i < n; i++) if ((px[i] >>> 24) != 0xff) return false;
        return true;
    }

    static void trimIcons() {
        Set<String> keep = new HashSet<>();
        for (int i = 0; i < alive.size(); i++) {
            HomeScreen h = alive.get(i);
            keep.addAll(h.dock);
            if (h.drawerIcons != null) keep.addAll(h.drawerIcons);
        }
        List<String> drop = new ArrayList<>();
        for (String p : iconCache.keySet()) if (!keep.contains(p)) drop.add(p);
        for (int i = 0; i < drop.size(); i++) {
            Bitmap b = iconCache.remove(drop.get(i));
            if (b != null && !b.isRecycled()) b.recycle();
        }
    }

    View textTile(String text, int w, int h) {
        FrameLayout t = focusable();
        TextView label = new TextView(ctx);
        style(label, 32, medium);
        label.setText(text);
        label.setGravity(Gravity.CENTER);
        View[] ring = ringAround(t, label, px(w), px(h), 32);
        focusLook(t, ring[0], ring[1], null, DIM, label);
        return t;
    }

    // ---- Focus ----
    // One look for the dock, the drawer and the top right icons, readable from across the room: the
    // focused one gets a glowing rim (a thin dark line against the card, so it reads on white cards,
    // then a crisp white border, then a soft white glow) and full brightness. App cards rest at 90%
    // brightness; icons and drawer labels at 60%. Drawer cards and icons also lift to 1.1x; dock cards
    // don't, and their rim and glow (12px in all) fit inside the dock panel's 12px inset.
    static final float FOCUS_SCALE = 1.1f, DIM = 0.6f, TILE_REST = 0.9f;
    static final int FOCUS_MS = 180;
    // At 1080p: a 4px white border outside a 2px dark line; the glow reaches about 6px past the border.
    static final float OUTLINE = 4, EDGE = 2, SHADOW = 22;

    FrameLayout focusable() {
        FrameLayout t = new FrameLayout(ctx);
        t.setId(View.generateViewId());
        t.setFocusable(true);
        t.setFocusableInTouchMode(true); // TVs have no touch; this just lets focus land after adb or a reboot
        t.setClickable(true);
        t.setClipChildren(false);
        t.setOutlineProvider(null); // translationZ only puts it above its neighbours; no system shadow
        return t;
    }

    // Adds content to holder (sized w x h) with a shadow behind it and an outline around it.
    // Returns {shadow, outline}.
    View[] ringAround(FrameLayout holder, View content, int w, int h, int radius) {
        holder.setClipChildren(false);
        int g = px(SHADOW), o = Math.max(2, px(OUTLINE)) + Math.max(1, px(EDGE));
        Ring glow = new Ring(ctx, true, px(radius) + g);
        FrameLayout.LayoutParams glp = new FrameLayout.LayoutParams(w + 2 * g, h + 2 * g);
        glp.leftMargin = glp.topMargin = -g;
        holder.addView(glow, glp);
        holder.addView(content, new FrameLayout.LayoutParams(w, h, Gravity.CENTER));
        Ring line = new Ring(ctx, false, px(radius) + o);
        FrameLayout.LayoutParams llp = new FrameLayout.LayoutParams(w + 2 * o, h + 2 * o);
        llp.leftMargin = llp.topMargin = -o;
        holder.addView(line, llp);
        return new View[] {glow, line};
    }

    // shown: a view seen only with focus (fades 0 to 1); dimmed: images at imageRest brightness and
    // text at 60% until focused.
    void focusLook(final View t, final View glow, final View line, final View shown, final float imageRest,
            final View... dimmed) {
        final float[] level = {0}; // 0 resting, 1 focused
        focusLevel(0, glow, line, shown, imageRest, dimmed);
        t.setOnFocusChangeListener((v, has) -> {
            if (has && t.getParent() == dockRow) lastDock = dockRow.indexOfChild(t);
            t.setTranslationZ(has ? 1 : 0);
            // Dock cards don't grow: the outline alone shows focus, so they fit the panel tightly.
            float lift = has && t.getParent() != dockRow ? FOCUS_SCALE : 1;
            t.animate().scaleX(lift).scaleY(lift)
                    .setDuration(FOCUS_MS).setInterpolator(ease).start();
            Object old = t.getTag();
            if (old instanceof ValueAnimator) ((ValueAnimator) old).cancel();
            ValueAnimator a = ValueAnimator.ofFloat(level[0], has ? 1 : 0);
            a.setDuration(FOCUS_MS);
            a.setInterpolator(ease);
            a.addUpdateListener(an -> {
                level[0] = (float) an.getAnimatedValue();
                focusLevel(level[0], glow, line, shown, imageRest, dimmed);
            });
            t.setTag(a);
            a.start();
        });
    }

    void focusLevel(float f, View glow, View line, View shown, float imageRest, View[] dimmed) {
        glow.setAlpha(f);
        line.setAlpha(f);
        if (shown != null) shown.setAlpha(f);
        int g = Math.round(255 * (imageRest + (1 - imageRest) * f));
        float text = DIM + (1 - DIM) * f;
        for (View d : dimmed) {
            if (d instanceof ImageView) ((ImageView) d).setColorFilter(Color.rgb(g, g, g), PorterDuff.Mode.MULTIPLY);
            else d.setAlpha(text);
        }
    }

    // The rim, or the soft glow behind it. The glow is drawn once per size and shared.
    static final Map<String, Bitmap> glowCache = new HashMap<>();

    class Ring extends View {
        final boolean glow;
        final float radius;
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        final Paint dark = new Paint(Paint.ANTI_ALIAS_FLAG);
        final RectF rect = new RectF();

        Ring(Context c, boolean glow, float radius) {
            super(c);
            this.glow = glow;
            this.radius = radius;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(2, px(OUTLINE)));
            paint.setColor(0xFFFFFFFF); // the glow bitmap is alpha only, drawn in this colour
            dark.setStyle(Paint.Style.STROKE);
            dark.setStrokeWidth(Math.max(1, px(EDGE)));
            dark.setColor(0xA6000000);
            setAlpha(0);
        }

        @Override
        protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            if (glow) {
                Bitmap b = glowBitmap(w, h, radius);
                if (b != null) c.drawBitmap(b, 0, 0, paint);
                return;
            }
            // White border on the outside, a thin dark line inside it hugging the card.
            float e = paint.getStrokeWidth() / 2;
            rect.set(e, e, w - e, h - e);
            c.drawRoundRect(rect, radius - e, radius - e, paint);
            float d = paint.getStrokeWidth() + dark.getStrokeWidth() / 2;
            rect.set(d, d, w - d, h - d);
            c.drawRoundRect(rect, radius - d, radius - d, dark);
        }
    }

    Bitmap glowBitmap(int w, int h, float radius) {
        String key = w + "x" + h + "r" + radius;
        Bitmap b = glowCache.get(key);
        if (b != null && !b.isRecycled()) return b;
        try {
            b = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8);
        } catch (OutOfMemoryError e) {
            return null;
        }
        // A blurred white line along the rim's outer edge. Its inner half sits under the border and the
        // card; the outer half is the glow, fading out about 6px past the border.
        int g = px(SHADOW), o = Math.max(2, px(OUTLINE)) + Math.max(1, px(EDGE));
        float at = g - o + px(1);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1, px(3)));
        p.setColor(0xE6FFFFFF);
        p.setMaskFilter(new BlurMaskFilter(Math.max(1, px(5)), BlurMaskFilter.Blur.NORMAL));
        float r = Math.max(0, radius - g) + o - px(1);
        new Canvas(b).drawRoundRect(new RectF(at, at, w - at, h - at), r, r, p);
        b.prepareToDraw();
        glowCache.put(key, b);
        return b;
    }

    // A focusable tile: a soft glass pill fades in behind it and it lifts slightly when focused.
    FrameLayout focusTile(int radius, final float lift) {
        final FrameLayout t = new FrameLayout(ctx);
        t.setFocusable(true);
        t.setFocusableInTouchMode(true); // TVs have no touch; this just lets focus land after adb or a reboot
        t.setClickable(true);
        // The pill is the tile's background, so it never changes the tile's size.
        final GradientDrawable pill = new GradientDrawable();
        pill.setColor(0x33FFFFFF);
        pill.setCornerRadius(px(radius));
        pill.setAlpha(0);
        t.setBackground(pill);
        t.setOnFocusChangeListener((v, has) -> {
            if (has && t.getParent() == dockRow) lastDock = dockRow.indexOfChild(t);
            ObjectAnimator.ofInt(pill, "alpha", pill.getAlpha(), has ? 255 : 0).setDuration(140).start();
            t.animate().scaleX(has ? lift : 1).scaleY(has ? lift : 1).setDuration(180).setInterpolator(ease).start();
        });
        return t;
    }

    // OK, seen from the couch: the card dips to 94% and eases back to where it was. The app opens at
    // once; the dip only confirms the press, so a slow stick never looks like it missed it.
    static final int PRESS_MS = 90, RELEASE_MS = 260;

    void press(final View v) {
        // Back to the focused size (drawer cards and icons lift; dock cards don't), even mid-lift or mid-dip.
        final float rest = v.isFocused() && v.getParent() != dockRow ? FOCUS_SCALE : 1;
        v.animate().cancel();
        v.animate().scaleX(rest * 0.94f).scaleY(rest * 0.94f).setDuration(PRESS_MS).setInterpolator(ease)
                .withEndAction(() -> v.animate().scaleX(rest).scaleY(rest).setDuration(RELEASE_MS)
                        .setInterpolator(ease).start()).start();
    }

    View addIcon(String asset, int x, View.OnClickListener click) {
        FrameLayout b = focusable();
        ImageView img = new ImageView(ctx);
        img.setImageBitmap(bitmap("ui/" + asset + ".png"));
        img.setScaleType(ImageView.ScaleType.FIT_CENTER);
        img.setPadding(px(18), px(18), px(18), px(18)); // a 40px glyph in the 76px circle
        View[] ring = ringAround(b, img, px(76), px(76), 38);
        focusLook(b, ring[0], ring[1], null, DIM, img);
        b.setOnClickListener(v -> { press(v); click.onClick(v); });
        base.addView(b, at(x, ICON_Y, 76, 76));
        return b;
    }

    // ---- Menus and the app drawer ----

    interface Pick { void pick(int i); }

    void appMenu(View anchor, final String p, final String label, boolean inDock) {
        final int i = dock.indexOf(p);
        if (inDock) {
            menu(anchor, new String[] {"Move left", "Move right", "Remove from Home"}, which -> {
                if (which == 2) dock.remove(i);
                else {
                    int j = which == 0 ? i - 1 : i + 1;
                    if (j < 0 || j >= dock.size()) return;
                    Collections.swap(dock, i, j);
                }
                saveDock();
                buildDock();
                int at = which == 2 ? Math.min(i, dock.size() - 1) : (which == 0 ? i - 1 : i + 1);
                if (at >= 0 && at < dockRow.getChildCount()) dockRow.getChildAt(at).requestFocus();
            });
        } else {
            menu(anchor, new String[] {i >= 0 ? "Remove from Home" : "Add to Home", "App info"}, which -> {
                if (which == 1) {
                    start(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + p)));
                    return;
                }
                if (i >= 0) dock.remove(p); else dock.add(p);
                saveDock();
                buildDock();
                showCaption(label + (i >= 0 ? " removed from Home" : " added to Home"));
            });
        }
    }

    void openSettingsMenu(View anchor) {
        boolean amazon = isInstalled(HomeService.AMAZON_HOME);
        final List<String> items = new ArrayList<>(amazon ? Arrays.asList("Fire TV settings", "Amazon home screen") : Arrays.asList("TV settings"));
        final int locationAt = items.size();
        items.add("Location  ·  " + Weather.current(prefs).label);
        final String update = Updater.available(ctx);
        if (update != null) items.add("Update to FireFrame " + update);
        menu(anchor, items.toArray(new String[0]), which -> {
            if (which == 0) {
                HomeService.pause(true);
                // Amazon's own settings screen. Settings.ACTION_SETTINGS goes through Amazon's home screen on Fire OS 7 and 8.
                if (!start(new Intent(Intent.ACTION_MAIN).addCategory("android.intent.category.LEANBACK_SETTINGS")
                        .setPackage(HomeService.AMAZON_HOME))) start(new Intent(Settings.ACTION_SETTINGS));
            } else if (which == locationAt) {
                openLocation();
            } else if (update != null && which == items.size() - 1) {
                showCaption("Downloading FireFrame " + update + "…");
                Updater.install(ctx, () -> showCaption("Couldn't download the update. Check the Wi-Fi and try again."));
            } else {
                HomeService.pause(false);
                Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                        .setPackage(HomeService.AMAZON_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if (!start(home)) showCaption("Amazon's home screen isn't available");
                else if (ctx instanceof Activity) ((Activity) ctx).finish();
            }
        });
    }

    void applyWeather() {
        if (weatherBox == null) return;
        if (!Weather.fresh(prefs)) {
            weatherBox.setVisibility(GONE);
            return;
        }
        weatherCity.setText(Weather.current(prefs).name);
        weatherTemp.setText(prefs.getInt("weather_temp", 0) + "\u00B0");
        weatherWord.setText(prefs.getString("weather_word", ""));
        boolean range = prefs.contains("weather_hi") && prefs.contains("weather_lo");
        weatherMore.setText(range ? "H " + prefs.getInt("weather_hi", 0) + "\u00B0  L " + prefs.getInt("weather_lo", 0) + "\u00B0" : "");
        weatherMore.setVisibility(range ? VISIBLE : GONE);
        weatherMark.setKind(prefs.getInt("weather_glyph", Weather.SUN));
        weatherBox.setVisibility(VISIBLE);
    }

    // City search. Results come from Open-Meteo. Reset puts Plano, TX back.
    void openLocation() {
        FrameLayout layer = new FrameLayout(ctx);
        layer.addView(new GlassView(ctx, 0, 0xC0101014), new FrameLayout.LayoutParams(-1, -1));
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF018181C);
        bg.setCornerRadius(px(32));
        box.setBackground(bg);
        box.setPadding(px(36), px(32), px(36), px(28));

        TextView title = new TextView(ctx);
        style(title, 40, semibold);
        title.setText("Location");
        box.addView(title, new LinearLayout.LayoutParams(-2, -2));
        TextView now = new TextView(ctx);
        style(now, 26, medium);
        now.setAlpha(0.7f);
        now.setText(Weather.current(prefs).label);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(-2, -2);
        nlp.topMargin = px(8);
        nlp.bottomMargin = px(22);
        box.addView(now, nlp);

        final EditText field = new EditText(ctx);
        style(field, 32, medium);
        field.setHint("City");
        field.setHintTextColor(0x88FFFFFF);
        field.setSingleLine(true);
        field.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        GradientDrawable fieldBg = new GradientDrawable();
        fieldBg.setColor(0x33FFFFFF);
        fieldBg.setCornerRadius(px(16));
        field.setBackground(fieldBg);
        field.setPadding(px(22), px(14), px(22), px(14));
        box.addView(field, new LinearLayout.LayoutParams(px(820), -2));

        final LinearLayout results = new LinearLayout(ctx);
        results.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(px(820), -2);
        rlp.topMargin = px(12);
        box.addView(results, rlp);

        final Runnable search = () -> {
            String q = field.getText().toString().trim();
            if (q.length() == 0) return;
            results.removeAllViews();
            results.addView(locationRow("Searching…", null));
            Weather.lookup(q, (found, failed) -> {
                if (!results.isAttachedToWindow()) return;
                results.removeAllViews();
                if (failed) {
                    showCaption("Couldn't search. Check the Wi-Fi and try again.");
                    return;
                }
                if (found.isEmpty()) {
                    showCaption("No matches");
                    return;
                }
                for (int i = 0; i < found.size(); i++) {
                    Weather.Place place = found.get(i);
                    View row = locationRow(place.label, () -> {
                        Weather.choose(prefs, place);
                        tick.run();
                        Weather.refresh(ctx);
                        closeLayer();
                    });
                    results.addView(row);
                    if (i == 0) row.requestFocus();
                }
            });
        };
        field.setOnEditorActionListener((v, action, event) -> {
            if (action == EditorInfo.IME_ACTION_SEARCH || action == EditorInfo.IME_ACTION_DONE) {
                search.run();
                return true;
            }
            return false;
        });
        View searchRow = locationRow("Search", search);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(px(820), -2);
        slp.topMargin = px(8);
        box.addView(searchRow, 3, slp); // under the field, above the results
        View reset = locationRow("Reset to Plano, TX", () -> {
            Weather.choose(prefs, Weather.plano());
            tick.run();
            Weather.refresh(ctx);
            closeLayer();
        });
        LinearLayout.LayoutParams zlp = new LinearLayout.LayoutParams(px(820), -2);
        zlp.topMargin = px(8);
        box.addView(reset, zlp);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(px(900), -2, Gravity.CENTER);
        layer.addView(box, lp);
        openLayer(layer, null, field);
    }

    View locationRow(String text, Runnable click) {
        FrameLayout row = focusTile(18, 1f);
        TextView t = new TextView(ctx);
        style(t, 30, medium);
        t.setText(text);
        t.setPadding(px(22), px(16), px(22), px(16));
        row.addView(t, new FrameLayout.LayoutParams(-1, -2));
        if (click != null) row.setOnClickListener(v -> click.run());
        else row.setFocusable(false);
        return row;
    }

    // The condition icon, drawn in code: filled shapes with a little colour so it reads at a glance.
    class WeatherMark extends View {
        static final int WHITE = 0xFFFFFFFF, CLOUD = 0xFFEEF1F5, DARK_CLOUD = 0xFFB9C0CB, SUN = 0xFFFFC53D,
                MOON = 0xFFF3E7B5, RAIN = 0xFF7CC4FF, BOLT = 0xFFFFD23F, FOG = 0xFFDCE1E8;
        int kind = Weather.SUN;
        final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Path path = new Path();

        WeatherMark(Context c) {
            super(c);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeCap(Paint.Cap.ROUND);
            setLayerType(LAYER_TYPE_SOFTWARE, null); // for the soft shadow under the shapes; it's small
        }

        void setKind(int k) { kind = k; invalidate(); }

        @Override protected void onDraw(Canvas c) {
            float u = Math.min(getWidth(), getHeight()) / 100f; // drawn on a 100 x 100 grid
            fill.setShadowLayer(3 * u, 0, u, 0x66000000);
            line.setStrokeWidth(5 * u);
            line.setShadowLayer(3 * u, 0, u, 0x66000000);
            switch (kind) {
                case Weather.SUN: sun(c, u, 50, 50, 1f); break;
                case Weather.MOON: moon(c, u, 50, 50, 1f); break;
                case Weather.PARTLY: sun(c, u, 36, 36, 0.8f); cloud(c, u, 56, 62, 0.8f, CLOUD); break;
                case Weather.CLOUD: cloud(c, u, 50, 54, 1f, CLOUD); break;
                case Weather.FOG:
                    cloud(c, u, 50, 40, 0.75f, CLOUD);
                    line.setColor(FOG);
                    for (int i = 0; i < 3; i++) {
                        float y = 66 + i * 11, inset = i == 1 ? 26 : 16;
                        c.drawLine(inset * u, y * u, (100 - inset) * u, y * u, line);
                    }
                    break;
                case Weather.DRIZZLE: cloud(c, u, 50, 40, 0.85f, CLOUD); drops(c, u, 2, 12); break;
                case Weather.RAIN: cloud(c, u, 50, 40, 0.85f, CLOUD); drops(c, u, 3, 20); break;
                case Weather.SNOW:
                    cloud(c, u, 50, 40, 0.85f, CLOUD);
                    fill.setColor(WHITE);
                    for (int i = 0; i < 3; i++) {
                        c.drawCircle((32 + i * 18) * u, (74 + (i % 2) * 10) * u, 5 * u, fill);
                    }
                    break;
                case Weather.STORM:
                    cloud(c, u, 50, 40, 0.85f, DARK_CLOUD);
                    fill.setColor(BOLT);
                    path.reset();
                    path.moveTo(54 * u, 56 * u);
                    path.lineTo(38 * u, 80 * u);
                    path.lineTo(50 * u, 80 * u);
                    path.lineTo(44 * u, 98 * u);
                    path.lineTo(64 * u, 70 * u);
                    path.lineTo(52 * u, 70 * u);
                    path.lineTo(60 * u, 56 * u);
                    path.close();
                    c.drawPath(path, fill);
                    break;
            }
        }

        void sun(Canvas c, float u, float cx, float cy, float k) {
            fill.setColor(SUN);
            line.setColor(SUN);
            c.drawCircle(cx * u, cy * u, 20 * k * u, fill);
            for (int i = 0; i < 8; i++) {
                double a = Math.PI * 2 * i / 8;
                float cos = (float) Math.cos(a), sin = (float) Math.sin(a);
                c.drawLine((cx + cos * 29 * k) * u, (cy + sin * 29 * k) * u,
                        (cx + cos * 40 * k) * u, (cy + sin * 40 * k) * u, line);
            }
        }

        void moon(Canvas c, float u, float cx, float cy, float k) {
            Path disc = new Path(), bite = new Path();
            disc.addCircle(cx * u, cy * u, 30 * k * u, Path.Direction.CW);
            bite.addCircle((cx + 16 * k) * u, (cy - 12 * k) * u, 26 * k * u, Path.Direction.CW);
            disc.op(bite, Path.Op.DIFFERENCE);
            fill.setColor(MOON);
            c.drawPath(disc, fill);
        }

        // Three puffs on a flat, rounded base; (cx, cy) is the middle of the base.
        void cloud(Canvas c, float u, float cx, float cy, float k, int color) {
            path.reset();
            path.addCircle((cx - 18 * k) * u, (cy + 2 * k) * u, 15 * k * u, Path.Direction.CW);
            path.addCircle((cx + 2 * k) * u, (cy - 8 * k) * u, 21 * k * u, Path.Direction.CW);
            path.addCircle((cx + 22 * k) * u, (cy + 4 * k) * u, 13 * k * u, Path.Direction.CW);
            path.addRoundRect(new RectF((cx - 33 * k) * u, (cy + 2 * k) * u, (cx + 35 * k) * u, (cy + 17 * k) * u),
                    8 * k * u, 8 * k * u, Path.Direction.CW);
            fill.setColor(color);
            c.drawPath(path, fill);
        }

        void drops(Canvas c, float u, int n, float len) {
            line.setColor(RAIN);
            for (int i = 0; i < n; i++) {
                float x = n == 3 ? 34 + i * 16 : 40 + i * 18;
                c.drawLine(x * u, 66 * u, (x - 6) * u, (66 + len) * u, line);
            }
        }
    }

    boolean start(Intent i) {
        try {
            ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    boolean isInstalled(String p) {
        try { pm.getPackageInfo(p, 0); return true; } catch (PackageManager.NameNotFoundException e) { return false; }
    }

    // A small glass menu by the anchor. Back closes it.
    void menu(View anchor, String[] items, final Pick pick) {
        FrameLayout layer = new FrameLayout(ctx);
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF0202024);
        bg.setCornerRadius(px(30));
        box.setBackground(bg);
        box.setPadding(px(10), px(10), px(10), px(10));
        View first = null;
        for (int i = 0; i < items.length; i++) {
            final int which = i;
            FrameLayout row = focusTile(22, 1f);
            TextView t = new TextView(ctx);
            style(t, 28, medium);
            t.setText(items[i]);
            t.setPadding(px(28), px(18), px(48), px(18));
            row.addView(t, new FrameLayout.LayoutParams(-2, -2));
            row.setOnClickListener(v -> { closeLayer(); pick.pick(which); });
            box.addView(row, new LinearLayout.LayoutParams(-1, -2));
            if (first == null) first = row;
        }
        box.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int[] loc = new int[2];
        anchor.getLocationInWindow(loc);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2);
        lp.leftMargin = Math.max(px(40), Math.min(loc[0], screenW - box.getMeasuredWidth() - px(40)));
        int below = loc[1] + anchor.getHeight() + px(16);
        lp.topMargin = below + box.getMeasuredHeight() < screenH - px(40) ? below : loc[1] - box.getMeasuredHeight() - px(16);
        layer.addView(box, lp);
        openLayer(layer, anchor, first);
    }

    // All apps: an even grid of the same 16:9 cards as the dock, with the same focus look. Five columns
    // of 312x175 cards, 60px apart across and down (the down gap runs from a label to the next card),
    // inside the home screen's 60px side margins: 5 x 312 + 4 x 60 = 1800 = 1920 - 2 x 60. Labels are
    // always shown. The rows scroll, and the focused card is always scrolled fully into view.
    static final int DRAWER_COLS = 5, DRAWER_W = 312, DRAWER_TILE_H = 227, DRAWER_GAP = 60, DRAWER_TOP = 168;

    void openDrawer(View anchor) {
        FrameLayout layer = new FrameLayout(ctx);
        layer.addView(new GlassView(ctx, 0, 0xA6000000), new FrameLayout.LayoutParams(-1, -1));
        TextView title = new TextView(ctx);
        style(title, 38, semibold);
        title.setShadowLayer(px(8), 0, px(1), 0x99000000);
        title.setText("All apps");
        layer.addView(title, at(SIDE_X, TOP_Y, -2, -2));
        TextView hint = new TextView(ctx);
        style(hint, 24, medium);
        hint.setAlpha(0.6f);
        hint.setText("Hold OK or press Menu to add an app to Home");
        layer.addView(hint, at(SIDE_X, TOP_Y + 54, -2, -2));

        GridScroller sv = new GridScroller(ctx);
        sv.setVerticalScrollBarEnabled(false);
        sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        sv.setClipToPadding(false);
        sv.setClipChildren(false);
        // Room above the first row and below the last for a focused card's scale, outline, shadow and label.
        sv.setPadding(px(SIDE_X), px(28), px(SIDE_X), px(DRAWER_GAP));
        GridLayout grid = new GridLayout(ctx);
        grid.setClipChildren(false);
        grid.setColumnCount(DRAWER_COLS);
        Map<String, String> apps = installed();
        drawerIcons = new HashSet<>(apps.keySet());
        drawerLayer = layer;
        View first = null;
        int i = 0;
        for (Map.Entry<String, String> a : apps.entrySet()) {
            View t = tile(a.getKey(), a.getValue(), DRAWER_W, DRAWER_TILE_H, false);
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = px(DRAWER_W);
            lp.height = px(DRAWER_TILE_H);
            lp.setMargins(i % DRAWER_COLS == 0 ? 0 : px(DRAWER_GAP), i < DRAWER_COLS ? 0 : px(DRAWER_GAP), 0, 0);
            grid.addView(t, lp);
            if (first == null) first = t;
            i++;
        }
        sv.addView(grid, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL));
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(-1, -1);
        slp.topMargin = px(DRAWER_TOP);
        layer.addView(sv, slp);
        openLayer(layer, anchor, first);
    }

    // The drawer's scroller. It brings a focused card into view with room around it for the focus
    // scale, outline, shadow and the label below. (The stock version ignores the bottom padding, so
    // the last row's label could end up under the screen's edge.)
    class GridScroller extends ScrollView {
        GridScroller(Context c) { super(c); }

        @Override
        protected int computeScrollDeltaToGetChildRectOnScreen(Rect rect) {
            if (getChildCount() == 0) return 0;
            int y = getScrollY(), h = getHeight(), room = px(36);
            int target = y;
            if (rect.bottom + room > y + h) target = rect.bottom + room - h;
            else if (rect.top - room < y) target = rect.top - room;
            int max = Math.max(0, getChildAt(0).getHeight() + getPaddingTop() + getPaddingBottom() - h);
            return Math.max(0, Math.min(target, max)) - y;
        }
    }

    // Layers stack over the home screen; only the top one can take focus.
    void openLayer(ViewGroup layer, View returnFocus, View focus) {
        layer.setTag(returnFocus);
        layer.setClickable(true);
        top().setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        layers.add(layer);
        root.addView(layer, new FrameLayout.LayoutParams(-1, -1));
        layer.setAlpha(0);
        layer.animate().alpha(1).setDuration(140).start();
        if (focus != null) focus.requestFocus();
    }

    void closeLayer() {
        ViewGroup layer = layers.remove(layers.size() - 1);
        boolean drawer = layer == drawerLayer;
        root.removeView(layer);
        if (drawer) { drawerLayer = null; drawerIcons = null; }
        top().setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        View back = (View) layer.getTag();
        if (back != null && back.isAttachedToWindow()) back.requestFocus();
        else if (layers.isEmpty() && dockRow.getChildCount() > 0) dockRow.getChildAt(0).requestFocus();
        if (layers.isEmpty() && appsChanged) buildDock();
        if (drawer) trimIcons(); // drawer bitmaps that aren't on a dock
        if (layers.isEmpty() && (ctx instanceof HomeService || ctx instanceof HomeWatcher)) HomeService.menusClosed();
    }

    ViewGroup top() {
        return layers.isEmpty() ? base : layers.get(layers.size() - 1);
    }

    // ---- Wallpaper ----

    File customWallpaper() {
        File dir = ctx.getExternalFilesDir(null); // wallpaper.sh pushes a photo here
        return dir == null ? null : new File(dir, "wallpaper.jpg");
    }

    List<String> choices() {
        List<String> c = new ArrayList<>();
        File f = customWallpaper();
        if (f != null && f.exists()) c.add(CUSTOM);
        for (String[] w : WALLPAPERS) c.add(w[0]);
        return c;
    }

    void nextWallpaper() {
        List<String> c = choices();
        String next = c.get((c.indexOf(prefs.getString("wallpaper", c.get(0))) + 1) % c.size());
        prefs.edit().putString("wallpaper", next).apply();
        showWallpaper(next);
        String credit = "Your photo";
        for (String[] w : WALLPAPERS) if (w[0].equals(next)) credit = w[1];
        showCaption(credit + "  \u00B7  " + (c.indexOf(next) + 1) + " of " + c.size());
    }

    // Decoding a photo takes a moment on a Fire TV Stick, so it happens off the UI thread.
    void showWallpaper(final String key) {
        shownWallpaper = key;
        seenPhotoVersion = photoVersion;
        synchronized (wallLock) {
            if (key.equals(cachedKey) && cachedPhoto != null) {
                useWall(cachedPhoto, cachedBlur);
                return;
            }
        }
        new Thread(() -> {
            Bitmap photo, soft, prevPhoto = null, prevBlur = null;
            boolean won;
            synchronized (wallLock) {
                if (key.equals(cachedKey) && cachedPhoto != null) {
                    photo = cachedPhoto;
                    soft = cachedBlur;
                    won = false;
                } else {
                    Bitmap b = decode(key);
                    if (b == null) b = decode(WALLPAPERS[0][0]);
                    if (b == null) return;
                    photo = b;
                    soft = blurred(b);
                    prevPhoto = cachedPhoto;
                    prevBlur = cachedBlur;
                    cachedKey = key;
                    cachedPhoto = photo;
                    cachedBlur = soft;
                    won = true;
                }
            }
            final Bitmap show = photo, frost = soft, oldPhoto = prevPhoto, oldBlur = prevBlur;
            final boolean share = won;
            post(() -> {
                if (share) shareWall(show, frost);
                else useWall(show, frost);
                // The old photo is still fading out on screen; free it once the fade is done.
                MAIN.postDelayed(() -> {
                    if (oldPhoto != null && oldPhoto != show) dropWall(oldPhoto);
                    if (oldBlur != null && oldBlur != frost && !oldBlur.isRecycled()) oldBlur.recycle();
                }, WALL_FADE_MS + 100);
            });
        }).start();
    }

    static final int WALL_FADE_MS = 520;
    static final Handler MAIN = new Handler(Looper.getMainLooper());

    // On a screen that's showing, the old wallpaper dissolves into the new one. One extra full-screen
    // layer, for half a second, only when the wallpaper changes.
    void useWall(Bitmap photo, Bitmap soft) {
        Drawable was = wallView.getDrawable();
        Bitmap old = was instanceof BitmapDrawable ? ((BitmapDrawable) was).getBitmap() : null;
        wallView.setImageBitmap(photo);
        if (old != null && old != photo && !old.isRecycled() && isShown()) {
            wallFade.animate().cancel();
            wallFade.setImageBitmap(old);
            wallFade.setAlpha(1f);
            wallFade.setVisibility(VISIBLE);
            wallFade.animate().alpha(0).setDuration(WALL_FADE_MS).setInterpolator(ease)
                    .withEndAction(this::clearWallFade).start();
        }
        blur = soft;
        root.invalidate();
        invalidateGlass(root);
    }

    void clearWallFade() {
        wallFade.animate().cancel();
        wallFade.setImageDrawable(null);
        wallFade.setVisibility(GONE);
    }

    // Every live screen shows the one bitmap, then the previous photo can be freed.
    static void shareWall(Bitmap photo, Bitmap soft) {
        for (int i = 0; i < alive.size(); i++) alive.get(i).useWall(photo, soft);
    }

    // Frees a wallpaper no screen shows any more, taking it off any fade still holding it first.
    static void dropWall(Bitmap old) {
        if (old.isRecycled()) return;
        for (int i = 0; i < alive.size(); i++) {
            HomeScreen h = alive.get(i);
            Drawable d = h.wallFade.getDrawable();
            if (d instanceof BitmapDrawable && ((BitmapDrawable) d).getBitmap() == old) h.clearWallFade();
            Drawable w = h.wallView.getDrawable();
            if (w instanceof BitmapDrawable && ((BitmapDrawable) w).getBitmap() == old) return; // still in use
        }
        old.recycle();
    }

    void invalidateGlass(View v) {
        if (v instanceof GlassView) v.invalidate();
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) invalidateGlass(((ViewGroup) v).getChildAt(i));
    }

    Bitmap decode(String key) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream in = open(key)) { BitmapFactory.decodeStream(in, null, o); }
            o.inSampleSize = 1;
            while (o.outWidth / (o.inSampleSize * 2) >= screenW && o.outHeight / (o.inSampleSize * 2) >= screenH) o.inSampleSize *= 2;
            o.inJustDecodeBounds = false;
            o.inScaled = false; // the file is already 1920x1080; don't let a density scale make a second copy
            o.inPreferredConfig = Bitmap.Config.ARGB_8888;
            try (InputStream in = open(key)) { return BitmapFactory.decodeStream(in, null, o); }
        } catch (Exception e) {
            return null;
        }
    }

    InputStream open(String key) throws Exception {
        return key.equals(CUSTOM) ? new FileInputStream(customWallpaper()) : ctx.getAssets().open("wallpapers/" + key + ".jpg");
    }

    // The frosted glass: the photo shrunk to 96 pixels wide and box-blurred. Drawn stretched, it reads as blur.
    static Bitmap blurred(Bitmap b) {
        int w = 96, h = Math.max(1, Math.round(96f * b.getHeight() / b.getWidth()));
        Bitmap mid = Bitmap.createScaledBitmap(b, w * 4, h * 4, true);
        Bitmap small = Bitmap.createScaledBitmap(mid, w, h, true);
        if (mid != b && mid != small) mid.recycle();
        int[] a = new int[w * h], t = new int[w * h];
        small.getPixels(a, 0, w, 0, 0, w, h);
        if (small != b) small.recycle();
        for (int i = 0; i < 3; i++) {
            boxPass(a, t, h, w, w, 1, 3);
            boxPass(t, a, w, h, 1, w, 3);
        }
        return Bitmap.createBitmap(a, w, h, Bitmap.Config.ARGB_8888);
    }

    static void boxPass(int[] src, int[] dst, int lines, int len, int lineStep, int step, int r) {
        for (int l = 0; l < lines; l++) {
            int start = l * lineStep;
            for (int i = 0; i < len; i++) {
                int red = 0, green = 0, blue = 0, n = 0;
                for (int k = Math.max(0, i - r); k <= Math.min(len - 1, i + r); k++, n++) {
                    int c = src[start + k * step];
                    red += (c >> 16) & 255;
                    green += (c >> 8) & 255;
                    blue += c & 255;
                }
                dst[start + i * step] = 0xFF000000 | (red / n) << 16 | (green / n) << 8 | blue / n;
            }
        }
    }

    // Draws the blurred photo exactly behind itself, so it looks like frosted glass over the wallpaper.
    // The dock's sideways scroller. It scrolls a whole card at a time, so the focused card always sits
    // between the bar's paddings, which leave room for its 1.1x scale, outline and shadow. (The stock
    // version ignores the padding at the far end, which left the last card under the bar's edge.)
    class DockScroller extends HorizontalScrollView {
        DockScroller(Context c) { super(c); }

        @Override
        protected int computeScrollDeltaToGetChildRectOnScreen(Rect rect) {
            if (getChildCount() == 0) return 0;
            int x = getScrollX(), w = getWidth();
            int winL = x + getPaddingLeft(), winR = x + w - getPaddingRight();
            int target = x;
            if (rect.right > winR) target = x + rect.right - winR;
            else if (rect.left < winL) target = x - (winL - rect.left);
            int stride = Math.max(1, px(DOCK_W + DOCK_GAP));
            if (target > x) target = (target + stride - 1) / stride * stride;
            else if (target < x) target = Math.max(0, target) / stride * stride;
            int max = Math.max(0, getChildAt(0).getWidth() - (w - getPaddingLeft() - getPaddingRight()));
            return Math.max(0, Math.min(target, max)) - x;
        }

        @Override
        protected void onScrollChanged(int l, int t, int oldl, int oldt) {
            super.onScrollChanged(l, t, oldl, oldt);
            if (glass != null) glass.invalidate(); // the fades turn on and off at the ends
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r2, int b) {
            super.onLayout(changed, l, t, r2, b);
            if (glass != null) glass.invalidate();
        }
    }

    class GlassView extends FrameLayout {
        final float radius;
        final Paint photo = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        final Paint tint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        final RectF rect = new RectF();
        final Matrix m = new Matrix();
        final int[] loc = new int[2];
        Bitmap shaderFor;
        HorizontalScrollView fadeFor; // fade the side of this row that has more to scroll to
        boolean bare; // draws nothing of its own: only clips and fades its children
        boolean liquid; // the dock's look: a sheen and a light rim, brighter at the top
        final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG), rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        int liquidFor = -1;
        final Paint fade = new Paint();

        GlassView(Context c, int radius, int tintColor) {
            this(c, radius, tintColor, 255);
        }

        // frost is the blurred photo's alpha. 255 hides the wallpaper; lower values show it through.
        GlassView(Context c, int radius, int tintColor, int frost) {
            super(c);
            this.radius = px(radius);
            photo.setAlpha(frost);
            tint.setColor(tintColor);
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(Math.max(1, px(1.5f)));
            edge.setColor(radius > 0 ? 0x1FFFFFFF : 0);
            setWillNotDraw(false);
        }

        @Override
        protected void dispatchDraw(Canvas c) {
            boolean left = fadeFor != null && fadeFor.canScrollHorizontally(-1);
            boolean right = fadeFor != null && fadeFor.canScrollHorizontally(1);
            if (!left && !right) { // the usual case: everything fits, no layer needed
                super.dispatchDraw(c);
                return;
            }
            int w = getWidth(), h = getHeight();
            float f = px(DOCK_FADE);
            int saved = c.saveLayer(0, 0, w, h, null);
            super.dispatchDraw(c);
            if (fade.getXfermode() == null) fade.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
            if (left) {
                fade.setShader(new LinearGradient(0, 0, f, 0, 0xFF000000, 0, Shader.TileMode.CLAMP));
                c.drawRect(0, 0, f, h, fade);
            }
            if (right) {
                fade.setShader(new LinearGradient(w - f, 0, w, 0, 0, 0xFF000000, Shader.TileMode.CLAMP));
                c.drawRect(w - f, 0, w, h, fade);
            }
            c.restoreToCount(saved);
        }

        @Override
        protected void onDraw(Canvas c) {
            if (bare) return;
            rect.set(0, 0, getWidth(), getHeight());
            if (blur != null) {
                if (shaderFor != blur) {
                    photo.setShader(new BitmapShader(blur, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
                    shaderFor = blur;
                }
                // Same centre-crop as the wallpaper, shifted to this view's place on screen.
                float k = Math.max((float) screenW / blur.getWidth(), (float) screenH / blur.getHeight());
                getLocationInWindow(loc);
                m.setScale(k, k);
                m.postTranslate((screenW - blur.getWidth() * k) / 2 - loc[0], (screenH - blur.getHeight() * k) / 2 - loc[1]);
                photo.getShader().setLocalMatrix(m);
                c.drawRoundRect(rect, radius, radius, photo);
            }
            c.drawRoundRect(rect, radius, radius, tint);
            if (liquid) {
                int h = getHeight();
                if (liquidFor != h) {
                    liquidFor = h;
                    sheen.setShader(new LinearGradient(0, 0, 0, h, 0x17FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
                    rim.setStyle(Paint.Style.STROKE);
                    rim.setStrokeWidth(Math.max(1, px(1.5f)));
                    rim.setShader(new LinearGradient(0, 0, 0, h, 0x4DFFFFFF, 0x26FFFFFF, Shader.TileMode.CLAMP));
                }
                c.drawRoundRect(rect, radius, radius, sheen);
                float e = rim.getStrokeWidth() / 2;
                rect.inset(e, e);
                c.drawRoundRect(rect, radius - e, radius - e, rim);
                return;
            }
            if (radius > 0) {
                float e = edge.getStrokeWidth() / 2;
                rect.inset(e, e);
                c.drawRoundRect(rect, radius - e, radius - e, edge);
            }
        }
    }

    // ---- Helpers ----

    // Under the top right icons on Home. Over a layer (the drawer, Location) it moves up beside the
    // layer's title, clear of the cards, so feedback from inside a menu is seen where it happened.
    void showCaption(String text) {
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) caption.getLayoutParams();
        int top = px(layers.isEmpty() ? ICON_Y + 76 + 20 : TOP_Y + 10);
        if (lp.topMargin != top) { lp.topMargin = top; caption.setLayoutParams(lp); }
        caption.bringToFront();
        caption.setText(text);
        caption.animate().cancel();
        caption.animate().alpha(1).setDuration(160).withEndAction(() ->
                caption.animate().alpha(0).setStartDelay(2600).setDuration(400).start()).start();
    }

    Bitmap bitmap(String asset) {
        try (InputStream in = ctx.getAssets().open(asset)) {
            return BitmapFactory.decodeStream(in);
        } catch (Exception e) {
            return null;
        }
    }

    void rounded(View v, final int radius) {
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline o) {
                o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), px(radius));
            }
        });
        v.setClipToOutline(true);
    }

    void style(TextView t, float size, Typeface face) {
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(size));
        t.setTypeface(face);
        t.setTextColor(0xFFFFFFFF);
        t.setIncludeFontPadding(false);
    }

    FrameLayout.LayoutParams at(int x, int y, int w, int h) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w < 0 ? w : px(w), h < 0 ? h : px(h));
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        lp.leftMargin = px(x);
        lp.topMargin = px(y);
        return lp;
    }

    int px(float v) {
        return Math.round(v * s);
    }
}
