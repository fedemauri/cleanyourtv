package io.github.maxhayim.smarthome;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Fork casa: what Home Assistant puts on the launcher's Home, built from GET /api/states and the daily forecast.
 * Three things: the weather line beside the date ("13° Nuvoloso · min 11° max 13°"), one "Casa" turn for the
 * panel under the clock (temperatures, and the alarm and shutters on the small line), and an alert turn for each
 * thing that needs a look right now (a door or the garage open, a device with a problem, an alarm going off,
 * smoke or water). No Android types and no network, so the JVM tests cover it.
 */
final class Panel {
    static final class Turn {
        final String top, headline, detail;
        final int colour;
        final long startMs, endMs;

        Turn(String top, String headline, String detail, int colour, long startMs, long endMs) {
            this.top = top;
            this.headline = headline;
            this.detail = detail;
            this.colour = colour;
            this.startMs = startMs;
            this.endMs = endMs;
        }
    }

    static final int RED = 0xFFE53935, AMBER = 0xFFFFB300, HOME = 0xFF4FC3F7;
    /** Turns are refreshed every couple of minutes; this keeps one alive in between. */
    static final long LIFETIME_MS = 60 * 60_000L;
    static final int MAX_TEMPS = 3;
    static final int MAX_ALERTS = 4;

    private Panel() {
    }

    private static boolean it(Locale l) {
        return "it".equals(l.getLanguage());
    }

    /** The weather line, or "" when there is no usable weather entity. */
    static String weatherLine(JSONArray states, JSONObject forecastReply, Locale locale) {
        JSONObject w = null;
        for (int i = 0; i < states.length(); i++) {
            JSONObject o = states.optJSONObject(i);
            if (o != null && o.optString("entity_id").startsWith("weather.") && available(o)) {
                w = o;
                break;
            }
        }
        if (w == null) return "";
        JSONObject a = attributes(w);
        StringBuilder b = new StringBuilder();
        if (a.has("temperature")) b.append(Math.round(a.optDouble("temperature"))).append("° ");
        b.append(condition(w.optString("state"), locale));
        JSONObject today = firstForecast(forecastReply, w.optString("entity_id"));
        if (today != null && today.has("templow") && today.has("temperature")) {
            b.append("\u2003min ").append(Math.round(today.optDouble("templow"))).append("° max ")
                    .append(Math.round(today.optDouble("temperature"))).append('°');
        }
        return b.toString().trim();
    }

    private static JSONObject firstForecast(JSONObject reply, String entityId) {
        if (reply == null) return null;
        JSONObject sr = reply.optJSONObject("service_response");
        JSONObject e = sr == null ? null : sr.optJSONObject(entityId);
        JSONArray f = e == null ? null : e.optJSONArray("forecast");
        return f == null || f.length() == 0 ? null : f.optJSONObject(0);
    }

    static String condition(String state, Locale locale) {
        boolean it = it(locale);
        switch (state) {
            case "clear-night": return it ? "Sereno" : "Clear";
            case "sunny": return it ? "Soleggiato" : "Sunny";
            case "partlycloudy": return it ? "Poco nuvoloso" : "Partly cloudy";
            case "cloudy": return it ? "Nuvoloso" : "Cloudy";
            case "fog": return it ? "Nebbia" : "Fog";
            case "rainy": return it ? "Pioggia" : "Rain";
            case "pouring": return it ? "Pioggia forte" : "Heavy rain";
            case "lightning": case "lightning-rainy": return it ? "Temporale" : "Thunderstorm";
            case "snowy": return it ? "Neve" : "Snow";
            case "snowy-rainy": return it ? "Nevischio" : "Sleet";
            case "hail": return it ? "Grandine" : "Hail";
            case "windy": case "windy-variant": return it ? "Vento" : "Windy";
            default: return state.isEmpty() ? "" : Character.toUpperCase(state.charAt(0)) + state.substring(1);
        }
    }

