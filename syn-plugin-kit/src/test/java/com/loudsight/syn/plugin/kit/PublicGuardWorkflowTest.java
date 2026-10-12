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
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The CI workflow fetches syn from Maven Central and runs {@code publish-check-private}; what it adds is the shell around that
 * call: which commits to look at, the fail-closed handling of a missing secret, and the fork exception. These tests run that
 * script, extracted from the installed resource, against real throwaway repos with {@link StubSyn} standing in for the installed
 * {@code syn}. (Until syn reached Central the script was a second implementation of the rules and a test held the two together.)
 */
class PublicGuardWorkflowTest {

    private static final String TERM = "hush-host.invalid";
    private static final String ZEROS = "0000000000000000000000000000000000000000";
    private static final String AUTHOR = "A U Thor <author@example.invalid>";
    private static final Pattern DENYLIST_LINE = Pattern.compile("denylist entry on line (\\d+)");

    private static String script;
    private static String stubBin;

    @BeforeAll
    static void extractTheScript() throws IOException {
        assumeTrue(Cmd.of("sh", "-c", "command -v git && command -v java").discardErr().capture().ok(), "git and java are needed");
        Path bin = Files.createTempDirectory("stub-syn-bin");
        Path syn = bin.resolve("syn");
        Files.writeString(syn, "#!/bin/sh\nexec java -cp '" + System.getProperty("java.class.path")
                + "' com.loudsight.syn.plugin.kit.StubSyn \"$@\"\n", StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(syn, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        stubBin = bin.toString();
        script = PublishInstallHooks.workflow().lines()
                .dropWhile(line -> !line.contains("# BEGIN public-guard"))
                .skip(1)
                .takeWhile(line -> !line.contains("# END public-guard"))
                .map(line -> line.startsWith("          ") ? line.substring(10) : line)
                .reduce("", (all, line) -> all + line + "\n");
        assertTrue(script.contains("set -eu"), "the markers must bracket the script");
    }

    // ---- what it catches, one category at a time

    @Test
    void aCleanHistoryPasses(@TempDir Path repo) throws Exception {
        repo(repo);
        commit(repo, "a.txt", "clean\n", "start", AUTHOR);

        Run run = guard(repo, TERM);

        assertEquals(0, run.exit(), run.output());
        assertTrue(run.output().contains("no private string or secret tripwire"), run.output());
    }

    @Test
    void aPrivateStringInAddedContentFailsWithoutBeingEchoed(@TempDir Path repo) throws Exception {
        repo(repo);
        commit(repo, "Config.java", "class C {\n  String h = \"" + TERM + "\";\n}\n", "add config", AUTHOR);

        Run run = guard(repo, TERM);

        assertEquals(1, run.exit(), run.output());
        assertTrue(run.output().contains("Config.java:2"), run.output());
        assertTrue(run.output().contains("denylist entry on line 1"), run.output());
        assertFalse(run.output().contains(TERM), "the private text must not reach the log: " + run.output());
    }

    @Test
    void aPrivateStringInACommitMessageFails(@TempDir Path repo) throws Exception {
        repo(repo);
        commit(repo, "a.txt", "clean\n", "Reproduced on " + TERM, AUTHOR);

        assertEquals(1, guard(repo, TERM).exit());
    }

    @Test
    void aPrivateAuthorIdentityFails(@TempDir Path repo) throws Exception {
        repo(repo);
        commit(repo, "a.txt", "clean\n", "tidy", "Dev <dev@" + TERM + ">");

        Run run = guard(repo, TERM);

        assertEquals(1, run.exit(), run.output());
        assertTrue(run.output().contains("message/identity"), run.output());
    }

    @Test
    void aPrivateStringInAFileNameFails(@TempDir Path repo) throws Exception {
        repo(repo);
        commit(repo, "notes-" + TERM + ".txt", "harmless\n", "add", AUTHOR);

        Run run = guard(repo, TERM);

        assertEquals(1, run.exit(), run.output());
        assertTrue(run.output().contains("file name"), run.output());
    }

    @Test
    void aLeakALaterCommitRemovesIsStillCaughtBecauseTheHistoryCarriesIt(@TempDir Path repo) throws Exception {
        repo(repo);
        commit(repo, "a.txt", "host " + TERM + "\n", "first", AUTHOR);
        commit(repo, "a.txt", "host removed\n", "second", AUTHOR);

        assertEquals(1, guard(repo, TERM).exit());
    }

    @Test
    void anAddedLineThatLooksLikeADiffHeaderIsStillContent(@TempDir Path repo) throws Exception {
        repo(repo);
        commit(repo, "d.txt", "context\n++ " + TERM + "\n", "add", AUTHOR);

        assertEquals(1, guard(repo, TERM).exit());
    }

    // ---- the allowlist

    @Test
    void anAllowedSubstringPassesButAnotherUseOfTheDomainFails(@TempDir Path repo) throws Exception {
        repo(repo);
        Fixtures.write(repo.resolve(".public-allowlist"), "# public contact\ncontact@" + TERM + "\n");
        commit(repo, "pom.xml", "<email>contact@" + TERM + "</email>\n", "contact", AUTHOR);
        assertEquals(0, guard(repo, TERM).exit(), "the permitted address passes");

        commit(repo, "Url.java", "String u = \"https://intranet." + TERM + "\";\n", "url", AUTHOR);
        Run run = guard(repo, TERM);

        assertEquals(1, run.exit(), run.output());
        assertTrue(run.output().contains("Url.java"), run.output());
        assertFalse(run.output().contains("pom.xml"), run.output());
    }

    // ---- failing closed, and the one exception

    @Test
    void aMissingDenylistSecretFailsClosedOnAPush(@TempDir Path repo) throws Exception {
        repo(repo);
        commit(repo, "a.txt", "clean\n", "start", AUTHOR);

        Run run = guard(repo, null);

        assertEquals(1, run.exit(), run.output());
        assertTrue(run.output().contains("PRIVATE_DENYLIST secret is not set"), run.output());
    }

    @Test
    void aForkPullRequestWithoutSecretsSkipsOnlyTheDenylist(@TempDir Path repo) throws Exception {
        repo(repo);
        commit(repo, "a.txt", "clean\n", "start", AUTHOR);

        Run run = guard(repo, null, "IS_FORK_PR", "true");

        assertEquals(0, run.exit(), run.output());
        assertTrue(run.output().contains("fork's pull request"), run.output());
    }

    @Test
    void aForkPullRequestStillFailsOnASecretFound(@TempDir Path repo) throws Exception {
        repo(repo);
        commit(repo, "a.txt", "clean\n", "start", AUTHOR);

        Run run = guard(repo, null, "IS_FORK_PR", "true", "SECRET_SCANNER", stubScanner(repo, 1).toString());

        assertEquals(1, run.exit(), run.output());
        assertTrue(run.output().contains("found secrets"), run.output());
    }

    @Test
    void anEmptyDenylistFailsClosed(@TempDir Path repo) throws Exception {
        repo(repo);
        commit(repo, "a.txt", "clean\n", "start", AUTHOR);

        Run run = guard(repo, "# only a comment\n");

        assertEquals(1, run.exit(), run.output());
        assertTrue(run.output().contains("no entries"), run.output());
    }

    @Test
    void aScannerThatFindsSecretsOrIsMissingFails(@TempDir Path repo) throws Exception {
        repo(repo);
        commit(repo, "a.txt", "clean\n", "start", AUTHOR);

        Run found = guard(repo, TERM, "SECRET_SCANNER", stubScanner(repo, 1).toString());
        Run missing = guard(repo, TERM, "SECRET_SCANNER", "no-such-scanner-binary");

        assertEquals(1, found.exit(), found.output());
        assertEquals(1, missing.exit(), missing.output());
        assertTrue(missing.output().contains("could not be run"), missing.output());
    }

    // ---- which commits are looked at

    @Test
    void aPullRequestChecksOnlyTheCommitsAfterItsBase(@TempDir Path repo) throws Exception {
        repo(repo);
        commit(repo, "old.txt", TERM + "\n", "old leak, already on the base branch", AUTHOR);
        String base = head(repo);
        commit(repo, "new.txt", "clean\n", "the pull request", AUTHOR);

        Run run = guard(repo, TERM, "BASE", base);

        assertEquals(0, run.exit(), "history before the base is not this change's doing: " + run.output());
    }

    @Test
    void aFirstPushWithNoPreviousTipChecksTheWholeHistory(@TempDir Path repo) throws Exception {
        repo(repo);
        commit(repo, "old.txt", TERM + "\n", "start", AUTHOR);
        commit(repo, "new.txt", "clean\n", "next", AUTHOR);

        assertEquals(1, guard(repo, TERM, "BEFORE", ZEROS).exit());
    }

    @Test
    void aPushChecksTheCommitsAfterThePreviousTip(@TempDir Path repo) throws Exception {
        repo(repo);
        commit(repo, "old.txt", TERM + "\n", "start", AUTHOR);
        String before = head(repo);
        commit(repo, "new.txt", "clean\n", "next", AUTHOR);

        Run run = guard(repo, TERM, "BEFORE", before);

        assertEquals(0, run.exit(), run.output());
    }

    // ---- a new ref: the commits no other ref already has

    @Test
    void aNewTagOnACommitABranchAlreadyHasChecksNothingNew(@TempDir Path repo) throws Exception {
        repo(repo);
        commit(repo, "old.txt", TERM + "\n", "old history, already public", AUTHOR);
        onRemoteBranch(repo, "master");
        tag(repo, "v1");

        Run run = guard(repo, TERM, "BEFORE", ZEROS, "GITHUB_REF_TYPE", "tag", "GITHUB_REF_NAME", "v1");

        assertEquals(0, run.exit(), "the tag adds no commit, so the old history is not scanned again: " + run.output());
        assertTrue(run.output().contains("in 0 pieces of text"), run.output());
    }

    @Test
    void aNewTagOnACommitNoBranchHasIsChecked(@TempDir Path repo) throws Exception {
        repo(repo);
        commit(repo, "old.txt", "clean\n", "already public", AUTHOR);
        onRemoteBranch(repo, "master");
        commit(repo, "new.txt", TERM + "\n", "only the tag reaches this", AUTHOR);
        tag(repo, "v1");

        Run run = guard(repo, TERM, "BEFORE", ZEROS, "GITHUB_REF_TYPE", "tag", "GITHUB_REF_NAME", "v1");

        assertEquals(1, run.exit(), run.output());
        assertFalse(run.output().contains("old.txt"), "only the new commit is reported: " + run.output());
    }

    @Test
    void aNewBranchIsCheckedEvenThoughItsOwnRemoteRefExistsByNow(@TempDir Path repo) throws Exception {
        repo(repo);
        commit(repo, "old.txt", TERM + "\n", "the whole history is new to this repo", AUTHOR);
        onRemoteBranch(repo, "feature");

        Run run = guard(repo, TERM, "BEFORE", ZEROS, "GITHUB_REF_TYPE", "branch", "GITHUB_REF_NAME", "feature");

        assertEquals(1, run.exit(), "the first push of a branch must scan it: " + run.output());
    }

    @Test
    void aNewBranchCutFromAnExistingOneChecksOnlyItsOwnCommits(@TempDir Path repo) throws Exception {
        repo(repo);
        commit(repo, "old.txt", TERM + "\n", "already public on master", AUTHOR);
        onRemoteBranch(repo, "master");
        commit(repo, "new.txt", "clean\n", "the branch's own work", AUTHOR);
        onRemoteBranch(repo, "feature");

        Run run = guard(repo, TERM, "BEFORE", ZEROS, "GITHUB_REF_TYPE", "branch", "GITHUB_REF_NAME", "feature");

        assertEquals(0, run.exit(), run.output());
    }

    @Test
    void aTagPushAndThePrePushHookAgreeOnWhatIsNew(@TempDir Path root) throws Exception {
        Path repo = root.resolve("repo");
        repo(repo);
        Fixtures.write(repo.resolve(".public-repo"), "");
        Path deny = root.resolve("denylist.txt");
        Files.writeString(deny, TERM + "\n", StandardCharsets.UTF_8);
        commit(repo, "old.txt", TERM + "\n", "already public", AUTHOR);
        onRemoteBranch(repo, "master");
        tag(repo, "v1");
        // The hook's range for a new ref is the pushed sha against every remote ref; the script's is the same against the others.
        Result hook = Fixtures.run(new PublishCheckPrivate(), repo, "--range", head(repo) + " --not --remotes", "--repo", repo.toString(),
            "--denylist", deny.toString(), "--scanner", "none");
        Run script = guard(repo, TERM, "BEFORE", ZEROS, "GITHUB_REF_TYPE", "tag", "GITHUB_REF_NAME", "v1");

        assertEquals(ExitCodes.OK, hook.exit(), hook.stdout());
        assertEquals(0, script.exit(), script.output());

        commit(repo, "new.txt", TERM + "\n", "new and leaky", AUTHOR);
        tag(repo, "v2");
        Result hookLeak = Fixtures.run(new PublishCheckPrivate(), repo, "--range", head(repo) + " --not --remotes", "--repo", repo.toString(),
            "--denylist", deny.toString(), "--scanner", "none");
        Run scriptLeak = guard(repo, TERM, "BEFORE", ZEROS, "GITHUB_REF_TYPE", "tag", "GITHUB_REF_NAME", "v2");

        assertEquals(ExitCodes.FAILURE, hookLeak.exit(), hookLeak.stdout());
        assertEquals(1, scriptLeak.exit(), scriptLeak.output());
        assertEquals(denylistLines(hookLeak.stdout()), denylistLines(scriptLeak.output()), "same denylist lines");
    }

    // ---- the same verdict as the Java command

    @Test
    void theScriptCallsTheCommandAndKeepsNoSecondImplementationOfTheRules() {
        assertTrue(script.contains("syn publish-check-private"), script);
        assertFalse(script.contains("awk"), "the denylist rules live in PublishCheckPrivate only");
    }

    // ---- helpers

    private record Run(int exit, String output) {
    }

    private static Run guard(Path repo, @Nullable String denylist, String... extraEnv) {
        Cmd cmd = Cmd.of("sh", "-c", script).dir(repo).mergeErr()
                .env("HEAD_SHA", head(repo)).env("SECRET_SCANNER", "true")
                .env("PATH", stubBin + java.io.File.pathSeparator + System.getenv("PATH"));
        if (denylist != null) {
            cmd = cmd.env("PRIVATE_DENYLIST", denylist.endsWith("\n") ? denylist : denylist + "\n");
        }
        for (int i = 0; i < extraEnv.length; i += 2) {
            cmd = cmd.env(extraEnv[i], extraEnv[i + 1]);
        }
        Cmd.Result result = cmd.capture();
        return new Run(result.exitCode(), result.stdout());
    }

    private static List<Integer> denylistLines(String text) {
        List<Integer> lines = new ArrayList<>();
        Matcher matcher = DENYLIST_LINE.matcher(text);
        while (matcher.find()) {
            Integer line = Integer.valueOf(matcher.group(1));
            if (!lines.contains(line)) {
                lines.add(line);
            }
        }
        return lines.stream().sorted().toList();
    }

    private static void repo(Path repo) throws IOException {
        Files.createDirectories(repo);
        Cmd.of("git", "init", "-q").dir(repo).discardErr().capture().orThrow();
    }

    private static void commit(Path repo, String file, String content, String message, String author) throws IOException {
        Fixtures.write(repo.resolve(file), content);
        Cmd.of("git", "add", "-A").dir(repo).discardErr().capture().orThrow();
        Cmd.of("git", "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "--allow-empty", "-m", message, "--author", author)
                .dir(repo).discardErr().capture().orThrow();
    }

    /** What the runner's checkout leaves: a remote-tracking ref for the branch, pointing where it points now. */
    private static void onRemoteBranch(Path repo, String branch) {
        Cmd.of("git", "update-ref", "refs/remotes/origin/" + branch, head(repo)).dir(repo).discardErr().capture().orThrow();
    }

    private static void tag(Path repo, String name) {
        Cmd.of("git", "tag", name).dir(repo).discardErr().capture().orThrow();
    }

    private static String head(Path repo) {
        return Cmd.of("git", "rev-parse", "HEAD").dir(repo).discardErr().capture().orThrow();
    }

    private static Path stubScanner(Path repo, int code) throws IOException {
        Path file = repo.resolveSibling(repo.getFileName() + "-scanner-" + code + ".sh");
        Files.writeString(file, "#!/bin/sh\necho 'stub scanner output'\nexit " + code + "\n", StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(file, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        return file;
    }
}
