package com.loudsight.syn.core;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.UnaryOperator;

/**
 * A repo's syn settings: {@code .syn/config.properties} under the repo root, committed with the repo so a
 * change to them is reviewed like any other. A key {@code plugins.dir} can be overridden for one run by the
 * system property {@code syn.plugins.dir}; the system property wins, then the file. A missing file is an
 * empty configuration, not an error; one that exists and cannot be read is.
 */
public final class SynConfig {

    public static final String DIRECTORY = ".syn";
    public static final String FILE = "config.properties";
    /** Prefix that turns a key into the system property that overrides it. */
    public static final String PROPERTY_PREFIX = "syn.";

    private final Path repoRoot;
    private final Properties file;

    private SynConfig(Path repoRoot, Properties file) {
        this.repoRoot = repoRoot;
        this.file = file;
    }

    public static SynConfig load(Path repoRoot) {
        Properties properties = new Properties();
        Path path = repoRoot.resolve(DIRECTORY).resolve(FILE);
        if (Files.exists(path)) {
            try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                properties.load(reader);
            } catch (IOException e) {
                throw new UncheckedIOException("cannot read " + path, e);
            }
        }
        return new SynConfig(repoRoot, properties);
    }

    /** The value of {@code key}: its system property, else the file's entry; a blank value counts as absent. */
    public Optional<String> get(String key) {
        return Optional.ofNullable(System.getProperty(PROPERTY_PREFIX + key))
                .filter(value -> !value.isBlank())
                .or(() -> Optional.ofNullable(file.getProperty(key)).filter(value -> !value.isBlank()))
                .map(String::strip);
    }

    /** {@link #get} split on commas, each item stripped, blanks dropped; empty when the key is absent. */
    public List<String> list(String key) {
        return get(key).stream()
                .flatMap(value -> Arrays.stream(value.split(",")))
                .map(String::strip)
                .filter(item -> !item.isEmpty())
                .toList();
    }

    /** Every setting whose key starts with {@code prefix}, by the rest of the key: the file's, then the system properties' (which win). */
    public Map<String, String> withPrefix(String prefix) {
        Map<String, String> found = new TreeMap<>();
        collect(file.stringPropertyNames(), prefix, file::getProperty, found);
        collect(System.getProperties().stringPropertyNames(), PROPERTY_PREFIX + prefix, System::getProperty, found);
        return found;
    }

    private static void collect(Set<String> names, String prefix, UnaryOperator<String> value, Map<String, String> into) {
        for (String name : names) {
            String text = value.apply(name);
            if (name.startsWith(prefix) && name.length() > prefix.length() && text != null && !text.isBlank()) {
                into.put(name.substring(prefix.length()), text.strip());
            }
        }
    }

    /** {@link #get} as a path, resolved against the repo root when relative. */
    public Optional<Path> path(String key) {
        return get(key).map(value -> repoRoot.resolve(value).normalize());
    }
}
