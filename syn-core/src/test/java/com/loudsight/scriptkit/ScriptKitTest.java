package com.loudsight.scriptkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScriptKitTest {

    @Test
    void quotesOnlyWhatNeedsQuoting() {
        assertEquals("docker", Shell.quote("docker"));
        assertEquals("'a b'", Shell.quote("a b"));
        assertEquals("'it'\"'\"'s'", Shell.quote("it's"));
        assertEquals("''", Shell.quote(""));
    }

    @Test
    void sshWrapsTheRemoteCommandInBashIc() {
        System.setProperty(DeployHost.HOST_PROPERTY, "tester@host.invalid");
        try {
            assertEquals(List.of("ssh", "tester@host.invalid", "bash", "-ic", "'docker ps --format '\"'\"'{{.Names}}'\"'\"''"),
                    DeployHost.docker("ps", "--format", "{{.Names}}").command());
            assertEquals("-A", DeployHost.sshWithAgent("true").command().get(1));
        } finally {
            System.clearProperty(DeployHost.HOST_PROPERTY);
        }
    }

    @Test
    void refusesDockerCompose() {
        assertThrows(IllegalArgumentException.class, () -> DeployHost.docker("compose", "ps"));
    }

    @Test
    void capturesStdoutAndExitCode() {
        Cmd.Result result = Cmd.of("sh", "-c", "echo one; echo two; exit 3").capture();
        assertEquals(3, result.exitCode());
        assertEquals(List.of("one", "two"), result.lines());
        assertThrows(Cmd.CmdException.class, result::orThrow);
        assertEquals("hi", Cmd.of("cat").stdin("hi\n").capture().orThrow());
    }

    @Test
    void timesOut() {
        assertThrows(Cmd.CmdException.class, () -> Cmd.of("sleep", "5").timeout(Duration.ofMillis(200)).capture());
    }

    @Test
    void readsHeadersInEitherCommentStyle() {
        Map<String, String> header = ScriptHeader.parse(List.of(
                "#!/usr/bin/env bash",
                "# @purpose: do a thing — use when a thing is needed",
                "// @tier: read-only",
                "# @purpose: ignored second value"));
        assertEquals(Map.of("purpose", "do a thing — use when a thing is needed", "tier", "read-only"), header);
    }

    @Test
    void helpIsOnlyTheFirstArgument() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);
        assertFalse(ScriptHeader.handleHelp(new String[] {"run", "--help"}, out));
        assertTrue(ScriptHeader.handleHelp(new String[] {"--help"}, out));
    }

    @Test
    void checksCountFailures() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Checks checks = new Checks(new PrintStream(bytes, true, StandardCharsets.UTF_8));
        checks.check(true, "fine", "broken");
        checks.check(false, "fine", "broken");
        assertEquals(1, checks.failures());
        assertEquals(1, checks.finish("Test"));
        assertTrue(bytes.toString(StandardCharsets.UTF_8).contains("FAIL     broken"));
    }

    @Test
    void aScriptIsNamedByItsFile() {
        assertEquals("deploy-run.sh", ScriptLibrary.commandName("scripts/deploy-run.sh"));
        assertEquals("plain.sh", ScriptLibrary.commandName("plain.sh"));
    }

    @Test
    void theLibraryHoldsOnlyShellPythonAndMjsScriptsDirectlyUnderScripts(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("scripts/java"));
        Files.createDirectories(root.resolve("build/automation/auto-agent/src/main/scripts"));
        Files.writeString(root.resolve("scripts/myEnv.sh"), "# exempt\n");
        Files.createDirectories(root.resolve(".syn"));
        Files.writeString(root.resolve(".syn/config.properties"), "catalog.script-exempt=scripts/myEnv.sh\n");
        Files.writeString(root.resolve("scripts/b-run-thing.sh"), "# @tier: read-only\n");
        Files.writeString(root.resolve("scripts/a-do-it.py"), "# @tier: read-only\n");
        Files.writeString(root.resolve("scripts/Thing.java"), "// not a library script any more\n");
        Files.writeString(root.resolve("scripts/java/Old.java"), "// a removed jx directory\n");
        Files.writeString(root.resolve("scripts/java/old.sh"), "# a removed jx directory\n");
        Files.writeString(root.resolve("build/automation/auto-agent/src/main/scripts/c-go.sh"), "# a removed directory\n");
        Files.writeString(root.resolve("scripts/notes.md"), "not a script");

        assertEquals(List.of("scripts/b-run-thing.sh", "scripts/a-do-it.py"), ScriptLibrary.load(root).files());
        assertEquals(List.of("scripts/a-do-it.py", "scripts/b-run-thing.sh"), ScriptLibrary.load(root).catalogFiles());
    }

    @Test
    void mergesStderrAndWritesToFile(@TempDir Path dir) {
        assertEquals(List.of("out", "err"), Cmd.of("sh", "-c", "echo out; echo err >&2").mergeErr().capture().lines());
        Path file = dir.resolve("out.txt");
        assertEquals(0, Cmd.of("echo", "to file").toFile(file));
        assertTrue(file.toFile().length() > 0);
    }

    @Test
    void readsWhatAnEnvFileExports(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("x.env");
        Files.writeString(file, "# comment\nexport TOKEN='a b=c'\nexport PROVIDER=fs\nPLAIN=$PROVIDER-x\n");
        assertEquals(Map.of("TOKEN", "a b=c", "PROVIDER", "fs", "PLAIN", "fs-x"), Env.sourced(file));
    }

    @Test
    void findsTheRepoRootAbove(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("AGENTS.md"), "");
        Files.createDirectory(dir.resolve(".git"));
        Path nested = Files.createDirectories(dir.resolve("a/b"));
        assertEquals(dir, Env.findRoot(nested));
    }

    @Test
    void aSynDirectoryMarksTheRootEvenWithoutAgentsMdAndBeatsANearerSubmodule(@TempDir Path dir) throws Exception {
        Files.createDirectory(dir.resolve(".git"));
        Files.createDirectory(dir.resolve(".syn"));
        Path submodule = Files.createDirectories(dir.resolve("sub"));
        Files.writeString(submodule.resolve(".git"), "gitdir: ../.git/modules/sub");
        assertEquals(dir, Env.findRoot(Files.createDirectories(submodule.resolve("src"))));
    }

    @Test
    void aPlainGitRepoIsTheRootWhenNothingBetterIsAbove(@TempDir Path dir) throws Exception {
        Files.createDirectory(dir.resolve(".git"));
        assertEquals(dir, Env.findRoot(Files.createDirectories(dir.resolve("a/b"))));
    }

    @Test
    void outsideAnyRepoIsAnError(@TempDir Path dir) {
        assertThrows(IllegalStateException.class, () -> Env.findRoot(dir));
    }
}
