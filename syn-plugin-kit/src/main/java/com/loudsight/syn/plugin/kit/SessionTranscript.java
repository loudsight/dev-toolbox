package com.loudsight.syn.plugin.kit;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * What one Claude Code transcript ({@code <sessionId>.jsonl}) says about its session, read in a single streaming pass.
 *
 * <p>A transcript has no completed marker, so "open" is inferred from how it <em>ends</em>: the last user or assistant
 * record of the main conversation (sidechain records belong to subagents and are ignored). Facts come from the same
 * pass: the plans it wrote, the commits it made and the files it touched. Every field name read here was checked
 * against real transcripts on 2026-10-06; an unknown record type is skipped, a corrupt line is counted and skipped.
 */
final class SessionTranscript {

    /** How the main conversation ends. Only {@link #INTERRUPTED} and {@link #QUESTION_PENDING} are nominated as open. */
    enum Ending {
        /** Ends on a tool call with no result, on a tool result, or on a user turn nobody answered. */
        INTERRUPTED,
        /** The assistant finished its turn but asked something or named work still to do. */
        QUESTION_PENDING,
        /** The assistant finished its turn with nothing pending. */
        FINAL,
        /** No user or assistant record at all: a stub. */
        EMPTY;

        boolean open() {
            return this == INTERRUPTED || this == QUESTION_PENDING;
        }

        String label() {
            return name().toLowerCase(Locale.ROOT).replace('_', '-');
        }
    }

    /** A commit the session made: the {@code [branch sha] subject} line git printed. */
    record Commit(String branch, String sha, String subject) {
    }

    /** Everything read from one transcript. */
    record Summary(String id, @Nullable Instant start, @Nullable Instant last, String title, String firstPrompt, Ending ending,
            String lastAssistantText, List<String> plans, List<Commit> commits, int commitAttempts, List<String> files, int unparseableLines) {
    }

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final Pattern COMMIT_COMMAND = Pattern.compile("(^|[;&|\\s])git\\b[^\\n;&|]*\\bcommit\\b");
    private static final Pattern COMMIT_LINE = Pattern.compile("^\\[([^\\]\\s]+)(?: \\([^)]*\\))? ([0-9a-f]{7,40})\\] (.*)$", Pattern.MULTILINE);
    private static final Pattern PENDING_WORDING = Pattern.compile(
            "(?i)(want me to|should i|shall i|do you want|would you like|still awaiting|awaiting your|not yet (done|built|deployed|pushed)|remaining:|still open|next step|go ahead|i.m listening|please (run|confirm|review|decide|tell)|let me know|say the word|your call|up to you)");
    private static final int PROMPT_CHARS = 200;
    static final int LAST_TEXT_CHARS = 600;
    private static final int TAIL_CHARS = 300;

    private SessionTranscript() {
    }

    /** The session id a transcript file stands for: its name without {@code .jsonl}. */
    static String idOf(Path file) {
        String name = String.valueOf(file.getFileName());
        return name.endsWith(".jsonl") ? name.substring(0, name.length() - ".jsonl".length()) : name;
    }

