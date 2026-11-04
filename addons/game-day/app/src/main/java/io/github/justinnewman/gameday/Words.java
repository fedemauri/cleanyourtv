package io.github.justinnewman.gameday;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The word in front of the countdown in the TV's language. The launcher writes it on Home as it is, so an
 * Italian TV would otherwise read "Starts tra 2g 4h". Only Italian for now; any other language keeps the
 * English word. In Italian the generic "Starts" is dropped: the panel's title already names the event.
 */
final class Words {
    private static final Map<String, String> IT = new HashMap<>();

    static {
        IT.put("Kickoff", "Calcio d'inizio");
        IT.put("Lights out", "Partenza");
        IT.put("Green flag", "Bandiera verde");
        IT.put("Tip-off", "Palla a due");
        IT.put("First pitch", "Primo lancio");
        IT.put("Puck drop", "Ingaggio");
        IT.put("First serve", "Primo servizio");
        IT.put("First ball", "Prima palla");
        IT.put("First bounce", "Primo rimbalzo");
        IT.put("First stone", "Prima stone");
        IT.put("Tee time", "Partenza");
        IT.put("Opening bell", "Primo round");
        IT.put("Throw-in", "Inizio");
        IT.put("Push-back", "Inizio");
        IT.put("Throw-off", "Inizio");
        IT.put("Face-off", "Ingaggio");
        IT.put("Start gun", "Partenza");
        IT.put("Post time", "Partenza");
        IT.put("Bell time", "Inizio");
        IT.put("Game on", "Inizio");
        IT.put("Starts", "");
    }

    private Words() {
    }

    static String local(String word, Locale locale) {
        if (word == null || word.isEmpty() || locale == null) return word == null ? "" : word;
        if (!"it".equals(locale.getLanguage())) return word;
        String it = IT.get(word);
        return it == null ? word : it;
    }
}
