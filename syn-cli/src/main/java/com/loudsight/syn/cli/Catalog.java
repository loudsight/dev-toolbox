package com.loudsight.syn.cli;

import com.loudsight.syn.spi.CommandDescriptor;
import com.loudsight.syn.spi.Context;
import com.loudsight.syn.spi.ExitCodes;
import com.loudsight.syn.spi.SynCommand;
import com.loudsight.syn.spi.Tier;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/** {@code syn catalog}: the commands available, searchable by keyword. Replaces grepping script headers. */
@Command(name = "catalog", mixinStandardHelpOptions = true,
        description = "list syn commands with tier and purpose, filtered by terms — use when looking for a command before writing one")
public final class Catalog implements SynCommand {

    private final Supplier<List<CommandDescriptor>> commands;

    @Option(names = "--markdown", description = "Print a markdown table instead of text")
    private boolean markdown;

    @Parameters(arity = "0..*", paramLabel = "TERM", description = "Search terms, matched against name, purpose and keywords")
    private final List<String> terms = new ArrayList<>();

    Catalog(Supplier<List<CommandDescriptor>> commands) {
        this.commands = commands;
    }

    @Override
    public Tier tier() {
        return Tier.READ_ONLY;
    }

    @Override
    public List<String> keywords() {
        return List.of("list", "search", "find", "commands", "index", "help");
    }

    @Override
    public int run(Context context) {
        List<CommandDescriptor> matching = commands.get().stream().filter(command -> command.matchesAll(terms)).toList();
        context.out().emit(matching, markdown ? markdownTable(matching) : text(matching));
        return ExitCodes.OK;
    }

    private static String text(List<CommandDescriptor> commands) {
        int width = commands.stream().mapToInt(command -> command.name().length()).max().orElse(0);
        StringBuilder out = new StringBuilder();
        for (CommandDescriptor command : commands) {
            out.append(pad(command.name(), width)).append("  ").append(pad(command.tier(), Tier.REMOTE_MUTATE.label().length())).append("  ").append(command.purpose()).append(System.lineSeparator());
        }
        return out.toString().stripTrailing();
    }

    private static String pad(String text, int width) {
        return text + " ".repeat(Math.max(0, width - text.length()));
    }

    private static String markdownTable(List<CommandDescriptor> commands) {
        StringBuilder out = new StringBuilder("| Command | Tier | Purpose | Keywords |\n|---|---|---|---|");
        for (CommandDescriptor command : commands) {
            out.append(String.format(Locale.ROOT, "%n| `syn %s` | %s | %s | %s |", command.name(), command.tier(),
                    command.purpose().replace("|", "\\|"), String.join(", ", command.keywords()).replace("|", "\\|")));
        }
        return out.toString();
    }
}
