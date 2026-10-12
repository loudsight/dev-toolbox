package com.loudsight.syn.plugin.kit;

import com.loudsight.scriptkit.Cmd;
import com.loudsight.syn.spi.Context;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/** Which repo a hook-run command is about: the one named by {@code --repo}, else the git repo of the current directory. */
final class RepoDir {

    private RepoDir() {
    }

    static Path resolve(@Nullable Path repo, Context context) {
        if (repo != null) {
            return repo.toAbsolutePath();
        }
        Cmd.Result top = Cmd.of("git", "rev-parse", "--show-toplevel").dir(Path.of("").toAbsolutePath()).discardErr().capture();
        return top.ok() && !top.stdout().isBlank() ? Path.of(top.stdout().strip()) : context.repoRoot();
    }
}
