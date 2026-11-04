package io.github.justinnewman.gameday;

import static org.junit.Assert.assertEquals;

import java.io.StringReader;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class IcsRecurTest {
    private static final ZoneId ROME = ZoneId.of("Europe/Rome");

    private static Instant rome(String local) {
        return LocalDateTime.parse(local).atZone(ROME).toInstant();
    }

    // As Google Calendar exports a shared calendar: a weekly master, one week skipped, one moved, one cancelled.
    private static final String ICS = String.join("\r\n",
            "BEGIN:VCALENDAR",
            "VERSION:2.0",
            "BEGIN:VEVENT",
            "DTSTART;TZID=Europe/Rome:20260107T213000",
            "DTEND;TZID=Europe/Rome:20260107T223000",
            "RRULE:FREQ=WEEKLY;BYDAY=WE",
            "EXDATE;TZID=Europe/Rome:20261111T213000",
            "UID:serata@google.com",
            "SUMMARY:Serata gioco",
            "END:VEVENT",
            "BEGIN:VEVENT",
            "DTSTART;TZID=Europe/Rome:20261119T200000",
            "DTEND;TZID=Europe/Rome:20261119T210000",
            "RECURRENCE-ID;TZID=Europe/Rome:20261118T213000",
            "UID:serata@google.com",
            "SUMMARY:Serata gioco (giovedì)",
            "END:VEVENT",
            "BEGIN:VEVENT",
            "DTSTART;TZID=Europe/Rome:20261125T213000",
            "RECURRENCE-ID;TZID=Europe/Rome:20261125T213000",
            "STATUS:CANCELLED",
            "UID:serata@google.com",
            "SUMMARY:Serata gioco",
            "END:VEVENT",
            "BEGIN:VEVENT",
            "DTSTART;TZID=Europe/Rome:20261106T170000",
            "DTEND;TZID=Europe/Rome:20261106T180000",
            "UID:gomme@google.com",
            "SUMMARY:Cambio gomme T-ROC",
            "END:VEVENT",
            "END:VCALENDAR", "");

    @Test
    public void repeatingEventsExpandWithSkippedMovedAndCancelledWeeks() throws Exception {
        List<Ics.Event> events = Ics.parse(new StringReader(ICS), ROME, rome("2026-11-04T12:00"), rome("2026-12-01T00:00"));
        List<String> got = new ArrayList<>();
        events.sort((a, b) -> a.start.compareTo(b.start));
        for (Ics.Event e : events) got.add(e.start.atZone(ROME).toLocalDateTime() + " " + e.title);
        assertEquals(Arrays.asList(
                "2026-11-04T21:30 Serata gioco",
                "2026-11-06T17:00 Cambio gomme T-ROC",
                "2026-11-19T20:00 Serata gioco (giovedì)"), got);
        assertEquals(rome("2026-11-04T22:30"), events.get(0).end);
    }
}
