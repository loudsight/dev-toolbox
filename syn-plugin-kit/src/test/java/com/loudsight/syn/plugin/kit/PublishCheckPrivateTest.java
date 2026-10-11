package com.loudsight.syn.plugin.kit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.loudsight.scriptkit.Cmd;
import com.loudsight.syn.plugin.kit.Fixtures.Result;
import com.loudsight.syn.spi.ExitCodes;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Every rule the public-repo guard enforces has a test that makes it fail, and the clean case beside it, as the repo's
 * convention for a check requires. The private terms are invented ({@code hush-host.invalid}), and the fake credentials
 * are assembled at runtime so this source can never trip the guard it tests.
 */
class PublishCheckPrivateTest {

    private static final String TERM = "hush-host.invalid";
    private static final String FAKE_GITHUB_TOKEN = "gh" + "p_" + "a1B2c3D4e5F6g7H8i9J0k1L2m3N4o5P6q7R8";
    private static final String FAKE_AWS_KEY = "AK" + "IA" + "QWERTYUIOPASDFGH";
    private static final String ZEROS = "0000000000000000000000000000000000000000";

    @BeforeAll
    static void needsGit() {
        assumeTrue(Cmd.of("sh", "-c", "command -v git").discardErr().capture().ok(), "git is needed");
    }

    // ---- the pieces

    @Test
    void termsMatchCaseInsensitivelyAndNameTheDenylistLineNotTheText() {
        PrivateTerms terms = PrivateTerms.parse(List.of("# private", "", "Hush-Host.invalid", "other-secret"), List.of());

        assertEquals(List.of(3), terms.hits("ssh deploy@HUSH-HOST.INVALID now"));
        assertEquals(List.of(3, 4), terms.hits("hush-host.invalid and other-secret"));
        assertEquals(List.of(), terms.hits("nothing private here"));
        assertEquals(2, terms.size());
    }

    @Test
    void anAllowedSubstringExcusesOnlyThatSubstring() {
        PrivateTerms terms = PrivateTerms.parse(List.of("example.com"), List.of("me@example.com"));

        assertEquals(List.of(), terms.hits("write to me@example.com"));
        assertEquals(List.of(1), terms.hits("write to me@example.com or to internal.example.com"));
    }

    @Test
    void theTripwiresCatchCredentialShapesAndIgnoreAwsDocumentedExample() {
        assertEquals(List.of("GitHub token"), SecretTripwires.hits("token = " + FAKE_GITHUB_TOKEN));
        assertEquals(List.of("AWS access key id"), SecretTripwires.hits("id=" + FAKE_AWS_KEY));
        assertEquals(List.of("private key block"), SecretTripwires.hits("-----BEGIN " + "RSA PRIVATE KEY-----"));
        assertEquals(List.of(), SecretTripwires.hits("aws_access_key_id = AKIAIOSFODNN7EXAMPLE"));
        assertEquals(List.of(), SecretTripwires.hits("an ordinary line of code"));
    }

    // ---- not public, and failing closed

    @Test
    void aRepoWithoutTheMarkerIsSkippedNotChecked(@TempDir Path root) throws Exception {
        Fixtures.write(root.resolve("a.txt"), TERM + "\n");
        Fixtures.gitInit(root);

        Result result = Fixtures.run(new PublishCheckPrivate(), root, "--staged", "--repo", root.toString(),
                "--denylist", denylist(root, TERM).toString());

        assertEquals(ExitCodes.OK, result.exit(), result.stdout());
        assertTrue(result.stdout().contains("not a public repo"), result.stdout());
    }

    @Test
    void aMissingDenylistInAPublicRepoFailsClosed(@TempDir Path root) throws Exception {
        publicRepo(root);

        Result result = Fixtures.run(new PublishCheckPrivate(), root, "--staged", "--repo", root.toString(),
                "--denylist", root.resolve("no-such-file").toString());

        assertEquals(ExitCodes.FAILURE, result.exit(), result.stdout());
        assertTrue(result.stdout().contains("refusing to pass"), result.stdout());
    }

    @Test
    void anEmptyDenylistInAPublicRepoFailsClosed(@TempDir Path root) throws Exception {
        publicRepo(root);

        Result result = Fixtures.run(new PublishCheckPrivate(), root, "--staged", "--repo", root.toString(),
                "--denylist", denylist(root, "# only a comment").toString());

        assertEquals(ExitCodes.FAILURE, result.exit(), result.stdout());
        assertTrue(result.stdout().contains("no entries"), result.stdout());
    }

    @Test
    void exactlyOneModeIsRequired(@TempDir Path root) throws Exception {
        publicRepo(root);

        Result none = Fixtures.run(new PublishCheckPrivate(), root, "--repo", root.toString());
        Result two = Fixtures.run(new PublishCheckPrivate(), root, "--staged", "--path", root.toString());

        assertEquals(ExitCodes.USAGE, none.exit());
        assertEquals(ExitCodes.USAGE, two.exit());
    }

