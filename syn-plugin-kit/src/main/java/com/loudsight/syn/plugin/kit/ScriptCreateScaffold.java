package com.loudsight.syn.plugin.kit;

import com.loudsight.syn.core.Names;
import com.loudsight.syn.core.SynConfig;
import com.loudsight.syn.spi.CommandDescriptor;
import com.loudsight.syn.spi.Context;
import com.loudsight.syn.spi.ExitCodes;
import com.loudsight.syn.spi.SynCommand;
import com.loudsight.syn.spi.Tier;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * Writes the command class into a plugin module and registers it in that module's META-INF/services, so it is found on
 * the next run. The description and keywords placeholders make {@code syn script-check-conventions} fail until they
 * are filled in - on purpose.
 *
 * <p>Where plugin modules live, and the package their commands go in, are repo conventions, set in
 * {@code .syn/config.properties} (see {@link SynConfig}): {@code scaffold.plugin-dir} (default {@code plugins/{plugin}}),
 * {@code scaffold.package} (default {@code com.example.syn.{plugin}}) and {@code scaffold.default-plugin} (default: none,
 * so {@code --plugin} is required). {@code {plugin}} stands for the plugin's name.
 */
@Command(name = "script-create-scaffold", mixinStandardHelpOptions = true,
        description = "create a new syn command that already follows the conventions — use when a task needs a repeatable command",
        footer = "Example: syn script-create-scaffold logs-summarize-deploy --tier read-only --plugin ops")
public final class ScriptCreateScaffold implements SynCommand {

    private static final String SERVICES = "src/main/resources/META-INF/services/com.loudsight.syn.spi.SynCommand";
    private static final String PLACEHOLDER = "{plugin}";
    private static final Pattern PLUGIN_NAME = Pattern.compile("[a-z][a-z0-9]*");

    private static String render(String packageName, String commandName, String className, String tierName) {
        return """
            package @PACKAGE@;

            import com.loudsight.syn.spi.Context;
            import com.loudsight.syn.spi.ExitCodes;
            import com.loudsight.syn.spi.SynCommand;
            import com.loudsight.syn.spi.Tier;
            import java.util.List;
            import picocli.CommandLine.Command;

            /**
             * <what it does, and the history that makes it necessary>
             *
             * <p>Runs on syn-core: Cmd for commands, Context#repoRoot for paths, Context#out for output,
             * DeployHost for the deploy host, Checks for ok/FAIL lists.
             */
            @Command(name = "@NAME@", mixinStandardHelpOptions = true,
                    description = "<outcome, not mechanism> — use when <situation>")
            public final class @CLASS@ implements SynCommand {

                @Override
                public Tier tier() {
                    return Tier.@TIER@;
                }

                @Override
                public List<String> keywords() {
                    return List.of("<the words someone would search for>");
                }

                @Override
                public int run(Context context) {
                    // Under context.dryRun() report what would happen and change nothing. Keep output short and
                    // structured: an agent will read it.
                    return ExitCodes.OK;
                }
            }
            """.replace("@PACKAGE@", packageName).replace("@NAME@", commandName).replace("@CLASS@", className).replace("@TIER@", tierName);
    }

    @Parameters(index = "0", paramLabel = "AREA-VERB-OBJECT", description = "The command's name, e.g. logs-merge-timeline")
    String name = "";

    @Option(names = "--tier", defaultValue = "read-only", description = "read-only, local-mutate, remote-mutate or irreversible")
    String tier = "read-only";

    @Option(names = "--plugin", description = "The plugin to add it to (where it lives: scaffold.plugin-dir; default: scaffold.default-plugin)")
    String plugin = "";

    @Override
    public Tier tier() {
        return Tier.LOCAL_MUTATE;
    }

    @Override
    public List<String> keywords() {
        return List.of("new command", "new script", "scaffold", "template", "create script", "boilerplate", "syn plugin");
    }

    @Override
    public int run(Context context) throws IOException {
        if (!Names.isAreaVerb(name)) {
            return refuse(context, ExitCodes.USAGE, "name must be area-verb[-object], e.g. logs-merge-timeline");
        }
        Tier chosen;
        try {
            chosen = Tier.parse(tier);
        } catch (IllegalArgumentException e) {
            return refuse(context, ExitCodes.USAGE, "tier must be read-only, local-mutate, remote-mutate or irreversible");
        }
        SynConfig config = SynConfig.load(context.repoRoot());
        String pluginName = plugin.isEmpty() ? config.get("scaffold.default-plugin").orElse("") : plugin;
        if (pluginName.isEmpty()) {
            return refuse(context, ExitCodes.USAGE, "say which plugin with --plugin, or set scaffold.default-plugin in " + SynConfig.DIRECTORY + "/" + SynConfig.FILE);
        }
        if (!PLUGIN_NAME.matcher(pluginName).matches()) {
            return refuse(context, ExitCodes.USAGE, "a plugin name is lowercase letters and digits, starting with a letter: " + pluginName);
        }
        Path module = context.repoRoot().resolve(config.get("scaffold.plugin-dir").orElse("plugins/" + PLACEHOLDER).replace(PLACEHOLDER, pluginName)).normalize();
        if (!Files.isDirectory(module)) {
            return refuse(context, ExitCodes.USAGE, "no plugin module " + context.repoRoot().relativize(module) + " (scaffold.plugin-dir)");
        }
        String packageName = config.get("scaffold.package").orElse("com.example.syn." + PLACEHOLDER).replace(PLACEHOLDER, pluginName);
        for (CommandDescriptor existing : context.commands()) {
            if (existing.name().equals(name)) {
                return refuse(context, ExitCodes.FAILURE, "a command with this name already exists (" + existing.origin() + ")");
            }
        }

        String className = className(name);
        Path source = module.resolve("src/main/java/" + packageName.replace('.', '/') + "/" + className + ".java");
        if (Files.exists(source)) {
            return refuse(context, ExitCodes.FAILURE, "already exists: " + context.repoRoot().relativize(source));
        }
        String code = render(packageName, name, className, chosen.name());
        if (context.dryRun()) {
            context.out().println("[dry-run] would create " + context.repoRoot().relativize(source) + " and register it");
            return ExitCodes.OK;
        }
        Files.createDirectories(Objects.requireNonNull(source.getParent()));
        Files.writeString(source, code, StandardCharsets.UTF_8);
        register(module.resolve(SERVICES), packageName + "." + className);
        context.out().println("created " + context.repoRoot().relativize(source) + " (run: syn " + name
                + ") - fill in the description and keywords, then run syn script-check-conventions");
        return ExitCodes.OK;
    }

    private static int refuse(Context context, int code, String message) {
        context.out().errorln(message);
        return code;
    }

    /** {@code area-verb-object} → {@code AreaVerbObject}. */
    static String className(String commandName) {
        StringBuilder out = new StringBuilder();
        for (String part : commandName.split("-", -1)) {
            if (!part.isEmpty()) {
                out.append(part.substring(0, 1).toUpperCase(Locale.ROOT)).append(part.substring(1));
            }
        }
        return out.toString();
    }

    /** Adds {@code provider} to the services file, creating the file (and its directory) when the plugin has none. */
    private static void register(Path services, String provider) throws IOException {
        Files.createDirectories(Objects.requireNonNull(services.getParent()));
        String existing = Files.exists(services) ? Files.readString(services, StandardCharsets.UTF_8) : "";
        String line = (existing.isEmpty() || existing.endsWith("\n") ? "" : "\n") + provider + "\n";
        Files.writeString(services, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}
