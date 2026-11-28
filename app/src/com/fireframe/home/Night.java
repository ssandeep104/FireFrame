package com.fireframe.home;

import android.view.View;
import android.widget.FrameLayout;
import java.util.Calendar;

// Night mode: a dark, warm veil over the whole home screen (menus included). Turned on with the moon
// button, or by a schedule (10 PM to 6 AM) switched on in Settings. Ported from dyascj/FireFrame 1.2.0.
final class Night {
    static final String[] STRENGTHS = {"Light", "Medium", "Strong"};
    static final float[] ALPHAS = {0.28f, 0.42f, 0.58f};
    static final int FROM = 22, UNTIL = 6;

    final HomeScreen s;
    final View scrim;
    View moonBtn;
    float shown = -1;

    Night(HomeScreen s) {
        this.s = s;
        scrim = new View(s.ctx);
        scrim.setBackgroundColor(0xFF140A02);
        scrim.setAlpha(0f);
        s.root.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
    }

    boolean inWindow() {
        int h = Calendar.getInstance(Weather.zone(s.prefs)).get(Calendar.HOUR_OF_DAY);
        return NightMath.inWindow(h, FROM, UNTIL);
    }

    boolean schedule() { return s.prefs.getBoolean("night_schedule", false); }

    boolean active() {
        if (!schedule()) return s.prefs.getBoolean("night_on", false);
        boolean in = inWindow();
        String ov = s.prefs.getString("night_ov", ""); // "on1": switched on by hand while the schedule said night
        if (!ov.isEmpty() && ov.endsWith(in ? "1" : "0")) return ov.startsWith("on");
        if (!ov.isEmpty()) s.prefs.edit().remove("night_ov").apply(); // the schedule has moved on: follow it again
        return in;
    }

    void toggle() {
        boolean target = !active();
        if (schedule()) s.prefs.edit().putString("night_ov", (target ? "on" : "off") + (inWindow() ? "1" : "0")).apply();
        else s.prefs.edit().putBoolean("night_on", target).apply();
        apply();
        s.showCaption(target ? "Night mode on" : "Night mode off");
    }

    int strength() {
        return Math.max(0, Math.min(STRENGTHS.length - 1, s.prefs.getInt("night_strength", 1)));
    }

    void nextStrength() {
        s.prefs.edit().putInt("night_strength", (strength() + 1) % STRENGTHS.length).apply();
        apply();
        s.showCaption("Night strength: " + STRENGTHS[strength()]);
    }

    void toggleSchedule() {
        s.prefs.edit().putBoolean("night_schedule", !schedule()).remove("night_ov").apply();
        apply();
        s.showCaption(schedule() ? "Night mode follows 10 PM to 6 AM" : "Night schedule off");
    }

    // Fades the veil in or out and lights the moon while night mode is on.
    void apply() {
        boolean on = active();
        if (moonBtn != null) moonBtn.setAlpha(on ? 1f : 0.7f);
        float target = on ? ALPHAS[strength()] : 0f;
        if (target == shown) return;
        shown = target;
        scrim.animate().cancel();
        if (!s.isShown()) scrim.setAlpha(target);
        else scrim.animate().alpha(target).setDuration(500).start();
    }
}
