package com.loudsight.syn.spi;

/**
 * Ends the running command with an exit code from anywhere in its call tree, the way {@code System.exit}
 * does in a script but without taking the JVM down, so the head can still report and the command stays
 * testable. The head turns it into the process exit code.
 */
public final class CommandExit extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int code;

    public CommandExit(int code) {
        super("exit " + code, null, false, false);
        this.code = code;
    }

    public int code() {
        return code;
    }
}
