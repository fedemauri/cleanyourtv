package io.github.justinnewman.gameday;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public class RecurTest {
    private static final ZoneId ROME = ZoneId.of("Europe/Rome");

    private static Instant rome(String local) {
        return LocalDateTime.parse(local).atZone(ROME).toInstant();
    }

    private static List<String> starts(String rule, String dtstart, String from, String to, Instant... exdates) {
        Recur r = Recur.parse(rule);
        List<String> out = new ArrayList<>();
        List<Instant> ex = new ArrayList<>();
        Collections.addAll(ex, exdates);
        for (Instant i : r.starts(LocalDateTime.parse(dtstart).atZone(ROME), rome(from), rome(to), ex)) {
            out.add(i.atZone(ROME).toLocalDateTime().toString());
        }
        return out;
    }

    @Test
    public void weeklyFromLongAgoLandsOnThisWeek() {
        // "Serata gioco": every Wednesday at 21:30 since January.
        assertEquals(java.util.Arrays.asList("2026-11-04T21:30", "2026-11-11T21:30"),
                starts("FREQ=WEEKLY;BYDAY=WE", "2026-01-07T21:30", "2026-11-04T12:00", "2026-11-12T00:00"));
    }

    @Test
    public void keepsTheLocalTimeAcrossDaylightSaving() {
        // Created in summer time, still 21:30 local after the clocks go back on 25 October.
        assertEquals(java.util.Arrays.asList("2026-10-28T21:30"),
                starts("FREQ=WEEKLY", "2026-07-01T21:30", "2026-10-27T00:00", "2026-10-29T00:00"));
    }

    @Test
    public void intervalCountAndUntil() {
        assertEquals(java.util.Arrays.asList("2026-11-06T11:00"),
                starts("FREQ=WEEKLY;INTERVAL=2", "2026-10-23T11:00", "2026-10-30T00:00", "2026-11-13T00:00"));
        assertEquals(Collections.emptyList(),
                starts("FREQ=DAILY;COUNT=3", "2026-11-01T09:00", "2026-11-04T00:00", "2026-11-10T00:00"));
        assertEquals(java.util.Arrays.asList("2026-11-04T09:00"),
                starts("FREQ=DAILY;UNTIL=20261104T235959Z", "2026-11-01T09:00", "2026-11-04T00:00", "2026-11-10T00:00"));
    }

    @Test
    public void severalWeekdays() {
        assertEquals(java.util.Arrays.asList("2026-11-05T18:00", "2026-11-07T18:00", "2026-11-10T18:00"),
                starts("FREQ=WEEKLY;BYDAY=TU,TH,SA", "2026-09-01T18:00", "2026-11-04T12:00", "2026-11-11T00:00"));
    }

    @Test
    public void monthlyByDayOfMonthAndByOrdinalWeekday() {
        assertEquals(java.util.Arrays.asList("2026-11-10T11:00"),
                starts("FREQ=MONTHLY", "2024-03-10T11:00", "2026-11-04T00:00", "2026-11-30T00:00"));
        assertEquals(java.util.Arrays.asList("2026-11-27T20:00"),
                starts("FREQ=MONTHLY;BYDAY=-1FR", "2026-01-30T20:00", "2026-11-04T00:00", "2026-12-01T00:00"));
        assertEquals(java.util.Arrays.asList("2026-11-14T10:00"),
                starts("FREQ=MONTHLY;BYDAY=2SA", "2026-01-10T10:00", "2026-11-04T00:00", "2026-12-01T00:00"));
    }

    @Test
    public void yearlyBirthday() {
        assertEquals(java.util.Arrays.asList("2026-11-08T00:00"),
                starts("FREQ=YEARLY", "1990-11-08T00:00", "2026-11-04T00:00", "2026-11-12T00:00"));
    }

    @Test
    public void exdateRemovesOneOccurrence() {
        assertEquals(java.util.Arrays.asList("2026-11-11T21:30"),
                starts("FREQ=WEEKLY", "2026-01-07T21:30", "2026-11-04T12:00", "2026-11-12T00:00", rome("2026-11-04T21:30")));
    }

    @Test
    public void unsupportedRulesAreNotExpanded() {
        assertNull(Recur.parse("FREQ=HOURLY"));
        assertNull(Recur.parse("FREQ=MONTHLY;BYSETPOS=-1;BYDAY=MO,TU,WE,TH,FR"));
        assertNull(Recur.parse("NOT A RULE"));
    }
}
