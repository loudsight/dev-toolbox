package com.loudsight.syn.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.loudsight.syn.spi.SynCommand;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.tools.JavaCompiler;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PluginLoaderTest {

    private static final ClassLoader HEAD = Thread.currentThread().getContextClassLoader();

    /** The head's own (test-registered) providers are always "builtin"; these tests are about everything else. */
    private static List<PluginLoader.Provided> pluginsOnly(Path pluginsDir, List<String> warnings) {
        return PluginLoader.discover(pluginsDir, HEAD, warnings::add).stream()
                .filter(provided -> !PluginLoader.BUILTIN.equals(provided.origin()))
                .toList();
    }

    private static final class Source extends SimpleJavaFileObject {

        private final String code;

        Source(String className, String code) {
            super(URI.create("string:///" + className.replace('.', '/') + ".java"), Kind.SOURCE);
            this.code = code;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return code;
        }
    }

    /**
     * {@code <plugins>/<name>/current/libs/<name>.jar} holding a command class compiled here, which
     * exists nowhere on the test classpath, and a services file naming it.
     */
    private static void installPlugin(Path plugins, String name) throws IOException {
        installPlugin(plugins, name, name);
    }

    /** As above, with the command class in package {@code plugin.<pkg>}, so two plugins can ship a class of the same name. */
    private static void installPlugin(Path plugins, String name, String pkg) throws IOException {
        String className = "plugin." + pkg + ".Command";
        String code = "package plugin." + pkg + ";"
                + "public final class Command implements com.loudsight.syn.spi.SynCommand {"
                + "  public com.loudsight.syn.spi.Tier tier() { return com.loudsight.syn.spi.Tier.READ_ONLY; }"
                + "  public int run(com.loudsight.syn.spi.Context context) { return 0; }"
                + "}";
        Path classes = plugins.resolveSibling("classes-" + name);
        Files.createDirectories(classes);
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "tests need a JDK, not a JRE");
        String spi = SynCommand.class.getProtectionDomain().getCodeSource().getLocation().getPath();
        Boolean compiled = compiler.getTask(null, null, null, List.of("-classpath", spi, "-d", classes.toString()), null, List.of(new Source(className, code))).call();
        assertTrue(compiled, "could not compile the test plugin");

        Path libs = plugins.resolve(name).resolve("current/libs");
        Files.createDirectories(libs);
        String classFile = className.replace('.', '/') + ".class";
        try (OutputStream out = Files.newOutputStream(libs.resolve(name + ".jar")); JarOutputStream jar = new JarOutputStream(out)) {
            jar.putNextEntry(new JarEntry(classFile));
            jar.write(Files.readAllBytes(classes.resolve(classFile)));
            jar.closeEntry();
            jar.putNextEntry(new JarEntry("META-INF/services/" + SynCommand.class.getName()));
            jar.write((className + "\n").getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
    }

    @Test
    void findsACommandInAPluginAndNamesThePluginAsItsOrigin(@TempDir Path temp) throws IOException {
        Path plugins = temp.resolve("plugins");
        installPlugin(plugins, "demo");
        List<String> warnings = new ArrayList<>();

        List<PluginLoader.Provided> found = pluginsOnly(plugins, warnings);

        assertEquals(1, found.size());
        assertEquals("demo", found.get(0).origin());
        assertEquals("plugin.demo.Command", found.get(0).command().getClass().getName());
        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    @Test
    void eachPluginGetsItsOwnClassLoader(@TempDir Path temp) throws IOException {
        Path plugins = temp.resolve("plugins");
        installPlugin(plugins, "one", "shared");
        installPlugin(plugins, "two", "shared");

        List<PluginLoader.Provided> found = pluginsOnly(plugins, new ArrayList<>());

        assertEquals(List.of("one", "two"), found.stream().map(PluginLoader.Provided::origin).toList());
        // Same class name, two distinct classes: neither plugin's copy shadows the other.
        assertEquals(found.get(0).command().getClass().getName(), found.get(1).command().getClass().getName());
        assertNotSame(found.get(0).command().getClass(), found.get(1).command().getClass());
    }

    @Test
    void theHeadsOwnProvidersAreNotRepeatedUnderEveryPlugin(@TempDir Path temp) throws IOException {
        Path plugins = temp.resolve("plugins");
        installPlugin(plugins, "demo");

        long testEchoes = PluginLoader.discover(plugins, HEAD, message -> { }).stream()
                .filter(provided -> provided.command() instanceof FakeCommands.Echo)
                .count();

        assertEquals(1, testEchoes);
    }

    @Test
    void aPluginDirectoryWithoutCurrentIsIgnored(@TempDir Path plugins) throws IOException {
        Files.createDirectories(plugins.resolve("half-installed/libs"));

        assertTrue(pluginsOnly(plugins, new ArrayList<>()).isEmpty());
    }

    @Test
    void aMissingPluginsDirectoryIsFine(@TempDir Path temp) {
        assertTrue(pluginsOnly(temp.resolve("absent"), new ArrayList<>()).isEmpty());
    }

    /** One bad entry must cost its own command, not syn: ServiceLoader raises these while iterating, not on get(). */
    @Test
    void aBrokenProviderIsSkippedWithAWarningAndTheRestStillLoad(@TempDir Path temp) throws IOException {
        Path plugins = temp.resolve("plugins");
        installPlugin(plugins, "good");
        Path libs = plugins.resolve("broken/current/libs");
        Files.createDirectories(libs);
        try (OutputStream out = Files.newOutputStream(libs.resolve("broken.jar")); JarOutputStream jar = new JarOutputStream(out)) {
            jar.putNextEntry(new JarEntry("META-INF/services/" + SynCommand.class.getName()));
            jar.write("com.example.DoesNotExist\n".getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
        List<String> warnings = new ArrayList<>();

        List<PluginLoader.Provided> found = pluginsOnly(plugins, warnings);

        assertEquals(List.of("good"), found.stream().map(PluginLoader.Provided::origin).toList());
        assertFalse(warnings.isEmpty());
        assertTrue(warnings.get(0).contains("broken"), warnings.toString());
    }
}
