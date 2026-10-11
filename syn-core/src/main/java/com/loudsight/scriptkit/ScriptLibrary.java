package com.loudsight.scriptkit;

import com.loudsight.syn.core.SynConfig;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * A repo's script library: where the remaining shell, Python and mjs scripts live, and the path of the generated
 * catalog. Repeatable tasks are syn commands, not scripts, so only what has to stay a script (sourced environment
 * files, hooks, launchers) is found here. Set in {@code .syn/config.properties} (see {@link SynConfig}):
 * {@code catalog.script-dirs} (default {@code scripts}), {@code catalog.script-exempt} (scripts that are configuration, not
 * tools; default none) and {@code catalog.index} (default {@code scripts/INDEX.md}).
 */
public final class ScriptLibrary {

    public static final List<String> EXTENSIONS = List.of(".sh", ".py", ".mjs");
    public static final String DEFAULT_DIR = "scripts";
    public static final String DEFAULT_INDEX = "scripts/INDEX.md";

    private final Path root;
    private final List<String> dirs;
    private final Set<String> exempt;
    private final String index;

    private ScriptLibrary(Path root, List<String> dirs, Set<String> exempt, String index) {
        this.root = root;
        this.dirs = dirs;
        this.exempt = exempt;
        this.index = index;
    }

    public static ScriptLibrary load(Path root) {
        SynConfig config = SynConfig.load(root);
        List<String> dirs = config.list("catalog.script-dirs");
        return new ScriptLibrary(root, dirs.isEmpty() ? List.of(DEFAULT_DIR) : dirs, Set.copyOf(config.list("catalog.script-exempt")),
                config.get("catalog.index").orElse(DEFAULT_INDEX));
    }

    /** Reviewed library directories, in catalog order, relative to the repo root. */
    public List<String> dirs() {
        return List.copyOf(dirs);
    }

    /** The generated catalog's path, relative to the repo root. */
    public String index() {
        return index;
    }

    /** Repo-relative paths of the scripts in {@link #dirs()}, per directory by extension then name, exempt scripts left out. */
    public List<String> files() {
        List<String> files = new ArrayList<>();
        for (String dir : dirs) {
            Path directory = root.resolve(dir);
            if (!Files.isDirectory(directory)) {
                continue;
            }
            for (String extension : EXTENSIONS) {
                try (Stream<Path> entries = Files.list(directory)) {
                    entries.filter(Files::isRegularFile)
                            .map(path -> dir + "/" + path.getFileName())
                            .filter(path -> path.endsWith(extension) && !exempt.contains(path))
                            .sorted()
                            .forEach(files::add);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        }
        return files;
    }

    /** The catalog's scripts, sorted by path. */
    public List<String> catalogFiles() {
        return files().stream().sorted().toList();
    }

    /** How a script is invoked by name: its file name. */
    public static String commandName(String file) {
        return file.substring(file.lastIndexOf('/') + 1);
    }
}
