package io.github.justinnewman.gameday;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;

/**
 * Reads the events of an iCalendar file (RFC 5545), as calendar apps subscribe to them: Stanza, Google Calendar's
 * secret address, a team's own feed. Only what Game Day needs: each event's title, start and end. Line by line,
 * keeping only the events in a window, so a season's file (Stanza's is about 130 KB) never sits in memory whole.
 * Repeating events (RRULE) are expanded in the window by {@link Recur}, with EXDATE and moved or cancelled
 * occurrences (RECURRENCE-ID); a rule Recur does not know keeps its first occurrence. Fork casa: a family calendar
 * is mostly repeating events ("Serata gioco" every Wednesday), which upstream read only as their first date.
 */
final class Ics {

    static final class Event {
        /** As written, emoji included; "" when the event has no SUMMARY. */
        final String title;
        final Instant start;
        /** Null when the calendar gives no end: the schedule then uses a typical length for the sport. */
        final Instant end;
        final boolean allDay;

        Event(String title, Instant start, Instant end, boolean allDay) {
            this.title = title;
            this.start = start;
            this.end = end;
            this.allDay = allDay;
        }
    }

    /** The body is not an iCalendar file: a web page (a sign-in page after a link was reset), an error, nothing. */
    static final class NotACalendar extends Exception {
        NotACalendar() {
            super("Not a calendar link");
        }
    }

    /** An event without an end is kept when it started less than this long before the window. */
    static final Duration NO_END_GRACE = Duration.ofHours(24);

