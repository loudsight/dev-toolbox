package com.loudsight.scriptkit;

import java.io.PrintStream;
import java.util.Locale;

/**
 * The ok/FAIL checklist of the pre- and post-flight scripts: every check is reported, and the
 * script's exit code says whether any failed.
 */
public final class Checks {

    private final PrintStream out;
    private int failures;

    public Checks() {
        this(System.out);
    }

    public Checks(PrintStream out) {
        this.out = out;
    }

    public void ok(String message) {
        note("ok", message);
    }

    public void bad(String message) {
        failures++;
        note("FAIL", message);
    }

    public void warn(String message) {
        note("warn", message);
    }

    /** A check that could not apply here - reported, not counted as a failure. */
    public void skip(String message) {
        note("skip", message);
    }

    /** A continuation line under the previous check (e.g. the files it found), indented to line up. */
    public void detail(String line) {
        out.println("           " + line);
    }

    /** Reports {@code okMessage} when {@code passed}, else counts a failure with {@code badMessage}. */
    public boolean check(boolean passed, String okMessage, String badMessage) {
        if (passed) {
            ok(okMessage);
        } else {
            bad(badMessage);
        }
        return passed;
    }

    public int failures() {
        return failures;
    }

    public boolean passed() {
        return failures == 0;
    }

    /** Prints the verdict and returns the exit code: 0 when every check passed, else 1. */
    public int finish(String name) {
        out.println();
        if (passed()) {
            out.println("✅ " + name + " passed");
            return 0;
        }
        out.println("❌ " + name + " FAILED - " + failures + " check(s)");
        return 1;
    }

    private void note(String label, String message) {
        out.printf(Locale.ROOT, "  %-8s %s%n", label, message);
    }
}
