# Credenza

[`README.md`](README.md) describes the project and its architecture.
Read the relevant source package and its mirrored tests before making changes.
For integration work, also read [src/it/README.md](src/it/README.md).

## Functional Design

- Keep AWS-compatible configuration parsing in `config/reader/`; decoders operate on immutable section data.
- Keep SSO cache I/O, JSON decoding, and their supporting types together in `config/sso/`.
- Bind configuration keys to their value types through `Field[A]`, and construct domain values through their validated constructors.
- Compose independent validation with Cats; use sequential validation when a lookup depends on a decoded value.
- Keep provider-support policy in the SSO decoders, separate from configuration-key metadata.
- Use synthetic configuration fixtures.
  Keep credential values and raw configuration text out of errors, logs, and diagnostic representations.

## Build and Code Style

- Keep Credenza Native-only.
- Put application settings and dependencies in `build.sbt` and sbt plugins in `project/plugins.sbt`.
- Keep integration configuration declarative; MUnit resources own fixture lifetimes.
  Discovery must not acquire fixtures or change standard sbt test-selection behavior.
- Use Scalafmt for both source and build files; follow the surrounding code for conventions the formatter does not cover.
- Update the README as working features are added.
- Preserve unaffected documentation wording and use semantic line breaks rather than fixed-width wrapping.

## Testing and Validation

Use an sbt runner and a JDK; the build has been verified with JDK 25.
Scala and sbt versions are pinned in `build.sbt` and `project/build.properties`.
Use the existing toolchain or report missing tools for the user to install.

Start with the smallest affected test suite during development.
- Compile with `sbt compile`.
- Use `sbt test` for incremental testing: sbt 2 runs suites that are new, previously failed, or affected by changes.
- Format source and build files with `sbt --batch "; scalafmtSbt; scalafmtAll"`.

Before opening a PR with code or build changes, run:

```sh
sbt --batch "; scalafmtSbtCheck; scalafmtCheckAll; compile; testFull"
```

`testFull` runs the complete suite without reusing cached test results.
For SSO HTTP or Native build changes, run `sbt integrationTest` as well.
For documentation-only changes, check links and run `git diff --check`.
Review the diff and report which checks ran.

## Agent Configuration

- Shared agent configuration belongs in `AGENTS.md` and `.agents/`.
- Vendor-specific configuration such as `.claude/`, `.cursor/`, and `.opencode/` stays local and gitignored.
