package com.loudsight.syn.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class NamesTest {

    @Test
    void anAreaVerbNameIsTwoOrMoreLowercaseParts() {
        assertTrue(Names.isAreaVerb("logs-fetch"));
        assertTrue(Names.isAreaVerb("logs-fetch-host"));
        assertTrue(Names.isAreaVerb("pom-check-v2"));
    }

    @Test
    void aSinglePartOrAMalformedNameIsRejected() {
        assertFalse(Names.isAreaVerb("catalog"));
        assertFalse(Names.isAreaVerb(""));
        assertFalse(Names.isAreaVerb("logs--fetch"));
        assertFalse(Names.isAreaVerb("-logs-fetch"));
        assertFalse(Names.isAreaVerb("logs-fetch-"));
        assertFalse(Names.isAreaVerb("Logs-fetch"));
        assertFalse(Names.isAreaVerb("logs_fetch"));
    }

    @Test
    void aScriptFileNameNeedsAKnownExtension() {
        assertTrue(Names.isScriptFileName("shell-setup-startup.sh"));
        assertTrue(Names.isScriptFileName("a-b.py"));
        assertTrue(Names.isScriptFileName("a-b.mjs"));
        assertFalse(Names.isScriptFileName("shell-setup-startup"));
        assertFalse(Names.isScriptFileName("shell-setup-startup.txt"));
        assertFalse(Names.isScriptFileName("setup.sh"));
        assertFalse(Names.isScriptFileName(".sh"));
    }
}
