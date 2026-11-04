package io.github.maxhayim.smarthome;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import java.time.ZoneId;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Fork casa: Home Assistant on the launcher's Home. The launcher lists this package in its game_day key, finds this
 * provider through the application's com.gathin.tvhome.games (panel turns) and com.gathin.tvhome.weather (the
 * line beside the date) meta-data, and queries it off its main thread when Home shows and every two minutes.
 *
 * content://…panel/turns: the launcher's game_day columns plus "headline" and "detail" (Panel.turns).
 * content://…panel/weather: one "text" column (Panel.weatherLine).
 *
 * Read-only and holding nothing secret: what Home shows anyway. The token never leaves this app. Answers come from
 * one fetch shared by both paths and kept for CACHE_MS, so a Home that queries both does one round trip.
 */
public final class PanelProvider extends ContentProvider {
    static final String[] TURN_COLUMNS = {"top_line", "word", "start_ms", "end_ms", "colour", "calendar", "turn_seconds",
            "headline", "detail"};
    static final String[] WEATHER_COLUMNS = {"text"};
    private static final long CACHE_MS = 90_000L;

    private static final Object LOCK = new Object();
    private static JSONArray states;
    private static JSONObject forecast;
    private static long fetchedAt;

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        String path = uri.getLastPathSegment();
        Context ctx = getContext();
        if (ctx == null || path == null) return null;
        boolean weather = path.equals("weather");
        if (!weather && !path.equals("turns")) return null;
        MatrixCursor c = new MatrixCursor(weather ? WEATHER_COLUMNS : TURN_COLUMNS);
        JSONArray s;
        JSONObject f;
        synchronized (LOCK) {
            refresh(ctx);
            s = states;
            f = forecast;
        }
        if (s == null) return c;
        Locale locale = Locale.getDefault();
        try {
            if (weather) {
                String line = Panel.weatherLine(s, f, locale);
                if (!line.isEmpty()) c.addRow(new Object[] {line});
            } else {
                long now = System.currentTimeMillis();
                for (Panel.Turn t : Panel.turns(s, Config.panelTemps(ctx), now, ZoneId.systemDefault(), locale)) {
                    c.addRow(new Object[] {t.top, "", t.startMs, t.endMs, t.colour, "", 30, t.headline, t.detail});
                }
            }
        } catch (RuntimeException e) {
            // A state Home Assistant shaped differently: Home keeps the rest of its panel.
        }
        return c;
    }

    /** One GET of /api/states and, with a weather entity, its daily forecast; the last good answer is kept on a failure. */
    private static void refresh(Context ctx) {
        long now = System.currentTimeMillis();
        if (states != null && now - fetchedAt < CACHE_MS) return;
        Config.importPushed(ctx);
        Config cfg = Config.load(ctx);
        if (!cfg.complete()) return;
        HaClient ha = new HaClient(cfg.url, cfg.token);
        HaClient.Reply r = ha.get("/api/states");
        if (!r.ok()) return;
        try {
            JSONArray fresh = new JSONArray(r.body);
            JSONObject fc = null;
            for (int i = 0; i < fresh.length(); i++) {
                String id = fresh.optJSONObject(i) == null ? "" : fresh.optJSONObject(i).optString("entity_id");
                if (!id.startsWith("weather.")) continue;
                HaClient.Reply fr = ha.post("/api/services/weather/get_forecasts?return_response",
                        new JSONObject().put("entity_id", id).put("type", "daily").toString());
                if (fr.ok()) fc = new JSONObject(fr.body);
                break;
            }
            states = fresh;
            forecast = fc;
            fetchedAt = now;
        } catch (Exception e) {
            // Not JSON (a proxy's error page): keep what we had.
        }
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
