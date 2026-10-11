package com.loudsight.syn.plugin.kit;

import com.loudsight.scriptkit.Cmd;
import com.loudsight.scriptkit.ScriptHeader;
import com.loudsight.scriptkit.ScriptLibrary;
import com.loudsight.syn.core.CatalogIndex;
import com.loudsight.syn.core.Names;
import com.loudsight.syn.core.SynConfig;
import com.loudsight.syn.spi.CommandDescriptor;
import com.loudsight.syn.spi.Context;
import com.loudsight.syn.spi.ExitCodes;
import com.loudsight.syn.spi.SynCommand;
import com.loudsight.syn.spi.Tier;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import picocli.CommandLine.Command;

/**
 * Meant to run from a pre-commit hook whenever a commit touches a library script, the catalog or the agent instruction file.
 *
 * <p>Every syn command (found through the context, so every plugin's) must:
 * <ul>
 *   <li>be named area-verb[-object]: lowercase, hyphenated, at least two parts (the builtin commands excepted);</li>
 *   <li>have a purpose of at most {@code conventions.purpose-max} (default 130) characters that says "use when", has no template placeholder left in it,
 *       and differs from every other command's;</li>
 *   <li>have keywords, which is what {@code syn catalog <term>} searches besides the name and purpose.</li>
 * </ul>
 * Every remaining shell, Python and mjs script in the library dirs must:
 * <ul>
 *   <li>be named area-verb[-object] plus an extension, and be executable;</li>
 *   <li>have a header with @purpose, @keywords, @tier, @usage and @example, the purpose under the same rules as
 *       a command's, the tier one of read-only, local-mutate, remote-mutate, irreversible;</li>
 *   <li>carry the standard --help line, and {@code <script> --help </dev/null} must exit 0 and print the purpose. A
 *       script without the standard line is reported, never executed - it might not treat --help as a no-op.</li>
 * </ul>
 * It also fails when the generated catalog is stale and when the agent instruction file ({@code conventions.instruction-file},
 * default {@code AGENTS.md}) exceeds {@code conventions.instruction-max-chars} (default 12,000; some agents truncate rule files
 * beyond a limit). A default instruction file that does not exist is skipped; one that was configured and is missing fails.
 * Settings are in {@code .syn/config.properties} (see {@link SynConfig}).
 */
@Command(name = "script-check-conventions", mixinStandardHelpOptions = true,
        description = "fail when a command or script breaks the naming, header, tier or --help rules — use when adding or editing one",
        footer = "Example: syn script-check-conventions && git commit ...")
public final class ScriptCheckConventions implements SynCommand {

    private static final Set<String> TIERS = Set.of("read-only", "local-mutate", "remote-mutate", "irreversible");
    private static final String SHELL_HELP_LINE =
            "case \"${1:-}\" in -h|--help) grep -E '^# @(purpose|keywords|tier|usage|example):' \"$0\" | sed 's/^# @//'; exit 0 ;; esac";
    private static final String DEFAULT_INSTRUCTION_FILE = "AGENTS.md";
    private static final int DEFAULT_INSTRUCTION_MAX_CHARS = 12_000;
    private static final int DEFAULT_PURPOSE_MAX_CHARS = 130;

    private int failures;
    private int purposeMax = DEFAULT_PURPOSE_MAX_CHARS;
    private PrintStream out = new PrintStream(PrintStream.nullOutputStream(), true, StandardCharsets.UTF_8);

    private void fail(String file, String problem) {
        failures++;
        out.printf(Locale.ROOT, "%-58s %s%n", file, problem);
    }

    @Override
    public Tier tier() {
        return Tier.READ_ONLY;
    }

    @Override
    public List<String> keywords() {
        return List.of("lint scripts", "conventions", "header check", "pre-commit", "validate scripts", "validate commands", "AGENTS.md size");
    }

