package com.loudsight.syn.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class RemoteOptionTest {

    @Test
    void withoutTheFlagTheArgumentsAreUntouched() {
        RemoteOption option = RemoteOption.extract(new String[] {"build-run-modules", "--modules", "x"});

        assertFalse(option.requested());
        assertEquals(List.of("build-run-modules", "--modules", "x"), option.args());
    }

    @Test
    void theBareFlagIsRemovedFromAnyPositionAndNamesNoHost() {
        for (String[] args : new String[][] {{"--on-remote", "a", "b"}, {"a", "--on-remote", "b"}, {"a", "b", "--on-remote"}}) {
            RemoteOption option = RemoteOption.extract(args);

            assertTrue(option.requested());
            assertNull(option.host());
            assertEquals(List.of("a", "b"), option.args());
        }
    }

    @Test
    void theEqualsFormNamesTheHostAndABlankValueMeansTheDefault() {
        assertEquals("u@h", RemoteOption.extract(new String[] {"a", "--on-remote= u@h "}).host());
        assertNull(RemoteOption.extract(new String[] {"a", "--on-remote="}).host());
        assertTrue(RemoteOption.extract(new String[] {"a", "--on-remote="}).requested());
    }

    @Test
    void anythingAfterADoubleDashBelongsToTheCommand() {
        RemoteOption option = RemoteOption.extract(new String[] {"a", "--", "--on-remote"});

        assertFalse(option.requested());
        assertEquals(List.of("a", "--", "--on-remote"), option.args());
    }

    @Test
    void aSimilarlyNamedOptionIsNotTheFlag() {
        RemoteOption option = RemoteOption.extract(new String[] {"a", "--on-remote-host"});

        assertFalse(option.requested());
        assertEquals(List.of("a", "--on-remote-host"), option.args());
    }

    @Test
    void syncIsTakenOutAlongsideTheFlagFromAnyPosition() {
        RemoteOption option = RemoteOption.extract("--sync", "a", "--on-remote", "b", "--sync");

        assertTrue(option.requested() && option.sync());
        assertEquals(List.of("a", "b"), option.args());
    }

    @Test
    void syncAloneBelongsToTheCommandAndIsLeftInPlace() {
        RemoteOption option = RemoteOption.extract("a", "--sync", "b");

        assertFalse(option.requested() || option.sync());
        assertEquals(List.of("a", "--sync", "b"), option.args());
    }

    @Test
    void aSyncAfterADoubleDashIsTheCommandsEvenWithTheFlag() {
        RemoteOption option = RemoteOption.extract("a", "--on-remote", "--", "--sync");

        assertTrue(option.requested());
        assertFalse(option.sync());
        assertEquals(List.of("a", "--", "--sync"), option.args());
    }
}