    /**
     * The panel's turns, alerts first (soonest start, as the launcher sorts), then "Casa". {@code tempIds} picks the
     * temperature sensors for "Casa" in order; empty picks up to three that read like rooms (-30° to 45°).
     */
    static List<Turn> turns(JSONArray states, List<String> tempIds, long nowMs, ZoneId zone, Locale locale) {
        boolean it = it(locale);
        DateTimeFormatter hm = DateTimeFormatter.ofPattern("HH:mm", locale).withZone(zone);
        Map<String, JSONObject> byId = new HashMap<>();
        List<JSONObject> all = new ArrayList<>();
        for (int i = 0; i < states.length(); i++) {
            JSONObject o = states.optJSONObject(i);
            if (o == null) continue;
            byId.put(o.optString("entity_id"), o);
            all.add(o);
        }
        List<Turn> alerts = new ArrayList<>();
        for (JSONObject o : all) {
            String id = o.optString("entity_id"), state = o.optString("state");
            String dc = attributes(o).optString("device_class", "");
            String name = name(o);
            long since = changedMs(o, nowMs);
            String at = hm.format(Instant.ofEpochMilli(since));
            if (id.startsWith("binary_sensor.") && "on".equals(state)) {
                switch (dc) {
                    case "door": case "window": case "garage_door": case "opening":
                        alerts.add(new Turn(name, (it ? "Aperto dalle " : "Open since ") + at, "", RED, since, nowMs + LIFETIME_MS));
                        break;
                    case "smoke": case "gas": case "carbon_monoxide": case "moisture": case "safety":
                        alerts.add(new Turn(name, (it ? "Allarme dalle " : "Alarm since ") + at, "", RED, since, nowMs + LIFETIME_MS));
                        break;
                    case "problem":
                        alerts.add(new Turn(name, (it ? "Problema dalle " : "Problem since ") + at, "", AMBER, since, nowMs + LIFETIME_MS));
                        break;
                    case "battery":
                        alerts.add(new Turn(name, it ? "Batteria scarica" : "Battery low", "", AMBER, since, nowMs + LIFETIME_MS));
                        break;
                    default:
                        break;
                }
            } else if (id.startsWith("alarm_control_panel.") && "triggered".equals(state)) {
                alerts.add(new Turn(name, (it ? "Allarme scattato alle " : "Alarm triggered at ") + at, "", RED, since, nowMs + LIFETIME_MS));
            }
        }
        Collections.sort(alerts, (x, y) -> Long.compare(x.startMs, y.startMs));
        List<Turn> out = new ArrayList<>(alerts.subList(0, Math.min(MAX_ALERTS, alerts.size())));

        List<String> temps = new ArrayList<>();
        for (JSONObject o : pickTemps(all, byId, tempIds)) {
            temps.add(shortName(name(o)) + " " + Math.round(Double.parseDouble(o.optString("state"))) + "°");
        }
        List<String> detail = new ArrayList<>();
        int up = 0, down = 0;
        for (JSONObject o : all) {
            String id = o.optString("entity_id"), state = o.optString("state");
            if (id.startsWith("alarm_control_panel.") && !"triggered".equals(state) && available(o)) {
                detail.add(alarmWord(state, it));
            } else if (id.startsWith("cover.") && available(o) && !attributes(o).has("entity_id")) {
                // A group ("Tutte le tapparelle") lists its members in entity_id: counted through them instead.
                if ("closed".equals(state) || "closing".equals(state)) down++;
                else up++;
            }
        }
        if (up + down > 0) detail.add(shutters(up, down, it));
        if (!temps.isEmpty() || !detail.isEmpty()) {
            String headline = temps.isEmpty() ? detail.remove(0) : String.join("\u2003", temps);
            out.add(new Turn(it ? "Casa" : "Home", headline, String.join("\u2003\u2003", detail), HOME, nowMs, nowMs + LIFETIME_MS));
        }
        return out;
    }

    private static List<JSONObject> pickTemps(List<JSONObject> all, Map<String, JSONObject> byId, List<String> ids) {
        List<JSONObject> out = new ArrayList<>();
        if (ids != null && !ids.isEmpty()) {
            for (String id : ids) {
                JSONObject o = byId.get(id);
                if (o != null && number(o)) out.add(o);
            }
            return out;
        }
        List<JSONObject> rooms = new ArrayList<>();
        for (JSONObject o : all) {
            if (!o.optString("entity_id").startsWith("sensor.")) continue;
            if (!"temperature".equals(attributes(o).optString("device_class"))) continue;
            if (!number(o)) continue;
            double v = Double.parseDouble(o.optString("state"));
            if (v >= -30 && v <= 45) rooms.add(o);
        }
        Collections.sort(rooms, (x, y) -> name(x).compareToIgnoreCase(name(y)));
        return rooms.subList(0, Math.min(MAX_TEMPS, rooms.size()));
    }

    private static boolean number(JSONObject o) {
        try {
            Double.parseDouble(o.optString("state"));
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static boolean available(JSONObject o) {
        String s = o.optString("state");
        return !s.isEmpty() && !"unavailable".equals(s) && !"unknown".equals(s);
    }

    private static JSONObject attributes(JSONObject o) {
        JSONObject a = o.optJSONObject("attributes");
        return a == null ? new JSONObject() : a;
    }

    private static String name(JSONObject o) {
        String id = o.optString("entity_id");
        String n = attributes(o).optString("friendly_name", "").trim();
        return n.isEmpty() ? id.substring(id.indexOf('.') + 1).replace('_', ' ') : n.replaceAll("\\s+", " ");
    }

    /** "Salotto Temperatura" is "Salotto" on the panel. */
    static String shortName(String n) {
        for (String suffix : new String[] {" Temperatura", " temperatura", " Temperature", " temperature"}) {
            if (n.endsWith(suffix) && n.length() > suffix.length()) return n.substring(0, n.length() - suffix.length());
        }
        return n;
    }

    private static long changedMs(JSONObject o, long fallback) {
        try {
            return OffsetDateTime.parse(o.optString("last_changed")).toInstant().toEpochMilli();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static String alarmWord(String state, boolean it) {
        switch (state) {
            case "disarmed": return it ? "Allarme disinserito" : "Alarm off";
            case "armed_home": return it ? "Allarme inserito in casa" : "Alarm on, home";
            case "armed_away": return it ? "Allarme inserito" : "Alarm on";
            case "armed_night": return it ? "Allarme notte" : "Alarm on, night";
            case "arming": return it ? "Allarme in attivazione" : "Alarm arming";
            case "pending": return it ? "Allarme in attesa" : "Alarm pending";
            default: return (it ? "Allarme " : "Alarm ") + state;
        }
    }

    /** "Tapparelle su", "Tapparelle giù", or "Tapparelle: 4 su, 3 giù" when some are each way. */
    static String shutters(int up, int down, boolean it) {
        if (down == 0) return it ? "Tapparelle su" : "Shutters up";
        if (up == 0) return it ? "Tapparelle giù" : "Shutters down";
        return it ? "Tapparelle: " + up + " su, " + down + " giù" : "Shutters: " + up + " up, " + down + " down";
    }
}