    /** One pass over {@code file}; never loads it whole (transcripts reach 10 MB). */
    static Summary read(Path file) throws IOException {
        State state = new State();
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode node;
                try {
                    node = MAPPER.readTree(line);
                } catch (JacksonException e) {
                    state.unparseable++;
                    continue;
                }
                state.accept(node);
            }
        }
        return state.summary(idOf(file));
    }

    /** Mutable accumulator for one pass; the records arrive in order. */
    private static final class State {

        private @Nullable Instant start;
        private @Nullable Instant last;
        private String title = "";
        private String firstPrompt = "";
        private Ending ending = Ending.EMPTY;
        private String lastAssistantText = "";
        private final Set<String> plans = new LinkedHashSet<>();
        private final Set<String> files = new LinkedHashSet<>();
        private final Map<String, String> pendingCommits = new HashMap<>();
        private final List<Commit> commits = new ArrayList<>();
        private int commitAttempts;
        private int unparseable;

        void accept(JsonNode node) {
            switch (node.path("type").asString("")) {
                case "ai-title" -> setTitle(node.path("aiTitle").asString(""));
                case "attachment" -> addPlan(node.path("attachment").path("planFilePath").asString(""));
                case "file-history-snapshot" -> {
                    for (String path : node.path("snapshot").path("trackedFileBackups").propertyNames()) {
                        files.add(path);
                    }
                }
                case "user" -> user(node);
                case "assistant" -> assistant(node);
                default -> {
                    // bookkeeping records (mode, permission-mode, last-prompt, system, ...) carry no facts
                }
            }
        }

        /** The last title record wins: Claude Code rewrites it as the session goes on. */
        private void setTitle(String latest) {
            if (!latest.isEmpty()) {
                title = latest;
            }
        }

        private boolean mainConversation(JsonNode node) {
            return !node.path("isSidechain").asBoolean(false);
        }

        private void stamp(JsonNode node) {
            String text = node.path("timestamp").asString("");
            try {
                Instant at = Instant.parse(text);
                if (start == null) {
                    start = at;
                }
                last = at;
            } catch (DateTimeParseException e) {
                // a record without a usable timestamp does not move the clock
                unparseable++;
            }
        }

        private void user(JsonNode node) {
            JsonNode content = node.path("message").path("content");
            boolean toolResult = false;
            StringBuilder text = new StringBuilder();
            if (content.isString()) {
                text.append(content.asString(""));
            } else if (content.isArray()) {
                for (JsonNode part : content) {
                    String kind = part.path("type").asString("");
                    if ("tool_result".equals(kind)) {
                        toolResult = true;
                        commitResult(part);
                    } else if ("text".equals(kind)) {
                        text.append(part.path("text").asString(""));
                    }
                }
            }
            if (!mainConversation(node)) {
                return;
            }
            stamp(node);
            ending = Ending.INTERRUPTED;
            if (!toolResult && firstPrompt.isEmpty() && !text.toString().isBlank() && !node.path("isMeta").asBoolean(false)) {
                firstPrompt = abbreviate(text.toString(), PROMPT_CHARS);
            }
        }

        private void assistant(JsonNode node) {
            JsonNode message = node.path("message");
            boolean toolUse = false;
            String text = "";
            for (JsonNode part : message.path("content")) {
                String kind = part.path("type").asString("");
                if ("text".equals(kind)) {
                    text = part.path("text").asString("");
                } else if ("tool_use".equals(kind)) {
                    toolUse = true;
                    toolUse(part);
                }
            }
            if (!mainConversation(node)) {
                return;
            }
            stamp(node);
            if (toolUse) {
                ending = Ending.INTERRUPTED;
                return;
            }
            String stop = message.path("stop_reason").asString("");
            if (!text.isBlank()) {
                lastAssistantText = text;
            }
            if (!"end_turn".equals(stop) && !"stop_sequence".equals(stop)) {
                ending = Ending.INTERRUPTED;
            } else {
                ending = endsWithQuestion(lastAssistantText) ? Ending.QUESTION_PENDING : Ending.FINAL;
            }
        }

        private void toolUse(JsonNode part) {
            JsonNode input = part.path("input");
            switch (part.path("name").asString("")) {
                case "ExitPlanMode" -> addPlan(input.path("planFilePath").asString(""));
                case "Write", "Edit" -> {
                    String path = input.path("file_path").asString("");
                    if (!path.isEmpty()) {
                        files.add(path);
                        addPlan(path);
                    }
                }
                case "Bash" -> {
                    String command = input.path("command").asString("");
                    if (COMMIT_COMMAND.matcher(command).find()) {
                        commitAttempts++;
                        pendingCommits.put(part.path("id").asString(""), command);
                    }
                }
                default -> {
                    // reads and searches change nothing
                }
            }
        }

        private void commitResult(JsonNode part) {
            String id = part.path("tool_use_id").asString("");
            if (pendingCommits.remove(id) == null) {
                return;
            }
            JsonNode content = part.path("content");
            StringBuilder text = new StringBuilder(content.isString() ? content.asString("") : "");
            if (content.isArray()) {
                for (JsonNode piece : content) {
                    text.append(piece.path("text").asString("")).append('\n');
                }
            }
            Matcher matcher = COMMIT_LINE.matcher(text);
            while (matcher.find()) {
                commits.add(new Commit(matcher.group(1), matcher.group(2), matcher.group(3)));
            }
        }

        private void addPlan(String path) {
            if (path.contains("/plans/") && path.endsWith(".md")) {
                plans.add(path);
            }
        }

        Summary summary(String id) {
            return new Summary(id, start, last, title, firstPrompt, ending, lastAssistantText,
                    List.copyOf(plans), List.copyOf(commits), commitAttempts, List.copyOf(files), unparseable);
        }
    }

    /**
     * True when the final words of an assistant turn hand something back: a question, an offer, or named leftovers.
     * Looks only at the tail, so a "Should I" quoted early in a long report does not count.
     */
    static boolean endsWithQuestion(String text) {
        String trimmed = text.strip();
        if (trimmed.isEmpty()) {
            return false;
        }
        String tail = trimmed.substring(Math.max(0, trimmed.length() - TAIL_CHARS));
        return trimmed.endsWith("?") || PENDING_WORDING.matcher(tail).find();
    }

    static String abbreviate(String text, int max) {
        String flat = text.replaceAll("\\s+", " ").strip();
        return flat.length() <= max ? flat : flat.substring(0, max - 1) + "…";
    }
}
