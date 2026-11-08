package com.loudsight.syn.cli;

import com.loudsight.syn.core.RemoteExec;
import com.loudsight.syn.core.SynConfig;
import com.loudsight.syn.spi.HostSync;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Which {@link HostSync} a remote run uses. Two providers is a problem until {@code override.host-sync=<plugin>} picks one
 * (the rule commands follow). The optional {@code remote.host-sync=<plugin>} names the plugin that must supply it: when that
 * plugin is missing, a remote run is refused rather than quietly using the default.
 *
 * @param hostSync the one to use; the built-in default when no plugin provides one
 * @param origin where it comes from
 * @param problems what stops syn running commands
 * @param refusal why a remote run must not start, or empty
 */
record HostSyncChoice(HostSync hostSync, String origin, List<String> problems, Optional<String> refusal) {

    static final String NAME = "host-sync";

    static final String REQUIRED_KEY = "remote.host-sync";

    private static final String DEFAULT_ORIGIN = "default";

    static HostSyncChoice resolve(List<PluginLoader.Service<HostSync>> found, SynConfig config) {
        List<PluginConflicts.Candidate<HostSync>> candidates = found.stream()
                .map(service -> new PluginConflicts.Candidate<>(NAME, service.origin(), service.service()))
                .toList();
        Map<String, String> overrides = onlyHostSync(config.withPrefix(PluginConflicts.OVERRIDE_PREFIX));
        PluginConflicts.Resolution<HostSync> resolution = PluginConflicts.resolve(candidates, overrides);
        List<String> problems = new ArrayList<>(resolution.problems());
        Optional<String> refusal = config.get(REQUIRED_KEY)
                .filter(required -> candidates.stream().noneMatch(candidate -> candidate.origin().equals(required)))
                .map(required -> REQUIRED_KEY + "=" + required + " but that plugin provides no host sync (found: "
                        + (candidates.isEmpty() ? "none" : String.join(", ", candidates.stream().map(PluginConflicts.Candidate::origin).toList()))
                        + "); install it or remove the key, a remote run will not use the default instead");
        if (resolution.kept().isEmpty()) {
            return new HostSyncChoice(RemoteExec.DEFAULT_HOST_SYNC, DEFAULT_ORIGIN, problems, refusal);
        }
        PluginConflicts.Candidate<HostSync> kept = resolution.kept().get(0);
        return new HostSyncChoice(kept.item(), kept.origin(), problems, refusal);
    }

    /** The override entry that is about the host sync. */
    static Map<String, String> onlyHostSync(Map<String, String> overrides) {
        return overrides.entrySet().stream().filter(entry -> NAME.equals(entry.getKey()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /** The overrides that are about commands: everything but the host sync. */
    static Map<String, String> withoutHostSync(Map<String, String> overrides) {
        return overrides.entrySet().stream().filter(entry -> !NAME.equals(entry.getKey()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /** The lines {@code --list-plugins} adds. */
    String report() {
        StringBuilder out = new StringBuilder(NAME).append(": ").append(origin);
        problems.forEach(problem -> out.append(System.lineSeparator()).append("  problem: ").append(problem));
        refusal.ifPresent(reason -> out.append(System.lineSeparator()).append("  remote runs refused: ").append(reason));
        return out.toString();
    }
}
