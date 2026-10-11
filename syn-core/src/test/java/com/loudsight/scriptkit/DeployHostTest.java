package com.loudsight.scriptkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The host is configuration, never code: these pin where {@link DeployHost#host()} reads it from. */
class DeployHostTest {

    private static final String POM_PROPERTY = "syn.build.pom";
    /** An environment with nothing set, so a developer's own DEPLOY_HOST cannot change the outcome. */
    private static @Nullable String noEnv(String ignoredName) {
        return null;
    }

    @AfterEach
    void clearProperties() {
        System.clearProperty(DeployHost.HOST_PROPERTY);
        System.clearProperty(DeployHost.CHECKOUT_PROPERTY);
        System.clearProperty(POM_PROPERTY);
    }

    @Test
    void theSystemPropertyNamesTheHost() {
        System.setProperty(DeployHost.HOST_PROPERTY, "  someone@box.invalid ");

        assertEquals("someone@box.invalid", DeployHost.host());
    }

    @Test
    void theHostIsReadFromThePropertiesFileBesideTheSynPom(@TempDir Path directory) throws IOException {
        Files.writeString(directory.resolve("deploy-host.properties"), "# a comment\nhost = file@box.invalid\n", StandardCharsets.UTF_8);
        System.setProperty(POM_PROPERTY, directory.resolve("pom.xml").toString());

        assertEquals("file@box.invalid", DeployHost.host(DeployHostTest::noEnv));
    }

    @Test
    void thePropertyBeatsTheFile(@TempDir Path directory) throws IOException {
        Files.writeString(directory.resolve("deploy-host.properties"), "host=file@box.invalid\n", StandardCharsets.UTF_8);
        System.setProperty(POM_PROPERTY, directory.resolve("pom.xml").toString());
        System.setProperty(DeployHost.HOST_PROPERTY, "property@box.invalid");

        assertEquals("property@box.invalid", DeployHost.host());
    }

    @Test
    void withNothingConfiguredItFailsInsteadOfGuessing(@TempDir Path directory) {
        System.setProperty(POM_PROPERTY, directory.resolve("pom.xml").toString());

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> DeployHost.host(DeployHostTest::noEnv));

        String message = Objects.requireNonNull(failure.getMessage());
        assertTrue(message.contains("no deploy host configured"), message);
    }

    @Test
    void theCheckoutIsReadFromTheSameFileAndPropertyBeatsIt(@TempDir Path directory) throws IOException {
        Files.writeString(directory.resolve("deploy-host.properties"), "host=h@box.invalid\ncheckout=/srv/from-file\n", StandardCharsets.UTF_8);
        System.setProperty(POM_PROPERTY, directory.resolve("pom.xml").toString());

        assertEquals("/srv/from-file", DeployHost.checkout(DeployHostTest::noEnv));

        System.setProperty(DeployHost.CHECKOUT_PROPERTY, "/srv/from-property");
        assertEquals("/srv/from-property", DeployHost.checkout(DeployHostTest::noEnv));
    }

    @Test
    void aMissingCheckoutFailsNamingTheKeyInsteadOfGuessing(@TempDir Path directory) throws IOException {
        Files.writeString(directory.resolve("deploy-host.properties"), "host=h@box.invalid\n", StandardCharsets.UTF_8);
        System.setProperty(POM_PROPERTY, directory.resolve("pom.xml").toString());

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> DeployHost.checkout(DeployHostTest::noEnv));

        assertTrue(Objects.requireNonNull(failure.getMessage()).contains("no deploy checkout configured"), failure.getMessage());
    }

    @Test
    void theEnvironmentBeatsTheFileAndRemoteHostBeatsBoth(@TempDir Path directory) throws IOException {
        Files.writeString(directory.resolve("deploy-host.properties"), "host=file@box.invalid\n", StandardCharsets.UTF_8);
        System.setProperty(POM_PROPERTY, directory.resolve("pom.xml").toString());
        Function<String, @Nullable String> both = name -> "REMOTE_HOST".equals(name) ? "remote@box.invalid" : "env@box.invalid";
        Function<String, @Nullable String> deployOnly = name -> "DEPLOY_HOST".equals(name) ? "env@box.invalid" : null;

        assertEquals("env@box.invalid", DeployHost.host(deployOnly));
        assertEquals("remote@box.invalid", DeployHost.resolveHost(null, both));
        assertEquals("env@box.invalid", DeployHost.resolveHost(null, deployOnly));
    }

    @Test
    void anExplicitHostBeatsRemoteHostWhichBeatsTheConfiguredHost() {
        System.setProperty(DeployHost.HOST_PROPERTY, "configured@box.invalid");

        assertEquals("explicit@box.invalid", DeployHost.resolveHost(" explicit@box.invalid ", name -> "remote@box.invalid"));
        assertEquals("remote@box.invalid", DeployHost.resolveHost(null, name -> "remote@box.invalid"));
        assertEquals("remote@box.invalid", DeployHost.resolveHost("  ", name -> "remote@box.invalid"));
        assertEquals("configured@box.invalid", DeployHost.resolveHost(null, name -> null));
        assertEquals("configured@box.invalid", DeployHost.resolveHost(null, name -> " "));
    }

    @Test
    void aFileWithoutAHostKeyCountsAsNotConfigured(@TempDir Path directory) throws IOException {
        Files.writeString(directory.resolve("deploy-host.properties"), "other=1\n", StandardCharsets.UTF_8);
        System.setProperty(POM_PROPERTY, directory.resolve("pom.xml").toString());

        assertThrows(IllegalStateException.class, () -> DeployHost.host(DeployHostTest::noEnv));
    }

    @Test
    void syncIgnoreIsACommaSeparatedListFromThePropertiesFileAndEmptyWhenUnset(@TempDir Path directory) throws IOException {
        Files.writeString(directory.resolve("deploy-host.properties"), "host=h@box.invalid\nsync.ignore= kb, docs/ ,\n", StandardCharsets.UTF_8);
        System.setProperty(POM_PROPERTY, directory.resolve("pom.xml").toString());

        assertEquals(java.util.Set.of("kb", "docs"), DeployHost.syncIgnore());

        Files.writeString(directory.resolve("deploy-host.properties"), "host=h@box.invalid\n", StandardCharsets.UTF_8);
        assertEquals(java.util.Set.of(), DeployHost.syncIgnore());
        assertEquals(java.util.Set.of(), DeployHost.parseIgnore(null));
    }
}
