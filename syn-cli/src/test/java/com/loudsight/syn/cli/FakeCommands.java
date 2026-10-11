package com.loudsight.syn.cli;

import com.loudsight.syn.spi.CommandExit;
import com.loudsight.syn.spi.Context;
import com.loudsight.syn.spi.ExitCodes;
import com.loudsight.syn.spi.SynCommand;
import com.loudsight.syn.spi.Tier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/** Providers registered by this module's test META-INF/services, standing in for a plugin's commands. */
final class FakeCommands {

    /** Set by {@link Remote} and {@link Irreversible} when they actually run, to prove a refusal ran nothing. */
    static final List<String> RAN = new ArrayList<>();

    private FakeCommands() {
    }

    @Command(name = "test-echo", mixinStandardHelpOptions = true, description = "Echo the words back")
    public static final class Echo implements SynCommand {

        @Parameters(arity = "0..*")
        private List<String> words = new ArrayList<>();

        @Override
        public Tier tier() {
            return Tier.READ_ONLY;
        }

        @Override
        public List<String> keywords() {
            return List.of("repeat", "parrot");
        }

        @Override
        public int run(Context context) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("words", words);
            data.put("dryRun", context.dryRun());
            context.out().emit(data, String.join(" ", words) + (context.dryRun() ? " (dry run)" : ""));
            return ExitCodes.OK;
        }
    }

    @Command(name = "test-remote", mixinStandardHelpOptions = true, description = "Pretend to change a remote host")
    public static final class Remote implements SynCommand {

        @Override
        public Tier tier() {
            return Tier.REMOTE_MUTATE;
        }

        @Override
        public int run(Context context) {
            RAN.add("test-remote");
            return ExitCodes.OK;
        }
    }

    @Command(name = "test-local", mixinStandardHelpOptions = true, description = "Pretend to change this machine")
    public static final class Local implements SynCommand {

        @Override
        public Tier tier() {
            return Tier.LOCAL_MUTATE;
        }

        @Override
        public int run(Context context) {
            RAN.add("test-local");
            return ExitCodes.OK;
        }
    }

    @Command(name = "test-refuses", mixinStandardHelpOptions = true, description = "Cannot run on the host")
    public static final class Refuses implements SynCommand {

        @Override
        public Tier tier() {
            return Tier.READ_ONLY;
        }

        @Override
        public java.util.Optional<String> remoteRefusal() {
            return java.util.Optional.of("it only makes sense here");
        }

        @Override
        public int run(Context context) {
            RAN.add("test-refuses");
            return ExitCodes.OK;
        }
    }

    @Command(name = "test-irreversible", mixinStandardHelpOptions = true, description = "Pretend to do something irreversible")
    public static final class Irreversible implements SynCommand {

        @Override
        public Tier tier() {
            return Tier.IRREVERSIBLE;
        }

        @Override
        public int run(Context context) {
            RAN.add("test-irreversible");
            return ExitCodes.OK;
        }
    }

    /** Irreversible, but its dry run is a read-only preview: tierUnder lowers the tier for it. */
    @Command(name = "test-preview", mixinStandardHelpOptions = true, description = "Irreversible unless previewed with --dry-run")
    public static final class Preview implements SynCommand {

        @Override
        public Tier tier() {
            return Tier.IRREVERSIBLE;
        }

        @Override
        public Tier tierUnder(boolean dryRun) {
            return dryRun ? Tier.READ_ONLY : tier();
        }

        @Override
        public int run(Context context) {
            RAN.add(context.dryRun() ? "test-preview (dry run)" : "test-preview");
            return ExitCodes.OK;
        }
    }

    /** Exits with code 7 from a helper several calls down, the way a ported script's System.exit does. */
    @Command(name = "test-exit", mixinStandardHelpOptions = true, description = "Exits with code 7 from a nested helper")
    public static final class Exit implements SynCommand {

        @Override
        public Tier tier() {
            return Tier.READ_ONLY;
        }

        @Override
        public int run(Context context) {
            deeper();
            return ExitCodes.OK;
        }

        private static void deeper() {
            throw new CommandExit(7);
        }
    }

    /** Prints each command the context lists with its tier; its own tier follows {@code --elevated}, like a command whose options change what it does. */
    @Command(name = "test-list", mixinStandardHelpOptions = true, description = "Lists the commands the context knows")
    public static final class Listing implements SynCommand {

        @Option(names = "--elevated")
        boolean elevated;

        @Override
        public Tier tier() {
            return elevated ? Tier.LOCAL_MUTATE : Tier.READ_ONLY;
        }

        @Override
        public int run(Context context) {
            List<String> entries = context.commands().stream().map(command -> command.name() + "=" + command.tier()).toList();
            context.out().emit(entries, String.join(",", entries));
            return ExitCodes.OK;
        }
    }
    @Command(name = "test-boom", mixinStandardHelpOptions = true, description = "Always fails")
    public static final class Boom implements SynCommand {

        @Override
        public Tier tier() {
            return Tier.READ_ONLY;
        }

        @Override
        public int run(Context context) {
            throw new IllegalStateException("the disk is on fire");
        }
    }
}
