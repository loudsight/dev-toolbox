package com.loudsight.scriptkit;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The library script header ({@code @purpose}, {@code @keywords}, {@code @tier}, {@code @usage},
 * {@code @example}), written as {@code #} comments in shell and {@code //} comments in Java.
 */
public final class ScriptHeader {

    public static final List<String> FIELDS = List.of("purpose", "keywords", "tier", "usage", "example");

    private static final Pattern LINE = Pattern.compile("^(?:#|//) @(" + String.join("|", FIELDS) + "):\\s*(.*)$");

    private ScriptHeader() {
    }

    /** The first value of each header field present in {@code script}, in file order. */
    public static Map<String, String> read(Path script) {
        try {
            return parse(Files.readAllLines(script));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static Map<String, String> parse(List<String> lines) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (String line : lines) {
            Matcher matcher = LINE.matcher(line);
            if (matcher.matches()) {
                fields.putIfAbsent(matcher.group(1), matcher.group(2).strip());
            }
        }
        return fields;
    }

    /**
     * The standard {@code --help}: when the first argument is {@code -h} or {@code --help}, prints
     * the running script's header and returns true, and the script should exit 0.
     */
    public static boolean handleHelp(String... args) {
        return handleHelp(args, System.out);
    }

    static boolean handleHelp(String[] args, PrintStream out) {
        if (args.length == 0 || !("-h".equals(args[0]) || "--help".equals(args[0]))) {
            return false;
        }
        Optional<Path> script = Env.script();
        if (script.isEmpty()) {
            out.println("no header: JX_SCRIPT is not set - run through scripts/jx");
            return true;
        }
        read(script.get()).forEach((field, value) -> out.println(field + ": " + value));
        return true;
    }
}
