# Credenza

Credenza is a local AWS credential broker for development tools.
The goal is to reuse an existing AWS login across Docker builds, containers, and IDEs, with credential caching and refresh handled in one place.

The project is at the initial setup stage, with the sbt build and Scalafmt configuration in place.

## Development

Development uses Scala 3 and sbt 2.
Versions are pinned in [`build.sbt`](build.sbt) and [`project/build.properties`](project/build.properties).
You need an sbt runner and a JDK; the build has been verified with JDK 25.

Compile the project:

```sh
sbt compile
```

Run the tests:

```sh
sbt test
```

Format Scala sources and build files using [`.scalafmt.conf`](.scalafmt.conf):

```sh
sbt --batch "; scalafmtSbt; scalafmtAll"
```

Check formatting and compilation:

```sh
sbt --batch "; scalafmtSbtCheck; scalafmtCheckAll; compile"
```

Contributor guidance is in [`AGENTS.md`](AGENTS.md).
