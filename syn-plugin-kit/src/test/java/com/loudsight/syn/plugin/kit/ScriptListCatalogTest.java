package com.loudsight.syn.plugin.kit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.loudsight.syn.plugin.kit.Fixtures.Result;
import com.loudsight.syn.spi.CommandDescriptor;
import com.loudsight.syn.spi.ExitCodes;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScriptListCatalogTest {

    private static CommandDescriptor command(String name) {
        return Fixtures.command(name, "read-only", "does a thing — use when testing", "thing");
    }

    private static void shellScript(Path root, String name, String purpose) throws IOException {
        Path script = Fixtures.write(root.resolve("scripts/" + name), String.format(Locale.ROOT, """
                #!/usr/bin/env bash
                # @purpose: %s
                # @keywords: a, b
                # @tier: read-only
                # @usage: scripts/%s
                # @example: scripts/%s
                """, purpose, name, name));
        assertTrue(script.toFile().setExecutable(true));
    }

    @Test
    void theCatalogIndexListsScriptsThenCommandsAndRoundTripsThroughCheck(@TempDir Path root) throws Exception {
        shellScript(root, "disk-clean-all.sh", "tidy disk — use when full");
        List<CommandDescriptor> commands = List.of(command("a-b"), command("c-d"));

        assertEquals(ExitCodes.FAILURE, Fixtures.run(new ScriptListCatalog(), root, commands, false, "--check").exit(), "no index yet");
        assertEquals(ExitCodes.OK, Fixtures.run(new ScriptListCatalog(), root, commands, false, "--markdown").exit());
        assertEquals(ExitCodes.OK, Fixtures.run(new ScriptListCatalog(), root, commands, false, "--check").exit());

        String index = Files.readString(root.resolve("scripts/INDEX.md"), StandardCharsets.UTF_8);
        assertTrue(index.contains("| [`disk-clean-all.sh`](disk-clean-all.sh) | read-only | tidy disk — use when full | a, b |"), index);
        assertTrue(index.contains("| `syn a-b` | read-only | does a thing — use when testing | thing |"), index);
        assertTrue(index.indexOf("disk-clean-all.sh") < index.indexOf("`syn a-b`"), "scripts come before commands");

        assertEquals(ExitCodes.FAILURE, Fixtures.run(new ScriptListCatalog(), root, List.of(command("a-b")), false, "--check").exit(),
                "a command was removed, so the index is stale");
    }

    @Test
    void theCatalogRefusesBothModesAtOnce(@TempDir Path root) throws Exception {
        assertEquals(ExitCodes.USAGE, Fixtures.run(new ScriptListCatalog(), root, "--markdown", "--check").exit());
    }

    @Test
    void theCatalogPrintsEveryEntryWithItsTier(@TempDir Path root) throws Exception {
        shellScript(root, "disk-clean-all.sh", "tidy disk — use when full");

        Result result = Fixtures.run(new ScriptListCatalog(), root, List.of(command("a-b")), false);

        assertEquals(ExitCodes.OK, result.exit());
        assertTrue(result.stdout().contains("syn a-b"), result.stdout());
        assertTrue(result.stdout().contains("disk-clean-all.sh"), result.stdout());
        assertTrue(result.stdout().contains("tidy disk"), result.stdout());
    }

    @Test
    void aConfiguredIndexPathAndScriptDirectoryAreUsedAndTheLinkIsRelativeToTheIndex(@TempDir Path root) throws Exception {
        Fixtures.write(root.resolve(".syn/config.properties"), "catalog.index=docs/TOOLS.md\ncatalog.script-dirs=tools\n");
        Fixtures.write(root.resolve("tools/a-do-it.sh"), "#!/usr/bin/env bash\n# @purpose: does it — use when needed\n# @keywords: x\n# @tier: read-only\n");
        Fixtures.write(root.resolve("scripts/ignored-now.sh"), "# @tier: read-only\n");

        assertEquals(ExitCodes.OK, Fixtures.run(new ScriptListCatalog(), root, List.of(), false, "--markdown").exit());

        String index = Files.readString(root.resolve("docs/TOOLS.md"), StandardCharsets.UTF_8);
        assertTrue(index.contains("[`a-do-it.sh`](../tools/a-do-it.sh)"), index);
        assertFalse(index.contains("ignored-now"), index);
        assertFalse(Files.exists(root.resolve("scripts/INDEX.md")));
        assertEquals(ExitCodes.OK, Fixtures.run(new ScriptListCatalog(), root, List.of(), false, "--check").exit());
    }
}
