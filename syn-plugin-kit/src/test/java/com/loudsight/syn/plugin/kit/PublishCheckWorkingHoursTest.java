package com.loudsight.syn.plugin.kit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.loudsight.syn.plugin.kit.Fixtures.Result;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PublishCheckWorkingHoursTest {

    // 2026-10-13 is a Tuesday; October is EDT (UTC-4), so 14:00Z is 10:00 Eastern.
    private static final String TUESDAY_10AM = "2026-10-13T14:00:00Z";

    private static Result check(Path root, String at) throws Exception {
        return Fixtures.run(new PublishCheckWorkingHours(), root, "--repo", root.toString(), "--at", at);
    }

    private static Path publicRepo(Path root) throws Exception {
        Fixtures.write(root.resolve(".public-repo"), "");
        return root;
    }

    @Test
    void aWeekdayMorningIsBlocked(@TempDir Path root) throws Exception {
        Result result = check(publicRepo(root), TUESDAY_10AM);
        assertEquals(1, result.exit(), result.stdout());
        assertTrue(result.stdout().contains("blocked during working hours"), result.stdout());
    }

    @Test
    void theWindowIsStartInclusiveEndExclusiveInTheZone(@TempDir Path root) throws Exception {
        publicRepo(root);
        assertEquals(0, check(root, "2026-10-13T10:59:59Z").exit(), "06:59:59 EDT is before the window");
        assertEquals(1, check(root, "2026-10-13T11:00:00Z").exit(), "07:00 EDT is inside it");
        assertEquals(1, check(root, "2026-10-13T22:59:59Z").exit(), "18:59:59 EDT is inside it");
        assertEquals(0, check(root, "2026-10-13T23:00:00Z").exit(), "19:00 EDT is outside it");
    }

    @Test
    void theZoneFollowsDaylightSaving(@TempDir Path root) throws Exception {
        publicRepo(root);
        // 2026-12-01 is a Tuesday in EST (UTC-5): 12:00Z is 07:00 and 11:59Z is 06:59.
        assertEquals(1, check(root, "2026-12-01T12:00:00Z").exit());
        assertEquals(0, check(root, "2026-12-01T11:59:59Z").exit());
    }

    @Test
    void aWeekendIsAllowed(@TempDir Path root) throws Exception {
        publicRepo(root);
        assertEquals(0, check(root, "2026-10-17T14:00:00Z").exit(), "Saturday");
        assertEquals(0, check(root, "2026-10-18T14:00:00Z").exit(), "Sunday");
    }

    @Test
    void aListedHolidayIsAllowed(@TempDir Path root) throws Exception {
        publicRepo(root);
        Fixtures.write(root.resolve(".syn/block-holidays.txt"), "# comment\n2026-10-13  # a day off\n");
        assertEquals(0, check(root, TUESDAY_10AM).exit());
        assertEquals(1, check(root, "2026-10-14T14:00:00Z").exit(), "the next day is still blocked");
    }

    @Test
    void aRepoWithoutTheMarkerIsSkipped(@TempDir Path root) throws Exception {
        Result result = check(root, TUESDAY_10AM);
        assertEquals(0, result.exit(), result.stdout());
        assertTrue(result.stdout().contains("not a public repo"), result.stdout());
    }

    @Test
    void configuredHoursAndZoneApply(@TempDir Path root) throws Exception {
        publicRepo(root);
        Fixtures.write(root.resolve(".syn/config.properties"),
                "publish.block-start=09:00\npublish.block-end=17:00\npublish.block-zone=UTC\n");
        assertEquals(0, check(root, "2026-10-13T08:59:00Z").exit());
        assertEquals(1, check(root, "2026-10-13T09:00:00Z").exit());
        assertEquals(0, check(root, "2026-10-13T17:00:00Z").exit());
    }

    @Test
    void aMalformedSettingFailsNamingTheKeyInsteadOfFallingBack(@TempDir Path root) throws Exception {
        publicRepo(root);
        Fixtures.write(root.resolve(".syn/config.properties"), "publish.block-start=breakfast\n");
        Result result = check(root, TUESDAY_10AM);
        assertEquals(2, result.exit());
        assertTrue(result.stderr().contains("publish.block-start"), result.stderr());
    }

    @Test
    void aMalformedHolidayLineFailsNamingTheLine(@TempDir Path root) throws Exception {
        publicRepo(root);
        Fixtures.write(root.resolve(".syn/block-holidays.txt"), "2026-10-13\nchristmas\n");
        Result result = check(root, TUESDAY_10AM);
        assertEquals(2, result.exit());
        assertTrue(result.stderr().contains(":2:"), result.stderr());
    }
}
