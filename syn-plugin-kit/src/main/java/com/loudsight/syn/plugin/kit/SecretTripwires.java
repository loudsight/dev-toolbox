package com.loudsight.syn.plugin.kit;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * A handful of high-precision patterns for the credentials whose shape is unmistakable, so {@code publish-check-private}
 * catches the worst leaks even on a machine with no secret scanner installed. This is the floor, not the net: betterleaks
 * (or gitleaks) runs alongside it when present and is the depth check. Patterns are chosen to almost never fire on
 * ordinary code; anything fuzzier (entropy, "password = ...") is the scanner's job, not this list's.
 */
final class SecretTripwires {

    private record Tripwire(String name, Pattern pattern) {
    }

    /** AWS's own documentation key: public, never a real credential, and what every tutorial and test pastes in. */
    private static final String DOCUMENTED_AWS_EXAMPLE = "AKIAIOSFODNN7EXAMPLE";

    private static final List<Tripwire> TRIPWIRES = List.of(
            new Tripwire("private key block", Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----")),
            new Tripwire("AWS access key id", Pattern.compile("\\b(?:AKIA|ASIA)[0-9A-Z]{16}\\b")),
            new Tripwire("GitHub token", Pattern.compile("\\bgh[pousr]_[A-Za-z0-9]{36,}\\b")),
            new Tripwire("GitHub fine-grained token", Pattern.compile("\\bgithub_pat_[A-Za-z0-9_]{50,}\\b")),
            new Tripwire("Slack token", Pattern.compile("\\bxox[baprs]-[A-Za-z0-9-]{10,}")),
            new Tripwire("Google API key", Pattern.compile("\\bAIza[0-9A-Za-z_-]{35}\\b")));

    private SecretTripwires() {
    }

    /** The names of the tripwires {@code text} trips; never the matched text, so a report cannot repeat a secret. */
    static List<String> hits(String text) {
        String scanned = text.replace(DOCUMENTED_AWS_EXAMPLE, "");
        List<String> names = new ArrayList<>();
        for (Tripwire tripwire : TRIPWIRES) {
            if (tripwire.pattern().matcher(scanned).find()) {
                names.add(tripwire.name());
            }
        }
        return names;
    }
}
