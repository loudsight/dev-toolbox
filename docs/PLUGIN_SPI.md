# Writing a syn command

A syn command is one class. The head finds it, parses its options, checks its tier, then calls `run`. This page is the whole
contract, with a command that was built, tested and run through the launcher to check it.

## The contract (`syn-spi`)

| Type | What it is |
|---|---|
| `SynCommand` | What you implement. `tier()` is required; `keywords()`, `tierUnder(dryRun)` and `remoteRefusal()` have defaults. `run(Context)` does the work and returns an exit code. |
| `Tier` | `READ_ONLY` (run freely), `LOCAL_MUTATE` (changes this machine or repo; run and report), `REMOTE_MUTATE` (changes a remote host), `IRREVERSIBLE` (a human at a terminal, with `--confirm`; agents are refused). Pick the strictest tier the command can reach. |
| `Context` | `repoRoot()`, `dryRun()` (report what you would do and change nothing), `json()`, `out()`, and `commands()` (every command the head found, for commands that check the command set). |
| `Output` | `emit(data, humanText)` is the result: JSON under `--json`, else the text. `println` is progress and `errorln` is a problem; under `--json` stdout carries only the one JSON document. |
| `ExitCodes` | `OK` 0, `FAILURE` 1, `USAGE` 2, `CONFIRMATION_REQUIRED` 3, `REFUSED` 4, `PLUGIN_CONFLICT` 5. Return `OK` or `FAILURE` from `run`; the head produces the rest. `throw new CommandExit(code)` ends the command from deep in a call tree. |

Options and arguments are ordinary [picocli](https://picocli.info) fields. The command name comes from `@Command(name = ...)`.

**Rules `syn script-check-conventions` enforces on every command** (all plugins, yours included):

- Name it `area-verb-object`: lowercase, hyphenated, at least two parts. The part before the first dash is the group the catalog files it under.
- The description says what the command achieves and ends in a "use when ..." clause, in at most 130 characters. It must differ from every other command's.
- Give it `keywords()`: what `syn catalog <term>` searches besides the name and description.

## An example

`syn-core` supplies helpers that commands share: `SynConfig` (reads `.syn/config.properties` at the repo root, and `-Dsyn.<key>=...`
overrides), `Cmd` (runs processes) and `Checks`. The command below counts `TODO` markers; the suffix of the files it reads is a
setting.

`src/main/java/com/example/todos/TodoCountMarkers.java`

```java
package com.example.todos;

import com.loudsight.syn.core.SynConfig;
import com.loudsight.syn.spi.Context;
import com.loudsight.syn.spi.ExitCodes;
import com.loudsight.syn.spi.SynCommand;
import com.loudsight.syn.spi.Tier;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/** Counts the TODO markers under the repository, so a pull request can say how many it adds. */
@Command(name = "todo-count-markers", mixinStandardHelpOptions = true,
        description = "count TODO markers in source files — use when judging how much unfinished work a change leaves")
public final class TodoCountMarkers implements SynCommand {

    @Option(names = "--max", description = "Fail when there are more markers than this")
    int max = Integer.MAX_VALUE;

    @Override
    public Tier tier() {
        return Tier.READ_ONLY;
    }

    @Override
    public List<String> keywords() {
        return List.of("todo", "unfinished", "markers", "fixme");
    }

    @Override
    public int run(Context context) throws IOException {
        String suffix = SynConfig.load(context.repoRoot()).get("todo.suffix").orElse(".java");
        long count = 0;
        try (Stream<Path> files = Files.walk(context.repoRoot())) {
            for (Path file : files.filter(f -> f.toString().endsWith(suffix)).toList()) {
                count += Files.readAllLines(file).stream().filter(line -> line.contains("TODO")).count();
            }
        }
        context.out().emit(new Result(count), count + " TODO markers");
        return count > max ? ExitCodes.FAILURE : ExitCodes.OK;
    }

    /** What {@code --json} prints. */
    record Result(long markers) {
    }
}
```

`src/main/resources/META-INF/services/com.loudsight.syn.spi.SynCommand` lists the class, one per line:

```
com.example.todos.TodoCountMarkers
```

The class needs a public no-argument constructor (the implicit one is fine). A command that is not listed there does not exist.

`pom.xml` depends on the three libraries the head already supplies as `provided`, so they stay out of your plugin. Use the
version of syn you run:

```xml
<dependency><groupId>com.loudsight</groupId><artifactId>syn-spi</artifactId><version>0.0.2</version><scope>provided</scope></dependency>
<dependency><groupId>com.loudsight</groupId><artifactId>syn-core</artifactId><version>0.0.2</version><scope>provided</scope></dependency>
<dependency><groupId>info.picocli</groupId><artifactId>picocli</artifactId><version>4.7.7</version><scope>provided</scope></dependency>
```

Java 25. Anything else your command needs is an ordinary dependency, and it is laid out beside the jar in the plugin's own
class loader, so it cannot clash with another plugin's versions.

## Testing it

`SynContext` and `ConsoleOutput` are public in `syn-core`, so a test builds a real context around a temporary directory and
calls `run`. Parse arguments with picocli first, so the options are populated as the head would populate them:

```java
class TodoCountMarkersTest {

    @TempDir
    Path root;

    private int run(String... args) throws Exception {
        TodoCountMarkers command = new TodoCountMarkers();
        new CommandLine(command).parseArgs(args);
        PrintStream sink = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
        return command.run(new SynContext(root, false, false, new ConsoleOutput(false, sink, sink), List::of));
    }

    @Test
    void passesUnderTheLimitAndFailsOverIt() throws Exception {
        Files.writeString(root.resolve("A.java"), "// TODO one\n// TODO two\n");
        assertEquals(ExitCodes.OK, run("--max", "2"));
        assertEquals(ExitCodes.FAILURE, run("--max", "1"));
    }
}
```

(Imports: `com.loudsight.syn.core.ConsoleOutput`, `com.loudsight.syn.core.SynContext`, `com.loudsight.syn.spi.ExitCodes`,
`picocli.CommandLine`, JUnit 5.) Test the refusal as well as the success: a command that cannot fail is not tested.

## Installing it

1. `mvn install` (or publish) your plugin jar.
2. In the repository's launcher `pom.xml` (see [`launcher/`](../launcher/README.md)), add an `<execution>` that copies it to
   `${syn.home}/plugins/<name>/current/libs`; the commented `my-commands` execution is the template.
3. Delete `.syn-cache/`, so the next `./syn` lays plugins out again.
4. `./syn catalog todo`, then `./syn todo-count-markers --json`, then `./syn script-check-conventions`. It also reports a stale
   `scripts/INDEX.md`; `./syn script-list-catalog --markdown` rewrites it.

`syn script-create-scaffold <area-verb-object> --plugin <name>` writes the class, the service entry and a header in this style
into a plugin module you already have (`scaffold.plugin-dir` and `scaffold.package` say where and under which package).

Two plugins that offer one command name are an error until `.syn/config.properties` says `override.<command>=<plugin>`;
`syn --list-plugins` shows who offers what.

## Habits that keep commands usable by agents

- Keep output short and structured: an agent will read it.
- Under `context.dryRun()`, report what would happen and change nothing.
