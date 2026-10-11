package com.loudsight.syn.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.loudsight.syn.cli.PluginConflicts.Candidate;
import com.loudsight.syn.cli.PluginConflicts.Resolution;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PluginConflictsTest {

    private static Candidate<String> offer(String name, String origin) {
        return new Candidate<>(name, origin, origin + ":" + name);
    }

    @Test
    void commandsWithDistinctNamesAreAllKeptAndNothingIsWrong() {
        Resolution<String> resolution = PluginConflicts.resolve(List.of(offer("a", "one"), offer("b", "two")), Map.of());

        assertEquals(2, resolution.kept().size());
        assertTrue(resolution.problems().isEmpty());
        assertTrue(resolution.shadowed().isEmpty());
    }

    @Test
    void aCollisionWithoutAnOverrideIsAProblemThatNamesBothPluginsAndTheKeyToSet() {
        Resolution<String> resolution = PluginConflicts.resolve(List.of(offer("a", "one"), offer("a", "two")), Map.of());

        assertEquals(1, resolution.problems().size());
        String problem = resolution.problems().get(0);
        assertTrue(problem.contains("one and two"), problem);
        assertTrue(problem.contains("override.a=<plugin>"), problem);
        assertTrue(problem.contains(".syn/config.properties"), problem);
    }

    @Test
    void anOverrideKeepsItsPluginAndRecordsWhoLost() {
        Resolution<String> resolution = PluginConflicts.resolve(List.of(offer("a", "one"), offer("a", "two")), Map.of("a", "two"));

        assertTrue(resolution.problems().isEmpty(), resolution.problems().toString());
        assertEquals(List.of("two:a"), resolution.kept().stream().map(Candidate::item).toList());
        assertEquals(Map.of("a", List.of("one")), resolution.shadowed());
    }

    @Test
    void anOverrideNamingAPluginThatDoesNotProvideTheCommandIsAProblem() {
        Resolution<String> resolution = PluginConflicts.resolve(List.of(offer("a", "one"), offer("a", "two")), Map.of("a", "three"));

        assertEquals(1, resolution.problems().size());
        assertTrue(resolution.problems().get(0).contains("does not provide a"), resolution.problems().get(0));
    }

    @Test
    void anOverrideWhereThereIsNoCollisionIsStaleAndAProblem() {
        Resolution<String> resolution = PluginConflicts.resolve(List.of(offer("a", "one")), Map.of("a", "one"));

        assertEquals(1, resolution.problems().size());
        assertTrue(resolution.problems().get(0).contains("overrides nothing"), resolution.problems().get(0));
    }

    @Test
    void anOverrideForACommandNobodyProvidesIsStaleAndAProblem() {
        Resolution<String> resolution = PluginConflicts.resolve(List.of(offer("a", "one")), Map.of("gone", "one"));

        assertEquals(1, resolution.problems().size());
        assertTrue(resolution.problems().get(0).contains("no plugin provides gone"), resolution.problems().get(0));
    }

    @Test
    void aPluginOfferingTheSameNameTwiceCannotBeSelectedByAnOverride() {
        Resolution<String> resolution = PluginConflicts.resolve(
                List.of(offer("a", "one"), new Candidate<>("a", "one", "second"), offer("a", "two")), Map.of("a", "one"));

        assertEquals(1, resolution.problems().size());
        assertTrue(resolution.problems().get(0).contains("more than once"), resolution.problems().get(0));
    }

    @Test
    void theReportListsWhatEachPluginProvidesWhoWonAndWhatIsWrong() {
        List<Candidate<String>> candidates = List.of(offer("a", "one"), offer("a", "two"), offer("b", "one"));
        Resolution<String> overridden = PluginConflicts.resolve(candidates, Map.of("a", "two"));
        Resolution<String> broken = PluginConflicts.resolve(candidates, Map.of());

        String report = PluginConflicts.report(Path.of("/plugins"), candidates, overridden);
        assertTrue(report.contains("plugins dir: /plugins"), report);
        assertTrue(report.contains("one: a, b"), report);
        assertTrue(report.contains("a: two replaces one"), report);
        assertTrue(PluginConflicts.report(Path.of("/plugins"), candidates, broken).contains("no command runs until these are fixed"));
    }
}
