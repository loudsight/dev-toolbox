package com.loudsight.syn.cli;

import com.loudsight.scriptkit.DeployHost;
import com.loudsight.scriptkit.Env;
import com.loudsight.syn.core.ConsoleOutput;
import com.loudsight.syn.core.RemoteExec;
import com.loudsight.syn.core.SynConfig;
import com.loudsight.syn.core.SynContext;
import com.loudsight.syn.core.SyncCheck;
import com.loudsight.syn.core.TierGuard;
import com.loudsight.syn.spi.CommandDescriptor;
import com.loudsight.syn.spi.CommandExit;
import com.loudsight.syn.spi.ExitCodes;
import com.loudsight.syn.spi.SynCommand;
import com.loudsight.syn.spi.Tier;
import com.loudsight.useful.helper.logging.LoggingHelper;
import java.io.Console;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParseResult;
import picocli.CommandLine.ScopeType;

/**
 * The syn head: a picocli root whose subcommands are the {@link SynCommand}s found on the classpath
 * and in plugins. The global flags work before or after the subcommand ({@code syn --json x} or
 * {@code syn x --json}). Before a command runs, the head applies its tier's rules.
 */
@Command(name = "syn", mixinStandardHelpOptions = true, version = "syn 0.0.1",
        description = "The repo's task CLI: reproducible, tiered, scriptable commands from pluggable libraries. Start with 'syn catalog'.",
        footer = {"",
                "On any command: --on-remote[=<user@host>] runs the same command line on the deploy host, in its checkout"
                        + " (host: the value, else REMOTE_HOST, else DEPLOY_HOST). It pulls nothing and warns loudly when"
                        + " the host's code differs from this machine's; add --sync to make the host pull origin/master and update"
                        + " its submodules first (it refuses over hand edits on the host). Example:",
                "  syn build-run-modules --modules '*backend*' --on-remote --sync"})
public final class Syn {

    private static final LoggingHelper LOGGER = LoggingHelper.wrap(Syn.class);

    @Option(names = "--json", scope = ScopeType.INHERIT, description = "Print results as one JSON document on stdout")
    private boolean json;

    @Option(names = "--confirm", scope = ScopeType.INHERIT,
            description = "Confirm an irreversible command; accepted and ignored elsewhere")
    private boolean confirm;

    @Option(names = "--dry-run", scope = ScopeType.INHERIT, description = "Report what would happen and change nothing")
    private boolean dryRun;

    @Option(names = "--list-plugins", description = "List the plugins found, what each provides, which override wins and what is wrong, then exit;"
            + " works even when a conflict stops every other command")
    private boolean listPlugins;

    private Syn() {
    }

    /** What running on the deploy host needs from outside this class; swapped for a fake in tests. */
    interface Remote {

        /** The loud warning lines when the host's checkout differs from {@code repoRoot}'s; none when it matches. */
        List<String> warning(Path repoRoot, String host, String checkout);

        /** The warning lines before a {@code --sync} run, which pulls: whether origin/master is what is here. */
        List<String> warningBeforeSync(Path repoRoot);

        /** Runs the request on the host, writing its output to {@code out}, and returns the exit code. */
        int run(RemoteExec.Request request, PrintStream out, PrintStream err) throws InterruptedException;
    }

    private static final Remote SSH = new Remote() {
        @Override
        public List<String> warning(Path repoRoot, String host, String checkout) {
            return SyncCheck.warning(repoRoot, host, checkout, DeployHost.syncIgnore());
        }

        @Override
        public List<String> warningBeforeSync(Path repoRoot) {
            return SyncCheck.warningBeforeSync(repoRoot, DeployHost.syncIgnore());
        }

        @Override
        public int run(RemoteExec.Request request, PrintStream out, PrintStream err) throws InterruptedException {
            return RemoteExec.run(request, out, err);
        }
    };

    public static void main(String[] args) {
        Path repoRoot = Env.repoRoot();
        OptionalInt superseded = SelfUpdate.refresh(repoRoot, args);
        if (superseded.isPresent()) {
            System.exit(superseded.getAsInt());
        }
        boolean interactive = Optional.ofNullable(System.console()).map(Console::isTerminal).orElse(false);
        System.exit(run(args, repoRoot, pluginsDir(repoRoot), System.out, System.err, interactive));
    }

