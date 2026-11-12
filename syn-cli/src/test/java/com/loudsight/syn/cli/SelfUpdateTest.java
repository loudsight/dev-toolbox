package com.loudsight.syn.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.loudsight.syn.cli.SelfUpdate.State;
import com.loudsight.syn.core.ReleaseLock;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SelfUpdateTest {

    @AfterEach
    void clearProperties() {
        for (String property : List.of(SelfUpdate.CACHE_PROPERTY, SelfUpdate.POM_PROPERTY, SelfUpdate.SOURCES_PROPERTY, SelfUpdate.PLUGINS_PROPERTY,
                SelfUpdate.PLUGINS_DIR_PROPERTY)) {
            System.clearProperty(property);
        }
    }

    @Test
    void aMatchingStampIsFresh() {
        assertEquals(State.FRESH, SelfUpdate.assess("abc", Optional.of("abc"), false, true));
        assertEquals(State.FRESH, SelfUpdate.assess("abc", Optional.of("abc"), true, true));
    }

    @Test
    void aDifferentStampIsStaleEvenRightAfterTheLauncherBuilt() {
        assertEquals(State.STALE, SelfUpdate.assess("new", Optional.of("old"), false, true));
        assertEquals(State.STALE, SelfUpdate.assess("new", Optional.of("old"), true, true));
    }

    @Test
    void aMissingStampIsAdoptedOnlyWhenTheLauncherJustBuilt() {
        assertEquals(State.ADOPT, SelfUpdate.assess("abc", Optional.empty(), true, true));
        assertEquals(State.STALE, SelfUpdate.assess("abc", Optional.empty(), false, true));
    }

    /** The launcher builds only the head: a matching stamp must not hide plugins that were never built. */
    @Test
    void aBuildMissingAPartIsStaleWhateverTheStampSays() {
        assertEquals(State.STALE, SelfUpdate.assess("abc", Optional.of("abc"), false, false));
        assertEquals(State.STALE, SelfUpdate.assess("abc", Optional.empty(), true, false));
    }

    /** Not launched by scripts/syn (no -Dsyn.cache): a test, the IDE - never rebuild or re-run. */
    @Test
    void doesNothingOutsideTheLauncher(@TempDir Path repo) {
        assertTrue(SelfUpdate.configure(repo).isEmpty());
        assertTrue(SelfUpdate.refresh(repo, "catalog").isEmpty());
    }

    @Test
    void theSwitchTurnsSelfUpdateOffEvenUnderTheLauncher(@TempDir Path repo) {
        System.setProperty(SelfUpdate.CACHE_PROPERTY, repo.resolve(".syn-cache").toString());

        assertTrue(SelfUpdate.configure(repo, true).isEmpty(), "SYN_NO_SELF_UPDATE keeps syn from rebuilding itself");
        assertTrue(SelfUpdate.configure(repo, false).isPresent());
    }

    @Test
    void theHeadAloneIsBuiltByDefault(@TempDir Path repo) {
        System.setProperty(SelfUpdate.CACHE_PROPERTY, repo.resolve(".syn-cache").toString());

        SelfUpdate.Config config = SelfUpdate.configure(repo, false).orElseThrow();

        assertEquals(SelfUpdate.DEFAULT_POM, config.pom());
        assertEquals(SelfUpdate.HEAD_SOURCES, config.sources());
        assertFalse(config.plugins());
        assertEquals(repo.resolve(".syn-cache/lib"), config.lib());
        assertEquals(repo.resolve(".syn-cache/plugins"), config.pluginsDir());
    }

    @Test
    void aRepoConfiguresItsOwnReactorSourcesAndPlugins(@TempDir Path repo) {
        System.setProperty(SelfUpdate.CACHE_PROPERTY, repo.resolve("cache").toString());
        System.setProperty(SelfUpdate.POM_PROPERTY, "build/syn/pom.xml");
        System.setProperty(SelfUpdate.SOURCES_PROPERTY, "a/b, c ,,d");
        System.setProperty(SelfUpdate.PLUGINS_PROPERTY, "true");

        SelfUpdate.Config config = SelfUpdate.configure(repo, false).orElseThrow();

        assertEquals("build/syn/pom.xml", config.pom());
        assertEquals(List.of("a/b", "c", "d"), config.sources());
        assertTrue(config.plugins());
    }

    @Test
    void pluginsLiveWhereTheRepoConfigurationSaysAndSelfUpdateAndTheHeadAgree(@TempDir Path repo) throws Exception {
        Files.createDirectories(repo.resolve(".syn"));
        Files.writeString(repo.resolve(".syn/config.properties"), "plugins.dir=tools/plugins\n");
        System.setProperty(SelfUpdate.CACHE_PROPERTY, repo.resolve(".syn-cache").toString());

        assertEquals(repo.resolve("tools/plugins"), Syn.pluginsDir(repo));
        assertEquals(repo.resolve("tools/plugins"), SelfUpdate.configure(repo, false).orElseThrow().pluginsDir());
    }

    @Test
    void aConfiguredPluginsDirIsWhereTheRebuiltPluginsGoAndNothingElseInItIsTouched(@TempDir Path repo) throws Exception {
        Path cache = repo.resolve(".syn-cache");
        Path pluginsDir = repo.resolve("mine");
        Path fresh = Files.createDirectories(cache.resolve("plugins.new"));
        Files.writeString(Files.createDirectories(fresh.resolve("built/current/libs")).resolve("built-2.jar"), "new");
        Files.writeString(Files.createDirectories(pluginsDir.resolve("built/current/libs")).resolve("built-1.jar"), "old");
        Files.writeString(Files.createDirectories(pluginsDir.resolve("installed/current/libs")).resolve("installed.jar"), "kept");

        SelfUpdate.swapPlugins(cache.resolve("plugins.old"), fresh, pluginsDir);

        assertEquals("new", Files.readString(pluginsDir.resolve("built/current/libs/built-2.jar")));
        assertFalse(Files.exists(pluginsDir.resolve("built/current/libs/built-1.jar")), "a rebuilt plugin is replaced whole");
        assertEquals("kept", Files.readString(pluginsDir.resolve("installed/current/libs/installed.jar")));
        assertFalse(Files.exists(cache.resolve("plugins.old")));
        assertFalse(Files.exists(fresh));
    }

    @Test
    void aMissingPluginsDirIsCreatedByTheFirstRebuild(@TempDir Path repo) throws Exception {
        Path cache = repo.resolve(".syn-cache");
        Path fresh = Files.createDirectories(cache.resolve("plugins.new/built/current/libs"));
        Files.writeString(fresh.resolve("built.jar"), "x");

        SelfUpdate.swapPlugins(cache.resolve("plugins.old"), cache.resolve("plugins.new"), repo.resolve("plugins"));

        assertTrue(Files.exists(repo.resolve("plugins/built/current/libs/built.jar")));
    }

    @Test
    void withoutConfigurationPluginsAreUnderTheCacheEvenWhenTheOldEnvironmentVariableWasMeantForThem(@TempDir Path repo) {
        System.setProperty(SelfUpdate.CACHE_PROPERTY, repo.resolve("c").toString());

        assertEquals(repo.resolve("c/plugins"), Syn.pluginsDir(repo));
    }

    @Test
    void aSuccessfulMavenRunPrintsNoMavenOutput() {
        List<String> printed = new ArrayList<>();
        List<Boolean> attempts = new ArrayList<>();

        SelfUpdate.mavenOrFail(List.of("package"), offline -> {
            attempts.add(offline);
            return new com.loudsight.scriptkit.Cmd.Result(0, "[INFO] noisy maven output");
        }, printed::add);

        assertEquals(List.of(true), attempts);
        assertTrue(printed.isEmpty(), printed.toString());
    }

    @Test
    void aFailedMavenRunPrintsBothAttemptsInFullAndThrows() {
        List<String> printed = new ArrayList<>();

        assertThrows(IllegalStateException.class, () -> SelfUpdate.mavenOrFail(List.of("package"),
                offline -> new com.loudsight.scriptkit.Cmd.Result(1, offline ? "OFFLINE-FAIL;" : "ONLINE-FAIL;"), printed::add));

        assertEquals(List.of("OFFLINE-FAIL;ONLINE-FAIL;"), printed);
    }

    /** A release edits the sources syn hashes and builds the same modules: a syn started elsewhere must not rebuild under it. */
    @Test
    void aStaleCacheIsNotRebuiltWhileAReleaseHoldsTheLock(@TempDir Path repo) throws IOException {
        SelfUpdate.Config config = staleCache(repo);
        List<String> builds = new ArrayList<>();
        List<String> reruns = new ArrayList<>();

        OptionalInt result;
        try (ReleaseLock _ = ReleaseLock.acquire(config.cache())) {
            result = SelfUpdate.refresh(config, ignoredConfig -> builds.add("build"), ignoredConfig -> {
                reruns.add("rerun");
                return 0;
            });
        }

        assertTrue(result.isEmpty(), "runs the cached build as it is");
        assertEquals(List.of(), builds);
        assertEquals(List.of(), reruns);
        assertEquals("old", Files.readString(config.stamp()).strip(), "the stamp still says the cache is stale");
    }

    @Test
    void aReleaseLockLeftByADeadProcessDoesNotStopTheRebuild(@TempDir Path repo) throws Exception {
        SelfUpdate.Config config = staleCache(repo);
        Process exited = new ProcessBuilder("true").start();
        assertTrue(exited.waitFor(60, TimeUnit.SECONDS));
        Files.writeString(ReleaseLock.path(config.cache()), "pid=" + exited.pid() + "\nstarted=" + ZonedDateTime.now(ZoneOffset.UTC).toInstant() + "\n");
        List<String> builds = new ArrayList<>();

        OptionalInt result = SelfUpdate.refresh(config, ignoredConfig -> builds.add("build"), ignoredConfig -> 3);

        assertEquals(OptionalInt.of(3), result, "rebuilt and re-ran");
        assertEquals(List.of("build"), builds);
    }

    @Test
    void aStaleCacheIsRebuiltWhenNoReleaseHoldsTheLock(@TempDir Path repo) throws IOException {
        SelfUpdate.Config config = staleCache(repo);
        List<String> builds = new ArrayList<>();

        OptionalInt result = SelfUpdate.refresh(config, ignoredConfig -> builds.add("build"), ignoredConfig -> 0);

        assertEquals(OptionalInt.of(0), result);
        assertEquals(List.of("build"), builds);
        assertFalse(Files.exists(config.cache().resolve(ReleaseLock.BUILD_LOCK)), "the build lock is released");
    }

    /** A cache whose stamp no longer matches its one source directory. */
    private static SelfUpdate.Config staleCache(Path repo) throws IOException {
        Path cache = repo.resolve(".syn-cache");
        Files.createDirectories(cache.resolve("lib"));
        Files.writeString(cache.resolve("stamp"), "old\n");
        Files.createDirectories(repo.resolve("src"));
        Files.writeString(repo.resolve("src/A.java"), "class A {}\n");
        return new SelfUpdate.Config(repo, cache, "pom.xml", List.of("src"), false, cache.resolve("plugins"));
    }
}
