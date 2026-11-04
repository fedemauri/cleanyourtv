package io.github.justinnewman.gameday;

import static org.junit.Assert.assertEquals;

import java.util.Locale;
import org.junit.Test;

public class WordsTest {
    private static final Locale IT = Locale.ITALIAN;

    @Test
    public void italianSportWords() {
        assertEquals("Calcio d'inizio", Words.local("Kickoff", IT));
        assertEquals("Partenza", Words.local("Lights out", IT));
        assertEquals("Palla a due", Words.local("Tip-off", IT));
        assertEquals("Ingaggio", Words.local("Puck drop", IT));
    }

    @Test
    public void genericStartsIsDroppedInItalian() {
        // The event's own title already says what it is; the launcher shows when it starts.
        assertEquals("", Words.local("Starts", IT));
    }

    @Test
    public void everySportWordHasAnItalianOne() {
        for (Sport s : Sport.values()) {
            String it = Words.local(s.word(), IT);
            if (!s.word().equals("Starts")) {
                assertEquals(s.word() + " is not translated", false, it.equals(s.word()));
            }
        }
    }

    @Test
    public void otherLanguagesAreLeftAlone() {
        assertEquals("Kickoff", Words.local("Kickoff", Locale.ENGLISH));
        assertEquals("Starts", Words.local("Starts", Locale.GERMAN));
        assertEquals("", Words.local("", IT));
        assertEquals("Unknown word", Words.local("Unknown word", IT));
    }
}
