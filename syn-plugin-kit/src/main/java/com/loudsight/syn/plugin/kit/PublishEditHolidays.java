package com.loudsight.syn.plugin.kit;

import com.loudsight.syn.core.SynConfig;
import com.loudsight.syn.spi.Context;
import com.loudsight.syn.spi.ExitCodes;
import com.loudsight.syn.spi.SynCommand;
import com.loudsight.syn.spi.Tier;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Adds, removes and lists the days the public-repo working-hours block does not apply (see
 * {@link PublishCheckWorkingHours}). The list is a plain file ({@code publish.block-holidays-file}, default
 * {@code .syn/block-holidays.txt}) so it can also be edited by hand; this command keeps it sorted and validated.
 */
@Command(name = "publish-edit-holidays", mixinStandardHelpOptions = true,
        description = "list or edit days exempt from the public-repo working-hours block — use when a holiday or leave allows commits",
        footer = {"Examples:",
                "  syn publish-edit-holidays --add 2026-12-25 --label Christmas",
                "  syn publish-edit-holidays --add-range 2026-12-24..2026-12-31 --label 'year end'",
                "  syn publish-edit-holidays --remove 2026-12-25",
                "  syn publish-edit-holidays --list"})
public final class PublishEditHolidays implements SynCommand {

    @Option(names = "--add", paramLabel = "DATE", description = "Exempt this day (YYYY-MM-DD)")
    private @Nullable String add;

    @Option(names = "--add-range", paramLabel = "FROM..TO", description = "Exempt every day from FROM to TO inclusive")
    private @Nullable String addRange;

    @Option(names = "--remove", paramLabel = "DATE", description = "Stop exempting this day")
    private @Nullable String remove;

    @Option(names = "--list", description = "Print the exempt days")
    private boolean list;

    @Option(names = "--label", paramLabel = "TEXT", description = "A note stored with --add or --add-range")
    private @Nullable String label;

    @Override
    public Tier tier() {
        return Tier.LOCAL_MUTATE;
    }

    @Override
    public Tier tierUnder(boolean dryRun) {
        return dryRun || list ? Tier.READ_ONLY : Tier.LOCAL_MUTATE;
    }

    @Override
    public List<String> keywords() {
        return List.of("holiday", "leave", "working hours", "public repo", "exempt", "weekday", "block");
    }

    @Override
    public int run(Context context) throws IOException {
        int modes = (add != null ? 1 : 0) + (addRange != null ? 1 : 0) + (remove != null ? 1 : 0) + (list ? 1 : 0);
        if (modes != 1) {
            context.out().errorln("give exactly one of --add, --add-range, --remove, --list");
            return ExitCodes.USAGE;
        }
        SynConfig config = SynConfig.load(context.repoRoot());
        Path file = context.repoRoot().resolve(config.get(PublishCheckWorkingHours.HOLIDAYS_KEY).orElse(PublishCheckWorkingHours.DEFAULT_HOLIDAYS));
        Map<LocalDate, String> days;
        try {
            days = new TreeMap<>(BlockHolidays.read(file));
            if (list) {
                days.forEach((date, text) -> context.out().println(text.isEmpty() ? date.toString() : date + "  " + text));
                return ExitCodes.OK;
            }
            if (add != null) {
                days.put(LocalDate.parse(add), labelText());
            } else if (addRange != null) {
                addRange(days);
            } else if (days.remove(LocalDate.parse(Objects.requireNonNull(remove))) == null) {
                context.out().errorln(remove + " is not in " + file);
                return ExitCodes.FAILURE;
            }
        } catch (IllegalArgumentException | DateTimeParseException e) {
            context.out().errorln(String.valueOf(e.getMessage()));
            return ExitCodes.USAGE;
        }
        if (context.dryRun()) {
            context.out().println("would write " + days.size() + " day(s) to " + file);
            return ExitCodes.OK;
        }
        BlockHolidays.write(file, days);
        context.out().println("wrote " + days.size() + " day(s) to " + file);
        return ExitCodes.OK;
    }

    private String labelText() {
        return label == null ? "" : label;
    }

    private void addRange(Map<LocalDate, String> days) {
        String[] ends = Objects.requireNonNull(addRange).split("\\.\\.", -1);
        if (ends.length != 2) {
            throw new IllegalArgumentException("--add-range wants FROM..TO, e.g. 2026-12-24..2026-12-31: " + addRange);
        }
        LocalDate from = LocalDate.parse(ends[0]);
        LocalDate to = LocalDate.parse(ends[1]);
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("--add-range ends before it starts: " + addRange);
        }
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            days.put(day, labelText());
        }
    }
}
