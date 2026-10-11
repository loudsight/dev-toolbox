package com.loudsight.syn.spi;

/**
 * How much a command can change, which decides what the head demands before running it. Mirrors the
 * tiers in the repo's AGENTS.md.
 */
public enum Tier {

    /** Changes nothing: run freely. */
    READ_ONLY("read-only"),
    /** Changes this machine or the repo: run and report. */
    LOCAL_MUTATE("local-mutate"),
    /** Changes a remote host: run it when asked to; no flag is needed. */
    REMOTE_MUTATE("remote-mutate"),
    /** Cannot be undone or goes public (a Maven Central release): only a human at a terminal, with {@code --confirm}. */
    IRREVERSIBLE("irreversible");

    private final String label;

    Tier(String label) {
        this.label = label;
    }

    /** The name used in catalogs and headers, e.g. {@code remote-mutate}. */
    public String label() {
        return label;
    }

    public static Tier parse(String label) {
        for (Tier tier : values()) {
            if (tier.label.equals(label)) {
                return tier;
            }
        }
        throw new IllegalArgumentException("unknown tier '" + label + "'");
    }
}
