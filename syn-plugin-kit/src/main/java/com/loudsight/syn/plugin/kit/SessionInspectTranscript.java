package com.loudsight.syn.plugin.kit;

import com.loudsight.syn.plugin.kit.SessionTranscript.Commit;
import com.loudsight.syn.plugin.kit.SessionTranscript.Summary;
import com.loudsight.syn.spi.Context;
import com.loudsight.syn.spi.ExitCodes;
import com.loudsight.syn.spi.Output;
import com.loudsight.syn.spi.SynCommand;
import com.loudsight.syn.spi.Tier;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * Transcripts run to 10 MB and must never be read raw. This reads one in a streaming pass and prints what a close-out
 * needs: the title, the time range, how it ended, the plan files it wrote, the commits it made and the files it touched.
 *
 * <p>Plans and files come from Write/Edit/ExitPlanMode calls and plan attachments; commits are the {@code [branch sha]}
 * line git printed for a {@code git commit} the session ran. A change made through a shell command other than
 * {@code git commit} is invisible here: check {@code git status} and the log before trusting "nothing else changed".
 */
@Command(name = "session-inspect-transcript", mixinStandardHelpOptions = true,
        description = "show a local Claude session's plans, commits, touched files and ending — use when closing out one session (local only)",
        footer = "Example: syn session-inspect-transcript 44bedad2 --json")
public final class SessionInspectTranscript implements SynCommand {

    private static final int MAX_FILES_SHOWN = 40;

    @Parameters(arity = "0..1", paramLabel = "<id|prefix|path>", description = "A session id, a unique prefix of one (8 characters is enough), or a transcript file")
    @Nullable String target;

    @Option(names = "--full", description = "Print the last assistant message whole instead of cut at 600 characters")
    boolean full;

    @Option(names = "--latest", description = "The session with the most recent activity (it may be this one)")
    boolean latest;

    @Option(names = "--projects-dir", description = "Transcript directory (default: ~/.claude/projects/<this repo>)")
    @Nullable Path projectsDir;

    @Override
    public Tier tier() {
        return Tier.READ_ONLY;
    }

    @Override
    public List<String> keywords() {
        return List.of("transcript", "session facts", "what did that session change", "plans written", "commits made", "close session", "claude session");
    }

    @Override
    public int run(Context context) throws IOException {
        Output out = context.out();
        Path projects = projectsDir != null ? projectsDir : SessionStore.defaultProjectsDir(context.repoRoot());
        Path file;
        try {
            file = resolve(projects);
        } catch (IllegalArgumentException e) {
            out.errorln(Objects.requireNonNullElse(e.getMessage(), "invalid argument"));
            return ExitCodes.USAGE;
        }
        Summary summary = SessionTranscript.read(file);
        out.emit(summary, human(summary, full));
        return ExitCodes.OK;
    }

    private Path resolve(Path projects) throws IOException {
        if (latest) {
            Path newest = null;
            Instant newestAt = Instant.MIN;
            for (Path candidate : SessionStore.transcripts(projects, null)) {
                Instant last = SessionTranscript.read(candidate).last();
                if (last != null && last.isAfter(newestAt)) {
                    newest = candidate;
                    newestAt = last;
                }
            }
            if (newest == null) {
                throw new IllegalArgumentException("no transcripts with activity in " + projects);
            }
            return newest;
        }
        if (target == null || target.isBlank()) {
            throw new IllegalArgumentException("give a session id (or prefix, or file), or --latest");
        }
        Path asPath = Path.of(target);
        if (Files.isRegularFile(asPath)) {
            return asPath;
        }
        List<Path> matches = new ArrayList<>();
        for (Path candidate : SessionStore.transcripts(projects, null)) {
            if (SessionTranscript.idOf(candidate).startsWith(target.toLowerCase(Locale.ROOT))) {
                matches.add(candidate);
            }
        }
        if (matches.size() != 1) {
            throw new IllegalArgumentException(matches.isEmpty() ? "no session starts with '" + target + "' in " + projects
                    : matches.size() + " sessions start with '" + target + "': give more of the id");
        }
        return matches.get(0);
    }

    private static String human(Summary summary, boolean full) {
        StringBuilder text = new StringBuilder();
        text.append(String.format(Locale.ROOT, "Session %s  \"%s\"%n", summary.id(), summary.title()));
        text.append(String.format(Locale.ROOT, "Active:  %s -> %s%n", summary.start(), summary.last()));
        text.append(String.format(Locale.ROOT, "Ending:  %s%n", summary.ending().label()));
        text.append(String.format(Locale.ROOT, "Prompt:  %s%n", summary.firstPrompt()));
        text.append(String.format(Locale.ROOT, "%nPlans (%d):%n", summary.plans().size()));
        summary.plans().forEach(plan -> text.append("  ").append(plan).append('\n'));
        text.append(String.format(Locale.ROOT, "%nCommits (%d seen of %d git-commit calls):%n", summary.commits().size(), summary.commitAttempts()));
        for (Commit commit : summary.commits()) {
            text.append(String.format(Locale.ROOT, "  %s %s %s%n", commit.sha(), commit.branch(), commit.subject()));
        }
        text.append(String.format(Locale.ROOT, "%nFiles touched (%d, edits made through Bash are not listed):%n", summary.files().size()));
        summary.files().stream().limit(MAX_FILES_SHOWN).forEach(path -> text.append("  ").append(path).append('\n'));
        if (summary.files().size() > MAX_FILES_SHOWN) {
            text.append(String.format(Locale.ROOT, "  ... and %d more (--json lists all)%n", summary.files().size() - MAX_FILES_SHOWN));
        }
        text.append(String.format(Locale.ROOT, "%nLast assistant text%s:%n  %s", full ? "" : " (cut at " + SessionTranscript.LAST_TEXT_CHARS + " characters; --full for all)", full ? summary.lastAssistantText() : SessionTranscript.abbreviate(summary.lastAssistantText(), SessionTranscript.LAST_TEXT_CHARS)));
        if (summary.unparseableLines() > 0) {
            text.append(String.format(Locale.ROOT, "%n(%d unreadable lines skipped)", summary.unparseableLines()));
        }
        return text.toString();
    }
}