    /** Some servers start the file with one (U+FEFF); it is not part of BEGIN:VCALENDAR. */
    private static final char BYTE_ORDER_MARK = (char) 0xFEFF;

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss", Locale.ROOT);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd", Locale.ROOT);

    private Ics() {
    }

    /**
     * The events that are still on or to come in the window: their end is after {@code from} and their start is
     * before {@code to}. Floating times and all-day dates are read in {@code zone}, the TV's time zone.
     */
    static List<Event> parse(Reader in, ZoneId zone, Instant from, Instant to) throws NotACalendar, IOException {
        return parse(in, zone, from, to, true);
    }

    /**
     * {@code expand} false reads a repeating event as its first occurrence only: for counting a file's entries
     * over all time (Fetcher), where expanding every weekly event to the year 2200 would be work for nothing.
     */
    static List<Event> parse(Reader in, ZoneId zone, Instant from, Instant to, boolean expand)
            throws NotACalendar, IOException {
        BufferedReader r = in instanceof BufferedReader ? (BufferedReader) in : new BufferedReader(in);
        Acc events = new Acc(expand);
        Deque<String> components = new ArrayDeque<>();
        boolean started = false;
        Draft draft = null;
        String pending = null;
        String raw;
        while (true) {
            raw = r.readLine();
            // A line that starts with a space or a tab continues the one before it (RFC 5545, 3.1).
            if (raw != null && pending != null && !raw.isEmpty() && (raw.charAt(0) == ' ' || raw.charAt(0) == '\t')) {
                pending += raw.substring(1);
                continue;
            }
            if (pending != null) {
                String line = pending;
                pending = null;
                if (!started) {
                    if (!line.isEmpty() && line.charAt(0) == BYTE_ORDER_MARK) line = line.substring(1);
                    if (line.trim().isEmpty()) {
                        if (raw == null) break;
                        pending = raw;
                        continue;
                    }
                    if (!line.trim().equalsIgnoreCase("BEGIN:VCALENDAR")) throw new NotACalendar();
                    started = true;
                }
                draft = take(line, components, draft, events, zone, from, to);
            }
            if (raw == null) break;
            pending = raw;
        }
        if (!started) throw new NotACalendar();
        events.expand(zone, from, to);
        return events.events;
    }

    /** What a parse collects: the events, the repeating ones to expand at the end, and the moved occurrences. */
    private static final class Acc {
        final List<Event> events = new ArrayList<>();
        final List<Master> masters = new ArrayList<>();
        /** uid|epoch millis of each occurrence a RECURRENCE-ID event replaces or cancels. */
        final Set<String> overridden = new HashSet<>();
        final boolean expand;

        Acc(boolean expand) {
            this.expand = expand;
        }

        void expand(ZoneId zone, Instant from, Instant to) {
            for (Master m : masters) {
                Duration length = m.base.end == null ? null : Duration.between(m.base.start, m.base.end);
                Instant since = from.minus(length != null ? length : NO_END_GRACE);
                for (Instant at : m.rule.starts(m.zonedStart, since, to, m.exdates)) {
                    if (overridden.contains(m.uid + "|" + at.toEpochMilli())) continue;
                    Instant end = null;
                    if (m.base.end != null) {
                        if (m.base.allDay) {
                            long days = ChronoUnit.DAYS.between(m.base.start.atZone(zone).toLocalDate(),
                                    m.base.end.atZone(zone).toLocalDate());
                            end = at.atZone(zone).plusDays(days).toInstant();
                        } else {
                            end = at.plus(length);
                        }
                    }
                    Event e = new Event(m.base.title, at, end, m.base.allDay);
                    if (inWindow(e, from, to)) events.add(e);
                }
            }
        }
    }

    private static final class Master {
        final String uid;
        final Event base;
        final ZonedDateTime zonedStart;
        final Recur rule;
        final List<Instant> exdates;

        Master(String uid, Event base, ZonedDateTime zonedStart, Recur rule, List<Instant> exdates) {
            this.uid = uid;
            this.base = base;
            this.zonedStart = zonedStart;
            this.rule = rule;
            this.exdates = exdates;
        }
    }

    /** One unfolded content line. Returns the event being read, if any. */
    private static Draft take(String line, Deque<String> components, Draft draft, Acc out,
                              ZoneId zone, Instant from, Instant to) {
        int colon = valueStart(line);
        if (colon < 0) return draft;
        String head = line.substring(0, colon);
        String value = line.substring(colon + 1);
        int semi = head.indexOf(';');
        String name = (semi < 0 ? head : head.substring(0, semi)).trim().toUpperCase(Locale.ROOT);
        String params = semi < 0 ? "" : head.substring(semi + 1);

        if (name.equals("BEGIN")) {
            String component = value.trim().toUpperCase(Locale.ROOT);
            components.push(component);
            return component.equals("VEVENT") && components.size() == 2 ? new Draft() : draft;
        }
        if (name.equals("END")) {
            String component = components.isEmpty() ? "" : components.pop();
            if (component.equals("VEVENT") && draft != null) {
                draft.collect(zone, from, to, out);
                return null;
            }
            return draft;
        }
        // Only the event's own properties: a VALARM inside it has a DESCRIPTION and TRIGGER of its own.
        if (draft == null || components.isEmpty() || !components.peek().equals("VEVENT")) return draft;
        switch (name) {
            case "SUMMARY":
                draft.title = unescape(value);
                break;
            case "DTSTART":
                draft.start = value.trim();
                draft.startParams = params;
                break;
            case "DTEND":
                draft.end = value.trim();
                draft.endParams = params;
                break;
            case "STATUS":
                draft.cancelled = value.trim().equalsIgnoreCase("CANCELLED");
                break;
            case "UID":
                draft.uid = value.trim();
                break;
            case "RRULE":
                draft.rrule = value.trim();
                break;
            case "EXDATE":
                draft.exdates.add(new String[] {value.trim(), params});
                break;
            case "RECURRENCE-ID":
                draft.recurrenceId = value.trim();
                draft.recurrenceParams = params;
                break;
            default:
                break;
        }
        return draft;
    }

    private static boolean inWindow(Event e, Instant from, Instant to) {
        if (!e.start.isBefore(to)) return false;
        Instant end = e.end != null ? e.end : e.start.plus(NO_END_GRACE);
        return end.isAfter(from);
    }

    /** The colon that ends the name and parameters; a colon inside a quoted parameter value does not count. */
    private static int valueStart(String line) {
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') quoted = !quoted;
            else if (c == ':' && !quoted) return i;
        }
        return -1;
    }

    private static String unescape(String v) {
        StringBuilder b = new StringBuilder(v.length());
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == '\\' && i + 1 < v.length()) {
                char n = v.charAt(++i);
                b.append(n == 'n' || n == 'N' ? '\n' : n);
            } else {
                b.append(c);
            }
        }
        return b.toString().trim();
    }

    private static final class Draft {
        String title = "";
        String start, startParams = "";
        String end, endParams = "";
        boolean cancelled;
        String uid = "";
        String rrule;
        final List<String[]> exdates = new ArrayList<>();
        String recurrenceId, recurrenceParams = "";

        /** A plain event goes straight in; a repeating one waits for the end of the file; a moved one is noted. */
        void collect(ZoneId zone, Instant from, Instant to, Acc acc) {
            if (recurrenceId != null) {
                try {
                    acc.overridden.add(uid + "|" + instant(recurrenceId, recurrenceParams, zone).toEpochMilli());
                } catch (DateTimeException bad) {
                    // An unreadable RECURRENCE-ID: the moved event still shows, the original may show twice.
                }
            }
            Event e = finish(zone);
            if (e == null) return;
            Recur rule = recurrenceId == null && acc.expand ? Recur.parse(rrule) : null;
            if (rule == null) {
                if (inWindow(e, from, to)) acc.events.add(e);
                return;
            }
            List<Instant> ex = new ArrayList<>();
            for (String[] x : exdates) {
                for (String v : x[0].split(",")) {
                    try {
                        ex.add(instant(v.trim(), x[1], zone));
                    } catch (DateTimeException bad) {
                        // skip an unreadable EXDATE
                    }
                }
            }
            ZoneId own = e.allDay ? zone : start.endsWith("Z") || start.endsWith("z") ? ZoneOffset.UTC : tzid(startParams, zone);
            acc.masters.add(new Master(uid, e, e.start.atZone(own), rule, ex));
        }

        Event finish(ZoneId zone) {
            if (cancelled || start == null) return null;
            try {
                boolean allDay = isDate(start, startParams);
                Instant s = instant(start, startParams, zone);
                Instant e = end == null ? null : instant(end, endParams, zone);
                if (allDay && e == null) e = LocalDate.parse(start, DATE).plusDays(1).atStartOfDay(zone).toInstant();
                if (e != null && !e.isAfter(s)) e = null;
                return new Event(title, s, e, allDay);
            } catch (DateTimeException bad) {
                return null;
            }
        }
    }

    private static boolean isDate(String value, String params) {
        return value.length() == 8 || params.toUpperCase(Locale.ROOT).contains("VALUE=DATE") && !params.toUpperCase(Locale.ROOT).contains("VALUE=DATE-TIME");
    }

    private static Instant instant(String value, String params, ZoneId zone) {
        if (isDate(value, params)) return LocalDate.parse(value.substring(0, 8), DATE).atStartOfDay(zone).toInstant();
        if (value.endsWith("Z") || value.endsWith("z")) {
            return LocalDateTime.parse(value.substring(0, value.length() - 1), DATE_TIME).toInstant(ZoneOffset.UTC);
        }
        return LocalDateTime.parse(value, DATE_TIME).atZone(tzid(params, zone)).toInstant();
    }

    /** The TZID parameter's zone; a name Java does not know (Outlook's "Mountain Standard Time") falls back. */
    private static ZoneId tzid(String params, ZoneId fallback) {
        for (String p : params.split(";")) {
            int eq = p.indexOf('=');
            if (eq > 0 && p.substring(0, eq).trim().equalsIgnoreCase("TZID")) {
                String id = p.substring(eq + 1).trim().replace("\"", "");
                try {
                    return ZoneId.of(id);
                } catch (DateTimeException unknown) {
                    return fallback;
                }
            }
        }
        return fallback;
    }
}
