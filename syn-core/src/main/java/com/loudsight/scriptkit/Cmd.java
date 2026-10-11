package com.loudsight.scriptkit;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * An external command: the replacement for backticks, pipes and GNU {@code timeout} (absent on
 * macOS) in shell scripts. Arguments are passed as a list, never re-split by a shell.
 */
@SuppressFBWarnings(
        value = "COMMAND_INJECTION",
        justification = "Running commands is this class's purpose; its callers are the repo's own scripts, run by a developer or agent, not a service handling outside input.")
public final class Cmd {

    private final List<String> command;
    private final Map<String, String> environment = new LinkedHashMap<>();
    private @Nullable Path directory;
    private @Nullable Duration timeout;
    private @Nullable String input;
    private boolean mergeErr;
    private ProcessBuilder.Redirect errors = ProcessBuilder.Redirect.INHERIT;

    private Cmd(List<String> command) {
        this.command = List.copyOf(command);
    }

    public static Cmd of(String... command) {
        return new Cmd(List.of(command));
    }

    public static Cmd of(List<String> command) {
        return new Cmd(command);
    }

    public Cmd dir(Path dir) {
        this.directory = dir;
        return this;
    }

    public Cmd env(String name, String value) {
        environment.put(name, value);
        return this;
    }

    /** Kills the command, and fails the call, once this elapses. */
    public Cmd timeout(Duration limit) {
        this.timeout = limit;
        return this;
    }

    /** Throws stderr away ({@code 2>/dev/null}). */
    public Cmd discardErr() {
        this.errors = ProcessBuilder.Redirect.DISCARD;
        return this;
    }

    /** Sends stderr wherever stdout goes ({@code 2>&1}). */
    public Cmd mergeErr() {
        this.mergeErr = true;
        return this;
    }

    public Cmd stdin(String text) {
        this.input = text;
        return this;
    }

    public List<String> command() {
        return command;
    }

    /** The command as a shell would print it, for logs and dry runs. */
    @Override
    public String toString() {
        return command.stream().map(Shell::quote).collect(Collectors.joining(" "));
    }

    /** Runs with stdout captured and stderr passed through to this process's stderr (or captured too, under {@link #mergeErr}). */
    public Result capture() {
        return run(builder().redirectError(errors), true);
    }

    /** Runs with stdout and stderr passed through; returns the exit code. */
    public int stream() {
        ProcessBuilder builder = builder()
                .redirectOutput(ProcessBuilder.Redirect.INHERIT)
                .redirectError(errors);
        return run(builder, false).exitCode();
    }

    /** Runs with stdout written to {@code file} ({@code > file}), stderr passed through; returns the exit code. */
    public int toFile(Path file) {
        ProcessBuilder builder = builder()
                .redirectOutput(file.toFile())
                .redirectError(errors);
        return run(builder, false).exitCode();
    }

    private ProcessBuilder builder() {
        ProcessBuilder builder = new ProcessBuilder(new ArrayList<>(command));
        if (directory != null) {
            builder.directory(directory.toFile());
        }
        builder.environment().putAll(environment);
        builder.redirectErrorStream(mergeErr);
        if (input == null) {
            builder.redirectInput(ProcessBuilder.Redirect.INHERIT);
        }
        return builder;
    }

    private Result run(ProcessBuilder builder, boolean captureOutput) {
        try {
            Process process = builder.start();
            if (input != null) {
                try (OutputStream in = process.getOutputStream()) {
                    in.write(input.getBytes(StandardCharsets.UTF_8));
                }
            }
            CompletableFuture<String> stdout = captureOutput
                    ? CompletableFuture.supplyAsync(() -> readAll(process.getInputStream()))
                    : CompletableFuture.completedFuture("");
            if (timeout == null) {
                process.waitFor();
            } else if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new CmdException("timed out after " + timeout + ": " + this);
            }
            return new Result(process.exitValue(), stdout.join());
        } catch (IOException e) {
            throw new CmdException("could not start: " + this, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CmdException("interrupted: " + this, e);
        }
    }

    private static String readAll(InputStream stream) {
        try (stream) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A finished command's exit code and captured stdout. */
    public record Result(int exitCode, String stdout) {

        public boolean ok() {
            return exitCode == 0;
        }

        public List<String> lines() {
            return stdout.isEmpty() ? List.of() : stdout.lines().toList();
        }

        /** The trimmed stdout, or an exception naming the exit code. */
        public String orThrow() {
            if (!ok()) {
                throw new CmdException("exited " + exitCode);
            }
            return stdout.strip();
        }
    }

    /** A command that could not run, timed out, or failed where success was required. */
    public static final class CmdException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        CmdException(String message) {
            super(message);
        }

        CmdException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
