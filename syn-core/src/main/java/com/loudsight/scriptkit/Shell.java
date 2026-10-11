package com.loudsight.scriptkit;

import java.util.regex.Pattern;

/** Quoting for the places a command line must still pass through a shell (ssh, bash -ic). */
public final class Shell {

    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9_@%+=:,./-]+");

    private Shell() {
    }

    /** Single-quotes {@code word} for POSIX shells unless it needs no quoting. */
    public static String quote(String word) {
        String quoted = "'" + word.replace("'", "'\"'\"'") + "'";
        return !word.isEmpty() && SAFE.matcher(word).matches() ? word : quoted;
    }
}
