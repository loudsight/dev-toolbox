package com.loudsight.syn.spi;

/**
 * Where a command writes. Under {@code --json}, stdout carries exactly one JSON document per
 * {@link #emit} and nothing else, so a caller can parse it; progress and diagnostics go to stderr.
 */
public interface Output {

    /** The command's result: {@code data} as JSON under {@code --json}, else the {@code human} text. */
    void emit(Object data, String human);

    /** Progress for a person: stdout normally, stderr under {@code --json}. */
    void println(String message);

    /** A problem worth seeing whatever the mode: always stderr. */
    void errorln(String message);

    /** Text without a trailing newline, where {@link #println} writes. */
    default void print(String text) {
        stream().print(text);
    }

    /**
     * The stream for free-form text (printf, a checklist): where {@link #println} writes, so stdout normally and
     * stderr under {@code --json}. For code that wants a {@link java.io.PrintStream} rather than a method.
     */
    java.io.PrintStream stream();
}
