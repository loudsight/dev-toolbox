package com.loudsight.syn.plugin.kit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.loudsight.syn.plugin.kit.Fixtures.Result;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PublishEditHolidaysTest {

    private static String file(Path root) throws Exception {
        return Files.readString(root.resolve(".syn/block-holidays.txt"));
    }

    @Test
    void addsKeepsTheFileSortedAndDeduplicated(@TempDir Path root) throws Exception {
        Fixtures.run(new PublishEditHolidays(), root, "--add", "2026-12-25", "--label", "Christmas");
        Fixtures.run(new PublishEditHolidays(), root, "--add", "2026-11-26");
        Fixtures.run(new PublishEditHolidays(), root, "--add", "2026-12-25", "--label", "Christmas Day");
        String text = file(root);
        assertTrue(text.indexOf("2026-11-26") < text.indexOf("2026-12-25"), text);
        assertEquals(1, text.split("2026-12-25", -1).length - 1, text);
        assertTrue(text.contains("# Christmas Day"), text);
    }

    @Test
    void addsARangeInclusive(@TempDir Path root) throws Exception {
        Result result = Fixtures.run(new PublishEditHolidays(), root, "--add-range", "2026-12-30..2027-01-01");
        assertEquals(0, result.exit(), result.stderr());
        String text = file(root);
        assertTrue(text.contains("2026-12-30") && text.contains("2026-12-31") && text.contains("2027-01-01"), text);
    }

    @Test
    void removesAndListsAndComplainsAboutAnUnknownDay(@TempDir Path root) throws Exception {
        Fixtures.run(new PublishEditHolidays(), root, "--add", "2026-12-25");
        assertEquals(1, Fixtures.run(new PublishEditHolidays(), root, "--remove", "2026-01-01").exit());
        assertEquals(0, Fixtures.run(new PublishEditHolidays(), root, "--remove", "2026-12-25").exit());
        assertFalse(file(root).contains("2026-12-25"));
        Fixtures.run(new PublishEditHolidays(), root, "--add", "2026-07-03");
        assertTrue(Fixtures.run(new PublishEditHolidays(), root, "--list").stdout().contains("2026-07-03"));
    }

    @Test
    void rejectsBadInputAndNeedsExactlyOneMode(@TempDir Path root) throws Exception {
        assertEquals(2, Fixtures.run(new PublishEditHolidays(), root, "--add", "next tuesday").exit());
        assertEquals(2, Fixtures.run(new PublishEditHolidays(), root, "--add-range", "2026-12-31..2026-12-30").exit());
        assertEquals(2, Fixtures.run(new PublishEditHolidays(), root).exit());
    }

    @Test
    void dryRunWritesNothing(@TempDir Path root) throws Exception {
        Result result = Fixtures.run(new PublishEditHolidays(), root, List.of(), true, "--add", "2026-12-25");
        assertEquals(0, result.exit());
        assertFalse(Files.exists(root.resolve(".syn/block-holidays.txt")));
    }
}
