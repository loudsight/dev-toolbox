package com.loudsight.syn.core;

import com.loudsight.scriptkit.Cmd;
import com.loudsight.syn.spi.CommandExit;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * A script running detached on the deploy host (nohup + setsid, its output in a log file, its exit code in a marker
 * file), so it survives a dropped connection; only {@link #awaitDone} and {@link #awaitDoneStreaming} need a live one,
 * and they reconnect. Shared by {@code syn deploy-run} and {@code syn --on-remote} for a command that changes things.
 */
public final class RemoteJob {

    /** Attempts for the reconnect-prone steps after the job has finished. */
    public static final int RETRIES = 5;

    /** The ssh and scp programs to run; the real ones, or a stand-in in a test. */
    public record Transport(String ssh, String scp) {

        public static Transport real() {
            return new Transport("ssh", "scp");
        }
    }

    private static final String STATE_MARKER = "\n@@SYN-STATE@@";
    private static final String PROBE_MARKER = "@@SYN-PROBE@@";

    private final String host;
    private final String runId;
    private final Consumer<String> say;
    private final Transport transport;
    private final long retryMillis;
    private final String directory;

    public RemoteJob(String host, String runId, Consumer<String> say) {
        this(host, runId, say, Transport.real(), 15_000, "/tmp");
    }

    public RemoteJob(String host, String runId, Consumer<String> say, Transport transport, long retryMillis, String directory) {
        this.host = host;
        this.runId = runId;
        this.say = say;
        this.transport = transport;
        this.retryMillis = retryMillis;
        this.directory = directory;
    }

    /** {@code /tmp/<runId>}: the prefix of every file this job keeps on the host. */
    public String basePath() {
        return directory + "/" + runId;
    }

    public String scriptPath() {
        return basePath() + ".sh";
    }

    public String logPath() {
        return basePath() + ".log";
    }

    public String donePath() {
        return basePath() + ".exitcode";
    }

    /** Copies {@code body} to the host and starts it detached; returns once it is running, not when it ends. */
    public void launch(String body) {
        must(Cmd.of(transport.ssh(), host, "cat > " + scriptPath()).stdin(body).stream());
        // chmod, the run and capturing its exit code all happen inside one backgrounded bash -c, so the
        // whole job's stdio (not just part of it) is detached from this ssh channel.
        must(Cmd.of(transport.ssh(), host, "nohup setsid bash -c 'chmod +x " + scriptPath() + "; bash " + scriptPath() + " > " + logPath()
                + " 2>&1; echo $? > " + donePath() + "' < /dev/null > /dev/null 2>&1 &").stdin("").stream());
    }

    /** Polls until the exit-code marker exists. Reconnecting is expected: only this loop needs a live connection. */
    public void awaitDone(long pollMillis) throws InterruptedException {
        awaitDone(pollMillis, "", ignored -> { });
    }

    /**
     * As {@link #awaitDone(long)}, also running {@code probe} on the host with each poll and handing its output to
     * {@code onProbe}, including on the poll that finds the job done. The probe shares the poll's ssh call, and its own
     * failure or stderr never counts as a lost connection. A blank probe adds nothing to the poll.
     */
    public void awaitDone(long pollMillis, String probe, Consumer<String> onProbe) throws InterruptedException {
        String command = "test -f " + donePath() + " && echo DONE || echo RUNNING"
                + (probe.isBlank() ? "" : "; echo '" + PROBE_MARKER + "'; { " + probe + "; } 2>/dev/null || true");
        while (true) {
            Cmd.Result status = Cmd.of(transport.ssh(), "-o", "ConnectTimeout=10", "-o", "ServerAliveInterval=15", "-o", "ServerAliveCountMax=3", host,
                    command).stdin("").discardErr().capture();
            if (status.ok()) {
                String output = status.stdout();
                int marker = output.indexOf(PROBE_MARKER);
                String state = marker < 0 ? output : output.substring(0, marker);
                if (marker >= 0) {
                    onProbe.accept(output.substring(marker + PROBE_MARKER.length()));
                }
                if ("DONE".equals(state.strip())) {
                    return;
                }
            } else {
                say.accept("⚠️  Connection to " + host + " interrupted - retrying in " + pollMillis / 1000 + "s (the detached job keeps running regardless)...");
            }
            Thread.sleep(pollMillis);
        }
    }

    /**
     * As {@link #awaitDone}, printing the job's output to {@code sink} as it appears: each poll fetches what the log
     * gained since the last, whole lines only (the rest waits for its newline, so a multi-byte character is never cut).
     */
    public void awaitDoneStreaming(long pollMillis, PrintStream sink) throws InterruptedException {
        long offset = 0;
        while (true) {
            String command = "if [ -f " + logPath() + " ]; then tail -c +" + (offset + 1) + " " + logPath() + "; fi; printf '" + STATE_MARKER.replace("\n", "\\n")
                    + "'; if [ -f " + donePath() + " ]; then echo DONE; else echo RUNNING; fi";
            Cmd.Result poll = Cmd.of(transport.ssh(), "-o", "ConnectTimeout=10", "-o", "ServerAliveInterval=15", "-o", "ServerAliveCountMax=3", host, command)
                    .stdin("").discardErr().capture();
            if (poll.ok()) {
                Chunk chunk = Chunk.parse(poll.stdout());
                if (chunk != null) {
                    String text = chunk.emit();
                    sink.print(text);
                    sink.flush();
                    offset += text.getBytes(StandardCharsets.UTF_8).length;
                    if (chunk.done()) {
                        return;
                    }
                }
            } else {
                say.accept("⚠️  Connection to " + host + " interrupted - retrying in " + pollMillis / 1000 + "s (the detached job keeps running regardless)...");
            }
            Thread.sleep(pollMillis);
        }
    }

    /**
     * What one streaming poll returned: the log bytes past the offset and whether the job has finished. Once it has,
     * everything is emitted; before that, only up to the last newline.
     */
    record Chunk(String text, boolean done) {

        static @Nullable Chunk parse(String output) {
            int marker = output.lastIndexOf(STATE_MARKER);
            if (marker < 0) {
                return null;
            }
            String state = output.substring(marker + STATE_MARKER.length()).strip();
            return new Chunk(output.substring(0, marker), "DONE".equals(state));
        }

        String emit() {
            if (done) {
                return text;
            }
            int lastNewline = text.lastIndexOf('\n');
            return lastNewline < 0 ? "" : text.substring(0, lastNewline + 1);
        }
    }

    /** Copies the whole log down, retrying; false when it could not (it then stays on the host). */
    public boolean fetchLog(Path local) throws InterruptedException {
        return retry(() -> Cmd.of(transport.scp(), "-q", host + ":" + logPath(), local.toString()).stdin("").capture().ok());
    }

    /** Copies the log down and writes it to {@code sink}; false when it could not be fetched. */
    public boolean copyLogTo(PrintStream sink) throws InterruptedException, IOException {
        Path local = Files.createTempFile("remote-job-", ".log");
        try {
            boolean fetched = fetchLog(local);
            if (fetched) {
                Files.copy(local, sink);
                sink.flush();
            }
            return fetched;
        } finally {
            Files.deleteIfExists(local);
        }
    }

    /** The job's exit code as the marker file holds it, or empty when it could not be read. */
    public String exitCode() throws InterruptedException {
        AtomicReference<String> holder = new AtomicReference<>("");
        retry(() -> {
            Cmd.Result done = Cmd.of(transport.ssh(), host, "cat " + donePath()).stdin("").discardErr().capture();
            holder.set(done.stdout().strip());
            return done.ok();
        });
        return holder.get();
    }

    /** Removes the job's files from the host, plus any {@code extra} paths. */
    public void cleanup(String... extra) {
        Cmd.of(transport.ssh(), host, "rm -f " + scriptPath() + " " + logPath() + " " + donePath() + " " + String.join(" ", List.of(extra)))
                .stdin("").discardErr().capture();
    }

    private boolean retry(BooleanSupplier attempt) throws InterruptedException {
        for (int i = 1; i <= RETRIES; i++) {
            if (attempt.getAsBoolean()) {
                return true;
            }
            if (i < RETRIES) {
                say.accept("⚠️  Connection to the deploy host interrupted - retrying in " + retryMillis / 1000 + "s (" + i + "/" + RETRIES + ")...");
                Thread.sleep(retryMillis);
            }
        }
        return false;
    }

    private static void must(int exit) {
        if (exit != 0) {
            throw new CommandExit(exit);
        }
    }
}
