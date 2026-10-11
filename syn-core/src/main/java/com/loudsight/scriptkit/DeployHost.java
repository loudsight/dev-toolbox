package com.loudsight.scriptkit;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * Commands on the deploy host, with the host rules from kb/recipes/deploy-host.md built in: {@code bash -ic} so
 * PATH tools resolve, and plain {@code docker} - {@code docker compose} silently shows nothing there.
 *
 * <p>Which host, and where its checkout lives, is configuration, never code (this library is shared). {@link #host()}
 * and {@link #checkout()} each take the first of: a system property ({@code syn.deploy.host}, {@code
 * syn.deploy.checkout}), an environment variable ({@code DEPLOY_HOST}, {@code DEPLOY_CHECKOUT}), the {@code host} or
 * {@code checkout} key of {@code deploy-host.properties} in the repo's syn directory (the directory of the {@code
 * syn.build.pom} the launcher passes). With none of them set they fail rather than guess.
 */
public final class DeployHost {

    /** The system property that names the host, ahead of the environment and the file. */
    public static final String HOST_PROPERTY = "syn.deploy.host";
    /** The system property that names the checkout path on the host, ahead of the environment and the file. */
    public static final String CHECKOUT_PROPERTY = "syn.deploy.checkout";

    private static final String REMOTE_HOST_ENV = "REMOTE_HOST";
    private static final String HOST_ENV = "DEPLOY_HOST";
    private static final String CHECKOUT_ENV = "DEPLOY_CHECKOUT";
    private static final String CONFIG_FILE = "deploy-host.properties";
    private static final String POM_PROPERTY = "syn.build.pom";

    private DeployHost() {
    }

    /** The deploy host as {@code user@host}; see the class comment for where it comes from. */
    public static String host() {
        return host(System::getenv);
    }

    static String host(Function<String, @Nullable String> environment) {
        return setting("host", HOST_PROPERTY, HOST_ENV, "user@name", "or pass --host", environment);
    }

    /**
     * The host a command should target: {@code explicit} (a {@code --host} or {@code --on-remote=} value) if given,
     * else {@code REMOTE_HOST} (a one-command override), else {@link #host()}.
     */
    public static String resolveHost(@Nullable String explicit) {
        return resolveHost(explicit, System::getenv);
    }

    static String resolveHost(@Nullable String explicit, Function<String, @Nullable String> environment) {
        if (explicit != null && !explicit.isBlank()) {
            return explicit.strip();
        }
        String override = environment.apply(REMOTE_HOST_ENV);
        if (override != null && !override.isBlank()) {
            return override.strip();
        }
        return host(environment);
    }

    /**
     * Paths (a submodule, or a directory) that a {@code --on-remote} sync check does not compare: the comma-separated
     * {@code sync.ignore} of {@code deploy-host.properties}, none when unset. It belongs in the file, not here - which
     * submodules cannot affect a build is the repo's knowledge, and this library is shared.
     */
    public static Set<String> syncIgnore() {
        return parseIgnore(fromFile("sync.ignore"));
    }

    static Set<String> parseIgnore(@Nullable String value) {
        if (value == null) {
            return Set.of();
        }
        return Pattern.compile(",").splitAsStream(value)
                .map(String::strip)
                .map(path -> path.endsWith("/") ? path.substring(0, path.length() - 1) : path)
                .filter(path -> !path.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    /** The absolute path of the repo's checkout on the deploy host; see the class comment for where it comes from. */
    public static String checkout() {
        return checkout(System::getenv);
    }

    static String checkout(Function<String, @Nullable String> environment) {
        return setting("checkout", CHECKOUT_PROPERTY, CHECKOUT_ENV, "/path/to/checkout", "", environment);
    }

    private static String setting(String key, String property, String environment, String example, String hint,
                                  Function<String, @Nullable String> environmentLookup) {
        String fromProperty = System.getProperty(property);
        if (fromProperty != null && !fromProperty.isBlank()) {
            return fromProperty.strip();
        }
        String fromEnv = environmentLookup.apply(environment);
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv.strip();
        }
        String fromFile = fromFile(key);
        if (fromFile != null) {
            return fromFile;
        }
        throw new IllegalStateException("no deploy " + key + " configured: set " + environment + ", or put " + key + "="
                + example + " in " + CONFIG_FILE + " beside the pom named by -D" + POM_PROPERTY
                + (hint.isEmpty() ? "" : ", " + hint));
    }

    private static @Nullable String fromFile(String key) {
        String pom = System.getProperty(POM_PROPERTY);
        if (pom == null || pom.isBlank()) {
            return null;
        }
        Path pomPath = Env.repoRoot().resolve(pom).normalize();
        Path directory = pomPath.getParent();
        if (directory == null) {
            return null;
        }
        Path file = directory.resolve(CONFIG_FILE);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            properties.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
        String value = properties.getProperty(key);
        return value == null || value.isBlank() ? null : value.strip();
    }

    /** {@code ssh HOST bash -ic '<command>'}. */
    public static Cmd ssh(String... command) {
        return build(false, command);
    }

    /** As {@link #ssh}, with the agent forwarded ({@code -A}), which anything running syn deploy-run needs. */
    public static Cmd sshWithAgent(String... command) {
        return build(true, command);
    }

    /** A {@code docker} command on the host. Refuses {@code compose}. */
    public static Cmd docker(String... args) {
        if (args.length > 0 && "compose".equals(args[0])) {
            throw new IllegalArgumentException("docker compose shows nothing on the deploy host - use plain docker");
        }
        return ssh(Stream.concat(Stream.of("docker"), Stream.of(args)).toArray(String[]::new));
    }

    private static Cmd build(boolean forwardAgent, String... command) {
        String remote = Stream.of(command).map(Shell::quote).collect(Collectors.joining(" "));
        List<String> ssh = new ArrayList<>(List.of("ssh"));
        if (forwardAgent) {
            ssh.add("-A");
        }
        ssh.addAll(List.of(host(), "bash", "-ic", Shell.quote(remote)));
        return Cmd.of(ssh);
    }
}
