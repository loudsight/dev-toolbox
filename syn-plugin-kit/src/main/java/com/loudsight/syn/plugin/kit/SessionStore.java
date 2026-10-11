package com.loudsight.syn.plugin.kit;

import com.loudsight.scriptkit.Cmd;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Where Claude Code keeps this machine's sessions and which of them are running or already closed. Local only:
 * transcripts live under the user's home on the laptop, never on the deploy host, so nothing here may run with
 * {@code --on-remote}, and tests give it temp directories instead of the real {@code ~/.claude}.
 */
final class SessionStore {

    /** A session whose last record is this recent counts as running even without a live process. */
    static final Duration RECENT = Duration.ofMinutes(30);

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final Pattern RELATIVE_DAYS = Pattern.compile("^(\\d+)d$");
    private static final Pattern CLOSES = Pattern.compile("^Closes-session:\\s*([0-9a-fA-F-]{8,36})\\s*$", Pattern.MULTILINE);

    private SessionStore() {
    }

    /** {@code ~/.claude/projects/<repo root with every non-alphanumeric character as '-'>}. */
    static Path defaultProjectsDir(Path repoRoot) {
        String encoded = repoRoot.toAbsolutePath().toString().replaceAll("[^A-Za-z0-9]", "-");
        return Path.of(System.getProperty("user.home"), ".claude", "projects", encoded);
    }

    /** {@code ~/.claude/sessions}: one {@code <pid>.json} per Claude Code process that is or was running. */
    static Path defaultSessionsDir() {
        return Path.of(System.getProperty("user.home"), ".claude", "sessions");
    }

    static Instant utcNow() {
        return ZonedDateTime.now(ZoneOffset.UTC).toInstant();
    }

    /**
     * The earliest last-activity a session may have to be considered: {@code 30d} is thirty days before {@code now},
     * {@code YYYY-MM-DD} is the start of that day (UTC), {@code all} is no limit (null).
     *
     * @throws IllegalArgumentException when {@code since} is none of those
     */
    static @Nullable Instant cutoff(String since, Instant now) {
        String text = since.strip();
        if ("all".equals(text)) {
            return null;
        }
        Matcher days = RELATIVE_DAYS.matcher(text);
        if (days.matches()) {
            return now.minus(Duration.ofDays(Long.parseLong(days.group(1))));
        }
        try {
            return LocalDate.parse(text).atStartOfDay(ZoneOffset.UTC).toInstant();
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("--since must be like 30d, 2026-09-01 or all, not '" + since + "'", e);
        }
    }

    /**
     * Transcript files that could have activity at or after {@code cutoff}. File mtime is no evidence of <em>when</em>
     * a session was last active (a bulk touch moves it forward), but a file last written before the cutoff cannot
     * hold activity after it, so mtime is used only to skip, never to date.
     */
    static List<Path> transcripts(Path projectsDir, @Nullable Instant cutoff) throws IOException {
        if (!Files.isDirectory(projectsDir)) {
            return List.of();
        }
        List<Path> found = new ArrayList<>();
        try (Stream<Path> files = Files.list(projectsDir)) {
            for (Path file : (Iterable<Path>) files.filter(f -> f.toString().endsWith(".jsonl"))::iterator) {
                if (cutoff == null || !Files.getLastModifiedTime(file).toInstant().isBefore(cutoff)) {
                    found.add(file);
                }
            }
        }
        return found;
    }

    /** Session ids whose Claude Code process is still running, from {@code <pid>.json} files holding {@code pid} and {@code sessionId}. */
    static Set<String> liveIds(Path sessionsDir) throws IOException {
        Set<String> live = new HashSet<>();
        if (!Files.isDirectory(sessionsDir)) {
            return live;
        }
        try (Stream<Path> files = Files.list(sessionsDir)) {
            for (Path file : (Iterable<Path>) files.filter(f -> f.toString().endsWith(".json"))::iterator) {
                JsonNode node = readOrNull(file);
                if (node == null) {
                    continue;
                }
                long pid = node.path("pid").asLong(-1);
                String id = node.path("sessionId").asString("");
                if (pid > 0 && !id.isEmpty() && ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
                    live.add(id);
                }
            }
        }
        return live;
    }

    /** The parsed file, or null for a half-written one: that session is then not provably running. */
    private static @Nullable JsonNode readOrNull(Path file) throws IOException {
        try {
            return MAPPER.readTree(Files.readString(file));
        } catch (JacksonException e) {
            return null;
        }
    }

    /**
     * Session ids named by a {@code Closes-session: <id>} line in a commit message of the repo or of one of the directories in
     * {@code alsoIn} (the repo's {@code sessions.closed-in}, e.g. a knowledge-base submodule), which is how a close-out marks a
     * session closed without a ledger file.
     */
    static Set<String> closedIds(Path repoRoot, List<String> alsoIn) {
        Set<String> closed = new HashSet<>();
        List<Path> dirs = new ArrayList<>(List.of(repoRoot));
        alsoIn.forEach(dir -> dirs.add(repoRoot.resolve(dir)));
        for (Path dir : dirs) {
            if (!Files.isDirectory(dir)) {
                continue;
            }
            Cmd.Result log = Cmd.of("git", "log", "--all", "--format=%B", "--grep=Closes-session:").dir(dir).discardErr().capture();
            if (!log.ok()) {
                continue;
            }
            Matcher matcher = CLOSES.matcher(log.stdout());
            while (matcher.find()) {
                closed.add(matcher.group(1));
            }
        }
        return closed;
    }

    /** True when a close-out marked {@code id}: a full id, or a prefix of one (the skill may write the short form). */
    static boolean isClosed(String id, Set<String> closedIds) {
        for (String closed : closedIds) {
            if (id.equals(closed) || (closed.length() >= 8 && id.startsWith(closed))) {
                return true;
            }
        }
        return false;
    }
}
