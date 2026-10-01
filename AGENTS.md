# Credenza

[`README.md`](README.md) describes the project and its architecture.
Read the relevant source package and its mirrored tests before making changes.

## Functional Design

- Keep AWS-compatible configuration parsing in `config/reader/`; decoders operate on immutable section data.
- Keep SSO cache I/O, JSON decoding, and their supporting types together in `config/sso/`.
- Bind configuration keys to their value types through `Field[A]`, and construct domain values through their validated constructors.
- Compose independent validation with Cats; use sequential validation when a lookup depends on a decoded value.
- Keep provider-support policy in the SSO decoders, separate from configuration-key metadata.
- Use synthetic configuration fixtures. Keep credential values and raw configuration text out of errors, logs, and diagnostic representations.

## Build and Code Style

- Keep the build as a single sbt project. Add dependencies and abstractions when the current work needs them.
- Put project settings in `build.sbt` and sbt plugins in `project/plugins.sbt`.
- Use Scalafmt for both source and build files; follow the surrounding code for conventions the formatter does not cover.
- Update the README as working features are added.

## Testing and Validation

Use an sbt runner and a JDK; the build has been verified with JDK 25.
Scala and sbt versions are pinned in `build.sbt` and `project/build.properties`.

Start with the smallest affected test suite during development.
- Compile with `sbt compile`.
- Use `sbt test` for incremental testing: sbt 2 runs suites that are new, previously failed, or affected by changes.
- Format source and build files with `sbt --batch "; scalafmtSbt; scalafmtAll"`.

Before opening a PR with code or build changes, run:

```sh
sbt --batch "; scalafmtSbtCheck; scalafmtCheckAll; compile; testFull"
```

`testFull` runs the complete suite without reusing cached test results.
For documentation-only changes, check links and run `git diff --check`.
Review the diff and report which checks ran.

## Agent Configuration

- Shared agent configuration belongs in `AGENTS.md` and `.agents/`.
- Vendor-specific configuration such as `.claude/`, `.cursor/`, and `.opencode/` stays local and gitignored.
