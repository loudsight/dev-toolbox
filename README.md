# dev-toolbox

**syn**: a task CLI for a repository. Repeatable tasks are named, tiered commands (`read-only`, `local-mutate`, `remote-mutate`,
`irreversible`) that a person or an agent runs the same way every time, instead of a pile of scripts. `syn catalog <term>` finds
one; `syn <command> --help` explains it.

| Module | What it is |
|---|---|
| `syn-spi` | The interface a command implements (`SynCommand`), and the types it is handed. Write your own commands against this. |
| `syn-core` | Helpers commands share: running processes, reading settings (`.syn/config.properties`), output, locks. |
| `syn-cli` | The head: finds commands, enforces each command's tier, runs one. |
| `syn-plugin-kit` | The generic commands: dead-link check for a knowledge base, a command scaffold, convention and shell-ceiling checks, a script catalog, a guard that keeps private strings and secrets out of public repositories, and tools over local Claude Code sessions. |
| [`launcher/`](launcher/README.md) | What a repository copies to use syn: a `pom.xml` that fetches the above from Maven Central, and a small `syn` script. |

Plugins are directories (`<name>/current/libs/*.jar`) found under the plugins directory; each gets its own class loader. Two plugins
offering the same command name is an error until `.syn/config.properties` says which wins (`override.<command>=<plugin>`), and
`syn --list-plugins` shows what is there. Every setting has a default, so a repository needs no configuration to start.

Java 25. Build: `mvn verify`.