    /**
     * Where plugins are loaded from: {@code plugins.dir} in the repo's syn configuration (see {@link SynConfig}), else
     * {@code plugins} under the cache the launcher builds into. {@link SelfUpdate} builds into the same place.
     */
    static Path pluginsDir(Path repoRoot) {
        Path cache = Optional.ofNullable(System.getProperty(SelfUpdate.CACHE_PROPERTY)).map(Path::of).orElseGet(() -> repoRoot.resolve(".syn-cache"));
        return pluginsDir(repoRoot, cache);
    }

    static Path pluginsDir(Path repoRoot, Path cache) {
        return SynConfig.load(repoRoot).path("plugins.dir").orElseGet(() -> cache.resolve("plugins"));
    }

    /** Runs one syn invocation and returns its exit code; {@link #main} is this plus {@code System.exit}. */
    static int run(String[] args, Path repoRoot, Path pluginsDir, PrintStream out, PrintStream err, boolean interactive) {
        return run(args, repoRoot, pluginsDir, out, err, interactive, SSH);
    }

    static int run(String[] args, Path repoRoot, Path pluginsDir, PrintStream out, PrintStream err, boolean interactive, Remote remote) {
        // Taken out before picocli parses: an option with an optional value would swallow the command name after it.
        RemoteOption onRemote = RemoteOption.extract(args);
        Syn root = new Syn();
        CommandLine commandLine = new CommandLine(root);
        Map<String, String> origins = new HashMap<>();
        // Described before any argument is parsed, so a command whose tier follows its options (--lower, --markdown) is
        // listed at its declared default, not as the run in progress has configured it: the catalog must not depend on
        // which command generated it.
        AtomicReference<List<CommandDescriptor>> described = new AtomicReference<>(List.of());
        Supplier<List<CommandDescriptor>> catalog = described::get;
        List<PluginConflicts.Candidate<CommandLine>> candidates = new ArrayList<>();
        candidates.add(new PluginConflicts.Candidate<>("catalog", PluginLoader.BUILTIN, new CommandLine(new Catalog(catalog))));
        for (PluginLoader.Provided provided : PluginLoader.discover(pluginsDir, Thread.currentThread().getContextClassLoader(), message -> err.println("syn: " + message))) {
            try {
                CommandLine sub = new CommandLine(provided.command());
                candidates.add(new PluginConflicts.Candidate<>(sub.getCommandName(), provided.origin(), sub));
            } catch (CommandLine.InitializationException e) {
                err.println("syn: skipping " + provided.command().getClass().getName() + " from " + provided.origin() + ": " + e.getMessage());
            }
        }
        PluginConflicts.Resolution<CommandLine> resolution = PluginConflicts.resolve(candidates,
                SynConfig.load(repoRoot).withPrefix(PluginConflicts.OVERRIDE_PREFIX));
        for (PluginConflicts.Candidate<CommandLine> kept : resolution.kept()) {
            commandLine.addSubcommand(kept.name(), kept.item());
            origins.put(kept.name(), kept.origin());
        }
        String pluginReport = PluginConflicts.report(pluginsDir, candidates, resolution);

        described.set(describe(commandLine, origins));
        // After the subcommands are added: picocli hands the writers only to the subcommands present at the time.
        commandLine.setOut(new PrintWriter(out, true, StandardCharsets.UTF_8));
        commandLine.setErr(new PrintWriter(err, true, StandardCharsets.UTF_8));
        commandLine.setExecutionStrategy(parseResult -> execute(parseResult, root, new Session(repoRoot, out, err, interactive, catalog, onRemote, remote, pluginReport, resolution.problems())));
        return commandLine.execute(onRemote.args().toArray(String[]::new));
    }

    /** What one invocation runs against, apart from the parsed arguments. */
    private record Session(Path repoRoot, PrintStream out, PrintStream err, boolean interactive,
                           Supplier<List<CommandDescriptor>> catalog, RemoteOption onRemote, Remote remote,
                           String pluginReport, List<String> pluginProblems) {
    }

