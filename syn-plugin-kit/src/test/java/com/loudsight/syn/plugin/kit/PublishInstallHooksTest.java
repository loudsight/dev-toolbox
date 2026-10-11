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
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PublishInstallHooksTest {

    @BeforeAll
    static void needsGit() {
        assumeTrue(Cmd.of("sh", "-c", "command -v git").discardErr().capture().ok(), "git is needed");
    }

    @Test
    void pointsAMarkedSubmoduleAtTheHooksAndLeavesAnUnmarkedOneAlone(@TempDir Path root) throws Exception {
        Path marked = repo(root, "pub");
        Path unmarked = repo(root, "priv");
        Fixtures.write(marked.resolve(".public-repo"), "");
        Fixtures.write(root.resolve(".gitmodules"), "[submodule \"pub\"]\n\tpath = pub\n\turl = x\n[submodule \"priv\"]\n\tpath = priv\n\turl = y\n");

        Result result = Fixtures.run(new PublishInstallHooks(), root);

        assertEquals(ExitCodes.OK, result.exit(), result.stdout());
        assertEquals(root.resolve(".syn/hooks/public").toString(), hooksPath(marked));
        assertEquals("", hooksPath(unmarked), "an unmarked repo is not touched");
    }

    @Test
    void markCreatesTheMarkerAndAnAllowlistAndIsIdempotent(@TempDir Path root) throws Exception {
        Path target = repo(root, "pub");

        Result first = Fixtures.run(new PublishInstallHooks(), root, "--mark", target.toString());
        Files.writeString(target.resolve(".public-allowlist"), "kept\n", StandardCharsets.UTF_8);
        Result second = Fixtures.run(new PublishInstallHooks(), root, "--mark", target.toString());

        assertEquals(ExitCodes.OK, first.exit(), first.stdout());
        assertEquals(ExitCodes.OK, second.exit(), second.stdout());
        assertTrue(Files.exists(target.resolve(".public-repo")));
        assertEquals("kept\n", Files.readString(target.resolve(".public-allowlist")), "an existing allowlist is never overwritten");
    }

    @Test
    void anUnmarkedExplicitRepoIsGuardedButWarned(@TempDir Path root) throws Exception {
        Path target = repo(root, "pub");

        Result result = Fixtures.run(new PublishInstallHooks(), root, target.toString());

        assertEquals(ExitCodes.OK, result.exit(), result.stdout());
        assertTrue(result.stdout().contains("no .public-repo yet"), result.stdout());
        assertEquals(root.resolve(".syn/hooks/public").toString(), hooksPath(target));
    }

    @Test
    void hooksAreWrittenExecutableAndRewrittenWhenTheyDrift(@TempDir Path root) throws Exception {
        Path target = repo(root, "pub");
        Path hook = Fixtures.write(root.resolve(".syn/hooks/public/pre-commit"), "#!/bin/sh\nexit 0\n");
        Files.setPosixFilePermissions(hook, PosixFilePermissions.fromString("rw-------"));

        Result first = Fixtures.run(new PublishInstallHooks(), root, target.toString());
        Result second = Fixtures.run(new PublishInstallHooks(), root, target.toString());

        assertEquals(ExitCodes.OK, first.exit(), first.stdout());
        assertTrue(first.stdout().contains("wrote the pre-commit hook") && first.stdout().contains("wrote the pre-push hook"), first.stdout());
        assertTrue(Files.isExecutable(hook));
        assertTrue(Files.readString(hook).contains("publish-check-private --staged"), "the shipped text replaced the drifted one");
        assertTrue(Files.readString(root.resolve(".syn/hooks/public/pre-push")).contains("publish-check-private --push-stdin --require-scanner"));
        assertTrue(second.stdout().contains("pre-commit hook is up to date"), second.stdout());
    }

    @Test
    void aConfiguredHooksDirAndLauncherAreUsedAndTheLauncherIsBakedInRelativeToTheHooks(@TempDir Path root) throws Exception {
        Fixtures.write(root.resolve(".syn/config.properties"), "publish.hooks-dir=scripts/git-hooks/public\npublish.launcher=scripts/syn\n");
        Path target = repo(root, "pub");

        Result result = Fixtures.run(new PublishInstallHooks(), root, target.toString());

        assertEquals(ExitCodes.OK, result.exit(), result.stdout());
        assertEquals(root.resolve("scripts/git-hooks/public").toString(), hooksPath(target));
        assertTrue(Files.readString(root.resolve("scripts/git-hooks/public/pre-commit")).contains("for candidate in \"$here/../../syn\"; do"));
    }

    @Test
    void aHookRunsTheCommandWithItsModeAndRefusesWhenSynCannotBeFound(@TempDir Path root) throws Exception {
        Path target = repo(root, "pub");
        Fixtures.run(new PublishInstallHooks(), root, target.toString());
        Path hook = root.resolve(".syn/hooks/public/pre-commit");
        Path fake = Fixtures.write(root.resolve("fake-syn"), "#!/bin/sh\necho \"$@\" > \"$(dirname \"$0\")/args\"\n");
        Files.setPosixFilePermissions(fake, PosixFilePermissions.fromString("rwx------"));

        Cmd.Result ran = Cmd.of("/bin/sh", hook.toString()).dir(target).env("SYN", fake.toString()).discardErr().capture();
        assertTrue(ran.ok(), ran.stdout());
        assertEquals("publish-check-private --staged --repo " + target.toRealPath(), Files.readString(root.resolve("args")).strip());

        Cmd.Result refused = Cmd.of("/bin/sh", hook.toString()).dir(target).env("PATH", "/usr/bin:/bin").mergeErr().capture();
        assertEquals(1, refused.exitCode(), refused.stdout());
        assertTrue(refused.stdout().contains("syn not found; refusing the commit"), refused.stdout());
    }

    @Test
    void dryRunChangesNothing(@TempDir Path root) throws Exception {
        Path target = repo(root, "pub");

        Result result = Fixtures.run(new PublishInstallHooks(), root, java.util.List.of(), true, "--mark", target.toString());

        assertEquals(ExitCodes.OK, result.exit(), result.stdout());
        assertEquals("", hooksPath(target));
        assertFalse(Files.exists(target.resolve(".public-repo")));
        assertFalse(Files.exists(root.resolve(".syn/hooks")), "a dry run writes no hooks");
    }

    @Test
    void ciWritesTheShippedWorkflowAndLeavesItAloneWhenCurrent(@TempDir Path root) throws Exception {
        Path target = repo(root, "pub");
        Path workflow = target.resolve(".github/workflows/public-guard.yml");

        Result first = Fixtures.run(new PublishInstallHooks(), root, "--ci", target.toString());
        long written = Files.getLastModifiedTime(workflow).toMillis();
        Result second = Fixtures.run(new PublishInstallHooks(), root, "--ci", target.toString());

        assertEquals(ExitCodes.OK, first.exit(), first.stdout());
        assertEquals(PublishInstallHooks.workflow(), Files.readString(workflow));
        assertTrue(first.stdout().contains("wrote .github/workflows/public-guard.yml"), first.stdout());
        assertTrue(second.stdout().contains("is up to date"), second.stdout());
        assertEquals(written, Files.getLastModifiedTime(workflow).toMillis(), "an up-to-date workflow is not rewritten");
    }

    @Test
    void ciRewritesAWorkflowThatDriftedFromTheShippedOne(@TempDir Path root) throws Exception {
        Path target = repo(root, "pub");
        Path workflow = Fixtures.write(target.resolve(".github/workflows/public-guard.yml"), "name: edited by hand\n");

        Result result = Fixtures.run(new PublishInstallHooks(), root, "--ci", target.toString());

        assertEquals(ExitCodes.OK, result.exit(), result.stdout());
        assertEquals(PublishInstallHooks.workflow(), Files.readString(workflow), "the shipped text is the canonical one");
    }

    @Test
    void ciUnderDryRunWritesNothing(@TempDir Path root) throws Exception {
        Path target = repo(root, "pub");

        Result result = Fixtures.run(new PublishInstallHooks(), root, java.util.List.of(), true, "--ci", target.toString());

        assertEquals(ExitCodes.OK, result.exit(), result.stdout());
        assertTrue(result.stdout().contains("would write .github/workflows/public-guard.yml"), result.stdout());
        assertFalse(Files.exists(target.resolve(".github")));
    }

    private static Path repo(Path root, String name) throws IOException {
        Path dir = Files.createDirectories(root.resolve(name));
        Cmd.of("git", "init", "-q").dir(dir).discardErr().capture();
        return dir;
    }

    private static String hooksPath(Path repo) {
        return Cmd.of("git", "config", "--get", "core.hooksPath").dir(repo).discardErr().capture().stdout().strip();
    }
}
