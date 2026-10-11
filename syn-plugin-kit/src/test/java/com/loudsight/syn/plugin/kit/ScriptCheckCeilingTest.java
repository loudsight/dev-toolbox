package com.loudsight.syn.plugin.kit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.loudsight.scriptkit.Cmd;
import com.loudsight.syn.plugin.kit.Fixtures.Result;
import com.loudsight.syn.spi.ExitCodes;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScriptCheckCeilingTest {

    @BeforeAll
    static void needsGit() {
        assumeTrue(Cmd.of("sh", "-c", "command -v git").discardErr().capture().ok(), "git is needed");
    }

    private static final String MANIFEST = "scripts/legacy-scripts.tsv";

    /** The settings a repo with a legacy-scripts manifest would have; the tests that do not say otherwise run under them. */
    private static void configure(Path root) throws IOException {
        Fixtures.write(root.resolve(".syn/config.properties"), "ceiling.manifest=" + MANIFEST + "\nceiling.default-category=migrate-w6\n");
    }

    private static void shell(Path root, String path, int lines) throws IOException {
        Fixtures.write(root.resolve(path), "#!/usr/bin/env bash\n" + "echo x\n".repeat(lines - 1));
    }

    @Test
    void theCeilingIsBaselinedThenHoldsThenFailsOnGrowthAndOnANewScript(@TempDir Path root) throws Exception {
        configure(root);
        shell(root, "scripts/a.sh", 5);
        Fixtures.gitInit(root);

        assertEquals(ExitCodes.OK, Fixtures.run(new ScriptCheckCeiling(), root, "--baseline").exit());
        assertTrue(Files.readString(root.resolve(MANIFEST), StandardCharsets.UTF_8).contains("scripts/a.sh\t5\tmigrate-w6"));
        assertEquals(ExitCodes.OK, Fixtures.run(new ScriptCheckCeiling(), root).exit());

        shell(root, "scripts/b.sh", 2);
        Fixtures.gitInit(root);
        Result grown = Fixtures.run(new ScriptCheckCeiling(), root);
        assertEquals(ExitCodes.FAILURE, grown.exit(), grown.stdout());
        assertTrue(grown.stdout().contains("new script scripts/b.sh"), grown.stdout());
        assertTrue(grown.stdout().contains("shell lines 7 > mark 5"), grown.stdout());
    }

    @Test
    void raiseRecordsTheReasonAndLowerTakesTheCredit(@TempDir Path root) throws Exception {
        configure(root);
        shell(root, "scripts/a.sh", 5);
        Fixtures.gitInit(root);
        Fixtures.run(new ScriptCheckCeiling(), root, "--baseline");
        shell(root, "scripts/b.sh", 2);
        Fixtures.gitInit(root);

        assertEquals(ExitCodes.OK, Fixtures.run(new ScriptCheckCeiling(), root, "--raise", "a launcher\nthat must stay shell").exit());
        String manifest = Files.readString(root.resolve(MANIFEST), StandardCharsets.UTF_8);
        assertTrue(manifest.contains("RAISED to 2 files, 7 lines, 0 inline: a launcher that must stay shell"), manifest);
        assertEquals(ExitCodes.OK, Fixtures.run(new ScriptCheckCeiling(), root).exit());

        Files.delete(root.resolve("scripts/b.sh"));
        Fixtures.gitInit(root);
        assertEquals(ExitCodes.FAILURE, Fixtures.run(new ScriptCheckCeiling(), root).exit(), "a listed script is gone");
        assertEquals(ExitCodes.OK, Fixtures.run(new ScriptCheckCeiling(), root, "--lower").exit());
        assertTrue(Files.readString(root.resolve(MANIFEST), StandardCharsets.UTF_8).contains("lowered to 1 files, 5 lines"));
        assertEquals(ExitCodes.OK, Fixtures.run(new ScriptCheckCeiling(), root).exit());
    }

    @Test
    void lowerRefusesToGoUp(@TempDir Path root) throws Exception {
        configure(root);
        shell(root, "scripts/a.sh", 5);
        Fixtures.gitInit(root);
        Fixtures.run(new ScriptCheckCeiling(), root, "--baseline");
        shell(root, "scripts/a.sh", 9);
        Fixtures.gitInit(root);
        String before = Files.readString(root.resolve(MANIFEST), StandardCharsets.UTF_8);

        Result result = Fixtures.run(new ScriptCheckCeiling(), root, "--lower");

        assertEquals(ExitCodes.FAILURE, result.exit());
        assertTrue(result.stdout().contains("nothing written"), result.stdout());
        assertEquals(before, Files.readString(root.resolve(MANIFEST), StandardCharsets.UTF_8));
    }

    @Test
    void inlineInterpreterCallsAreCounted(@TempDir Path root) throws Exception {
        configure(root);
        Fixtures.write(root.resolve("scripts/a.sh"), "#!/usr/bin/env bash\npython3 -c 'print(1)'\n");
        Fixtures.gitInit(root);

        Fixtures.run(new ScriptCheckCeiling(), root, "--baseline");

        assertTrue(Files.readString(root.resolve(MANIFEST), StandardCharsets.UTF_8).contains("inline-interpreter-calls\t1"));
    }

    @Test
    void theCeilingRejectsConflictingModesAndABlankReason(@TempDir Path root) throws Exception {
        assertEquals(ExitCodes.USAGE, Fixtures.run(new ScriptCheckCeiling(), root, "--lower", "--raise", "x").exit());
        assertEquals(ExitCodes.USAGE, Fixtures.run(new ScriptCheckCeiling(), root, "--raise", " ").exit());
    }


    @Test
    void withoutConfigurationTheManifestIsUnderSynAndTheHeaderNamesNoRepo(@TempDir Path root) throws Exception {
        shell(root, "tools/a.sh", 5);
        Fixtures.gitInit(root);

        assertEquals(ExitCodes.OK, Fixtures.run(new ScriptCheckCeiling(), root, "--baseline").exit());

        String manifest = Files.readString(root.resolve(".syn/script-ceiling.tsv"), StandardCharsets.UTF_8);
        assertTrue(manifest.contains("tools/a.sh\t5\tunclassified"), manifest);
        assertFalse(manifest.contains("Why and how"), manifest);
        assertFalse(manifest.contains("category:"), manifest);
        assertFalse(Files.exists(root.resolve(MANIFEST)));
    }

    @Test
    void aCategoriesFileLabelsRowsFirstMatchWinsAndTheRestGetTheDefault(@TempDir Path root) throws Exception {
        Fixtures.write(root.resolve(".syn/categories.tsv"), "# why\n\n^scripts/git-hooks/\texempt-hook\n^scripts/\tmigrate-w2\n");
        Fixtures.write(root.resolve(".syn/config.properties"), "ceiling.manifest=" + MANIFEST + "\nceiling.categories-file=.syn/categories.tsv\n"
                + "ceiling.default-category=later\nceiling.docs=docs/scripts.md\n");
        shell(root, "scripts/git-hooks/pre-commit", 3);
        shell(root, "scripts/run-it.sh", 4);
        shell(root, "elsewhere/x.sh", 2);
        Fixtures.gitInit(root);

        Fixtures.run(new ScriptCheckCeiling(), root, "--baseline");

        String manifest = Files.readString(root.resolve(MANIFEST), StandardCharsets.UTF_8);
        assertTrue(manifest.contains("scripts/git-hooks/pre-commit\t3\texempt-hook"), manifest);
        assertTrue(manifest.contains("scripts/run-it.sh\t4\tmigrate-w2"), manifest);
        assertTrue(manifest.contains("elsewhere/x.sh\t2\tlater"), manifest);
        assertTrue(manifest.contains("Why and how: docs/scripts.md"), manifest);
        assertTrue(manifest.contains("# category: exempt-*"), manifest);
    }

    @Test
    void aMalformedCategoriesLineOrABadRegexIsAUsageErrorAndWritesNothing(@TempDir Path root) throws Exception {
        shell(root, "scripts/a.sh", 3);
        Fixtures.gitInit(root);
        Fixtures.write(root.resolve(".syn/config.properties"), "ceiling.manifest=" + MANIFEST + "\nceiling.categories-file=.syn/c.tsv\n");
        Fixtures.write(root.resolve(".syn/c.tsv"), "no-tab-here exempt\n");

        Result malformed = Fixtures.run(new ScriptCheckCeiling(), root, "--baseline");
        assertEquals(ExitCodes.USAGE, malformed.exit());
        assertTrue(malformed.stderr().contains("expected regex<TAB>category"), malformed.stderr());

        Fixtures.write(root.resolve(".syn/config.properties"), "ceiling.manifest=" + MANIFEST + "\nceiling.exclude=([\n");
        assertEquals(ExitCodes.USAGE, Fixtures.run(new ScriptCheckCeiling(), root, "--baseline").exit());
        assertFalse(Files.exists(root.resolve(MANIFEST)));
    }

    @Test
    void excludedPathsAreNotCounted(@TempDir Path root) throws Exception {
        Fixtures.write(root.resolve(".syn/config.properties"), "ceiling.manifest=" + MANIFEST + "\nceiling.exclude=^\\\\.ignored/|^vendor/\n");
        shell(root, "scripts/a.sh", 5);
        shell(root, ".ignored/scratch.sh", 50);
        shell(root, "vendor/lib.sh", 70);
        Fixtures.gitInit(root);

        Fixtures.run(new ScriptCheckCeiling(), root, "--baseline");

        String manifest = Files.readString(root.resolve(MANIFEST), StandardCharsets.UTF_8);
        assertTrue(manifest.contains("scripts/a.sh"), manifest);
        assertFalse(manifest.contains("scratch.sh") || manifest.contains("lib.sh"), manifest);
    }
}
