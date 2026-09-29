# Credenza

Credenza is a Scala 3 project for brokering temporary AWS role credentials to local development tools.

[`README.md`](README.md) is the project overview and development entry point.
The build definitions and formatter configuration are authoritative for tool versions and settings.

## Build and Code Style

- Keep the build as a single sbt project. Add dependencies and abstractions when the current work needs them.
- Put project settings in `build.sbt` and sbt plugins in `project/plugins.sbt`.
- Use the pinned Scala 3 and sbt 2 versions. The sbt version lives in `project/build.properties`.
- Use Scalafmt for both source and build files; follow the surrounding code for conventions the formatter does not cover.
- Update the README as working features are added.

## Testing and Validation

For code and build changes, run:

```sh
sbt --batch "; scalafmtSbtCheck; scalafmtCheckAll; compile"
```

Run relevant tests when available, starting with the smallest affected suite.
For documentation-only changes, check local links and run `git diff --check`.
Review the diff and report which checks ran.

## Agent Configuration

- Shared agent configuration belongs in `AGENTS.md` and `.agents/`.
- Vendor-specific configuration such as `.claude/`, `.cursor/`, and `.opencode/` stays local and gitignored.