    // ---- pre-commit: the staged change

    @Test
    void aCleanStagedChangePasses(@TempDir Path root) throws Exception {
        publicRepo(root);
        Fixtures.write(root.resolve("Hello.java"), "class Hello {}\n");
        stage(root);

        Result result = staged(root);

        assertEquals(ExitCodes.OK, result.exit(), result.stdout());
        assertTrue(result.stdout().contains("no private string"), result.stdout());
    }

    @Test
    void aPrivateStringInAnAddedLineFailsAndIsNotRepeated(@TempDir Path root) throws Exception {
        publicRepo(root);
        Fixtures.write(root.resolve("Config.java"), "class Config {\n  String host = \"" + TERM + "\";\n}\n");
        stage(root);

        Result result = staged(root);

        assertEquals(ExitCodes.FAILURE, result.exit(), result.stdout());
        assertTrue(result.stdout().contains("Config.java:2"), "names the file and line: " + result.stdout());
        assertTrue(result.stdout().contains("denylist entry on line 1"), result.stdout());
        assertFalse(result.stdout().contains(TERM), "the private text must not be echoed: " + result.stdout());
    }

    @Test
    void aPrivateStringInAFileNameFails(@TempDir Path root) throws Exception {
        publicRepo(root);
        Fixtures.write(root.resolve("notes-" + TERM + ".txt"), "harmless\n");
        stage(root);

        Result result = staged(root);

        assertEquals(ExitCodes.FAILURE, result.exit(), result.stdout());
        assertTrue(result.stdout().contains("file name"), result.stdout());
    }

    @Test
    void aCredentialTripwireFailsWithoutEchoingTheCredential(@TempDir Path root) throws Exception {
        publicRepo(root);
        Fixtures.write(root.resolve("deploy.properties"), "github.token=" + FAKE_GITHUB_TOKEN + "\n");
        stage(root);

        Result result = staged(root);

        assertEquals(ExitCodes.FAILURE, result.exit(), result.stdout());
        assertTrue(result.stdout().contains("secret tripwire: GitHub token"), result.stdout());
        assertFalse(result.stdout().contains(FAKE_GITHUB_TOKEN), result.stdout());
    }

    @Test
    void anAllowlistedContactPassesButAnotherUseOfTheDomainFails(@TempDir Path root) throws Exception {
        publicRepo(root);
        Fixtures.write(root.resolve(".public-allowlist"), "# public contact\ncontact@hush-host.invalid\n");
        Fixtures.write(root.resolve("pom.xml"), "<email>contact@hush-host.invalid</email>\n");
        stage(root);
        assertEquals(ExitCodes.OK, staged(root).exit(), "the permitted address passes");

        Fixtures.write(root.resolve("Url.java"), "String u = \"https://intranet.hush-host.invalid\";\n");
        stage(root);
        Result result = staged(root);

        assertEquals(ExitCodes.FAILURE, result.exit(), result.stdout());
        assertTrue(result.stdout().contains("Url.java"), result.stdout());
        assertFalse(result.stdout().contains("pom.xml"), "the allowed line is not reported: " + result.stdout());
    }

    @Test
    void aLineThatIsBeingRemovedIsNotFlagged(@TempDir Path root) throws Exception {
        publicRepo(root);
        Fixtures.write(root.resolve("old.txt"), "see " + TERM + "\n");
        stage(root);
        commit(root, "add", "A U Thor <author@example.invalid>");
        Fixtures.write(root.resolve("old.txt"), "see the docs\n");
        stage(root);

        Result result = staged(root);

        assertEquals(ExitCodes.OK, result.exit(), "removing a private line is the fix, not the leak: " + result.stdout());
    }

    @Test
    void anAddedLineStartingWithPlusPlusIsStillScanned(@TempDir Path root) throws Exception {
        publicRepo(root);
        Fixtures.write(root.resolve("diff.txt"), "context\n++ " + TERM + "\n");
        stage(root);

        Result result = staged(root);

        assertEquals(ExitCodes.FAILURE, result.exit(), "a '+++'-looking added line is content, not a header: " + result.stdout());
    }

    // ---- pre-push: the commits

    @Test
    void aPrivateStringInACommitMessageFailsTheRange(@TempDir Path root) throws Exception {
        publicRepo(root);
        Fixtures.write(root.resolve("a.txt"), "clean\n");
        stage(root);
        commit(root, "Reproduced on " + TERM + " last night", "A U Thor <author@example.invalid>");

        Result result = Fixtures.run(new PublishCheckPrivate(), root, "--range", "HEAD", "--repo", root.toString(),
                "--denylist", denylist(root, TERM).toString());

        assertEquals(ExitCodes.FAILURE, result.exit(), result.stdout());
        assertTrue(result.stdout().contains("message/identity"), result.stdout());
        assertFalse(result.stdout().contains(TERM), result.stdout());
    }

