package com.loudsight.syn.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.loudsight.syn.spi.CommandDescriptor;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CatalogIndexTest {

    private static final List<CommandDescriptor> COMMANDS = List.of(
            new CommandDescriptor("a-b", "a", "read-only", "does a thing — use when testing", List.of("thing"), "builtin"));

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(java.util.Objects.requireNonNull(file.getParent()));
        Files.writeString(file, content);
    }

    @Test
    void withoutConfigurationTheHeaderIsGenericAndNamesNothingOfAnyRepo(@TempDir Path root) {
        String index = CatalogIndex.markdown(root, COMMANDS);

        assertTrue(index.startsWith("# Script library"), index);
        assertTrue(index.contains("| Command or script | Tier | Purpose | Keywords |"), index);
        assertTrue(index.contains("| `syn a-b` | read-only | does a thing — use when testing | thing |"), index);
        assertFalse(index.contains("legacy-scripts"), index);
        assertFalse(index.contains("build/syn"), index);
    }

    @Test
    void aConfiguredHeaderFileReplacesTheTextAboveTheTableOnly(@TempDir Path root) throws IOException {
        write(root.resolve(".syn/header.md"), "# My tools\n\nUse them.\n\n\n");
        write(root.resolve(".syn/config.properties"), "catalog.header-file=.syn/header.md\n");

        String index = CatalogIndex.markdown(root, COMMANDS);

        assertTrue(index.startsWith("# My tools\n\nUse them.\n\n| Command or script | Tier |"), index);
    }

    @Test
    void aHeaderFileThatCannotBeReadIsAnErrorNotTheDefault(@TempDir Path root) throws IOException {
        write(root.resolve(".syn/config.properties"), "catalog.header-file=missing.md\n");

        UncheckedIOException e = assertThrows(UncheckedIOException.class, () -> CatalogIndex.markdown(root, COMMANDS));
        assertTrue(String.valueOf(e.getMessage()).contains("catalog.header-file"), e.getMessage());
    }

    @Test
    void theIndexIsCurrentOnlyWhenItMatchesWhatWouldBeGeneratedNow(@TempDir Path root) throws IOException {
        assertFalse(CatalogIndex.isCurrent(root, COMMANDS), "no index yet");
        write(root.resolve("scripts/INDEX.md"), CatalogIndex.markdown(root, COMMANDS));
        assertTrue(CatalogIndex.isCurrent(root, COMMANDS));
        assertFalse(CatalogIndex.isCurrent(root, List.of()), "a command was removed");
    }

    @Test
    void exemptScriptsAndOtherDirectoriesAreConfigurable(@TempDir Path root) throws IOException {
        write(root.resolve("scripts/env.sh"), "# env\n");
        write(root.resolve("scripts/tool.sh"), "# tool\n");
        write(root.resolve("hooks/pre-commit.sh"), "# hook\n");
        com.loudsight.scriptkit.ScriptLibrary defaults = com.loudsight.scriptkit.ScriptLibrary.load(root);
        assertEquals(List.of("scripts/env.sh", "scripts/tool.sh"), defaults.catalogFiles());

        write(root.resolve(".syn/config.properties"), "catalog.script-dirs=scripts, hooks\ncatalog.script-exempt=scripts/env.sh\n");
        com.loudsight.scriptkit.ScriptLibrary configured = com.loudsight.scriptkit.ScriptLibrary.load(root);

        assertEquals(List.of("hooks/pre-commit.sh", "scripts/tool.sh"), configured.catalogFiles());
        assertEquals(List.of("scripts", "hooks"), configured.dirs());
    }
}
