package com.loudsight.syn.spi;

import java.util.List;
import java.util.Locale;

/**
 * What the catalog knows about one command: the row {@code syn catalog} prints, also handed to commands
 * (through {@link Context#commands()}) that report on or check the command set itself.
 *
 * @param tier the tier's label, e.g. {@code remote-mutate}
 * @param origin {@code builtin}, or the name of the plugin the command came from
 */
public record CommandDescriptor(String name, String group, String tier, String purpose, List<String> keywords, String origin) {

    public CommandDescriptor {
        keywords = List.copyOf(keywords);
    }

    /** Whether every term appears in the name, purpose or keywords, ignoring case. */
    public boolean matchesAll(List<String> terms) {
        String haystack = (name + ' ' + purpose + ' ' + String.join(" ", keywords)).toLowerCase(Locale.ROOT);
        return terms.stream().allMatch(term -> haystack.contains(term.toLowerCase(Locale.ROOT)));
    }
}
