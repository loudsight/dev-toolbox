package com.loudsight.scriptkit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.loudsight.scriptkit.DockerImageBuild.Config;
import com.loudsight.scriptkit.DockerImageBuild.PackageLookup;
import com.loudsight.scriptkit.DockerImageBuild.User;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DockerImageBuildTest {

    @Test
    void readsConfigWithTheDirectoryAsModuleName(@TempDir Path dir) throws Exception {
        Path module = Files.createDirectories(dir.resolve("backend-server"));
        Files.writeString(module.resolve(".docker-image"), "# comment\npackage=tarball-exact-version\nuser-args=false\n");
        assertEquals(new Config("backend-server", PackageLookup.TARBALL_EXACT_VERSION, false, true), Config.read(module));
    }

    @Test
    void findsPackagesTheWayEachLookupSays(@TempDir Path module) throws Exception {
        Path target = Files.createDirectories(module.resolve("target"));
        for (String file : List.of("svc-1.0-sources.jar", "svc-1.0.jar", "svc-1.0-tests.jar")) {
            Files.writeString(target.resolve(file), "x");
        }
        assertEquals(Optional.of(target.resolve("svc-1.0.jar")),
                DockerImageBuild.findPackage(module, new Config("svc", PackageLookup.TARBALL_OR_JAR, true, true), "1.0"));
        Files.writeString(target.resolve("svc-1.0-bin.tar.gz"), "x");
        assertEquals(Optional.of(target.resolve("svc-1.0-bin.tar.gz")),
                DockerImageBuild.findPackage(module, new Config("svc", PackageLookup.TARBALL_OR_JAR, true, true), "1.0"));
        assertEquals(Optional.empty(),
                DockerImageBuild.findPackage(module, new Config("svc", PackageLookup.TARBALL_EXACT_VERSION, true, true), "2.0"));
        assertEquals(Optional.of(target.resolve("svc-1.0.jar")),
                DockerImageBuild.findPackage(module, new Config("svc", PackageLookup.JAR, true, true), "1.0"));
        assertEquals(Optional.empty(),
                DockerImageBuild.findPackage(module, new Config("svc", PackageLookup.NONE, true, true), "1.0"));
    }

    @Test
    void buildsTheCommandInTheShellScriptsOrder(@TempDir Path module) throws Exception {
        Path pkg = Files.createDirectories(module.resolve("target")).resolve("svc-1.0-bin.tar.gz");
        Files.writeString(pkg, "abc", StandardCharsets.UTF_8);
        List<String> labels = DockerImageBuild.labels(pkg, "abc1234-dirty", Instant.parse("2026-09-27T10:11:12.345Z"));
        assertEquals(List.of(
                "--label", "com.loudsight.package-file=svc-1.0-bin.tar.gz",
                "--label", "com.loudsight.package-sha256=ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                "--label", "com.loudsight.source-sha=abc1234-dirty",
                "--label", "com.loudsight.built-at=2026-09-27T10:11:12Z"), labels);
        List<String> command = DockerImageBuild.command(module, "1.0", List.of("r/svc:1.0", "r/svc:latest"),
                new Config("svc", PackageLookup.TARBALL_OR_JAR, true, true), pkg, new User("501", "20", "me"), labels);
        assertEquals(List.of("docker", "build", "--build-arg", "VERSION=1.0",
                "--build-arg", "PACKAGE_FILE=target/svc-1.0-bin.tar.gz",
                "--build-arg", "UID=501", "--build-arg", "GID=20", "--build-arg", "UNAME=me"), command.subList(0, 12));
        assertEquals(List.of("-t", "r/svc:1.0", "-t", "r/svc:latest", "-f", module.resolve("Dockerfile").toString(), module.toString()),
                command.subList(20, command.size()));
        assertEquals(List.of("docker", "build", "--build-arg", "VERSION=1.0", "-t", "r/n:1.0", "-f", module.resolve("Dockerfile").toString(), module.toString()),
                DockerImageBuild.command(module, "1.0", List.of("r/n:1.0"), new Config("n", PackageLookup.NONE, false, false), null, null, List.of()));
    }
}
