package io.github.justinnewman.gameday;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * A repeating event's RRULE (RFC 5545, 3.3.10), for the rules a family calendar uses: every day, week, month or
 * year, with INTERVAL, COUNT or UNTIL, the weekdays of a week (BYDAY=TU,TH), a weekday of the month (BYDAY=2SA,
 * -1FR), a day of the month (BYMONTHDAY) and the months of a year (BYMONTH). Anything else (BYSETPOS, hourly
 * rules, ...) is not expanded and the event keeps its first occurrence, as before. Occurrences are built in the
 * event's own zone, so a 21:30 stays 21:30 across a daylight-saving change.
 */
final class Recur {
    enum Freq { DAILY, WEEKLY, MONTHLY, YEARLY }

    /** Upper bound on the periods walked, so a broken rule can never spin. Daily for 30 years is about 11000. */
    private static final int MAX_STEPS = 20000;
    private static final DateTimeFormatter UNTIL_TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss", Locale.ROOT);
    private static final DateTimeFormatter UNTIL_DATE = DateTimeFormatter.ofPattern("yyyyMMdd", Locale.ROOT);

    final Freq freq;
    final int interval;
    /** 0: no COUNT. */
    final int count;
    /** Null: no UNTIL. Kept raw: a date-only UNTIL is read in the event's zone. */
    final String until;
    /** BYDAY: ordinal 0 for "every", otherwise 1..5 or -1..-5. */
    final List<int[]> byDay;
    final List<Integer> byMonthDay;
    final List<Integer> byMonth;

    private Recur(Freq freq, int interval, int count, String until, List<int[]> byDay, List<Integer> byMonthDay,
                  List<Integer> byMonth) {
        this.freq = freq;
        this.interval = interval;
        this.count = count;
        this.until = until;
        this.byDay = byDay;
        this.byMonthDay = byMonthDay;
        this.byMonth = byMonth;
    }

    /** Null for a rule this class does not expand. */
    static Recur parse(String rule) {
        if (rule == null) return null;
        Freq freq = null;
        int interval = 1, count = 0;
        String until = null;
        List<int[]> byDay = new ArrayList<>();
        List<Integer> byMonthDay = new ArrayList<>();
        List<Integer> byMonth = new ArrayList<>();
        try {
            for (String part : rule.trim().split(";")) {
                int eq = part.indexOf('=');
                if (eq <= 0) return null;
                String k = part.substring(0, eq).trim().toUpperCase(Locale.ROOT);
                String v = part.substring(eq + 1).trim().toUpperCase(Locale.ROOT);
                switch (k) {
                    case "FREQ":
                        freq = Freq.valueOf(v);
                        break;
                    case "INTERVAL":
                        interval = Integer.parseInt(v);
                        break;
                    case "COUNT":
                        count = Integer.parseInt(v);
                        break;
                    case "UNTIL":
                        until = v;
                        break;
                    case "WKST":
                        break;   // weeks start on Monday here; only matters with INTERVAL>1 and Sunday starts
                    case "BYDAY":
                        for (String d : v.split(",")) byDay.add(day(d.trim()));
                        break;
                    case "BYMONTHDAY":
                        for (String d : v.split(",")) byMonthDay.add(Integer.parseInt(d.trim()));
                        break;
                    case "BYMONTH":
                        for (String d : v.split(",")) byMonth.add(Integer.parseInt(d.trim()));
                        break;
                    default:
                        return null;   // BYSETPOS, BYHOUR, BYWEEKNO, ...: not expanded
                }
            }
        } catch (IllegalArgumentException bad) {   // NumberFormatException included
            return null;
        }
        if (freq == null || interval < 1 || count < 0) return null;
        return new Recur(freq, interval, count, until, byDay, byMonthDay, byMonth);
    }

    private static int[] day(String d) {
        String code = d.substring(d.length() - 2);
        String n = d.substring(0, d.length() - 2);
        int ordinal = n.isEmpty() || n.equals("+") ? 0 : Integer.parseInt(n.replace("+", ""));
        DayOfWeek dow;
        switch (code) {
            case "MO": dow = DayOfWeek.MONDAY; break;
            case "TU": dow = DayOfWeek.TUESDAY; break;
            case "WE": dow = DayOfWeek.WEDNESDAY; break;
            case "TH": dow = DayOfWeek.THURSDAY; break;
            case "FR": dow = DayOfWeek.FRIDAY; break;
            case "SA": dow = DayOfWeek.SATURDAY; break;
            case "SU": dow = DayOfWeek.SUNDAY; break;
            default: throw new IllegalArgumentException(d);
        }
        if (ordinal < -5 || ordinal > 5) throw new IllegalArgumentException(d);
        return new int[] {dow.getValue(), ordinal};
    }

