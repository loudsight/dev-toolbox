package com.loudsight.syn.spi;

/** The exit codes syn promises, so CI and agents can tell a refusal from a failure. */
public final class ExitCodes {

    public static final int OK = 0;
    /** The command ran and failed. */
    public static final int FAILURE = 1;
    /** Unknown command or bad arguments (picocli's own code). */
    public static final int USAGE = 2;
    /** An irreversible command needs {@code --confirm} and it was not given. */
    public static final int CONFIRMATION_REQUIRED = 3;
    /** The command's tier is not runnable in this context (an irreversible command, not at a terminal). */
    public static final int REFUSED = 4;
    /** Two plugins offer a command of the same name and the configuration does not say which wins (or an override is stale). */
    public static final int PLUGIN_CONFLICT = 5;

    private ExitCodes() {
    }
}
