package com.loudsight.syn.spi;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CommandDescriptorTest {

    private static CommandDescriptor descriptor(List<String> keywords) {
        return new CommandDescriptor("logs-fetch-host", "logs", "read-only", "copy a window of container logs", keywords, "builtin");
    }

    @Test
    void matchesWhenEveryTermIsInTheNamePurposeOrKeywordsIgnoringCase() {
        CommandDescriptor command = descriptor(List.of("ssh", "Docker"));

        assertTrue(command.matchesAll(List.of("FETCH", "docker")));
        assertTrue(command.matchesAll(List.of("window", "ssh")));
        assertTrue(command.matchesAll(List.of()));
    }

    @Test
    void doesNotMatchWhenAnyTermIsMissing() {
        assertFalse(descriptor(List.of("ssh")).matchesAll(List.of("fetch", "kubernetes")));
    }

    @Test
    void keepsItsOwnCopyOfTheKeywords() {
        List<String> keywords = new ArrayList<>(List.of("ssh"));
        CommandDescriptor command = descriptor(keywords);

        keywords.add("later");

        assertFalse(command.keywords().contains("later"));
        assertThrows(UnsupportedOperationException.class, () -> command.keywords().add("x"));
    }
}
