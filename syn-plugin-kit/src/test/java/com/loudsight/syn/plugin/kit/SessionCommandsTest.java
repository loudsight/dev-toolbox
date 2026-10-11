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
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The session commands over synthetic transcripts: never a real {@code ~/.claude}, which the deploy host does not have. */
class SessionCommandsTest {

    private static final Instant NOW = Instant.parse("2026-10-01T12:00:00Z");
    private static final String INTERRUPTED_TOOL_USE = "a1000000-0000-0000-0000-000000000001";
    private static final String INTERRUPTED_TOOL_RESULT = "a2000000-0000-0000-0000-000000000002";
    private static final String INTERRUPTED_USER = "a3000000-0000-0000-0000-000000000003";
    private static final String QUESTION = "a4000000-0000-0000-0000-000000000004";
    private static final String FINAL = "a5000000-0000-0000-0000-000000000005";
    private static final String STUB = "a6000000-0000-0000-0000-000000000006";

    // ---- records

    private static String user(String at, String text) {
        return "{\"type\":\"user\",\"timestamp\":\"" + at + "\",\"message\":{\"role\":\"user\",\"content\":\"" + text + "\"}}";
    }

    private static String toolResult(String at, String toolUseId, String text) {
        return "{\"type\":\"user\",\"timestamp\":\"" + at + "\",\"message\":{\"role\":\"user\",\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\""
                + toolUseId + "\",\"content\":\"" + text + "\"}]}}";
    }

    private static String say(String at, String text) {
        return "{\"type\":\"assistant\",\"timestamp\":\"" + at + "\",\"message\":{\"role\":\"assistant\",\"stop_reason\":\"end_turn\",\"content\":[{\"type\":\"text\",\"text\":\""
                + text + "\"}]}}";
    }

    private static String tool(String at, String id, String name, String inputJson) {
        return "{\"type\":\"assistant\",\"timestamp\":\"" + at + "\",\"message\":{\"role\":\"assistant\",\"stop_reason\":\"tool_use\",\"content\":[{\"type\":\"tool_use\",\"id\":\""
                + id + "\",\"name\":\"" + name + "\",\"input\":" + inputJson + "}]}}";
    }

    private static String sidechainTool(String at) {
        return "{\"type\":\"assistant\",\"isSidechain\":true,\"timestamp\":\"" + at
                + "\",\"message\":{\"role\":\"assistant\",\"stop_reason\":\"tool_use\",\"content\":[{\"type\":\"tool_use\",\"id\":\"s1\",\"name\":\"Read\",\"input\":{}}]}}";
    }

    private static String title(String text) {
        return "{\"type\":\"ai-title\",\"aiTitle\":\"" + text + "\"}";
    }

    private static void transcript(Path dir, String id, String... records) throws IOException {
        Fixtures.write(dir.resolve(id + ".jsonl"), String.join("\n", records) + "\n");
    }

    private static SessionListOpen list() {
        SessionListOpen command = new SessionListOpen();
        command.clock = () -> NOW;
        return command;
    }

    private static Result runList(SessionListOpen command, Path root, Path projects, Path registry, String... extra) throws Exception {
        List<String> args = new java.util.ArrayList<>(List.of("--projects-dir", projects.toString(), "--sessions-dir", registry.toString()));
        args.addAll(List.of(extra));
        return Fixtures.run(command, root, args.toArray(String[]::new));
    }

    private static void fiveEndings(Path projects) throws IOException {
        String t = "2026-09-20T10:00:00Z";
        transcript(projects, INTERRUPTED_TOOL_USE, title("tool-use end"), user(t, "go"), tool(t, "t1", "Bash", "{\\\"command\\\":\\\"ls\\\"}"));
        transcript(projects, INTERRUPTED_TOOL_RESULT, title("tool-result end"), user(t, "go"), tool(t, "t1", "Bash", "{}"), toolResult(t, "t1", "ok"));
        transcript(projects, INTERRUPTED_USER, title("unanswered"), user(t, "first"), say(t, "Done."), user("2026-09-20T10:05:00Z", "and another thing"));
        transcript(projects, QUESTION, title("asks"), user(t, "go"), say(t, "I changed two files. Want me to apply the third as well?"));
        transcript(projects, FINAL, title("finished"), user(t, "go"), say(t, "All done; nothing outstanding."));
        transcript(projects, STUB, "{\"type\":\"mode\",\"mode\":\"default\"}");
    }

    // ---- session-list-open

