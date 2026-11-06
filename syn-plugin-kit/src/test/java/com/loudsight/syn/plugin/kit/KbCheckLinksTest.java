package com.loudsight.syn.plugin.kit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.loudsight.scriptkit.Cmd;
import com.loudsight.syn.plugin.kit.Fixtures.Result;
import com.loudsight.syn.spi.ExitCodes;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KbCheckLinksTest {

    @BeforeAll
    static void needsGitAndGrep() {
        assumeTrue(Cmd.of("sh", "-c", "command -v git && command -v grep").discardErr().capture().ok(), "git and grep are needed");
    }

    @Test
    void kbCheckLinksReportsADeadKbPathAndADeadRelativeLink(@TempDir Path root) throws Exception {
        Fixtures.write(root.resolve("kb/README.md"), "See kb/plans/gone.md and [other](missing.md) and [web](https://x.y) and [here](#top).\n");
        Fixtures.gitInit(root);

        Result result = Fixtures.run(new KbCheckLinks(), root);

        assertEquals(ExitCodes.FAILURE, result.exit(), result.stdout());
        assertTrue(result.stdout().contains("kb/plans/gone.md"), result.stdout());
        assertTrue(result.stdout().contains("missing.md"), result.stdout());
        assertFalse(result.stdout().contains("https://x.y"), result.stdout());
        assertTrue(result.stdout().contains("Fixing:"), result.stdout());
    }

    @Test
    void kbCheckLinksQuietLeavesOutTheAdvice(@TempDir Path root) throws Exception {
        Fixtures.write(root.resolve("kb/README.md"), "See kb/plans/gone.md\n");
        Fixtures.gitInit(root);

        Result result = Fixtures.run(new KbCheckLinks(), root, "--quiet");

        assertEquals(ExitCodes.FAILURE, result.exit());
        assertFalse(result.stdout().contains("Fixing:"), result.stdout());
    }

    @Test
    void kbCheckLinksPassesWhenEverythingResolves(@TempDir Path root) throws Exception {
        Fixtures.write(root.resolve("kb/plans/real.md"), "x\n");
        Fixtures.write(root.resolve("kb/README.md"), "See kb/plans/real.md and [it](plans/real.md#section).\n");
        Fixtures.gitInit(root);

        Result result = Fixtures.run(new KbCheckLinks(), root);

        assertEquals(ExitCodes.OK, result.exit(), result.stdout());
        assertTrue(result.stdout().contains("OK - no dead kb references."), result.stdout());
    }

    @Test
    void kbCheckLinksFindsADanglingTrackedSymlink(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("kb"));
        Files.createSymbolicLink(root.resolve("link"), Path.of("kb/nowhere"));
        Fixtures.gitInit(root);

        Result result = Fixtures.run(new KbCheckLinks(), root);

        assertEquals(ExitCodes.FAILURE, result.exit(), result.stdout());
        assertTrue(result.stdout().contains("-> kb/nowhere"), result.stdout());
    }

    private static void configure(Path root, String properties) throws IOException {
        Fixtures.write(root.resolve(".syn/config.properties"), properties);
    }

    @Test
    void aConfiguredKbDirIsTheOneChecked(@TempDir Path root) throws Exception {
        configure(root, "kb.dir=notes\n");
        Fixtures.write(root.resolve("notes/README.md"), "See notes/plans/gone.md and kb/plans/ignored.md\n");
        Fixtures.gitInit(root);

        Result result = Fixtures.run(new KbCheckLinks(), root);

        assertEquals(ExitCodes.FAILURE, result.exit(), result.stdout());
        assertTrue(result.stdout().contains("notes/plans/gone.md"), result.stdout());
        assertFalse(result.stdout().contains("kb/plans/ignored.md"), "the default dir is not checked once another is configured: " + result.stdout());
    }

    @Test
    void aKbDirWithRegexCharactersIsMatchedLiterally(@TempDir Path root) throws Exception {
        configure(root, "kb.dir=docs.v2\n");
        Fixtures.write(root.resolve("docs.v2/README.md"), "See docs.v2/plans/gone.md and docsXv2/plans/other.md\n");
        Fixtures.gitInit(root);

        Result result = Fixtures.run(new KbCheckLinks(), root);

        assertTrue(result.stdout().contains("docs.v2/plans/gone.md"), result.stdout());
        assertFalse(result.stdout().contains("docsXv2"), result.stdout());
    }

    @Test
    void retiredRootsAreOnlyReportedWhenConfigured(@TempDir Path root) throws Exception {
        Fixtures.write(root.resolve("src/A.java"), "// see notebook/plans/x.md\n");
        Fixtures.write(root.resolve("kb/plans/x.md"), "x\n");
        Fixtures.gitInit(root);

        Result unconfigured = Fixtures.run(new KbCheckLinks(), root);
        assertEquals(ExitCodes.OK, unconfigured.exit(), unconfigured.stdout());
        assertFalse(unconfigured.stdout().contains("notebook"), unconfigured.stdout());

        configure(root, "kb.retired-roots=docs, notebook\n");
        Result configured = Fixtures.run(new KbCheckLinks(), root);
        assertEquals(ExitCodes.FAILURE, configured.exit(), configured.stdout());
        assertTrue(configured.stdout().contains("notebook/plans/x.md"), configured.stdout());
        assertTrue(configured.stdout().contains("`docs/...` or `notebook/...` -> `kb/...`"), configured.stdout());
    }

    @Test
    void subdirsDecideWhichRetiredPathsCount(@TempDir Path root) throws Exception {
        configure(root, "kb.retired-roots=notebook\nkb.subdirs=wip\n");
        Fixtures.write(root.resolve("src/A.java"), "// notebook/wip/a.md and notebook/plans/b.md\n");
        Fixtures.gitInit(root);

        Result result = Fixtures.run(new KbCheckLinks(), root);

        assertTrue(result.stdout().contains("notebook/wip/a.md"), result.stdout());
        assertFalse(result.stdout().contains("notebook/plans/b.md"), result.stdout());
    }

    @Test
    void excludedPathsAreNotReported(@TempDir Path root) throws Exception {
        Fixtures.write(root.resolve("kb/README.md"), "See kb/plans/gone.md\n");
        Fixtures.write(root.resolve("kb/staging/old.md"), "See kb/plans/also-gone.md\n");
        Fixtures.gitInit(root);
        assertTrue(Fixtures.run(new KbCheckLinks(), root).stdout().contains("kb/staging/old.md"));

        configure(root, "kb.exclude=kb/staging/\n");
        Result result = Fixtures.run(new KbCheckLinks(), root);

        assertTrue(result.stdout().contains("kb/plans/gone.md"), result.stdout());
        assertFalse(result.stdout().contains("kb/staging/old.md"), result.stdout());
    }

    @Test
    void bracketsThatAreNotLinksAreNotReported(@TempDir Path root) throws Exception {
        Fixtures.write(root.resolve("kb/plans/real.md"), "x\n");
        Fixtures.write(root.resolve("kb/README.md"),
                "- [x] then text (done)\na] b (c)\n]x( y )\nC:\\dir\\file] (z)\nreal [link](plans/real.md).\n");
        Fixtures.gitInit(root);

        Result result = Fixtures.run(new KbCheckLinks(), root);

        assertEquals(ExitCodes.OK, result.exit(), result.stdout());
    }

    @Test
    void aRealDeadLinkIsReportedWithItsLineNumber(@TempDir Path root) throws Exception {
        Fixtures.write(root.resolve("kb/sub/a.md"), "ok\n[x] y\nsee [d](../gone.md#h) and [e](ok.md)\n");
        Fixtures.gitInit(root);

        Result result = Fixtures.run(new KbCheckLinks(), root);

        assertEquals(ExitCodes.FAILURE, result.exit(), result.stdout());
        assertTrue(result.stdout().contains("kb/sub/a.md:3"), result.stdout());
        assertTrue(result.stdout().contains("../gone.md"), result.stdout());
    }

    @Test
    void aSymlinkedMarkdownFileIsScannedOnlyAtItsTarget(@TempDir Path root) throws Exception {
        Fixtures.write(root.resolve("kb/sub/SKILL.md"), "[ok](../sub/SKILL.md)\n");
        try {
            Files.createSymbolicLink(root.resolve("kb/alias.md"), Path.of("sub/SKILL.md"));
        } catch (UnsupportedOperationException | IOException noSymlinks) {
            assumeTrue(false, "symlinks are not available");
        }
        Fixtures.write(root.resolve("kb/sub/ok.md"), "[ok](ok.md)\n");
        Fixtures.gitInit(root);

        Result result = Fixtures.run(new KbCheckLinks(), root);

        assertEquals(ExitCodes.OK, result.exit(), result.stdout());
    }

    /** The reported Windows failure: the backslashes of the old grep pattern were lost, and the stripped pattern matches any "]" + text. */
    @Test
    void theLinkPatternNeedsItsBackslashesAndTheCheckerDoesNotShellOutForIt() {
        String degraded = KbCheckLinks.MARKDOWN_LINK.pattern().replace("\\", "");
        for (String notALink : new String[] {"[x] then text (done)", "a] b (c)"}) {
            assertFalse(KbCheckLinks.MARKDOWN_LINK.matcher(notALink).find(), notALink);
            assertTrue(java.util.regex.Pattern.compile(degraded).matcher(notALink).find(), "degraded pattern matches " + notALink);
        }
        assertTrue(KbCheckLinks.MARKDOWN_LINK.matcher("[a](b.md)").find());
    }

    @Test
    void configuredAdviceIsAppendedUnlessQuiet(@TempDir Path root) throws Exception {
        configure(root, "kb.advice=do not link into kb/staging\n");
        Fixtures.write(root.resolve("kb/README.md"), "See kb/plans/gone.md\n");
        Fixtures.gitInit(root);

        assertTrue(Fixtures.run(new KbCheckLinks(), root).stdout().contains("- do not link into kb/staging"));
        assertFalse(Fixtures.run(new KbCheckLinks(), root, "--quiet").stdout().contains("do not link into"));
    }
}
