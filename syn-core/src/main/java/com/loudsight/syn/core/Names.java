package com.loudsight.syn.core;

import java.util.Set;

/** The naming rule shared by commands and library scripts: {@code area-verb[-object]}. */
public final class Names {

    private static final Set<String> SCRIPT_EXTENSIONS = Set.of("sh", "py", "mjs");

    private Names() {
    }

    /** At least two parts, each lowercase letters and digits, joined by single hyphens. */
    public static boolean isAreaVerb(String name) {
        String[] parts = name.split("-", -1);
        if (parts.length < 2) {
            return false;
        }
        for (String part : parts) {
            if (part.isEmpty() || !part.chars().allMatch(c -> (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9'))) {
                return false;
            }
        }
        return true;
    }

    /** {@code area-verb[-object].sh} (or .py, .mjs): the shape of a library script's file name. */
    public static boolean isScriptFileName(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 && SCRIPT_EXTENSIONS.contains(fileName.substring(dot + 1)) && isAreaVerb(fileName.substring(0, dot));
    }
}
