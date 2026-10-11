package com.loudsight.syn.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.loudsight.scriptkit.Cmd;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The real launch, poll, tail, fetch and cleanup flow, with a stand-in for ssh that runs the command on this machine.
 * macOS has no {@code setsid}, so the stand-in also puts a trivial one on the PATH the "host" sees.
 */
class RemoteJobTest {

    @TempDir
    Path temp;

    private RemoteJob.Transport transport;
    private final List<String> said = new ArrayList<>();
    private Path directory;

    @BeforeEach
    void standIns() throws IOException {
        Path bin = Files.createDirectory(temp.resolve("bin"));
        directory = Files.createDirectory(temp.resolve("host-tmp"));
        write(bin.resolve("setsid"), "#!/bin/bash\nexec \"$@\"\n");
        write(bin.resolve("ssh"), "#!/bin/bash\n"
                + "while [ \"${1:0:1}\" = \"-\" ]; do shift 2; done\n"
                + "shift\n"
                + "PATH=\"" + bin + ":$PATH\" exec bash -c \"$*\"\n");
        write(bin.resolve("scp"), "#!/bin/bash\nshift\ncp \"${1#*:}\" \"$2\"\n");
        transport = new RemoteJob.Transport(bin.resolve("ssh").toString(), bin.resolve("scp").toString());
    }

    private static void write(Path file, String text) throws IOException {
        Files.writeString(file, text, StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwx------"));
    }

    private RemoteJob job(String id) {
        return new RemoteJob("host.invalid", id, said::add, transport, 10, directory.toString());
    }

    /** What one poll prints on the host: the log bytes, then the marker line. */
    private static RemoteJob.@Nullable Chunk poll(String log, String state) {
        return RemoteJob.Chunk.parse(log + "\n@@SYN-STATE@@" + state + "\n");
    }

    private static String printed(ByteArrayOutputStream out) {
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    void aJobRunsDetachedItsLogComesDownItsExitCodeIsReadAndItsFilesAreRemoved() throws Exception {
        RemoteJob job = job("job-a");

        job.launch("echo hello from the host\necho and a line on stderr >&2\nexit 3\n");
        job.awaitDone(50);
        ByteArrayOutputStream log = new ByteArrayOutputStream();
        boolean fetched = job.copyLogTo(new PrintStream(log, true, StandardCharsets.UTF_8));

        assertTrue(fetched);
        assertEquals("hello from the host\nand a line on stderr\n", printed(log), "stdout and stderr land in one log, in order");
        assertEquals("3", job.exitCode());
        assertTrue(Files.exists(Path.of(job.scriptPath())) && Files.exists(Path.of(job.logPath())) && Files.exists(Path.of(job.donePath())));

        job.cleanup();

        assertFalse(Files.exists(Path.of(job.scriptPath())) || Files.exists(Path.of(job.logPath())) || Files.exists(Path.of(job.donePath())));
    }

    @Test
    void aProbeRidesEveryPollIncludingTheLastAndItsFailureIsNotALostConnection() throws Exception {
        RemoteJob job = job("job-e");
        List<String> probed = new ArrayList<>();

        job.launch("sleep 0.5\nexit 0\n");
        job.awaitDone(50, "echo \"seen $(test -f " + job.donePath() + " && echo done || echo running)\"; false", probed::add);

        assertTrue(probed.size() >= 2, probed.toString());
        assertEquals("seen running", probed.get(0).strip());
        assertEquals("seen done", probed.get(probed.size() - 1).strip(), "the poll that finds the job done still delivers its probe");
        assertTrue(said.isEmpty(), "a failing probe must not read as an interrupted connection: " + said);
    }

    @Test
    void streamingPrintsEachLineAsItAppearsAndNeverCutsALineOrACharacter() throws Exception {
        RemoteJob job = job("job-b");
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        job.launch("echo one\nsleep 0.4\nprintf 'twé'\nsleep 0.4\nprintf 'o\\n'\nsleep 0.2\nprintf 'no newline at the end'\nexit 7\n");
        job.awaitDoneStreaming(50, new PrintStream(out, true, StandardCharsets.UTF_8));

        assertEquals("one\ntwéo\nno newline at the end", printed(out), "the half line waited for its newline; the last one is flushed at the end");
        assertEquals("7", job.exitCode());
    }

    @Test
    void aJobThatIsStillRunningIsNotReportedDone() throws Exception {
        RemoteJob job = job("job-c");

        job.launch("sleep 5\n");

        Thread.sleep(300);
        assertFalse(Files.exists(Path.of(job.donePath())), "the marker exists only when the job has ended");
        // end it so the test leaves nothing running
        Cmd.of("pkill", "-f", job.scriptPath()).capture();
    }

    @Test
    void aFailingTransportIsRetriedAndReportedNotSilentlyIgnored() throws Exception {
        RemoteJob.Transport broken = new RemoteJob.Transport("false", "false");
        RemoteJob job = new RemoteJob("host.invalid", "job-d", said::add, broken, 1, directory.toString());

        assertEquals("", job.exitCode());
        assertFalse(job.copyLogTo(new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8)));

        assertEquals(2 * (RemoteJob.RETRIES - 1), said.size(), said.toString());
        assertTrue(said.get(0).contains("retrying"), said.get(0));
    }

    @Test
    void aChunkEmitsWholeLinesWhileRunningAndEverythingOnceDone() {
        RemoteJob.Chunk running = poll("one\ntwo\npart\n", "RUNNING");
        RemoteJob.Chunk done = poll("one\ntwo\npart", "DONE");

        assertNotNull(running);
        assertNotNull(done);
        assertEquals("one\ntwo\npart\n", running.emit(), "ends in a newline here, so all of it is whole lines");
        assertFalse(running.done());
        RemoteJob.Chunk midLine = poll("one\npa", "RUNNING");
        assertNotNull(midLine);
        assertEquals("one\n", midLine.emit(), "the half line waits for its newline");
        assertEquals("a\n", new RemoteJob.Chunk("a\nhalf", false).emit());
        assertEquals("", new RemoteJob.Chunk("half", false).emit());
        assertEquals("a\nhalf", new RemoteJob.Chunk("a\nhalf", true).emit());
        assertTrue(done.done());
        assertEquals("one\ntwo\npart", done.emit(), "once done, even an unterminated last line is emitted");
        assertNull(RemoteJob.Chunk.parse("a poll that never reached the marker"));
    }
}
