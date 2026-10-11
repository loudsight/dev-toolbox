package com.loudsight.syn.plugin.kit;

import com.loudsight.scriptkit.Cmd;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Collects everything a commit, a push or a tree would publish, as small pieces of text each labelled with where it
 * came from, and checks every piece against the private denylist and the secret tripwires.
 *
 * <p>"Everything" is wider than file contents, because a private string leaks just as well through a file name, a commit
 * message, an author or committer identity, a symlink target or a submodule URL. The first three are collected
 * explicitly; the symlink target and the submodule URL are added lines of the diff like any other. Only added lines
 * are scanned: a removed line is not being published, and one an earlier unpushed commit added is scanned in its own
 * commit.
 */
final class PublishScan {

    /** One piece of publishable text and where it is. */
    record Item(String where, String text) {
    }

    /** A hit: where, and what rule it broke, named without repeating the matched text. */
    record Finding(String where, String what) {
    }

    private static final Pattern NEW_START = Pattern.compile("\\+(\\d+)");
    private static final Set<String> SKIPPED_DIRS = Set.of(".git", "target", "node_modules", ".idea");
    private static final long MAX_FILE_BYTES = 2L * 1024 * 1024;
    private static final int BINARY_PROBE_BYTES = 8000;

    private final Path repo;

    PublishScan(Path repo) {
        this.repo = repo;
    }

    /** The staged change: file names, then the added lines of {@code git diff --cached}. */
    List<Item> staged() {
        List<Item> items = new ArrayList<>();
        addDiff(items, git("diff", "--cached", "--no-color", "--unified=0", "--no-ext-diff", "--no-renames"), "staged ");
        return items;
    }

    /** Each commit {@code revArgs} selects (anything {@code git rev-list} takes): message, identities, names, added lines. */
    List<Item> commits(List<String> revArgs) {
        List<String> listing = new ArrayList<>(List.of("rev-list", "--reverse"));
        listing.addAll(revArgs);
        List<Item> items = new ArrayList<>();
        for (String sha : git(listing.toArray(String[]::new)).lines().toList()) {
            String shortSha = sha.substring(0, Math.min(sha.length(), 8));
            items.add(new Item("commit " + shortSha + " message/identity",
                    git("log", "-1", "--format=%an%n%ae%n%cn%n%ce%n%B", sha)));
            addDiff(items, git("show", "--format=", "--no-color", "--unified=0", "--no-ext-diff", "--no-renames", sha),
                    "commit " + shortSha + " ");
        }
        return items;
    }

    /** A directory tree (or one file): every path, then every line of every text file, skipping build output and VCS data. */
    static List<Item> tree(Path root) throws IOException {
        List<Item> items = new ArrayList<>();
        if (Files.isRegularFile(root)) {
            addFile(items, Objects.requireNonNullElse(root.getFileName(), root).toString(), root);
            return items;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                Path name = dir.getFileName();
                return !dir.equals(root) && name != null && SKIPPED_DIRS.contains(name.toString())
                        ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                addFile(items, root.relativize(file).toString(), file);
                return FileVisitResult.CONTINUE;
            }
        });
        return items;
    }

    /** Checks every item; the findings are in order, with repeats removed. */
    static List<Finding> scan(List<Item> items, PrivateTerms terms) {
        Set<Finding> findings = new LinkedHashSet<>();
        for (Item item : items) {
            for (String name : SecretTripwires.hits(item.text())) {
                findings.add(new Finding(item.where(), "secret tripwire: " + name));
            }
            for (int line : terms.hits(item.text())) {
                findings.add(new Finding(item.where(), "denylist entry on line " + line));
            }
        }
        return List.copyOf(findings);
    }

    private String git(String... args) {
        List<String> command = new ArrayList<>(List.of("git", "-c", "core.quotepath=off"));
        command.addAll(List.of(args));
        return Cmd.of(command).dir(repo).discardErr().capture().stdout();
    }

    private static void addFile(List<Item> items, String relative, Path file) throws IOException {
        items.add(new Item(relative, relative));
        if (Files.isSymbolicLink(file)) {
            items.add(new Item(relative + " (symlink target)", Files.readSymbolicLink(file).toString()));
            return;
        }
        if (Files.size(file) > MAX_FILE_BYTES) {
            return;
        }
        byte[] bytes = Files.readAllBytes(file);
        for (int i = 0; i < Math.min(bytes.length, BINARY_PROBE_BYTES); i++) {
            if (bytes[i] == 0) {
                return;
            }
        }
        List<String> lines = new String(bytes, StandardCharsets.UTF_8).lines().toList();
        for (int i = 0; i < lines.size(); i++) {
            items.add(new Item(relative + ":" + (i + 1), lines.get(i)));
        }
    }

    /**
     * Reads a unified diff as a small state machine, because a line's meaning depends on where it is: before the first
     * hunk a {@code +++} line is a header, after it the same text is an added line that starts with {@code ++}.
     */
    private static void addDiff(List<Item> items, String patch, String prefix) {
        boolean inHeader = false;
        String file = "?";
        int line = 0;
        for (String text : patch.split("\n", -1)) {
            if (text.startsWith("diff --git ") || text.startsWith("diff --cc ")) {
                inHeader = true;
                file = "?";
                items.add(new Item(prefix + "file name", text));
            } else if (inHeader) {
                if (text.startsWith("+++ ")) {
                    String name = text.substring(4);
                    file = name.startsWith("b/") ? name.substring(2) : name;
                } else if (text.startsWith("@@")) {
                    inHeader = false;
                    line = firstNewLine(text);
                }
            } else if (text.startsWith("@@")) {
                line = firstNewLine(text);
            } else if (!text.isEmpty() && text.charAt(0) == '+') {
                items.add(new Item(prefix + file + ":" + line, text.substring(1)));
                line++;
            }
        }
    }

    private static int firstNewLine(String hunkHeader) {
        Matcher matcher = NEW_START.matcher(hunkHeader);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
    }
}
