package io.github.maxhayim.smarthome;

import static org.junit.Assert.assertEquals;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class PanelTest {
    private static final ZoneId ROME = ZoneId.of("Europe/Rome");
    private static final long NOW = ZonedDateTime.of(2026, 11, 4, 15, 0, 0, 0, ROME).toInstant().toEpochMilli();

    private static JSONObject st(String id, String state, String name, String deviceClass, String changed) throws Exception {
        JSONObject a = new JSONObject().put("friendly_name", name);
        if (deviceClass != null) a.put("device_class", deviceClass);
        return new JSONObject().put("entity_id", id).put("state", state).put("attributes", a)
                .put("last_changed", changed == null ? "2026-11-04T11:44:03.826421+00:00" : changed);
    }

    private static JSONArray home(boolean garageOpen) throws Exception {
        return new JSONArray()
                .put(new JSONObject().put("entity_id", "weather.forecast_casa").put("state", "cloudy")
                        .put("attributes", new JSONObject().put("temperature", 13.0).put("friendly_name", "Forecast Casa")))
                .put(st("sensor.energia_casa_temperatura", "50.4", "Energia casa Temperatura", "temperature", null))
                .put(st("sensor.blink_salotto_temperatura", "17.2222", "Salotto Temperatura", "temperature", null))
                .put(st("sensor.blink_camere_temperatura", "20.5555", "Camere Temperatura", "temperature", null))
                .put(st("sensor.blink_esterno_temperatura", "16.1111", "Esterno Temperatura", "temperature", null))
                .put(st("sensor.blink_garage_temperatura", "unknown", "Garage Temperatura", "temperature", null))
                .put(st("binary_sensor.garage", garageOpen ? "on" : "off", "Garage", "garage_door", "2026-11-04T13:20:00+00:00"))
                .put(st("binary_sensor.lampada_arco_overheated", "off", "Lampada arco Overheated", "problem", null))
                .put(st("alarm_control_panel.blink_casa", "disarmed", "blink Casa", null, null))
                .put(st("cover.tutte_le_tapparelle", "open", "Tutte le tapparelle", null, null)
                        .put("attributes", new JSONObject().put("friendly_name", "Tutte le tapparelle")
                                .put("entity_id", new JSONArray().put("cover.sala").put("cover.studio").put("cover.cucina"))))
                .put(st("cover.sala", "open", "Sala", "shutter", null))
                .put(st("cover.studio", "closed", "Studio", "shutter", null))
                .put(st("cover.cucina", "open", "Lavello Cucina", "shutter", null));
    }

    @Test
    public void weatherLineWithTodaysRange() throws Exception {
        JSONObject forecast = new JSONObject("{\"service_response\":{\"weather.forecast_casa\":{\"forecast\":"
                + "[{\"condition\":\"cloudy\",\"temperature\":13.3,\"templow\":11.2}]}}}");
        assertEquals("13° Nuvoloso · min 11° max 13°", Panel.weatherLine(home(false), forecast, Locale.ITALIAN));
        assertEquals("13° Nuvoloso", Panel.weatherLine(home(false), null, Locale.ITALIAN));
        assertEquals("13° Cloudy", Panel.weatherLine(home(false), null, Locale.ENGLISH));
        assertEquals("", Panel.weatherLine(new JSONArray(), null, Locale.ITALIAN));
    }

    @Test
    public void casaTurnPicksRoomTemperaturesAndListsAlarmAndShutters() throws Exception {
        List<Panel.Turn> t = Panel.turns(home(false), Collections.emptyList(), NOW, ROME, Locale.ITALIAN);
        assertEquals(1, t.size());
        assertEquals("Casa", t.get(0).top);
        // 50° (a device's own temperature) and "unknown" are left out; names lose "Temperatura".
        assertEquals("Camere 21° · Esterno 16° · Salotto 17°", t.get(0).headline);
        assertEquals("Allarme disinserito     Tapparelle: 2 su, 1 giù", t.get(0).detail);
        assertEquals("Tapparelle su", Panel.shutters(3, 0, true));
    }

    @Test
    public void namedSensorsKeepTheirOrder() throws Exception {
        List<Panel.Turn> t = Panel.turns(home(false), Arrays.asList("sensor.blink_salotto_temperatura",
                "sensor.blink_camere_temperatura", "sensor.blink_esterno_temperatura"), NOW, ROME, Locale.ITALIAN);
        assertEquals("Salotto 17° · Camere 21° · Esterno 16°", t.get(0).headline);
    }

    @Test
    public void anOpenGarageComesFirstWithItsTime() throws Exception {
        List<Panel.Turn> t = Panel.turns(home(true), Collections.emptyList(), NOW, ROME, Locale.ITALIAN);
        assertEquals(2, t.size());
        assertEquals("Garage", t.get(0).top);
        assertEquals("Aperto dalle 14:20", t.get(0).headline);
        assertEquals(Panel.RED, t.get(0).colour);
        assertEquals("Casa", t.get(1).top);
    }
}
