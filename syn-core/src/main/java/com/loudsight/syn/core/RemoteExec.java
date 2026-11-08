package com.loudsight.syn.core;

import com.loudsight.scriptkit.Cmd;
import com.loudsight.scriptkit.Shell;
import com.loudsight.syn.spi.HostSync;
import java.nio.file.Path;
import java.io.PrintStream;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Runs a syn command line on the deploy host, in its checkout, exactly as typed here minus {@code --on-remote}. The
 * host's own {@code scripts/syn} does the work, so the command, its tier rules and its output are the host's. Output
 * streams as it is produced; the exit code is the remote command's, or 255 when ssh could not connect. A command that
 * changes things runs {@linkplain #runDetached detached} instead, so a dropped connection cannot end it half way.
 *
 * <p>By default nothing is pulled: the host runs whatever it has checked out, and {@link SyncCheck} says when that
 * differs from here. With {@code sync} the host first runs the {@link HostSync#syncFragment()} in the checkout.
 */
public final class RemoteExec {

    /** Ssh gives up on a host that does not answer, and on a connection that has gone silent, rather than hang. */
    static final List<String> SSH_OPTIONS = List.of("-o", "ConnectTimeout=10", "-o", "ServerAliveInterval=15", "-o", "ServerAliveCountMax=3");

    /** How often a detached run is polled for new output. */
    private static final long POLL_MILLIS = 2_000;

    private RemoteExec() {
    }

    /**
     * One remote run.
     *
     * @param sync whether the host pulls first ({@link HostSync#syncFragment()})
     * @param detach whether it runs as a {@link RemoteJob} that survives a dropped connection
     * @param hostSync the host setup; {@link #DEFAULT_HOST_SYNC} unless a plugin supplies one
     * @param launcher the command that starts syn in the host's checkout; see {@link #launcher}
     */
    public record Request(String host, String checkout, List<String> args, boolean sync, boolean detach, HostSync hostSync, String launcher) {

        public Request {
            args = List.copyOf(args);
        }

        /** A request with the default launcher. */
        public Request(String host, String checkout, List<String> args, boolean sync, boolean detach, HostSync hostSync) {
            this(host, checkout, args, sync, detach, hostSync, DEFAULT_LAUNCHER);
        }

        /** A request with the default host setup and launcher. */
        public Request(String host, String checkout, List<String> args, boolean sync, boolean detach) {
            this(host, checkout, args, sync, detach, DEFAULT_HOST_SYNC, DEFAULT_LAUNCHER);
        }
    }

    /** The launcher when {@code remote.launcher} is not set: {@code syn} on the host's PATH. */
    public static final String DEFAULT_LAUNCHER = "syn";

    /** {@code remote.launcher} in the repo's syn settings (e.g. {@code scripts/syn}, relative to the checkout), else {@link #DEFAULT_LAUNCHER}. */
    public static String launcher(Path repoRoot) {
        return SynConfig.load(repoRoot).get("remote.launcher").orElse(DEFAULT_LAUNCHER);
    }

    /** The host setup used when no plugin supplies one. */
    public static final HostSync DEFAULT_HOST_SYNC = new GenericHostSync();

    /** The shell script run on the host: toolchain, the checkout, then {@code scripts/syn} with {@code args}. */
    public static String script(String checkout, List<String> args) {
        return script(checkout, args, false);
    }

    /** As {@link #script(String, List)}; with {@code sync}, the default host sync runs in the checkout first. */
    public static String script(String checkout, List<String> args, boolean sync) {
        return script(checkout, args, sync, DEFAULT_HOST_SYNC);
    }

    /** As {@link #script(String, List, boolean)}, with the host setup taken from {@code hostSync}. */
    public static String script(String checkout, List<String> args, boolean sync, HostSync hostSync) {
        return script(checkout, args, sync, hostSync, DEFAULT_LAUNCHER);
    }

    /** As {@link #script(String, List, boolean, HostSync)}, starting syn with {@code launcher}. */
    public static String script(String checkout, List<String> args, boolean sync, HostSync hostSync, String launcher) {
        String syn = Stream.concat(Stream.of(launcher), args.stream()).map(Shell::quote).collect(Collectors.joining(" "));
        String prelude = hostSync.envPrelude();
        String lines = prelude.strip();
        String before = lines.isEmpty() ? "" : String.join(" && ", lines.split("\n")) + " && ";
        if (!sync) {
            return before + "cd " + Shell.quote(checkout) + " && " + syn;
        }
        // Multi-line, under set -e from after the prelude (which may read unset variables or return non-zero).
        return (lines.isEmpty() ? "" : lines + "\n") + "set -e\ncd " + Shell.quote(checkout) + "\n" + hostSync.syncFragment() + syn + "\n";
    }

    /**
     * {@code ssh HOST bash -c '<script>'}, with timeouts. Not {@code bash -ic}: the script sources the host's env script
     * itself, and an interactive shell without a terminal only adds "no job control" noise to stderr.
     */
    public static List<String> sshCommand(String host, String script) {
        List<String> ssh = new ArrayList<>(List.of("ssh"));
        ssh.addAll(SSH_OPTIONS);
        ssh.addAll(List.of(host, "bash", "-c", Shell.quote(script)));
        return ssh;
    }

    /** Runs the request, detached or streaming as it says; returns the command's exit code. */
    public static int run(Request request, PrintStream out, PrintStream err) throws InterruptedException {
        String script = script(request.checkout(), request.args(), request.sync(), request.hostSync(), request.launcher());
        return request.detach() ? runDetached(request.host(), script, out, err) : Cmd.of(sshCommand(request.host(), script)).stream();
    }

    private static int runDetached(String host, String script, PrintStream out, PrintStream err) throws InterruptedException {
        String runId = "syn-" + LocalDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss", Locale.ROOT))
                + "-" + ProcessHandle.current().pid();
        RemoteJob job = new RemoteJob(host, runId, err::println);
        job.launch(script);
        err.println("syn: detached on " + host + " as " + runId + " (survives a dropped connection); log " + job.logPath());
        job.awaitDoneStreaming(POLL_MILLIS, out);
        String code = job.exitCode();
        if (code.isEmpty()) {
            err.println("syn: could not read the exit code; the files stay on the host: " + job.basePath() + ".*");
            return 1;
        }
        job.cleanup();
        return code.matches("\\d+") ? Integer.parseInt(code) : 1;
    }
}
