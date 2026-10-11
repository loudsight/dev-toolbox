package com.loudsight.syn.core;

import com.loudsight.syn.spi.CommandDescriptor;
import com.loudsight.syn.spi.Context;
import com.loudsight.syn.spi.Output;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;

/** The head's {@link Context}: the repo root, the global flags, where to write, and the command catalog on demand. */
public record SynContext(Path repoRoot, boolean json, boolean dryRun, Output out, Supplier<List<CommandDescriptor>> catalog)
        implements Context {

    /** A context with no commands to list: for tests and callers that never ask. */
    public SynContext(Path repoRoot, boolean json, boolean dryRun, Output out) {
        this(repoRoot, json, dryRun, out, List::of);
    }

    @Override
    public List<CommandDescriptor> commands() {
        return catalog.get();
    }
}
