package com.loudsight.syn.cli;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.loudsight.syn.spi.SynCommand;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.tools.JavaCompiler;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import picocli.CommandLine;

/** Plugins built on the fly: a jar under {@code <plugins>/<name>/current/libs} whose one command has a chosen name and prints a chosen line. */
final class PluginFixtures {

    private PluginFixtures() {
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

    static void install(Path plugins, String plugin, String commandName, String prints) throws IOException {
        String className = "plugin." + plugin + ".Command";
        String code = "package plugin." + plugin + ";"
                + "@picocli.CommandLine.Command(name = \"" + commandName + "\")"
                + "public final class Command implements com.loudsight.syn.spi.SynCommand {"
                + "  public com.loudsight.syn.spi.Tier tier() { return com.loudsight.syn.spi.Tier.READ_ONLY; }"
                + "  public int run(com.loudsight.syn.spi.Context context) { context.out().println(\"" + prints + "\"); return 0; }"
                + "}";
        Path classes = plugins.resolveSibling("classes-" + plugin);
        Files.createDirectories(classes);
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "tests need a JDK, not a JRE");
        String classpath = location(SynCommand.class) + java.io.File.pathSeparator + location(CommandLine.class);
        boolean compiled = Boolean.TRUE.equals(compiler.getTask(null, null, null, List.of("-classpath", classpath, "-d", classes.toString()),
                null, List.of(new Source(className, code))).call());
        assertTrue(compiled, "could not compile the test plugin");

        Path libs = Files.createDirectories(plugins.resolve(plugin).resolve("current/libs"));
        String classFile = className.replace('.', '/') + ".class";
        try (OutputStream out = Files.newOutputStream(libs.resolve(plugin + ".jar")); JarOutputStream jar = new JarOutputStream(out)) {
            jar.putNextEntry(new JarEntry(classFile));
            jar.write(Files.readAllBytes(classes.resolve(classFile)));
            jar.closeEntry();
            jar.putNextEntry(new JarEntry("META-INF/services/" + SynCommand.class.getName()));
            jar.write((className + "\n").getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
    }

    private static String location(Class<?> type) {
        return type.getProtectionDomain().getCodeSource().getLocation().getPath();
    }
}
