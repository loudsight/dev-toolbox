package com.loudsight.syn.plugin.kit;

import com.loudsight.scriptkit.Cmd;
import com.loudsight.syn.core.SynConfig;
import com.loudsight.syn.spi.Context;
import com.loudsight.syn.spi.ExitCodes;
import com.loudsight.syn.spi.SynCommand;
import com.loudsight.syn.spi.Tier;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Every shell and Python script in the repository and its submodules is a row in a manifest (by default
 * {@code .syn/script-ceiling.tsv}), which is the high-water mark: the file count, the line total and the count of
 * inline interpreter calls (python3 -, perl -e...) may only go down. New scripting is Java, a syn command.
 *
 * <p>Settings in {@code .syn/config.properties} (see {@link SynConfig}): {@code ceiling.manifest}; {@code ceiling.exclude}, a
 * regex for paths that are not counted; {@code ceiling.categories-file}, lines of {@code regex<TAB>category} (first match wins,
 * {@code #} comments) that label each row; {@code ceiling.default-category} (default {@code unclassified}) for the rest; and
 * {@code ceiling.docs}, where the manifest header points readers.
 *
 * <ul>
 *   <li>(no option) check; exit 1 on an unlisted script, a missing listed one, or a mark exceeded</li>
 *   <li>--lower rewrite the manifest to the current, lower figures (refuses to go up)</li>
 *   <li>--raise R accept the current figures even when higher, recording reason R in the manifest - only for shell
 *       that must stay shell (sourced env, git hook, container entrypoint) or an urgent fix; the manifest diff is
 *       how a reviewer sees it</li>
 *   <li>--baseline write the manifest from scratch (once; refuses when it exists)</li>
 * </ul>
 */
@Command(name = "script-check-ceiling", mixinStandardHelpOptions = true,
        description = "fail when shell/Python scripts grow past their high-water mark — use when a commit adds or grows a script",
        footer = "Example: syn script-check-ceiling --lower   # after migrating a script to Java")
public final class ScriptCheckCeiling implements SynCommand {

    private static final Pattern SCRIPT_EXTENSION = Pattern.compile(".*\\.(sh|bash|zsh|py)$");
    private static final Pattern SHEBANG = Pattern.compile("^#!.*\\b(sh|bash|zsh|python[0-9.]*|perl)\\b.*");
    private static final Pattern INLINE_INTERPRETER = Pattern.compile("\\b(python[0-9.]*|perl|node|ruby)\\s+(-c|-e|-)(\\s|$)");

    private static final String DEFAULT_MANIFEST = ".syn/script-ceiling.tsv";
    private static final String DEFAULT_CATEGORY = "unclassified";

    @Option(names = "--lower", description = "Rewrite the manifest to the current, lower figures (refuses to go up)")
    private boolean lower;

    @Option(names = "--raise", paramLabel = "REASON", description = "Accept the current figures even when higher, recording the reason")
    private @Nullable String raise;

    @Option(names = "--baseline", description = "Write the manifest from scratch (once; refuses when it exists)")
    private boolean baseline;

    private PrintStream out = new PrintStream(PrintStream.nullOutputStream(), true, StandardCharsets.UTF_8);
    private Settings settings = new Settings(DEFAULT_MANIFEST, null, DEFAULT_CATEGORY, List.of(), "", false);

    private record Row(String path, int lines, String category) {
    }

    /**
     * What the repo configured.
     *
     * @param exclude paths that are not counted, or null
     * @param rules the burn-down labels: first matching path regex wins
     * @param docs where the manifest header sends readers; empty for nowhere
     * @param categorised whether {@code rules} came from a file, which decides if the header explains categories
     */
    private record Settings(String manifest, @Nullable Pattern exclude, String defaultCategory,
                            List<Map.Entry<Pattern, String>> rules, String docs, boolean categorised) {

        static Settings load(Path root) throws IOException {
            SynConfig config = SynConfig.load(root);
            Pattern exclude = config.get("ceiling.exclude").map(Pattern::compile).orElse(null);
            Optional<Path> file = config.path("ceiling.categories-file");
            return new Settings(config.get("ceiling.manifest").orElse(DEFAULT_MANIFEST), exclude,
                    config.get("ceiling.default-category").orElse(DEFAULT_CATEGORY),
                    file.isPresent() ? rules(file.get()) : List.of(), config.get("ceiling.docs").orElse(""), file.isPresent());
        }

        private static List<Map.Entry<Pattern, String>> rules(Path file) throws IOException {
            List<Map.Entry<Pattern, String>> rules = new ArrayList<>();
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] cells = line.split("\t", -1);
                if (cells.length != 2 || cells[0].isBlank() || cells[1].isBlank()) {
                    throw new IllegalArgumentException("ceiling.categories-file " + file.getFileName() + ": expected regex<TAB>category, got: " + line);
                }
                rules.add(Map.entry(Pattern.compile(cells[0]), cells[1].strip()));
            }
            return rules;
        }
    }

    private record Tally(Map<String, Integer> lines, int inline) {

        int total() {
            return lines.values().stream().mapToInt(Integer::intValue).sum();
        }
    }

    private record Manifest(Map<String, Row> rows, int inline, List<String> history) {

        int total() {
            return rows.values().stream().mapToInt(Row::lines).sum();
        }
    }

    /** Writing the manifest changes the repo; the plain check changes nothing. */
    @Override
    public Tier tier() {
        return lower || raise != null || baseline ? Tier.LOCAL_MUTATE : Tier.READ_ONLY;
    }

    @Override
    public List<String> keywords() {
        return List.of("high-water mark", "ceiling", "ratchet", "shell scripts", "python", "bash", "legacy scripts", "burn-down", "java scripting");
    }

    @Override
    public int run(Context context) throws IOException {
        out = context.out().stream();
        Path root = context.repoRoot();
        try {
            settings = Settings.load(root);
        } catch (IllegalArgumentException | UncheckedIOException e) {
            return usage(context, String.valueOf(e.getMessage()));
        }
        Tally now = tally(root);
        Path manifestFile = root.resolve(settings.manifest());
        int modes = (lower ? 1 : 0) + (raise != null ? 1 : 0) + (baseline ? 1 : 0);
        if (modes > 1) {
            return usage(context, "choose one of --lower, --raise and --baseline");
        }
        if (baseline) {
            return baseline(context, manifestFile, now);
        }
        if (lower) {
            return lower(manifestFile, read(manifestFile), now);
        }
        if (raise != null) {
            return raise.isBlank()
                    ? usage(context, "--raise needs a reason")
                    : raise(manifestFile, read(manifestFile), now, raise);
        }
        return check(read(manifestFile), now);
    }

    private static int usage(Context context, String problem) {
        context.out().errorln("script-check-ceiling: " + problem + " - see --help");
        return ExitCodes.USAGE;
    }

    /** Every counted script, with its line count, plus the inline interpreter calls across them. */
    private Tally tally(Path root) throws IOException {
        Map<String, Integer> lines = new TreeMap<>();
        int inline = 0;
        Pattern excluded = settings.exclude();
        for (String path : Cmd.of("git", "ls-files", "--recurse-submodules").dir(root).capture().lines()) {
            Path file = root.resolve(path);
            if ((excluded != null && excluded.matcher(path).find()) || path.endsWith(".java") || !Files.isRegularFile(file)) {
                continue;
            }
            List<String> content = readLines(file);
            boolean script = SCRIPT_EXTENSION.matcher(path).matches()
                    || (!path.substring(path.lastIndexOf('/') + 1).contains(".")
                            && !content.isEmpty() && SHEBANG.matcher(content.getFirst()).matches());
            if (script) {
                lines.put(path, content.size());
                inline += (int) content.stream().filter(line -> INLINE_INTERPRETER.matcher(line).find()).count();
            }
        }
        return new Tally(lines, inline);
    }

    private static List<String> readLines(Path file) throws IOException {
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (MalformedInputException ignoredBinary) {
            return List.of();
        }
    }

    private int check(Manifest mark, Tally now) {
        List<String> problems = new ArrayList<>();
        now.lines().keySet().stream().filter(path -> !mark.rows().containsKey(path))
                .forEach(path -> problems.add("new script " + path + " - write it in Java (a syn command), or --raise if it must be shell"));
        mark.rows().keySet().stream().filter(path -> !now.lines().containsKey(path))
                .forEach(path -> problems.add("listed script is gone: " + path + " - run --lower to take the credit"));
        if (now.total() > mark.total()) {
            problems.add("shell lines " + now.total() + " > mark " + mark.total() + " - move the logic to Java, or --raise");
        }
        if (now.inline() > mark.inline()) {
            problems.add("inline interpreter calls " + now.inline() + " > mark " + mark.inline() + " (python3 -, perl -e…)");
        }
        problems.forEach(problem -> out.println("  FAIL     " + problem));
        out.printf(Locale.ROOT, "legacy scripts: %d files / %d lines / %d inline (mark %d / %d / %d)%n",
                now.lines().size(), now.total(), now.inline(), mark.rows().size(), mark.total(), mark.inline());
        if (!problems.isEmpty()) {
            return ExitCodes.FAILURE;
        }
        if (now.total() < mark.total() || now.inline() < mark.inline()) {
            out.println("below the mark - lock it in: syn script-check-ceiling --lower");
        }
        return ExitCodes.OK;
    }

    private int baseline(Context context, Path manifestFile, Tally now) throws IOException {
        if (Files.exists(manifestFile)) {
            return usage(context, settings.manifest() + " exists - --baseline is for the first run only");
        }
        Map<String, Row> rows = new LinkedHashMap<>();
        now.lines().forEach((path, lines) -> rows.put(path, new Row(path, lines, categorise(path))));
        write(manifestFile, new Manifest(rows, now.inline(),
                List.of("# " + today() + " baseline: " + rows.size() + " files, " + now.total() + " lines, " + now.inline() + " inline")));
        return check(read(manifestFile), now);
    }

    private int lower(Path manifestFile, Manifest mark, Tally now) throws IOException {
        boolean added = now.lines().keySet().stream().anyMatch(path -> !mark.rows().containsKey(path));
        if (added || now.total() > mark.total() || now.inline() > mark.inline()) {
            out.println("not lower than the mark - nothing written");
            return check(mark, now);
        }
        return rewrite(manifestFile, mark, now, "# " + today() + " lowered to " + now.lines().size() + " files, "
                + now.total() + " lines, " + now.inline() + " inline");
    }

    private int raise(Path manifestFile, Manifest mark, Tally now, String reason) throws IOException {
        return rewrite(manifestFile, mark, now, "# " + today() + " RAISED to " + now.lines().size() + " files, "
                + now.total() + " lines, " + now.inline() + " inline: " + reason.replaceAll("\\s+", " "));
    }

    private int rewrite(Path manifestFile, Manifest mark, Tally now, String historyLine) throws IOException {
        Map<String, Row> rows = new LinkedHashMap<>();
        now.lines().forEach((path, lines) -> {
            Row existing = mark.rows().get(path);
            rows.put(path, new Row(path, lines, existing != null ? existing.category() : categorise(path)));
        });
        List<String> history = new ArrayList<>(mark.history());
        history.add(historyLine);
        write(manifestFile, new Manifest(rows, now.inline(), history));
        out.println(historyLine.substring(2) + " - commit " + settings.manifest());
        return ExitCodes.OK;
    }

    private String categorise(String path) {
        return settings.rules().stream().filter(rule -> rule.getKey().matcher(path).find())
                .map(Map.Entry::getValue).findFirst().orElse(settings.defaultCategory());
    }

    private static String today() {
        return LocalDate.now(ZoneOffset.UTC).toString();
    }

    private Manifest read(Path manifestFile) throws IOException {
        if (!Files.exists(manifestFile)) {
            throw new IllegalStateException(settings.manifest() + " is missing - run --baseline once");
        }
        Map<String, Row> rows = new LinkedHashMap<>();
        List<String> history = new ArrayList<>();
        int inline = 0;
        for (String line : Files.readAllLines(manifestFile, StandardCharsets.UTF_8)) {
            String[] cells = line.split("\t", -1);
            if (line.startsWith("# 20")) {
                history.add(line);
            } else if ("inline-interpreter-calls".equals(cells[0])) {
                inline = Integer.parseInt(cells[1]);
            } else if (cells.length == 3 && !line.startsWith("#") && !"path".equals(cells[0])) {
                rows.put(cells[0], new Row(cells[0], Integer.parseInt(cells[1]), cells[2]));
            }
        }
        return new Manifest(rows, inline, history);
    }

    private void write(Path manifestFile, Manifest manifest) throws IOException {
        List<String> lines = new ArrayList<>(List.of(
                "# High-water mark for shell/Python scripts - written by syn script-check-ceiling, never by hand.",
                "# The file count, line total and inline-interpreter count may only fall."
                        + (settings.docs().isEmpty() ? "" : " Why and how: " + settings.docs())));
        if (settings.categorised()) {
            lines.add("# category: exempt-* stays shell (sourced env, git hook, entrypoint, launcher, in-container helper); migrate-wN is its burn-down week.");
        }
        lines.addAll(manifest.history());
        lines.add("inline-interpreter-calls\t" + manifest.inline());
        lines.add("path\tlines\tcategory");
        manifest.rows().values().forEach(row -> lines.add(row.path() + "\t" + row.lines() + "\t" + row.category()));
        Files.createDirectories(Objects.requireNonNull(manifestFile.getParent()));
        Files.write(manifestFile, lines, StandardCharsets.UTF_8);
    }
}
