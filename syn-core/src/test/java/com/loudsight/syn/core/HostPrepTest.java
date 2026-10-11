package com.loudsight.syn.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.loudsight.scriptkit.Cmd;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The real host-prep script against real git repositories and a real {@code flock}. macOS has no {@code flock}, so these
 * run where there is one (the deploy host, at every {@code mvn verify} of syn); the lock path is swapped for a temp file.
 */
class HostPrepTest {

    @TempDir
    Path temp;

    private Path origin;
    private Path host;
    private Path pusher;
    private Path lock;

    private static void git(Path dir, String... args) {
        String[] command = new String[args.length + 7];
        System.arraycopy(new String[] {"git", "-c", "user.email=t@example.invalid", "-c", "user.name=t", "-c", "commit.gpgsign=false"}, 0, command, 0, 7);
        System.arraycopy(args, 0, command, 7, args.length);
        Cmd.of(command).dir(dir).capture().orThrow();
    }

    private String head(Path dir) {
        return Cmd.of("git", "rev-parse", "HEAD").dir(dir).capture().orThrow().strip();
    }

    @BeforeEach
    void repositories() throws IOException {
        assumeTrue(Cmd.of("bash", "-c", "command -v flock").capture().ok(), "needs a real flock (not on macOS)");
        origin = Files.createDirectory(temp.resolve("origin.git"));
        git(origin, "init", "--bare", "-q", "--initial-branch=master");
        pusher = Files.createDirectory(temp.resolve("pusher"));
        git(pusher, "init", "-q", "--initial-branch=master");
        Files.writeString(pusher.resolve("a.txt"), "one\n");
        git(pusher, "add", "a.txt");
        git(pusher, "commit", "-q", "-m", "one");
        git(pusher, "remote", "add", "origin", origin.toString());
        git(pusher, "push", "-q", "origin", "HEAD:master");
        host = temp.resolve("host");
        Cmd.of("git", "clone", "-q", origin.toString(), host.toString()).capture().orThrow();
        lock = temp.resolve("deploy.lock");
    }

    private void pushSecondCommit() throws IOException {
        Files.writeString(pusher.resolve("b.txt"), "two\n");
        git(pusher, "add", "b.txt");
        git(pusher, "commit", "-q", "-m", "two");
        git(pusher, "push", "-q", "origin", "HEAD:master");
    }

    private Cmd.Result prep() {
        String script = "set -e\ncd '" + host + "'\n" + HostPrep.sync().replace("/opt/.deploy-run.lock", lock.toString());
        return Cmd.of("bash", "-c", script).mergeErr().capture();
    }

    @Test
    void aCleanCheckoutPullsWhatOriginHas() throws IOException {
        pushSecondCommit();

        Cmd.Result result = prep();

        assertTrue(result.ok(), result.stdout());
        assertEquals(head(pusher), head(host), "the host is at origin's commit");
        assertTrue(Files.exists(host.resolve("b.txt")));
        assertTrue(result.stdout().contains("Pulling latest code"), result.stdout());
    }

    @Test
    void aHandEditedTrackedFileRefusesThePullNamesTheFileAndPullsNothing() throws IOException {
        pushSecondCommit();
        String before = head(host);
        Files.writeString(host.resolve("a.txt"), "hotfix on the host\n");

        Cmd.Result result = prep();

        assertEquals(1, result.exitCode(), result.stdout());
        assertTrue(result.stdout().contains("refusing to pull"), result.stdout());
        assertTrue(result.stdout().contains("a.txt"), "names the file: " + result.stdout());
        assertEquals(before, head(host), "nothing was pulled over the edit");
        assertEquals("hotfix on the host\n", Files.readString(host.resolve("a.txt")), "and the edit is untouched");
    }

    @Test
    void anUntrackedFileDoesNotBlockThePull() throws IOException {
        pushSecondCommit();
        Files.writeString(host.resolve("scratch.log"), "noise\n");

        assertTrue(prep().ok());
        assertEquals(head(pusher), head(host));
    }

    @Test
    void aRunHoldingTheLockMakesASecondOneRefuseWithoutTouchingTheCheckout() throws Exception {
        pushSecondCommit();
        String before = head(host);
        CompletableFuture<Cmd.Result> holder = CompletableFuture.supplyAsync(
                () -> Cmd.of("bash", "-c", "exec 8>'" + lock + "'; flock 8; sleep 4").capture());
        long deadline = System.nanoTime() + 3_000_000_000L;
        while (Cmd.of("bash", "-c", "exec 8>'" + lock + "'; flock -n 8").capture().ok() && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }

        Cmd.Result result = prep();

        assertEquals(1, result.exitCode(), result.stdout());
        assertTrue(result.stdout().contains("already using this checkout"), result.stdout());
        assertEquals(before, head(host), "a refused run does not pull");
        holder.get();
        assertTrue(prep().ok(), "once the holder is gone the same run goes through");
    }
}
