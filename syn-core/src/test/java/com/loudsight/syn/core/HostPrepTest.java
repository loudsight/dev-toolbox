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

    /** git with the file transport allowed: a submodule whose remote is a local path is otherwise refused. */
    private static void gitFile(Path dir, String... args) {
        String[] withFile = new String[args.length + 2];
        withFile[0] = "-c";
        withFile[1] = "protocol.file.allow=always";
        System.arraycopy(args, 0, withFile, 2, args.length);
        git(dir, withFile);
    }

    /**
     * The failure that stopped the 2026-10-11 staging deploy: a public submodule's history was replaced, so the
     * pointers the superproject recorded on the way to its current one no longer exist on the submodule's remote.
     * A plain {@code git pull} fetches submodules for every commit it brings in, asks for each of those pointers
     * by hash, and dies with "not our ref" before the deploy starts. Only the pointer the superproject lands on needs
     * to be fetched, and {@code git submodule update} asks for exactly that.
     */
    @Test
    void aPullSucceedsWhenSubmodulePointersOnTheWayWereOrphanedByAHistoryRewrite() throws IOException {
        Path libOrigin = Files.createDirectory(temp.resolve("lib-origin.git"));
        git(libOrigin, "init", "--bare", "-q", "--initial-branch=master");
        Path lib = Files.createDirectory(temp.resolve("lib"));
        git(lib, "init", "-q", "--initial-branch=master");
        Files.writeString(lib.resolve("l.txt"), "lib one\n");
        git(lib, "add", "l.txt");
        git(lib, "commit", "-q", "-m", "lib one");
        git(lib, "remote", "add", "origin", libOrigin.toString());
        git(lib, "push", "-q", "origin", "HEAD:master");

        // The superproject adopts the submodule, and the host catches up to it: the state a host that is only a little
        // behind is in.
        gitFile(pusher, "submodule", "add", "-q", libOrigin.toString(), "lib");
        git(pusher, "commit", "-q", "-m", "add lib");
        git(pusher, "push", "-q", "origin", "HEAD:master");
        gitFile(host, "pull", "-q", "origin", "master");
        gitFile(host, "submodule", "update", "--init");

        // A pointer the superproject passes through: lib moves on, and the superproject records it.
        Files.writeString(lib.resolve("l.txt"), "lib two\n");
        git(lib, "commit", "-q", "-am", "lib two");
        git(lib, "push", "-q", "origin", "HEAD:master");
        gitFile(pusher.resolve("lib"), "fetch", "-q", "origin");
        git(pusher.resolve("lib"), "checkout", "-q", "FETCH_HEAD");
        git(pusher, "commit", "-q", "-am", "lib pointer: two");
        git(pusher, "push", "-q", "origin", "HEAD:master");

        // lib's history is then replaced by a fresh root and the old commits are gone from its remote.
        git(lib, "checkout", "-q", "--orphan", "fresh");
        git(lib, "commit", "-q", "-m", "lib fresh snapshot");
        git(lib, "push", "-q", "-f", "origin", "HEAD:master");
        git(libOrigin, "reflog", "expire", "--expire=now", "--all");
        git(libOrigin, "gc", "-q", "--prune=now");

        // ...and the superproject lands on a pointer that does exist.
        gitFile(pusher.resolve("lib"), "fetch", "-q", "origin");
        git(pusher.resolve("lib"), "checkout", "-q", "FETCH_HEAD");
        git(pusher, "commit", "-q", "-am", "lib pointer: fresh");
        git(pusher, "push", "-q", "origin", "HEAD:master");

        String script = "set -e\ncd '" + host + "'\n"
                + "export GIT_CONFIG_COUNT=1 GIT_CONFIG_KEY_0=protocol.file.allow GIT_CONFIG_VALUE_0=always\n"
                + HostPrep.sync().replace("/opt/.deploy-run.lock", lock.toString());
        Cmd.Result result = Cmd.of("bash", "-c", script).mergeErr().capture();

        assertTrue(result.ok(), result.stdout());
        assertEquals(head(pusher), head(host), "the superproject is at origin's commit");
        assertEquals(head(lib), head(host.resolve("lib")), "and the submodule is at the pointer it landed on");
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
