package com.gathin.tvhome;

import android.content.Context;
import android.os.Handler;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Fork casa: game_day may name several add-ons, separated by commas ("io.github.justinnewman.gameday,
 * io.github.maxhayim.smarthome"). Each one that publishes a panel provider is a {@link GameDay} source, and the
 * panel rotates through the turns of all of them. The first source sets the seconds per turn.
 */
final class PanelSources {
    private final List<GameDay> sources;

    private PanelSources(List<GameDay> sources) {
        this.sources = sources;
    }

    /** The packages in a game_day value, in order, without blanks or repeats. */
    static List<String> packages(String value) {
        List<String> out = new ArrayList<>();
        if (value == null) return out;
        for (String part : value.split(",")) {
            String p = part.trim();
            if (!p.isEmpty() && !out.contains(p)) out.add(p);
        }
        return out;
    }

    /** Null when no listed package publishes a panel provider. */
    static PanelSources create(Context ctx, String value, Handler main, Runnable onChange) {
        List<GameDay> found = new ArrayList<>();
        for (String pkg : packages(value)) {
            GameDay g = GameDay.create(ctx, pkg, main, onChange);
            if (g != null) found.add(g);
        }
        return found.isEmpty() ? null : new PanelSources(found);
    }

    List<GameDay.Turn> turns() {
        if (sources.size() == 1) return sources.get(0).turns();
        List<GameDay.Turn> all = new ArrayList<>();
        for (GameDay g : sources) all.addAll(g.turns());
        return Collections.unmodifiableList(all);
    }

    int turnSeconds() {
        return sources.get(0).turnSeconds();
    }

    void read() {
        for (GameDay g : sources) g.read();
    }

    void start() {
        for (GameDay g : sources) g.start();
    }

    void stop() {
        for (GameDay g : sources) g.stop();
    }

    void shutdown() {
        for (GameDay g : sources) g.shutdown();
    }
}
