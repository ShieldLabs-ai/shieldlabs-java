# Contributing

Thanks for helping improve the ShieldLabs Java SDK.

## Build and test

You need JDK 11 or later and Maven 3.6.3 or later.

```bash
mvn verify
```

This compiles with `-Xlint:all -Werror`, runs the tests, checks line coverage (at least 90%) and
builds the source and javadoc jars. Without a local JDK, use Docker:

```bash
docker run --rm -v "$PWD":/src -w /src maven:3.9-eclipse-temurin-17 mvn -B verify
```

The example in `examples/httpserver` depends on the installed SDK:

```bash
mvn install -DskipTests
mvn -f examples/httpserver/pom.xml verify
```

## Guidelines

- Keep the public API small and the runtime dependencies to Jackson Databind only. Public methods
  need javadoc.
- Tests must not need network access: use `TestServer` (a local `com.sun.net.httpserver` server)
  and `FakeTimer` for anything that waits.
- The files in `src/test/resources/fixtures` are shared test fixtures that every ShieldLabs server
  SDK passes. Do not edit them by hand; open an issue if one looks wrong. They are synced from
  `contract/` in shieldlabs-openapi: `contract-sync.json` maps each file,
  `.shieldlabs-contract.lock` records the release, CI runs
  `python3 scripts/sync_contract.py --check`, and the `contract-sync.yml` workflow opens a pull
  request when a new release changes them.
- Documentation and comments use plain, technical English: "risk signals", the three risk bands
  (trusted 0-29, suspicious 30-59, dangerous 60-100), colons or parentheses instead of dashes.
- Use conventional commit messages (`feat: ...`, `fix: ...`, `test: ...`, `docs: ...`, `ci: ...`) and
  add a line to `CHANGELOG.md` for user-visible changes.

## Releases

Maintainers release by pushing a tag `vX.Y.Z` that matches the version in `pom.xml`. The release
workflow builds and tests the tag, signs the artifacts, publishes them to Maven Central and creates
the GitHub release. Re-running it for the same tag is safe: a version that is already on Maven
Central is not uploaded again.

## Reporting problems

Open an issue with the SDK version, the Java version and a minimal example. For account questions,
write to [contact@shieldlabs.ai](mailto:contact@shieldlabs.ai).
