package com.loudsight.syn.cli;

import com.loudsight.syn.core.SynConfig;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Two plugins offering a command of the same name is never resolved by luck of load order: it is a problem until the
 * repo's configuration says which one wins ({@code override.<command>=<plugin>}), and an override that has nothing to
 * override is a problem too, so a rename cannot leave a stale one behind. A problem stops every command except
 * {@code --list-plugins} and {@code --help}.
 */
final class PluginConflicts {

    static final String OVERRIDE_PREFIX = "override.";

    private static final String WHERE = SynConfig.DIRECTORY + "/" + SynConfig.FILE;

    /** One command on offer: its name, the plugin it comes from, and what the head needs to run it. */
    record Candidate<T>(String name, String origin, T item) {
    }

    /**
     * What to register.
     *
     * @param kept one candidate per command name; for a name with an unresolved problem, the first offered, so that it still parses
     * @param shadowed the origins that lost, by command name, where an override decided
     * @param problems what stops syn running commands
     */
    record Resolution<T>(List<Candidate<T>> kept, Map<String, List<String>> shadowed, List<String> problems) {
    }

    private PluginConflicts() {
    }

    static <T> Resolution<T> resolve(List<Candidate<T>> candidates, Map<String, String> overrides) {
        Map<String, List<Candidate<T>>> byName = new LinkedHashMap<>();
        for (Candidate<T> candidate : candidates) {
            byName.computeIfAbsent(candidate.name(), name -> new ArrayList<>()).add(candidate);
        }
        List<Candidate<T>> kept = new ArrayList<>();
        Map<String, List<String>> shadowed = new LinkedHashMap<>();
        List<String> problems = new ArrayList<>();
        byName.forEach((name, offered) -> {
            String wanted = overrides.get(name);
            List<String> origins = offered.stream().map(Candidate::origin).toList();
            if (offered.size() == 1) {
                if (wanted != null) {
                    problems.add(OVERRIDE_PREFIX + name + "=" + wanted + " overrides nothing: only " + origins.get(0) + " provides " + name
                            + "; remove it from " + WHERE);
                }
                kept.add(offered.get(0));
            } else if (wanted == null) {
                problems.add(name + " is provided by " + String.join(" and ", origins) + "; say which one wins with "
                        + OVERRIDE_PREFIX + name + "=<plugin> in " + WHERE);
                kept.add(offered.get(0));
            } else {
                List<Candidate<T>> winners = offered.stream().filter(candidate -> candidate.origin().equals(wanted)).toList();
                if (winners.size() == 1) {
                    kept.add(winners.get(0));
                    shadowed.put(name, origins.stream().filter(origin -> !origin.equals(wanted)).toList());
                } else {
                    problems.add(OVERRIDE_PREFIX + name + "=" + wanted + (winners.isEmpty()
                            ? " names a plugin that does not provide " + name + " (it comes from " + String.join(" and ", origins) + ")"
                            : " cannot decide: " + wanted + " provides " + name + " more than once"));
                    kept.add(offered.get(0));
                }
            }
        });
        overrides.forEach((name, wanted) -> {
            if (!byName.containsKey(name)) {
                problems.add(OVERRIDE_PREFIX + name + "=" + wanted + " overrides nothing: no plugin provides " + name + "; remove it from " + WHERE);
            }
        });
        return new Resolution<>(kept, shadowed, problems);
    }

    /** What {@code --list-plugins} prints: where plugins are loaded from, what each provides, who won, and what is wrong. */
    static <T> String report(Path pluginsDir, List<Candidate<T>> candidates, Resolution<T> resolution) {
        Map<String, List<String>> byOrigin = new LinkedHashMap<>();
        for (Candidate<T> candidate : candidates) {
            byOrigin.computeIfAbsent(candidate.origin(), origin -> new ArrayList<>()).add(candidate.name());
        }
        StringBuilder out = new StringBuilder("plugins dir: ").append(pluginsDir).append(System.lineSeparator());
        byOrigin.forEach((origin, names) -> out.append(origin).append(": ").append(String.join(", ", names)).append(System.lineSeparator()));
        if (!resolution.shadowed().isEmpty()) {
            out.append(System.lineSeparator()).append("overrides in ").append(WHERE).append(':').append(System.lineSeparator());
            resolution.shadowed().forEach((name, losers) -> out.append("  ").append(name).append(": ")
                    .append(winner(resolution, name)).append(" replaces ").append(String.join(" and ", losers)).append(System.lineSeparator()));
        }
        if (!resolution.problems().isEmpty()) {
            out.append(System.lineSeparator()).append("problems (no command runs until these are fixed):").append(System.lineSeparator());
            resolution.problems().forEach(problem -> out.append("  ").append(problem).append(System.lineSeparator()));
        }
        return out.toString().stripTrailing();
    }

    private static <T> String winner(Resolution<T> resolution, String name) {
        return resolution.kept().stream().filter(candidate -> candidate.name().equals(name)).map(Candidate::origin).findFirst().orElse("?");
    }
}
