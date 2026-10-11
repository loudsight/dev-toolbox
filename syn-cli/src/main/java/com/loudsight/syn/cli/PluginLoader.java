package com.loudsight.syn.cli;

import com.loudsight.syn.spi.SynCommand;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Finds {@link SynCommand} providers: those on the head's own classpath ("builtin"), then those in
 * plugins under a plugins directory laid out like the backend-server's modules:
 * {@code <dir>/<name>/current/libs/*.jar} and {@code <dir>/<name>/current/resources}. Each plugin
 * gets its own class loader, child of the head's, so two plugins may use different versions of a
 * library; the SPI and picocli come from the head.
 */
public final class PluginLoader {

    public static final String BUILTIN = "builtin";

    /** Bad entries tolerated per class loader before giving up on it, so a pathological plugin cannot loop forever. */
    private static final int MAX_FAILURES = 50;

    /** A discovered command and where it came from. */
    public record Provided(SynCommand command, String origin) {
    }

    private PluginLoader() {
    }

    /**
     * Every provider reachable, builtin first then plugins by name. A provider that cannot be
     * instantiated is reported to {@code warn} and skipped: one broken plugin must not take down syn.
     */
    public static List<Provided> discover(Path pluginsDir, ClassLoader head, Consumer<String> warn) {
        List<Provided> found = new ArrayList<>(load(head, Set.of(), BUILTIN, warn));
        // A plugin's loader also sees the head's providers through its parent: those are not the plugin's.
        Set<Class<?>> headTypes = found.stream().map(provided -> provided.command().getClass()).collect(Collectors.toSet());
        if (Files.isDirectory(pluginsDir)) {
            for (Path plugin : plugins(pluginsDir)) {
                Path current = plugin.resolve("current");
                if (Files.isDirectory(current)) {
                    String name = Objects.requireNonNull(plugin.getFileName()).toString();
                    found.addAll(load(classLoaderFor(current, head), headTypes, name, warn));
                }
            }
        }
        return found;
    }

    private static List<Provided> load(ClassLoader loader, Set<Class<?>> headTypes, String origin, Consumer<String> warn) {
        List<Provided> found = new ArrayList<>();
        Iterator<ServiceLoader.Provider<SynCommand>> providers = ServiceLoader.load(SynCommand.class, loader).stream().iterator();
        int failures = 0;
        boolean more = true;
        while (more && failures < MAX_FAILURES) {
            // ServiceLoader reports a bad provider entry (class missing, wrong type, no constructor) while
            // iterating, not in get(), and carries on with the next entry afterwards.
            try {
                more = providers.hasNext();
                if (more) {
                    ServiceLoader.Provider<SynCommand> provider = providers.next();
                    if (!headTypes.contains(provider.type())) {
                        found.add(new Provided(provider.get(), origin));
                    }
                }
            } catch (ServiceConfigurationError e) {
                failures++;
                warn.accept("skipping a command from " + origin + ": " + e.getMessage());
            }
        }
        return found;
    }

    private static List<Path> plugins(Path pluginsDir) {
        try (Stream<Path> entries = Files.list(pluginsDir)) {
            return entries.filter(Files::isDirectory).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static ClassLoader classLoaderFor(Path current, ClassLoader head) {
        List<URL> urls = new ArrayList<>();
        try {
            urls.add(current.resolve("resources").toUri().toURL());
            Path libs = current.resolve("libs");
            if (Files.isDirectory(libs)) {
                try (Stream<Path> jars = Files.list(libs)) {
                    for (Path jar : jars.filter(path -> path.toString().endsWith(".jar")).sorted().toList()) {
                        urls.add(jar.toUri().toURL());
                    }
                }
            }
        } catch (MalformedURLException e) {
            throw new IllegalStateException("bad plugin path under " + current, e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new URLClassLoader(urls.toArray(URL[]::new), head);
    }
}
