package com.loudsight.syn.plugin.kit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.loudsight.syn.core.CatalogIndex;
import com.loudsight.syn.plugin.kit.Fixtures.Result;
import com.loudsight.syn.spi.CommandDescriptor;
import com.loudsight.syn.spi.ExitCodes;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScriptCheckConventionsTest {

    private static CommandDescriptor good(String name, String purpose) {
        return Fixtures.command(name, "read-only", purpose, "k");
    }

    private static Path cleanRepo(Path root, List<CommandDescriptor> commands) throws IOException {
        Fixtures.write(root.resolve("AGENTS.md"), "# agents\n");
        Fixtures.write(root.resolve("scripts/INDEX.md"), CatalogIndex.markdown(root, commands));
        return root;
    }

    @Test
    void conventionsPassForWellFormedCommands(@TempDir Path root) throws Exception {
        List<CommandDescriptor> commands = List.of(good("logs-fetch-host", "copy logs — use when debugging"), good("kb-check-links", "find rot — use when moving docs"));
        cleanRepo(root, commands);

        Result result = Fixtures.run(new ScriptCheckConventions(), root, commands, false);

        assertEquals(ExitCodes.OK, result.exit(), result.stdout());
        assertTrue(result.stdout().contains("OK - 2 commands"), result.stdout());
    }

    @Test
    void conventionsFlagBadNamesPurposesKeywordsAndDuplicates(@TempDir Path root) throws Exception {
        List<CommandDescriptor> commands = List.of(
                good("single", "x — use when y"),
                good("aa-bb", "no trigger here"),
                good("cc-dd", "has <placeholder> — use when y"),
                good("ee-ff", "x".repeat(131) + " use when"),
                good("gg-hh", "same — use when z"),
                good("ii-jj", "same — use when z"),
                new CommandDescriptor("kk-ll", "kk", "read-only", "ok — use when q", List.of(), "plugin"),
                new CommandDescriptor("mm-nn", "mm", "sudo", "ok2 — use when q", List.of("k"), "plugin"));
        cleanRepo(root, commands);

        Result result = Fixtures.run(new ScriptCheckConventions(), root, commands, false);

        assertEquals(ExitCodes.FAILURE, result.exit(), result.stdout());
        String out = result.stdout();
        assertTrue(out.contains("syn single") && out.contains("name is not area-verb"), out);
        assertTrue(out.contains("purpose must say \"use when"), out);
        assertTrue(out.contains("template placeholder"), out);
        assertTrue(out.contains("purpose is 140 chars (max 130)") || out.contains("max 130"), out);
        assertTrue(out.contains("duplicates syn gg-hh"), out);
        assertTrue(out.contains("no keywords"), out);
        assertTrue(out.contains("tier 'sudo'"), out);
    }

    @Test
    void conventionsLetBuiltinCommandsHaveSingleWordNames(@TempDir Path root) throws Exception {
        List<CommandDescriptor> commands = List.of(new CommandDescriptor("catalog", "catalog", "read-only", "list — use when looking", List.of("k"), "builtin"));
        cleanRepo(root, commands);

        assertEquals(ExitCodes.OK, Fixtures.run(new ScriptCheckConventions(), root, commands, false).exit());
    }


    @Test
    void conventionsFailOnAStaleIndexAndAnOversizeAgentsFile(@TempDir Path root) throws Exception {
        List<CommandDescriptor> commands = List.of(good("aa-bb", "x — use when y"));
        cleanRepo(root, commands);
        Fixtures.write(root.resolve("AGENTS.md"), "x".repeat(12_001));
        Fixtures.write(root.resolve("scripts/INDEX.md"), "stale\n");

        Result result = Fixtures.run(new ScriptCheckConventions(), root, commands, false);

        assertEquals(ExitCodes.FAILURE, result.exit());
        assertTrue(result.stdout().contains("scripts/INDEX.md"), result.stdout());
        assertTrue(result.stdout().contains("AGENTS.md"), result.stdout());
        assertTrue(result.stderr().contains("is stale"), result.stderr());
    }

    @Test
    void conventionsHoldRemainingShellScriptsToTheirHeaderRules(@TempDir Path root) throws Exception {
        List<CommandDescriptor> commands = List.of();
        Fixtures.write(root.resolve("scripts/badname.sh"), "#!/usr/bin/env bash\n# @purpose: nothing\n");
        cleanRepo(root, commands);
        Fixtures.write(root.resolve("scripts/INDEX.md"), CatalogIndex.markdown(root, commands));

        Result result = Fixtures.run(new ScriptCheckConventions(), root, commands, false);

        assertEquals(ExitCodes.FAILURE, result.exit());
        assertTrue(result.stdout().contains("scripts/badname.sh") && result.stdout().contains("name is not area-verb"), result.stdout());
        assertTrue(result.stdout().contains("not executable"), result.stdout());
        assertTrue(result.stdout().contains("missing header field @keywords"), result.stdout());
        assertTrue(result.stdout().contains("standard --help line missing"), result.stdout());
    }

    private static void configure(Path root, String properties) throws IOException {
        Fixtures.write(root.resolve(".syn/config.properties"), properties);
    }

    @Test
    void theInstructionFileNameAndCapAreConfigurable(@TempDir Path root) throws Exception {
        List<CommandDescriptor> commands = List.of(good("aa-bb", "x — use when y"));
        cleanRepo(root, commands);
        Fixtures.write(root.resolve("CLAUDE.md"), "x".repeat(101));
        Fixtures.write(root.resolve("AGENTS.md"), "x".repeat(50_000));
        configure(root, "conventions.instruction-file=CLAUDE.md\nconventions.instruction-max-chars=100\n");

        Result result = Fixtures.run(new ScriptCheckConventions(), root, commands, false);

        assertEquals(ExitCodes.FAILURE, result.exit(), result.stdout());
        assertTrue(result.stdout().contains("CLAUDE.md") && result.stdout().contains("101 chars (max 100)"), result.stdout());
        assertFalse(result.stdout().contains("AGENTS.md"), "only the configured file is checked: " + result.stdout());
    }

    @Test
    void aDefaultInstructionFileThatDoesNotExistIsSkippedButAConfiguredOneMustExist(@TempDir Path root) throws Exception {
        List<CommandDescriptor> commands = List.of(good("aa-bb", "x — use when y"));
        Fixtures.write(root.resolve("scripts/INDEX.md"), CatalogIndex.markdown(root, commands));

        Result skipped = Fixtures.run(new ScriptCheckConventions(), root, commands, false);
        assertEquals(ExitCodes.OK, skipped.exit(), skipped.stdout());
        assertFalse(skipped.stdout().contains("AGENTS.md"), skipped.stdout());

        configure(root, "conventions.instruction-file=RULES.md\n");
        Fixtures.write(root.resolve("scripts/INDEX.md"), CatalogIndex.markdown(root, commands));
        Result missing = Fixtures.run(new ScriptCheckConventions(), root, commands, false);
        assertEquals(ExitCodes.FAILURE, missing.exit(), missing.stdout());
        assertTrue(missing.stdout().contains("RULES.md") && missing.stdout().contains("does not exist"), missing.stdout());
    }

    @Test
    void aCapOfZeroTurnsTheInstructionFileSizeCheckOff(@TempDir Path root) throws Exception {
        List<CommandDescriptor> commands = List.of(good("aa-bb", "x — use when y"));
        cleanRepo(root, commands);
        Fixtures.write(root.resolve("AGENTS.md"), "x".repeat(20_000));
        configure(root, "conventions.instruction-max-chars=0\n");

        assertEquals(ExitCodes.OK, Fixtures.run(new ScriptCheckConventions(), root, commands, false).exit());
    }

    @Test
    void thePurposeLengthLimitIsConfigurable(@TempDir Path root) throws Exception {
        List<CommandDescriptor> commands = List.of(good("aa-bb", "x".repeat(60) + " — use when y"));
        cleanRepo(root, commands);
        assertEquals(ExitCodes.OK, Fixtures.run(new ScriptCheckConventions(), root, commands, false).exit());

        configure(root, "conventions.purpose-max=50\n");
        Result result = Fixtures.run(new ScriptCheckConventions(), root, commands, false);

        assertEquals(ExitCodes.FAILURE, result.exit(), result.stdout());
        assertTrue(result.stdout().contains("(max 50)"), result.stdout());
    }

    @Test
    void aLimitThatIsNotANumberIsAUsageErrorNotADefault(@TempDir Path root) throws Exception {
        List<CommandDescriptor> commands = List.of(good("aa-bb", "x — use when y"));
        cleanRepo(root, commands);
        configure(root, "conventions.purpose-max=lots\n");

        Result result = Fixtures.run(new ScriptCheckConventions(), root, commands, false);

        assertEquals(ExitCodes.USAGE, result.exit());
        assertTrue(result.stderr().contains("conventions.purpose-max is not a number: lots"), result.stderr());
    }
}
