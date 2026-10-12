package com.loudsight.syn.plugin.kit;

import com.loudsight.syn.core.ConsoleOutput;
import com.loudsight.syn.core.SynContext;
import com.loudsight.syn.spi.CommandExit;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import picocli.CommandLine;

/**
 * A stand-in for the {@code syn} executable the CI workflow installs from Maven Central: it runs
 * {@code publish-check-private} from this module's classes, so {@link PublicGuardWorkflowTest} can run the workflow's real script.
 */
public final class StubSyn {

    private StubSyn() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0 || !"publish-check-private".equals(args[0])) {
            System.err.println("StubSyn only knows publish-check-private");
            System.exit(2);
        }
        PublishCheckPrivate command = new PublishCheckPrivate();
        new CommandLine(command).parseArgs(Arrays.copyOfRange(args, 1, args.length));
        SynContext context = new SynContext(Path.of("").toAbsolutePath(), false, false,
                new ConsoleOutput(false, System.out, System.err), () -> List.of());
        int exit;
        try {
            exit = command.run(context);
        } catch (CommandExit e) {
            exit = e.code();
        }
        System.out.flush();
        System.exit(exit);
    }
}
