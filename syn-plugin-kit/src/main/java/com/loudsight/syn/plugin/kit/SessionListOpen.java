package com.loudsight.syn.plugin.kit;

import com.loudsight.syn.plugin.kit.SessionTranscript.Ending;
import com.loudsight.syn.core.SynConfig;
import com.loudsight.syn.plugin.kit.SessionTranscript.Summary;
import com.loudsight.syn.spi.Context;
import com.loudsight.syn.spi.ExitCodes;
import com.loudsight.syn.spi.Output;
import com.loudsight.syn.spi.SynCommand;
import com.loudsight.syn.spi.Tier;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Claude Code transcripts carry no completed marker, so a session is called open when its transcript <em>ends</em> open:
 * on a tool call with no result, on a tool result, on a user turn nobody answered, or on an assistant turn that hands
 * something back (a question, an offer, named leftovers). This lists those, newest first, within a last-activity window.
 *
 * <p>It nominates and never decides: the ending is a heuristic, and a close-out (in this repo the {@code session-close} skill, named by {@code sessions.close-skill}) reads the evidence.
 * Sessions that are running (a live Claude Code process, or activity in the last 30 minutes) are shown apart and are
 * never nominated, and a session a commit marks {@code Closes-session: <id>} (in the repo or a directory of {@code sessions.closed-in}) is skipped. Dating uses the last record's own
 * timestamp: file mtime is not evidence (a bulk touch moved 18 transcripts to the same minute on 2026-09-26).
 */
@Command(name = "session-list-open", mixinStandardHelpOptions = true,
        description = "list local Claude sessions that ended with work open, newest first — use when closing out or auditing sessions (local only)",
        footer = "Example: syn session-list-open --since 60d --json")
public final class SessionListOpen implements SynCommand {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT).withZone(ZoneOffset.UTC);
    private static final int SHORT_ID = 8;

    @Option(names = "--since", defaultValue = "30d", description = "How far back to look by last activity: 30d, 2026-09-01 or all (default ${DEFAULT-VALUE})")
    String since = "30d";

    @Option(names = "--all", description = "List every non-empty session in the window, not only the open ones")
    boolean all;

    @Option(names = "--projects-dir", description = "Transcript directory (default: ~/.claude/projects/<this repo>)")
    @Nullable Path projectsDir;

    @Option(names = "--sessions-dir", description = "Claude Code process registry (default: ~/.claude/sessions)")
    @Nullable Path sessionsDir;

    /** Replaced by tests so a fixed "now" decides the window and the 30-minute rule. */
    Supplier<Instant> clock = SessionStore::utcNow;

    /** One session as listed. */
    record Row(String id, String lastActivity, String ending, boolean live, boolean closed, boolean nominated, String title) {
    }

    /** What {@code --json} prints. */
    record Report(String since, int inWindow, int nominated, int live, int closed, List<Row> sessions) {
    }

    @Override
    public Tier tier() {
        return Tier.READ_ONLY;
    }

    @Override
    public List<String> keywords() {
        return List.of("open loops", "unfinished", "incomplete session", "interrupted", "close session", "transcript", "claude sessions", "what did I leave open");
    }

    @Override
    public int run(Context context) throws IOException {
        Output out = context.out();
        Instant now = clock.get();
        Instant cutoff;
        try {
            cutoff = SessionStore.cutoff(since, now);
        } catch (IllegalArgumentException e) {
            out.errorln(Objects.requireNonNullElse(e.getMessage(), "invalid argument"));
            return ExitCodes.USAGE;
        }
        Path projects = projectsDir != null ? projectsDir : SessionStore.defaultProjectsDir(context.repoRoot());
        Path registry = sessionsDir != null ? sessionsDir : SessionStore.defaultSessionsDir();
        Set<String> running = SessionStore.liveIds(registry);
        SynConfig config = SynConfig.load(context.repoRoot());
        Set<String> closedIds = SessionStore.closedIds(context.repoRoot(), config.list("sessions.closed-in"));

        List<Row> rows = new ArrayList<>();
        int inWindow = 0;
        int live = 0;
        int closed = 0;
        for (Path file : SessionStore.transcripts(projects, cutoff)) {
            Summary summary = SessionTranscript.read(file);
            Instant last = summary.last();
            if (summary.ending() == Ending.EMPTY || last == null || (cutoff != null && last.isBefore(cutoff))) {
                continue;
            }
            inWindow++;
            boolean isLive = running.contains(summary.id()) || last.isAfter(now.minus(SessionStore.RECENT));
            boolean isClosed = SessionStore.isClosed(summary.id(), closedIds);
            live += isLive ? 1 : 0;
            closed += isClosed && !isLive ? 1 : 0;
            boolean nominated = summary.ending().open() && !isLive && !isClosed;
            if (all || nominated || isLive) {
                rows.add(new Row(summary.id(), STAMP.format(last), summary.ending().label(), isLive, isClosed, nominated, summary.title()));
            }
        }
        rows.sort(Comparator.comparing(Row::lastActivity).reversed());
        int nominated = (int) rows.stream().filter(Row::nominated).count();
        out.emit(new Report(since, inWindow, nominated, live, closed, rows), human(rows, inWindow, nominated, live, closed, config.get("sessions.close-skill")));
        return ExitCodes.OK;
    }

    private String human(List<Row> rows, int inWindow, int nominated, int live, int closed, Optional<String> closeSkill) {
        StringBuilder text = new StringBuilder();
        // Every session is in exactly one bucket, so the four counts add up to inWindow: running first, then closed, then open,
        // and what remains ended with nothing pending.
        int finished = inWindow - nominated - live - closed;
        text.append(String.format(Locale.ROOT, "%d open of %d sessions active since %s (%d running and skipped, %d marked closed, %d finished with nothing pending)%n",
                nominated, inWindow, "all".equals(since) ? "the beginning" : "--since " + since, live, closed, finished));
        for (Row row : rows) {
            String flag = row.live() ? "LIVE" : row.closed() ? "closed" : row.nominated() ? "OPEN" : "";
            text.append(String.format(Locale.ROOT, "%s  %s  %-16s %-6s %s%n", row.lastActivity(), row.id().substring(0, Math.min(SHORT_ID, row.id().length())),
                    row.ending(), flag, row.title()));
        }
        if (nominated > 0) {
            text.append("Next: syn session-inspect-transcript <id>" + closeSkill.map(skill -> ", then the " + skill + " skill").orElse("")
                    + ". The ending is a heuristic: read before deciding.");
        }
        return text.toString().stripTrailing();
    }
}