    /**
     * The occurrence starts from {@code from} (inclusive) to {@code to} (exclusive), soonest first, without the
     * {@code exdates}. COUNT counts every occurrence from the first one, excluded ones included (RFC 5545).
     */
    List<Instant> starts(ZonedDateTime dtstart, Instant from, Instant to, Collection<Instant> exdates) {
        Instant untilAt = untilInstant(dtstart);
        List<Instant> out = new ArrayList<>();
        int generated = 0;
        for (int step = 0; step < MAX_STEPS; step++) {
            List<ZonedDateTime> period = period(dtstart, step);
            boolean pastEnd = false;
            for (ZonedDateTime c : period) {
                if (c.isBefore(dtstart)) continue;
                Instant at = c.toInstant();
                if (untilAt != null && at.isAfter(untilAt)) { pastEnd = true; break; }
                if (count > 0 && generated >= count) { pastEnd = true; break; }
                generated++;
                if (!at.isBefore(to)) { pastEnd = true; break; }
                if (!at.isBefore(from) && !exdates.contains(at)) out.add(at);
            }
            if (pastEnd) break;
            // Nothing in this period can be after `to` yet only when the period is empty (Feb 30th): keep going.
            if (!period.isEmpty() && !period.get(period.size() - 1).toInstant().isBefore(to)) break;
        }
        return out;
    }

    private Instant untilInstant(ZonedDateTime dtstart) {
        if (until == null) return null;
        try {
            if (until.length() == 8) {
                return LocalDate.parse(until, UNTIL_DATE).atTime(23, 59, 59).atZone(dtstart.getZone()).toInstant();
            }
            if (until.endsWith("Z")) {
                return LocalDateTime.parse(until.substring(0, until.length() - 1), UNTIL_TIME).toInstant(ZoneOffset.UTC);
            }
            return LocalDateTime.parse(until, UNTIL_TIME).atZone(dtstart.getZone()).toInstant();
        } catch (RuntimeException bad) {
            return null;
        }
    }

    /** The candidate starts of period {@code step} (0 is the one that holds DTSTART), sorted. */
    private List<ZonedDateTime> period(ZonedDateTime dtstart, int step) {
        LocalDate first = dtstart.toLocalDate();
        List<LocalDate> days = new ArrayList<>();
        switch (freq) {
            case DAILY:
                days.add(first.plusDays((long) step * interval));
                break;
            case WEEKLY: {
                LocalDate monday = first.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusWeeks((long) step * interval);
                if (byDay.isEmpty()) days.add(monday.plusDays(first.getDayOfWeek().getValue() - 1));
                for (int[] d : byDay) days.add(monday.plusDays(d[0] - 1));
                break;
            }
            case MONTHLY: {
                LocalDate month = first.withDayOfMonth(1).plusMonths((long) step * interval);
                inMonth(month, first, days);
                break;
            }
            case YEARLY: {
                LocalDate year = first.withDayOfYear(1).plusYears((long) step * interval);
                if (byMonth.isEmpty()) {
                    LocalDate month = year.withMonth(first.getMonthValue());
                    if (byDay.isEmpty() && byMonthDay.isEmpty()) {
                        if (first.getDayOfMonth() <= month.lengthOfMonth()) days.add(month.withDayOfMonth(first.getDayOfMonth()));
                    } else {
                        inMonth(month, first, days);
                    }
                } else {
                    for (int m : byMonth) inMonth(year.withMonth(m), first, days);
                }
                break;
            }
        }
        List<ZonedDateTime> out = new ArrayList<>();
        for (LocalDate d : days) out.add(ZonedDateTime.of(d, dtstart.toLocalTime(), dtstart.getZone()));
        Collections.sort(out);
        return out;
    }

    /** The days of {@code month} (its first day) that the BY rules pick; DTSTART's day of month without any. */
    private void inMonth(LocalDate month, LocalDate first, List<LocalDate> days) {
        int length = month.lengthOfMonth();
        if (!byMonthDay.isEmpty()) {
            for (int md : byMonthDay) {
                int d = md > 0 ? md : length + md + 1;
                if (d >= 1 && d <= length) days.add(month.withDayOfMonth(d));
            }
        } else if (!byDay.isEmpty()) {
            for (int[] bd : byDay) {
                DayOfWeek dow = DayOfWeek.of(bd[0]);
                if (bd[1] == 0) {
                    for (LocalDate d = month.with(TemporalAdjusters.firstInMonth(dow)); d.getMonth() == month.getMonth(); d = d.plusWeeks(1)) days.add(d);
                } else if (bd[1] > 0) {
                    LocalDate d = month.with(TemporalAdjusters.dayOfWeekInMonth(bd[1], dow));
                    if (d.getMonth() == month.getMonth()) days.add(d);
                } else {
                    LocalDate d = month.with(TemporalAdjusters.lastInMonth(dow)).plusWeeks(bd[1] + 1);
                    if (d.getMonth() == month.getMonth()) days.add(d);
                }
            }
        } else if (first.getDayOfMonth() <= length) {
            days.add(month.withDayOfMonth(first.getDayOfMonth()));
        }
    }
}
