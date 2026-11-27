package com.fireframe.home;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;

// Weather for the saved city. Open-Meteo needs no key. The clock uses the same time zone.
// Nothing here touches device settings. A failed fetch just leaves the line hidden.
class Weather {
    static final long EVERY = 30 * 60 * 1000L;
    static final String PLANO_TZ = "America/Chicago";
    static final int SUN = 1, MOON = 2, PARTLY = 3, CLOUD = 4, FOG = 5, DRIZZLE = 6, RAIN = 7, SNOW = 8, STORM = 9;

    static final class Place {
        final String name, label, tz;
        final double lat, lon;
        Place(String name, String label, String tz, double lat, double lon) {
            this.name = name;
            this.label = label;
            this.tz = tz;
            this.lat = lat;
            this.lon = lon;
        }
    }

    interface Places { void on(List<Place> found, boolean failed); }

    static Place plano() {
        return new Place("Plano", "Plano, TX", PLANO_TZ, 33.0198, -96.6989);
    }

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("fireframe", Context.MODE_PRIVATE);
    }

    static Place current(SharedPreferences p) {
        if (!p.contains("geo_lat")) return plano();
        return new Place(p.getString("geo_name", "Plano"), p.getString("geo_label", "Plano, TX"),
                p.getString("geo_tz", PLANO_TZ), p.getFloat("geo_lat", 33.0198f), p.getFloat("geo_lon", -96.6989f));
    }

    static TimeZone zone(SharedPreferences p) {
        return TimeZone.getTimeZone(current(p).tz);
    }

    static void choose(SharedPreferences p, Place place) {
        epoch++;
        p.edit().putString("geo_name", place.name).putString("geo_label", place.label).putString("geo_tz", place.tz)
                .putFloat("geo_lat", (float) place.lat).putFloat("geo_lon", (float) place.lon)
                .remove("weather_line").remove("weather_at").remove("weather_temp").remove("weather_word")
                .remove("weather_hi").remove("weather_lo").apply();
    }

    static volatile boolean busy;
    static int epoch; // bumped when the city changes, so an in-flight fetch can't write the old place

    // At most one request. A fresh reading is shown as-is; a failed one clears the line.
    static void refresh(Context c) {
        final SharedPreferences p = prefs(c);
        long at = p.getLong("weather_at", 0);
        if (fresh(p) && System.currentTimeMillis() - at < EVERY) {
            publish();
            return;
        }
        if (busy) return;
        busy = true;
        final int mine = epoch;
        final Place place = current(p);
        new Thread(() -> {
            String line = null, said = null;
            int glyph = 0, temp = 0;
            Integer hi = null, lo = null;
            try {
                String url = "https://api.open-meteo.com/v1/forecast?latitude=" + place.lat + "&longitude=" + place.lon
                        + "&current=temperature_2m,weather_code,is_day"
                        + "&daily=temperature_2m_max,temperature_2m_min&forecast_days=1"
                        + "&temperature_unit=fahrenheit&timezone=" + URLEncoder.encode(place.tz, "UTF-8");
                JSONObject root = new JSONObject(read(url));
                JSONObject cur = root.getJSONObject("current");
                int code = cur.getInt("weather_code");
                boolean day = cur.optInt("is_day", 1) == 1;
                temp = (int) Math.round(cur.getDouble("temperature_2m"));
                glyph = glyph(code, day);
                said = word(code, day);
                line = temp + "\u00B0F " + said + "  \u00B7  " + place.name;
                JSONObject daily = root.optJSONObject("daily");
                JSONArray max = daily == null ? null : daily.optJSONArray("temperature_2m_max");
                JSONArray min = daily == null ? null : daily.optJSONArray("temperature_2m_min");
                if (max != null && min != null && max.length() > 0 && min.length() > 0
                        && !max.isNull(0) && !min.isNull(0)) {
                    hi = (int) Math.round(max.getDouble(0));
                    lo = (int) Math.round(min.getDouble(0));
                }
            } catch (Exception ignored) {
                line = null;
            }
            final String show = line, word = said;
            final int mark = glyph, now = temp;
            final Integer high = hi, low = lo;
            new Handler(Looper.getMainLooper()).post(() -> {
                busy = false;
                if (mine != epoch) { refresh(c); return; }
                SharedPreferences.Editor e = p.edit();
                if (show == null) e.remove("weather_line").remove("weather_at");
                else {
                    e.putString("weather_line", show).putInt("weather_glyph", mark).putInt("weather_temp", now)
                            .putString("weather_word", word).putLong("weather_at", System.currentTimeMillis());
                    if (high != null && low != null) e.putInt("weather_hi", high).putInt("weather_lo", low);
                    else e.remove("weather_hi").remove("weather_lo");
                }
                e.apply();
                publish();
            });
        }).start();
    }

    // A reading for the widget is saved (older versions saved only a line of text).
    static boolean fresh(SharedPreferences p) {
        return p.getString("weather_line", null) != null && p.contains("weather_temp") && p.contains("weather_word");
    }

    static void publish() {
        for (int i = 0; i < HomeScreen.alive.size(); i++) {
            HomeScreen h = HomeScreen.alive.get(i);
            h.post(h::applyWeather);
        }
    }

    static void lookup(String query, Places done) {
        new Thread(() -> {
            List<Place> found = new ArrayList<>();
            boolean failed = false;
            try {
                String url = "https://geocoding-api.open-meteo.com/v1/search?count=5&name="
                        + URLEncoder.encode(query, "UTF-8");
                JSONObject root = new JSONObject(read(url));
                JSONArray results = root.optJSONArray("results");
                if (results != null) {
                    for (int i = 0; i < results.length() && found.size() < 5; i++) {
                        JSONObject r = results.getJSONObject(i);
                        String name = r.optString("name", "");
                        if (name.length() == 0) continue;
                        String admin = r.optString("admin1", "");
                        String country = r.optString("country", "");
                        String label = name;
                        if (admin.length() > 0) label = label + ", " + admin;
                        else if (country.length() > 0) label = label + ", " + country;
                        String tz = r.optString("timezone", PLANO_TZ);
                        if (tz.length() == 0) tz = PLANO_TZ;
                        found.add(new Place(name, label, tz, r.getDouble("latitude"), r.getDouble("longitude")));
                    }
                }
            } catch (Exception ignored) {
                failed = true;
            }
            final List<Place> list = found;
            final boolean bad = failed;
            new Handler(Looper.getMainLooper()).post(() -> done.on(list, bad));
        }).start();
    }

    static int glyph(int code, boolean day) {
        if (code == 0 || code == 1) return day ? SUN : MOON;
        if (code == 2) return PARTLY;
        if (code == 3) return CLOUD;
        if (code == 45 || code == 48) return FOG;
        if (code == 51 || code == 53 || code == 55 || code == 56 || code == 57) return DRIZZLE;
        if (code == 71 || code == 73 || code == 75 || code == 77 || code == 85 || code == 86) return SNOW;
        if (code == 95 || code == 96 || code == 99) return STORM;
        return RAIN;
    }

    static String word(int code, boolean day) {
        if (code == 0 || code == 1) return day ? "Sunny" : "Clear";
        if (code == 2) return "Partly cloudy";
        if (code == 3) return "Cloudy";
        if (code == 45 || code == 48) return "Fog";
        if (code == 51 || code == 53 || code == 55 || code == 56 || code == 57) return "Drizzle";
        if (code == 71 || code == 73 || code == 75 || code == 77 || code == 85 || code == 86) return "Snow";
        if (code == 95 || code == 96 || code == 99) return "Thunderstorm";
        return "Rain";
    }

    static String read(String url) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setRequestProperty("User-Agent", "FireFrame");
        h.setConnectTimeout(8000);
        h.setReadTimeout(10000);
        try (InputStream in = h.getResponseCode() == 200 ? h.getInputStream() : null) {
            if (in == null) throw new Exception("HTTP " + h.getResponseCode());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8 * 1024];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } finally {
            h.disconnect();
        }
    }
}