    @Test
    void aPrivateAuthorIdentityFailsTheRange(@TempDir Path root) throws Exception {
        publicRepo(root);
        Fixtures.write(root.resolve("a.txt"), "clean\n");
        stage(root);
        commit(root, "tidy", "Dev <dev@" + TERM + ">");

        Result result = Fixtures.run(new PublishCheckPrivate(), root, "--range", "HEAD", "--repo", root.toString(),
                "--denylist", denylist(root, TERM).toString());

        assertEquals(ExitCodes.FAILURE, result.exit(), result.stdout());
        assertTrue(result.stdout().contains("message/identity"), result.stdout());
    }

    @Test
    void aCleanRangePasses(@TempDir Path root) throws Exception {
        publicRepo(root);
        Fixtures.write(root.resolve("a.txt"), "clean\n");
        stage(root);
        commit(root, "tidy", "A U Thor <author@example.invalid>");

        Result result = Fixtures.run(new PublishCheckPrivate(), root, "--range", "HEAD", "--repo", root.toString(),
                "--denylist", denylist(root, TERM).toString());

        assertEquals(ExitCodes.OK, result.exit(), result.stdout());
    }

    @Test
    void aLeakAddedInAnEarlierCommitOfTheRangeIsStillCaughtAfterALaterCommitRemovesIt(@TempDir Path root) throws Exception {
        publicRepo(root);
        Fixtures.write(root.resolve("a.txt"), "host " + TERM + "\n");
        stage(root);
        commit(root, "first", "A U Thor <author@example.invalid>");
        Fixtures.write(root.resolve("a.txt"), "host removed\n");
        stage(root);
        commit(root, "second", "A U Thor <author@example.invalid>");

        Result result = Fixtures.run(new PublishCheckPrivate(), root, "--range", "HEAD", "--repo", root.toString(),
                "--denylist", denylist(root, TERM).toString());

        assertEquals(ExitCodes.FAILURE, result.exit(), "the history still carries it, so pushing it would publish it: " + result.stdout());
    }

    @Test
    void thePrePushLinesBecomeRangesAndDeletionsAreIgnored(@TempDir Path root) throws Exception {
        publicRepo(root);
        Fixtures.write(root.resolve("a.txt"), "clean\n");
        stage(root);
        commit(root, "one", "A U Thor <author@example.invalid>");
        String head = Cmd.of("git", "rev-parse", "HEAD").dir(root).capture().orThrow();
        String lines = "refs/heads/new " + head + " refs/heads/new " + ZEROS + "\n"
                + "refs/heads/old " + head + " refs/heads/old " + head + "\n"
                + "(delete) " + ZEROS + " refs/heads/gone " + head + "\n";

        List<List<String>> ranges = PublishCheckPrivate.pushedRanges(root, new BufferedReader(new StringReader(lines)));

        assertEquals(List.of(List.of(head, "--not", "--remotes"), List.of(head + ".." + head)), ranges);
    }

    // ---- a tree or a piece of text

    @Test
    void aTreeIsCheckedForContentAndPathsAndSkipsBuildOutputAndBinaries(@TempDir Path root) throws Exception {
        Fixtures.write(root.resolve("src/Main.java"), "// " + TERM + "\n");
        Fixtures.write(root.resolve("target/classes/Generated.java"), "// " + TERM + "\n");
        Files.write(root.resolve("logo.bin"), new byte[] {0, 1, 2, 3});

        Result result = Fixtures.run(new PublishCheckPrivate(), root, "--path", root.toString(),
                "--denylist", denylist(root, TERM).toString());

        assertEquals(ExitCodes.FAILURE, result.exit(), result.stdout());
        assertTrue(result.stdout().contains("Main.java:1"), result.stdout());
        assertFalse(result.stdout().contains("Generated.java"), "build output is not published: " + result.stdout());
    }

    @Test
    void aSingleTextFileIsChecked(@TempDir Path root) throws Exception {
        Path post = Fixtures.write(root.resolve("post.md"), "# A post\nI deploy to " + TERM + " daily.\n");

        Result dirty = Fixtures.run(new PublishCheckPrivate(), root, "--path", post.toString(),
                "--denylist", denylist(root, TERM).toString());
        Fixtures.write(post, "# A post\nI deploy daily.\n");
        Result clean = Fixtures.run(new PublishCheckPrivate(), root, "--path", post.toString(),
                "--denylist", denylist(root, TERM).toString());

        assertEquals(ExitCodes.FAILURE, dirty.exit(), dirty.stdout());
        assertTrue(dirty.stdout().contains("post.md:2"), dirty.stdout());
        assertEquals(ExitCodes.OK, clean.exit(), clean.stdout());
    }

