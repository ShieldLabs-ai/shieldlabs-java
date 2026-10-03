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

## OpenAPI contract updates

Install the pinned generator dependency with `python3 -m pip install -r scripts/requirements.txt`.
After updating `resources/shieldlabs-api.yaml`, run `python3 scripts/generate-wire.py`, then
`python3 scripts/generate-wire.py --check` and `python3 scripts/check-wire-drift.py`.
The latter compiles the supported client against renamed and retyped fields and an optional additive
field. Use `--docker` if Maven is available only in the documented container. Run `mvn verify` and
`bash scripts/verify-package.sh` afterward.

Run `python3 scripts/test-wire-generation.py` for operation-boundary regression checks. New required
parameters on either consumed operation and a changed Management profile route require an adapter
update. Optional new query/header parameters remain compatible. Ping and scored webhook envelope
fields must retain compatible names and declared kinds because the runtime shares envelope parsing.

`WireModels.java` contains generated, package-private schema views. `WireValue` keeps the original
JSON value behind a declared-kind wrapper. Normalization requires the matching kind, so type drift
fails compilation without introducing strict deserialization of old or unexpected server values.
Enums remain strings on responses; unknown fields remain in `raw()`. New wire shapes or schema
constructs not supported by the narrow generator fail explicitly and need an adapter change.
The `generated/` directory is a separate reference client, not the supported runtime.

## Code guidelines

- Keep the public API small and the runtime dependencies to Jackson Databind only. Public methods
  need javadoc.
- Tests must not need network access: use `TestServer` (a local `com.sun.net.httpserver` server)
  and `FakeTimer` for anything that waits.
- The files in `src/test/resources/fixtures` are shared test fixtures that every ShieldLabs server
  SDK passes. Do not edit them by hand; open an issue if one looks wrong.
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
