package com.loudsight.syn.spi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class TierTest {

    @Test
    void everyTierRoundTripsThroughItsLabel() {
        for (Tier tier : Tier.values()) {
            assertEquals(tier, Tier.parse(tier.label()));
        }
    }

    @Test
    void labelsMatchTheAgentsMdTierNames() {
        assertEquals("read-only", Tier.READ_ONLY.label());
        assertEquals("local-mutate", Tier.LOCAL_MUTATE.label());
        assertEquals("remote-mutate", Tier.REMOTE_MUTATE.label());
        assertEquals("irreversible", Tier.IRREVERSIBLE.label());
    }

    @Test
    void anUnknownLabelIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> Tier.parse("read_only"));
    }
}
