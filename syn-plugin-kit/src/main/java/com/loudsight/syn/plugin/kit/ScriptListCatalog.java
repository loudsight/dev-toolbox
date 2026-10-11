package com.loudsight.syn.plugin.kit;

import com.loudsight.scriptkit.ScriptHeader;
import com.loudsight.scriptkit.ScriptLibrary;
import com.loudsight.syn.core.CatalogIndex;
import com.loudsight.syn.spi.CommandDescriptor;
import com.loudsight.syn.spi.Context;
import com.loudsight.syn.spi.ExitCodes;
import com.loudsight.syn.spi.Output;
import com.loudsight.syn.spi.SynCommand;
import com.loudsight.syn.spi.Tier;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Lists every syn command and every remaining library script with its tier and purpose. Commands carry their
 * description, tier and keywords in code; scripts carry the machine-readable header. {@code --markdown} rewrites the
 * generated index; {@code --check} exits 1 if that file is stale, which {@code syn script-check-conventions} uses.
 * Where the scripts live, the index's path and the text above its table are configuration (see {@link ScriptLibrary}
 * and {@link CatalogIndex}): {@code catalog.script-dirs}, {@code catalog.script-exempt}, {@code catalog.index} and
 * {@code catalog.header-file}.
 */
@Command(name = "script-list-catalog", mixinStandardHelpOptions = true,
        description = "list every command and library script with its tier and purpose — use when looking for a tool before writing one",
        footer = "Example: syn script-list-catalog --markdown   # regenerate the index after adding a command or script")
public final class ScriptListCatalog implements SynCommand {

    @Option(names = "--markdown", description = "Rewrite the generated index (catalog.index, default scripts/INDEX.md)")
    private boolean markdown;

    @Option(names = "--check", description = "Exit 1 if the generated index is stale")
    private boolean check;

    @Override
    public Tier tier() {
        return markdown ? Tier.LOCAL_MUTATE : Tier.READ_ONLY;
    }

    @Override
    public List<String> keywords() {
        return List.of("catalog", "index", "find a script", "discover tools", "which script", "list scripts", "INDEX.md");
    }

    @Override
    public int run(Context context) throws IOException {
        Path root = context.repoRoot();
        ScriptLibrary library = ScriptLibrary.load(root);
        Path index = root.resolve(library.index());
        Output out = context.out();
        if (markdown && check) {
            context.out().errorln("script-list-catalog: choose --markdown or --check, not both");
            return ExitCodes.USAGE;
        }
        if (markdown) {
            Files.createDirectories(Objects.requireNonNull(index.getParent()));
            Files.writeString(index, CatalogIndex.markdown(root, context.commands()), StandardCharsets.UTF_8);
            out.println("wrote " + library.index() + " (" + (library.catalogFiles().size() + context.commands().size()) + " entries)");
            return ExitCodes.OK;
        }
        if (check) {
            if (!CatalogIndex.isCurrent(root, context.commands())) {
                context.out().errorln(library.index() + " is stale - run: syn script-list-catalog --markdown");
                return ExitCodes.FAILURE;
            }
            return ExitCodes.OK;
        }
        for (CommandDescriptor command : context.commands()) {
            out.println(String.format(Locale.ROOT, "%-40s %-14s %s", "syn " + command.name(), command.tier(), command.purpose()));
        }
        for (String file : library.catalogFiles()) {
            Map<String, String> header = ScriptHeader.read(root.resolve(file));
            out.println(String.format(Locale.ROOT, "%-40s %-14s %s", ScriptLibrary.commandName(file),
                    header.getOrDefault("tier", ""), header.getOrDefault("purpose", "")));
        }
        return ExitCodes.OK;
    }
}
