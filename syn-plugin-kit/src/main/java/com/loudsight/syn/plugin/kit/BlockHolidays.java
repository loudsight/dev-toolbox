package com.loudsight.syn.plugin.kit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The days the working-hours block does not apply: one {@code YYYY-MM-DD} per line with an optional {@code # label},
 * blank lines and whole-line comments ignored. A line that is not a date is an error naming the file and line, never
 * skipped, so a typo cannot silently leave a holiday blocked or (worse) unblocked.
 */
final class BlockHolidays {

    private BlockHolidays() {
    }

    /** The dates in {@code file} with their labels, in date order; a missing file is empty. */
    static Map<LocalDate, String> read(Path file) throws IOException {
        Map<LocalDate, String> days = new TreeMap<>();
        if (!Files.isRegularFile(file)) {
            return days;
        }
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            if (line.isEmpty() || line.charAt(0) == '#') {
                continue;
            }
            int hash = line.indexOf('#');
            String date = (hash < 0 ? line : line.substring(0, hash)).strip();
            String label = hash < 0 ? "" : line.substring(hash + 1).strip();
            try {
                days.put(LocalDate.parse(date), label);
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException(file + ":" + (i + 1) + ": not a YYYY-MM-DD date: " + date, e);
            }
        }
        return days;
    }

    /** Writes {@code days} sorted, one per line, keeping the file's leading comment block out (it is regenerated). */
    static void write(Path file, Map<LocalDate, String> days) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("# Days the public-repo working-hours block does not apply. Managed by `syn publish-edit-holidays`.");
        days.forEach((date, label) -> lines.add(label.isEmpty() ? date.toString() : date + "  # " + label));
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(file, lines, StandardCharsets.UTF_8);
    }
}
