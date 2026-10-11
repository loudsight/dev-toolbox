package com.loudsight.syn.spi;

import java.nio.file.Path;
import java.util.List;

/** What the head hands a command when it runs. */
public interface Context {

    /** The superproject root. */
    Path repoRoot();

    /** True under {@code --json}: the command should {@link Output#emit} structured data. */
    boolean json();

    /** True under {@code --dry-run}: the command must report what it would do and change nothing. */
    boolean dryRun();

    Output out();

    /** Every command the head found, builtin and from plugins, by name: for commands that check or list the command set. */
    List<CommandDescriptor> commands();
}
