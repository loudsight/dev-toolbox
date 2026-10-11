package com.loudsight.syn.plugin.kit;

import com.loudsight.scriptkit.Cmd;
import com.loudsight.syn.core.ConsoleOutput;
import com.loudsight.syn.core.SynContext;
import com.loudsight.syn.spi.CommandDescriptor;
import com.loudsight.syn.spi.CommandExit;
import com.loudsight.syn.spi.SynCommand;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import picocli.CommandLine;

/** What the command tests share: a context over a temp repo, argument parsing as the head does it, and a throwaway git repo. */
final class Fixtures {

    /** One command run: its exit code and what it wrote to stdout and stderr. */
    record Result(int exit, String stdout, String stderr) {
    }

    private Fixtures() {
    }

    /** Parses {@code args} into {@code command} with picocli, then runs it against {@code root}, as the head would. */
    static Result run(SynCommand command, Path root, List<CommandDescriptor> commands, boolean dryRun, String... args) throws Exception {
        new CommandLine(command).parseArgs(args);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        SynContext context = new SynContext(root, false, dryRun,
                new ConsoleOutput(false, new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8)),
                () -> commands);
        int exit;
        try {
            exit = command.run(context);
        } catch (CommandExit e) {
            exit = e.code();
        }
        return new Result(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    static Result run(SynCommand command, Path root, String... args) throws Exception {
        return run(command, root, List.of(), false, args);
    }

    static Path write(Path file, String content) throws IOException {
        Files.createDirectories(Objects.requireNonNull(file.toAbsolutePath().getParent()));
        return Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    /** Turns {@code root} into a git repo with everything in it staged, so {@code git ls-files} sees it. */
    static void gitInit(Path root) {
        for (List<String> step : List.of(List.of("init", "-q"), List.of("add", "-A"))) {
            List<String> command = new java.util.ArrayList<>(List.of("git", "-c", "user.name=t", "-c", "user.email=t@t"));
            command.addAll(step);
            Cmd.of(command).dir(root).discardErr().capture();
        }
    }

    static CommandDescriptor command(String name, String tier, String purpose, String... keywords) {
        return new CommandDescriptor(name, name.contains("-") ? name.substring(0, name.indexOf('-')) : name, tier, purpose, List.of(keywords), "plugin");
    }
}
