package com.loudsight.syn.core;

import com.loudsight.scriptkit.Cmd;
import com.loudsight.scriptkit.Shell;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Whether the deploy host's checkout is the code this machine has, so {@code --on-remote} never silently runs
 * something other than what was just edited. {@link #warning} is empty when they match and a loud block when not.
 */
public final class SyncCheck {

    /** Run in a repo root, locally and on the host alike, so both sides are described by the same commands. */
    static final String STATE_SCRIPT = "git rev-parse HEAD; echo @@DIRTY; git status --porcelain --untracked-files=no;"
            + " echo @@SUBMODULES; git submodule status --recursive";

    private SyncCheck() {
    }

    /** A checkout as the comparison sees it: its commit, its modified tracked files, and each submodule's commit. */
    record State(String head, List<String> dirty, Map<String, String> submodules) {

        static State parse(String output) {
            String head = "";
            List<String> dirty = new ArrayList<>();
            Map<String, String> submodules = new LinkedHashMap<>();
            int section = 0;
            for (String line : output.lines().filter(text -> !text.isBlank()).toList()) {
                if ("@@DIRTY".equals(line)) {
                    section = 1;
                } else if ("@@SUBMODULES".equals(line)) {
                    section = 2;
                } else if (section == 0) {
                    head = line.strip();
                } else if (section == 1) {
                    dirty.add(line.strip());
                } else {
                    // " <sha> path (describe)": a leading '-', '+' or 'U' marks uninitialised, moved or conflicted.
                    String entry = line.substring(1).strip();
                    int shaEnd = entry.indexOf(' ');
                    if (shaEnd > 0) {
                        String rest = entry.substring(shaEnd + 1).strip();
                        int pathEnd = rest.indexOf(' ');
                        submodules.put(pathEnd < 0 ? rest : rest.substring(0, pathEnd), entry.substring(0, shaEnd));
                    }
                }
            }
            return new State(head, dirty, submodules);
        }
    }

    /**
     * What differs: {@code loud} means the host is certainly running other code (another commit, another submodule
     * commit, or hand edits on the host); {@code notes} are the working-tree edits here that are not on the host yet.
     * Both leave out {@code ignored} paths.
     */
    record Findings(List<String> loud, List<String> notes) {

        boolean isEmpty() {
            return loud.isEmpty() && notes.isEmpty();
        }
    }

    static Findings differences(State local, State remote, Set<String> ignored) {
        return differences(local, remote, ignored, "the host");
    }

    /** As {@link #differences(State, State, Set)}, naming the other side of the comparison {@code other} in the text. */
    static Findings differences(State local, State remote, Set<String> ignored, String other) {
        List<String> loud = new ArrayList<>();
        if (!local.head().equals(remote.head())) {
            loud.add("superproject: this machine is at " + abbreviate(local.head()) + ", " + other + " is at " + abbreviate(remote.head()));
        }
        Set<String> paths = new TreeSet<>(local.submodules().keySet());
        paths.addAll(remote.submodules().keySet());
        for (String path : paths) {
            String here = local.submodules().get(path);
            String there = remote.submodules().get(path);
            if (!isIgnored(path, ignored) && (here == null || there == null || !here.equals(there))) {
                loud.add("submodule " + path + ": this machine " + (here == null ? "has none" : abbreviate(here))
                        + ", the host " + (there == null ? "has none" : abbreviate(there)));
            }
        }
        List<String> hostEdits = relevant(remote.dirty(), ignored);
        if (!hostEdits.isEmpty()) {
            loud.add(hostEdits.size() + " modified tracked file(s) on the host: " + preview(hostEdits));
        }
        List<String> notes = new ArrayList<>();
        List<String> localEdits = relevant(local.dirty(), ignored);
        if (!localEdits.isEmpty()) {
            notes.add(localEdits.size() + " uncommitted file(s) here are not on the host: " + preview(localEdits));
        }
        return new Findings(loud, notes);
    }

    /** Formats the loud findings as the block printed before a remote run; empty when there are none. */
    static List<String> block(String host, List<String> differences) {
        return block("WARNING: " + host + " is NOT running the code on this machine - results may not be what you expect",
                differences, "Nothing was pulled: push, then git pull and git submodule update on the host to run what you have here.");
    }

    static List<String> block(String headline, List<String> differences, String footer) {
        if (differences.isEmpty()) {
            return List.of();
        }
        String bar = "!".repeat(78);
        List<String> lines = new ArrayList<>();
        lines.add(bar);
        lines.add("!! " + headline);
        for (String difference : differences) {
            lines.add("!!   " + difference);
        }
        lines.add("!! " + footer);
        lines.add(bar);
        return lines;
    }

    /** The lines to print before running on {@code host}: the loud block, then any notes; empty when they match. */
    public static List<String> warning(Path repoRoot, String host, String checkout, Set<String> ignored) {
        Cmd.Result local = Cmd.of("bash", "-c", STATE_SCRIPT).dir(repoRoot).capture();
        Cmd.Result remote = Cmd.of(RemoteExec.sshCommand(host, "cd " + Shell.quote(checkout) + " && " + STATE_SCRIPT))
                .capture();
        if (!local.ok() || !remote.ok()) {
            return block("WARNING: could not compare this machine with " + host + " - it may not be running the code here",
                    List.of("git state unreadable (exit " + local.exitCode() + " here, " + remote.exitCode() + " there)"),
                    "Running anyway.");
        }
        Findings findings = differences(State.parse(local.stdout()), State.parse(remote.stdout()), ignored);
        List<String> lines = new ArrayList<>(block(host, findings.loud()));
        findings.notes().forEach(note -> lines.add("syn: note: " + note));
        return lines;
    }

    /**
     * The lines to print before a {@code --sync} run. The host is about to pull origin/master, so what matters is not
     * how the host stands now but whether origin/master is the code here: a commit not pushed yet, or one pushed from
     * elsewhere, is loud, and uncommitted local edits are a note, as without {@code --sync}.
     */
    public static List<String> warningBeforeSync(Path repoRoot, Set<String> ignored) {
        Cmd.Result local = Cmd.of("bash", "-c", STATE_SCRIPT).dir(repoRoot).capture();
        Cmd.Result origin = Cmd.of("git", "ls-remote", "origin", "refs/heads/master").dir(repoRoot).capture();
        String originHead = origin.ok() ? firstField(origin.stdout()) : "";
        if (!local.ok() || originHead.isEmpty()) {
            return block("WARNING: --sync could not compare this machine with origin/master",
                    List.of("git state unreadable (exit " + local.exitCode() + " here, " + origin.exitCode() + " for origin)"),
                    "The host will pull origin/master whatever is here.");
        }
        State here = State.parse(local.stdout());
        // Compare the superproject commit only: the pre-push hook refuses a pointer to a submodule commit that is not pushed.
        Findings found = differences(here, new State(originHead, List.of(), here.submodules()), ignored, "origin/master (what the host pulls)");
        List<String> lines = new ArrayList<>(block("WARNING: --sync will run origin/master, which is NOT the code on this machine",
                found.loud(), "Push first (or pull here) so the host runs what you expect."));
        found.notes().forEach(note -> lines.add("syn: note: " + note));
        return lines;
    }

    /** The entries of a {@code git status --porcelain} listing whose path is not under an ignored path. */
    private static List<String> relevant(List<String> dirty, Set<String> ignored) {
        return dirty.stream().filter(entry -> !isIgnored(pathOf(entry), ignored)).toList();
    }

    /** {@code "M path"} to {@code path}; a rename ({@code "R old -> new"}) is judged by its new path. */
    private static String pathOf(String entry) {
        String path = entry.substring(entry.indexOf(' ') + 1).strip();
        int arrow = path.indexOf(" -> ");
        return arrow < 0 ? path : path.substring(arrow + 4);
    }

    private static boolean isIgnored(String path, Set<String> ignored) {
        return ignored.stream().anyMatch(prefix -> path.equals(prefix) || path.startsWith(prefix + "/"));
    }

    /** {@code "<sha>\trefs/heads/master"} to {@code <sha>}; empty for blank output. */
    private static String firstField(String line) {
        String text = line.strip();
        int end = 0;
        while (end < text.length() && !Character.isWhitespace(text.charAt(end))) {
            end++;
        }
        return text.substring(0, end);
    }

    private static String abbreviate(String sha) {
        return sha.length() > 10 ? sha.substring(0, 10) : sha;
    }

    private static String preview(List<String> files) {
        List<String> shown = files.subList(0, Math.min(3, files.size()));
        return String.join(", ", shown) + (files.size() > shown.size() ? ", ..." : "");
    }
}
