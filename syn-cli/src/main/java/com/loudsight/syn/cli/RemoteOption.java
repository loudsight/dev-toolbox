package com.loudsight.syn.cli;

import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * {@code --on-remote[=<user@host>]}, taken out of the arguments before picocli sees them. An option whose value is
 * optional would swallow the word after it ({@code syn --on-remote build-run-modules}), so the bare flag means the
 * default host and only the {@code =} form names one. {@code --sync} is taken out with it, but only alongside it: on its
 * own it belongs to the command. Everything after a bare {@code --} is the command's own.
 *
 * @param requested whether the flag was given
 * @param host the host named with {@code =}, or null for the default host
 * @param sync whether the host should pull and update its submodules first (only with {@code requested})
 * @param args the arguments without the flag: what the host's syn is run with
 */
record RemoteOption(boolean requested, @Nullable String host, boolean sync, List<String> args) {

    private static final String FLAG = "--on-remote";
    private static final String SYNC = "--sync";

    static RemoteOption extract(String... arguments) {
        List<String> rest = new ArrayList<>();
        List<String> restKeepingSync = new ArrayList<>();
        boolean requested = false;
        boolean sync = false;
        String host = "";
        boolean literal = false;
        for (String argument : arguments) {
            if (literal) {
                rest.add(argument);
                restKeepingSync.add(argument);
            } else if ("--".equals(argument)) {
                literal = true;
                rest.add(argument);
                restKeepingSync.add(argument);
            } else if (SYNC.equals(argument)) {
                sync = true;
                restKeepingSync.add(argument);
            } else if (FLAG.equals(argument)) {
                requested = true;
            } else if (argument.startsWith(FLAG + "=")) {
                requested = true;
                host = argument.substring(FLAG.length() + 1).strip();
            } else {
                rest.add(argument);
                restKeepingSync.add(argument);
            }
        }
        return new RemoteOption(requested, host.isEmpty() ? null : host, requested && sync,
                List.copyOf(requested ? rest : restKeepingSync));
    }
}
