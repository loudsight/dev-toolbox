# syn launcher

syn is a task CLI for a repository: tiered, scriptable commands (`syn catalog` lists them) that an agent or a person runs by
name. This directory is all a repository needs to use it. Nothing here becomes part of your product.

**Needs:** Java 25 and Maven on the PATH, and a git repository.

1. Copy `pom.xml` and `syn.tmpl` into your repository (anywhere; a `tools/syn/` directory works), and save `syn.tmpl` as `syn`
   with `chmod +x`. Ignore `.syn-cache/` in git.
2. Run `./syn catalog`. The first run has Maven fetch syn and `syn-plugin-kit` (the generic commands) from Maven Central into
   `.syn-cache/`. Delete `.syn-cache/` to update.
3. Settings live in `.syn/config.properties` at your repository root (committed, so changes are reviewed). Every key has a
   default, so the file can be empty or absent. The keys by command are in each command's class comment in `syn-plugin-kit`:
   `kb.*` (`kb-check-links`), `scaffold.*` (`script-create-scaffold`), `catalog.*` (`script-list-catalog`), `conventions.*`
   (`script-check-conventions`), `ceiling.*` (`script-check-ceiling`), `publish.*` (`publish-install-hooks`) and `sessions.*`
   (`session-list-open`). `plugins.dir` moves the plugin directory. Any key can be overridden for one run with
   `-Dsyn.<key>=...`.

## Your own commands

A command is a class implementing `com.loudsight.syn.spi.SynCommand` (from `syn-spi`), registered in
`META-INF/services/com.loudsight.syn.spi.SynCommand`. `syn script-create-scaffold NAME --plugin P` writes one, with the
conventions `syn script-check-conventions` enforces. Publish the jar (or `mvn install` it) and add an `<execution>` for it to
`pom.xml`, as in the commented example; it is laid out as `plugins/NAME/current/libs`, which is where syn looks.

Two plugins offering a command of the same name is an error until your configuration says which wins:
`override.<command>=<plugin>`. `syn --list-plugins` shows what each plugin provides and any problem.
