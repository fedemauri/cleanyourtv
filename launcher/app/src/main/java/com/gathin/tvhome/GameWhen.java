package com.gathin.tvhome;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * When a game_day entry starts, as the panel's second line: "Tra 25 min", "Oggi alle 17:00", "Domani alle 9:30",
 * "Venerdì 6 alle 17:00". A countdown in days ("tra 2g 4h") makes the reader do arithmetic for a family
 * calendar; a weekday and a time do not. Under an hour the countdown is still the useful form. The add-on's
 * word, when it has one ("Calcio d'inizio"), goes in front. Pure java.time, no Android, so it is unit tested.
 */
final class GameWhen {
    /** The five patterns, from string resources on the TV and literals in the tests. */
    static final class Templates {
        final String minutes, today, tomorrow, day, withWord;

        Templates(String minutes, String today, String tomorrow, String day, String withWord) {
            this.minutes = minutes;
            this.today = today;
            this.tomorrow = tomorrow;
            this.day = day;
            this.withWord = withWord;
        }
    }

    private GameWhen() {
    }

    static String text(long startMs, long nowMs, ZoneId zone, Locale locale, boolean is24, String word, Templates t) {
        long untilMs = startMs - nowMs;
        String when;
        if (untilMs < 60 * 60_000L) {
            long minutes = Math.max(1, (untilMs + 59_999) / 60_000);
            when = String.format(locale, t.minutes, minutes);
        } else {
            ZonedDateTime start = Instant.ofEpochMilli(startMs).atZone(zone);
            LocalDate today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate();
            String time = DateTimeFormatter.ofPattern(is24 ? "HH:mm" : "h:mm a", locale).format(start);
            LocalDate day = start.toLocalDate();
            if (day.equals(today)) when = String.format(locale, t.today, time);
            else if (day.equals(today.plusDays(1))) when = String.format(locale, t.tomorrow, time);
            else when = String.format(locale, t.day, DateTimeFormatter.ofPattern("EEEE d", locale).format(start), time);
        }
        if (word == null || word.isEmpty()) return capitalise(when, locale);
        return String.format(locale, t.withWord, word, lowerFirst(when, locale));
    }

    private static String capitalise(String s, Locale locale) {
        return s.isEmpty() ? s : s.substring(0, 1).toUpperCase(locale) + s.substring(1);
    }

    private static String lowerFirst(String s, Locale locale) {
        return s.isEmpty() ? s : s.substring(0, 1).toLowerCase(locale) + s.substring(1);
    }
}
