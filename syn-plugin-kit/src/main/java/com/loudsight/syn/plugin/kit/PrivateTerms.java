package com.loudsight.syn.plugin.kit;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The owner's private denylist and the public repo's allowlist, for {@code publish-check-private}.
 *
 * <p>The denylist is one fixed string per line (blank lines and {@code #} comments ignored), matched case-insensitively.
 * It lives outside every public repo because the list is itself private, so a match is reported by the entry's
 * <em>line number</em>, never by its text: the report can then appear in a public CI log without repeating the secret.
 *
 * <p>The allowlist is one permitted substring per line (a public contact address, say). It is blanked out of the text
 * before the denylist is applied, so it excuses exactly that substring and nothing else on the line.
 */
final class PrivateTerms {

    /** A denylist entry in lower case and the 1-based line of the file it came from. */
    record Entry(int line, String lowered) {
    }

    private static final String GAP = "\u0000";

    private final List<Entry> entries;
    private final List<String> allowed;

    private PrivateTerms(List<Entry> entries, List<String> allowed) {
        this.entries = entries;
        this.allowed = allowed;
    }

    static PrivateTerms parse(List<String> denylistLines, List<String> allowlistLines) {
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < denylistLines.size(); i++) {
            String term = denylistLines.get(i).strip();
            if (!term.isEmpty() && term.charAt(0) != '#') {
                entries.add(new Entry(i + 1, term.toLowerCase(Locale.ROOT)));
            }
        }
        List<String> allowed = allowlistLines.stream()
                .map(String::strip)
                .filter(line -> !line.isEmpty() && line.charAt(0) != '#')
                .map(line -> line.toLowerCase(Locale.ROOT))
                .sorted(Comparator.comparingInt(String::length).reversed())
                .toList();
        return new PrivateTerms(List.copyOf(entries), allowed);
    }

    int size() {
        return entries.size();
    }

    /** The denylist line numbers whose entry occurs in {@code text}, once the allowlisted substrings are blanked out. */
    List<Integer> hits(String text) {
        String lowered = text.toLowerCase(Locale.ROOT);
        for (String permitted : allowed) {
            lowered = lowered.replace(permitted, GAP);
        }
        List<Integer> lines = new ArrayList<>();
        for (Entry entry : entries) {
            if (lowered.contains(entry.lowered())) {
                lines.add(entry.line());
            }
        }
        return lines;
    }
}
