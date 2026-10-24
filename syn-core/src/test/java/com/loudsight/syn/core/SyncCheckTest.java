package com.loudsight.syn.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import com.loudsight.scriptkit.Cmd;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SyncCheckTest {

    private static final String CLEAN = """
            aaaaaaaaaaaaaaaa
            @@DIRTY
            @@SUBMODULES
             1111111111 kb (heads/master)
            +2222222222 dev-toolbox (heads/master-1-gabc)
            """;

    @Test
    void parseReadsTheCommitTheModifiedFilesAndEachSubmodulesCommit() {
        SyncCheck.State state = SyncCheck.State.parse("""
                abc123
                @@DIRTY
                 M scripts/myEnv.sh
                @@SUBMODULES
                 1111111111 kb (heads/master)
                -3333333333 other
                """);

        assertEquals("abc123", state.head());
        assertEquals(List.of("M scripts/myEnv.sh"), state.dirty());
        assertEquals("1111111111", state.submodules().get("kb"));
        assertEquals("3333333333", state.submodules().get("other"));
    }

    @Test
    void identicalCleanStatesHaveNoDifferencesAndNoWarning() {
        SyncCheck.State state = SyncCheck.State.parse(CLEAN);

        assertTrue(SyncCheck.differences(state, SyncCheck.State.parse(CLEAN), Set.of()).isEmpty());
        assertTrue(SyncCheck.block("h", List.of()).isEmpty());
    }

    @Test
    void aDifferentCommitIsReportedWithBothAbbreviatedShas() {
        SyncCheck.State here = SyncCheck.State.parse(CLEAN);
        SyncCheck.State there = SyncCheck.State.parse(CLEAN.replace("aaaaaaaaaaaaaaaa", "bbbbbbbbbbbbbbbb"));

        List<String> found = SyncCheck.differences(here, there, Set.of()).loud();

        assertEquals(1, found.size());
        assertTrue(found.get(0).contains("aaaaaaaaaa") && found.get(0).contains("bbbbbbbbbb"), found.get(0));
    }

    @Test
    void aSubmoduleAtAnotherCommitOrMissingOnOneSideIsReported() {
        SyncCheck.State here = SyncCheck.State.parse(CLEAN);
        SyncCheck.State there = SyncCheck.State.parse("""
                aaaaaaaaaaaaaaaa
                @@DIRTY
                @@SUBMODULES
                 9999999999 kb (heads/master)
                """);

        List<String> found = SyncCheck.differences(here, there, Set.of()).loud();

        assertEquals(2, found.size());
        assertTrue(found.stream().anyMatch(line -> line.contains("kb") && line.contains("9999999999")), found.toString());
        assertTrue(found.stream().anyMatch(line -> line.contains("dev-toolbox") && line.contains("the host has none")), found.toString());
    }

    private static final String HERE_EDITED = """
            aaaaaaaaaaaaaaaa
            @@DIRTY
             M a
             M b
             M c
             M d
            @@SUBMODULES
            """;

    private static final String THERE_EDITED = """
            aaaaaaaaaaaaaaaa
            @@DIRTY
             M z
            @@SUBMODULES
            """;

    @Test
    void editsHereAreANoteNotALoudFindingAndLongListsAreCut() {
        SyncCheck.Findings found = SyncCheck.differences(
                SyncCheck.State.parse(HERE_EDITED), SyncCheck.State.parse("aaaaaaaaaaaaaaaa\n@@DIRTY\n@@SUBMODULES\n"), Set.of());

        assertTrue(found.loud().isEmpty(), found.toString());
        assertEquals(1, found.notes().size());
        assertTrue(found.notes().get(0).startsWith("4 uncommitted file(s) here are not on the host") && found.notes().get(0).endsWith(", ..."),
                found.notes().get(0));
    }

    @Test
    void handEditsOnTheHostAreLoud() {
        SyncCheck.Findings found = SyncCheck.differences(
                SyncCheck.State.parse("aaaaaaaaaaaaaaaa\n@@DIRTY\n@@SUBMODULES\n"), SyncCheck.State.parse(THERE_EDITED), Set.of());

        assertEquals(1, found.loud().size());
        assertTrue(found.loud().get(0).startsWith("1 modified tracked file(s) on the host"), found.loud().get(0));
        assertTrue(found.notes().isEmpty(), found.toString());
    }

    @Test
    void ignoredPathsAreLeftOutOfTheSubmoduleAndEditComparisonsAlike() {
        SyncCheck.State here = SyncCheck.State.parse("""
                aaaaaaaaaaaaaaaa
                @@DIRTY
                 M kb
                 M kb/README.md
                @@SUBMODULES
                 1111111111 kb (heads/master)
                """);
        SyncCheck.State there = SyncCheck.State.parse("""
                aaaaaaaaaaaaaaaa
                @@DIRTY
                 M kb
                @@SUBMODULES
                 9999999999 kb (heads/master)
                """);

        assertTrue(SyncCheck.differences(here, there, Set.of("kb")).isEmpty(), SyncCheck.differences(here, there, Set.of("kb")).toString());
        SyncCheck.Findings unfiltered = SyncCheck.differences(here, there, Set.of());
        assertEquals(2, unfiltered.loud().size(), unfiltered.toString());
        assertEquals(1, unfiltered.notes().size(), unfiltered.toString());
    }

    @Test
    void anIgnoredNameDoesNotHideALookalikeSiblingPath() {
        SyncCheck.State here = SyncCheck.State.parse("aaaaaaaaaaaaaaaa\n@@DIRTY\n M kb-tools/run.sh\n@@SUBMODULES\n");
        SyncCheck.State there = SyncCheck.State.parse("aaaaaaaaaaaaaaaa\n@@DIRTY\n@@SUBMODULES\n");

        assertEquals(1, SyncCheck.differences(here, there, Set.of("kb")).notes().size());
    }

    @Test
    void theWarningIsABoxedBlockNamingTheHostAndEveryDifference() {
        List<String> block = SyncCheck.block("deploy@host.invalid", List.of("one", "two"));

        assertTrue(block.get(0).startsWith("!!!!") && block.get(block.size() - 1).startsWith("!!!!"), block.toString());
        assertTrue(block.get(1).contains("deploy@host.invalid") && block.get(1).contains("NOT running the code"), block.get(1));
        assertTrue(block.contains("!!   one") && block.contains("!!   two"), block.toString());
    }

    private static void git(Path dir, String... args) {
        String[] command = new String[args.length + 7];
        System.arraycopy(new String[] {"git", "-c", "user.email=t@example.invalid", "-c", "user.name=t", "-c", "commit.gpgsign=false"}, 0, command, 0, 7);
        System.arraycopy(args, 0, command, 7, args.length);
        Cmd.of(command).dir(dir).capture().orThrow();
    }

    @Test
    void beforeASyncOnlyOriginsHeadAndLocalEditsMatterNotTheHost(@TempDir Path temp) throws IOException {
        Path origin = Files.createDirectory(temp.resolve("origin.git"));
        git(origin, "init", "--bare", "-q", "--initial-branch=master");
        Path here = Files.createDirectory(temp.resolve("here"));
        git(here, "init", "-q", "--initial-branch=master");
        Files.writeString(here.resolve("a.txt"), "one\n");
        git(here, "add", "a.txt");
        git(here, "commit", "-q", "-m", "one");
        git(here, "remote", "add", "origin", origin.toString());
        git(here, "push", "-q", "origin", "HEAD:master");

        assertEquals(List.of(), SyncCheck.warningBeforeSync(here, Set.of()), "pushed and clean: nothing to say");

        Files.writeString(here.resolve("a.txt"), "edited\n");
        List<String> edited = SyncCheck.warningBeforeSync(here, Set.of());
        assertEquals(1, edited.size(), edited.toString());
        assertTrue(edited.get(0).startsWith("syn: note: 1 uncommitted file(s)"), edited.get(0));

        git(here, "commit", "-q", "-am", "two, not pushed");
        List<String> unpushed = SyncCheck.warningBeforeSync(here, Set.of());
        assertTrue(unpushed.get(0).startsWith("!!!!"), unpushed.toString());
        assertTrue(unpushed.stream().anyMatch(line -> line.contains("origin/master (what the host pulls) is at")), unpushed.toString());
        assertTrue(unpushed.stream().anyMatch(line -> line.contains("Push first")), unpushed.toString());
    }

    @Test
    void beforeASyncAnUnreadableOriginIsReportedNotSilentlyTreatedAsInSync(@TempDir Path temp) throws IOException {
        git(temp, "init", "-q", "--initial-branch=master");
        Files.writeString(temp.resolve("a.txt"), "x\n");
        git(temp, "add", "a.txt");
        git(temp, "commit", "-q", "-m", "x");

        List<String> warning = SyncCheck.warningBeforeSync(temp, Set.of());

        assertTrue(warning.stream().anyMatch(line -> line.contains("could not compare")), warning.toString());
    }
}
