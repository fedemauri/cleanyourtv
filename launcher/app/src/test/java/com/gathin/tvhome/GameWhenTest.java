package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Locale;
import org.junit.Test;

public class GameWhenTest {
    private static final ZoneId ROME = ZoneId.of("Europe/Rome");
    private static final GameWhen.Templates IT = new GameWhen.Templates(
            "Tra %1$d min", "Oggi alle %1$s", "Domani alle %1$s", "%1$s alle %2$s", "%1$s %2$s");
    private static final GameWhen.Templates EN = new GameWhen.Templates(
            "In %1$d min", "Today at %1$s", "Tomorrow at %1$s", "%1$s at %2$s", "%1$s %2$s");

    private static long at(int day, int hour, int minute) {
        return ZonedDateTime.of(2026, 11, day, hour, minute, 0, 0, ROME).toInstant().toEpochMilli();
    }

    private static final long NOW = at(4, 12, 0);   // Wednesday 4 November 2026, 12:00

    @Test
    public void underAnHourCountsDownInMinutes() {
        assertEquals("Tra 25 min", GameWhen.text(at(4, 12, 25), NOW, ROME, Locale.ITALIAN, true, "", IT));
        assertEquals("Tra 1 min", GameWhen.text(NOW + 10_000, NOW, ROME, Locale.ITALIAN, true, "", IT));
    }

    @Test
    public void laterTodayAndTomorrowNameTheDay() {
        assertEquals("Oggi alle 17:00", GameWhen.text(at(4, 17, 0), NOW, ROME, Locale.ITALIAN, true, "", IT));
        assertEquals("Domani alle 09:30", GameWhen.text(at(5, 9, 30), NOW, ROME, Locale.ITALIAN, true, "", IT));
    }

    @Test
    public void laterThisWeekNamesWeekdayAndDate() {
        assertEquals("Venerdì 6 alle 17:00", GameWhen.text(at(6, 17, 0), NOW, ROME, Locale.ITALIAN, true, "", IT));
        assertEquals("Friday 6 at 5:00 PM", GameWhen.text(at(6, 17, 0), NOW, ROME, Locale.ENGLISH, false, "", EN));
    }

    @Test
    public void aSportWordGoesInFrontAndTheRestIsLowerCase() {
        assertEquals("Calcio d'inizio domani alle 20:45",
                GameWhen.text(at(5, 20, 45), NOW, ROME, Locale.ITALIAN, true, "Calcio d'inizio", IT));
        assertEquals("Calcio d'inizio tra 10 min",
                GameWhen.text(at(4, 12, 10), NOW, ROME, Locale.ITALIAN, true, "Calcio d'inizio", IT));
    }

    @Test
    public void midnightCountsAsTomorrow() {
        long lateNight = at(4, 23, 0);
        assertEquals("Domani alle 00:30", GameWhen.text(at(5, 0, 30), lateNight, ROME, Locale.ITALIAN, true, "", IT));
    }
}