    private static int execute(ParseResult parseResult, Syn root, Session session) {
        PrintStream out = session.out();
        PrintStream err = session.err();
        Integer help = CommandLine.executeHelpRequest(parseResult);
        if (help != null) {
            return help;
        }
        if (root.listPlugins) {
            out.println(session.pluginReport());
            return ExitCodes.OK;
        }
        if (!session.pluginProblems().isEmpty()) {
            session.pluginProblems().forEach(problem -> err.println("syn: " + problem));
            err.println("syn: no command runs until this is fixed; 'syn --list-plugins' shows every plugin and what it provides");
            return ExitCodes.PLUGIN_CONFLICT;
        }
        ParseResult leaf = parseResult;
        while (leaf.hasSubcommand()) {
            leaf = leaf.subcommand();
        }
        CommandSpec spec = leaf.commandSpec();
        if (!(spec.userObject() instanceof SynCommand command)) {
            spec.commandLine().usage(out);
            return ExitCodes.USAGE;
        }
        // What this run is held to: a command whose dry run changes nothing may declare a lower tier for it (SynCommand#tierUnder).
        Tier tier = command.tierUnder(root.dryRun);
        TierGuard.Decision decision = TierGuard.check(tier, root.confirm, session.interactive());
        if (!decision.allowed()) {
            err.println("syn: " + spec.name() + ": " + decision.message());
            return decision.exitCode();
        }
        try {
            if (session.onRemote().requested()) {
                return executeRemote(command, tier, spec.name(), session);
            }
            return command.run(new SynContext(session.repoRoot(), root.json, root.dryRun, new ConsoleOutput(root.json, out, err), session.catalog()));
        } catch (CommandExit e) {
            return e.code();
        } catch (Exception e) {
            err.println("syn: " + spec.name() + ": " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            if (System.getenv("SYN_DEBUG") != null) {
                LOGGER.logError("stack trace of the failure in " + spec.name(), e);
            }
            return ExitCodes.FAILURE;
        }
    }

    /**
     * {@code --on-remote}: the same command line on the deploy host. The tier rules have already run here and are never
     * relaxed: an irreversible command is refused (a human runs it on the host), and a local-mutate command is reported
     * as the remote-mutate it becomes, because what it mutates is the host.
     */
    private static int executeRemote(SynCommand command, Tier tier, String name, Session session) throws InterruptedException {
        if (tier == Tier.IRREVERSIBLE) {
            session.err().println("syn: " + name + ": --on-remote refused: an irreversible command is run by a human on the host");
            return ExitCodes.REFUSED;
        }
        Optional<String> refusal = command.remoteRefusal();
        if (refusal.isPresent()) {
            session.err().println("syn: " + name + ": --on-remote refused: " + refusal.get());
            return ExitCodes.REFUSED;
        }
        String host = DeployHost.resolveHost(session.onRemote().host());
        String checkout = DeployHost.checkout();
        boolean sync = session.onRemote().sync();
        (sync ? session.remote().warningBeforeSync(session.repoRoot()) : session.remote().warning(session.repoRoot(), host, checkout))
                .forEach(session.err()::println);
        if (tier == Tier.LOCAL_MUTATE) {
            session.err().println("syn: " + name + " is local-mutate; with --on-remote it changes the host, so treat it as remote-mutate");
        }
        // A command that changes things runs detached, so a dropped connection cannot end it half way; a read-only one just streams.
        boolean detach = tier != Tier.READ_ONLY;
        session.err().println("syn: " + name + " on " + host + ":" + checkout + (sync ? " (pulling first)" : "") + (detach ? " (detached)" : ""));
        return session.remote().run(new RemoteExec.Request(host, checkout, session.onRemote().args(), sync, detach), session.out(), session.err());
    }

    private static List<CommandDescriptor> describe(CommandLine commandLine, Map<String, String> origins) {
        List<CommandDescriptor> commands = new ArrayList<>();
        commandLine.getSubcommands().forEach((name, sub) -> {
            if (sub.getCommandSpec().userObject() instanceof SynCommand command) {
                String[] description = sub.getCommandSpec().usageMessage().description();
                String group = name.contains("-") ? name.substring(0, name.indexOf('-')) : name;
                commands.add(new CommandDescriptor(name, group, command.tier().label(), description.length == 0 ? "" : description[0],
                        command.keywords(), origins.getOrDefault(name, PluginLoader.BUILTIN)));
            }
        });
        commands.sort(Comparator.comparing(CommandDescriptor::name));
        return commands;
    }
}
