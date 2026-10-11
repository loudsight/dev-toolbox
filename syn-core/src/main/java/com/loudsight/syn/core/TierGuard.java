package com.loudsight.syn.core;

import com.loudsight.syn.spi.ExitCodes;
import com.loudsight.syn.spi.Tier;

/**
 * The rules a tier imposes before its command runs. The head applies them, so no command can forget:
 * read-only, local-mutate and remote-mutate run (asking for the command is the confirmation, so remote-mutate
 * needs no flag); irreversible needs {@code --confirm} and a person at a terminal, which an agent's piped shell never
 * is ("irreversible never (a human runs it)").
 */
public final class TierGuard {

    /** The verdict: when not allowed, {@code exitCode} and {@code message} say why. */
    public record Decision(boolean allowed, int exitCode, String message) {

        static Decision allow() {
            return new Decision(true, ExitCodes.OK, "");
        }

        static Decision deny(int exitCode, String message) {
            return new Decision(false, exitCode, message);
        }
    }

    private TierGuard() {
    }

    /**
     * Whether a command of {@code tier} may run now.
     *
     * @param confirmed whether {@code --confirm} was given
     * @param interactive whether stdin and stdout are a terminal
     */
    public static Decision check(Tier tier, boolean confirmed, boolean interactive) {
        return switch (tier) {
            case READ_ONLY, LOCAL_MUTATE, REMOTE_MUTATE -> Decision.allow();
            case IRREVERSIBLE -> {
                if (!interactive) {
                    yield Decision.deny(ExitCodes.REFUSED,
                            "irreversible command: refused outside an interactive terminal - a human runs it");
                }
                yield confirmed
                        ? Decision.allow()
                        : Decision.deny(ExitCodes.CONFIRMATION_REQUIRED, "irreversible command: re-run with --confirm");
            }
        };
    }
}
