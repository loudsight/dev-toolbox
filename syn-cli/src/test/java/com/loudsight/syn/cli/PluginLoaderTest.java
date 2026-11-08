package com.loudsight.syn.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.loudsight.syn.core.RemoteExec;
import com.loudsight.syn.core.SynConfig;
import com.loudsight.syn.spi.HostSync;
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
        installJar(plugins, name, SynCommand.class, className, List.of(new Source(className, code)));
    }

    /**
     * Compiles {@code sources} and jars only {@code className} (not its helpers) with a services file for {@code service}, so
     * a helper class can be compiled yet left out, as a plugin built against a different head would lack it.
     */
    private static void installJar(Path plugins, String name, Class<?> service, String className, List<Source> sources) throws IOException {
        Path classes = plugins.resolveSibling("classes-" + name);
        Files.createDirectories(classes);
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "tests need a JDK, not a JRE");
        String spi = service.getProtectionDomain().getCodeSource().getLocation().getPath();
        assertTrue(compiler.getTask(null, null, null, List.of("-classpath", spi, "-d", classes.toString()), null, sources).call(),
                "could not compile the test plugin");

        Path libs = plugins.resolve(name).resolve("current/libs");
        Files.createDirectories(libs);
        String classFile = className.replace('.', '/') + ".class";
        try (OutputStream out = Files.newOutputStream(libs.resolve(name + ".jar")); JarOutputStream jar = new JarOutputStream(out)) {
            jar.putNextEntry(new JarEntry(classFile));
            jar.write(Files.readAllBytes(classes.resolve(classFile)));
            jar.closeEntry();
            jar.putNextEntry(new JarEntry("META-INF/services/" + service.getName()));
            jar.write((className + "\n").getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
    }

    /**
     * A plugin whose only service is a {@link HostSync}; with {@code missingDependency} its class needs another class that
     * is left out of the jar, as a plugin built against a different head would.
     */
    private static void installHostSyncPlugin(Path plugins, String name, boolean missingDependency) throws IOException {
        String className = "plugin." + name + ".Sync";
        // A field initialiser, so instantiating the provider needs the missing class.
        String field = missingDependency ? "private final Object helper = new plugin." + name + ".Helper();" : "";
        String code = "package plugin." + name + ";"
                + "public final class Sync implements com.loudsight.syn.spi.HostSync {" + field
                + "  public String envPrelude() { return \"source /plugin/" + name + ".sh\"; }"
                + "  public String syncFragment() { return \"echo " + name + "\\n\"; }"
                + "}";
        List<Source> sources = new ArrayList<>(List.of(new Source(className, code)));
        if (missingDependency) {
            sources.add(new Source("plugin." + name + ".Helper", "package plugin." + name + "; public final class Helper { }"));
        }
        installJar(plugins, name, HostSync.class, className, sources);
    }

    private static HostSyncChoice choose(Path temp, String config, List<String> warnings) throws IOException {
        Path repo = temp.resolve("repo");
        Files.createDirectories(repo.resolve(".syn"));
        Files.writeString(repo.resolve(".syn/config.properties"), config);
        return HostSyncChoice.resolve(PluginLoader.services(HostSync.class, temp.resolve("plugins"), HEAD, warnings::add), SynConfig.load(repo));
    }

    @Test
    void findsAServiceOtherThanACommandInAPluginAndNamesItsOrigin(@TempDir Path temp) throws IOException {
        installHostSyncPlugin(temp.resolve("plugins"), "alpha", false);
        List<String> warnings = new ArrayList<>();

        List<PluginLoader.Service<HostSync>> found = PluginLoader.services(HostSync.class, temp.resolve("plugins"), HEAD, warnings::add);

        assertEquals(1, found.size());
        assertEquals("alpha", found.get(0).origin());
        assertEquals("source /plugin/alpha.sh", found.get(0).service().envPrelude());
        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    @Test
    void aProviderThatDoesNotLinkIsSkippedWithAClearWarningNotACrash(@TempDir Path temp) throws IOException {
        Path plugins = temp.resolve("plugins");
        installHostSyncPlugin(plugins, "good", false);
        installHostSyncPlugin(plugins, "stale", true);
        List<String> warnings = new ArrayList<>();

        List<PluginLoader.Service<HostSync>> found = PluginLoader.services(HostSync.class, plugins, HEAD, warnings::add);

        assertEquals(List.of("good"), found.stream().map(PluginLoader.Service::origin).toList());
        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains("stale") && warnings.get(0).contains("link"), warnings.get(0));
    }

    @Test
    void withNoProviderTheDefaultHostSyncIsUsed(@TempDir Path temp) throws IOException {
        HostSyncChoice choice = choose(temp, "", new ArrayList<>());

        assertSame(RemoteExec.DEFAULT_HOST_SYNC, choice.hostSync());
        assertTrue(choice.problems().isEmpty() && choice.refusal().isEmpty());
    }

    @Test
    void aSingleProviderIsUsed(@TempDir Path temp) throws IOException {
        installHostSyncPlugin(temp.resolve("plugins"), "alpha", false);

        HostSyncChoice choice = choose(temp, "", new ArrayList<>());

        assertEquals("alpha", choice.origin());
        assertEquals("source /plugin/alpha.sh", choice.hostSync().envPrelude());
        assertTrue(choice.problems().isEmpty());
    }

    @Test
    void twoProvidersAreAProblemUntilAnOverridePicksOne(@TempDir Path temp) throws IOException {
        installHostSyncPlugin(temp.resolve("plugins"), "alpha", false);
        installHostSyncPlugin(temp.resolve("plugins"), "beta", false);

        HostSyncChoice unresolved = choose(temp, "", new ArrayList<>());
        HostSyncChoice resolved = choose(temp, "override.host-sync=beta\n", new ArrayList<>());

        assertEquals(1, unresolved.problems().size());
        assertTrue(unresolved.problems().get(0).contains("alpha and beta"), unresolved.problems().toString());
        assertTrue(resolved.problems().isEmpty(), resolved.problems().toString());
        assertEquals("beta", resolved.origin());
    }

    @Test
    void aRequiredPluginThatIsMissingRefusesInsteadOfUsingTheDefault(@TempDir Path temp) throws IOException {
        HostSyncChoice missing = choose(temp, "remote.host-sync=alpha\n", new ArrayList<>());
        installHostSyncPlugin(temp.resolve("plugins"), "alpha", false);
        HostSyncChoice present = choose(temp, "remote.host-sync=alpha\n", new ArrayList<>());

        assertTrue(missing.refusal().isPresent());
        assertTrue(missing.refusal().get().contains("alpha"), missing.refusal().get());
        assertTrue(present.refusal().isEmpty());
        assertTrue(present.report().contains("host-sync: alpha"), present.report());
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
