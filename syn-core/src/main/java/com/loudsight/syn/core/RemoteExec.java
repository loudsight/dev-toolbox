package com.loudsight.syn.core;

import com.loudsight.scriptkit.Cmd;
import com.loudsight.scriptkit.Shell;
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
 * differs from here. With {@code sync} the host first takes {@link HostPrep#sync()} (the lock, a refusal over hand edits,
 * the pull and the submodule update), and keeps the lock until the command ends so a deploy cannot pull mid-run.
 */
public final class RemoteExec {

    /** The env script the deploy host sources for its toolchain (the one {@code syn deploy-run} sources there too). */
    static final String HOST_ENV_SCRIPT = "/opt/myEnv.sh";

    /** Ssh gives up on a host that does not answer, and on a connection that has gone silent, rather than hang. */
    static final List<String> SSH_OPTIONS = List.of("-o", "ConnectTimeout=10", "-o", "ServerAliveInterval=15", "-o", "ServerAliveCountMax=3");

    /** How often a detached run is polled for new output. */
    private static final long POLL_MILLIS = 2_000;

    private RemoteExec() {
    }

    /**
     * One remote run.
     *
     * @param sync whether the host pulls first ({@link HostPrep})
     * @param detach whether it runs as a {@link RemoteJob} that survives a dropped connection
     */
    public record Request(String host, String checkout, List<String> args, boolean sync, boolean detach) {

        public Request {
            args = List.copyOf(args);
        }
    }

    /** The shell script run on the host: toolchain, the checkout, then {@code scripts/syn} with {@code args}. */
    public static String script(String checkout, List<String> args) {
        return script(checkout, args, false);
    }

    /** As {@link #script(String, List)}; with {@code sync}, {@link HostPrep#sync()} runs in the checkout first. */
    public static String script(String checkout, List<String> args, boolean sync) {
        String syn = Stream.concat(Stream.of("scripts/syn"), args.stream()).map(Shell::quote).collect(Collectors.joining(" "));
        if (!sync) {
            return "source " + HOST_ENV_SCRIPT + " && export WORK_ROOT=/opt && cd " + Shell.quote(checkout) + " && " + syn;
        }
        // Multi-line, under set -e from after the env script (which reads unset variables and may return non-zero).
        return "source " + HOST_ENV_SCRIPT + "\nexport WORK_ROOT=/opt\nset -e\ncd " + Shell.quote(checkout) + "\n"
                + HostPrep.sync() + syn + "\n";
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
        String script = script(request.checkout(), request.args(), request.sync());
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
