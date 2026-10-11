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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScriptCreateScaffoldTest {

    private static final String SERVICES = "build/syn/syn-plugin-repo/src/main/resources/META-INF/services/com.loudsight.syn.spi.SynCommand";
    private static final String SOURCE = "build/syn/syn-plugin-repo/src/main/java/com/loudsight/syn/plugin/repo/MyNewThing.java";

    /** A repo laid out the way the superproject is: its conventions are configuration, not code. */
    private static Path repoWithPluginModule(Path temp) throws IOException {
        Files.createDirectories(temp.resolve("build/syn/syn-plugin-repo"));
        Fixtures.write(temp.resolve(".syn/config.properties"), """
                scaffold.plugin-dir=build/syn/syn-plugin-{plugin}
                scaffold.package=com.loudsight.syn.plugin.{plugin}
                scaffold.default-plugin=repo
                """);
        return temp;
    }

    @Test
    void scaffoldWritesTheCommandAndRegistersIt(@TempDir Path temp) throws Exception {
        Path root = repoWithPluginModule(temp);

        Result result = Fixtures.run(new ScriptCreateScaffold(), root, "my-new-thing", "--tier", "remote-mutate");

        assertEquals(ExitCodes.OK, result.exit(), result.stderr());
        String code = Files.readString(root.resolve(SOURCE), StandardCharsets.UTF_8);
        assertTrue(code.contains("package com.loudsight.syn.plugin.repo;"), code);
        assertTrue(code.contains("@Command(name = \"my-new-thing\""), code);
        assertTrue(code.contains("public final class MyNewThing implements SynCommand"), code);
        assertTrue(code.contains("return Tier.REMOTE_MUTATE;"), code);
        assertEquals("com.loudsight.syn.plugin.repo.MyNewThing\n", Files.readString(root.resolve(SERVICES), StandardCharsets.UTF_8));
    }

    @Test
    void scaffoldAppendsToAnExistingServicesFileEvenWithoutATrailingNewline(@TempDir Path temp) throws Exception {
        Path root = repoWithPluginModule(temp);
        Fixtures.write(root.resolve(SERVICES), "com.example.Existing");

        Fixtures.run(new ScriptCreateScaffold(), root, "my-new-thing");

        assertEquals("com.example.Existing\ncom.loudsight.syn.plugin.repo.MyNewThing\n",
                Files.readString(root.resolve(SERVICES), StandardCharsets.UTF_8));
    }

    /** The placeholders are how the conventions check forces the author to describe the command. */
    @Test
    void theScaffoldedCommandFailsTheConventionsUntilItIsFilledIn(@TempDir Path temp) throws Exception {
        Path root = repoWithPluginModule(temp);
        Fixtures.run(new ScriptCreateScaffold(), root, "my-new-thing");
        String code = Files.readString(root.resolve(SOURCE), StandardCharsets.UTF_8);

        assertTrue(code.contains("description = \"<outcome, not mechanism> — use when <situation>\""), code);
        assertTrue(code.contains("List.of(\"<the words someone would search for>\")"), code);
    }

    @Test
    void scaffoldRefusesAnExistingNameBadInputAndAnUnknownPlugin(@TempDir Path temp) throws Exception {
        Path root = repoWithPluginModule(temp);
        Fixtures.run(new ScriptCreateScaffold(), root, "my-new-thing");

        assertEquals(ExitCodes.FAILURE, Fixtures.run(new ScriptCreateScaffold(), root, "my-new-thing").exit());
        assertEquals(ExitCodes.FAILURE, Fixtures.run(new ScriptCreateScaffold(), root, List.of(command("my-other-thing")), false, "my-other-thing").exit());
        assertEquals(ExitCodes.USAGE, Fixtures.run(new ScriptCreateScaffold(), root, "single").exit());
        assertEquals(ExitCodes.USAGE, Fixtures.run(new ScriptCreateScaffold(), root, "a-b", "--tier", "sudo").exit());
        assertEquals(ExitCodes.USAGE, Fixtures.run(new ScriptCreateScaffold(), root, "a-b", "--plugin", "nope").exit());
    }

    @Test
    void scaffoldUnderDryRunWritesNothing(@TempDir Path temp) throws Exception {
        Path root = repoWithPluginModule(temp);

        Result result = Fixtures.run(new ScriptCreateScaffold(), root, List.of(), true, "my-new-thing");

        assertEquals(ExitCodes.OK, result.exit());
        assertTrue(result.stdout().contains("[dry-run]"), result.stdout());
        assertFalse(Files.exists(root.resolve(SOURCE)));
    }

    @Test
    void withoutConfigurationAPluginIsNamedAndGoesUnderPluginsInTheExamplePackage(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("plugins/demo"));

        Result result = Fixtures.run(new ScriptCreateScaffold(), root, "my-new-thing", "--plugin", "demo");

        assertEquals(ExitCodes.OK, result.exit(), result.stderr());
        String code = Files.readString(root.resolve("plugins/demo/src/main/java/com/example/syn/demo/MyNewThing.java"), StandardCharsets.UTF_8);
        assertTrue(code.contains("package com.example.syn.demo;"), code);
        assertEquals("com.example.syn.demo.MyNewThing\n",
                Files.readString(root.resolve("plugins/demo/src/main/resources/META-INF/services/com.loudsight.syn.spi.SynCommand"), StandardCharsets.UTF_8));
    }

    @Test
    void withoutAPluginAndWithoutADefaultItSaysHowToChooseOne(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("plugins/demo"));

        Result result = Fixtures.run(new ScriptCreateScaffold(), root, "my-new-thing");

        assertEquals(ExitCodes.USAGE, result.exit());
        assertTrue(result.stderr().contains("scaffold.default-plugin"), result.stderr());
    }

    @Test
    void aPluginNameThatCouldEscapeTheRepoOrBreakThePackageIsRefusedAndNothingIsWritten(@TempDir Path temp) throws Exception {
        Path root = repoWithPluginModule(temp);

        for (String bad : List.of("../x", "My-Plugin", "a.b", "1abc")) {
            assertEquals(ExitCodes.USAGE, Fixtures.run(new ScriptCreateScaffold(), root, "my-new-thing", "--plugin", bad).exit(), bad);
        }
        assertFalse(Files.exists(root.resolve(SOURCE)));
    }

    @Test
    void theNamedPluginBeatsTheConfiguredDefault(@TempDir Path temp) throws Exception {
        Path root = repoWithPluginModule(temp);
        Files.createDirectories(root.resolve("build/syn/syn-plugin-other"));

        Fixtures.run(new ScriptCreateScaffold(), root, "my-new-thing", "--plugin", "other");

        assertTrue(Files.exists(root.resolve("build/syn/syn-plugin-other/src/main/java/com/loudsight/syn/plugin/other/MyNewThing.java")));
        assertFalse(Files.exists(root.resolve(SOURCE)));
    }

    private static CommandDescriptor command(String name) {
        return Fixtures.command(name, "read-only", "does a thing — use when testing", "thing");
    }
}