    @Override
    public int run(Context context) throws IOException {
        out = context.out().stream();
        failures = 0;
        Path root = context.repoRoot();
        SynConfig config = SynConfig.load(root);
        int instructionMax;
        try {
            purposeMax = number(config, "conventions.purpose-max", DEFAULT_PURPOSE_MAX_CHARS);
            instructionMax = number(config, "conventions.instruction-max-chars", DEFAULT_INSTRUCTION_MAX_CHARS);
        } catch (IllegalArgumentException e) {
            context.out().errorln(String.valueOf(e.getMessage()));
            return ExitCodes.USAGE;
        }

        Map<String, String> purposes = new HashMap<>();
        List<CommandDescriptor> commands = context.commands();
        for (CommandDescriptor command : commands) {
            checkCommand(command, purposes);
        }

        ScriptLibrary library = ScriptLibrary.load(root);
        List<String> files = library.files();
        for (String file : files) {
            checkScript(root, file, purposes);
        }

        if (!CatalogIndex.isCurrent(root, commands)) {
            context.out().errorln(library.index() + " is stale - run: syn script-list-catalog --markdown");
            fail(library.index(), "stale");
        }

        String instructionFile = config.get("conventions.instruction-file").orElse(DEFAULT_INSTRUCTION_FILE);
        Path instructions = root.resolve(instructionFile);
        String instructionStatus = "";
        if (Files.exists(instructions)) {
            String text = Files.readString(instructions, StandardCharsets.UTF_8);
            int chars = text.codePointCount(0, text.length());
            if (instructionMax > 0 && chars > instructionMax) {
                fail(instructionFile, chars + " chars (max " + instructionMax + ")");
            }
            instructionStatus = "; " + instructionFile + " is " + chars + (instructionMax > 0 ? "/" + instructionMax : "") + " chars";
        } else if (config.get("conventions.instruction-file").isPresent()) {
            fail(instructionFile, "does not exist (conventions.instruction-file)");
        }

        if (failures > 0) {
            out.println(failures + " convention violation(s).");
            return ExitCodes.FAILURE;
        }
        out.println("OK - " + commands.size() + " commands and " + files.size() + " scripts follow the conventions" + instructionStatus + ".");
        return ExitCodes.OK;
    }

    private static int number(SynConfig config, String key, int fallback) {
        return config.get(key).map(value -> {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(key + " is not a number: " + value, e);
            }
        }).orElse(fallback);
    }

    private void checkCommand(CommandDescriptor command, Map<String, String> purposes) {
        String where = "syn " + command.name();
        boolean builtin = "builtin".equals(command.origin());
        if (!builtin && !Names.isAreaVerb(command.name())) {
            fail(where, "name is not area-verb[-object]");
        }
        if (command.keywords().isEmpty()) {
            fail(where, "no keywords (override keywords() in the command)");
        }
        checkPurpose(where, command.purpose(), purposes);
        if (!TIERS.contains(command.tier())) {
            fail(where, "tier '" + command.tier() + "' is not one of: read-only local-mutate remote-mutate irreversible");
        }
    }

    /** The purpose rules shared by commands and scripts; {@code purposes} maps each purpose seen to where. */
    private void checkPurpose(String where, String purpose, Map<String, String> purposes) {
        if (purpose.isEmpty()) {
            fail(where, "no purpose (the @Command description)");
            return;
        }
        int length = purpose.codePointCount(0, purpose.length());
        if (length > purposeMax) {
            fail(where, "purpose is " + length + " chars (max " + purposeMax + ")");
        }
        if (!purpose.contains("use when")) {
            fail(where, "purpose must say \"use when <situation>\"");
        }
        if (purpose.contains("<")) {
            fail(where, "purpose still contains a template placeholder");
        }
        String earlier = purposes.putIfAbsent(purpose, where);
        if (earlier != null) {
            fail(where, "purpose duplicates " + earlier + "'s - merge them");
        }
    }

    private void checkScript(Path root, String file, Map<String, String> purposes) throws IOException {
        Path path = root.resolve(file);
        String name = String.valueOf(path.getFileName());
        if (!Names.isScriptFileName(name)) {
            fail(file, "name is not area-verb[-object]");
        }
        if (!Files.isExecutable(path)) {
            fail(file, "not executable (chmod +x, then git add)");
        }
        Map<String, String> header = ScriptHeader.read(path);
        for (String field : ScriptHeader.FIELDS) {
            if (header.getOrDefault(field, "").isEmpty()) {
                fail(file, "missing header field @" + field);
            }
        }
        String purpose = header.getOrDefault("purpose", "");
        if (!purpose.isEmpty()) {
            checkPurpose(file, purpose, purposes);
        }
        String tier = header.getOrDefault("tier", "");
        if (!tier.isEmpty() && !TIERS.contains(tier)) {
            fail(file, "@tier '" + tier + "' is not one of: read-only local-mutate remote-mutate irreversible");
        }
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        if (name.endsWith(".sh")) {
            if (lines.contains(SHELL_HELP_LINE)) {
                checkHelp(file, Cmd.of("bash", file, "--help").dir(root), Duration.ofSeconds(10));
            } else {
                fail(file, "standard --help line missing (see syn script-create-scaffold)");
            }
        }
    }

    /** Runs {@code --help} with no stdin: it must exit 0 and print the header, within {@code limit}. */
    private void checkHelp(String file, Cmd help, Duration limit) {
        try {
            Cmd.Result result = help.mergeErr().stdin("").timeout(limit).capture();
            if (!result.ok()) {
                fail(file, "--help exited " + result.exitCode());
            }
            if (!result.stdout().contains("purpose:")) {
                fail(file, "--help does not print the header");
            }
        } catch (Cmd.CmdException e) {
            fail(file, "--help did not finish: " + e.getMessage());
        }
    }
}
