package com.loudsight.syn.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.loudsight.syn.spi.ExitCodes;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The head against two real plugin class loaders: {@code demo} offers {@code test-echo}, which the test builtins already do. */
class SynPluginConflictTest {

    @TempDir
    Path repo;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    @BeforeEach
    void installDemo() throws IOException {
        PluginFixtures.install(repo.resolve("plugins"), "demo", "test-echo", "from-demo");
    }

    private int syn(String... args) {
        return Syn.run(args, repo, repo.resolve("plugins"), new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8), false);
    }

    private void configure(String properties) throws IOException {
        Files.createDirectories(repo.resolve(".syn"));
        Files.writeString(repo.resolve(".syn/config.properties"), properties);
    }

    private String stdout() {
        return out.toString(StandardCharsets.UTF_8);
    }

    private String stderr() {
        return err.toString(StandardCharsets.UTF_8);
    }

    @Test
    void aCollisionWithoutAnOverrideRunsNothingAndNamesBothPlugins() {
        assertEquals(ExitCodes.PLUGIN_CONFLICT, syn("test-echo", "hello"));

        assertTrue(stderr().contains("test-echo is provided by builtin and demo"), stderr());
        assertTrue(stderr().contains("--list-plugins"), stderr());
        assertFalse(stdout().contains("hello"), "the command must not have run: " + stdout());
        assertFalse(stdout().contains("from-demo"), stdout());
    }

    @Test
    void aCollisionAlsoStopsCommandsThatDoNotCollide() {
        assertEquals(ExitCodes.PLUGIN_CONFLICT, syn("catalog"));
    }

    @Test
    void anOverrideInTheConfigurationLetsThePluginWin() throws IOException {
        configure("override.test-echo=demo\n");

        assertEquals(ExitCodes.OK, syn("test-echo"));

        assertTrue(stdout().contains("from-demo"), stdout());
    }

    @Test
    void anOverrideCanKeepTheBuiltinInstead() throws IOException {
        configure("override.test-echo=builtin\n");

        assertEquals(ExitCodes.OK, syn("test-echo", "hello"));

        assertTrue(stdout().contains("hello"), stdout());
        assertFalse(stdout().contains("from-demo"), stdout());
    }

    @Test
    void anOverrideNamingAPluginThatIsNotThereIsStillAProblem() throws IOException {
        configure("override.test-echo=absent\n");

        assertEquals(ExitCodes.PLUGIN_CONFLICT, syn("test-echo"));
        assertTrue(stderr().contains("names a plugin that does not provide test-echo"), stderr());
    }

    @Test
    void aStaleOverrideIsAProblemEvenWhenNothingCollides() throws IOException {
        configure("override.test-echo=demo\noverride.test-boom=demo\n");

        assertEquals(ExitCodes.PLUGIN_CONFLICT, syn("test-echo"));
        assertTrue(stderr().contains("override.test-boom=demo overrides nothing: only builtin provides test-boom"), stderr());
        assertFalse(stderr().contains("test-echo is provided"), "the real override still resolves its collision: " + stderr());
    }

    @Test
    void listPluginsWorksDuringAConflictAndShowsIt() {
        assertEquals(ExitCodes.OK, syn("--list-plugins"));

        assertTrue(stdout().contains("demo: test-echo"), stdout());
        assertTrue(stdout().contains("builtin: catalog, test-echo"), stdout());
        assertTrue(stdout().contains("no command runs until these are fixed"), stdout());
    }

    @Test
    void listPluginsShowsWhoReplacesWhom() throws IOException {
        configure("override.test-echo=demo\n");

        assertEquals(ExitCodes.OK, syn("--list-plugins"));

        assertTrue(stdout().contains("test-echo: demo replaces builtin"), stdout());
    }

    @Test
    void helpStillWorksDuringAConflict() {
        assertEquals(ExitCodes.OK, syn("--help"));
    }

    @Test
    void aSystemPropertyOverridesForOneRun() {
        System.setProperty("syn.override.test-echo", "demo");
        try {
            assertEquals(ExitCodes.OK, syn("test-echo"));
            assertTrue(stdout().contains("from-demo"), stdout());
        } finally {
            System.clearProperty("syn.override.test-echo");
        }
    }
}