    @Test
    void nominatesOnlyInterruptedAndQuestionPendingSessions(@TempDir Path root) throws Exception {
        Path projects = root.resolve("projects");
        fiveEndings(projects);

        Result result = runList(list(), root, projects, root.resolve("registry"));

        assertEquals(ExitCodes.OK, result.exit(), result.stderr());
        assertTrue(result.stdout().startsWith("4 open of 5 sessions"), result.stdout());
        assertTrue(result.stdout().contains("0 running and skipped, 0 marked closed, 1 finished with nothing pending)"),
                "the four buckets add up to the five sessions: " + result.stdout());
        assertTrue(result.stdout().contains("a1000000  interrupted"), result.stdout());
        assertTrue(result.stdout().contains("a2000000  interrupted"), result.stdout());
        assertTrue(result.stdout().contains("a3000000  interrupted"), result.stdout());
        assertTrue(result.stdout().contains("a4000000  question-pending"), result.stdout());
        assertFalse(result.stdout().contains("a5000000"), "a finished session is not nominated: " + result.stdout());
        assertFalse(result.stdout().contains("a6000000"), "an empty stub is hidden: " + result.stdout());
    }

    @Test
    void aHandBackWithoutAQuestionMarkIsStillPending(@TempDir Path root) throws Exception {
        Path projects = root.resolve("projects");
        String t = "2026-09-20T10:00:00Z";
        transcript(projects, QUESTION, user(t, "go"), say(t, "Both fixes are in. Please run the staging deploy and tell me what it shows."));
        transcript(projects, FINAL, user(t, "go"), say(t, "Both fixes are in and the staging deploy shows healthy."));
        transcript(projects, INTERRUPTED_TOOL_RESULT, user(t, "go"), say(t, "Verified: the claim holds. Recommendation: nothing to change."));

        Result result = runList(list(), root, projects, root.resolve("registry"), "--all");

        assertTrue(result.stdout().contains("a4000000  question-pending"), result.stdout());
        assertTrue(result.stdout().contains("a5000000  final"), result.stdout());
        assertTrue(result.stdout().contains("a2000000  final"), "a plain recommendation is a conclusion, not a hand-back: " + result.stdout());
    }

    @Test
    void inspectCutsTheLastMessageUnlessFull(@TempDir Path root) throws Exception {
        Path projects = root.resolve("projects");
        transcript(projects, FINAL, user("2026-09-20T10:00:00Z", "go"), say("2026-09-20T10:01:00Z", "x".repeat(900) + " TAILMARK"));

        Result cut = Fixtures.run(new SessionInspectTranscript(), root, "a5000000", "--projects-dir", projects.toString());
        Result full = Fixtures.run(new SessionInspectTranscript(), root, "a5000000", "--full", "--projects-dir", projects.toString());

        assertTrue(cut.stdout().contains("cut at 600 characters; --full for all"), cut.stdout());
        assertFalse(cut.stdout().contains("TAILMARK"), cut.stdout());
        assertTrue(full.stdout().contains("TAILMARK"), full.stdout());
        assertFalse(full.stdout().contains("--full for all"), full.stdout());
    }

    @Test
    void allListsFinishedSessionsToo(@TempDir Path root) throws Exception {
        Path projects = root.resolve("projects");
        fiveEndings(projects);

        Result result = runList(list(), root, projects, root.resolve("registry"), "--all");

        assertTrue(result.stdout().contains("a5000000  final"), result.stdout());
    }

    @Test
    void aSidechainRecordDoesNotChangeHowTheMainConversationEnds(@TempDir Path root) throws Exception {
        Path projects = root.resolve("projects");
        transcript(projects, FINAL, user("2026-09-20T10:00:00Z", "go"), say("2026-09-20T10:01:00Z", "Done."), sidechainTool("2026-09-20T10:02:00Z"));

        Result result = runList(list(), root, projects, root.resolve("registry"), "--all");

        assertTrue(result.stdout().contains("a5000000  final"), result.stdout());
        assertTrue(result.stdout().contains("2026-09-20 10:01"), "last activity is the main conversation's: " + result.stdout());
    }

    @Test
    void theWindowUsesTheLastRecordNotTheFileTime(@TempDir Path root) throws Exception {
        Path projects = root.resolve("projects");
        transcript(projects, INTERRUPTED_TOOL_USE, user("2026-08-01T10:00:00Z", "old"), tool("2026-08-01T10:01:00Z", "t1", "Bash", "{}"));
        transcript(projects, INTERRUPTED_USER, user("2026-09-25T10:00:00Z", "recent"));

        Result thirtyDays = runList(list(), root, projects, root.resolve("registry"));
        Result everything = runList(list(), root, projects, root.resolve("registry"), "--since", "all");
        Result fromDate = runList(list(), root, projects, root.resolve("registry"), "--since", "2026-07-01");

        assertTrue(thirtyDays.stdout().startsWith("1 open of 1 sessions"), thirtyDays.stdout());
        assertFalse(thirtyDays.stdout().contains("a1000000"), thirtyDays.stdout());
        assertTrue(everything.stdout().startsWith("2 open of 2 sessions"), everything.stdout());
        assertTrue(fromDate.stdout().startsWith("2 open of 2 sessions"), fromDate.stdout());
    }

