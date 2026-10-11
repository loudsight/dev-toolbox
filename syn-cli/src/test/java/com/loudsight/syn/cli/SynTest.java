package com.loudsight.syn.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.loudsight.scriptkit.DeployHost;
import com.loudsight.syn.core.RemoteExec;
import com.loudsight.syn.spi.ExitCodes;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class SynTest {

    @TempDir
    Path temp;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    /** Stands in for the deploy host: records what would have been run there and what warning it would have shown. */
    private static final class FakeRemote implements Syn.Remote {

        final List<String> runs = new ArrayList<>();
        List<String> warning = List.of();
        List<String> warningBeforeSync = List.of();

        @Override
        public List<String> warning(Path repoRoot, String host, String checkout) {
            return warning;
        }

        @Override
        public List<String> warningBeforeSync(Path repoRoot) {
            return warningBeforeSync;
        }

        @Override
        public int run(RemoteExec.Request request, PrintStream out, PrintStream err) {
            runs.add(request.host() + ":" + request.checkout() + " " + String.join(" ", request.args())
                    + (request.sync() ? " [sync]" : "") + (request.detach() ? " [detach]" : ""));
            return 5;
        }
    }

    private final FakeRemote remote = new FakeRemote();

    @BeforeEach
    void clearRan() {
        FakeCommands.RAN.clear();
        System.setProperty(DeployHost.HOST_PROPERTY, "me@host.invalid");
        System.setProperty(DeployHost.CHECKOUT_PROPERTY, "/srv/checkout");
    }

    @AfterEach
    void clearHost() {
        System.clearProperty(DeployHost.HOST_PROPERTY);
        System.clearProperty(DeployHost.CHECKOUT_PROPERTY);
    }

    private int onRemote(boolean interactive, String... args) {
        return Syn.run(args, temp, temp.resolve("no-plugins"),
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8), interactive, remote);
    }

    private int syn(boolean interactive, String... args) {
        return Syn.run(args, temp, temp.resolve("no-plugins"),
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8), interactive);
    }

    private int syn(String... args) {
        return syn(false, args);
    }

    private String stdout() {
        return out.toString(StandardCharsets.UTF_8);
    }

    private String stderr() {
        return err.toString(StandardCharsets.UTF_8);
    }

    @Test
    void runsADiscoveredCommandWithItsArguments() {
        assertEquals(ExitCodes.OK, syn("test-echo", "hello", "world"));
        assertEquals("hello world" + System.lineSeparator(), stdout());
    }

    @Test
    void jsonWorksAfterTheSubcommand() {
        assertEquals(ExitCodes.OK, syn("test-echo", "a", "b", "--json"));
        JsonNode parsed = JsonMapper.builder().build().readTree(stdout());
        assertEquals("a", parsed.get("words").get(0).asString());
        assertEquals("b", parsed.get("words").get(1).asString());
    }

    @Test
    void jsonWorksBeforeTheSubcommand() {
        assertEquals(ExitCodes.OK, syn("--json", "test-echo", "a"));
        assertEquals("a", JsonMapper.builder().build().readTree(stdout()).get("words").get(0).asString());
    }

    @Test
    void dryRunReachesTheCommand() {
        assertEquals(ExitCodes.OK, syn("test-echo", "x", "--dry-run"));
        assertTrue(stdout().contains("(dry run)"), stdout());
    }

    @Test
    void remoteMutateRunsWithoutConfirm() {
        assertEquals(ExitCodes.OK, syn("test-remote"));
        assertEquals(List.of("test-remote"), FakeCommands.RAN);
    }

    @Test
    void remoteMutateStillAcceptsConfirm() {
        assertEquals(ExitCodes.OK, syn("test-remote", "--confirm"));
        assertEquals(List.of("test-remote"), FakeCommands.RAN);
    }

    @Test
    void irreversibleIsRefusedWithoutATerminalEvenWhenConfirmed() {
        assertEquals(ExitCodes.REFUSED, syn(false, "test-irreversible", "--confirm"));
        assertTrue(FakeCommands.RAN.isEmpty());
    }

    @Test
    void irreversibleAtATerminalNeedsConfirmThenRuns() {
        assertEquals(ExitCodes.CONFIRMATION_REQUIRED, syn(true, "test-irreversible"));
        assertTrue(FakeCommands.RAN.isEmpty());
        assertEquals(ExitCodes.OK, syn(true, "test-irreversible", "--confirm"));
        assertEquals(List.of("test-irreversible"), FakeCommands.RAN);
    }

    @Test
    void aCommandCanLowerItsTierForADryRunAndOnlyForThat() {
        // The preview runs without a terminal and without --confirm; the real run is refused exactly as before.
        assertEquals(ExitCodes.OK, syn(false, "test-preview", "--dry-run"));
        assertEquals(List.of("test-preview (dry run)"), FakeCommands.RAN);
        FakeCommands.RAN.clear();

        assertEquals(ExitCodes.REFUSED, syn(false, "test-preview", "--confirm"));
        assertEquals(ExitCodes.CONFIRMATION_REQUIRED, syn(true, "test-preview"));
        assertTrue(FakeCommands.RAN.isEmpty());
        assertEquals(ExitCodes.OK, syn(true, "test-preview", "--confirm"));
        assertEquals(List.of("test-preview"), FakeCommands.RAN);
    }

    @Test
    void aCommandThatDoesNotOverrideItKeepsItsTierUnderDryRun() {
        assertEquals(ExitCodes.REFUSED, syn(false, "test-irreversible", "--dry-run", "--confirm"));
        assertTrue(FakeCommands.RAN.isEmpty());
    }

    @Test
    void aFailingCommandIsExitOneWithItsMessageOnStderr() {
        assertEquals(ExitCodes.FAILURE, syn("test-boom"));
        assertTrue(stderr().contains("test-boom: the disk is on fire"), stderr());
        assertEquals("", stdout());
    }

    /** A script's System.exit becomes CommandExit: it must end the command with that code, quietly, from any depth. */
    @Test
    void commandExitFromANestedHelperIsTheExitCode() {
        assertEquals(7, syn("test-exit"));
        assertEquals("", stderr());
    }

    @Test
    void aCommandCanListEveryCommandTheHeadFound() {
        assertEquals(ExitCodes.OK, syn("test-list"));
        assertTrue(stdout().contains("catalog"), stdout());
        assertTrue(stdout().contains("test-echo"), stdout());
        assertTrue(stdout().contains("test-list"), stdout());
    }

    /** The catalog is the same whichever command asks for it: a tier that follows options is listed at its declared default. */
    @Test
    void theCatalogListsADeclaredTierNotTheOneTheRunningCommandHasConfigured() {
        assertEquals(ExitCodes.OK, syn("test-list", "--elevated"));

        assertTrue(stdout().contains("test-list=read-only"), stdout());
    }

    @Test
    void anUnknownCommandIsAUsageError() {
        assertEquals(ExitCodes.USAGE, syn("no-such-command"));
        assertTrue(stderr().contains("no-such-command"), stderr());
    }

    @Test
    void noArgumentsPrintsUsageAndIsAUsageError() {
        assertEquals(ExitCodes.USAGE, syn());
        assertTrue(stdout().contains("catalog"), stdout());
    }

    @Test
    void helpPrintsTheCommandsDescription() {
        assertEquals(ExitCodes.OK, syn("test-echo", "--help"));
        assertTrue(stdout().contains("Echo the words back"), stdout());
    }

    @Test
    void catalogListsEveryCommandWithTierAndPurpose() {
        assertEquals(ExitCodes.OK, syn("catalog"));
        assertTrue(stdout().contains("catalog"), stdout());
        assertTrue(stdout().matches("(?s).*test-echo\\s+read-only\\s+Echo the words back.*"), stdout());
        assertTrue(stdout().matches("(?s).*test-remote\\s+remote-mutate\\s+Pretend to change a remote host.*"), stdout());
    }

    @Test
    void catalogFiltersOnNamePurposeAndKeywords() {
        assertEquals(ExitCodes.OK, syn("catalog", "parrot"));
        assertTrue(stdout().contains("test-echo"), stdout());
        assertFalse(stdout().contains("test-remote"), stdout());
    }

    @Test
    void catalogJsonCarriesGroupAndOrigin() {
        assertEquals(ExitCodes.OK, syn("catalog", "test-echo", "--json"));
        JsonNode entry = JsonMapper.builder().build().readTree(stdout()).get(0);
        assertEquals("test-echo", entry.get("name").asString());
        assertEquals("test", entry.get("group").asString());
        assertEquals("read-only", entry.get("tier").asString());
        assertEquals("builtin", entry.get("origin").asString());
        assertEquals("repeat", entry.get("keywords").get(0).asString());
    }

    @Test
    void catalogMarkdownIsATable() {
        assertEquals(ExitCodes.OK, syn("catalog", "test-echo", "--markdown"));
        assertTrue(stdout().contains("| Command | Tier | Purpose | Keywords |"), stdout());
        assertTrue(stdout().contains("| `syn test-echo` | read-only | Echo the words back | repeat, parrot |"), stdout());
    }

    @Test
    void onRemoteRunsTheSameArgumentsMinusTheFlagOnTheHostAndReturnsItsExitCode() {
        assertEquals(5, onRemote(false, "test-echo", "hi", "--on-remote"));

        assertEquals(List.of("me@host.invalid:/srv/checkout test-echo hi"), remote.runs);
        assertEquals("", stdout(), "the command itself must not also run here");
        assertTrue(stderr().contains("test-echo on me@host.invalid:/srv/checkout"), stderr());
    }

    @Test
    void theBareFlagBeforeTheCommandDoesNotSwallowTheCommandName() {
        assertEquals(5, onRemote(false, "--on-remote", "test-echo", "hi"));

        assertEquals(List.of("me@host.invalid:/srv/checkout test-echo hi"), remote.runs);
    }

    @Test
    void theEqualsFormNamesTheHost() {
        assertEquals(5, onRemote(false, "test-echo", "--on-remote=you@other.invalid"));

        assertEquals(List.of("you@other.invalid:/srv/checkout test-echo"), remote.runs);
    }

    @Test
    void dryRunAndJsonArePassedThroughToTheHostUntouched() {
        onRemote(false, "test-echo", "x", "--dry-run", "--json", "--on-remote");

        assertEquals(List.of("me@host.invalid:/srv/checkout test-echo x --dry-run --json"), remote.runs);
    }

    @Test
    void withoutTheFlagNothingRunsOnTheHost() {
        assertEquals(ExitCodes.OK, onRemote(false, "test-echo", "hi"));

        assertTrue(remote.runs.isEmpty());
    }

    @Test
    void anIrreversibleCommandIsNeverRunOnTheHostEvenConfirmedAtATerminal() {
        assertEquals(ExitCodes.REFUSED, onRemote(true, "test-irreversible", "--confirm", "--on-remote"));

        assertTrue(remote.runs.isEmpty());
        assertTrue(FakeCommands.RAN.isEmpty());
        assertTrue(stderr().contains("--on-remote refused"), stderr());
    }

    @Test
    void aCommandThatRefusesTheHostIsRefusedWithItsReasonAndRunsNowhere() {
        assertEquals(ExitCodes.REFUSED, onRemote(false, "test-refuses", "--on-remote"));

        assertTrue(remote.runs.isEmpty());
        assertTrue(FakeCommands.RAN.isEmpty());
        assertTrue(stderr().contains("it only makes sense here"), stderr());
    }

    @Test
    void aLocalMutateCommandIsReportedAsTheRemoteMutateItBecomes() {
        onRemote(false, "test-local", "--on-remote");

        assertTrue(stderr().contains("treat it as remote-mutate"), stderr());
        assertEquals(1, remote.runs.size());
        assertTrue(FakeCommands.RAN.isEmpty());
    }

    @Test
    void theSyncWarningIsPrintedBeforeTheCommandRuns() {
        remote.warning = List.of("!! WARNING: the host differs");

        onRemote(false, "test-echo", "--on-remote");

        assertTrue(stderr().indexOf("!! WARNING: the host differs") < stderr().indexOf("test-echo on "), stderr());
        assertEquals(1, remote.runs.size());
    }

    @Test
    void syncAsksTheHostToPullFirstAndWarnsAboutOriginInsteadOfTheHostsCurrentState() {
        remote.warning = List.of("!! the host's current state - must not be used with --sync");
        remote.warningBeforeSync = List.of("!! origin/master is not what is here");

        assertEquals(5, onRemote(false, "test-echo", "hi", "--on-remote", "--sync"));

        assertEquals(List.of("me@host.invalid:/srv/checkout test-echo hi [sync]"), remote.runs);
        assertTrue(stderr().contains("origin/master is not what is here"), stderr());
        assertFalse(stderr().contains("current state"), stderr());
        assertTrue(stderr().contains("(pulling first)"), stderr());
    }

    @Test
    void syncWithoutOnRemoteIsLeftForTheCommandWhichRejectsWhatItDoesNotKnow() {
        assertEquals(ExitCodes.USAGE, syn("test-echo", "--sync"));

        assertTrue(remote.runs.isEmpty(), "nothing may run on the host without --on-remote");
        assertTrue(stderr().contains("--sync"), "the command, not the head, rejected it: " + stderr());
    }

    @Test
    void aReadOnlyCommandStreamsAndAMutatingOneIsDetached() {
        onRemote(false, "test-echo", "--on-remote");
        onRemote(false, "test-local", "--on-remote");
        onRemote(false, "test-remote", "--on-remote");

        assertEquals(List.of("me@host.invalid:/srv/checkout test-echo",
                "me@host.invalid:/srv/checkout test-local [detach]",
                "me@host.invalid:/srv/checkout test-remote [detach]"), remote.runs);
        assertTrue(stderr().contains("test-local on me@host.invalid:/srv/checkout (detached)"), stderr());
        assertFalse(stderr().contains("test-echo on me@host.invalid:/srv/checkout (detached)"), stderr());
    }
}
