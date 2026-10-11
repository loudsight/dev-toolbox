package com.loudsight.syn.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SynConfigTest {

    @AfterEach
    void clearProperty() {
        System.clearProperty("syn.plugins.dir");
    }

    private static void write(Path root, String content) throws IOException {
        Files.createDirectories(root.resolve(SynConfig.DIRECTORY));
        Files.writeString(root.resolve(SynConfig.DIRECTORY).resolve(SynConfig.FILE), content);
    }

    @Test
    void aMissingFileIsAnEmptyConfiguration(@TempDir Path root) {
        assertTrue(SynConfig.load(root).get("plugins.dir").isEmpty());
        assertTrue(SynConfig.load(root).path("plugins.dir").isEmpty());
    }

    @Test
    void theFileSuppliesAValue(@TempDir Path root) throws IOException {
        write(root, "plugins.dir = tools/plugins\n");

        assertEquals(Optional.of("tools/plugins"), SynConfig.load(root).get("plugins.dir"));
    }

    @Test
    void aSystemPropertyBeatsTheFile(@TempDir Path root) throws IOException {
        write(root, "plugins.dir=from-file\n");
        System.setProperty("syn.plugins.dir", "from-property");

        assertEquals(Optional.of("from-property"), SynConfig.load(root).get("plugins.dir"));
    }

    @Test
    void aBlankValueCountsAsAbsentAndFallsThrough(@TempDir Path root) throws IOException {
        write(root, "plugins.dir=from-file\n");
        System.setProperty("syn.plugins.dir", "  ");

        assertEquals(Optional.of("from-file"), SynConfig.load(root).get("plugins.dir"));
    }

    @Test
    void aRelativePathIsResolvedAgainstTheRepoRootAndAnAbsoluteOneIsKept(@TempDir Path root) throws IOException {
        write(root, "plugins.dir=tools/../plugins\n");
        assertEquals(Optional.of(root.resolve("plugins")), SynConfig.load(root).path("plugins.dir"));

        Path absolute = root.resolve("elsewhere").toAbsolutePath();
        System.setProperty("syn.plugins.dir", absolute.toString());
        assertEquals(Optional.of(absolute), SynConfig.load(root).path("plugins.dir"));
    }

    @Test
    void aListSplitsOnCommasAndDropsBlanksAndIsEmptyWhenAbsent(@TempDir Path root) throws IOException {
        assertEquals(List.of(), SynConfig.load(root).list("kb.retired-roots"));

        write(root, "kb.retired-roots = docs, notebook ,,\n");

        assertEquals(List.of("docs", "notebook"), SynConfig.load(root).list("kb.retired-roots"));
    }

    @Test
    void withPrefixCollectsTheFilesKeysAndTheSystemPropertiesWhichWin(@TempDir Path root) throws IOException {
        write(root, "override.a=from-file\noverride.b=kept\nother=ignored\noverride.=nameless\n");
        System.setProperty("syn.override.a", "from-property");
        System.setProperty("syn.override.c", "added");
        try {
            assertEquals(java.util.Map.of("a", "from-property", "b", "kept", "c", "added"), SynConfig.load(root).withPrefix("override."));
        } finally {
            System.clearProperty("syn.override.a");
            System.clearProperty("syn.override.c");
        }
    }

    @Test
    void aConfigurationThatExistsButCannotBeReadIsAnErrorNotADefault(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve(SynConfig.DIRECTORY).resolve(SynConfig.FILE));

        UncheckedIOException e = assertThrows(UncheckedIOException.class, () -> SynConfig.load(root));
        assertTrue(String.valueOf(e.getMessage()).contains(SynConfig.FILE), e.getMessage());
    }
}
