package com.loudsight.syn.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.loudsight.syn.spi.ExitCodes;
import com.loudsight.syn.spi.Tier;
import org.junit.jupiter.api.Test;

class TierGuardTest {

    @Test
    void readOnlyAndLocalMutateAlwaysRun() {
        for (Tier tier : new Tier[] {Tier.READ_ONLY, Tier.LOCAL_MUTATE}) {
            assertTrue(TierGuard.check(tier, false, false).allowed(), tier.label());
        }
    }

    @Test
    void remoteMutateRunsWithoutConfirmOrATerminal() {
        assertTrue(TierGuard.check(Tier.REMOTE_MUTATE, false, false).allowed());
        assertTrue(TierGuard.check(Tier.REMOTE_MUTATE, true, false).allowed());
    }

    @Test
    void irreversibleIsRefusedWithoutATerminalEvenWhenConfirmed() {
        TierGuard.Decision piped = TierGuard.check(Tier.IRREVERSIBLE, true, false);
        assertFalse(piped.allowed());
        assertEquals(ExitCodes.REFUSED, piped.exitCode());
    }

    @Test
    void irreversibleAtATerminalStillNeedsConfirm() {
        assertEquals(ExitCodes.CONFIRMATION_REQUIRED, TierGuard.check(Tier.IRREVERSIBLE, false, true).exitCode());
        assertTrue(TierGuard.check(Tier.IRREVERSIBLE, true, true).allowed());
    }
}