    // ---- the secret scanner

    @Test
    void aScannerThatFindsSecretsFailsTheCheck(@TempDir Path root) throws Exception {
        publicRepo(root);
        Fixtures.write(root.resolve("a.txt"), "clean\n");
        stage(root);

        Result result = Fixtures.run(new PublishCheckPrivate(), root, "--staged", "--repo", root.toString(),
                "--denylist", denylist(root, TERM).toString(), "--scanner", stubScanner(root, 1).toString());

        assertEquals(ExitCodes.FAILURE, result.exit(), result.stdout());
        assertTrue(result.stdout().contains("found secrets"), result.stdout());
    }

    @Test
    void aScannerThatFindsNothingPassesAndACrashingScannerFails(@TempDir Path root) throws Exception {
        publicRepo(root);
        Fixtures.write(root.resolve("a.txt"), "clean\n");
        stage(root);

        Result clean = Fixtures.run(new PublishCheckPrivate(), root, "--staged", "--repo", root.toString(),
                "--denylist", denylist(root, TERM).toString(), "--scanner", stubScanner(root, 0).toString());
        Result crashed = Fixtures.run(new PublishCheckPrivate(), root, "--staged", "--repo", root.toString(),
                "--denylist", denylist(root, TERM).toString(), "--scanner", stubScanner(root, 3).toString());

        assertEquals(ExitCodes.OK, clean.exit(), clean.stdout());
        assertEquals(ExitCodes.FAILURE, crashed.exit(), "a scanner that crashed checked nothing: " + crashed.stdout());
    }

    @Test
    void noScannerWarnsUnlessItIsRequired(@TempDir Path root) throws Exception {
        publicRepo(root);
        Fixtures.write(root.resolve("a.txt"), "clean\n");
        stage(root);

        Result warned = Fixtures.run(new PublishCheckPrivate(), root, "--staged", "--repo", root.toString(),
                "--denylist", denylist(root, TERM).toString(), "--scanner", "none");
        Result required = Fixtures.run(new PublishCheckPrivate(), root, "--staged", "--repo", root.toString(),
                "--denylist", denylist(root, TERM).toString(), "--scanner", "none", "--require-scanner");

        assertEquals(ExitCodes.OK, warned.exit(), "the tripwires still ran, so a missing scanner is only a warning: " + warned.stdout());
        assertTrue(warned.stdout().contains("only the built-in tripwires ran"), warned.stdout());
        assertEquals(ExitCodes.FAILURE, required.exit(), required.stdout());
    }

    @Test
    void aScannerThatCannotStartFails(@TempDir Path root) throws Exception {
        publicRepo(root);
        Fixtures.write(root.resolve("a.txt"), "clean\n");
        stage(root);

        Result result = Fixtures.run(new PublishCheckPrivate(), root, "--staged", "--repo", root.toString(),
                "--denylist", denylist(root, TERM).toString(), "--scanner", root.resolve("no-such-scanner").toString());

        assertEquals(ExitCodes.FAILURE, result.exit(), "a scanner that never ran checked nothing: " + result.stdout());
        assertTrue(result.stdout().contains("could not be run"), result.stdout());
    }

    // ---- helpers

    private static Result staged(Path root) throws Exception {
        return Fixtures.run(new PublishCheckPrivate(), root, "--staged", "--repo", root.toString(),
                "--denylist", denylistPath(root).toString(), "--scanner", "true");
    }

    private static Path denylist(Path root, String... lines) throws IOException {
        Path file = root.resolveSibling(root.getFileName() + "-denylist.txt");
        Files.write(file, List.of(lines), StandardCharsets.UTF_8);
        return file;
    }

    private static Path denylistPath(Path root) throws IOException {
        return denylist(root, TERM);
    }

    private static void publicRepo(Path root) throws IOException {
        Files.createDirectories(root);
        Fixtures.write(root.resolve(".public-repo"), "");
        Cmd.of("git", "init", "-q").dir(root).discardErr().capture();
    }

    private static void stage(Path root) {
        Cmd.of("git", "add", "-A").dir(root).discardErr().capture();
    }

    private static void commit(Path root, String message, String author) {
        Cmd.of("git", "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "--allow-empty", "-m", message, "--author", author)
                .dir(root).discardErr().capture().orThrow();
    }

    /** A scanner stand-in that ignores its arguments and exits with {@code code}. */
    private static Path stubScanner(Path root, int code) throws IOException {
        Path script = root.resolveSibling(root.getFileName() + "-scanner-" + code + ".sh");
        Files.writeString(script, "#!/bin/sh\necho 'stub scanner output'\nexit " + code + "\n", StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(script, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        return script;
    }
}
