package com.loudsight.syn.spi;

import java.util.List;
import java.util.Optional;

/**
 * A command a plugin contributes. Implementations are picocli {@code @Command} classes (the command
 * name and description come from the annotation; options and parameters are ordinary picocli
 * fields) registered in {@code META-INF/services/com.loudsight.syn.spi.SynCommand}, with a public
 * no-argument constructor. Name them {@code area-verb-object}; the part before the first dash is the
 * group the catalog files them under.
 *
 * <p>The head parses the arguments, applies the {@link #tier()} rules, then calls {@link #run}.
 */
public interface SynCommand {

    Tier tier();

    /**
     * The tier this particular run is held to, given the global {@code --dry-run}. The default is {@link #tier()} whatever the
     * flag. A command whose dry run provably changes nothing returns a lower tier for it, so a read-only preview is not refused
     * for what the real run does; {@link #tier()} stays the strictest, which is what the catalog lists. Options of the command
     * itself (its own flags) may be consulted too: they are populated before this is asked.
     */
    default Tier tierUnder(boolean dryRun) {
        return tier();
    }

    /** Extra search terms for {@code syn catalog}; the name and description are searched anyway. */
    default List<String> keywords() {
        return List.of();
    }

    /**
     * Why {@code --on-remote} cannot run this command on the deploy host, or empty when it can. Refuse a command that
     * already reaches the host itself (it would hop twice) or that only makes sense on the machine it was typed on.
     */
    default Optional<String> remoteRefusal() {
        return Optional.empty();
    }

    /**
     * Runs the command after its options are populated.
     *
     * @return an exit code: {@link ExitCodes#OK} on success
     */
    int run(Context context) throws Exception;
}