    @Test
    void aSessionWithARunningProcessIsLiveNotOpen(@TempDir Path root) throws Exception {
        Path projects = root.resolve("projects");
        Path registry = root.resolve("registry");
        transcript(projects, INTERRUPTED_TOOL_USE, user("2026-09-20T10:00:00Z", "go"), tool("2026-09-20T10:01:00Z", "t1", "Bash", "{}"));
        Fixtures.write(registry.resolve(ProcessHandle.current().pid() + ".json"),
                "{\"pid\":" + ProcessHandle.current().pid() + ",\"sessionId\":\"" + INTERRUPTED_TOOL_USE + "\"}");
        Fixtures.write(registry.resolve("999999999.json"), "{\"pid\":999999999,\"sessionId\":\"" + INTERRUPTED_USER + "\"}");
        transcript(projects, INTERRUPTED_USER, user("2026-09-20T10:00:00Z", "go"));

        Result result = runList(list(), root, projects, registry);

        assertTrue(result.stdout().startsWith("1 open of 2 sessions"), result.stdout());
        assertTrue(result.stdout().contains("a1000000  interrupted      LIVE"), result.stdout());
        assertTrue(result.stdout().contains("a3000000  interrupted      OPEN"), "a dead pid does not make a session live: " + result.stdout());
    }

    @Test
    void activityInTheLastThirtyMinutesCountsAsRunning(@TempDir Path root) throws Exception {
        Path projects = root.resolve("projects");
        transcript(projects, INTERRUPTED_USER, user("2026-10-01T11:45:00Z", "just now"));

        Result result = runList(list(), root, projects, root.resolve("registry"));

        assertTrue(result.stdout().startsWith("0 open of 1 sessions"), result.stdout());
        assertTrue(result.stdout().contains("LIVE"), result.stdout());
    }

