package com.loudsight.syn.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ContentHasherTest {

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(Objects.requireNonNull(file.getParent()));
        Files.writeString(file, content);
    }

    /** The algorithm auto-agent's ModuleContentHasher uses, spelled out independently: path, NUL, bytes, per sorted file. */
    @Test
    void digestsSortedRelativePathsNulAndBytes(@TempDir Path root) throws IOException, NoSuchAlgorithmException {
        write(root.resolve("m/b.txt"), "second");
        write(root.resolve("m/a.txt"), "first");
        write(root.resolve("m/sub/c.txt"), "third");

        MessageDigest expected = MessageDigest.getInstance("SHA-256");
        for (String[] entry : new String[][] {{"a.txt", "first"}, {"b.txt", "second"}, {"sub/c.txt", "third"}}) {
            expected.update(entry[0].getBytes(StandardCharsets.UTF_8));
            expected.update((byte) 0);
            expected.update(entry[1].getBytes(StandardCharsets.UTF_8));
        }

        assertEquals(HexFormat.of().formatHex(expected.digest()), new ContentHasher(root).hashModule("m"));
    }

    @Test
    void ignoresBuildOutputAndHiddenDirectories(@TempDir Path root) throws IOException {
        write(root.resolve("m/src/A.java"), "class A {}");
        String clean = new ContentHasher(root).hashModule("m");

        write(root.resolve("m/target/classes/A.class"), "bytes");
        write(root.resolve("m/node_modules/x/index.js"), "x");
        write(root.resolve("m/.git/HEAD"), "ref");
        write(root.resolve("m/.idea/workspace.xml"), "ide");
        write(root.resolve("m/src/test/integration/playwright/test-results/trace.zip"), "27MB");

        assertEquals(clean, new ContentHasher(root).hashModule("m"));
    }

    @Test
    void dotMetaCountsBecauseItAffectsBuilds(@TempDir Path root) throws IOException {
        write(root.resolve("m/src/A.java"), "class A {}");
        String before = new ContentHasher(root).hashModule("m");

        write(root.resolve("m/.meta/flags"), "x");

        assertNotEquals(before, new ContentHasher(root).hashModule("m"));
    }

    @Test
    void aChangedFileChangesTheHash(@TempDir Path root) throws IOException {
        write(root.resolve("m/A.java"), "one");
        String before = new ContentHasher(root).hashModule("m");

        write(root.resolve("m/A.java"), "two");

        assertNotEquals(before, new ContentHasher(root).hashModule("m"));
    }

    /** A checkout under a directory named dist (or any dot-directory) must not exclude the whole repository. */
    @Test
    void anExcludedNameAboveTheProjectRootIsNotJudged(@TempDir Path temp) throws IOException {
        Path root = temp.resolve("dist/.work/repo");
        write(root.resolve("m/A.java"), "class A {}");

        assertNotEquals(ContentHasher.NO_CONTENT_HASH, new ContentHasher(root).hashModule("m"));
        assertEquals(new ContentHasher(temp.resolve("elsewhere")).hashModule("."), ContentHasher.NO_CONTENT_HASH);
        write(temp.resolve("elsewhere/m/A.java"), "class A {}");
        assertEquals(new ContentHasher(root).hashModule("m"), new ContentHasher(temp.resolve("elsewhere")).hashModule("m"));
    }

    @Test
    void aMissingModuleHashesToNoHash(@TempDir Path root) {
        assertEquals(ContentHasher.NO_CONTENT_HASH, new ContentHasher(root).hashModule("absent"));
    }

    @Test
    void severalModulesHashTogetherAndAMissingOneSpoilsTheLot(@TempDir Path root) throws IOException {
        write(root.resolve("a/A.java"), "a");
        write(root.resolve("b/B.java"), "b");
        ContentHasher hasher = new ContentHasher(root);
        String both = hasher.hashModules(List.of("a", "b")).orElseThrow();

        write(root.resolve("b/B.java"), "changed");

        assertNotEquals(both, hasher.hashModules(List.of("a", "b")).orElseThrow());
        assertTrue(hasher.hashModules(List.of("a", "absent")).isEmpty());
    }
}
