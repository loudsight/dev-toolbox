package com.loudsight.syn.plugin.kit;

import com.loudsight.scriptkit.Checks;
import com.loudsight.scriptkit.Env;
import com.loudsight.syn.core.SynConfig;
import com.loudsight.syn.spi.Context;
import com.loudsight.syn.spi.ExitCodes;
import com.loudsight.syn.spi.SynCommand;
import com.loudsight.syn.spi.Tier;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Refuses a commit or push to a public repo during the working day on a weekday, so work on the public libraries
 * happens outside it. The hooks run this before {@code publish-check-private}.
 *
 * <p>Opt-in: with neither {@code publish.block-start} nor {@code publish.block-end} configured nothing is ever blocked.
 * Once configured, blocked: Monday to Friday, from {@code publish.block-start} up to but not including
 * {@code publish.block-end} (both HH:mm, both required), in {@code publish.block-zone} (an IANA zone id, default UTC, so a
 * region zone follows daylight saving), unless the date is listed in the holidays file ({@code publish.block-holidays-file}, edited with
 * {@code syn publish-edit-holidays}). Setting {@code SYN_WORKHOURS_OVERRIDE} to anything but {@code 0} or {@code false}
 * lets the commit through. A repo without a {@code .public-repo} marker is skipped. A malformed setting fails with the
 * key named rather than falling back, since a silent fallback would turn the block off. Reads nothing from stdin: the
 * pre-push hook needs it for the next check. Local only: CI does not run it, since it would block every weekday merge.
 */
@Command(name = "publish-check-working-hours", mixinStandardHelpOptions = true,
        description = "block a public-repo commit or push on a weekday during working hours — use when a public-repo hook runs before a commit or push",
        footer = {"Examples:",
                "  syn publish-check-working-hours                      # in a repo with .public-repo (the hooks do this)",
                "  SYN_WORKHOURS_OVERRIDE=1 git commit ...              # let one commit through",
                "Settings: publish.block-start, publish.block-end, publish.block-zone, publish.block-holidays-file."})
public final class PublishCheckWorkingHours implements SynCommand {

    static final String OVERRIDE = "SYN_WORKHOURS_OVERRIDE";
    static final String START_KEY = "publish.block-start";
    static final String END_KEY = "publish.block-end";
    static final String HOLIDAYS_KEY = "publish.block-holidays-file";
    static final String DEFAULT_HOLIDAYS = ".syn/block-holidays.txt";
    private static final String MARKER = ".public-repo";

    @Option(names = "--repo", paramLabel = "DIR", description = "The repo to check (default: the git repo of the current directory)")
    private @Nullable Path repo;

    @Option(names = "--at", paramLabel = "INSTANT", hidden = true, description = "Pretend it is this ISO-8601 instant (tests)")
    private @Nullable String at;

    @Override
    public Tier tier() {
        return Tier.READ_ONLY;
    }

    @Override
    public List<String> keywords() {
        return List.of("working hours", "weekday", "public repo", "commit", "push", "block", "pre-commit", "pre-push", "holiday", "override");
    }

    @Override
    public int run(Context context) throws IOException {
        Checks checks = new Checks(context.out().stream());
        Path repoDir = RepoDir.resolve(repo, context);
        if (!Files.exists(repoDir.resolve(MARKER))) {
            checks.skip(repoDir + " has no " + MARKER + " marker: not a public repo, nothing to check");
            return ExitCodes.OK;
        }
        SynConfig config = SynConfig.load(context.repoRoot());
        if (config.get(START_KEY).isEmpty() && config.get(END_KEY).isEmpty()) {
            checks.skip("no " + START_KEY + " / " + END_KEY + " configured: nothing is blocked");
            return ExitCodes.OK;
        }
        LocalTime start;
        LocalTime end;
        ZoneId zone;
        ZonedDateTime now;
        Map<LocalDate, String> holidays;
        try {
            start = time(config, START_KEY);
            end = time(config, END_KEY);
            zone = zone(config);
            now = at == null ? ZonedDateTime.now(zone) : Instant.parse(at).atZone(zone);
            holidays = BlockHolidays.read(context.repoRoot().resolve(config.get(HOLIDAYS_KEY).orElse(DEFAULT_HOLIDAYS)));
        } catch (IllegalArgumentException | DateTimeParseException e) {
            context.out().errorln(String.valueOf(e.getMessage()));
            return ExitCodes.USAGE;
        }

        String reason = blockedReason(now, start, end, holidays);
        if (reason == null) {
            String outside = "outside the working-hours block (" + now.toLocalDate() + " " + now.toLocalTime().withNano(0) + " " + zone + ")";
            checks.ok(outside);
            return ExitCodes.OK;
        }
        if (overrideActive(Env.get(OVERRIDE).orElse(""))) {
            String allowed = reason + " — allowed because " + OVERRIDE + " is set";
            checks.warn(allowed);
            return ExitCodes.OK;
        }
        checks.bad(reason);
        checks.detail("commit and push to public repos are blocked Mon-Fri " + start + "-" + end + " " + zone
                + "; set " + OVERRIDE + "=1 to override, or add a holiday with: syn publish-edit-holidays --add YYYY-MM-DD");
        return ExitCodes.FAILURE;
    }

    /** Why {@code now} is blocked, or null when it is not. */
    static @Nullable String blockedReason(ZonedDateTime now, LocalTime start, LocalTime end, Map<LocalDate, String> holidays) {
        DayOfWeek day = now.getDayOfWeek();
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY || holidays.containsKey(now.toLocalDate())) {
            return null;
        }
        LocalTime time = now.toLocalTime();
        if (time.isBefore(start) || !time.isBefore(end)) {
            return null;
        }
        return "public-repo commits are blocked during working hours (" + day + " " + time.withNano(0) + " " + now.getZone() + ")";
    }

    /** True when the override variable's value asks to let the commit through. */
    static boolean overrideActive(String value) {
        return !value.isBlank() && !"0".equals(value) && !"false".equals(value);
    }

    private static LocalTime time(SynConfig config, String key) {
        String value = config.get(key).orElseThrow(() -> new IllegalArgumentException(key + " is not set: set both " + START_KEY + " and " + END_KEY));
        try {
            return LocalTime.parse(value);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(key + " is not an HH:mm time: " + value, e);
        }
    }

    private static ZoneId zone(SynConfig config) {
        String value = config.get("publish.block-zone").orElse("UTC");
        try {
            return ZoneId.of(value);
        } catch (DateTimeException e) {
            throw new IllegalArgumentException("publish.block-zone is not a time zone: " + value, e);
        }
    }
}