    @Test
    void aClosesSessionTrailerMarksASessionClosed(@TempDir Path root) throws Exception {
        assumeTrue(Cmd.of("git", "--version").discardErr().capture().ok(), "git is needed");
        Path projects = root.resolve("projects");
        fiveEndings(projects);
        Fixtures.gitInit(root);
        Cmd.of("git", "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "--allow-empty", "-m", "close one\n\nCloses-session: " + QUESTION.substring(0, 8))
                .dir(root).discardErr().capture();

        Result result = runList(list(), root, projects, root.resolve("registry"));

        assertTrue(result.stdout().startsWith("3 open of 5 sessions"), result.stdout());
        assertTrue(result.stdout().contains("1 marked closed"), result.stdout());
        assertFalse(result.stdout().contains("a4000000"), result.stdout());
    }

    @Test
    void anUnreadableSinceIsAUsageError(@TempDir Path root) throws Exception {
        Result result = runList(list(), root, root.resolve("projects"), root.resolve("registry"), "--since", "last week");

        assertEquals(ExitCodes.USAGE, result.exit());
        assertTrue(result.stderr().contains("--since"), result.stderr());
    }

    @Test
    void aMissingTranscriptDirectoryIsAnEmptyListNotAFailure(@TempDir Path root) throws Exception {
        Result result = runList(list(), root, root.resolve("nowhere"), root.resolve("registry"));

        assertEquals(ExitCodes.OK, result.exit(), result.stderr());
        assertTrue(result.stdout().startsWith("0 open of 0 sessions"), result.stdout());
    }

    // ---- session-inspect-transcript

    private static void writeRichTranscript(Path projects) throws IOException {
        String t = "2026-09-20T10:00:00Z";
        transcript(projects, QUESTION, title("rich"), user(t, "please do the thing"),
                "{\"type\":\"attachment\",\"attachment\":{\"type\":\"plan_mode\",\"planFilePath\":\"/h/.claude/plans/idea-slug.md\"}}",
                tool(t, "w1", "Write", "{\"file_path\":\"/h/.claude/plans/idea-slug.md\",\"content\":\"x\"}"),
                tool(t, "w2", "Edit", "{\"file_path\":\"/r/src/Thing.java\"}"),
                tool(t, "p1", "ExitPlanMode", "{\"planFilePath\":\"/h/.claude/plans/idea-slug.md\",\"plan\":\"x\"}"),
                tool(t, "c1", "Bash", "{\"command\":\"git commit -m 'thing'\"}"),
                toolResult(t, "c1", "[master abc1234] add the thing\\n 1 file changed"),
                tool(t, "c2", "Bash", "{\"command\":\"git commit -m 'second'\"}"),
                "{not json at all",
                "{\"type\":\"file-history-snapshot\",\"snapshot\":{\"trackedFileBackups\":{\"src/Other.java\":{\"version\":1}}}}",
                say("2026-09-20T11:00:00Z", "Shipped. Should I also retire the plan?"));
    }

    @Test
    void inspectReportsPlansCommitsFilesAndTheEnding(@TempDir Path root) throws Exception {
        Path projects = root.resolve("projects");
        writeRichTranscript(projects);

        Result result = Fixtures.run(new SessionInspectTranscript(), root, "a4000000", "--projects-dir", projects.toString());

        assertEquals(ExitCodes.OK, result.exit(), result.stderr());
        String text = result.stdout();
        assertTrue(text.contains("\"rich\""), text);
        assertTrue(text.contains("Ending:  question-pending"), text);
        assertTrue(text.contains("Prompt:  please do the thing"), text);
        assertTrue(text.contains("Plans (1):\n  /h/.claude/plans/idea-slug.md"), "the same plan seen three ways is listed once: " + text);
        assertTrue(text.contains("Commits (1 seen of 2 git-commit calls):"), text);
        assertTrue(text.contains("abc1234 master add the thing"), text);
        assertTrue(text.contains("/r/src/Thing.java"), text);
        assertTrue(text.contains("src/Other.java"), text);
        assertTrue(text.contains("(1 unreadable lines skipped)"), text);
    }

    @Test
    void inspectTakesAFileOrLatest(@TempDir Path root) throws Exception {
        Path projects = root.resolve("projects");
        writeRichTranscript(projects);
        transcript(projects, FINAL, title("newer"), user("2026-09-25T10:00:00Z", "go"), say("2026-09-25T10:01:00Z", "Done."));

        Result byFile = Fixtures.run(new SessionInspectTranscript(), root, projects.resolve(QUESTION + ".jsonl").toString());
        Result latest = Fixtures.run(new SessionInspectTranscript(), root, "--latest", "--projects-dir", projects.toString());

        assertTrue(byFile.stdout().contains("\"rich\""), byFile.stdout());
        assertTrue(latest.stdout().contains("\"newer\""), latest.stdout());
    }

    @Test
    void inspectRefusesAnAmbiguousOrUnknownPrefix(@TempDir Path root) throws Exception {
        Path projects = root.resolve("projects");
        fiveEndings(projects);

        Result ambiguous = Fixtures.run(new SessionInspectTranscript(), root, "a", "--projects-dir", projects.toString());
        Result unknown = Fixtures.run(new SessionInspectTranscript(), root, "zzzz", "--projects-dir", projects.toString());
        Result none = Fixtures.run(new SessionInspectTranscript(), root, "--projects-dir", projects.toString());

        assertEquals(ExitCodes.USAGE, ambiguous.exit());
        assertTrue(ambiguous.stderr().contains("sessions start with"), ambiguous.stderr());
        assertEquals(ExitCodes.USAGE, unknown.exit());
        assertEquals(ExitCodes.USAGE, none.exit());
        assertTrue(Files.isDirectory(projects));
    }

    @Test
    void aCloseInAConfiguredDirectoryCountsAndOneInAnUnlistedDirectoryDoesNot(@TempDir Path root) throws Exception {
        assumeTrue(Cmd.of("git", "--version").discardErr().capture().ok(), "git is needed");
        Path projects = root.resolve("projects");
        fiveEndings(projects);
        Path notes = Files.createDirectories(root.resolve("notes"));
        Cmd.of("git", "init", "-q").dir(notes).discardErr().capture();
        Cmd.of("git", "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "--allow-empty", "-m", "close one\n\nCloses-session: " + QUESTION.substring(0, 8))
                .dir(notes).discardErr().capture();
        Fixtures.gitInit(root);

        assertTrue(runList(list(), root, projects, root.resolve("registry")).stdout().startsWith("4 open of 5 sessions"), "notes/ is not listed, so its commit does not count");

        Fixtures.write(root.resolve(".syn/config.properties"), "sessions.closed-in=notes\n");
        Result configured = runList(list(), root, projects, root.resolve("registry"));

        assertTrue(configured.stdout().startsWith("3 open of 5 sessions"), configured.stdout());
        assertTrue(configured.stdout().contains("1 marked closed"), configured.stdout());
    }

    @Test
    void theNextStepNamesTheCloseSkillOnlyWhenOneIsConfigured(@TempDir Path root) throws Exception {
        Path projects = root.resolve("projects");
        fiveEndings(projects);

        String plain = runList(list(), root, projects, root.resolve("registry")).stdout();
        assertTrue(plain.contains("Next: syn session-inspect-transcript <id>. The ending is a heuristic"), plain);

        Fixtures.write(root.resolve(".syn/config.properties"), "sessions.close-skill=session-close\n");
        String named = runList(list(), root, projects, root.resolve("registry")).stdout();
        assertTrue(named.contains("Next: syn session-inspect-transcript <id>, then the session-close skill. The ending"), named);
    }
}
